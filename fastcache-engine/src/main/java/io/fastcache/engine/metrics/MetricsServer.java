package io.fastcache.engine.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.util.FastCacheLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The zero-ops management console: a read-only HTTP endpoint served from inside the sidecar.
 *
 * <h2>Why {@code com.sun.net.httpserver} and nothing else</h2>
 * This server exists to make the cache observable, not to be a web framework. Tomcat would add ~9&nbsp;MB of
 * jars and a thread pool; Spring Boot Web would add forty more and a startup cost measured in seconds. The
 * JDK's built-in HTTP server is already in {@code java.base}'s neighbourhood ({@code jdk.httpserver}), costs
 * nothing to ship, and starts in single-digit milliseconds. The whole point of FastCache is that a data
 * scientist types {@code import fastcache} and gets an engine; a console that tripled the wheel size would
 * contradict the product.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code /dashboard} — the single-page HTML console (also served at {@code /}).</li>
 *   <li>{@code /metrics} — the same data as JSON, for scraping or scripting.</li>
 *   <li>{@code /health} — liveness, for supervisors.</li>
 * </ul>
 * Both data endpoints accept {@code ?model=gpt-4o|claude-3-5-sonnet|<name>:<usd>} to re-price the savings
 * figure without restarting anything.
 *
 * <h2>Read-only, loopback-only</h2>
 * Every handler rejects anything but GET, and the listener binds {@code 127.0.0.1} by default. The console
 * exposes cache keys' aggregate behaviour and the host's memory profile with no authentication; that is
 * fine on loopback and a liability on {@code 0.0.0.0}. Binding wider is possible but must be deliberate.
 */
public final class MetricsServer implements AutoCloseable {

    private static final FastCacheLog LOG = FastCacheLog.of(MetricsServer.class);
    private static final int STOP_DELAY_SECONDS = 1;

    private final ShardedStorageEngine engine;
    private final String bindHost;
    private final int requestedPort;
    private final CostProfile defaultProfile;
    private final String version;
    private final long startedAtMillis = System.currentTimeMillis();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int boundPort = -1;

    public MetricsServer(ShardedStorageEngine engine, String bindHost, int requestedPort,
                         CostProfile defaultProfile, String version) {
        this.engine = engine;
        this.bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        this.requestedPort = requestedPort;
        this.defaultProfile = defaultProfile == null ? CostProfile.GPT_4O : defaultProfile;
        this.version = version;
    }

    /**
     * Binds and starts serving.
     *
     * @return the bound port (resolved when port 0 was requested)
     */
    public int start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return boundPort;
        }
        HttpServer httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName(bindHost), requestedPort), 16);

        httpServer.createContext("/", new PageHandler());
        httpServer.createContext("/dashboard", new PageHandler());
        httpServer.createContext("/metrics", new JsonHandler());
        httpServer.createContext("/health", new HealthHandler());

        // Virtual threads: a dashboard left open in a browser tab polling every second must not hold a
        // platform thread hostage, and this executor must never compete with the cache's own carriers.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(executor);
        httpServer.start();

        this.server = httpServer;
        this.boundPort = httpServer.getAddress().getPort();
        LOG.info("FastCache console on http://{0}:{1}/dashboard", bindHost, boundPort);
        return boundPort;
    }

    public int port() {
        return boundPort;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Captures the current metrics under a given price profile. Exposed for tests and embedding. */
    public MetricsSnapshot snapshot(CostProfile profile) {
        return MetricsSnapshot.capture(engine.stats(), engine.savingsLedger(), profile,
                startedAtMillis, version);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        HttpServer httpServer = server;
        if (httpServer != null) {
            httpServer.stop(STOP_DELAY_SECONDS);
        }
        ExecutorService pool = executor;
        if (pool != null) {
            pool.shutdownNow();
        }
        LOG.info("FastCache console stopped.");
    }

    // ---------------------------------------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------------------------------------

    private CostProfile profileFor(HttpExchange exchange) {
        String model = queryParam(exchange.getRequestURI(), "model");
        return model == null ? defaultProfile : CostProfile.parse(model);
    }

    private final class JsonHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!guardGet(exchange)) {
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8",
                    snapshot(profileFor(exchange)).toJson());
        }
    }

    private final class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!guardGet(exchange)) {
                return;
            }
            respond(exchange, 200, "text/html; charset=utf-8",
                    Dashboard.render(snapshot(profileFor(exchange))));
        }
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!guardGet(exchange)) {
                return;
            }
            boolean healthy = !engine.isClosed();
            respond(exchange, healthy ? 200 : 503, "application/json; charset=utf-8",
                    "{\"status\":\"" + (healthy ? "UP" : "DOWN") + "\",\"uptime_ms\":"
                            + (System.currentTimeMillis() - startedAtMillis) + "}");
        }
    }

    /** @return false when the request was rejected and the exchange is already closed */
    private static boolean guardGet(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        // The console is strictly read-only. Refusing non-GET outright means no amount of creative
        // request crafting can mutate cache state through this port.
        exchange.getResponseHeaders().add("Allow", "GET");
        respond(exchange, 405, "text/plain; charset=utf-8", "FastCache console is read-only.\n");
        return false;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Live counters must never be served from a browser or proxy cache — a cached dashboard showing
        // frozen numbers is worse than no dashboard, because it looks like the engine has stalled.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }

    /** Minimal query parsing; no dependency, and the console has exactly one parameter. */
    private static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                params.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
            }
        }
        return params.get(name);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
