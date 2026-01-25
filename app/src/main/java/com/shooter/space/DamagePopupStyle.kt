package com.shooter.space

/**
 * Damage popup style bitflags (Phase 2D).
 * Used by both GameEngine (spawn) and MainActivity (render).
 */
const val POPUP_STYLE_CRIT = 1 shl 0  // 0x01
const val POPUP_STYLE_FIRE = 1 shl 1  // 0x02
const val POPUP_STYLE_ICE  = 1 shl 2  // 0x04
