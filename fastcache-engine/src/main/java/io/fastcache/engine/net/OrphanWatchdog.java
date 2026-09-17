package io.fastcache.engine.net;

import io.fastcache.engine.util.FastCacheLog;
import io.fastcache.engine.util.TimeSpec;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orphan isolation: the poison-pill protocol that stops a sidecar outliving the process that started it.
 *
 * <h2>The scenario</h2>
 * A Python training script boots the sidecar, fills it with 4&nbsp;GB of embeddings, and is then killed with
 * {@code kill -9}. No atexit hook runs. No shutdown handler fires. The parent is gone, nothing will ever
 * connect to this JVM again — and it sits there holding 4&nbsp;GB of RAM until the machine is rebooted. Do
 * that three times in an afternoon of debugging and the box is out of memory with nothing visibly running.
 *
 * <h2>Two independent signals</h2>
 * <ol>
 *   <li><b>Heartbeat window</b> (as specified: 40s). Clients beat every {@code window/4}; any protocol
 *       traffic also counts. When the rolling window elapses with nothing received, the engine logs a
 *       terminal alert and exits cleanly. This catches every case, including a parent that is alive but
 *       permanently wedged.</li>
 *   <li><b>Parent liveness</b> (optional, and much faster). When the bootstrapper passes {@code --parent-pid},
 *       {@link ProcessHandle#isAlive()} turns a SIGKILL into a sub-second detection instead of a 40-second
 *       wait. This is the precise signal; the heartbeat is the general one.</li>
 * </ol>
 *
 * <p><b>The parent is not the only client.</b> A dead parent PID triggers termination only when no other
 * connection is live. The process that happened to spawn the sidecar has no special status: in a gunicorn
 * or Celery deployment, worker&nbsp;1 starts the engine and the other seven attach to it, and worker&nbsp;1
 * restarting is routine. Reaping on its PID alone would delete the warm cache out from under seven healthy
 * workers — turning a feature meant to prevent waste into a recurring outage. The heartbeat window remains
 * in force either way, so a genuinely abandoned engine is still reclaimed.
 *
 * <h2>Why it is opt-in</h2>
 * Armed only when the launcher asks for it. A sidecar embedded in a Spring Boot application, or one started
 * by hand to be shared by a fleet, has no heartbeating parent — arming this by default would make such a
 * deployment shoot itself in the head 40 seconds after startup. Zero-ops must not mean zero-control.
 *
 * <h2>Exit, not crash</h2>
 * {@code System.exit(0)} runs the registered shutdown hook, which stops the server and releases every
 * native slot. {@code Runtime.halt} would skip that and leak the state file. The exit code is 0 because an
 * orphan reaping itself is correct behaviour, not a failure — a non-zero code would make process
 * supervisors restart the very JVM we are trying to retire.
 */
final class OrphanWatchdog {

    private static final FastCacheLog LOG = FastCacheLog.of(OrphanWatchdog.class);

    /** Checks per heartbeat window. Four gives sub-window detection without a busy loop. */
    private static final int CHECKS_PER_WINDOW = 4;

    private final FastCacheServer server;
    private final long heartbeatWindowMillis;
    private final long parentPid;
    private final Runnable terminator;
    private final AtomicBoolean fired = new AtomicBoolean();

    private volatile Thread thread;

    OrphanWatchdog(FastCacheServer server, long heartbeatWindowMillis, long parentPid) {
        this(server, heartbeatWindowMillis, parentPid, () -> System.exit(0));
    }

    /** Package-private constructor taking an injectable terminator, so tests need not kill the JVM. */
    OrphanWatchdog(FastCacheServer server, long heartbeatWindowMillis, long parentPid, Runnable terminator) {
        this.server = server;
        this.heartbeatWindowMillis = heartbeatWindowMillis;
        this.parentPid = parentPid;
        this.terminator = terminator;
    }

    /** Starts monitoring. A window of zero or {@link TimeSpec#NEVER} disables the heartbeat check. */
    void start() {
        boolean heartbeatArmed = heartbeatWindowMillis > 0;
        boolean parentArmed = parentPid > 0;
        if (!heartbeatArmed && !parentArmed) {
            LOG.debug("Orphan watchdog disabled; sidecar will run until stopped or idle-timed-out.");
            return;
        }

        // A virtual thread here is deliberate: a platform daemon thread would be fine, but a non-daemon
        // one would itself keep the JVM alive — the exact failure this class exists to prevent.
        this.thread = Thread.ofVirtual().name("fastcache-orphan-watchdog").start(() -> loop(heartbeatArmed, parentArmed));

        LOG.info("Orphan watchdog armed: heartbeat window {0}{1}.",
                heartbeatArmed ? TimeSpec.format(heartbeatWindowMillis) : "disabled",
                parentArmed ? ", parent pid " + parentPid : "");
    }

    private void loop(boolean heartbeatArmed, boolean parentArmed) {
        long interval = heartbeatArmed
                ? Math.max(250L, heartbeatWindowMillis / CHECKS_PER_WINDOW)
                : 1_000L;

        while (server.isRunning() && !fired.get()) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (parentArmed && !isParentAlive() && server.liveConnections() == 0) {
                terminate("parent process " + parentPid + " is gone (hard kill) and no other client is "
                        + "connected; reaping orphaned sidecar");
                return;
            }

            if (heartbeatArmed) {
                long silence = System.currentTimeMillis() - server.lastActivityMillis();
                if (silence > heartbeatWindowMillis) {
                    terminate("no client heartbeat or command for " + TimeSpec.format(silence)
                            + " (window " + TimeSpec.format(heartbeatWindowMillis) + "); "
                            + "assuming the parent application died");
                    return;
                }
            }
        }
    }

    /**
     * @return whether the launching process is still running. An empty {@code ProcessHandle} means the PID
     *         no longer exists, which on every supported platform means the parent is genuinely gone.
     */
    private boolean isParentAlive() {
        try {
            Optional<ProcessHandle> handle = ProcessHandle.of(parentPid);
            return handle.isPresent() && handle.get().isAlive();
        } catch (SecurityException | UnsupportedOperationException e) {
            // Cannot inspect the parent on this platform: fall back to the heartbeat signal alone rather
            // than treating an unanswerable question as "the parent is dead".
            return true;
        }
    }

    private void terminate(String reason) {
        if (!fired.compareAndSet(false, true)) {
            return;
        }
        // Deliberately at error level and on stderr: this is a terminal event, and when someone later asks
        // "where did my cache go?", this line is the entire answer.
        LOG.error("FASTCACHE ORPHAN WATCHDOG: " + reason + ". Shutting down and releasing all memory.", null);
        System.err.println("FASTCACHE_ORPHANED " + reason);
        System.err.flush();
        terminator.run();
    }

    void stop() {
        fired.set(true);
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
    }
}
