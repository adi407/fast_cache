package io.fastcache.engine.net;

import io.fastcache.engine.core.EngineConfig;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.metrics.CostProfile;
import io.fastcache.engine.metrics.MetricsServer;
import io.fastcache.engine.util.FastCacheLog;
import io.fastcache.engine.util.TimeSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point of the sidecar JAR &mdash; the process the Python client boots on {@code import fastcache}.
 *
 * <p>Two contracts the Python bootstrapper depends on, both deliberately dumb and parse-proof:
 * <ol>
 *   <li>A single handshake line on stdout, flushed the instant the port is bound:
 *       {@code FASTCACHE_READY port=<p> pid=<pid> shards=<n> version=<v>}. The client reads stdout until it
 *       sees this, which is what makes {@code --port 0} (OS-assigned ephemeral port) usable.</li>
 *   <li>A state file written atomically (temp file + {@code ATOMIC_MOVE}) so a second Python process can
 *       discover an already-running sidecar and reuse it instead of starting a duplicate. Atomicity matters:
 *       a half-written state file read by a racing interpreter would send it to a nonexistent port.</li>
 * </ol>
 *
 * <p>An idle watchdog reaps the process once no client has spoken for {@code --idle-timeout}, so an
 * abandoned notebook does not leave a multi-gigabyte JVM resident forever. Set it to {@code 0} for a
 * long-lived shared sidecar.
 */
public final class SidecarMain {

    private static final FastCacheLog LOG = FastCacheLog.of(SidecarMain.class);
    private static final String VERSION = "1.1.0";

    private SidecarMain() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        if (options.containsKey("help")) {
            printUsage();
            return;
        }

        String host = options.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(options.getOrDefault("port", "0"));
        long idleTimeoutMillis = TimeSpec.parseMillis(options.getOrDefault("idle-timeout", "30m"));
        long heartbeatWindowMillis = TimeSpec.parseMillis(options.getOrDefault("heartbeat-timeout", "0"));
        long parentPid = Long.parseLong(options.getOrDefault("parent-pid", "0"));
        Path stateFile = options.containsKey("state-file") ? Path.of(options.get("state-file")) : null;

        EngineConfig config = buildConfig(options);
        ShardedStorageEngine engine = new ShardedStorageEngine(config);
        FastCacheServer server = new FastCacheServer(engine, host, port);
        MetricsServer console = buildConsole(engine, options);
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Ordering: stop accepting first, then release native memory. Reversed, an in-flight GET
            // could read a slot that has already been unmapped. The console goes first of all: it only
            // reads state, so there is no reason to let it observe a half-torn-down engine.
            if (console != null) {
                console.close();
            }
            server.close();
            engine.close();
            if (stateFile != null) {
                try {
                    Files.deleteIfExists(stateFile);
                } catch (IOException ignored) {
                    // A stale state file is handled by the client's liveness probe.
                }
            }
            shutdownLatch.countDown();
        }, "fastcache-shutdown"));

        try {
            int boundPort = server.start();
            int consolePort = startConsole(console);
            if (stateFile != null) {
                writeStateFile(stateFile, host, boundPort, consolePort, config);
            }
            announce(boundPort, consolePort, config);
            if (idleTimeoutMillis != TimeSpec.NEVER && idleTimeoutMillis > 0) {
                startIdleWatchdog(server, idleTimeoutMillis);
            }
            // Orphan isolation. Armed only when the launcher asks for it, because a sidecar with no
            // heartbeating parent (embedded in Spring, or shared by a fleet of workers) would
            // otherwise reap itself 40 seconds after a perfectly healthy startup.
            new OrphanWatchdog(server, heartbeatWindowMillis, parentPid).start();
            shutdownLatch.await();
        } catch (IOException e) {
            System.err.println("FASTCACHE_FAILED " + e.getMessage());
            LOG.error("Sidecar failed to bind " + host + ":" + port, e);
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EngineConfig buildConfig(Map<String, String> options) {
        EngineConfig fromEnv = EngineConfig.fromEnvironment();
        EngineConfig.Builder builder = EngineConfig.builder()
                .shardCount(fromEnv.shardCount())
                .maxOffHeapBytes(fromEnv.maxOffHeapBytes())
                .memoryRejectRatio(fromEnv.memoryRejectRatio())
                .maxEntriesPerShard(fromEnv.maxEntriesPerShard())
                .maxValueBytes(fromEnv.maxValueBytes())
                .defaultTtlMillis(fromEnv.defaultTtlMillis())
                .sweepIntervalMillis(fromEnv.sweepIntervalMillis())
                .lruEnabled(fromEnv.lruEnabled())
                .hashSpreading(fromEnv.hashSpreading())
                .staleGraceMillis(fromEnv.staleGraceMillis())
                .refreshLeaseMillis(fromEnv.refreshLeaseMillis());

        if (options.containsKey("shards")) {
            builder.shardCount(Integer.parseInt(options.get("shards")));
        }
        if (options.containsKey("offheap-max")) {
            builder.maxOffHeapBytes(EngineConfig.parseByteSize(options.get("offheap-max")));
        }
        if (options.containsKey("reject-ratio")) {
            builder.memoryRejectRatio(Double.parseDouble(options.get("reject-ratio")));
        }
        if (options.containsKey("default-ttl")) {
            builder.defaultTtl(options.get("default-ttl"));
        }
        if (options.containsKey("max-value-bytes")) {
            builder.maxValueBytes((int) EngineConfig.parseByteSize(options.get("max-value-bytes")));
        }
        if (options.containsKey("hash-spreading")) {
            builder.hashSpreading(Boolean.parseBoolean(options.get("hash-spreading")));
        }
        if (options.containsKey("stale-grace")) {
            builder.staleGraceMillis(TimeSpec.parseMillis(options.get("stale-grace")));
        }
        if (options.containsKey("refresh-lease")) {
            builder.refreshLeaseMillis(TimeSpec.parseMillis(options.get("refresh-lease")));
        }
        return builder.build();
    }

    /**
     * Starts the console, tolerating a port clash.
     *
     * @return the bound console port, or -1 when disabled or unavailable
     */
    private static int startConsole(MetricsServer console) {
        if (console == null) {
            return -1;
        }
        try {
            return console.start();
        } catch (IOException e) {
            // A busy metrics port must never stop the cache from serving. This is by far the most
            // likely startup failure - a second sidecar, or anything else already on 8081 - and the
            // cache itself is entirely unaffected by it.
            LOG.warn("Console could not bind ({0}); continuing without it.", e.toString());
            return -1;
        }
    }

    /** Builds the console, or null when {@code --metrics-port off} was passed. */
    private static MetricsServer buildConsole(ShardedStorageEngine engine, Map<String, String> options) {
        String raw = options.getOrDefault("metrics-port", "8081");
        if (raw.equalsIgnoreCase("off") || raw.equals("-1")) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            port = 8081;
        }
        CostProfile profile = CostProfile.parse(options.getOrDefault("cost-model", "gpt-4o"));
        String metricsHost = options.getOrDefault("metrics-host", "127.0.0.1");
        return new MetricsServer(engine, metricsHost, port, profile, VERSION);
    }

    /** The handshake. Printed to stdout and flushed; the Python bootstrapper blocks on exactly this line. */
    private static void announce(int port, int consolePort, EngineConfig config) {
        System.out.println("FASTCACHE_READY"
                + " port=" + port
                + " pid=" + ProcessHandle.current().pid()
                + " shards=" + config.shardCount()
                + " offheap=" + config.maxOffHeapBytes()
                + " console=" + consolePort
                + " version=" + VERSION);
        System.out.flush();
    }

    /**
     * Writes the discovery file atomically. A minimal hand-rolled JSON object keeps the engine JAR free of
     * a JSON dependency; the five fields here are stable wire contract with the Python side.
     */
    private static void writeStateFile(Path stateFile, String host, int port, int consolePort,
                                       EngineConfig config) throws IOException {
        Path parent = stateFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = "{"
                + "\"host\":\"" + host + "\","
                + "\"port\":" + port + ","
                + "\"pid\":" + ProcessHandle.current().pid() + ","
                + "\"shards\":" + config.shardCount() + ","
                + "\"offheap_max\":" + config.maxOffHeapBytes() + ","
                + "\"console_port\":" + consolePort + ","
                + "\"version\":\"" + VERSION + "\","
                + "\"started_at\":" + System.currentTimeMillis()
                + "}";
        Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent, "fastcache-", ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // Some Windows filesystems refuse ATOMIC_MOVE across handles; fall back and accept the tiny race.
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Exits the JVM once the sidecar has been idle (no live connections and no traffic) for the configured
     * window. Runs on a virtual thread; a platform thread here would keep the JVM alive on its own.
     */
    private static void startIdleWatchdog(FastCacheServer server, long idleTimeoutMillis) {
        Thread.ofVirtual().name("fastcache-idle-watchdog").start(() -> {
            long checkInterval = Math.max(1_000L, idleTimeoutMillis / 10);
            while (server.isRunning()) {
                try {
                    Thread.sleep(checkInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boolean idle = server.liveConnections() == 0
                        && System.currentTimeMillis() - server.lastActivityMillis() > idleTimeoutMillis;
                if (idle) {
                    LOG.info("Sidecar idle for {0}; shutting down to release memory.",
                            TimeSpec.format(idleTimeoutMillis));
                    System.exit(0); // Triggers the shutdown hook, which releases every native slot.
                }
            }
        });
    }

    /** Accepts {@code --key=value}, {@code --key value} and bare {@code --flag}. */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String body = arg.substring(2);
            int equals = body.indexOf('=');
            if (equals >= 0) {
                parsed.put(body.substring(0, equals), body.substring(equals + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                parsed.put(body, args[++i]);
            } else {
                parsed.put(body, "true");
            }
        }
        return parsed;
    }

    private static void printUsage() {
        System.out.println("""
                FastCache sidecar %s

                  --host <addr>            bind address (default 127.0.0.1; loopback only unless overridden)
                  --port <n>               listen port, 0 = OS-assigned ephemeral (default 0)
                  --shards <n>             vertical shard count (default 32)
                  --offheap-max <size>     native budget, e.g. 4g / 512m (default: JVM max heap, clamped)
                  --reject-ratio <0..1>    memory utilisation at which writes are shed (default 0.85)
                  --default-ttl <spec>     fallback TTL, e.g. 15m (default 15m)
                  --max-value-bytes <size> largest accepted payload (default 64m)
                  --hash-spreading <bool>  murmur3-finalize key hashes before routing (default true)
                  --stale-grace <spec>     serve-stale window during a refresh (default 2s, 0 disables)
                  --refresh-lease <spec>   how long one refresh may hold its lease (default 10s)
                  --idle-timeout <spec>    self-terminate after this idle period, 0 = never (default 30m)
                  --heartbeat-timeout <s>  orphan watchdog: exit when no client traffic arrives for this
                                           long (0 = disabled; the Python autopilot passes 40s)
                  --parent-pid <pid>       also exit the moment this process disappears (hard-kill defence)
                  --metrics-port <n>       console port, 0 = ephemeral, 'off' disables (default 8081)
                  --metrics-host <addr>    console bind address (default 127.0.0.1)
                  --cost-model <spec>      gpt-4o | claude-3-5-sonnet | <name>:<usd-per-million-tokens>
                  --state-file <path>      discovery file for client auto-attach
                """.formatted(VERSION));
    }
}
