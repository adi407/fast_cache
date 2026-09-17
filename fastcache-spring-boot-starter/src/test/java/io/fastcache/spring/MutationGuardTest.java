package io.fastcache.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Protection against shared-mutable-state corruption on the live-reference Spring path.
 *
 * <p>The bug this prevents has no exception and no stack trace: one caller mutates a cached collection and
 * every other caller in the JVM silently sees the edit for the rest of the TTL. These tests assert the two
 * halves of value semantics — that immutable types stay zero-cost, and that mutable ones cannot leak an
 * aliased reference in either direction.
 */
class MutationGuardTest {

    // --- value shapes under test ---------------------------------------------------------------------
    record Immutable(String id, int size) { }

    record RecordWithMutableComponent(String id, List<String> items) implements Serializable { }

    record NestedImmutable(Immutable inner, UUID id) { }

    static final class MutableBean implements Serializable {
        List<String> items = new ArrayList<>(List.of("original"));
    }

    static final class NotSerializable {
        List<String> items = new ArrayList<>(List.of("original"));
    }

    // --- classification -------------------------------------------------------------------------------

    @Test
    @DisplayName("records of immutable components are immutable")
    void recordsAreImmutable() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        assertEquals(MutationGuard.Verdict.IMMUTABLE, guard.classify(Immutable.class));
        assertEquals(MutationGuard.Verdict.IMMUTABLE, guard.classify(NestedImmutable.class));
    }

    @Test
    @DisplayName("a record with a mutable component is NOT immutable")
    void recordsAreNotAutomaticallySafe() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        // The whole point: "it's a record" is not proof of immutability. A record wrapping an ArrayList
        // is exactly as dangerous as the ArrayList, and trusting the record shape would reintroduce the
        // entire bug class through the back door.
        assertEquals(MutationGuard.Verdict.COPYABLE, guard.classify(RecordWithMutableComponent.class));
    }

    @Test
    @DisplayName("well-known value types are immutable")
    void knownValueTypes() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        for (Class<?> type : new Class<?>[]{String.class, Integer.class, Boolean.class, UUID.class,
                BigDecimal.class, Instant.class, java.time.LocalDate.class}) {
            assertEquals(MutationGuard.Verdict.IMMUTABLE, guard.classify(type), type.getName());
        }
    }

    @Test
    @DisplayName("mutable Serializable types are copyable; non-serializable ones are unsafe")
    void mutableClassification() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        assertEquals(MutationGuard.Verdict.COPYABLE, guard.classify(ArrayList.class));
        assertEquals(MutationGuard.Verdict.COPYABLE, guard.classify(MutableBean.class));
        assertEquals(MutationGuard.Verdict.UNSAFE, guard.classify(NotSerializable.class));
    }

    // --- behaviour ------------------------------------------------------------------------------------

    @Test
    @DisplayName("immutable values are served by reference at zero cost")
    void immutableIsZeroCopy() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        Immutable value = new Immutable("k", 1);

        assertSame(value, guard.onStore(value));
        assertSame(value, guard.onServe(value), "copying an immutable value would be pure waste");
        assertEquals(0, guard.deepCopies());
    }

    @Test
    @DisplayName("a caller cannot corrupt the cache by mutating what it was served")
    void servedValueIsIsolated() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        List<String> cached = new ArrayList<>(List.of("original"));

        @SuppressWarnings("unchecked")
        List<String> served = (List<String>) guard.onServe(cached);
        served.add("MUTATED BY CALLER");

        assertEquals(1, cached.size(), "the cached instance must be untouched by a caller's edit");
        assertNotSame(cached, served);
    }

    @Test
    @DisplayName("a producer cannot corrupt the cache by mutating what it stored")
    void storedValueIsIsolated() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        List<String> producerCopy = new ArrayList<>(List.of("original"));

        @SuppressWarnings("unchecked")
        List<String> stored = (List<String>) guard.onStore(producerCopy);
        producerCopy.add("MUTATED AFTER STORING");

        assertEquals(1, stored.size(),
                "copying only on read would leave the producer holding a live handle on the entry");
    }

    @Test
    @DisplayName("the deep copy is deep, not shallow")
    void copyIsDeep() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        List<List<String>> nested = new ArrayList<>();
        nested.add(new ArrayList<>(List.of("inner")));

        @SuppressWarnings("unchecked")
        List<List<String>> copy = (List<List<String>>) guard.onServe(nested);
        copy.get(0).add("MUTATED");

        assertEquals(1, nested.get(0).size(),
                "clone() would have shared the elements; that is why this uses serialization");
    }

    @Test
    @DisplayName("a record's mutable component is protected too")
    void recordComponentIsProtected() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        RecordWithMutableComponent value =
                new RecordWithMutableComponent("k", new ArrayList<>(List.of("x")));

        RecordWithMutableComponent served = (RecordWithMutableComponent) guard.onServe(value);
        served.items().add("MUTATED");

        assertEquals(1, value.items().size());
    }

    @Test
    @DisplayName("arrays are copied element by element")
    void arraysAreCopied() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);

        String[] strings = {"a", "b"};
        String[] copiedStrings = (String[]) guard.onServe(strings);
        copiedStrings[0] = "MUTATED";
        assertEquals("a", strings[0]);

        byte[] bytes = {1, 2, 3};
        byte[] copiedBytes = (byte[]) guard.onServe(bytes);
        copiedBytes[0] = 99;
        assertEquals(1, bytes[0]);
    }

    @Test
    @DisplayName("an unsafe type is refused rather than aliased")
    void unsafeIsRefused() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);

        assertNull(guard.onStore(new NotSerializable()),
                "handing out an aliased mutable instance is the bug; refusing to cache is the only "
                        + "honest option");
        assertEquals(1, guard.refusals());
    }

    @Test
    @DisplayName("a serve-side copy failure degrades to the shared instance rather than erroring")
    void serveFailsOpen() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        NotSerializable value = new NotSerializable();

        assertSame(value, guard.onServe(value),
                "a copy failure on the read path must not turn a cache hit into an exception");
    }

    @Test
    @DisplayName("OFF trusts the caller completely")
    void policyOff() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.OFF);
        List<String> value = new ArrayList<>(List.of("a"));

        assertSame(value, guard.onStore(value));
        assertSame(value, guard.onServe(value));
        assertEquals(0, guard.deepCopies());
    }

    @Test
    @DisplayName("STRICT copies types AUTO would refuse")
    void policyStrict() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.STRICT);
        // STRICT attempts the copy rather than refusing outright; a non-serializable value still cannot
        // be copied, so it is skipped -- but no type is refused on classification alone.
        assertNull(guard.onStore(new NotSerializable()));
        assertEquals(MutationGuard.Policy.STRICT, guard.policy());
    }

    @Test
    @DisplayName("null passes through untouched")
    void nullIsPassedThrough() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        assertNull(guard.onStore(null));
        assertNull(guard.onServe(null));
    }

    @Test
    @DisplayName("classifications are exposed for diagnosing unexpected copy cost")
    void classificationsAreReported() {
        MutationGuard guard = new MutationGuard(MutationGuard.Policy.AUTO);
        guard.onServe(new Immutable("k", 1));
        guard.onServe(new ArrayList<>(List.of("a")));

        assertEquals(MutationGuard.Verdict.IMMUTABLE,
                guard.classifications().get(Immutable.class.getName()));
        assertEquals(MutationGuard.Verdict.COPYABLE,
                guard.classifications().get(ArrayList.class.getName()));
    }
}
