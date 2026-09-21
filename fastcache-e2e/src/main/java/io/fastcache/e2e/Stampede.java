package io.fastcache.e2e;

import io.fastcache.bench.Stats;
import io.fastcache.engine.core.ShardedStorageEngine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Phase 13 - N concurrent callers, one missing key, one expensive loader.
 *
 * <p>Measured two ways per arm, because the interesting question is not "can this be done" but "what do
 * you get without writing it yourself":
 *
 * <ul>
 *   <li><b>native</b> - whatever coalescing the cache provides on its own. Caffeine has
 *       {@code Cache.get(key, loader)}; the embedded engine has {@code readThrough}; the sidecar and
 *       Redis have nothing reachable from Java (see §3a of the results - {@code OP_REFRESH_LEASE} exists
 *       in the protocol and is used by the Python client, but no Java client implements it).
 *   <li><b>app</b> - the ten lines any team writes: a {@code ConcurrentHashMap} of in-flight futures.
 *       Applied identically to every arm, so Redis is not made to look worse than it is by pretending
 *       the capability is unavailable to it.
 * </ul>
 *
 * <p>Reporting only the native column would overstate FastCache. Reporting only the app column would hide
 * that two of the arms need no code at all. Both are reported.
 */
public final class Stampede {

    /** The ten lines. Deliberately minimal - this is what a team writes, not a library. */
    private static final ConcurrentHashMap<String, CompletableFuture<LargeResponse>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private Stampede() {
    }

    public record Result(String arm, String mode, int concurrency, long loaderRuns, long correct,
                         long errors, double firstResponseMillis, double totalMillis,
                         Stats latencyMicros) {

        public String toJson() {
            return "{\"arm\":\"" + arm + "\",\"mode\":\"" + mode + "\",\"concurrency\":" + concurrency
                    + ",\"loaderRuns\":" + loaderRuns
                    + ",\"correct\":" + correct
                    + ",\"errors\":" + errors
                    + ",\"firstResponseMillis\":" + String.format("%.2f", firstResponseMillis)
                    + ",\"totalMillis\":" + String.format("%.2f", totalMillis)
                    + ",\"p50Millis\":" + String.format("%.2f", latencyMicros.p50() / 1000.0)
                    + ",\"p95Millis\":" + String.format("%.2f", latencyMicros.p95() / 1000.0)
                    + ",\"p99Millis\":" + String.format("%.2f", latencyMicros.p99() / 1000.0)
                    + ",\"maxMillis\":" + String.format("%.2f", latencyMicros.max() / 1000.0) + "}";
        }
    }

    /**
     * Fires {@code concurrency} simultaneous requests for one key that is guaranteed absent.
     *
     * @param mode {@code native}, {@code app} or {@code none}
     */
    public static Result run(DocumentService service, ShardedStorageEngine embeddedEngine,
                             Object caffeineCache, String arm, String mode, int concurrency,
                             String key, int payloadBytes, int seed, long missCostMillis)
            throws InterruptedException {

        LongAdder loaderRuns = new LongAdder();
        LongAdder correct = new LongAdder();
        LongAdder errors = new LongAdder();
        long[] latencies = new long[concurrency];
        AtomicLong firstResponseNanos = new AtomicLong(Long.MAX_VALUE);

        Supplier<LargeResponse> loader = () -> {
            loaderRuns.increment();
            try {
                Thread.sleep(missCostMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LargeResponse.of(key, payloadBytes, seed);
        };

        // All callers released together. Staggering them would measure a queue, not a stampede.
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, 512));

        long started;
        try {
            for (int i = 0; i < concurrency; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        long t0 = System.nanoTime();
                        LargeResponse value = switch (mode) {
                            case "app" -> coalesced(key, service, loader);
                            case "native" -> nativeLoad(service, key, loader);
                            default -> uncoalesced(service, key, loader);
                        };
                        long elapsed = System.nanoTime() - t0;
                        latencies[index] = elapsed;
                        firstResponseNanos.accumulateAndGet(elapsed, Math::min);
                        if (value != null && value.matches(key, payloadBytes, seed)) {
                            correct.increment();
                        }
                    } catch (Exception e) {
                        errors.increment();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            started = System.nanoTime();
            go.countDown();
            done.await();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
            IN_FLIGHT.remove(key);
        }

        double totalMillis = (System.nanoTime() - started) / 1e6;
        Stats latency = Stats.of(latencies, concurrency).toMicros();
        return new Result(arm, mode, concurrency, loaderRuns.sum(), correct.sum(), errors.sum(),
                firstResponseNanos.get() / 1e6, totalMillis, latency);
    }

    /** Application-level coalescing: one future per key, everyone else joins it. */
    private static LargeResponse coalesced(String key, DocumentService service,
                                           Supplier<LargeResponse> loader) {
        LargeResponse cached = service.peek(key);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<LargeResponse> future = IN_FLIGHT.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> {
                    LargeResponse loaded = loader.get();
                    service.populate(k, loaded);
                    return loaded;
                }));
        try {
            return future.join();
        } finally {
            IN_FLIGHT.remove(key, future);
        }
    }

    /**
     * Whatever the backend offers by itself.
     *
     * <p>For the cross-process arms this is the same as no coalescing, and that is the finding rather
     * than an omission: there is no Java client path to {@code OP_REFRESH_LEASE}.
     */
    private static LargeResponse nativeLoad(DocumentService service, String key,
                                            Supplier<LargeResponse> loader) {
        return service.nativeReadThrough(key, loader);
    }

    private static LargeResponse uncoalesced(DocumentService service, String key,
                                             Supplier<LargeResponse> loader) {
        LargeResponse cached = service.peek(key);
        if (cached != null) {
            return cached;
        }
        LargeResponse loaded = loader.get();
        service.populate(key, loaded);
        return loaded;
    }
}
