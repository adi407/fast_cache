package io.fastcache.engine.net;

import io.fastcache.engine.core.EngineStats;
import io.fastcache.engine.core.Lease;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.core.WriteStatus;
import io.fastcache.engine.util.FastCacheLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles one client connection, start to finish, on one dedicated virtual thread.
 *
 * <h2>Why blocking NIO on a virtual thread, not {@code AsynchronousSocketChannel}</h2>
 * A {@link SocketChannel} read issued from a virtual thread does not block an OS thread. The JDK detects
 * the virtual-thread context, registers the socket with the platform poller, and <em>unmounts</em> the
 * continuation until the socket is ready. The carrier thread is returned to the pool immediately. That
 * gives the scalability of an async reactor with the readability of straight-line blocking code &mdash; no
 * callback chains, no completion handlers, and stack traces that actually name the operation that failed.
 * {@code AsynchronousSocketChannel} would add a completion-handler thread pool and a state machine to
 * achieve strictly less.
 *
 * <h2>Thread-pinning hazards, and how each is avoided</h2>
 * <ul>
 *   <li>No {@code synchronized} block exists anywhere on this path &mdash; a virtual thread parked on a
 *       socket read inside a monitor would pin its carrier for the entire network round trip, which under
 *       token-streaming load is measured in seconds.</li>
 *   <li>Per-session buffers are plain fields, not a shared pool behind a lock. With virtual threads,
 *       buffer pooling is an anti-pattern: contention on the pool is far more expensive than the
 *       allocation it saves, and there may be a hundred thousand sessions.</li>
 *   <li>The engine's own locks are {@code StampedLock}/{@code ReentrantLock}, both virtual-thread aware.</li>
 * </ul>
 *
 * <h2>Zero-copy PUT</h2>
 * A large payload is read from the socket <em>straight into the off-heap slot</em> that will hold it in the
 * cache. There is no intermediate {@code byte[]}, so a 50&nbsp;MB context window never becomes a
 * garbage-collectable object at any point.
 */
final class ClientSession implements Runnable {

    private static final FastCacheLog LOG = FastCacheLog.of(ClientSession.class);

    /** Scratch buffer used only to drain payloads we are rejecting, so the stream stays framed. */
    private static final int DRAIN_CHUNK_BYTES = 64 * 1024;

    private final SocketChannel channel;
    private final ShardedStorageEngine engine;
    private final AtomicLong lastActivityMillis;
    private final long remoteId;

    /** Whether this session ever sent telemetry, so only reporting sessions are retired into the ledger. */
    private boolean heartbeatSeen;

    private final ByteBuffer requestHeader = ByteBuffer.allocate(Protocol.REQUEST_HEADER_BYTES);
    private final ByteBuffer responseHeader = ByteBuffer.allocate(Protocol.RESPONSE_HEADER_BYTES);

    ClientSession(SocketChannel channel, ShardedStorageEngine engine, AtomicLong lastActivityMillis, long remoteId) {
        this.channel = channel;
        this.engine = engine;
        this.lastActivityMillis = lastActivityMillis;
        this.remoteId = remoteId;
    }

    @Override
    public void run() {
        try {
            channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
            while (channel.isOpen()) {
                if (!handleOneRequest()) {
                    break;
                }
            }
        } catch (ClosedChannelException e) {
            // Ordinary client disconnect or server shutdown. Not an error.
        } catch (IOException e) {
            LOG.debug("Session {0} terminated: {1}", remoteId, e.toString());
        } catch (Throwable t) {
            // A session must never take the server down with it.
            LOG.error("Unhandled failure in session " + remoteId, t instanceof Exception e ? e : new RuntimeException(t));
        } finally {
            // Fold this session's cumulative L1 counters into the retired totals before forgetting it,
            // so a disconnecting client does not make the console's savings figure go backwards.
            if (heartbeatSeen) {
                engine.savingsLedger().retireClient(remoteId);
            }
            closeQuietly();
        }
    }

    /** @return false when the connection should be closed */
    private boolean handleOneRequest() throws IOException {
        requestHeader.clear();
        if (!readFully(requestHeader)) {
            return false; // Clean EOF.
        }
        requestHeader.flip();
        lastActivityMillis.set(System.currentTimeMillis());

        int magic = Short.toUnsignedInt(requestHeader.getShort());
        if (magic != Protocol.REQUEST_MAGIC) {
            // Desynchronised or non-FastCache client: we cannot know where the next frame starts, so the
            // only safe action is to report and hang up rather than guess.
            LOG.warn("Session {0} sent bad magic 0x{1}; closing.", remoteId, Integer.toHexString(magic));
            writeStatusOnly(Protocol.ST_BAD_REQUEST);
            return false;
        }

        byte opcode = requestHeader.get();
        byte flags = requestHeader.get();
        int keyLength = Short.toUnsignedInt(requestHeader.getShort());
        long ttlMillis = requestHeader.getLong();
        int valueLength = requestHeader.getInt();
        int sourceCharacters = requestHeader.getInt();

        if (keyLength < 0 || valueLength < 0) {
            // Structurally impossible frame: we cannot find the next frame boundary, so hang up.
            LOG.warn("Session {0} sent a malformed frame (key={1}, value={2}); closing.",
                    remoteId, keyLength, valueLength);
            writeStatusOnly(Protocol.ST_BAD_REQUEST);
            return false;
        }

        // An over-limit payload is rejected, not fatal: the frame is drained in handlePut and the
        // connection survives, so the client gets REJECTED_TOO_LARGE instead of a connection reset
        // halfway through its send. Beyond the drain ceiling, though, reading the body purely to discard
        // it is free work an abusive client could ask for indefinitely, so that one does close.
        if (valueLength > drainCeiling()) {
            LOG.warn("Session {0} announced {1} bytes, past the {2}-byte drain ceiling; closing.",
                    remoteId, valueLength, drainCeiling());
            writeStatusOnly(Protocol.ST_REJECTED_TOO_LARGE);
            return false;
        }

        String key = keyLength == 0 ? "" : readKey(keyLength);

        return switch (opcode) {
            case Protocol.OP_GET -> handleGet(key);
            case Protocol.OP_PUT -> handlePut(key, flags, ttlMillis, valueLength, sourceCharacters);
            case Protocol.OP_DELETE -> handleDelete(key);
            case Protocol.OP_PING -> writeStatusOnly(Protocol.ST_OK);
            case Protocol.OP_HEARTBEAT -> handleHeartbeat(valueLength);
            case Protocol.OP_REFRESH_LEASE -> handleRefreshLease(key);
            case Protocol.OP_REFRESH_DONE -> handleRefreshDone(key);
            case Protocol.OP_STATS -> handleStats();
            case Protocol.OP_FLUSH -> handleFlush();
            case Protocol.OP_CLOSE -> {
                writeStatusOnly(Protocol.ST_OK);
                yield false;
            }
            default -> {
                LOG.warn("Session {0} sent unknown opcode {1}.", remoteId, Protocol.opcodeName(opcode));
                yield writeStatusOnly(Protocol.ST_BAD_REQUEST);
            }
        };
    }

    // ---------------------------------------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------------------------------------

    private boolean handleGet(String key) throws IOException {
        // try-with-resources guarantees the reference count is dropped even if the socket write throws
        // mid-transfer, which is the difference between a leaked 50 MB native slot and a clean recovery.
        try (Lease lease = engine.acquire(key)) {
            if (lease == null) {
                return writeStatusOnly(Protocol.ST_MISS);
            }
            ByteBuffer payload = lease.readOnlyView();
            responseHeader.clear();
            responseHeader.putShort((short) Protocol.RESPONSE_MAGIC);
            // ST_STALE is still a usable value: it tells the client to serve this now and schedule a
            // refresh, which is what keeps a thousand readers off the backend during a recompute.
            responseHeader.put(lease.isStale() ? Protocol.ST_STALE : Protocol.ST_OK);
            responseHeader.put(lease.flags());
            responseHeader.putInt(payload.remaining());
            responseHeader.flip();

            // Gathering write: header and off-heap payload leave in a single syscall, and the payload goes
            // native-memory-to-socket without ever being copied onto the Java heap.
            writeFully(new ByteBuffer[]{responseHeader, payload});
            return true;
        }
    }

    private boolean handlePut(String key, byte flags, long ttlMillis, int valueLength, int sourceCharacters)
            throws IOException {
        if (key.isEmpty()) {
            drain(valueLength);
            return writeStatusOnly(Protocol.ST_BAD_REQUEST);
        }

        ShardedStorageEngine.WriteTicket ticket = engine.beginWrite(valueLength);
        if (ticket == null) {
            // Rejected by admission control. The payload is still in flight, so it must be drained to keep
            // the stream framed; dropping the connection instead would turn back-pressure into an outage.
            WriteStatus status = engine.classifyRejection(valueLength);
            drain(valueLength);
            return writeStatusOnly(Protocol.statusFor(status));
        }

        try {
            ByteBuffer slot = ticket.slot();
            slot.clear();
            if (!readFully(slot)) {
                engine.abortWrite(ticket);
                return false; // Client vanished mid-payload.
            }
            // ttlMillis: -1 means never, 0 means "use the server default" — both handled by the engine.
            WriteStatus status = engine.commitWrite(key, ticket, flags, ttlMillis, sourceCharacters);
            return writeStatusOnly(Protocol.statusFor(status));
        } catch (IOException | RuntimeException e) {
            engine.abortWrite(ticket);
            throw e;
        }
    }

    /**
     * Absorbs a client liveness beat plus its L1 telemetry.
     *
     * <p>The payload is a tiny {@code key=value} text frame rather than a binary struct: it is sent once
     * every few seconds per client, so parsing cost is irrelevant, and a self-describing format means an
     * older engine and a newer client that added a field still interoperate instead of desynchronising.
     */
    private boolean handleHeartbeat(int valueLength) throws IOException {
        long l1Hits = 0;
        long l1Characters = 0;
        if (valueLength > 0) {
            ByteBuffer body = ByteBuffer.allocate(valueLength);
            if (!readFully(body)) {
                return false;
            }
            String report = new String(body.array(), 0, valueLength, StandardCharsets.UTF_8);
            for (String token : report.split("\\s+")) {
                int equals = token.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = token.substring(0, equals);
                try {
                    long value = Long.parseLong(token.substring(equals + 1));
                    if (name.equals("l1_hits")) {
                        l1Hits = value;
                    } else if (name.equals("l1_chars")) {
                        l1Characters = value;
                    }
                } catch (NumberFormatException ignored) {
                    // A malformed telemetry field is not worth failing a liveness beat over.
                }
            }
        }
        engine.savingsLedger().reportClient(remoteId, l1Hits, l1Characters);
        heartbeatSeen = true;
        return writeStatusOnly(Protocol.ST_OK);
    }

    /**
     * Cross-process single-flight. Grants the refresh lease to exactly one caller host-wide; everyone else
     * receives ST_MISS and serves their stale copy instead of calling the model.
     */
    private boolean handleRefreshLease(String key) throws IOException {
        boolean granted = !key.isEmpty() && engine.refreshCoordinator().tryAcquireLead(key);
        return writeStatusOnly(granted ? Protocol.ST_OK : Protocol.ST_MISS);
    }

    private boolean handleRefreshDone(String key) throws IOException {
        if (!key.isEmpty()) {
            engine.refreshCoordinator().complete(key);
        }
        return writeStatusOnly(Protocol.ST_OK);
    }

    private boolean handleDelete(String key) throws IOException {
        boolean removed = engine.delete(key);
        return writeStatusOnly(removed ? Protocol.ST_OK : Protocol.ST_MISS);
    }

    private boolean handleStats() throws IOException {
        EngineStats stats = engine.stats();
        return writePayload(Protocol.ST_OK, (byte) 0,
                ByteBuffer.wrap(stats.toWireString().getBytes(StandardCharsets.UTF_8)));
    }

    private boolean handleFlush() throws IOException {
        long reclaimed = engine.flush();
        return writePayload(Protocol.ST_OK, (byte) 0,
                ByteBuffer.wrap(Long.toString(reclaimed).getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------------------------------------------
    // Framing primitives
    // ---------------------------------------------------------------------------------------------------

    private String readKey(int keyLength) throws IOException {
        ByteBuffer keyBuffer = ByteBuffer.allocate(keyLength);
        if (!readFully(keyBuffer)) {
            throw new ClosedChannelException();
        }
        return new String(keyBuffer.array(), 0, keyLength, StandardCharsets.UTF_8);
    }

    /**
     * Reads until the buffer is full.
     *
     * @return false on clean EOF before any byte of this buffer was read
     */
    private boolean readFully(ByteBuffer buffer) throws IOException {
        boolean anyRead = false;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer); // Unmounts the virtual thread; does not block a carrier.
            if (read < 0) {
                if (anyRead) {
                    throw new IOException("truncated frame: " + buffer.remaining() + " bytes missing");
                }
                return false;
            }
            anyRead |= read > 0;
        }
        return true;
    }

    /**
     * Largest announced payload we are willing to read-and-discard in order to keep a connection usable.
     * Twice the accepted maximum: generous enough that a client marginally over the limit gets a clean
     * rejection, tight enough that nobody can make the sidecar read gigabytes for free.
     */
    private long drainCeiling() {
        return 2L * engine.config().maxValueBytes();
    }

    /** Reads and discards {@code count} bytes so a rejected PUT does not desynchronise the stream. */
    private void drain(int count) throws IOException {
        int remaining = count;
        ByteBuffer scratch = ByteBuffer.allocate(Math.min(Math.max(remaining, 1), DRAIN_CHUNK_BYTES));
        while (remaining > 0) {
            scratch.clear();
            scratch.limit(Math.min(remaining, scratch.capacity()));
            int read = channel.read(scratch);
            if (read < 0) {
                throw new ClosedChannelException();
            }
            remaining -= read;
        }
    }

    private boolean writeStatusOnly(byte status) throws IOException {
        responseHeader.clear();
        responseHeader.putShort((short) Protocol.RESPONSE_MAGIC);
        responseHeader.put(status);
        responseHeader.put((byte) 0);
        responseHeader.putInt(0);
        responseHeader.flip();
        writeFully(new ByteBuffer[]{responseHeader});
        return true;
    }

    private boolean writePayload(byte status, byte flags, ByteBuffer payload) throws IOException {
        responseHeader.clear();
        responseHeader.putShort((short) Protocol.RESPONSE_MAGIC);
        responseHeader.put(status);
        responseHeader.put(flags);
        responseHeader.putInt(payload.remaining());
        responseHeader.flip();
        writeFully(new ByteBuffer[]{responseHeader, payload});
        return true;
    }

    private void writeFully(ByteBuffer[] buffers) throws IOException {
        long remaining = 0;
        for (ByteBuffer buffer : buffers) {
            remaining += buffer.remaining();
        }
        while (remaining > 0) {
            long written = channel.write(buffers); // Also unmounts rather than blocking a carrier.
            if (written < 0) {
                throw new ClosedChannelException();
            }
            remaining -= written;
        }
    }

    private void closeQuietly() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not worth a log line.
        }
    }
}
