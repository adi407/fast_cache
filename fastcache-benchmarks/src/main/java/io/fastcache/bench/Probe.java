package io.fastcache.bench;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * JVM and OS memory instrumentation for the benchmark.
 *
 * <p>Three independent sources deliberately, because no single one is trustworthy on its own:
 *
 * <ul>
 *   <li><b>MXBeans</b> for heap, non-heap and old-generation occupancy. Cheap, but blind to native memory.
 *   <li><b>GC notifications</b> for individual pause durations. {@code getCollectionTime()} gives a
 *       cumulative total only; the notification carries each collection's duration, which is what a p99
 *       pause requires.
 *   <li><b>Process RSS</b> from the OS. This is the only reading that sees off-heap allocations, and is
 *       therefore the ground truth for every memory claim in the results.
 * </ul>
 *
 * <p>Each instance attaches its own GC listeners and <b>must be closed</b>; callers take a
 * {@link #mark()} and diff against it, so scenarios do not contaminate one another.
 */
public final class Probe implements AutoCloseable {

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private final List<long[]> pauses = new ArrayList<>();       // guarded by pauses
    private final LongAdder collections = new LongAdder();
    private final AtomicLong pauseNanosTotal = new AtomicLong();
    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final List<NotificationListener> listeners = new ArrayList<>();

    public Probe() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(bean instanceof NotificationEmitter emitter)) {
                continue;
            }
            NotificationListener listener = (Notification notification, Object handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                        .equals(notification.getType())) {
                    return;
                }
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo
                        .from((CompositeData) notification.getUserData());
                GcInfo gc = info.getGcInfo();
                long micros = gc.getDuration() * 1000L;   // getDuration() is milliseconds
                collections.increment();
                pauseNanosTotal.addAndGet(micros * 1000L);
                synchronized (pauses) {
                    pauses.add(new long[]{micros});
                }
            };
            emitter.addNotificationListener(listener, null, null);
            emitters.add(emitter);
            listeners.add(listener);
        }
    }

    /**
     * Detaches this probe's GC listeners.
     *
     * <p>Not housekeeping: a benchmark matrix builds one Probe per cell, and without this every cell
     * leaves a listener attached to every collector for the life of the JVM. By the sixtieth cell, sixty
     * listeners fire on every collection. The accumulating cost lands on whichever arm runs last, which
     * is a systematic bias dressed up as a measurement.
     */
    @Override
    public void close() {
        for (int i = 0; i < emitters.size(); i++) {
            try {
                emitters.get(i).removeNotificationListener(listeners.get(i));
            } catch (javax.management.ListenerNotFoundException ignored) {
                // Already gone; nothing to undo.
            }
        }
        emitters.clear();
        listeners.clear();
    }

    /** A point-in-time cursor. Diff a later {@link GcDelta} against this to isolate one scenario. */
    public Mark mark() {
        synchronized (pauses) {
            return new Mark(pauses.size(), collections.sum(), pauseNanosTotal.get(),
                    THREADS.getTotalThreadAllocatedBytes());
        }
    }

    public record Mark(int pauseIndex, long collections, long pauseNanos, long allocatedBytes) { }

    /** GC activity that occurred since a {@link Mark}. */
    public record GcDelta(long collections, double totalPauseMillis, Stats pauseMicros,
                          long allocatedBytes) {

        public String format() {
            return String.format("collections=%d totalPause=%.1fms alloc=%.1fMB  pause %s",
                    collections, totalPauseMillis, allocatedBytes / 1048576.0,
                    pauseMicros.format("us"));
        }
    }

    public GcDelta since(Mark mark) {
        long[] window;
        synchronized (pauses) {
            int size = pauses.size();
            window = new long[Math.max(0, size - mark.pauseIndex())];
            for (int i = mark.pauseIndex(); i < size; i++) {
                window[i - mark.pauseIndex()] = pauses.get(i)[0];
            }
        }
        long nanos = pauseNanosTotal.get() - mark.pauseNanos();
        return new GcDelta(collections.sum() - mark.collections(), nanos / 1_000_000.0,
                Stats.of(window, window.length),
                THREADS.getTotalThreadAllocatedBytes() - mark.allocatedBytes());
    }

    // ---------------------------------------------------------------------------------------------------
    // Memory readings
    // ---------------------------------------------------------------------------------------------------

    public static long heapUsed() {
        return MEMORY.getHeapMemoryUsage().getUsed();
    }

    public static long heapCommitted() {
        return MEMORY.getHeapMemoryUsage().getCommitted();
    }

    public static long nonHeapUsed() {
        return MEMORY.getNonHeapMemoryUsage().getUsed();
    }

    /** Old-generation occupancy, or -1 when this collector exposes no old-gen pool (e.g. some ZGC modes). */
    public static long oldGenUsed() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName().toLowerCase();
            if (name.contains("old") || name.contains("tenured")) {
                return pool.getUsage().getUsed();
            }
        }
        return -1;
    }

    /**
     * Settled heap occupancy: what is still reachable once the collector has had a fair chance.
     *
     * <p>Two collections with a pause between them, because a single {@code System.gc()} on G1 routinely
     * leaves floating garbage that a second pass reclaims. This is a measurement aid, not something a
     * production path would ever do, and it is applied identically to every arm.
     */
    public static long settledHeapUsed() {
        for (int i = 0; i < 2; i++) {
            System.gc();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return heapUsed();
    }

    // ---------------------------------------------------------------------------------------------------
    // Process RSS - the only reading that sees off-heap memory
    // ---------------------------------------------------------------------------------------------------

    private static volatile boolean rssWarned;

    /** Resident set size of this process in bytes, or -1 when unavailable. */
    public static long rssBytes() {
        return rssBytes(ProcessHandle.current().pid());
    }

    public static long rssBytes(long pid) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("linux")) {
                return linuxRss(pid);
            }
            return windowsRss(pid);
        } catch (Exception e) {
            if (!rssWarned) {
                rssWarned = true;
                System.err.println("[probe] RSS unavailable: " + e);
            }
            return -1;
        }
    }

    private static long linuxRss(long pid) throws Exception {
        String path = pid == ProcessHandle.current().pid()
                ? "/proc/self/status" : "/proc/" + pid + "/status";
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024L;
                }
            }
        }
        return -1;
    }

    /**
     * Windows working set via PowerShell.
     *
     * <p>{@code wmic} is absent on current Windows 11 builds, and there is no JDK API for RSS, so a short
     * out-of-process call is the only portable option. It costs roughly 100 ms, which is why samplers here
     * run on a 1 s cadence or slower and never inside a timed loop.
     */
    private static long windowsRss(long pid) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                "-Command", "(Get-Process -Id " + pid + ").WorkingSet64");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
        }
        process.waitFor();
        return output == null ? -1 : Long.parseLong(output.trim());
    }
}
