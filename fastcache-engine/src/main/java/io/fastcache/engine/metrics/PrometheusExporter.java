package io.fastcache.engine.metrics;

import io.fastcache.engine.core.EngineStats;
import io.fastcache.engine.core.ShardStats;
import io.fastcache.engine.core.ShardedStorageEngine;

import java.util.List;
import java.util.Locale;

/**
 * Renders the engine's state in the Prometheus text exposition format.
 *
 * <p><b>Why this matters more than the dashboard.</b> The HTML console is useful for a developer looking
 * at one machine right now. It cannot alert, cannot retain history, and cannot be correlated with anything
 * else. Emitting the same numbers in a format Prometheus already scrapes means FastCache shows up in the
 * Grafana board and the alerting rules a team already has, instead of asking them to watch a bespoke page.
 * That is the difference between a metric being visible and a metric being used.
 *
 * <p>Format rules that are easy to get wrong and that a scraper will reject outright:
 * <ul>
 *   <li>Every metric needs {@code # HELP} and {@code # TYPE} before its first sample.</li>
 *   <li>Counters end in {@code _total} and only ever increase.</li>
 *   <li>Histogram buckets are cumulative and must include a {@code le="+Inf"} bucket equal to
 *       {@code _count}.</li>
 *   <li>Numbers must use a dot as the decimal separator regardless of the JVM's locale, which is why
 *       every format call here passes {@link Locale#ROOT} explicitly.</li>
 * </ul>
 */
public final class PrometheusExporter {

    private static final String PREFIX = "fastcache_";

    private PrometheusExporter() {
    }

    public static String render(ShardedStorageEngine engine, MetricsSnapshot snapshot) {
        StringBuilder out = new StringBuilder(8192);
        EngineStats stats = snapshot.engine();

        // --- build info -------------------------------------------------------------------------------
        help(out, "build_info", "Build and runtime information", "gauge");
        out.append(PREFIX).append("build_info{version=\"").append(escape(snapshot.version()))
                .append("\",shards=\"").append(stats.shardCount()).append("\"} 1\n");

        gauge(out, "uptime_seconds", "Seconds since the engine started",
                snapshot.uptimeMillis() / 1000.0);

        // --- cpu --------------------------------------------------------------------------------------
        // -1 is the JVM's own "not yet known" for the first samples after start. Emitting it unchanged is
        // deliberate: a dashboard can filter a negative, but cannot distinguish a real 0% from a fabricated
        // one if we were to round it up.
        gauge(out, "process_cpu_ratio",
                "CPU used by this JVM as a fraction of total capacity, -1 if unavailable",
                RuntimeMetrics.processCpuLoad());
        gauge(out, "system_cpu_ratio",
                "CPU used across the whole machine, -1 if unavailable",
                RuntimeMetrics.systemCpuLoad());
        gauge(out, "system_load_average",
                "One-minute load average, -1 where the platform does not report it",
                RuntimeMetrics.systemLoadAverage());
        gauge(out, "available_processors", "Processors visible to the JVM",
                RuntimeMetrics.availableProcessors());

        // --- garbage collection -----------------------------------------------------------------------
        counter(out, "gc_collections_total", "Garbage collections since start",
                RuntimeMetrics.gcCollectionCount());
        counter(out, "gc_time_seconds_total",
                "Wall time spent collecting. The off-heap design exists so this does not scale with cache size",
                RuntimeMetrics.gcCollectionTimeMillis() / 1000.0);

        // --- threads ----------------------------------------------------------------------------------
        // Platform threads only; ThreadMXBean cannot see virtual threads. This number staying flat while
        // connections climb is the evidence that one-virtual-thread-per-connection works.
        gauge(out, "platform_threads", "Live platform threads (virtual threads are not counted)",
                RuntimeMetrics.platformThreadCount());
        gauge(out, "platform_threads_peak", "Peak platform threads since start",
                RuntimeMetrics.peakPlatformThreadCount());

        // --- memory -----------------------------------------------------------------------------------
        gauge(out, "memory_system_total_bytes", "Total physical memory", snapshot.systemTotalBytes());
        gauge(out, "memory_system_used_bytes", "Physical memory in use", snapshot.systemUsedBytes());
        gauge(out, "memory_heap_used_bytes", "JVM heap in use", snapshot.heapUsedBytes());
        gauge(out, "memory_heap_max_bytes", "JVM heap ceiling", snapshot.heapMaxBytes());
        gauge(out, "memory_nonheap_used_bytes", "JVM non-heap (metaspace, code cache)",
                snapshot.nonHeapUsedBytes());
        gauge(out, "memory_offheap_reserved_bytes", "Native memory reserved for cached payloads",
                snapshot.offHeapReservedBytes());
        gauge(out, "memory_offheap_budget_bytes", "Configured native memory budget",
                snapshot.offHeapBudgetBytes());
        gauge(out, "memory_offheap_slots", "Live off-heap slots; must return to zero when the cache drains",
                stats.liveOffHeapSlots());
        gauge(out, "memory_rejecting_writes",
                "1 when the memory guard is shedding writes", stats.memory().rejecting() ? 1 : 0);

        // --- cache ------------------------------------------------------------------------------------
        gauge(out, "cache_entries", "Entries currently held", stats.entries());
        gauge(out, "cache_bytes", "Bytes of payload held off-heap", stats.bytes());
        counter(out, "cache_hits_total", "Reads served from cache", stats.hits());
        counter(out, "cache_stale_hits_total",
                "Reads served from the stale-while-revalidate window", stats.staleHits());
        counter(out, "cache_misses_total", "Reads that found nothing", stats.misses());
        counter(out, "cache_writes_total", "Values written", stats.writes());
        counter(out, "cache_ttl_evictions_total", "Entries reclaimed after expiry", stats.ttlEvictions());
        counter(out, "cache_lru_evictions_total", "Entries reclaimed for capacity", stats.lruEvictions());
        counter(out, "cache_write_rejections_total",
                "Writes shed under memory pressure", stats.writeRejections());
        gauge(out, "cache_shard_skew",
                "Largest shard divided by the mean; 1.0 is perfect balance", stats.shardSkew());

        // --- stampede defence -------------------------------------------------------------------------
        counter(out, "refresh_leads_total", "Refresh leases granted; one per genuine recompute",
                stats.refreshLeads());
        counter(out, "herd_suppressed_total",
                "Duplicate recomputations prevented. This is the stampede defence doing its job",
                stats.herdSuppressed());
        gauge(out, "refreshes_in_flight", "Refreshes currently running", stats.refreshesInFlight());

        // --- clients ----------------------------------------------------------------------------------
        gauge(out, "clients_connected", "Clients reporting via heartbeat", snapshot.connectedClients());
        counter(out, "client_l1_hits_total",
                "Reads short-circuited in a client's local cache, never reaching the socket",
                snapshot.l1Hits());

        // --- savings ----------------------------------------------------------------------------------
        SavingsLedger.Savings savings = snapshot.savings();
        counter(out, "tokens_avoided_total",
                "Estimated input tokens not sent to a model (characters/4)", savings.tokensAvoided());
        gauge(out, "cost_saved_usd",
                "Estimated spend avoided at the configured model rate. An estimate, not an invoice",
                savings.savedUsd());
        gauge(out, "cost_incurred_usd", "Estimated spend on calls that did happen",
                savings.actualCostUsd());

        // --- latency ----------------------------------------------------------------------------------
        histogram(out, engine.getLatency());
        histogram(out, engine.putLatency());
        histogram(out, engine.deleteLatency());

        // --- per shard --------------------------------------------------------------------------------
        // Labelled rather than flattened, so a hot shard is a query away instead of 32 separate metrics.
        help(out, "shard_entries", "Entries per shard", "gauge");
        List<ShardStats> shards = stats.shards();
        for (ShardStats shard : shards) {
            out.append(PREFIX).append("shard_entries{shard=\"").append(shard.index()).append("\"} ")
                    .append(shard.entries()).append('\n');
        }
        help(out, "shard_bytes", "Payload bytes per shard", "gauge");
        for (ShardStats shard : shards) {
            out.append(PREFIX).append("shard_bytes{shard=\"").append(shard.index()).append("\"} ")
                    .append(shard.bytes()).append('\n');
        }

        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------
    // Format primitives
    // ---------------------------------------------------------------------------------------------------

    private static void help(StringBuilder out, String name, String description, String type) {
        out.append("# HELP ").append(PREFIX).append(name).append(' ').append(description).append('\n');
        out.append("# TYPE ").append(PREFIX).append(name).append(' ').append(type).append('\n');
    }

    private static void gauge(StringBuilder out, String name, String description, double value) {
        help(out, name, description, "gauge");
        out.append(PREFIX).append(name).append(' ').append(number(value)).append('\n');
    }

    private static void counter(StringBuilder out, String name, String description, double value) {
        help(out, name, description, "counter");
        out.append(PREFIX).append(name).append(' ').append(number(value)).append('\n');
    }

    private static void histogram(StringBuilder out, LatencyHistogram latency) {
        String name = "operation_duration_seconds";
        String label = latency.name();
        help(out, name, "Engine-side operation latency in seconds", "histogram");

        long[] counts = latency.cumulativeCounts();
        for (int i = 0; i < LatencyHistogram.BUCKET_BOUNDS_SECONDS.length; i++) {
            out.append(PREFIX).append(name).append("_bucket{operation=\"").append(label)
                    .append("\",le=\"").append(number(LatencyHistogram.BUCKET_BOUNDS_SECONDS[i]))
                    .append("\"} ").append(counts[i]).append('\n');
        }
        out.append(PREFIX).append(name).append("_bucket{operation=\"").append(label)
                .append("\",le=\"+Inf\"} ").append(counts[counts.length - 1]).append('\n');
        out.append(PREFIX).append(name).append("_sum{operation=\"").append(label).append("\"} ")
                .append(number(latency.totalSeconds())).append('\n');
        out.append(PREFIX).append(name).append("_count{operation=\"").append(label).append("\"} ")
                .append(latency.count()).append('\n');
    }

    /** Locale-independent rendering; a comma decimal separator makes the whole scrape unparseable. */
    private static String number(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.format(Locale.ROOT, "%d", (long) value);
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
