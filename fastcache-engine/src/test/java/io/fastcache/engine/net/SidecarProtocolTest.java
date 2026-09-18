package io.fastcache.engine.net;

import io.fastcache.engine.core.EngineConfig;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.metrics.CostProfile;
import io.fastcache.engine.metrics.MetricsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the wire protocol and the console, without involving Python.
 *
 * <p>The Python suite exercises these paths too, but only this test can run in a JDK-only CI job, and
 * only this test pins the <em>protocol</em> rather than the client's interpretation of it. If the framing
 * ever drifts, this fails in the Java build rather than as a confusing decode error three layers up.
 */
class SidecarProtocolTest {

    private ShardedStorageEngine engine;
    private FastCacheServer server;
    private MetricsServer console;
    private int port;
    private int consolePort;

    @BeforeEach
    void start() throws IOException {
        engine = new ShardedStorageEngine(EngineConfig.builder()
                .shardCount(8)
                .maxValueBytes(64 * 1024)
                .staleGraceMillis(600)
                .sweepIntervalMillis(200)
                .build());
        server = new FastCacheServer(engine, "127.0.0.1", 0);   // 0 = OS-assigned, never collides in CI
        port = server.start();
        console = new MetricsServer(engine, "127.0.0.1", 0, CostProfile.GPT_4O, "test");
        consolePort = console.start();
    }

    @AfterEach
    void stop() {
        if (console != null) {
            console.close();
        }
        if (server != null) {
            server.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A minimal protocol client, so the test pins the wire format rather than reusing engine helpers.
    // ---------------------------------------------------------------------------------------------

    private record Response(int status, int flags, byte[] payload) {
        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private static final class Client implements AutoCloseable {
        private final Socket socket;

        Client(int port) throws IOException {
            this.socket = new Socket("127.0.0.1", port);
            this.socket.setTcpNoDelay(true);
            this.socket.setSoTimeout(15_000);
        }

        Response send(byte opcode, String key, byte[] value, byte flags, long ttlMillis, int sourceChars)
                throws IOException {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            ByteBuffer header = ByteBuffer.allocate(Protocol.REQUEST_HEADER_BYTES);
            header.putShort((short) Protocol.REQUEST_MAGIC);
            header.put(opcode);
            header.put(flags);
            header.putShort((short) keyBytes.length);
            header.putLong(ttlMillis);
            header.putInt(value.length);
            header.putInt(sourceChars);

            OutputStream out = socket.getOutputStream();
            out.write(header.array());
            out.write(keyBytes);
            out.write(value);
            out.flush();

            byte[] responseHeader = readExactly(Protocol.RESPONSE_HEADER_BYTES);
            ByteBuffer parsed = ByteBuffer.wrap(responseHeader);
            int magic = Short.toUnsignedInt(parsed.getShort());
            assertEquals(Protocol.RESPONSE_MAGIC, magic, "response magic");
            int status = parsed.get();
            int responseFlags = parsed.get();
            int length = parsed.getInt();
            return new Response(status, responseFlags, length > 0 ? readExactly(length) : new byte[0]);
        }

        Response send(byte opcode, String key) throws IOException {
            return send(opcode, key, new byte[0], (byte) 0, 0, 0);
        }

        private byte[] readExactly(int count) throws IOException {
            byte[] buffer = new byte[count];
            InputStream in = socket.getInputStream();
            int read = 0;
            while (read < count) {
                int chunk = in.read(buffer, read, count - read);
                if (chunk < 0) {
                    throw new IOException("connection closed with " + (count - read) + " bytes outstanding");
                }
                read += chunk;
            }
            return buffer;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Protocol
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PING answers OK")
    void ping() throws IOException {
        try (Client client = new Client(port)) {
            assertEquals(Protocol.ST_OK, client.send(Protocol.OP_PING, "").status());
        }
    }

    @Test
    @DisplayName("PUT then GET round-trips the payload and its codec flags")
    void putGet() throws IOException {
        try (Client client = new Client(port)) {
            byte[] value = "a cached completion".getBytes(StandardCharsets.UTF_8);
            assertEquals(Protocol.ST_OK,
                    client.send(Protocol.OP_PUT, "k", value, (byte) 0x13, 60_000, 19).status());

            Response get = client.send(Protocol.OP_GET, "k");
            assertEquals(Protocol.ST_OK, get.status());
            assertEquals(0x13, get.flags(), "codec flags must be echoed verbatim");
            assertEquals("a cached completion", get.text());
        }
    }

    @Test
    @DisplayName("GET on an absent key reports MISS")
    void miss() throws IOException {
        try (Client client = new Client(port)) {
            assertEquals(Protocol.ST_MISS, client.send(Protocol.OP_GET, "absent").status());
        }
    }

    @Test
    @DisplayName("DELETE removes the entry")
    void delete() throws IOException {
        try (Client client = new Client(port)) {
            client.send(Protocol.OP_PUT, "k", "v".getBytes(StandardCharsets.UTF_8), (byte) 0, 60_000, 1);
            assertEquals(Protocol.ST_OK, client.send(Protocol.OP_DELETE, "k").status());
            assertEquals(Protocol.ST_MISS, client.send(Protocol.OP_GET, "k").status());
        }
    }

    @Test
    @DisplayName("an entry inside its grace window returns ST_STALE, not ST_OK")
    void staleStatus() throws IOException, InterruptedException {
        try (Client client = new Client(port)) {
            client.send(Protocol.OP_PUT, "k", "v".getBytes(StandardCharsets.UTF_8), (byte) 0, 200, 1);
            assertEquals(Protocol.ST_OK, client.send(Protocol.OP_GET, "k").status());

            Thread.sleep(350);
            Response stale = client.send(Protocol.OP_GET, "k");
            assertEquals(Protocol.ST_STALE, stale.status(), "the client needs to know a refresh is due");
            assertEquals("v", stale.text(), "a stale value is still a usable value");
        }
    }

    @Test
    @DisplayName("an oversized PUT is rejected but leaves the connection usable")
    void oversizedPutDoesNotKillTheConnection() throws IOException {
        try (Client client = new Client(port)) {
            byte[] tooBig = new byte[80 * 1024]; // maxValueBytes is 64 KiB in this fixture.

            Response rejected = client.send(Protocol.OP_PUT, "big", tooBig, (byte) 0, 60_000, 0);
            assertEquals(Protocol.ST_REJECTED_TOO_LARGE, rejected.status());

            // The frame was drained, so the stream is still framed and the session keeps working.
            assertEquals(Protocol.ST_OK, client.send(Protocol.OP_PING, "").status(),
                    "back-pressure must not become a connection reset");
        }
    }

    @Test
    @DisplayName("REFRESH_LEASE is granted to one caller and refused to the next")
    void refreshLease() throws IOException {
        try (Client first = new Client(port); Client second = new Client(port)) {
            assertEquals(Protocol.ST_OK, first.send(Protocol.OP_REFRESH_LEASE, "hot").status());
            assertEquals(Protocol.ST_MISS, second.send(Protocol.OP_REFRESH_LEASE, "hot").status(),
                    "single-flight must hold across connections, not just within one");

            assertEquals(Protocol.ST_OK, first.send(Protocol.OP_REFRESH_DONE, "hot").status());
            assertEquals(Protocol.ST_OK, second.send(Protocol.OP_REFRESH_LEASE, "hot").status());
        }
    }

    @Test
    @DisplayName("HEARTBEAT carries client L1 telemetry into the ledger")
    void heartbeatTelemetry() throws IOException {
        try (Client client = new Client(port)) {
            byte[] report = "l1_hits=42 l1_chars=1234".getBytes(StandardCharsets.UTF_8);
            assertEquals(Protocol.ST_OK,
                    client.send(Protocol.OP_HEARTBEAT, "", report, (byte) 0, 0, 0).status());

            assertEquals(42, engine.savingsLedger().l1Hits());
            assertEquals(1234, engine.savingsLedger().l1Characters());
            assertEquals(1, engine.savingsLedger().connectedClients());
        }
    }

    @Test
    @DisplayName("a malformed heartbeat field does not fail the beat")
    void malformedTelemetryIsTolerated() throws IOException {
        try (Client client = new Client(port)) {
            byte[] report = "l1_hits=abc l1_chars= junk".getBytes(StandardCharsets.UTF_8);
            assertEquals(Protocol.ST_OK,
                    client.send(Protocol.OP_HEARTBEAT, "", report, (byte) 0, 0, 0).status(),
                    "liveness must not depend on telemetry parsing");
        }
    }

    @Test
    @DisplayName("STATS returns a parseable summary line")
    void stats() throws IOException {
        try (Client client = new Client(port)) {
            client.send(Protocol.OP_PUT, "k", "v".getBytes(StandardCharsets.UTF_8), (byte) 0, 60_000, 1);
            client.send(Protocol.OP_GET, "k");

            String wire = client.send(Protocol.OP_STATS, "").text();
            assertTrue(wire.contains("shards=8"), wire);
            assertTrue(wire.contains("hits=1"), wire);
            assertTrue(wire.contains("stale_hits="), wire);
            assertTrue(wire.contains("herd_suppressed="), wire);
        }
    }

    @Test
    @DisplayName("a bad magic number closes the connection instead of guessing")
    void badMagicIsFatal() throws IOException {
        try (Socket raw = new Socket("127.0.0.1", port)) {
            raw.setSoTimeout(10_000);
            ByteBuffer header = ByteBuffer.allocate(Protocol.REQUEST_HEADER_BYTES);
            header.putShort((short) 0xDEAD); // Not a FastCache frame.
            header.put(Protocol.OP_PING);
            header.put((byte) 0);
            header.putShort((short) 0);
            header.putLong(0);
            header.putInt(0);
            header.putInt(0);
            raw.getOutputStream().write(header.array());
            raw.getOutputStream().flush();

            byte[] response = new byte[Protocol.RESPONSE_HEADER_BYTES];
            int read = raw.getInputStream().read(response);
            assertTrue(read > 0, "the server should report the problem before hanging up");
            assertEquals(Protocol.ST_BAD_REQUEST, ByteBuffer.wrap(response).get(2));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Console
    // ---------------------------------------------------------------------------------------------

    private String httpGet(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + consolePort + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    @Test
    @DisplayName("/metrics serves well-formed JSON with every section")
    void metricsEndpoint() throws IOException {
        try (Client client = new Client(port)) {
            client.send(Protocol.OP_PUT, "k", "x".repeat(400).getBytes(StandardCharsets.UTF_8),
                    (byte) 1, 60_000, 400);
            client.send(Protocol.OP_GET, "k");
        }

        String json = httpGet("/metrics");
        for (String section : new String[]{"\"memory\"", "\"cache\"", "\"clients\"", "\"savings\"", "\"shards\""}) {
            assertTrue(json.contains(section), () -> "missing " + section + " in " + json);
        }
        assertTrue(json.contains("\"shards\":8"));
        // Locale safety: a comma-decimal JVM must not emit 0,8532 and produce invalid JSON.
        assertTrue(json.matches(".*\"system_used_ratio\":\\d+\\.\\d+.*"), json);
    }

    @Test
    @DisplayName("/metrics re-prices when a model is supplied")
    void metricsModelSwitch() throws IOException {
        assertTrue(httpGet("/metrics?model=claude-3-5-sonnet").contains("\"model_id\":\"claude-3-5-sonnet\""));
        assertTrue(httpGet("/metrics?model=in-house:1.25").contains("\"usd_per_million_input_tokens\":1.25"));
    }

    @Test
    @DisplayName("/dashboard serves a populated HTML page")
    void dashboardEndpoint() throws IOException {
        String html = httpGet("/dashboard");
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("FastCache Console"));
        // Server-rendered: the page must be readable before any JavaScript runs.
        assertTrue(html.contains("const boot = {"), "the snapshot must be inlined, not fetched");
        for (String element : new String[]{"sys-bar", "off-bar", "heap-bar", "saved", "t-herd"}) {
            assertTrue(html.contains("id=\"" + element + "\""), "dashboard missing #" + element);
        }
    }

    @Test
    @DisplayName("/health reports UP")
    void healthEndpoint() throws IOException {
        assertTrue(httpGet("/health").contains("\"status\":\"UP\""));
    }

    @Test
    @DisplayName("the console refuses every non-GET method")
    void consoleIsReadOnly() throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + consolePort + "/metrics");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(405, connection.getResponseCode(),
                "no request shape may mutate cache state through the metrics port");
        connection.disconnect();
    }

    @Test
    @DisplayName("/metrics/prometheus is valid exposition format")
    void prometheusEndpoint() throws IOException {
        try (Client client = new Client(port)) {
            client.send(Protocol.OP_PUT, "k", "value".getBytes(StandardCharsets.UTF_8), (byte) 1, 60_000, 5);
            client.send(Protocol.OP_GET, "k");
            client.send(Protocol.OP_GET, "absent");
        }

        String body = httpGet("/metrics/prometheus");

        // Every metric needs HELP and TYPE before its first sample, or a scraper rejects it.
        assertTrue(body.contains("# HELP fastcache_cache_hits_total"), body.substring(0, 200));
        assertTrue(body.contains("# TYPE fastcache_cache_hits_total counter"));
        assertTrue(body.contains("# TYPE fastcache_operation_duration_seconds histogram"));

        assertTrue(body.contains("fastcache_process_cpu_ratio"), "CPU must be exported");
        assertTrue(body.contains("fastcache_gc_collections_total"), "GC must be exported");
        assertTrue(body.contains("fastcache_platform_threads"), "thread count must be exported");
        assertTrue(body.contains("fastcache_memory_offheap_reserved_bytes"));
        assertTrue(body.contains("fastcache_herd_suppressed_total"));
        assertTrue(body.contains("shard=\"0\""), "per-shard series must be labelled");

        for (String line : body.split("\n")) {
            if (!line.startsWith("#") && line.contains(",")) {
                String value = line.substring(line.lastIndexOf(' ') + 1);
                assertFalse(value.contains(","),
                        "locale-dependent number formatting leaked into: " + line);
            }
        }
    }

    @Test
    @DisplayName("histogram buckets are cumulative and +Inf equals the count")
    void prometheusHistogramIsWellFormed() throws IOException {
        try (Client client = new Client(port)) {
            for (int i = 0; i < 25; i++) {
                client.send(Protocol.OP_GET, "k" + i);
            }
        }

        String prefix = "fastcache_operation_duration_seconds_bucket{operation=\"get\",le=";
        long previous = -1;
        long infinity = -1;
        long declaredCount = -1;
        int buckets = 0;

        for (String line : httpGet("/metrics/prometheus").split("\n")) {
            line = line.trim();
            if (line.startsWith(prefix)) {
                long value = Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
                assertTrue(value >= previous,
                        "buckets must be cumulative: " + value + " followed " + previous);
                previous = value;
                buckets++;
                if (line.contains("+Inf")) {
                    infinity = value;
                }
            } else if (line.startsWith("fastcache_operation_duration_seconds_count{operation=\"get\"}")) {
                declaredCount = Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
            }
        }

        assertTrue(buckets > 5, "expected a full bucket ladder, found " + buckets);
        assertEquals(declaredCount, infinity, "the +Inf bucket must equal _count");
    }

    @Test
    @DisplayName("latency percentiles appear in the JSON for the dashboard")
    void latencyInJson() throws IOException {
        try (Client client = new Client(port)) {
            for (int i = 0; i < 10; i++) {
                client.send(Protocol.OP_PUT, "lat" + i, new byte[128], (byte) 0, 60_000, 0);
                client.send(Protocol.OP_GET, "lat" + i);
            }
        }

        String json = httpGet("/metrics");
        assertTrue(json.contains("\"latency\""), json.substring(0, Math.min(300, json.length())));
        assertTrue(json.contains("\"p99_ms\""));
        assertTrue(json.contains("\"runtime\""));
        assertTrue(json.contains("\"process_cpu_ratio\""));
        assertTrue(json.contains("\"gc_collections\""));
    }

    @Test
    @DisplayName("the server binds an ephemeral port when asked for 0")
    void ephemeralPort() {
        assertNotEquals(0, port);
        assertNotEquals(0, consolePort);
        assertNotEquals(port, consolePort);
    }
}
