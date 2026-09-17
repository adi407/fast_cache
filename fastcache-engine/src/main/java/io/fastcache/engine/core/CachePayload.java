package io.fastcache.engine.core;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The value half of a cache entry. A sealed hierarchy of two records, because FastCache serves two very
 * different tenants from one engine:
 *
 * <ul>
 *   <li>{@link OffHeap} &mdash; bytes that arrived over the socket from the Python sidecar client. Stored
 *       in a direct {@link ByteBuffer}, completely invisible to the garbage collector. A 50&nbsp;MB context
 *       window stored here contributes <em>zero</em> bytes to GC root scanning and zero copying work to a
 *       young-generation evacuation, which is the whole point of the design.</li>
 *   <li>{@link Reference} &mdash; a live JVM object handed over by the Spring AOP layer. In-process callers
 *       already own the object graph; round-tripping it through JSON or a byte buffer would add
 *       serialization cost and an extra copy for no benefit.</li>
 * </ul>
 *
 * <p><b>Reference counting.</b> Freeing a direct buffer that another thread is still reading is a SIGSEGV,
 * not an exception. Every payload therefore carries a reference count: the owning map entry holds one
 * reference, each reader lease takes one more, and the native slot is released only when the count reaches
 * zero. This is what makes eviction safe to run concurrently with reads on a background virtual thread.
 */
public sealed interface CachePayload permits CachePayload.OffHeap, CachePayload.Reference {

    /** Bytes charged against the off-heap budget (zero for on-heap references). */
    int footprintBytes();

    /** Opaque client-defined codec/compression bits, echoed back verbatim on read. */
    byte flags();

    /**
     * Length in characters of the original text this payload was built from, or {@code 0} when the value
     * is not text.
     *
     * <p>Tracked separately from {@link #footprintBytes()} because the ledger cannot derive it: a
     * zstd-compressed 40&nbsp;KB prompt occupies a few hundred bytes on the wire, so counting stored bytes
     * would under-report token savings by an order of magnitude. The writer knows the pre-compression
     * length and sends it; everyone downstream just reads it.
     */
    int sourceCharacters();

    /**
     * Takes a reader reference.
     *
     * @return {@code false} when the payload has already been fully released &mdash; the caller lost a race
     *         with an evictor and must treat the lookup as a miss
     */
    boolean tryRetain();

    /**
     * Drops one reference.
     *
     * @return {@code true} when this call dropped the final reference, meaning the caller is now solely
     *         responsible for releasing the underlying native slot
     */
    boolean releaseReference();

    /**
     * Off-heap payload: a direct buffer slot plus its live reference count.
     *
     * <p>{@code length} is tracked separately from {@code buffer.capacity()} because the allocator may hand
     * back a size-class-rounded slot; only {@code length} bytes are meaningful.
     */
    record OffHeap(ByteBuffer buffer, int length, byte flags, int sourceCharacters,
                   AtomicInteger references) implements CachePayload {

        public OffHeap {
            Objects.requireNonNull(buffer, "buffer");
            Objects.requireNonNull(references, "references");
            if (!buffer.isDirect()) {
                throw new IllegalArgumentException("OffHeap payload requires a direct ByteBuffer");
            }
            if (length < 0 || length > buffer.capacity()) {
                throw new IllegalArgumentException("length " + length + " outside slot capacity " + buffer.capacity());
            }
        }

        /** Creates a payload owned by the cache with an initial reference count of one. */
        public static OffHeap owned(ByteBuffer buffer, int length, byte flags, int sourceCharacters) {
            return new OffHeap(buffer, length, flags, Math.max(0, sourceCharacters), new AtomicInteger(1));
        }

        @Override
        public int footprintBytes() {
            return length;
        }

        @Override
        public boolean tryRetain() {
            while (true) {
                int current = references.get();
                if (current <= 0) {
                    return false; // Already released; the slot may be unmapped at any instant.
                }
                if (references.compareAndSet(current, current + 1)) {
                    return true;
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public boolean releaseReference() {
            int remaining = references.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException("off-heap payload reference count underflow: " + remaining);
            }
            return remaining == 0;
        }

        /**
         * A read-only, independently-positioned window over the payload. Safe to hand to a
         * {@code SocketChannel.write} call: NIO will copy straight from native memory to the socket without
         * ever materialising the bytes on the Java heap.
         */
        public ByteBuffer readOnlyView() {
            ByteBuffer view = buffer.asReadOnlyBuffer();
            view.position(0).limit(length);
            return view;
        }
    }

    /** On-heap payload: a live object reference, used exclusively by the in-process Spring integration. */
    record Reference(Object value, int estimatedBytes) implements CachePayload {

        public static Reference of(Object value) {
            return new Reference(value, 0);
        }

        @Override
        public int sourceCharacters() {
            // In-process values are not serialized, so the character count is read straight off the object.
            return value instanceof CharSequence text ? text.length() : 0;
        }

        @Override
        public int footprintBytes() {
            // Deliberately zero: on-heap references are bounded by entry count and the GC, not by the
            // off-heap budget. Charging a guessed object size against the native budget would let a bad
            // estimate starve the socket path of real memory.
            return 0;
        }

        @Override
        public byte flags() {
            return 0;
        }

        @Override
        public boolean tryRetain() {
            return true; // The GC is the reference count for on-heap values.
        }

        @Override
        public boolean releaseReference() {
            return false; // Nothing native to free.
        }
    }
}
