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
 * A minimal RESP2 client, written to be structurally identical to {@link WireClient}.
 *
 * <p><b>Why not Jedis or Lettuce.</b> The question this benchmark asks is whether FastCache's server and
 * protocol beat Redis's, not whether one Java client library is better optimised than another. Using a
 * mature client for Redis and a hand-rolled one for FastCache would fold the client difference into the
 * answer. So both sides get the same client architecture: same {@code Socket} options, same 64 KiB
 * buffered streams, same per-call pooling, same measurement harness. Only the server and the wire format
 * differ.
 *
 * <p>The bias this introduces, stated plainly, runs <b>in Redis's favour</b>: a minimal client has less
 * abstraction overhead than Lettuce's netty pipeline, so these numbers are if anything slightly better
 * than a real application using a real client would see. That is the right direction for a benchmark whose
 * author has an interest in the other side winning.
 *
 * <p>Only the commands the benchmark needs are implemented: SET (with optional PX), GET, DEL, FLUSHALL,
 * DBSIZE and INFO.
 */
public final class RespClient implements AutoCloseable {

    private static final byte[] CRLF = {'\r', '\n'};

    private final String host;
    private final int port;
    private final Deque<Connection> pool = new ArrayDeque<>();

    private record Connection(Socket socket, DataInputStream in, DataOutputStream out) { }

    public RespClient(String host, int port) {
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

    /** Writes a RESP array of bulk strings: {@code *N\r\n$len\r\n<bytes>\r\n...}. */
    private static void writeCommand(DataOutputStream out, byte[]... args) throws IOException {
        out.write('*');
        out.write(Integer.toString(args.length).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        for (byte[] arg : args) {
            out.write('$');
            out.write(Integer.toString(arg.length).getBytes(StandardCharsets.US_ASCII));
            out.write(CRLF);
            out.write(arg);
            out.write(CRLF);
        }
        out.flush();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Reads one line up to CRLF, returning it without the terminator. */
    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder builder = new StringBuilder(32);
        int previous = -1;
        while (true) {
            int current = in.read();
            if (current < 0) {
                throw new IOException("connection closed mid-reply");
            }
            if (previous == '\r' && current == '\n') {
                builder.setLength(builder.length() - 1);
                return builder.toString();
            }
            builder.append((char) current);
            previous = current;
        }
    }

    /**
     * Reads a bulk-string reply.
     *
     * @return the payload, or null for a RESP nil ({@code $-1})
     */
    private static byte[] readBulk(DataInputStream in) throws IOException {
        String header = readLine(in);
        if (header.isEmpty()) {
            throw new IOException("empty reply header");
        }
        char type = header.charAt(0);
        if (type == '-') {
            throw new IOException("redis error: " + header.substring(1));
        }
        if (type != '$') {
            // +OK, :N and the like: no body to read, and no payload to return.
            return null;
        }
        int length = Integer.parseInt(header.substring(1));
        if (length < 0) {
            return null;   // nil - a cache miss
        }
        byte[] value = new byte[length];
        in.readFully(value);
        in.skipNBytes(2);  // trailing CRLF
        return value;
    }

    /** Reads a reply whose body is discarded, returning the status/integer line. */
    private String readStatus(DataInputStream in) throws IOException {
        String header = readLine(in);
        if (!header.isEmpty() && header.charAt(0) == '$') {
            int length = Integer.parseInt(header.substring(1));
            if (length >= 0) {
                in.skipNBytes(length + 2L);
            }
        }
        if (!header.isEmpty() && header.charAt(0) == '-') {
            throw new IOException("redis error: " + header.substring(1));
        }
        return header;
    }

    public boolean set(String key, byte[] value, long ttlMillis) {
        Connection connection = null;
        try {
            connection = borrow();
            if (ttlMillis > 0) {
                writeCommand(connection.out(), ascii("SET"), ascii(key), value, ascii("PX"),
                        ascii(Long.toString(ttlMillis)));
            } else {
                writeCommand(connection.out(), ascii("SET"), ascii(key), value);
            }
            String status = readStatus(connection.in());
            giveBack(connection);
            return status.startsWith("+OK");
        } catch (IOException e) {
            if (connection != null) {
                discard(connection);
            }
            throw new RuntimeException("redis SET failed for key " + key, e);
        }
    }

    public byte[] get(String key) {
        Connection connection = null;
        try {
            connection = borrow();
            writeCommand(connection.out(), ascii("GET"), ascii(key));
            byte[] value = readBulk(connection.in());
            giveBack(connection);
            return value;
        } catch (IOException e) {
            if (connection != null) {
                discard(connection);
            }
            throw new RuntimeException("redis GET failed for key " + key, e);
        }
    }

    public void del(String key) {
        simple(ascii("DEL"), ascii(key));
    }

    public void flushAll() {
        simple(ascii("FLUSHALL"));
    }

    public long dbSize() {
        Connection connection = null;
        try {
            connection = borrow();
            writeCommand(connection.out(), ascii("DBSIZE"));
            String reply = readStatus(connection.in());
            giveBack(connection);
            return reply.startsWith(":") ? Long.parseLong(reply.substring(1)) : -1;
        } catch (IOException | NumberFormatException e) {
            if (connection != null) {
                discard(connection);
            }
            return -1;
        }
    }

    /** Parses the numeric fields of an {@code INFO <section>} reply. */
    public Map<String, Long> info(String section) {
        Connection connection = null;
        try {
            connection = borrow();
            writeCommand(connection.out(), ascii("INFO"), ascii(section));
            byte[] body = readBulk(connection.in());
            giveBack(connection);
            Map<String, Long> parsed = new HashMap<>();
            if (body == null) {
                return parsed;
            }
            for (String line : new String(body, StandardCharsets.UTF_8).split("\r?\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0 || line.startsWith("#")) {
                    continue;
                }
                try {
                    parsed.put(line.substring(0, colon), (long) Double.parseDouble(line.substring(colon + 1)));
                } catch (NumberFormatException ignored) {
                    // Non-numeric INFO fields (version strings, policy names) are not needed here.
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

    private void simple(byte[]... args) {
        Connection connection = null;
        try {
            connection = borrow();
            writeCommand(connection.out(), args);
            readStatus(connection.in());
            giveBack(connection);
        } catch (IOException e) {
            if (connection != null) {
                discard(connection);
            }
            throw new RuntimeException("redis command failed", e);
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
