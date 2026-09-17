package io.fastcache.engine.util;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Thin wrapper over {@link System.Logger} so the engine JAR keeps its zero-dependency promise
 * (no SLF4J, no Logback) while still routing cleanly into an application's logging backend when
 * one is present on the module path.
 */
public final class FastCacheLog {

    private final Logger delegate;

    private FastCacheLog(Logger delegate) {
        this.delegate = delegate;
    }

    public static FastCacheLog of(Class<?> owner) {
        return new FastCacheLog(System.getLogger(owner.getName()));
    }

    public void debug(String message, Object... args) {
        delegate.log(Level.DEBUG, message, args);
    }

    public void info(String message, Object... args) {
        delegate.log(Level.INFO, message, args);
    }

    public void warn(String message, Object... args) {
        delegate.log(Level.WARNING, message, args);
    }

    public void error(String message, Throwable t) {
        delegate.log(Level.ERROR, message, t);
    }
}
