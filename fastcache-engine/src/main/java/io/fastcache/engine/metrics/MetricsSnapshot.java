package io.fastcache.engine.metrics;

import io.fastcache.engine.core.EngineStats;
import io.fastcache.engine.core.ShardStats;
import io.fastcache.engine.memory.PhysicalMemory;

import java.util.Locale;

/**
 * One coherent read of everything the console shows, captured at a single instant.
 *
 * <p>Taken as a snapshot rather than queried field-by-field so the dashboard can never render an
 * internally inconsistent picture — hits from one moment against a memory reading from 40&nbsp;ms later is
 * how you end up with a hit ratio above 1.0 and a support ticket.
 *
 * <p>Serialises itself to JSON by hand. A JSON library would be the only runtime dependency in the entire
 * engine, for the sake of one endpoint emitting a fixed, flat object.
 */
public record MetricsSnapshot(
        long timestampMillis,
        long uptimeMillis,
        String version,
        EngineStats engine,
        SavingsLedger.Savings savings,
        long systemTotalBytes,
        long systemUsedBytes,
        long systemFreeBytes,
        long heapUsedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long offHeapReservedBytes,
        long offHeapBudgetBytes,
        long l1Hits,
        long l1Characters,
        int connectedClients,
        double processCpuRatio,
        double systemCpuRatio,
        long gcCollections,
        long gcTimeMillis,
        int platformThreads,
        String latencyJson) {

    public static MetricsSnapshot capture(EngineStats engine, SavingsLedger ledger, CostProfile profile,
                                          long startedAtMillis, String version) {
        return capture(engine, ledger, profile, startedAtMillis, version, "{}");
    }

    /**
     * @param latencyJson pre-rendered percentile object; the histograms live on the engine, which this
     *                    record deliberately does not hold a reference to so a snapshot stays a snapshot
     */
    public static MetricsSnapshot capture(EngineStats engine, SavingsLedger ledger, CostProfile profile,
                                          long startedAtMillis, String version, String latencyJson) {
        return new MetricsSnapshot(
                System.currentTimeMillis(),
                System.currentTimeMillis() - startedAtMillis,
                version,
                engine,
                ledger.computeSavings(profile),
                PhysicalMemory.totalBytes(),
                PhysicalMemory.usedBytes(),
                PhysicalMemory.freeBytes(),
                PhysicalMemory.heapUsedBytes(),
                PhysicalMemory.heapMaxBytes(),
                PhysicalMemory.nonHeapUsedBytes(),
                engine.memory().reservedBytes(),
                engine.memory().budgetBytes(),
                ledger.l1Hits(),
                ledger.l1Characters(),
                ledger.connectedClients(),
                RuntimeMetrics.processCpuLoad(),
                RuntimeMetrics.systemCpuLoad(),
                RuntimeMetrics.gcCollectionCount(),
                RuntimeMetrics.gcCollectionTimeMillis(),
                RuntimeMetrics.platformThreadCount(),
                latencyJson == null || latencyJson.isBlank() ? "{}" : latencyJson);
    }

    public double systemUsedRatio() {
        return systemTotalBytes <= 0 ? 0.0 : (double) systemUsedBytes / systemTotalBytes;
    }

    public double heapUsedRatio() {
        return heapMaxBytes <= 0 ? 0.0 : (double) heapUsedBytes / heapMaxBytes;
    }

    public double offHeapUsedRatio() {
        return offHeapBudgetBytes <= 0 ? 0.0 : (double) offHeapReservedBytes / offHeapBudgetBytes;
    }

    /** Requests answered without a backend call, including client-side L1 short-circuits. */
    public long totalHits() {
        return engine.hits() + l1Hits;
    }

    public long totalRequests() {
        return totalHits() + engine.misses();
    }

    public double overallHitRatio() {
        long total = totalRequests();
        return total == 0 ? 0.0 : (double) totalHits() / total;
    }

    public String toJson() {
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        field(json, "timestamp", timestampMillis).append(',');
        field(json, "uptime_ms", uptimeMillis).append(',');
        json.append("\"version\":\"").append(escape(version)).append("\",");

        json.append("\"memory\":{");
        field(json, "system_total_bytes", systemTotalBytes).append(',');
        field(json, "system_used_bytes", systemUsedBytes).append(',');
        field(json, "system_free_bytes", systemFreeBytes).append(',');
        field(json, "system_used_ratio", systemUsedRatio()).append(',');
        field(json, "heap_used_bytes", heapUsedBytes).append(',');
        field(json, "heap_max_bytes", heapMaxBytes).append(',');
        field(json, "heap_used_ratio", heapUsedRatio()).append(',');
        field(json, "non_heap_used_bytes", nonHeapUsedBytes).append(',');
        field(json, "offheap_reserved_bytes", offHeapReservedBytes).append(',');
        field(json, "offheap_budget_bytes", offHeapBudgetBytes).append(',');
        field(json, "offheap_used_ratio", offHeapUsedRatio()).append(',');
        field(json, "offheap_slots", engine.liveOffHeapSlots()).append(',');
        json.append("\"rejecting_writes\":").append(engine.memory().rejecting());
        json.append("},");

        json.append("\"runtime\":{");
        field(json, "process_cpu_ratio", processCpuRatio).append(',');
        field(json, "system_cpu_ratio", systemCpuRatio).append(',');
        field(json, "gc_collections", gcCollections).append(',');
        field(json, "gc_time_ms", gcTimeMillis).append(',');
        field(json, "platform_threads", platformThreads).append(',');
        field(json, "available_processors", RuntimeMetrics.availableProcessors());
        json.append("},");

        json.append("\"cache\":{");
        field(json, "shards", engine.shardCount()).append(',');
        field(json, "entries", engine.entries()).append(',');
        field(json, "bytes", engine.bytes()).append(',');
        field(json, "total_requests", totalRequests()).append(',');
        field(json, "hits", engine.hits()).append(',');
        field(json, "stale_hits", engine.staleHits()).append(',');
        field(json, "misses", engine.misses()).append(',');
        field(json, "hit_ratio", engine.hitRatio()).append(',');
        field(json, "overall_hit_ratio", overallHitRatio()).append(',');
        field(json, "writes", engine.writes()).append(',');
        field(json, "ttl_evictions", engine.ttlEvictions()).append(',');
        field(json, "lru_evictions", engine.lruEvictions()).append(',');
        field(json, "write_rejections", engine.writeRejections()).append(',');
        field(json, "shard_skew", engine.shardSkew()).append(',');
        field(json, "refresh_leads", engine.refreshLeads()).append(',');
        field(json, "herd_suppressed", engine.herdSuppressed()).append(',');
        field(json, "refreshes_in_flight", engine.refreshesInFlight());
        json.append("},");

        json.append("\"clients\":{");
        field(json, "connected", connectedClients).append(',');
        field(json, "l1_hits", l1Hits).append(',');
        field(json, "l1_characters", l1Characters);
        json.append("},");

        json.append("\"savings\":{");
        json.append("\"model\":\"").append(escape(savings.profile().displayName())).append("\",");
        json.append("\"model_id\":\"").append(escape(savings.profile().id())).append("\",");
        field(json, "usd_per_million_input_tokens", savings.profile().usdPerMillionInputTokens()).append(',');
        field(json, "characters_per_token", savings.profile().charactersPerToken()).append(',');
        field(json, "characters_paid", savings.charactersPaid()).append(',');
        field(json, "characters_avoided", savings.charactersAvoided()).append(',');
        field(json, "tokens_paid", savings.tokensPaid()).append(',');
        field(json, "tokens_avoided", savings.tokensAvoided()).append(',');
        field(json, "actual_cost_usd", savings.actualCostUsd()).append(',');
        field(json, "projected_cost_usd", savings.projectedCostUsd()).append(',');
        field(json, "saved_usd", savings.savedUsd()).append(',');
        field(json, "savings_ratio", savings.savingsRatio());
        json.append("},");

        json.append("\"latency\":").append(latencyJson).append(',');

        json.append("\"shards\":[");
        for (int i = 0; i < engine.shards().size(); i++) {
            ShardStats shard = engine.shards().get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append('{');
            field(json, "index", shard.index()).append(',');
            field(json, "entries", shard.entries()).append(',');
            field(json, "bytes", shard.bytes()).append(',');
            field(json, "hits", shard.hits()).append(',');
            field(json, "misses", shard.misses());
            json.append('}');
        }
        json.append(']');

        return json.append('}').toString();
    }

    private static StringBuilder field(StringBuilder json, String name, long value) {
        return json.append('"').append(name).append("\":").append(value);
    }

    private static StringBuilder field(StringBuilder json, String name, double value) {
        // Locale.ROOT is load-bearing: under a comma-decimal locale, String.valueOf would emit 0,8532 and
        // produce structurally invalid JSON that every consumer rejects.
        String rendered = Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "0";
        return json.append('"').append(name).append("\":").append(rendered);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
