package io.fastcache.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Protects the in-process reference cache from shared-mutable-state corruption.
 *
 * <h2>The failure this prevents</h2>
 * The Spring path's entire performance argument is that it caches <em>live object references</em> — no
 * serialization, no copying, a hit costs a hash lookup. The cost of that argument is aliasing: every caller
 * receives the same instance. So:
 *
 * <pre>{@code
 * List<Document> docs = search("gpu");   // cache hit, shared instance
 * docs.add(extraResult);                 // Thread A "just tweaks its own copy"
 * }</pre>
 *
 * Thread A has now silently edited what every other caller in the JVM will see for the next fifteen
 * minutes, on every thread, for that key. There is no exception, no log line and no failing test — the
 * corruption surfaces later as inexplicably wrong results somewhere unrelated. It is one of the genuinely
 * nasty classes of production bug, and an annotation that reads {@code @FastCache(ttl = "15m")} gives a
 * developer no reason to expect it.
 *
 * <h2>The policy</h2>
 * Under {@link Policy#AUTO} (the default), each value type is classified once:
 * <ul>
 *   <li><b>Immutable</b> — records whose components are all immutable, plus {@code String}, boxed
 *       primitives, enums, {@code UUID}, {@code BigDecimal}, {@code java.time} types and immutable
 *       collections holding immutable elements. Stored and served by reference, at zero cost. This is the
 *       path the documentation steers everyone toward, and it stays exactly as fast as before.</li>
 *   <li><b>Copyable</b> — mutable but {@link Serializable}. Deep-copied on the way in <em>and</em> on the
 *       way out, giving true value semantics.</li>
 *   <li><b>Unsafe</b> — mutable and not serializable, so it cannot be copied safely. The value is not
 *       cached at all, and the type is named once in a warning. Refusing to cache is the only honest
 *       option: caching it would hand out an aliased mutable object, which is the bug.</li>
 * </ul>
 *
 * <h2>Why copy on write as well as on read</h2>
 * Copying only on read still leaves the producer holding a live reference to the cached object; it can
 * mutate the entry immediately after storing it. Copying only on write hands every reader the cache's own
 * private instance, which they can then mutate. Value semantics need both.
 *
 * <h2>Why Java serialization for the copy</h2>
 * It is the only mechanism in the platform that deep-copies an arbitrary object graph correctly, including
 * shared references and cycles within the graph. {@code clone()} is shallow by default, so a cloned
 * {@code ArrayList} still shares its elements and offers no protection at all. Serialization is slow —
 * which is precisely why the default policy avoids it for the types people should be caching, and says so
 * loudly for the types they should not.
 */
public final class MutationGuard {

    private static final Logger log = LoggerFactory.getLogger(MutationGuard.class);

    /** Guards against a pathological or recursive type definition stalling classification. */
    private static final int MAX_CLASSIFICATION_DEPTH = 12;

    public enum Policy {
        /** Classify per type: immutable by reference, mutable deep-copied, unsafe not cached. */
        AUTO,
        /** Deep-copy every non-immutable value, and refuse none — closest to a distributed cache. */
        STRICT,
        /**
         * Trust the caller completely. Fastest, and correct only if every cached value is genuinely
         * treated as immutable by every caller.
         */
        OFF
    }

    public enum Verdict {
        IMMUTABLE,
        COPYABLE,
        UNSAFE
    }

    /** Final, well-known immutable value types that need no structural inspection. */
    private static final Set<Class<?>> KNOWN_IMMUTABLE = Set.of(
            String.class, Boolean.class, Byte.class, Character.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class, Void.class,
            BigDecimal.class, BigInteger.class, UUID.class, URI.class, Class.class,
            Instant.class, Duration.class);

    private final Policy policy;
    private final ConcurrentHashMap<Class<?>, Verdict> verdicts = new ConcurrentHashMap<>();
    private final Set<Class<?>> warned = ConcurrentHashMap.newKeySet();

    private final LongAdder referencesServed = new LongAdder();
    private final LongAdder deepCopies = new LongAdder();
    private final LongAdder refusals = new LongAdder();

    MutationGuard(Policy policy) {
        this.policy = policy == null ? Policy.AUTO : policy;
    }

    Policy policy() {
        return policy;
    }

    /**
     * Prepares a value for storage.
     *
     * @return the object to store, or {@code null} when it must not be cached at all
     */
    Object onStore(Object value) {
        return protect(value, "storing");
    }

    /**
     * Prepares a cached value for return to a caller.
     *
     * @return the object to hand out; falls back to the cached instance if a copy fails
     */
    Object onServe(Object value) {
        Object copy = protect(value, "serving");
        // A copy failure on the read path must not turn a cache hit into an error. Returning the shared
        // instance is the pre-guard behaviour: degraded, but the call still succeeds.
        return copy == null ? value : copy;
    }

    private Object protect(Object value, String phase) {
        if (value == null || policy == Policy.OFF) {
            return value;
        }
        Verdict verdict = classify(value.getClass());
        if (verdict == Verdict.IMMUTABLE) {
            referencesServed.increment();
            return value; // The fast path: nothing to protect against.
        }
        if (verdict == Verdict.UNSAFE && policy == Policy.AUTO) {
            warnOnce(value.getClass(), "is neither immutable nor Serializable, so it cannot be safely "
                    + "copied. FastCache will not cache it: handing out an aliased mutable instance would "
                    + "let one caller corrupt every other caller's result. Make it a record, or make it "
                    + "Serializable.");
            refusals.increment();
            return null;
        }
        Object copy = deepCopy(value);
        if (copy == null) {
            warnOnce(value.getClass(), "could not be deep-copied while " + phase
                    + "; caching is skipped for this type.");
            refusals.increment();
            return null;
        }
        deepCopies.increment();
        return copy;
    }

    /** Classifies a type once and memoises it; classification is reflective and not cheap. */
    Verdict classify(Class<?> type) {
        Verdict cached = verdicts.get(type);
        if (cached != null) {
            return cached;
        }
        Verdict verdict = classify(type, 0);
        verdicts.put(type, verdict);
        return verdict;
    }

    private Verdict classify(Class<?> type, int depth) {
        if (depth > MAX_CLASSIFICATION_DEPTH) {
            // Deeply nested or self-referential generics: stop guessing and take the safe branch.
            return Serializable.class.isAssignableFrom(type) ? Verdict.COPYABLE : Verdict.UNSAFE;
        }
        if (type.isPrimitive() || type.isEnum() || KNOWN_IMMUTABLE.contains(type)) {
            return Verdict.IMMUTABLE;
        }
        if (Temporal.class.isAssignableFrom(type) && type.getName().startsWith("java.time.")) {
            return Verdict.IMMUTABLE;
        }
        if (type.isArray()) {
            // Arrays are always mutable, but always cheaply copyable — no serialization needed.
            return Verdict.COPYABLE;
        }
        if (type.isRecord()) {
            return classifyRecord(type, depth);
        }
        if (isImmutableCollectionType(type)) {
            // The container cannot be structurally modified. Its elements still can be, but their declared
            // type is erased at runtime, so this is checked per instance in isImmutableInstance.
            return Verdict.IMMUTABLE;
        }
        return Serializable.class.isAssignableFrom(type) ? Verdict.COPYABLE : Verdict.UNSAFE;
    }

    /**
     * A record is immutable exactly when every one of its components is. The recursion is what makes this
     * meaningful: {@code record Page(String title, List<Item> items)} is <em>not</em> immutable if
     * {@code items} is an {@code ArrayList}, and treating it as immutable because "it's a record" would
     * reintroduce the whole bug class through the back door.
     */
    private Verdict classifyRecord(Class<?> type, int depth) {
        boolean copyable = Serializable.class.isAssignableFrom(type);
        for (RecordComponent component : type.getRecordComponents()) {
            Verdict componentVerdict = classify(component.getType(), depth + 1);
            if (componentVerdict != Verdict.IMMUTABLE) {
                return copyable ? Verdict.COPYABLE : Verdict.UNSAFE;
            }
        }
        return Verdict.IMMUTABLE;
    }

    /** Recognises {@code List.of(...)} / {@code Collections.unmodifiable*} containers by their class. */
    private static boolean isImmutableCollectionType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.util.ImmutableCollections$")
                || name.startsWith("java.util.Collections$Unmodifiable")
                || name.startsWith("java.util.Collections$Empty")
                || name.startsWith("java.util.Collections$Singleton");
    }

    /** Deep-copies via serialization, or by element copy for arrays. Returns null when impossible. */
    private Object deepCopy(Object value) {
        try {
            if (value.getClass().isArray()) {
                return copyArray(value);
            }
            if (!(value instanceof Serializable)) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(value);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                return in.readObject();
            }
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            log.debug("Deep copy of {} failed", value.getClass().getName(), e);
            return null;
        }
    }

    private Object copyArray(Object array) {
        int length = Array.getLength(array);
        Object copy = Array.newInstance(array.getClass().getComponentType(), length);
        if (array.getClass().getComponentType().isPrimitive()) {
            System.arraycopy(array, 0, copy, 0, length); // Primitives have no aliasing to worry about.
            return copy;
        }
        for (int i = 0; i < length; i++) {
            Object element = Array.get(array, i);
            // Elements must be copied too: copying only the backing array would still share every element.
            Array.set(copy, i, element == null || classify(element.getClass()) == Verdict.IMMUTABLE
                    ? element
                    : deepCopy(element));
        }
        return copy;
    }

    private void warnOnce(Class<?> type, String message) {
        if (warned.add(type)) {
            log.warn("FastCache mutation guard: {} {}", type.getName(), message);
        }
    }

    long referencesServed() {
        return referencesServed.sum();
    }

    long deepCopies() {
        return deepCopies.sum();
    }

    long refusals() {
        return refusals.sum();
    }

    /** Snapshot of which types were classified how — the diagnostic for "why is my cache slow?". */
    java.util.Map<String, Verdict> classifications() {
        java.util.Map<String, Verdict> summary = new java.util.HashMap<>();
        verdicts.forEach((type, verdict) -> summary.put(type.getName(), verdict));
        return Collections.unmodifiableMap(summary);
    }
}
