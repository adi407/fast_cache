package io.fastcache.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * FastCache in the only configuration where payloads leave the application's heap: a separate sidecar JVM,
 * reached over the binary wire protocol.
 *
 * <p>This is the arm that can support or falsify the off-heap thesis. What it costs is visible in
 * {@link WireClient} and must be carried into the results rather than assumed away:
 *
 * <ul>
 *   <li>Every {@code get} allocates a fresh {@code byte[]} on the caller's heap. The payload is off-heap
 *       <em>at rest</em>, not while in use. A 25 MB value read a thousand times allocates 25 GB.
 *   <li>Every operation is a socket round trip, so a cache hit costs milliseconds, not nanoseconds.
 *   <li>A second process must be running, and its memory belongs on the balance sheet
 *       ({@link #externalRssBytes()}).
 * </ul>
 */
public final class FastCacheSidecarArm implements CacheArm {

    private final Process process;
    private final WireClient client;
    private final LongAdder rejections = new LongAdder();
    private final long sidecarPid;

    /**
     * Boots a sidecar and waits for its {@code FASTCACHE_READY} handshake.
     *
     * @param jar           path to {@code fastcache-engine.jar}
     * @param budgetBytes   off-heap budget
     * @param maxValueBytes largest accepted payload. Must exceed the largest benchmark size: the engine
     *                      default of 64 MiB would silently reject the 100 MB case as REJECTED_TOO_LARGE.
     * @param rejectRatio   memory guard reject ratio, exposed so Phase 7 can vary it
     * @param heapBytes     sidecar heap. Small on purpose: payloads go off-heap, so a large heap here
     *                      would hide the very property being measured.
     */
    public FastCacheSidecarArm(Path jar, long budgetBytes, long maxValueBytes, double rejectRatio,
                               long heapBytes) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary(),
                "-Xmx" + heapBytes,
                "-XX:MaxDirectMemorySize=" + (budgetBytes * 2),
                "-cp", jar.toString(),
                "io.fastcache.engine.net.SidecarMain",
                "--port", "0",
                "--offheap-max", Long.toString(budgetBytes),
                "--max-value-bytes", Long.toString(maxValueBytes),
                "--reject-ratio", Double.toString(rejectRatio),
                "--metrics-port", "off",
                "--idle-timeout", "0",
                "--heartbeat-timeout", "0",
                "--stale-grace", "0");
        this.process = builder.start();
        this.sidecarPid = process.pid();
        drainStderr(process);

        int bound = awaitHandshake(process);
        if (bound <= 0) {
            process.destroyForcibly();
            throw new IOException("sidecar did not announce a port");
        }
        this.client = new WireClient("127.0.0.1", bound);
    }

    private static int awaitHandshake(Process process) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 30_000;
        String line;
        while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
            if (!line.startsWith("FASTCACHE_READY")) {
                continue;
            }
            for (String token : line.split("\\s+")) {
                if (token.startsWith("port=")) {
                    return Integer.parseInt(token.substring(5));
                }
            }
        }
        return -1;
    }

    /**
     * An unread stdout/stderr pipe fills at ~64 KB and then blocks the writing JVM outright, which would
     * present as a cache hang rather than a plumbing mistake.
     */
    private static void drainStderr(Process process) {
        Thread.ofVirtual().name("sidecar-stderr").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // Discarded: the engine logs at INFO on startup, and the benchmark's own output is
                    // the record that matters.
                }
            } catch (IOException ignored) {
                // Process exited.
            }
        });
    }

    private static String javaBinary() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    public long sidecarPid() {
        return sidecarPid;
    }

    public int port() {
        return client.port();
    }

    @Override
    public String name() {
        return "fastcache-sidecar";
    }

    @Override
    public boolean put(String key, byte[] value, long ttlMillis) {
        boolean accepted = client.put(key, value, ttlMillis);
        if (!accepted) {
            rejections.increment();
        }
        return accepted;
    }

    @Override
    public byte[] get(String key) {
        return client.get(key);
    }

    @Override
    public void remove(String key) {
        client.delete(key);
    }

    @Override
    public void clear() {
        client.flush();
    }

    @Override
    public long reportedBytes() {
        return client.stats().getOrDefault("bytes", -1L);
    }

    @Override
    public long offHeapSlots() {
        return client.stats().getOrDefault("offheap_slots", -1L);
    }

    @Override
    public long offHeapReserved() {
        return client.stats().getOrDefault("offheap_reserved", -1L);
    }

    @Override
    public long entries() {
        return client.stats().getOrDefault("entries", -1L);
    }

    @Override
    public long writeRejections() {
        // The engine's own counter, not the client's view of refused responses: it is authoritative and
        // it is the number Phase 7 reasons about.
        return client.stats().getOrDefault("write_rejections", rejections.sum());
    }

    @Override
    public boolean storesOffHeap() {
        return true;
    }

    @Override
    public long externalRssBytes() {
        return Probe.rssBytes(sidecarPid);
    }

    @Override
    public void close() {
        client.close();
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
