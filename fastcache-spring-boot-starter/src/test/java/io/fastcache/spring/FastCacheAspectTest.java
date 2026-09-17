package io.fastcache.spring;

import io.fastcache.engine.core.ShardedStorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code @FastCache} annotation against a real Spring context.
 *
 * <p>Counters are static because {@code proxyTargetClass=true} produces a CGLIB subclass instantiated via
 * Objenesis — its own instance fields are never initialised, so reading one through the proxy reference
 * returns null. That is a property of Spring proxies, not of the cache, but it bites every test that tries
 * to count invocations through an injected bean.
 */
class FastCacheAspectTest {

    public record Embedding(String prompt, int dimensions) { }

    public static class PromptService {
        static final AtomicInteger calls = new AtomicInteger();
        static volatile CountDownLatch gate;

        @FastCache(ttl = "15m")
        public Embedding embed(String prompt) {
            calls.incrementAndGet();
            return new Embedding(prompt, prompt.length());
        }

        @FastCache(ttl = "15m", key = "#tenant + ':' + #query", unless = "#result.isEmpty()")
        public List<String> search(String tenant, String query) {
            calls.incrementAndGet();
            return query.isBlank() ? List.of() : List.of(tenant + "/" + query);
        }

        @FastCache(ttl = "15m", condition = "#id > 100")
        public String lookup(int id) {
            calls.incrementAndGet();
            return "row-" + id;
        }

        @FastCache(ttl = "15m")
        public List<String> mutableResult(String key) {
            calls.incrementAndGet();
            return new ArrayList<>(List.of("original"));
        }

        @FastCache(ttl = "15m")
        public String failing(String key) {
            calls.incrementAndGet();
            throw new IllegalStateException("downstream is down");
        }

        @FastCache(ttl = "400ms")
        public String slow(String key) {
            calls.incrementAndGet();
            try {
                if (gate != null) {
                    gate.await(15, TimeUnit.SECONDS);
                }
                Thread.sleep(100);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            return "computed-" + calls.get();
        }
    }

    @Configuration
    static class TestConfig {
        @Bean
        PromptService promptService() {
            return new PromptService();
        }
    }

    private AnnotationConfigApplicationContext context;
    private PromptService service;

    @BeforeEach
    void start() {
        PromptService.calls.set(0);
        PromptService.gate = null;
        context = new AnnotationConfigApplicationContext();
        context.register(FastCacheAutoConfiguration.class, TestConfig.class);
        context.refresh();
        service = context.getBean(PromptService.class);
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("auto-configuration registers the engine, aspect and facade with no user config")
    void zeroConfigWiring() {
        assertNotNull(context.getBean(ShardedStorageEngine.class));
        assertNotNull(context.getBean(FastCacheAspect.class));
        assertNotNull(context.getBean(FastCacheOperations.class));
    }

    @Test
    @DisplayName("a repeated call is served from cache")
    void cachesRepeatCalls() {
        Embedding first = service.embed("hello");
        Embedding second = service.embed("hello");

        assertEquals(1, PromptService.calls.get());
        assertSame(first, second, "an immutable record is served by reference, with no copy");
    }

    @Test
    @DisplayName("distinct arguments are distinct keys")
    void distinguishesArguments() {
        service.embed("a");
        service.embed("b");
        assertEquals(2, PromptService.calls.get());
    }

    @Test
    @DisplayName("a SpEL key expression is honoured")
    void spelKey() {
        service.search("acme", "gpu");
        service.search("acme", "gpu");
        assertEquals(1, PromptService.calls.get());

        service.search("other", "gpu");
        assertEquals(2, PromptService.calls.get(), "the tenant is part of the key");
    }

    @Test
    @DisplayName("'unless' keeps a vetoed result out of the cache")
    void unlessVetoes() {
        service.search("acme", "  ");   // blank -> empty result -> vetoed
        service.search("acme", "  ");
        assertEquals(2, PromptService.calls.get(),
                "an empty result must be recomputed, not cached");
    }

    @Test
    @DisplayName("'condition' bypasses the cache when false")
    void conditionBypasses() {
        service.lookup(5);
        service.lookup(5);
        assertEquals(2, PromptService.calls.get(), "condition false -> not cached");

        service.lookup(500);
        service.lookup(500);
        assertEquals(3, PromptService.calls.get(), "condition true -> cached");
    }

    @Test
    @DisplayName("exceptions propagate and are never cached")
    void exceptionsAreNotCached() {
        assertThrows(IllegalStateException.class, () -> service.failing("k"));
        assertThrows(IllegalStateException.class, () -> service.failing("k"));

        assertEquals(2, PromptService.calls.get(),
                "caching a failure would turn a transient blip into a 15-minute outage");
    }

    @Test
    @DisplayName("a mutable result cannot be corrupted by a caller")
    void mutationGuardProtectsCallers() {
        List<String> first = service.mutableResult("k");
        first.add("MUTATED BY CALLER");

        List<String> second = service.mutableResult("k");
        assertEquals(1, second.size(), "one caller's edit must not leak into every other caller's result");
        assertEquals("original", second.get(0));
    }

    @Test
    @DisplayName("500 concurrent callers on an expiring key cause exactly one recomputation")
    void stampedeCollapsesToOneCall() throws Exception {
        service.slow("hot");                        // prime
        Thread.sleep(600);                          // expire into the grace window
        int before = PromptService.calls.get();

        PromptService.gate = new CountDownLatch(1);
        int threads = 500;
        CyclicBarrier startLine = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        startLine.await(30, TimeUnit.SECONDS);
                        assertNotNull(service.slow("hot"));
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            Thread.sleep(200);
            PromptService.gate.countDown();         // release the single refresher
            assertTrue(done.await(60, TimeUnit.SECONDS), "callers did not all complete");
        }

        assertEquals(0, errors.get(), "no caller may receive an error");
        assertEquals(1, PromptService.calls.get() - before,
                "the herd must collapse to exactly one backend call");

        FastCacheAspect aspect = context.getBean(FastCacheAspect.class);
        assertTrue(aspect.staleServedCount() > 0, "the rest should have been served stale");
    }

    @Test
    @DisplayName("the programmatic facade round-trips and evicts")
    void operationsFacade() {
        FastCacheOperations operations = context.getBean(FastCacheOperations.class);

        operations.put("manual", "value", "30s");
        assertEquals("value", operations.get("manual", String.class).orElse(null));
        assertTrue(operations.evict("manual"));
        assertTrue(operations.get("manual", String.class).isEmpty());
    }

    @Test
    @DisplayName("the reference path stores nothing off-heap")
    void referencePathIsOffHeapFree() {
        service.embed("hello");
        assertEquals(0, context.getBean(ShardedStorageEngine.class).stats().bytes(),
                "in-process values are live references; nothing is serialized into a native slot");
    }
}
