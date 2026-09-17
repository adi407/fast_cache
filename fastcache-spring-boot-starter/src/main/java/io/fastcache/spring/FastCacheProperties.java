package io.fastcache.spring;

import io.fastcache.engine.core.EngineConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Optional tuning, bound from {@code application.yml} under the {@code fastcache} prefix.
 *
 * <p>Every field already carries a production-sane default, so an application that sets none of them gets
 * a correctly configured 32-shard engine. This class exists for the day someone needs 128 shards, not for
 * day one.
 *
 * <pre>{@code
 * fastcache:
 *   shards: 64
 *   off-heap-max: 8GB
 *   default-ttl: 15m
 *   server:
 *     enabled: true      # also serve the TCP sidecar protocol from inside this JVM
 *     port: 7431
 *   stale-grace: 2s      # serve-stale window that collapses a thundering herd
 *   mutation-guard: AUTO # AUTO | STRICT | OFF
 * }</pre>
 */
@ConfigurationProperties(prefix = "fastcache")
public class FastCacheProperties {

    /** Master switch. When false the aspect passes every call straight through. */
    private boolean enabled = true;

    /** Number of vertical shards. Powers of two distribute best across the hash router. */
    private int shards = EngineConfig.DEFAULT_SHARD_COUNT;

    /** Native memory budget for off-heap payloads. Ignored by the pure in-process reference path. */
    private DataSize offHeapMax = DataSize.ofMegabytes(512);

    /** Memory utilisation at which writes are shed rather than risking an OOM. */
    private double rejectRatio = EngineConfig.DEFAULT_REJECT_RATIO;

    /** TTL applied when {@code @FastCache} does not specify one. */
    private String defaultTtl = "15m";

    /** Entry ceiling per shard; overflow is evicted least-recently-used first. */
    private int maxEntriesPerShard = 100_000;

    /** Largest single payload accepted over the socket protocol. */
    private DataSize maxValueSize = DataSize.ofMegabytes(64);

    /** Cadence of the background eviction sweeper. */
    private Duration sweepInterval = Duration.ofSeconds(1);

    /**
     * Murmur3-finalize key hashes before shard routing. <b>On by default</b>: keys differing only in a
     * numeric suffix cluster badly under a raw {@code String.hashCode} modulo (measured 2.3x max/mean
     * across 32 shards, versus 1.2x with the finalizer). Disable only to reproduce a legacy key layout.
     */
    private boolean hashSpreading = true;

    /**
     * How long an expired entry may still be served while one caller refreshes it. This is the
     * stampede defence: without a grace window, every concurrent reader of a hot key misses at the same
     * instant and calls the backend simultaneously.
     */
    private Duration staleGrace = Duration.ofSeconds(2);

    /** How long one refresh may hold its lease before another caller may take over from a stuck leader. */
    private Duration refreshLease = Duration.ofSeconds(10);

    /**
     * Protection against a caller mutating a cached object and corrupting it for everyone else.
     *
     * <p>{@code AUTO} (default) serves immutable types by reference at zero cost, deep-copies mutable
     * Serializable ones, and refuses to cache mutable types it cannot copy. {@code STRICT} copies
     * everything non-immutable. {@code OFF} trusts the caller entirely and is the fastest.
     */
    private MutationGuard.Policy mutationGuard = MutationGuard.Policy.AUTO;

    private final Server server = new Server();

    /** Embedded sidecar listener: lets Python processes on the same host share this JVM's cache. */
    public static class Server {

        /** Off by default: an in-process cache should not open a port unless asked. */
        private boolean enabled = false;

        /** Loopback by default. The protocol is unauthenticated; do not bind it to a public interface. */
        private String host = "127.0.0.1";

        /** 0 lets the OS assign an ephemeral port. */
        private int port = 7431;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    /** Projects these properties onto the engine's own immutable configuration record. */
    public EngineConfig toEngineConfig() {
        return EngineConfig.builder()
                .shardCount(shards)
                .maxOffHeapBytes(offHeapMax.toBytes())
                .memoryRejectRatio(rejectRatio)
                .maxEntriesPerShard(maxEntriesPerShard)
                .maxValueBytes((int) Math.min(Integer.MAX_VALUE, maxValueSize.toBytes()))
                .defaultTtl(defaultTtl)
                .sweepIntervalMillis(sweepInterval.toMillis())
                .hashSpreading(hashSpreading)
                .staleGraceMillis(staleGrace.toMillis())
                .refreshLeaseMillis(refreshLease.toMillis())
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getShards() {
        return shards;
    }

    public void setShards(int shards) {
        this.shards = shards;
    }

    public DataSize getOffHeapMax() {
        return offHeapMax;
    }

    public void setOffHeapMax(DataSize offHeapMax) {
        this.offHeapMax = offHeapMax;
    }

    public double getRejectRatio() {
        return rejectRatio;
    }

    public void setRejectRatio(double rejectRatio) {
        this.rejectRatio = rejectRatio;
    }

    public String getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(String defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public int getMaxEntriesPerShard() {
        return maxEntriesPerShard;
    }

    public void setMaxEntriesPerShard(int maxEntriesPerShard) {
        this.maxEntriesPerShard = maxEntriesPerShard;
    }

    public DataSize getMaxValueSize() {
        return maxValueSize;
    }

    public void setMaxValueSize(DataSize maxValueSize) {
        this.maxValueSize = maxValueSize;
    }

    public Duration getSweepInterval() {
        return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
    }

    public boolean isHashSpreading() {
        return hashSpreading;
    }

    public void setHashSpreading(boolean hashSpreading) {
        this.hashSpreading = hashSpreading;
    }

    public Duration getStaleGrace() {
        return staleGrace;
    }

    public void setStaleGrace(Duration staleGrace) {
        this.staleGrace = staleGrace;
    }

    public Duration getRefreshLease() {
        return refreshLease;
    }

    public void setRefreshLease(Duration refreshLease) {
        this.refreshLease = refreshLease;
    }

    public MutationGuard.Policy getMutationGuard() {
        return mutationGuard;
    }

    public void setMutationGuard(MutationGuard.Policy mutationGuard) {
        this.mutationGuard = mutationGuard;
    }

    public Server getServer() {
        return server;
    }
}
