package io.fastcache.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caches a method's return value in FastCache.
 *
 * <p>The entire configuration surface is this annotation. No XML, no {@code @EnableCaching}, no
 * {@code CacheManager} bean, no cache name registration: dropping the starter on the classpath registers
 * the engine, the aspect and the shard layout, and this is all a developer ever writes.
 *
 * <pre>{@code
 * @FastCache(ttl = "15m")
 * public EmbeddingMatrix embed(String prompt) { ... }
 *
 * @FastCache(ttl = "30s", key = "#tenant.id + ':' + #query", unless = "#result.isEmpty()")
 * public List<Document> search(Tenant tenant, String query) { ... }
 * }</pre>
 *
 * <p><b>Values are stored as live object references.</b> Nothing is serialized to JSON or to bytes on this
 * path &mdash; the returned object graph is parked directly in the shard map. Two consequences that are
 * deliberate, not accidental:
 * <ul>
 *   <li>It is effectively free: a cache hit costs a hash lookup and a reference read, with no
 *       marshalling.</li>
 *   <li>Callers receive <em>the same instance</em>, not a copy. Cache immutable values (records, frozen
 *       collections, DTOs you never mutate). Mutating a cached object mutates what every other caller
 *       sees. This is the same contract as Spring's own local {@code ConcurrentMapCache}.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface FastCache {

    /**
     * Time to live, in the FastCache duration grammar: {@code 500ms}, {@code 30s}, {@code 15m},
     * {@code 2h}, {@code 7d}, or {@code never}. Blank inherits {@code fastcache.default-ttl}.
     */
    String ttl() default "";

    /**
     * SpEL expression for the cache key, evaluated against the method arguments by name
     * ({@code "#userId"}, {@code "#request.tenant + ':' + #request.prompt"}).
     *
     * <p>Empty means auto-generate from the declaring class, method name and arguments. The generated key
     * uses the arguments' {@code toString}, so a parameter whose class does not override {@code toString}
     * contributes its identity hash and will never produce a hit &mdash; supply an explicit key for those.
     *
     * <p><b>Referencing parameters by name requires {@code -parameters}.</b> Spring Boot's Maven and Gradle
     * plugins pass that compiler flag by default, so this normally just works. If your build does not, the
     * aspect detects it at first call, logs a warning naming the method, and <em>disables caching for that
     * method</em> rather than silently evaluating every variable to null &mdash; which would collapse all
     * calls onto one key and return other callers' results. Positional variables ({@code #p0}, {@code #p1})
     * work regardless of compiler flags.
     */
    String key() default "";

    /** Namespace prefix, so two beans with identically-named methods cannot collide. Defaults to the FQCN. */
    String namespace() default "";

    /** SpEL guard evaluated <em>before</em> invocation on the arguments; when false, caching is bypassed. */
    String condition() default "";

    /**
     * SpEL veto evaluated <em>after</em> invocation with {@code #result} bound; when true, the result is
     * returned but not stored. The idiomatic way to keep empty or partial results out of the cache.
     */
    String unless() default "";

    /** Whether a {@code null} return is cached (negative caching). Off by default. */
    boolean cacheNulls() default false;
}
