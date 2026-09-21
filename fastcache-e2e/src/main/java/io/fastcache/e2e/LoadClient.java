package io.fastcache.e2e;

import io.fastcache.bench.Stats;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * HTTP load generator. Runs in a separate JVM from the service so its own allocations never appear in
 * the service's memory figures.
 *
 * <p>Reports the full request distribution plus the per-phase breakdown the service returns, so that
 * "cache lookup was fast" and "the request was fast" can be told apart.
 */
public final class LoadClient {

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String base = options.getOrDefault("base", "http://127.0.0.1:8080");
        int entries = Integer.parseInt(options.getOrDefault("entries", "64"));
        int concurrency = Integer.parseInt(options.getOrDefault("concurrency", "8"));
        long warmupSeconds = Long.parseLong(options.getOrDefault("warmup-seconds", "20"));
        long durationSeconds = Long.parseLong(options.getOrDefault("duration-seconds", "60"));
        double writeRatio = Double.parseDouble(options.getOrDefault("write-ratio", "0.0"));
        String label = options.getOrDefault("label", "run");
        Path csv = options.containsKey("csv") ? Path.of(options.get("csv")) : null;

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        // START -> CLEAR -> POPULATE -> VERIFY -> WARMUP -> MEASURE.
        //
        // The clear happens in the service before Spring binds the port, so by the time this runs the
        // cache is already empty; the assertion is still made, because "the code that clears it ran" and
        // "the server is empty" are different statements and only the second one is checkable.
        requireVerified(get(http, base + "/admin/verify?phase=empty", Duration.ofMinutes(2)), "pre-fill");

        // Populate, then reset the service's GC/allocation baseline so the measured window is steady
        // state rather than the fill.
        get(http, base + "/admin/warmup", Duration.ofMinutes(20));

        // Entry count, payload length, seed stamp and content digest for every key, before any timing.
        requireVerified(get(http, base + "/admin/verify?phase=populated", Duration.ofMinutes(10)),
                "post-fill");

        if (warmupSeconds > 0) {
            drive(http, base, entries, concurrency, warmupSeconds, writeRatio, null);
        }
        get(http, base + "/admin/mark", Duration.ofSeconds(30));

        List<long[]> samples = new ArrayList<>();
        long measuredStartNanos = System.nanoTime();
        Result result = drive(http, base, entries, concurrency, durationSeconds, writeRatio, samples);
        double measuredSeconds = (System.nanoTime() - measuredStartNanos) / 1e9;

        String metrics = get(http, base + "/admin/metrics?settle=true", Duration.ofMinutes(5));

        long[] totals = samples.stream().mapToLong(s -> s[0]).toArray();
        long[] lookups = samples.stream().mapToLong(s -> s[1]).toArray();
        long[] decodes = samples.stream().mapToLong(s -> s[2]).toArray();
        long[] business = samples.stream().mapToLong(s -> s[3]).toArray();
        long[] wall = samples.stream().mapToLong(s -> s[4]).toArray();

        Stats total = Stats.of(totals, totals.length).toMicros();
        Stats lookup = Stats.of(lookups, lookups.length).toMicros();
        Stats decode = Stats.of(decodes, decodes.length).toMicros();
        Stats work = Stats.of(business, business.length).toMicros();
        Stats http_ = Stats.of(wall, wall.length).toMicros();

        long completed = result.completed.sum();
        double reqPerSec = completed / Math.max(0.001, measuredSeconds);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("concurrency", concurrency);
        row.put("writeRatio", writeRatio);
        row.put("durationSeconds", durationSeconds);
        row.put("measuredSeconds", String.format(java.util.Locale.ROOT, "%.3f", measuredSeconds));
        row.put("requests", completed);
        row.put("errors", result.errors.sum());
        row.put("reqPerSec", String.format(java.util.Locale.ROOT, "%.1f", reqPerSec));
        // Level 2, the number a developer's client would see: wall clock around the HTTP call, measured
        // in the generator. Everything prefixed `svc` below is the service's own view of the same
        // request and excludes HTTP framing, queueing in Tomcat and the return trip.
        row.put("httpP50us", http_.p50());
        row.put("httpP95us", http_.p95());
        row.put("httpP99us", http_.p99());
        row.put("httpMaxus", http_.max());
        row.put("svcP50us", total.p50());
        row.put("svcP95us", total.p95());
        row.put("svcP99us", total.p99());
        row.put("svcMaxus", total.max());
        // Level 1, the cache transport alone.
        row.put("lookupP50us", lookup.p50());
        row.put("lookupP95us", lookup.p95());
        row.put("lookupP99us", lookup.p99());
        row.put("lookupMaxus", lookup.max());
        row.put("decodeP50us", decode.p50());
        row.put("decodeP99us", decode.p99());
        row.put("businessP50us", work.p50());
        row.put("hitsObserved", result.hits.sum());

        for (Map.Entry<String, String> entry : parseJson(metrics).entrySet()) {
            row.put(entry.getKey(), entry.getValue());
        }

        // Cell validity gates, applied here rather than left to a reader of the CSV. A cell that fails
        // one of these is void, and the previous round proved that a void cell left in a results file is
        // indistinguishable from a real one three days later.
        List<String> failures = new ArrayList<>();
        if (result.errors.sum() != 0) {
            failures.add("errors=" + result.errors.sum() + " (required 0)");
        }
        long corrupt = Long.parseLong(String.valueOf(row.getOrDefault("corrupt", "0")));
        if (corrupt != 0) {
            failures.add("corrupt=" + corrupt + " reason=" + row.get("corruptReason"));
        }
        double hitRatio = Double.parseDouble(String.valueOf(row.getOrDefault("hitRatio", "0")));
        if (hitRatio < 0.999) {
            failures.add(String.format(java.util.Locale.ROOT,
                    "hitRatio=%.5f (required >= 0.99900)", hitRatio));
        }
        if (completed == 0) {
            failures.add("no requests completed");
        }
        // Stricter than the ratio and the reason the counters are windowed: in a fully populated 100%
        // GET window nothing may miss, so a single loader run means the cache evicted mid-measurement.
        long loaderRuns = Long.parseLong(String.valueOf(row.getOrDefault("loaderRuns", "0")));
        if (loaderRuns != 0) {
            failures.add("loaderRuns=" + loaderRuns + " during the measured window (required 0)");
        }
        row.put("cellValid", failures.isEmpty());
        row.put("invalidReason", failures.isEmpty() ? "none" : String.join("; ", failures));

        StringBuilder line = new StringBuilder();
        row.forEach((k, v) -> line.append(k).append('=').append(v).append(' '));
        System.out.println("RESULT " + line);

        if (csv != null) {
            boolean fresh = !Files.exists(csv);
            Files.createDirectories(csv.toAbsolutePath().getParent());
            StringBuilder out = new StringBuilder();
            if (fresh) {
                out.append(String.join(",", row.keySet())).append('\n');
            }
            out.append(row.values().stream().map(String::valueOf)
                    .reduce((a, b) -> a + "," + b).orElse("")).append('\n');
            Files.writeString(csv, out.toString(), fresh
                    ? java.nio.file.StandardOpenOption.CREATE
                    : java.nio.file.StandardOpenOption.APPEND);
        }

        if (!failures.isEmpty()) {
            System.out.println("CELL-INVALID " + label + " :: " + String.join("; ", failures));
            System.exit(4);
        }
    }

    /**
     * Fails the cell now if an integrity phase did not pass.
     *
     * <p>Exit code 3 rather than an exception so the harness can tell an integrity failure from a crash
     * and refuse to record the cell either way.
     */
    private static void requireVerified(String json, String phase) {
        Map<String, String> body = parseJson(json);
        if (!"true".equals(body.get("ok"))) {
            System.out.println("CELL-INVALID integrity " + phase + " :: " + json);
            System.exit(3);
        }
        System.out.println("INTEGRITY " + phase + " ok entries=" + body.get("entries")
                + " payloadBytes=" + body.get("payloadBytes"));
    }

    private record Result(LongAdder completed, LongAdder errors, LongAdder hits) { }

    private static Result drive(HttpClient http, String base, int entries, int concurrency,
                                long seconds, double writeRatio, List<long[]> samples)
            throws InterruptedException {
        LongAdder completed = new LongAdder();
        LongAdder errors = new LongAdder();
        LongAdder hits = new LongAdder();
        AtomicInteger writeCounter = new AtomicInteger();
        long deadline = System.currentTimeMillis() + seconds * 1000;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch done = new CountDownLatch(concurrency);
        for (int i = 0; i < concurrency; i++) {
            pool.submit(() -> {
                java.util.Random rng = new java.util.Random(Thread.currentThread().threadId());
                try {
                    while (System.currentTimeMillis() < deadline) {
                        int index = rng.nextInt(entries);
                        // A "write" is a request for a key outside the populated set, which forces a miss
                        // and therefore a cache population - the closest thing to a SET this endpoint has.
                        boolean write = writeRatio > 0 && rng.nextDouble() < writeRatio;
                        String id = write
                                ? "doc-w" + writeCounter.incrementAndGet() % Math.max(1, entries)
                                : "doc-" + index;
                        try {
                            long sent = System.nanoTime();
                            HttpResponse<String> response = http.send(
                                    HttpRequest.newBuilder(URI.create(
                                                    base + "/documents/" + id + "?seed=" + index))
                                            .timeout(Duration.ofMinutes(5)).GET().build(),
                                    HttpResponse.BodyHandlers.ofString());
                            long wallNanos = System.nanoTime() - sent;
                            if (response.statusCode() != 200) {
                                errors.increment();
                                // A 500 here is the service refusing a value that failed verification.
                                // Finishing the run would produce a full result table describing the
                                // wrong payload, which is precisely the failure this harness exists to
                                // not repeat. Stop at the first one.
                                if (response.statusCode() == 500) {
                                    System.out.println("CELL-INVALID corrupt value :: " + id
                                            + " :: " + response.body().replace('\n', ' '));
                                    Runtime.getRuntime().halt(5);
                                }
                                continue;
                            }
                            completed.increment();
                            Map<String, String> body = parseJson(response.body());
                            if ("true".equals(body.get("hit"))) {
                                hits.increment();
                            }
                            if (samples != null) {
                                long[] sample = {
                                        Long.parseLong(body.getOrDefault("totalNanos", "0")),
                                        Long.parseLong(body.getOrDefault("lookupNanos", "0")),
                                        Long.parseLong(body.getOrDefault("decodeNanos", "0")),
                                        Long.parseLong(body.getOrDefault("businessNanos", "0")),
                                        wallNanos};
                                synchronized (samples) {
                                    samples.add(sample);
                                }
                            }
                        } catch (IOException | InterruptedException e) {
                            errors.increment();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        pool.shutdownNow();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        return new Result(completed, errors, hits);
    }

    private static String get(HttpClient http, String url, Duration timeout) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Flat-JSON reader. The payloads here are one level deep by construction, so this is sufficient. */
    private static Map<String, String> parseJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : json.replaceAll("[{}\"]", "").split(",")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                out.put(part.substring(0, colon).trim(), part.substring(colon + 1).trim());
            }
        }
        return out;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(args[i].substring(2), args[++i]);
            }
        }
        return options;
    }
}
