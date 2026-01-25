package com.shooter.space

/**
 * Boss entity (Phase 3 framework).
 * Primitives only, no allocations during update.
 * Phase transitions triggered by HP thresholds.
 */
data class Boss(
    var x: Float,
    var y: Float,
    val size: Float,
    var combat: CombatStats,  // non-null, reuses existing combat system
    var phaseIndex: Int = 0,  // current phase (0-based)
    val phaseThresholds: FloatArray  // HP % thresholds for phase transitions (descending order)
) {
    // Helper: check if boss is alive
    val isAlive: Boolean get() = combat.hp > 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Boss

        if (x != other.x) return false
        if (y != other.y) return false
        if (size != other.size) return false
        if (combat != other.combat) return false
        if (phaseIndex != other.phaseIndex) return false
        if (!phaseThresholds.contentEquals(other.phaseThresholds)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + combat.hashCode()
        result = 31 * result + phaseIndex
        result = 31 * result + phaseThresholds.contentHashCode()
        return result
    }
}
