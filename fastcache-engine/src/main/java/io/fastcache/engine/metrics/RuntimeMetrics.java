package io.fastcache.engine.metrics;

import io.fastcache.engine.util.FastCacheLog;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.List;

/**
 * CPU, garbage collection and thread readings from the platform MXBeans.
 *
 * <p>Resolved once at class-init and read on demand. As with {@code PhysicalMemory}, the extended CPU
 * methods are looked up on the exported {@code com.sun.management.OperatingSystemMXBean} interface rather
 * than on {@code bean.getClass()} — the runtime class lives in a package {@code jdk.management} does not
 * open, so reflecting on it throws and silently zeroes every reading.
 */
public final class RuntimeMetrics {

    private static final FastCacheLog LOG = FastCacheLog.of(RuntimeMetrics.class);

    private static final OperatingSystemMXBean OS_BEAN = ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    private static final List<GarbageCollectorMXBean> GC_BEANS =
            ManagementFactory.getGarbageCollectorMXBeans();

    private static final Method PROCESS_CPU;
    private static final Method SYSTEM_CPU;

    static {
        Method processCpu = null;
        Method systemCpu = null;
        try {
            Class<?> extended = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (extended.isInstance(OS_BEAN)) {
                processCpu = extended.getMethod("getProcessCpuLoad");
                // getCpuLoad is the JDK 14+ name; getSystemCpuLoad is the older one.
                systemCpu = findMethod(extended, "getCpuLoad", "getSystemCpuLoad");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("CPU readings unavailable ({0}); the console will report -1 for CPU.", e.toString());
        }
        PROCESS_CPU = processCpu;
        SYSTEM_CPU = systemCpu;
    }

    private RuntimeMetrics() {
    }

    private static Method findMethod(Class<?> owner, String preferred, String legacy)
            throws NoSuchMethodException {
        try {
            return owner.getMethod(preferred);
        } catch (NoSuchMethodException e) {
            return owner.getMethod(legacy);
        }
    }

    /**
     * Fraction of one CPU's capacity used by this JVM, across all cores, in [0, 1].
     *
     * <p>Returns {@code -1} when unavailable, which is also what the JVM itself returns for the first
     * call or two before it has two samples to compare. Callers should render a negative value as "not
     * yet known" rather than as zero — a CPU graph that reads 0% during startup is actively misleading.
     */
    public static double processCpuLoad() {
        return read(PROCESS_CPU);
    }

    /** Fraction of the whole machine's CPU in use, in [0, 1], or {@code -1} when unavailable. */
    public static double systemCpuLoad() {
        return read(SYSTEM_CPU);
    }

    public static int availableProcessors() {
        return OS_BEAN.getAvailableProcessors();
    }

    /** System load average over the last minute, or {@code -1} on platforms that do not report it. */
    public static double systemLoadAverage() {
        return OS_BEAN.getSystemLoadAverage();
    }

    /** Total collections across every collector since JVM start. */
    public static long gcCollectionCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    /**
     * Total wall time spent collecting, in milliseconds.
     *
     * <p>This is the number that matters for FastCache specifically: the entire off-heap design exists so
     * that a cache holding gigabytes does not move this figure. If it climbs in proportion to cache size,
     * something is being retained on the heap that should not be.
     */
    public static long gcCollectionTimeMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long time = bean.getCollectionTime();
            if (time > 0) {
                total += time;
            }
        }
        return total;
    }

    public static List<String> gcNames() {
        return GC_BEANS.stream().map(GarbageCollectorMXBean::getName).toList();
    }

    /**
     * Live <em>platform</em> threads.
     *
     * <p>Deliberately not a count of virtual threads: {@link ThreadMXBean} does not see them, and there is
     * no supported API that does. That is not a gap in this metric so much as the point of the design —
     * FastCache runs one virtual thread per connection and per sweep task, and this number staying flat
     * while connections climb into the thousands is the evidence that the model works.
     */
    public static int platformThreadCount() {
        return THREAD_BEAN.getThreadCount();
    }

    public static int peakPlatformThreadCount() {
        return THREAD_BEAN.getPeakThreadCount();
    }

    public static int daemonThreadCount() {
        return THREAD_BEAN.getDaemonThreadCount();
    }

    public static long uptimeMillis() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    private static double read(Method method) {
        if (method == null) {
            return -1;
        }
        try {
            double value = (double) method.invoke(OS_BEAN);
            return Double.isNaN(value) ? -1 : value;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1; // Telemetry failure must never propagate into a caller's control flow.
        }
    }
}
