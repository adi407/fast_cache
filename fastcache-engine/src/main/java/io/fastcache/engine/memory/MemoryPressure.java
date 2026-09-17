package io.fastcache.engine.memory;

/** Immutable snapshot of the engine memory situation, surfaced through STATS and Spring actuator. */
public record MemoryPressure(
        long reservedBytes,
        long budgetBytes,
        double budgetRatio,
        double physicalRatio,
        double effectiveRatio,
        boolean rejecting) {

    public MemoryPressure {
        if (budgetBytes <= 0) {
            throw new IllegalArgumentException("budgetBytes must be positive");
        }
    }
}
