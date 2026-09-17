package io.fastcache.engine.core;

/** Per-shard counters. Exposed over the STATS opcode and by the Spring actuator contributor. */
public record ShardStats(
        int index,
        int entries,
        long bytes,
        long hits,
        long staleHits,
        long misses,
        long writes,
        long ttlEvictions,
        long lruEvictions) {

    public double hitRatio() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / (double) total;
    }
}
