package io.fastcache.engine.core;

import io.fastcache.engine.util.TimeSpec;

/**
 * Engine tuning surface. Every value has a defensible production default, so the zero-configuration
 * promise holds: {@code EngineConfig.defaults()} is a valid production configuration.
 *
 * @param shardCount          number of vertical shards (default 32); higher values reduce map contention
 *                            at the cost of a larger fixed footprint and a longer sweep cycle
 * @param maxOffHeapBytes     total native budget across all shards
 * @param memoryRejectRatio   utilisation at which writes start being rejected (default 0.85)
 * @param maxEntriesPerShard  per-shard entry ceiling that triggers LRU eviction
 * @param maxValueBytes       largest single payload accepted (default 64 MiB, sized for 50 MB contexts)
 * @param defaultTtlMillis    TTL applied when a caller does not specify one
 * @param sweepIntervalMillis cadence of the background eviction sweeper
 * @param lruEnabled          whether capacity overflow evicts by recency (false = TTL-only)
 * @param hashSpreading       applies the murmur3 32-bit finalizer to the key hash before the modulo.
 *                            <b>Default: true.</b> See {@link ShardedStorageEngine#shardIndexFor(String)}
 *                            for the measured skew table; disable only to reproduce legacy placement
 * @param staleGraceMillis    how long an expired entry stays servable while a single refresher recomputes
 *                            it (stale-while-revalidate window; default 2s, 0 disables)
 * @param refreshLeaseMillis  how long one refresh leadership lasts before another caller may steal it,
 *                            so a crashed or hung leader cannot block a key forever
 */
public record EngineConfig(
        int shardCount,
        long maxOffHeapBytes,
        double memoryRejectRatio,
        int maxEntriesPerShard,
        int maxValueBytes,
        long defaultTtlMillis,
        long sweepIntervalMillis,
        boolean lruEnabled,
        boolean hashSpreading,
        long staleGraceMillis,
        long refreshLeaseMillis) {

    public static final int DEFAULT_SHARD_COUNT = 32;
    public static final int DEFAULT_MAX_VALUE_BYTES = 64 * 1024 * 1024;
    public static final double DEFAULT_REJECT_RATIO = 0.85d;
    public static final long DEFAULT_STALE_GRACE_MILLIS = 2_000L;
    public static final long DEFAULT_REFRESH_LEASE_MILLIS = 10_000L;

    public EngineConfig {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive: " + shardCount);
        }
        if (maxOffHeapBytes <= 0) {
            throw new IllegalArgumentException("maxOffHeapBytes must be positive: " + maxOffHeapBytes);
        }
        if (memoryRejectRatio <= 0 || memoryRejectRatio > 1.0) {
            throw new IllegalArgumentException("memoryRejectRatio must be in (0,1]: " + memoryRejectRatio);
        }
        if (maxEntriesPerShard <= 0) {
            throw new IllegalArgumentException("maxEntriesPerShard must be positive: " + maxEntriesPerShard);
        }
        if (maxValueBytes <= 0) {
            throw new IllegalArgumentException("maxValueBytes must be positive: " + maxValueBytes);
        }
        if (sweepIntervalMillis <= 0) {
            throw new IllegalArgumentException("sweepIntervalMillis must be positive: " + sweepIntervalMillis);
        }
        if (staleGraceMillis < 0) {
            throw new IllegalArgumentException("staleGraceMillis must not be negative: " + staleGraceMillis);
        }
        if (refreshLeaseMillis <= 0) {
            throw new IllegalArgumentException("refreshLeaseMillis must be positive: " + refreshLeaseMillis);
        }
    }

    public static EngineConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads overrides from system properties ({@code -Dfastcache.shards=64}) falling back to environment
     * variables ({@code FASTCACHE_SHARDS}). This is what the sidecar JAR uses, so the Python bootstrapper
     * can tune the engine purely through the process launch.
     */
    public static EngineConfig fromEnvironment() {
        Builder builder = new Builder();
        builder.shardCount((int) longSetting("fastcache.shards", "FASTCACHE_SHARDS", DEFAULT_SHARD_COUNT));
        builder.maxOffHeapBytes(longSetting("fastcache.offheap.max", "FASTCACHE_OFFHEAP_MAX", defaultBudget()));
        builder.memoryRejectRatio(doubleSetting("fastcache.memory.reject-ratio", "FASTCACHE_REJECT_RATIO",
                DEFAULT_REJECT_RATIO));
        builder.maxEntriesPerShard((int) longSetting("fastcache.shard.max-entries", "FASTCACHE_SHARD_MAX_ENTRIES",
                100_000));
        builder.maxValueBytes((int) longSetting("fastcache.value.max-bytes", "FASTCACHE_VALUE_MAX_BYTES",
                DEFAULT_MAX_VALUE_BYTES));
        String ttl = stringSetting("fastcache.ttl.default", "FASTCACHE_DEFAULT_TTL", "15m");
        builder.defaultTtl(ttl);
        builder.sweepIntervalMillis(longSetting("fastcache.sweep.interval-ms", "FASTCACHE_SWEEP_INTERVAL_MS", 1_000L));
        builder.hashSpreading(Boolean.parseBoolean(
                stringSetting("fastcache.hash-spreading", "FASTCACHE_HASH_SPREADING", "true")));
        builder.staleGraceMillis(TimeSpec.parseMillis(
                stringSetting("fastcache.stale-grace", "FASTCACHE_STALE_GRACE", "2s")));
        builder.refreshLeaseMillis(TimeSpec.parseMillis(
                stringSetting("fastcache.refresh-lease", "FASTCACHE_REFRESH_LEASE", "10s")));
        return builder.build();
    }

    /** Half of physical memory, clamped to [64 MiB, 16 GiB], is a safe zero-config native budget. */
    private static long defaultBudget() {
        long maxDirect = Runtime.getRuntime().maxMemory();
        long candidate = Math.max(maxDirect, 512L * 1024 * 1024);
        return Math.min(Math.max(candidate, 64L * 1024 * 1024), 16L * 1024 * 1024 * 1024);
    }

    private static String stringSetting(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long longSetting(String property, String env, long fallback) {
        String raw = stringSetting(property, env, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return parseByteSize(raw);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double doubleSetting(String property, String env, double fallback) {
        String raw = stringSetting(property, env, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Accepts {@code 512m}, {@code 4g}, {@code 1048576}. */
    public static long parseByteSize(String raw) {
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        long multiplier = 1L;
        if (s.endsWith("k") || s.endsWith("kb")) {
            multiplier = 1024L;
        } else if (s.endsWith("m") || s.endsWith("mb")) {
            multiplier = 1024L * 1024;
        } else if (s.endsWith("g") || s.endsWith("gb")) {
            multiplier = 1024L * 1024 * 1024;
        }
        String digits = s.replaceAll("[^0-9.]", "");
        return (long) (Double.parseDouble(digits) * multiplier);
    }

    /** Mutable builder; the built record stays immutable. */
    public static final class Builder {
        private int shardCount = DEFAULT_SHARD_COUNT;
        private long maxOffHeapBytes = defaultBudget();
        private double memoryRejectRatio = DEFAULT_REJECT_RATIO;
        private int maxEntriesPerShard = 100_000;
        private int maxValueBytes = DEFAULT_MAX_VALUE_BYTES;
        private long defaultTtlMillis = TimeSpec.parseMillis("15m");
        private long sweepIntervalMillis = 1_000L;
        private boolean lruEnabled = true;
        private boolean hashSpreading = true;
        private long staleGraceMillis = DEFAULT_STALE_GRACE_MILLIS;
        private long refreshLeaseMillis = DEFAULT_REFRESH_LEASE_MILLIS;

        public Builder shardCount(int value) {
            this.shardCount = value;
            return this;
        }

        public Builder maxOffHeapBytes(long value) {
            this.maxOffHeapBytes = value;
            return this;
        }

        public Builder memoryRejectRatio(double value) {
            this.memoryRejectRatio = value;
            return this;
        }

        public Builder maxEntriesPerShard(int value) {
            this.maxEntriesPerShard = value;
            return this;
        }

        public Builder maxValueBytes(int value) {
            this.maxValueBytes = value;
            return this;
        }

        public Builder defaultTtl(String spec) {
            this.defaultTtlMillis = TimeSpec.parseMillis(spec);
            return this;
        }

        public Builder defaultTtlMillis(long value) {
            this.defaultTtlMillis = value;
            return this;
        }

        public Builder sweepIntervalMillis(long value) {
            this.sweepIntervalMillis = value;
            return this;
        }

        public Builder lruEnabled(boolean value) {
            this.lruEnabled = value;
            return this;
        }

        public Builder hashSpreading(boolean value) {
            this.hashSpreading = value;
            return this;
        }

        public Builder staleGraceMillis(long value) {
            // TimeSpec.NEVER (-1) reads as "no grace window" here rather than "infinite staleness",
            // because serving an entry forever past its TTL is never what a TTL means.
            this.staleGraceMillis = Math.max(0L, value);
            return this;
        }

        public Builder refreshLeaseMillis(long value) {
            this.refreshLeaseMillis = value <= 0 ? DEFAULT_REFRESH_LEASE_MILLIS : value;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(shardCount, maxOffHeapBytes, memoryRejectRatio, maxEntriesPerShard,
                    maxValueBytes, defaultTtlMillis, sweepIntervalMillis, lruEnabled, hashSpreading,
                    staleGraceMillis, refreshLeaseMillis);
        }
    }
}
