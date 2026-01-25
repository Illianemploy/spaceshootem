package com.shooter.space

/**
 * Telegraph entity (Phase 4 visual telegraphing).
 * Primitives only, fixed-size pool, no allocations during update.
 * Unused slots have remainingMs == 0.
 * Purely visual, does not affect gameplay logic.
 */
data class Telegraph(
    var x: Float,
    var y: Float,
    var remainingMs: Long,
    var radius: Float,
    var style: Int  // reserved for different telegraph types (0 for now)
)
