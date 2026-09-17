package io.fastcache.engine.memory;

import io.fastcache.engine.util.FastCacheLog;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;

/**
 * Machine and JVM memory readings, resolved once at class-init and reused everywhere.
 *
 * <p>Single source of truth on purpose: the admission guard and the metrics console must never disagree
 * about how much memory the box has. A dashboard showing 40% while writes are being shed at 85% is worse
 * than no dashboard.
 *
 * <p><b>Reflection target matters.</b> The extended methods are resolved against the exported
 * {@code com.sun.management.OperatingSystemMXBean} interface, never against {@code bean.getClass()} — the
 * runtime class is {@code com.sun.management.internal.OperatingSystemImpl}, which {@code jdk.management}
 * does not open, so reflecting on it throws {@code InaccessibleObjectException} and silently demotes every
 * reading to the heap-only fallback.
 */
public final class PhysicalMemory {

    private static final FastCacheLog LOG = FastCacheLog.of(PhysicalMemory.class);

    private static final OperatingSystemMXBean OS_BEAN = ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    private static final Method TOTAL_METHOD;
    private static final Method FREE_METHOD;
    private static final boolean AVAILABLE;

    static {
        Method total = null;
        Method free = null;
        boolean available = false;
        try {
            Class<?> extended = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (extended.isInstance(OS_BEAN)) {
                total = findMethod(extended, "getTotalMemorySize", "getTotalPhysicalMemorySize");
                free = findMethod(extended, "getFreeMemorySize", "getFreePhysicalMemorySize");
                available = ((long) total.invoke(OS_BEAN)) > 0;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Physical memory readings unavailable ({0}); falling back to heap-only metrics.",
                    e.toString());
        }
        TOTAL_METHOD = total;
        FREE_METHOD = free;
        AVAILABLE = available;
    }

    private PhysicalMemory() {
    }

    /** Tries the JDK 14+ name first, then the pre-14 name, so one build runs on every modern LTS. */
    private static Method findMethod(Class<?> owner, String preferred, String legacy)
            throws NoSuchMethodException {
        try {
            return owner.getMethod(preferred);
        } catch (NoSuchMethodException e) {
            return owner.getMethod(legacy);
        }
    }

    /** @return true when real machine readings are available rather than the heap fallback */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Total physical memory, container-aware on modern JDKs. Zero when unavailable. */
    public static long totalBytes() {
        return read(TOTAL_METHOD);
    }

    /**
     * Genuinely free physical memory.
     *
     * <p>Note this is <em>free</em>, not <em>available</em>: the OS counts reclaimable file-cache and
     * standby pages as used, so a perfectly healthy host routinely reports 80-90% used. Anything making a
     * shed-load decision on this number must account for that.
     */
    public static long freeBytes() {
        return read(FREE_METHOD);
    }

    public static long usedBytes() {
        return Math.max(0L, totalBytes() - freeBytes());
    }

    /** Used fraction of physical memory, or the heap fraction when machine readings are unavailable. */
    public static double usedRatio() {
        long total = totalBytes();
        if (total <= 0) {
            return heapRatio();
        }
        return 1.0 - ((double) freeBytes() / (double) total);
    }

    public static long heapUsedBytes() {
        return MEMORY_BEAN.getHeapMemoryUsage().getUsed();
    }

    /** Heap ceiling; falls back to the committed size when the JVM reports no maximum. */
    public static long heapMaxBytes() {
        long max = MEMORY_BEAN.getHeapMemoryUsage().getMax();
        return max > 0 ? max : MEMORY_BEAN.getHeapMemoryUsage().getCommitted();
    }

    public static double heapRatio() {
        long max = heapMaxBytes();
        return max <= 0 ? 0.0 : (double) heapUsedBytes() / (double) max;
    }

    /** Non-heap JVM memory: metaspace, code cache, compiler arenas. Not the off-heap cache budget. */
    public static long nonHeapUsedBytes() {
        return MEMORY_BEAN.getNonHeapMemoryUsage().getUsed();
    }

    private static long read(Method method) {
        if (!AVAILABLE || method == null) {
            return 0L;
        }
        try {
            return (long) method.invoke(OS_BEAN);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0L; // Telemetry failure must never propagate into a caller's control flow.
        }
    }
}
