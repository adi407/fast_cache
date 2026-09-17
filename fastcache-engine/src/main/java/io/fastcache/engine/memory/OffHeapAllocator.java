package io.fastcache.engine.memory;

import io.fastcache.engine.util.FastCacheLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAdder;

/**
 * Allocates and <em>deterministically</em> releases direct {@link ByteBuffer} slots.
 *
 * <p><b>Why this class exists.</b> {@code ByteBuffer.allocateDirect} reclaims native memory only when
 * the wrapper object is collected and its {@code Cleaner} runs — i.e. on the GC's schedule. For a cache
 * that routinely churns 20&ndash;50&nbsp;MB AI context windows that is unacceptable: native memory would
 * balloon while the heap stays tiny (so the GC feels no pressure and never runs), and the process gets
 * OOM-killed with a nearly empty Java heap. We therefore free eagerly via
 * {@code sun.misc.Unsafe.invokeCleaner}.
 *
 * <p><b>Safety.</b> Calling {@code invokeCleaner} on a buffer that is still readable by another thread is
 * a hard JVM crash (SIGSEGV), not an exception. This allocator is therefore never called directly by
 * readers — every free is gated behind the reference count on
 * {@code CachePayload.OffHeap}, so a slot is released only after the owning map entry has been removed
 * <em>and</em> every in-flight reader lease has been closed.
 */
public final class OffHeapAllocator {

    private static final FastCacheLog LOG = FastCacheLog.of(OffHeapAllocator.class);

    /** Bound {@code Unsafe.invokeCleaner(ByteBuffer)}, or {@code null} when unavailable. */
    private static final MethodHandle INVOKE_CLEANER = resolveInvokeCleaner();

    private final LongAdder liveSlots = new LongAdder();
    private final LongAdder liveBytes = new LongAdder();
    private final LongAdder allocations = new LongAdder();
    private final LongAdder frees = new LongAdder();

    private static MethodHandle resolveInvokeCleaner() {
        try {
            // jdk.unsupported opens sun.misc unconditionally, so no --add-opens flag is required.
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            MethodHandle handle = MethodHandles.lookup()
                    .findVirtual(unsafeClass, "invokeCleaner", MethodType.methodType(void.class, ByteBuffer.class));
            return handle.bindTo(unsafe);
        } catch (Throwable t) {
            LOG.warn("Deterministic off-heap reclamation unavailable ({0}); "
                    + "falling back to GC-driven Cleaner. Expect higher native memory watermarks.", t.toString());
            return null;
        }
    }

    /** @return true when this JVM supports eager native reclamation. */
    public static boolean supportsDeterministicFree() {
        return INVOKE_CLEANER != null;
    }

    /**
     * Allocates a native slot. Callers MUST have reserved the same byte count with
     * {@link MemoryGuard#tryReserve(long)} first — this method performs no admission control.
     *
     * @throws OutOfMemoryError if the OS refuses the mapping despite admission control passing
     */
    public ByteBuffer allocate(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative allocation: " + bytes);
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes);
        liveSlots.increment();
        liveBytes.add(bytes);
        allocations.increment();
        return buffer;
    }

    /**
     * Releases a native slot. Idempotency is the caller's responsibility (enforced by the payload
     * reference count); double-freeing is undefined behaviour at the JVM level.
     */
    public void free(ByteBuffer buffer, int bytes) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        liveSlots.decrement();
        liveBytes.add(-bytes);
        frees.increment();
        if (INVOKE_CLEANER == null) {
            return; // Degraded mode: drop the reference and let the GC's Cleaner get to it eventually.
        }
        try {
            INVOKE_CLEANER.invokeExact(buffer);
        } catch (Throwable t) {
            // Never propagate: a failed free is a leak, a thrown free from an eviction sweep is an outage.
            LOG.error("Failed to release off-heap slot of " + bytes + " bytes", t instanceof Exception e ? e : new RuntimeException(t));
        }
    }

    public long liveSlots() {
        return liveSlots.sum();
    }

    public long liveBytes() {
        return liveBytes.sum();
    }

    public long totalAllocations() {
        return allocations.sum();
    }

    public long totalFrees() {
        return frees.sum();
    }
}
