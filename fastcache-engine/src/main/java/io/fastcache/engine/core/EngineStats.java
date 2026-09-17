package io.fastcache.engine.core;

import io.fastcache.engine.memory.MemoryPressure;

import java.util.List;
import java.util.Locale;

/** Engine-wide rollup plus the raw per-shard breakdown (useful for spotting a hot shard). */
public record EngineStats(
        int shardCount,
        long entries,
        long bytes,
        long hits,
        long staleHits,
        long misses,
        long writes,
        long ttlEvictions,
        long lruEvictions,
        long writeRejections,
        long liveOffHeapSlots,
        long refreshLeads,
        long herdSuppressed,
        int refreshesInFlight,
        MemoryPressure memory,
        List<ShardStats> shards) {

    public EngineStats {
        shards = List.copyOf(shards);
    }

    public double hitRatio() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / (double) total;
    }

    /**
     * Ratio of the largest shard to the mean. A value far above 1.0 means the key distribution is skewed
     * and one shard is carrying the load &mdash; the signal that a celebrity key needs client-side L1
     * caching rather than more shards.
     */
    public double shardSkew() {
        if (shards.isEmpty() || entries == 0) {
            return 1.0;
        }
        int max = shards.stream().mapToInt(ShardStats::entries).max().orElse(0);
        double mean = (double) entries / shards.size();
        return mean == 0 ? 1.0 : max / mean;
    }

    /** Compact single-line rendering used by the STATS wire response. */
    public String toWireString() {
        return "shards=" + shardCount
                + " entries=" + entries
                + " bytes=" + bytes
                + " hits=" + hits
                + " stale_hits=" + staleHits
                + " misses=" + misses
                + " writes=" + writes
                + " ttl_evictions=" + ttlEvictions
                + " lru_evictions=" + lruEvictions
                + " write_rejections=" + writeRejections
                + " offheap_slots=" + liveOffHeapSlots
                + " offheap_reserved=" + memory.reservedBytes()
                + " offheap_budget=" + memory.budgetBytes()
                + " refresh_leads=" + refreshLeads
                + " herd_suppressed=" + herdSuppressed
                + " refreshes_in_flight=" + refreshesInFlight
                + " memory_ratio=" + fixed(memory.effectiveRatio())
                + " rejecting=" + memory.rejecting()
                + " hit_ratio=" + fixed(hitRatio())
                + " shard_skew=" + String.format(Locale.ROOT, "%.2f", shardSkew());
    }

    private static String fixed(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
