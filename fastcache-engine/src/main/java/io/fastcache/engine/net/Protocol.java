package io.fastcache.engine.net;

import io.fastcache.engine.core.WriteStatus;

/**
 * The FastCache wire protocol: fixed-width binary framing, no text parsing, no reflection, no schema
 * negotiation. Both header layouts are constant-size so a reader can always issue exactly two reads
 * (header, then body) and never has to scan for a delimiter.
 *
 * <p><b>Protocol v2.</b> The request header carries a {@code sourceCharacters} field so the savings ledger
 * can price a compressed payload by its original text length rather than its wire size. Client and engine
 * ship together in one artifact, so the header was widened rather than bolted on as an optional trailer;
 * a v1 client talking to a v2 engine desynchronises on the first frame and is rejected on the magic check.
 *
 * <pre>
 * REQUEST  (22-byte header, big-endian)
 *   0  u16  magic            0xFC01
 *   2  u8   opcode
 *   3  u8   flags            opaque client codec/compression bits, echoed back on GET
 *   4  u16  keyLength        UTF-8 bytes, max 65535
 *   6  i64  ttlMillis        -1 = never, 0 = server default
 *   14 i32  valueLength      0 for GET/DELETE/PING/STATS/FLUSH
 *   18 i32  sourceCharacters pre-compression character count, 0 when not text (accounting only)
 *   22 ...  key bytes, then value bytes
 *
 * RESPONSE (8-byte header, big-endian)
 *   0  u16  magic          0xFC02
 *   2  u8   status
 *   3  u8   flags          echoed payload codec bits
 *   4  i32  valueLength
 *   8  ...  value bytes
 * </pre>
 *
 * <p>Wire codes are frozen: the Python client decodes these integers directly.
 */
public final class Protocol {

    private Protocol() {
    }

    public static final int REQUEST_MAGIC = 0xFC01;
    public static final int RESPONSE_MAGIC = 0xFC02;

    public static final int REQUEST_HEADER_BYTES = 22;
    public static final int RESPONSE_HEADER_BYTES = 8;
    public static final int MAX_KEY_BYTES = 65_535;

    // --- Opcodes ---------------------------------------------------------------------------------------
    public static final byte OP_GET = 1;
    public static final byte OP_PUT = 2;
    public static final byte OP_DELETE = 3;
    public static final byte OP_PING = 4;
    public static final byte OP_STATS = 5;
    public static final byte OP_FLUSH = 6;
    public static final byte OP_CLOSE = 7;

    /**
     * Liveness beat from a client, carrying that client's L1 telemetry as its payload. Distinct from
     * {@link #OP_PING} because a heartbeat also feeds the orphan watchdog and the metrics console, and a
     * PING must stay a pure zero-side-effect probe usable by discovery.
     */
    public static final byte OP_HEARTBEAT = 8;

    /**
     * Cross-process single-flight: "may I be the one to recompute this key?". Returns {@link #ST_OK} to
     * exactly one caller and {@link #ST_MISS} to everyone else, so a stampede is collapsed across every
     * Python worker on the host, not merely within one interpreter.
     */
    public static final byte OP_REFRESH_LEASE = 9;

    /** Releases a refresh lease taken with {@link #OP_REFRESH_LEASE}. */
    public static final byte OP_REFRESH_DONE = 10;

    // --- Response statuses -----------------------------------------------------------------------------
    public static final byte ST_OK = 0;
    public static final byte ST_MISS = 1;
    public static final byte ST_REJECTED_MEMORY = 2;
    public static final byte ST_REJECTED_TOO_LARGE = 3;
    public static final byte ST_REJECTED_SHUTDOWN = 4;
    public static final byte ST_REJECTED_ALLOCATION = 5;
    public static final byte ST_BAD_REQUEST = 6;
    public static final byte ST_SERVER_ERROR = 7;

    /**
     * A hit served from the stale-while-revalidate window. The value is usable and the client should use
     * it; it also tells the client this key is due for a refresh.
     */
    public static final byte ST_STALE = 8;

    /** Projects an engine write outcome onto the wire status space. */
    public static byte statusFor(WriteStatus status) {
        return switch (status) {
            case ACCEPTED -> ST_OK;
            case REJECTED_MEMORY_PRESSURE -> ST_REJECTED_MEMORY;
            case REJECTED_TOO_LARGE -> ST_REJECTED_TOO_LARGE;
            case REJECTED_SHUTDOWN -> ST_REJECTED_SHUTDOWN;
            case REJECTED_ALLOCATION_FAILED -> ST_REJECTED_ALLOCATION;
        };
    }

    public static String opcodeName(byte opcode) {
        return switch (opcode) {
            case OP_GET -> "GET";
            case OP_PUT -> "PUT";
            case OP_DELETE -> "DELETE";
            case OP_PING -> "PING";
            case OP_STATS -> "STATS";
            case OP_FLUSH -> "FLUSH";
            case OP_CLOSE -> "CLOSE";
            case OP_HEARTBEAT -> "HEARTBEAT";
            case OP_REFRESH_LEASE -> "REFRESH_LEASE";
            case OP_REFRESH_DONE -> "REFRESH_DONE";
            default -> "UNKNOWN(" + (opcode & 0xFF) + ")";
        };
    }
}
