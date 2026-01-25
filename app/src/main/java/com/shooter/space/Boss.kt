package com.shooter.space

/**
 * Boss entity (Phase 3 framework).
 * Primitives only + FloatArray thresholds, no allocations during update.
 * Phase transitions triggered by HP thresholds.
 * Phase 4: Attack state (primitives only).
 */
class Boss(
    var x: Float,
    var y: Float,
    val size: Float,
    var combat: CombatStats,  // non-null, reuses existing combat system
    var phaseIndex: Int = 0,  // current phase (0-based)
    val phaseThresholds: FloatArray,  // HP % thresholds for phase transitions (descending order)
    // Phase 4: Attack state (primitives only)
    var attackCooldownMs: Long = 2000L,  // Time between attacks
    var attackTimerMs: Long = 0L,        // Countdown timer
    var spreadCount: Int = 3             // Number of bullets in spread pattern (phase-dependent)
) {
    // Helper: check if boss is alive
    val isAlive: Boolean get() = combat.hp > 0
}
