package io.fastcache.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Phase 5: writer process -&gt; shared server -&gt; reader process, for FastCache and Redis side by side.
 *
 * <p>Runs in two modes. Without {@code --reader}, this is the writer: it fills a store, then spawns a
 * second JVM running this same class with {@code --reader}, a provider name and a port. The reader knows
 * nothing else; it reads every key back and compares the bytes.
 *
 * <p>Caffeine cannot appear here at all — a second process has no access to another process's heap. That
 * is the capability gap. But Redis fills the same gap and has done for fifteen years, so it is measured
 * beside FastCache rather than assumed away: if Redis matches the sidecar, FastCache's one remaining
 * technical differentiator does not differentiate.
 */
public final class CrossProcessBench {

    private static final long MB = 1 << 20;

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("reader")) {
            runReader(Integer.parseInt(options.get("port")),
                    Integer.parseInt(options.getOrDefault("keys", "64")),
                    Integer.parseInt(options.getOrDefault("payload", String.valueOf(1 << 20))),
                    options.getOrDefault("provider", "fastcache"));
            return;
        }

        int redisPort = Integer.parseInt(options.getOrDefault("redis-port", "6399"));
        long redisPid = Long.parseLong(options.getOrDefault("redis-pid", "0"));
        int[] sizes = {1 << 20, 10 << 20, 25 << 20};
        Path jar = Path.of(options.getOrDefault("jar",
                "fastcache-engine/target/fastcache-engine.jar"));

        System.out.println("=".repeat(118));
        System.out.println("Phase 5 - cross-process read: FastCache sidecar vs Redis");
        System.out.println("  a writer process fills the store; a separate reader JVM reads it back and");
        System.out.println("  compares every byte. Both servers are on loopback on this host.");
        System.out.println("=".repeat(118));
        System.out.printf("%n  %-18s %-9s %-7s %-9s %-11s %-11s %-11s %-11s %s%n",
                "provider", "payload", "keys", "written", "byteExact", "readP50ms", "readP95ms",
                "readP99ms", "serverRssMB");
        System.out.println("  " + "-".repeat(114));

        for (int size : sizes) {
            int keys = (int) Math.max(4, (256 * MB) / size);

            // FastCache. The guard is left at its 0.85 default here on purpose: this table is about the
            // capability as shipped, not about isolating one variable.
            try (FastCacheSidecarArm sidecar = new FastCacheSidecarArm(jar, 2048L * MB,
                    Math.max(size * 2L, 256L << 20), 0.85, 512L << 20)) {
                int written = 0;
                for (int i = 0; i < keys; i++) {
                    if (sidecar.put("xp:" + i, Payloads.of(size, i), 600_000)) {
                        written++;
                    }
                }
                report("fastcache-sidecar", size, keys, written,
                        spawnReader(sidecar.port(), keys, size, "fastcache"),
                        sidecar.externalRssBytes());
            }

            // Redis: an already-running server this benchmark does not manage.
            try (RespClient redis = new RespClient("127.0.0.1", redisPort)) {
                if (redis.dbSize() < 0) {
                    System.out.printf("  %-18s %-9s redis unreachable on port %d - skipped%n",
                            "redis", Payloads.label(size), redisPort);
                    continue;
                }
                redis.flushAll();
                int written = 0;
                for (int i = 0; i < keys; i++) {
                    if (redis.set("xp:" + i, Payloads.of(size, i), 600_000)) {
                        written++;
                    }
                }
                report("redis", size, keys, written, spawnReader(redisPort, keys, size, "redis"),
                        redisPid > 0 ? Probe.rssBytes(redisPid) : -1);
            }
        }

        System.out.println();
        System.out.println("  Caffeine cannot appear in this table: a second process cannot read another");
        System.out.println("  process's heap. Redis can, which is why it is here.");
    }

    private static void report(String provider, int size, int keys, int written, String summary,
                               long serverRssBytes) {
        System.out.printf("  %-18s %-9s %-7d %-9d %s  serverRssMB=%s%n",
                provider, Payloads.label(size), keys, written, summary,
                serverRssBytes < 0 ? "n/a" : String.valueOf(serverRssBytes / MB));
    }

    /** Runs a second JVM that knows only a port and a provider name, and returns its one result line. */
    private static String spawnReader(int port, int keys, int size, String provider) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                javaBinary(), "-Xmx512m",
                "-cp", System.getProperty("java.class.path"),
                CrossProcessBench.class.getName(),
                "--reader", "true",
                "--provider", provider,
                "--port", Integer.toString(port),
                "--keys", Integer.toString(keys),
                "--payload", Integer.toString(size)));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process reader = builder.start();

        String summary = "reader produced no output";
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(reader.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.startsWith("READER_RESULT ")) {
                    summary = line.substring("READER_RESULT ".length());
                }
            }
        }
        reader.waitFor();
        return summary;
    }

    /** The reader child: knows only a port and a provider, reads everything back, verifies every byte. */
    private static void runReader(int port, int keys, int payloadBytes, String provider) {
        AutoCloseable handle = null;
        try {
            Function<String, byte[]> get;
            if (provider.equals("redis")) {
                RespClient client = new RespClient("127.0.0.1", port);
                handle = client;
                get = client::get;
            } else {
                WireClient client = new WireClient("127.0.0.1", port);
                handle = client;
                get = client::get;
            }

            long[] samples = new long[keys];
            int readBack = 0;
            int byteExact = 0;
            for (int i = 0; i < keys; i++) {
                long started = System.nanoTime();
                byte[] value = get.apply("xp:" + i);
                samples[i] = System.nanoTime() - started;
                if (value != null) {
                    readBack++;
                    if (value.length == payloadBytes
                            && Arrays.equals(value, Payloads.of(payloadBytes, i))) {
                        byteExact++;
                    }
                }
            }
            Stats latency = Stats.of(samples, keys).toMicros();
            System.out.printf("READER_RESULT %-9d %-11s %-11.2f %-11.2f %-11.2f%n",
                    readBack, byteExact + "/" + keys,
                    latency.p50() / 1000.0, latency.p95() / 1000.0, latency.p99() / 1000.0);
        } catch (Exception e) {
            System.out.println("READER_RESULT failed: " + e);
        } finally {
            if (handle != null) {
                try {
                    handle.close();
                } catch (Exception ignored) {
                    // Nothing useful to do on a close failure in a child that is about to exit.
                }
            }
        }
    }

    private static String javaBinary() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
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
}
