package io.fastcache.engine.core;

/**
 * Atomic status codes returned by every write path. FastCache never signals back-pressure with an
 * exception: a rejected write is a normal, expected outcome that the caller degrades gracefully around
 * (the Python decorator simply calls the wrapped function; the Spring aspect simply skips caching).
 *
 * <p>The wire codes are frozen &mdash; the Python client switches on these integers.
 */
public enum WriteStatus {

    /** Stored. */
    ACCEPTED(0),

    /**
     * Refused by {@code MemoryGuard}: the engine budget or the machine crossed the rejection ratio.
     * The cache is intact and reads continue to serve; only writes are shed.
     */
    REJECTED_MEMORY_PRESSURE(1),

    /** Payload exceeded {@code maxValueBytes}. Guards against a single request eating the whole budget. */
    REJECTED_TOO_LARGE(2),

    /** Engine is closing; no new state is being accepted. */
    REJECTED_SHUTDOWN(3),

    /** Native allocation failed even though admission control passed (OS refused the mapping). */
    REJECTED_ALLOCATION_FAILED(4);

    private final int code;

    WriteStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean accepted() {
        return this == ACCEPTED;
    }

    public static WriteStatus fromCode(int code) {
        for (WriteStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown write status code: " + code);
    }
}
