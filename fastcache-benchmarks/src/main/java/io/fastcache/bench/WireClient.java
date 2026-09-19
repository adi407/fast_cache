package io.fastcache.bench;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * A minimal Java client for the FastCache wire protocol.
 *
 * <p>Separate from {@link FastCacheSidecarArm} because two different things need it: the benchmark arm,
 * which also owns the sidecar process, and the Phase 5 reader, which owns nothing and knows only a port.
 *
 * <p>Framing follows {@code io.fastcache.engine.net.Protocol} v2: a 22-byte big-endian request header and
 * an 8-byte response header. The constants are duplicated here rather than imported so that a protocol
 * change breaks this client loudly at test time instead of silently agreeing with itself.
 */
public final class WireClient implements AutoCloseable {

    private static final int REQUEST_MAGIC = 0xFC01;
    private static final int RESPONSE_MAGIC = 0xFC02;

    static final byte OP_GET = 1;
    static final byte OP_PUT = 2;
    static final byte OP_DELETE = 3;
    static final byte OP_STATS = 5;
    static final byte OP_FLUSH = 6;

    static final byte ST_OK = 0;
    static final byte ST_STALE = 8;

    private final String host;
    private final int port;
    private final Deque<Connection> pool = new ArrayDeque<>();

    private record Connection(Socket socket, DataInputStream in, DataOutputStream out) { }

    public WireClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public int port() {
        return port;
    }

    private Connection borrow() throws IOException {
        synchronized (pool) {
            Connection pooled = pool.poll();
            if (pooled != null) {
                return pooled;
            }
        }
        Socket socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        return new Connection(socket,
                new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16)),
                new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16)));
    }

    private void giveBack(Connection connection) {
        synchronized (pool) {
            pool.push(connection);
        }
    }

    private void discard(Connection connection) {
        try {
            connection.socket().close();
        } catch (IOException ignored) {
            // Already broken.
        }
    }

    private static void writeHeader(DataOutputStream out, byte opcode, byte[] key, long ttlMillis,
                                    int valueLength) throws IOException {
        out.writeShort(REQUEST_MAGIC);
        out.writeByte(opcode);
        out.writeByte(0);                       // flags: opaque client codec bits, unused here
        out.writeShort(key.length);
        out.writeLong(ttlMillis);
        out.writeInt(valueLength);
        out.writeInt(0);                        // sourceCharacters: accounting only
        out.write(key);
    }

    /** @return the response status; any body is read and discarded */
    private byte call(byte opcode, String key, byte[] value, long ttlMillis) {
        Connection connection = null;
        try {
            connection = borrow();
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            writeHeader(connection.out(), opcode, keyBytes, ttlMillis,
                    value == null ? 0 : value.length);
            if (value != null) {
                connection.out().write(value);
            }
            connection.out().flush();

            DataInputStream in = connection.in();
            byte status = readResponseHeaderStatus(in);
            int length = in.readInt();
            if (length > 0) {
                in.skipNBytes(length);
            }
            giveBack(connection);
            return status;
        } catch (IOException e) {
            if (connection != null) {
                discard(connection);
            }
            throw new RuntimeException("wire call " + opcode + " failed for key " + key, e);
        }
    }

    private static byte readResponseHeaderStatus(DataInputStream in) throws IOException {
        int magic = in.readUnsignedShort();
        if (magic != RESPONSE_MAGIC) {
            throw new IOException("bad response magic 0x" + Integer.toHexString(magic));
        }
        byte status = in.readByte();
        in.readByte();   // echoed flags
        return status;
    }

    public boolean put(String key, byte[] value, long ttlMillis) {
        return call(OP_PUT, key, value, ttlMillis <= 0 ? 0 : ttlMillis) == ST_OK;
    }

    public byte[] get(String key) {
        Connection connection = null;
        try {
            connection = borrow();
            writeHeader(connection.out(), OP_GET, key.getBytes(StandardCharsets.UTF_8), 0, 0);
            connection.out().flush();

            DataInputStream in = connection.in();
            byte status = readResponseHeaderStatus(in);
            int length = in.readInt();
            byte[] value = null;
            if (length > 0) {
                value = new byte[length];
                in.readFully(value);
            }
            giveBack(connection);
            return (status == ST_OK || status == ST_STALE) ? value : null;
        } catch (IOException e) {
            if (connection != null) {
                discard(connection);
            }
            throw new RuntimeException("wire get failed for key " + key, e);
        }
    }

    public void delete(String key) {
        call(OP_DELETE, key, null, 0);
    }

    public void flush() {
        call(OP_FLUSH, "", null, 0);
    }

    /** Parses the engine's {@code key=value} STATS line into the numeric fields. */
    public Map<String, Long> stats() {
        Connection connection = null;
        try {
            connection = borrow();
            writeHeader(connection.out(), OP_STATS, new byte[0], 0, 0);
            connection.out().flush();

            DataInputStream in = connection.in();
            readResponseHeaderStatus(in);
            int length = in.readInt();
            byte[] body = new byte[Math.max(0, length)];
            if (length > 0) {
                in.readFully(body);
            }
            giveBack(connection);

            Map<String, Long> parsed = new HashMap<>();
            for (String token : new String(body, StandardCharsets.UTF_8).split("\\s+")) {
                int eq = token.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                try {
                    parsed.put(token.substring(0, eq), (long) Double.parseDouble(token.substring(eq + 1)));
                } catch (NumberFormatException ignored) {
                    // Non-numeric fields (model name, rejecting=true) are not needed here.
                }
            }
            return parsed;
        } catch (IOException e) {
            if (connection != null) {
                discard(connection);
            }
            return Map.of();
        }
    }

    @Override
    public void close() {
        synchronized (pool) {
            pool.forEach(this::discard);
            pool.clear();
        }
    }
}
