package io.fastcache.engine.core;

import java.nio.ByteBuffer;

/**
 * A borrowed, reference-counted view of a cache entry.
 *
 * <p>This is the only safe way to read an off-heap value. Holding a lease guarantees the underlying native
 * slot cannot be unmapped by a concurrent evictor; closing it releases that guarantee. Use it exactly as
 * you would a file handle:
 *
 * <pre>{@code
 * try (Lease lease = engine.acquire(key)) {
 *     if (lease == null) {
 *         return miss();
 *     }
 *     channel.write(lease.readOnlyView());   // straight from native memory to the socket
 * }
 * }</pre>
 *
 * <p><b>Not thread-safe by design.</b> A lease belongs to the virtual thread that took it. Sharing one
 * across threads would reintroduce exactly the race the reference count exists to prevent.
 */
public final class Lease implements AutoCloseable {

    private final CacheEntry entry;
    private final PayloadReleaser releaser;
    private boolean closed;

    Lease(CacheEntry entry, PayloadReleaser releaser) {
        this.entry = entry;
        this.releaser = releaser;
    }

    public CacheEntry entry() {
        return entry;
    }

    public String key() {
        return entry.key();
    }

    public byte flags() {
        return entry.payload().flags();
    }

    public long remainingTtlMillis() {
        return entry.remainingTtlMillis(System.currentTimeMillis());
    }

    /**
     * True when this value is past its TTL but still inside the stale-while-revalidate grace window.
     *
     * <p>A stale lease is a perfectly good value to return to a caller — that is the whole point of the
     * grace window. It is also the signal that someone should refresh the key, which
     * {@code ShardedStorageEngine.readThrough} acts on by electing a single refresher.
     */
    public boolean isStale() {
        return entry.isExpired(System.currentTimeMillis());
    }

    public int length() {
        return entry.payload().footprintBytes();
    }

    /**
     * Read-only window over the native slot, valid until {@link #close()}.
     *
     * @throws IllegalStateException if the value is an on-heap reference (use {@link #reference()})
     */
    public ByteBuffer readOnlyView() {
        ensureOpen();
        if (entry.payload() instanceof CachePayload.OffHeap offHeap) {
            return offHeap.readOnlyView();
        }
        throw new IllegalStateException("entry " + entry.key() + " holds an on-heap reference, not a buffer");
    }

    /** Copies the value onto the Java heap. Only for callers that genuinely need a {@code byte[]}. */
    public byte[] toByteArray() {
        ByteBuffer view = readOnlyView();
        byte[] copy = new byte[view.remaining()];
        view.get(copy);
        return copy;
    }

    /**
     * The live object stored by the in-process Spring path.
     *
     * @throws IllegalStateException if the value is an off-heap buffer
     */
    public Object reference() {
        ensureOpen();
        if (entry.payload() instanceof CachePayload.Reference ref) {
            return ref.value();
        }
        throw new IllegalStateException("entry " + entry.key() + " holds an off-heap buffer, not a reference");
    }

    public boolean isOffHeap() {
        return entry.payload() instanceof CachePayload.OffHeap;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("lease for key " + entry.key() + " is already closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return; // Idempotent: try-with-resources plus an explicit close must not double-release.
        }
        closed = true;
        releaser.release(entry.payload());
    }
}
