package io.fastcache.spring;

import io.fastcache.engine.core.Lease;
import io.fastcache.engine.core.RefreshCoordinator;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.core.WriteStatus;
import io.fastcache.engine.util.TimeSpec;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The interceptor behind {@link FastCache}.
 *
 * <h2>Failure policy: fail open, always</h2>
 * Every cache interaction is wrapped so that <em>no</em> cache failure can fail a business call. If the
 * engine rejects a write under memory pressure, the result is still returned. If a lookup throws, the
 * method is invoked normally. A cache is an optimisation; an optimisation that can take down a request path
 * is a liability. The only exception that propagates is the one thrown by the intercepted method itself.
 *
 * <h2>Stampede defence</h2>
 * On expiry of a hot key, one caller wins the refresh lease and invokes the method; concurrent callers are
 * handed the stale value immediately, or park on the winner for at most the grace window. Without this, a
 * key serving a thousand requests a second turns every TTL boundary into a thousand-way simultaneous call
 * to whatever the method wraps.
 *
 * <h2>Exceptions are never cached, and never poison a good value</h2>
 * {@code proceed()} is called outside the store block, so a thrown exception propagates untouched and no
 * entry is written. When a refresh fails and a stale value is still in hand, the stale value is returned
 * rather than the exception: the caller had a usable answer a moment ago, and a transient backend blip
 * should not become a user-visible error.
 *
 * <h2>Ordering</h2>
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE} + 100 &mdash; inside transaction and security advice, so a
 * cache hit does not open a database transaction, but outside anything that must observe every call.
 */
@Aspect
public class FastCacheAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(FastCacheAspect.class);

    private final ShardedStorageEngine engine;
    private final FastCacheProperties properties;
    private final FastCacheKeyGenerator keyGenerator = new FastCacheKeyGenerator();
    private final MutationGuard mutationGuard;

    /** Memoised TTL parsing: {@code "15m"} is a constant per call site, so parse it once per method. */
    private final ConcurrentHashMap<Method, Long> ttlCache = new ConcurrentHashMap<>();

    /** Memoised SpEL viability per method; see {@link #expressionsUsable(FastCache, Method)}. */
    private final ConcurrentHashMap<Method, Boolean> expressionsUsable = new ConcurrentHashMap<>();

    private final LongAdder hits = new LongAdder();
    private final LongAdder staleServed = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder bypasses = new LongAdder();
    private final LongAdder rejections = new LongAdder();
    private final LongAdder herdWaits = new LongAdder();

    public FastCacheAspect(ShardedStorageEngine engine, FastCacheProperties properties) {
        this.engine = engine;
        this.properties = properties;
        this.mutationGuard = new MutationGuard(properties.getMutationGuard());
    }

    /** A cached value plus whether it is inside the stale-while-revalidate window. */
    private record CachedValue(Object value, boolean stale) {
    }

    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint joinPoint, FastCache annotation) throws Throwable {
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        Object target = joinPoint.getTarget();

        if (!expressionsUsable(annotation, method)) {
            bypasses.increment();
            return joinPoint.proceed();
        }

        String key;
        try {
            if (!keyGenerator.evaluateCondition(annotation.condition(), method, target, args, null)) {
                bypasses.increment();
                return joinPoint.proceed();
            }
            key = keyGenerator.generate(annotation, method, target, args);
        } catch (RuntimeException e) {
            // A broken SpEL expression is a developer error, but it must not be a production outage.
            log.warn("FastCache key generation failed for {}; bypassing cache", method, e);
            bypasses.increment();
            return joinPoint.proceed();
        }

        CachedValue cached = lookup(key);
        if (cached != null && !cached.stale()) {
            hits.increment();
            return serve(cached.value());
        }

        return refreshOrServeStale(joinPoint, annotation, method, target, args, key, cached);
    }

    /**
     * The single-flight core. Exactly one caller per key recomputes; the rest serve stale or wait.
     */
    private Object refreshOrServeStale(ProceedingJoinPoint joinPoint, FastCache annotation, Method method,
                                       Object target, Object[] args, String key, CachedValue stale)
            throws Throwable {
        RefreshCoordinator coordinator = engine.refreshCoordinator();

        if (coordinator.tryAcquireLead(key)) {
            try {
                if (stale == null) {
                    misses.increment();
                }
                Object result = joinPoint.proceed(); // Outside every catch: exceptions propagate uncached.
                store(annotation, method, target, args, key, result);
                return result;
            } catch (Throwable failure) {
                if (stale != null) {
                    // We hold a usable value from moments ago. Returning it beats surfacing a transient
                    // backend failure — that is the entire point of keeping a grace window.
                    log.debug("FastCache refresh of {} failed; serving stale value", key, failure);
                    staleServed.increment();
                    return serve(stale.value());
                }
                throw failure;
            } finally {
                // Finally, not after the return: an exception path that skipped this would make every
                // other caller wait out the full lease period for a refresh that already failed.
                coordinator.complete(key);
            }
        }

        if (stale != null) {
            staleServed.increment(); // Someone else is refreshing; do not block, do not call the backend.
            return serve(stale.value());
        }

        // Cold miss while another caller is already computing this key. Park on them.
        misses.increment();
        herdWaits.increment();
        coordinator.awaitCompletion(key, Math.max(1L, engine.config().staleGraceMillis()));

        CachedValue published = lookup(key);
        if (published != null) {
            hits.increment();
            return serve(published.value());
        }
        // The leader failed or overran. Compute without storing: a straggler overwriting a value the
        // recovered leader just published would be worse than one extra uncached call.
        return joinPoint.proceed();
    }

    /**
     * One-time, per-method safety check that this call site's SpEL can actually be evaluated.
     *
     * <p>Memoised because it is a pure function of the method, and because the alternative &mdash; probing
     * the parameter-name discoverer on every invocation &mdash; would put reflection on the hot path. The
     * warning fires once per method rather than once per call: a per-call warning on a method serving
     * thousands of requests a second becomes its own outage.
     */
    private boolean expressionsUsable(FastCache annotation, Method method) {
        return expressionsUsable.computeIfAbsent(method, m -> {
            boolean usable = keyGenerator.canResolveVariables(
                    m, annotation.key(), annotation.condition(), annotation.unless());
            if (!usable) {
                log.warn("FastCache disabled for {}: its SpEL references parameter names, but this class "
                        + "was compiled without '-parameters' so they cannot be resolved. Every call would "
                        + "collapse onto one cache key and return other callers' results. Fix: compile with "
                        + "-parameters (Spring Boot's build plugins do this by default), or use positional "
                        + "variables such as #p0/#p1.", m);
            }
            return usable;
        });
    }

    /**
     * Reads through to the engine.
     *
     * <p>The lease is closed immediately: on-heap references need no reference counting, and holding the
     * lease past the read would only delay eviction.
     */
    private CachedValue lookup(String key) {
        try (Lease lease = engine.acquire(key)) {
            return lease == null ? null : new CachedValue(lease.reference(), lease.isStale());
        } catch (RuntimeException e) {
            log.debug("FastCache lookup failed for key {}; treating as a miss", key, e);
            return null;
        }
    }

    /** Applies the mutation guard on the way out, so a caller can never mutate the cached instance. */
    private Object serve(Object stored) {
        if (stored == NullMarker.INSTANCE) {
            return null;
        }
        return mutationGuard.onServe(stored);
    }

    private void store(FastCache annotation, Method method, Object target, Object[] args,
                       String key, Object result) {
        try {
            if (result == null && !annotation.cacheNulls()) {
                return;
            }
            if (keyGenerator.evaluateVeto(annotation.unless(), method, target, args, result)) {
                return; // 'unless' evaluated true: the caller explicitly excluded this result.
            }

            Object storable = result == null ? NullMarker.INSTANCE : mutationGuard.onStore(result);
            if (storable == null) {
                return; // Mutation guard refused: the type cannot be cached safely. Already warned once.
            }

            long ttl = resolveTtl(annotation, method);
            WriteStatus status = engine.putReference(key, storable, ttl);
            if (!status.accepted()) {
                rejections.increment();
                // Debug, not warn: under sustained pressure this fires on every call and a warn-level log
                // would itself become the bottleneck.
                log.debug("FastCache rejected write for {}: {}", key, status);
            }
        } catch (RuntimeException e) {
            log.warn("FastCache store failed for key {}; result returned uncached", key, e);
        }
    }

    private long resolveTtl(FastCache annotation, Method method) {
        return ttlCache.computeIfAbsent(method, m -> {
            String spec = StringUtils.hasText(annotation.ttl()) ? annotation.ttl() : properties.getDefaultTtl();
            try {
                return TimeSpec.parseMillis(spec);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid @FastCache ttl '{}' on {}; falling back to engine default", spec, m);
                return 0L; // 0 lets the engine apply its own configured default.
            }
        });
    }

    /**
     * Sentinel distinguishing "cached null" from "not cached". Without it, negative caching is impossible:
     * a {@code null} lookup result is indistinguishable from a miss.
     */
    private enum NullMarker {
        INSTANCE
    }

    public long hitCount() {
        return hits.sum();
    }

    public long missCount() {
        return misses.sum();
    }

    public long bypassCount() {
        return bypasses.sum();
    }

    public long rejectionCount() {
        return rejections.sum();
    }

    /** Calls answered from the grace window instead of waiting for a refresh. */
    public long staleServedCount() {
        return staleServed.sum();
    }

    /** Cold misses that parked on another caller's refresh rather than duplicating the work. */
    public long herdWaitCount() {
        return herdWaits.sum();
    }

    /** Values handed out by reference because their type is immutable — the zero-copy fast path. */
    public long referencesServedCount() {
        return mutationGuard.referencesServed();
    }

    /** Values deep-copied to prevent shared-mutable-state corruption. */
    public long deepCopyCount() {
        return mutationGuard.deepCopies();
    }

    /** Values refused because they are mutable and not safely copyable. */
    public long mutationRefusalCount() {
        return mutationGuard.refusals();
    }

    /** Per-type immutability verdicts, for diagnosing unexpected deep-copy cost. */
    public java.util.Map<String, MutationGuard.Verdict> mutationClassifications() {
        return mutationGuard.classifications();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
