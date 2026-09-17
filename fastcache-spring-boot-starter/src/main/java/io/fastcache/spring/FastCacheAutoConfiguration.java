package io.fastcache.spring;

import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.net.FastCacheServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.io.IOException;

/**
 * The whole of "zero-ops". Putting this starter on the classpath registers the sharded engine, the AOP
 * aspect and (optionally) the sidecar listener. An application adds a dependency and writes
 * {@code @FastCache(ttl = "15m")}; nothing else.
 *
 * <p>Every bean is {@link ConditionalOnMissingBean}, so an application that wants to hand-build its engine
 * can define its own {@code ShardedStorageEngine} and this configuration steps aside entirely.
 *
 * <p>{@code destroyMethod = "close"} on the engine is load-bearing, not tidiness: the engine holds native
 * memory the GC cannot reclaim. Without it, a Spring context restart inside a running JVM (common in
 * devtools and in tests) leaks the entire off-heap budget every cycle.
 */
@AutoConfiguration
@ConditionalOnClass(ShardedStorageEngine.class)
@ConditionalOnProperty(prefix = "fastcache", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FastCacheProperties.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class FastCacheAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FastCacheAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ShardedStorageEngine fastCacheStorageEngine(FastCacheProperties properties) {
        ShardedStorageEngine engine = new ShardedStorageEngine(properties.toEngineConfig());
        log.info("FastCache auto-configured with {} shards (default TTL {})",
                properties.getShards(), properties.getDefaultTtl());
        return engine;
    }

    @Bean
    @ConditionalOnMissingBean
    public FastCacheAspect fastCacheAspect(ShardedStorageEngine engine, FastCacheProperties properties) {
        return new FastCacheAspect(engine, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public FastCacheOperations fastCacheOperations(ShardedStorageEngine engine, FastCacheProperties properties) {
        return new FastCacheOperations(engine, properties);
    }

    /**
     * Exposes this JVM's cache over the sidecar protocol, so co-located Python processes can share it
     * instead of booting a second engine. Opt-in via {@code fastcache.server.enabled=true}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "fastcache.server", name = "enabled", havingValue = "true")
    public FastCacheServerLifecycle fastCacheServerLifecycle(ShardedStorageEngine engine,
                                                            FastCacheProperties properties) {
        return new FastCacheServerLifecycle(engine, properties);
    }

    /**
     * Binds the listener to the Spring lifecycle. Implemented as a bean rather than started inline so a
     * bind failure surfaces as a context startup failure with a real stack trace, instead of a swallowed
     * exception during bean construction.
     */
    public static class FastCacheServerLifecycle implements InitializingBean, DisposableBean {

        private final ShardedStorageEngine engine;
        private final FastCacheProperties properties;
        private FastCacheServer server;

        FastCacheServerLifecycle(ShardedStorageEngine engine, FastCacheProperties properties) {
            this.engine = engine;
            this.properties = properties;
        }

        @Override
        public void afterPropertiesSet() throws IOException {
            FastCacheProperties.Server config = properties.getServer();
            this.server = new FastCacheServer(engine, config.getHost(), config.getPort());
            int port = server.start();
            log.info("FastCache sidecar listener bound to {}:{}", config.getHost(), port);
        }

        public int port() {
            return server == null ? -1 : server.port();
        }

        @Override
        public void destroy() {
            if (server != null) {
                server.close();
            }
        }
    }
}
