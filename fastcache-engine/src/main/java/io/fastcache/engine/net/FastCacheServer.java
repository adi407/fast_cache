package io.fastcache.engine.net;

import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.util.FastCacheLog;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sidecar's TCP front door: an NIO acceptor loop that spawns one untethered virtual thread per client
 * connection.
 *
 * <p><b>Binding.</b> Loopback only by default. The sidecar holds an entire process's cache with no
 * authentication; exposing it on {@code 0.0.0.0} would be a data-exfiltration hole, and a data scientist
 * running {@code import fastcache} should not be able to create one by accident. Binding elsewhere requires
 * an explicit host argument.
 *
 * <p><b>Port 0.</b> Binding to port 0 lets the OS assign a free ephemeral port, which the Python
 * bootstrapper reads back from the handshake line. That is what makes "no infrastructure setup" true even
 * when three notebooks start at once.
 *
 * <p><b>One virtual thread per connection.</b> With ten thousand concurrent token streams that is ten
 * thousand virtual threads &mdash; roughly a few hundred bytes of continuation each, scheduled onto a
 * carrier pool the size of the core count. The same shape on platform threads would need a bounded pool
 * and a queue, which reintroduces head-of-line blocking on exactly the slow, large transfers this engine
 * is built for.
 */
public final class FastCacheServer implements AutoCloseable {

    private static final FastCacheLog LOG = FastCacheLog.of(FastCacheServer.class);

    private final ShardedStorageEngine engine;
    private final String bindHost;
    private final int requestedPort;
    private final int backlog;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong connectionCounter = new AtomicLong();
    private final AtomicLong liveConnections = new AtomicLong();
    private final AtomicLong lastActivityMillis = new AtomicLong(System.currentTimeMillis());

    private volatile ServerSocketChannel serverChannel;
    private volatile ExecutorService sessionExecutor;
    private volatile Thread acceptorThread;
    private volatile int boundPort = -1;

    public FastCacheServer(ShardedStorageEngine engine, String bindHost, int requestedPort) {
        this(engine, bindHost, requestedPort, 1024);
    }

    public FastCacheServer(ShardedStorageEngine engine, String bindHost, int requestedPort, int backlog) {
        this.engine = engine;
        this.bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        this.requestedPort = requestedPort;
        this.backlog = backlog;
    }

    /**
     * Binds the listening socket and starts accepting. Returns as soon as the port is bound, so the caller
     * can print the handshake line before any client could possibly connect.
     *
     * @return the actual bound port (resolved when port 0 was requested)
     */
    public int start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return boundPort;
        }
        ServerSocketChannel channel = ServerSocketChannel.open();
        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
            channel.bind(new InetSocketAddress(InetAddress.getByName(bindHost), requestedPort), backlog);
            // Blocking mode is intentional: every accept runs on a virtual thread, where "blocking" means
            // "unmount the continuation", not "park an OS thread".
            channel.configureBlocking(true);
        } catch (IOException e) {
            running.set(false);
            channel.close();
            throw e;
        }

        this.serverChannel = channel;
        this.boundPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        this.sessionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.acceptorThread = Thread.ofVirtual().name("fastcache-acceptor").start(this::acceptLoop);

        LOG.info("FastCache sidecar listening on {0}:{1}", bindHost, boundPort);
        return boundPort;
    }

    private void acceptLoop() {
        ServerSocketChannel channel = serverChannel;
        while (running.get() && channel != null && channel.isOpen()) {
            try {
                SocketChannel client = channel.accept();
                if (client == null) {
                    continue;
                }
                long id = connectionCounter.incrementAndGet();
                liveConnections.incrementAndGet();
                lastActivityMillis.set(System.currentTimeMillis());
                sessionExecutor.execute(() -> {
                    try {
                        new ClientSession(client, engine, lastActivityMillis, id).run();
                    } finally {
                        liveConnections.decrementAndGet();
                    }
                });
            } catch (AsynchronousCloseException e) {
                return; // Normal shutdown: close() closed the listening channel out from under us.
            } catch (IOException e) {
                if (running.get()) {
                    // A single failed accept (fd exhaustion, transient RST) must not kill the listener.
                    LOG.warn("Accept failed: {0}", e.toString());
                }
            } catch (Throwable t) {
                LOG.error("Acceptor loop error", t instanceof Exception e ? e : new RuntimeException(t));
            }
        }
    }

    public int port() {
        return boundPort;
    }

    public String host() {
        return bindHost;
    }

    public boolean isRunning() {
        return running.get();
    }

    public long totalConnections() {
        return connectionCounter.get();
    }

    public long liveConnections() {
        return liveConnections.get();
    }

    /** Epoch millis of the last accept or request. The idle watchdog reaps abandoned sidecars on this. */
    public long lastActivityMillis() {
        return lastActivityMillis.get();
    }

    /**
     * Stops accepting and terminates live sessions. Closing the listening channel is what wakes the
     * acceptor out of its blocking {@code accept()}; there is no poll loop to signal.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            ServerSocketChannel channel = serverChannel;
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            LOG.warn("Failed to close listening socket: {0}", e.toString());
        }
        Thread acceptor = acceptorThread;
        if (acceptor != null) {
            acceptor.interrupt();
        }
        ExecutorService executor = sessionExecutor;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("{0} session(s) did not terminate within 5s.", liveConnections.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("FastCache sidecar stopped after serving {0} connection(s).", connectionCounter.get());
    }
}
