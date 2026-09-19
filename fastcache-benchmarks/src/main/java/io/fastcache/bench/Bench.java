package io.fastcache.bench;

import io.fastcache.engine.memory.OffHeapAllocator;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Benchmark entry point.
 *
 * <pre>
 *   java -cp ... io.fastcache.bench.Bench --scenario A --implementation all --payload-size 1MB,10MB
 * </pre>
 *
 * <p>Every run prints its full environment first, because a benchmark result without the JVM flags that
 * produced it is an anecdote.
 */
public final class Bench {

    private static final long MB = 1 << 20;

    private static final int[] DEFAULT_SIZES = {
            256 * 1024, 1 << 20, 5 << 20, 10 << 20, 25 << 20, 50 << 20, 100 << 20
    };

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) {
            usage();
            return;
        }

        List<Integer> sizes = options.containsKey("payload-size")
                ? parseSizes(options.get("payload-size"))
                : Arrays.stream(DEFAULT_SIZES).boxed().toList();
        List<String> arms = options.containsKey("implementation")
                ? Arrays.asList(options.get("implementation").split(","))
                : List.of("caffeine", "fastcache-embedded", "fastcache-sidecar");
        if (arms.size() == 1 && arms.get(0).equals("all")) {
            arms = List.of("caffeine", "fastcache-embedded", "fastcache-sidecar");
        }
        if (arms.size() == 1 && arms.get(0).equals("cross-process")) {
            // The two arms that can actually share a cache between processes. Caffeine cannot appear.
            arms = List.of("fastcache-sidecar", "redis");
        }
        String scenario = options.getOrDefault("scenario", "A").toUpperCase(Locale.ROOT);
        long budget = parseSize(options.getOrDefault("budget", "1g"));
        long target = parseSize(options.getOrDefault("target", "512m"));
        long durationMillis = Long.parseLong(options.getOrDefault("duration", "60")) * 1000L;
        int concurrency = Integer.parseInt(options.getOrDefault("concurrency", "8"));
        int repeat = Integer.parseInt(options.getOrDefault("repeat", "1"));
        int operations = Integer.parseInt(options.getOrDefault("operations", "2000"));
        Path jar = Path.of(options.getOrDefault("jar",
                "fastcache-engine/target/fastcache-engine.jar"));
        Path csv = options.containsKey("csv") ? Path.of(options.get("csv")) : null;
        // Phase 7 established that the default 0.85 guard refuses writes on this class of host once the
        // engine holds more than max(64 MiB, 5% of budget), whatever the budget says. That is a real
        // finding, and it is also a confound for a GC comparison: an arm that cannot hold the working set
        // is not being compared on GC, it is being compared on admission control. Raising this to 1.0
        // isolates the GC question; the admission question is measured separately by GuardProbe.
        double rejectRatio = Double.parseDouble(options.getOrDefault("reject-ratio", "0.85"));
        double readRatio = Double.parseDouble(options.getOrDefault("read-ratio", "1.0"));
        String redisHost = options.getOrDefault("redis-host", "127.0.0.1");
        int redisPort = Integer.parseInt(options.getOrDefault("redis-port", "6399"));
        long redisPid = Long.parseLong(options.getOrDefault("redis-pid", "0"));

        printEnvironment(budget, target, concurrency, durationMillis, repeat, rejectRatio,
                redisHost, redisPort, redisPid);

        List<Scenarios.Result> results = new ArrayList<>();
        for (int run = 1; run <= repeat; run++) {
            System.out.printf("%n--- run %d of %d -------------------------------------------------%n",
                    run, repeat);
            for (int size : sizes) {
                // Rotate the arm order every run. Within one JVM the later cells execute against a
                // hotter JIT, so a fixed order hands the last arm a systematic advantage. Measured on
                // the first pass: run 1 was 10-47% slower than run 3 for every arm, and Caffeine drifted
                // 93%. Rotating cancels the order effect instead of hoping it is small.
                List<String> ordered = rotate(arms, run - 1);
                for (String armName : ordered) {
                    try (CacheArm arm = build(armName, budget, jar, size, rejectRatio,
                            redisHost, redisPort, redisPid);
                         Probe probe = new Probe()) {
                        Scenarios.Result result = switch (scenario) {
                            case "A" -> Scenarios.fill(arm, size, target, probe);
                            case "B" -> Scenarios.read(arm, size, target, operations, concurrency, probe);
                            case "C" -> Scenarios.churn(arm, size, target, durationMillis, concurrency,
                                    probe);
                            case "D" -> Scenarios.eviction(arm, size, budget, probe);
                            case "E" -> Scenarios.expiration(arm, size, target, probe);
                            case "F" -> Scenarios.replacement(arm, size,
                                    Integer.parseInt(options.getOrDefault("cycles", "200")), probe);
                            case "G" -> Scenarios.clear(arm, size, target, probe);
                            case "H" -> Scenarios.churn(arm, size, target,
                                    Math.max(durationMillis, 1_800_000L), concurrency, probe);
                            // M reports GET and SET percentiles separately; --read-ratio picks the mix.
                            case "M" -> Scenarios.mixed(arm, size, target, operations, concurrency,
                                    readRatio, probe);
                            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
                        };
                        result.with("run", run);
                        result.with("armOrder", ordered.indexOf(armName));
                        results.add(result);
                        System.out.println(result.line());
                    } catch (Exception e) {
                        System.out.printf("  %-22s %-20s %-6s FAILED: %s%n",
                                scenario, armName, Payloads.label(size), e);
                    }
                }
            }
        }

        if (csv != null) {
            writeCsv(csv, results);
            System.out.println("\nwrote " + results.size() + " rows to " + csv);
        }
    }

    /** Left-rotates by {@code by}, so each run starts with a different arm. */
    private static List<String> rotate(List<String> items, int by) {
        if (items.isEmpty()) {
            return items;
        }
        int shift = Math.floorMod(by, items.size());
        List<String> rotated = new ArrayList<>(items.subList(shift, items.size()));
        rotated.addAll(items.subList(0, shift));
        return rotated;
    }

    private static CacheArm build(String name, long budget, Path jar, int payloadBytes,
                                  double rejectRatio, String redisHost, int redisPort, long redisPid)
            throws IOException {
        return switch (name) {
            case "caffeine" -> new CaffeineArm(budget);
            // Entry ceiling chosen so the arm holds the same BYTE budget as Caffeine at this payload size.
            // FastCache cannot bound by bytes on the heap path, so this is the benchmark supplying by hand
            // a bound the implementation does not have. Noted in the results.
            case "fastcache-embedded" -> new FastCacheEmbeddedArm(budget,
                    Math.max(1, (int) (budget / payloadBytes / 32) + 1));
            case "fastcache-sidecar" -> new FastCacheSidecarArm(jar, budget,
                    Math.max(payloadBytes * 2L, 256L << 20), rejectRatio,
                    Math.max(512L << 20, budget));
            // Redis is not started by the benchmark: it is a real server the operator runs, and a
            // failure to reach it is reported rather than silently skipped.
            case "redis" -> new RedisArm(redisHost, redisPort, redisPid);
            default -> throw new IllegalArgumentException("unknown implementation " + name);
        };
    }

    private static void printEnvironment(long budget, long target, int concurrency, long durationMillis,
                                         int repeat, double rejectRatio, String redisHost, int redisPort,
                                         long redisPid) {
        System.out.println("=".repeat(100));
        System.out.println("FastCache JVM memory / GC benchmark");
        System.out.println("=".repeat(100));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("timestamp", Instant.now().toString());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        env.put("cpus", String.valueOf(Runtime.getRuntime().availableProcessors()));
        env.put("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        env.put("jvmFlags", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        env.put("maxHeapMB", String.valueOf(Runtime.getRuntime().maxMemory() / MB));
        env.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(java.lang.management.GarbageCollectorMXBean::getName).toList().toString());
        env.put("cacheBudgetMB", String.valueOf(budget / MB));
        env.put("targetResidentMB", String.valueOf(target / MB));
        env.put("concurrency", String.valueOf(concurrency));
        env.put("durationSeconds", String.valueOf(durationMillis / 1000));
        env.put("repeat", String.valueOf(repeat));
        env.put("sidecarRejectRatio", String.valueOf(rejectRatio));
        env.put("redisEndpoint", redisHost + ":" + redisPort + " pid=" + redisPid);
        env.put("deterministicOffHeapFree", String.valueOf(OffHeapAllocator.supportsDeterministicFree()));
        env.put("totalPhysicalMB", String.valueOf(
                io.fastcache.engine.memory.PhysicalMemory.totalBytes() / MB));
        env.put("physicalUsedRatio", String.format("%.4f",
                io.fastcache.engine.memory.PhysicalMemory.usedRatio()));
        env.forEach((key, value) -> System.out.printf("  %-24s %s%n", key, value));

        if (!OffHeapAllocator.supportsDeterministicFree()) {
            System.out.println("\n  *** WARNING: deterministic off-heap free is UNAVAILABLE on this JVM.");
            System.out.println("  *** FastCache's central design claim does not hold in this mode.");
            System.out.println("  *** Results below must not be read as evidence for or against it.\n");
        }
        System.out.println("=".repeat(100));
    }

    private static void writeCsv(Path path, List<Scenarios.Result> results) throws IOException {
        if (results.isEmpty()) {
            return;
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        List<String> columns = new ArrayList<>(results.stream()
                .flatMap(result -> result.values().keySet().stream())
                .distinct().toList());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path))) {
            writer.println("scenario,arm,payloadBytes," + String.join(",", columns));
            for (Scenarios.Result result : results) {
                StringBuilder row = new StringBuilder();
                row.append(result.scenario()).append(',').append(result.arm()).append(',')
                        .append(result.payloadBytes());
                for (String column : columns) {
                    row.append(',').append(result.values().getOrDefault(column, ""));
                }
                writer.println(row);
            }
        }
    }

    private static List<Integer> parseSizes(String spec) {
        List<Integer> sizes = new ArrayList<>();
        for (String token : spec.split(",")) {
            sizes.add((int) parseSize(token.trim()));
        }
        return sizes;
    }

    private static long parseSize(String spec) {
        String text = spec.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        if (text.endsWith("k") || text.endsWith("kb")) {
            multiplier = 1024;
        } else if (text.endsWith("m") || text.endsWith("mb")) {
            multiplier = 1 << 20;
        } else if (text.endsWith("g") || text.endsWith("gb")) {
            multiplier = 1 << 30;
        }
        return Long.parseLong(text.replaceAll("[^0-9]", "")) * multiplier;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    private static void usage() {
        System.out.println("""
                FastCache JVM memory / GC benchmark

                  --scenario <A-H|M>        A fill, B read, C churn, D eviction, E expiration,
                                            F replacement, G clear, H long churn (>=30m),
                                            M mixed read/write with GET and SET timed separately
                  --implementation <list>   caffeine,fastcache-embedded,fastcache-sidecar,redis
                                            ('all' = the first three; 'cross-process' = sidecar + redis)
                  --redis-host <addr>       redis host (default 127.0.0.1)
                  --redis-port <n>          redis port (default 6399)
                  --redis-pid <pid>         redis server pid, so its RSS can be reported
                  --payload-size <list>     e.g. 256k,1m,10m,50m   (default: all seven sizes)
                  --budget <size>           cache memory budget, applied to every arm (default 1g)
                  --target <size>           resident payload volume to fill to (default 512m)
                  --duration <seconds>      scenario C/H run length (default 60)
                  --concurrency <n>         worker threads (default 8)
                  --operations <n>          scenario B/M operation count (default 2000)
                  --read-ratio <0..1>       scenario M read share: 1.0 pure GET, 0.0 pure SET,
                                            0.9 read-heavy, 0.5 balanced (default 1.0)
                  --cycles <n>              scenario F replacement cycles (default 200)
                  --repeat <n>              repeat the whole matrix n times (default 1)
                  --reject-ratio <0..1>     sidecar memory-guard reject ratio (default 0.85; 1.0 disables
                                            admission control, to isolate GC from shedding)
                  --jar <path>              fastcache-engine.jar for the sidecar arm
                  --csv <path>              also write results as CSV
                """);
    }
}
