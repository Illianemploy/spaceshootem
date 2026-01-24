package com.shooter.space

import androidx.compose.ui.geometry.Offset

/**
 * Sealed class representing all possible input events in the game.
 * UI layer sends these to GameEngine; no direct state mutation allowed.
 */
sealed class InputEvent {
    /**
     * Player drag/move input.
     * @param dragAmount Delta movement from last frame
     * @param absolutePosition Current touch position on screen
     */
    data class Move(
        val dragAmount: Offset,
        val absolutePosition: Offset
    ) : InputEvent()

    /**
     * Toggle pause state (not currently used - pause is automatic via shop)
     */
    data object PauseToggle : InputEvent()

    /**
     * Shop interaction events
     */
    sealed class ShopEvent : InputEvent() {
        data object Open : ShopEvent()
        data object Close : ShopEvent()
        data class Purchase(val itemId: String) : ShopEvent()
    }

    /**
     * Debug events
     */
    data object DebugToggle : InputEvent()
}
