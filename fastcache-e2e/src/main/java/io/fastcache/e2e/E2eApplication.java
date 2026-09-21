package io.fastcache.e2e;

import io.fastcache.bench.Probe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The service under test: a Spring Boot application whose only interesting property is which cache sits
 * behind its one endpoint.
 *
 * <p>It runs in its own JVM. The load generator runs in another. That separation is the point — every
 * heap, GC and allocation figure reported here belongs to the service, not to the thing hammering it.
 * Driving load from inside the same JVM would mix the generator's own allocations into exactly the
 * measurement this experiment exists to take.
 *
 * <pre>
 *   --arm      caffeine | fastcache-embedded | fastcache-sidecar | redis
 *   --payload  bytes per document
 *   --entries  documents to pre-populate
 *   --rep      bytes | object     (representation A or B)
 * </pre>
 */
@SpringBootApplication
@RestController
public class E2eApplication {

    private static final AtomicReference<DocumentService> SERVICE = new AtomicReference<>();
    private static final AtomicReference<Config> CONFIG = new AtomicReference<>();
    private static Probe probe;

    record Config(String arm, int payloadBytes, int entries, String representation, long missCostMillis) { }

    public static void main(String[] args) {
        Map<String, String> options = parse(args);
        Config config = new Config(
                options.getOrDefault("arm", "caffeine"),
                (int) parseSize(options.getOrDefault("payload", "1m")),
                Integer.parseInt(options.getOrDefault("entries", "64")),
                options.getOrDefault("rep", "object"),
                Long.parseLong(options.getOrDefault("miss-cost", "50")));
        CONFIG.set(config);

        long budget = parseSize(options.getOrDefault("budget", "2g"));
        Backends.Backend backend = switch (config.arm()) {
            case "caffeine" -> new Backends.CaffeineBackend(budget);
            // The per-shard ceiling is set so it cannot bind, because it is the ONLY bound this arm has:
            // putReference reports a zero off-heap footprint, so the byte budget does not apply to it.
            // Sizing it at entries/shards looks right and is not: murmur3 skew of 1.2-1.4x puts the
            // hottest shard above the mean, so the ceiling binds there while the cache is half empty.
            // Measured with entries/32+1 at 512 entries: only 204 were resident and throughput collapsed
            // to the miss-bound rate. Caffeine's 2 GB weight bound does not bind for this working set,
            // so neither may this one, or the arms are not being compared on the same thing.
            case "fastcache-embedded" -> new Backends.FastCacheEmbeddedBackend(
                    budget, Math.max(8, config.entries()));
            case "fastcache-sidecar" -> new Backends.FastCacheSidecarBackend(
                    options.getOrDefault("cache-host", "127.0.0.1"),
                    Integer.parseInt(options.getOrDefault("cache-port", "6380")),
                    Long.parseLong(options.getOrDefault("cache-pid", "0")));
            case "redis" -> new Backends.RedisBackend(
                    options.getOrDefault("cache-host", "127.0.0.1"),
                    Integer.parseInt(options.getOrDefault("cache-port", "6399")),
                    Long.parseLong(options.getOrDefault("cache-pid", "0")));
            default -> throw new IllegalArgumentException("unknown arm " + config.arm());
        };

        // Start from an empty cache, always. The in-process arms get this free from a fresh JVM; the
        // cross-process arms do not, because their server outlives the application. See Backend.clear().
        backend.clear();

        // --rep bytes selects Representation A (bulk payload + minimal header); anything else is
        // Representation B (full object graph). Both round-trip through the same codec for the arms
        // that need one, so the difference between them is exactly the structure.
        SERVICE.set(new DocumentService(backend, config.payloadBytes(), config.missCostMillis(),
                "bytes".equalsIgnoreCase(config.representation()), config.entries()));
        probe = new Probe();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            probe.close();
            backend.close();
        }));

        SpringApplication application = new SpringApplication(E2eApplication.class);
        application.setDefaultProperties(Map.of(
                "server.port", options.getOrDefault("port", "8080"),
                // Tomcat's thread pool must not be the bottleneck the experiment accidentally measures.
                "server.tomcat.threads.max", options.getOrDefault("server-threads", "200"),
                "spring.main.banner-mode", "off",
                "logging.level.root", "WARN"));
        application.run();
    }

    // ---------------------------------------------------------------------------------------------------

    /**
     * The measured endpoint.
     *
     * <p>Returns only a digest of the value rather than the value itself. Writing 25 MB back over HTTP
     * would make this a benchmark of Tomcat's response buffering, and it would be charged identically to
     * every arm while dwarfing the differences between them. The value is fully consumed inside the
     * service ({@link LargeResponse#consume()}), so nothing is elided — it simply is not re-serialised
     * onto the wire.
     */
    @GetMapping(value = "/documents/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String document(@PathVariable String id, @RequestParam(defaultValue = "0") int seed) {
        DocumentService service = SERVICE.get();
        DocumentService.Timings timings = new DocumentService.Timings();
        LargeResponse value = service.fetch(id, seed, timings);
        return "{\"id\":\"" + value.documentId()
                + "\",\"bytes\":" + value.payload().length
                + ",\"sections\":" + value.sections().size()
                + ",\"hit\":" + timings.hit
                + ",\"lookupNanos\":" + timings.lookupNanos
                + ",\"decodeNanos\":" + timings.decodeNanos
                + ",\"businessNanos\":" + timings.businessNanos
                + ",\"totalNanos\":" + timings.totalNanos() + "}";
    }

    /**
     * The integrity gate between POPULATE and WARMUP.
     *
     * <p>{@code phase=empty} asserts the cache really is empty before the fill — the check that would
     * have caught a server still holding another payload tier's entries. {@code phase=populated} reads
     * every key back and verifies id, length, seed stamp and content digest, outside any timed path.
     *
     * <p>Deliberately not part of {@code /admin/warmup}: a cell must be able to fail here, before it has
     * produced a single measurement, rather than produce a plausible table and a corrupt count.
     */
    @GetMapping(value = "/admin/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    public String verify(@RequestParam(defaultValue = "populated") String phase) {
        Config config = CONFIG.get();
        DocumentService service = SERVICE.get();
        long entries = service.backend().entries();
        String failure;
        if ("empty".equalsIgnoreCase(phase)) {
            failure = entries == 0 ? null : "cache not empty before populate: entries=" + entries;
        } else if (entries != config.entries()) {
            failure = "entry count expected=" + config.entries() + " actual=" + entries;
        } else {
            failure = service.verifyAll(config.entries());
        }
        return "{\"phase\":\"" + phase
                + "\",\"ok\":" + (failure == null)
                + ",\"entries\":" + entries
                + ",\"expectedEntries\":" + config.entries()
                + ",\"payloadBytes\":" + config.payloadBytes()
                + ",\"failure\":" + (failure == null ? "null" : "\"" + failure.replace('"', '\'') + "\"")
                + "}";
    }

    /** Pre-populates the cache so a steady-state run measures hits rather than the fill. */
    @GetMapping("/admin/warmup")
    public String warmup() {
        Config config = CONFIG.get();
        DocumentService service = SERVICE.get();
        DocumentService.Timings timings = new DocumentService.Timings();
        for (int i = 0; i < config.entries(); i++) {
            service.fetch("doc-" + i, i, timings);
        }
        return "{\"entries\":" + service.backend().entries()
                + ",\"loaderRuns\":" + service.loaderRuns() + "}";
    }

    /** Everything the harness needs about this JVM, sampled at the moment of the call. */
    @GetMapping(value = "/admin/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public String metrics(@RequestParam(defaultValue = "false") boolean settle) {
        DocumentService service = SERVICE.get();
        Config config = CONFIG.get();
        long heap = settle ? Probe.settledHeapUsed() : Probe.heapUsed();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arm", config.arm());
        out.put("representation", config.representation());
        out.put("payloadBytes", config.payloadBytes());
        out.put("heapUsed", heap);
        out.put("heapCommitted", Probe.heapCommitted());
        out.put("oldGenUsed", Probe.oldGenUsed());
        out.put("nonHeapUsed", Probe.nonHeapUsed());
        out.put("rss", Probe.rssBytes());
        out.put("externalRss", service.backend().externalRssBytes());
        out.put("entries", service.backend().entries());
        out.put("writeRejections", service.backend().writeRejections());
        out.put("hits", service.hits());
        out.put("misses", service.misses());
        out.put("loaderRuns", service.loaderRuns());
        out.put("corrupt", service.corrupt());
        out.put("corruptReason", service.corruptReason() == null
                ? "none"
                : service.corruptReason().replace(',', ';').replace(':', ' ').replace('"', '\''));
        out.put("hitRatio", service.hitRatio());
        out.put("cachesOffHeap", service.backend().cachesOffHeap());
        out.put("decodesOnHit", service.backend().decodesOnHit());

        Probe.GcDelta gc = probe.since(GC_BASELINE.get() == null ? probe.mark() : GC_BASELINE.get());
        out.put("gcCollections", gc.collections());
        out.put("gcTotalPauseMillis", gc.totalPauseMillis());
        out.put("gcPauseP95Micros", gc.pauseMicros().p95());
        out.put("gcPauseP99Micros", gc.pauseMicros().p99());
        out.put("gcPauseMaxMicros", gc.pauseMicros().max());
        out.put("allocatedBytes", gc.allocatedBytes());

        StringBuilder json = new StringBuilder("{");
        out.forEach((k, v) -> json.append('"').append(k).append("\":")
                .append(v instanceof String s ? "\"" + s + "\"" : String.valueOf(v)).append(','));
        json.setLength(json.length() - 1);
        return json.append('}').toString();
    }

    /**
     * Terminates the service.
     *
     * <p>Exists because killing this process reliably from the harness turned out not to be portable:
     * under Git Bash on Windows, {@code $!} yields an MSYS pid that Windows process APIs do not
     * recognise, so the kill silently failed and a finished cell's JVM kept running - and kept consuming
     * CPU and memory - while the next cell was being measured. An in-band shutdown removes the ambiguity.
     */
    @GetMapping("/admin/shutdown")
    public String shutdown() {
        new Thread(() -> {
            try {
                Thread.sleep(200);   // let this response flush first
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "e2e-shutdown").start();
        return "{\"stopping\":true,\"pid\":" + ProcessHandle.current().pid() + "}";
    }

    /**
     * Phase 13. Fires {@code concurrency} simultaneous requests at one key that is guaranteed absent and
     * reports how many times the loader actually ran.
     */
    @GetMapping(value = "/stampede", produces = MediaType.APPLICATION_JSON_VALUE)
    public String stampede(@RequestParam(defaultValue = "100") int concurrency,
                           @RequestParam(defaultValue = "native") String mode,
                           @RequestParam(defaultValue = "0") int round) throws InterruptedException {
        Config config = CONFIG.get();
        DocumentService service = SERVICE.get();
        // A fresh key per invocation, so every round starts from a genuine miss.
        String key = "stampede-" + mode + "-" + round + "-" + System.nanoTime();
        Stampede.Result result = Stampede.run(service, null, null, config.arm(), mode, concurrency,
                key, config.payloadBytes(), 7, config.missCostMillis());
        return result.toJson();
    }

    /** The OS pid, so the harness can verify termination rather than assume it. */
    @GetMapping("/admin/pid")
    public String pid() {
        return "{\"pid\":" + ProcessHandle.current().pid() + "}";
    }

    private static final AtomicReference<Probe.Mark> GC_BASELINE = new AtomicReference<>();

    /**
     * Resets the GC, allocation and hit-accounting baselines so a measured window excludes warmup.
     *
     * <p>The hit counters are reset alongside the GC mark rather than left cumulative, so that
     * {@code hitRatio}, {@code misses} and {@code loaderRuns} describe the measurement and can be gated
     * on. See {@link DocumentService#resetCounters()} for why the cumulative figure cannot be.
     */
    @GetMapping("/admin/mark")
    public String mark() {
        GC_BASELINE.set(probe.mark());
        SERVICE.get().resetCounters();
        return "{\"marked\":true}";
    }

    // ---------------------------------------------------------------------------------------------------

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(args[i].substring(2), args[++i]);
            }
        }
        return options;
    }

    static long parseSize(String spec) {
        String text = spec.trim().toLowerCase(java.util.Locale.ROOT);
        long multiplier = text.endsWith("k") || text.endsWith("kb") ? 1024
                : text.endsWith("m") || text.endsWith("mb") ? 1 << 20
                : text.endsWith("g") || text.endsWith("gb") ? 1 << 30 : 1;
        return Long.parseLong(text.replaceAll("[^0-9]", "")) * multiplier;
    }
}
