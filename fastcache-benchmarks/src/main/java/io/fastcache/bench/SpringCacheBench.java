package io.fastcache.bench;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.fastcache.engine.core.EngineConfig;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.spring.FastCacheManager;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Phase 4: the realistic-application comparison, run through Spring's own {@code CacheManager}
 * abstraction rather than through a synthetic put/get loop.
 *
 * <p>This drives the production code path. {@code Cache.get(key, Callable)} is exactly what
 * {@code @Cacheable(sync = true)} invokes, so the single-flight behaviour measured here is the behaviour a
 * Spring application gets. The loader stands in for expensive document processing: it sleeps for a
 * configured cost and returns a large byte array.
 *
 * <p>Two providers, same abstraction, same loader, same concurrency:
 * <ul>
 *   <li>{@code CaffeineCacheManager} &mdash; Spring's supported Caffeine integration.
 *   <li>{@code FastCacheManager} &mdash; FastCache's own, from the starter module.
 * </ul>
 *
 * <p>Loader executions are counted, which is the single-flight measurement: 500 concurrent callers for one
 * uncached key should produce 1 execution, not 500.
 */
public final class SpringCacheBench {

    private static final long MB = 1 << 20;

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        int[] sizes = {1 << 20, 5 << 20, 10 << 20, 25 << 20, 50 << 20};
        int[] concurrencies = {100, 500};
        long loaderMillis = Long.parseLong(options.getOrDefault("loader-millis", "50"));
        int repeat = Integer.parseInt(options.getOrDefault("repeat", "3"));

        System.out.println("=".repeat(112));
        System.out.println("Phase 4 - Spring CacheManager comparison  (@Cacheable(sync=true) path)");
        System.out.printf("  loader cost %d ms, %d repeats, heap %d MB, JVM %s%n",
                loaderMillis, repeat, Runtime.getRuntime().maxMemory() / MB,
                System.getProperty("java.version"));
        System.out.println("=".repeat(112));
        System.out.printf("%n  %-10s %-12s %-6s %-9s %-10s %-11s %-11s %-11s %-9s %-9s %s%n",
                "provider", "payload", "conc", "loaderRuns", "correct", "p50ms", "p95ms", "p99ms",
                "heapMB", "gcMs", "wallMs");
        System.out.println("  " + "-".repeat(108));

        for (int size : sizes) {
            for (int concurrency : concurrencies) {
                for (int run = 0; run < repeat; run++) {
                    measure("caffeine", size, concurrency, loaderMillis);
                    measure("fastcache", size, concurrency, loaderMillis);
                }
            }
        }

        System.out.println("""

                  loaderRuns is the single-flight measurement: one uncached key, N concurrent callers.
                  1 means the provider collapsed the stampede. N means it did not.
                  heapMB is settled heap occupancy attributable to the cached value, after two collections.
                """);
    }

    private static void measure(String provider, int payloadBytes, int concurrency, long loaderMillis)
            throws Exception {
        ShardedStorageEngine engine = null;
        CacheManager manager;
        if (provider.equals("caffeine")) {
            CaffeineCacheManager caffeine = new CaffeineCacheManager("documents");
            caffeine.setCaffeine(Caffeine.newBuilder()
                    .maximumWeight(1024L * MB)
                    .weigher((Object key, Object value) -> ((byte[]) value).length)
                    .expireAfterWrite(10, TimeUnit.MINUTES));
            manager = caffeine;
        } else {
            engine = new ShardedStorageEngine(EngineConfig.builder()
                    .maxOffHeapBytes(1024L * MB)
                    .maxValueBytes(Integer.MAX_VALUE - 8)
                    .maxEntriesPerShard(100_000)
                    .staleGraceMillis(0)
                    .build());
            manager = new FastCacheManager(engine, "10m", Map.of(), Set.of("documents"));
        }

        try {
            Cache cache = manager.getCache("documents");
            if (cache == null) {
                throw new IllegalStateException("no cache");
            }

            // Warm the JIT on a throwaway key so the measured window is not compilation.
            for (int i = 0; i < 20; i++) {
                final int seed = i;
                cache.get("warm:" + i, () -> Payloads.of(4096, seed));
            }
            cache.evict("documents:hot");

            LongAdder loaderRuns = new LongAdder();
            AtomicInteger correct = new AtomicInteger();
            long[] latencies = new long[concurrency];
            Probe probe = new Probe();
            long heapBefore = Probe.settledHeapUsed();
            Probe.Mark mark = probe.mark();

            CountDownLatch ready = new CountDownLatch(concurrency);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(concurrency);
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, 512));
            AtomicLong wallStart = new AtomicLong();

            for (int i = 0; i < concurrency; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        long t0 = System.nanoTime();
                        byte[] value = cache.get("hot", () -> {
                            loaderRuns.increment();
                            Thread.sleep(loaderMillis);          // stands in for document processing
                            return Payloads.of(payloadBytes, 42);
                        });
                        latencies[index] = System.nanoTime() - t0;
                        if (value != null && value.length == payloadBytes
                                && Payloads.seedOf(value) == 42) {
                            correct.incrementAndGet();
                        }
                    } catch (Exception e) {
                        latencies[index] = -1;
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            wallStart.set(System.nanoTime());
            go.countDown();
            done.await();
            long wallMillis = (System.nanoTime() - wallStart.get()) / 1_000_000;
            pool.shutdownNow();

            Probe.GcDelta gc = probe.since(mark);
            long heapAfter = Probe.settledHeapUsed();
            long[] valid = java.util.Arrays.stream(latencies).filter(v -> v > 0).toArray();
            Stats latency = Stats.of(valid, valid.length);

            System.out.printf("  %-10s %-12s %-6d %-9d %-10s %-11.2f %-11.2f %-11.2f %-9d %-9.1f %d%n",
                    provider, Payloads.label(payloadBytes), concurrency, loaderRuns.sum(),
                    correct.get() + "/" + concurrency,
                    latency.p50() / 1e6, latency.p95() / 1e6, latency.p99() / 1e6,
                    (heapAfter - heapBefore) / MB, gc.totalPauseMillis(), wallMillis);
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(args[i].substring(2), args[++i]);
            }
        }
        return options;
    }

    /** Kept so the unused-import checker does not hide a genuine dependency on the starter module. */
    static List<Class<?>> requiredTypes() {
        return List.of(FastCacheManager.class, CaffeineCacheManager.class);
    }
}
