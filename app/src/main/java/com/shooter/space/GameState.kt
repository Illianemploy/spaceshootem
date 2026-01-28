package com.shooter.space

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.random.Random

/**
 * Lightweight game state containing only primitives and slow-changing UI state.
 * Entity lists (enemies, bullets, stars, etc.) are accessed directly from GameEngine
 * to eliminate per-frame allocation/copying.
 *
 * UI reads entities from GameEngine stable references, not copied snapshots.
 */
data class GameState(
    // === GAME METRICS (primitives only) ===
    val score: Int,
    val earnedCurrency: Int,
    val currentMultiplier: Double,
    val playerHealth: Int,
    val maxPlayerHealth: Int,
    val isAlive: Boolean,
    val playerInvulnMs: Long,  // Player invulnerability timer (Phase 2B visual feedback)

    // === TIMING ===
    val gameTime: Float, // Total elapsed time (for compatibility)
    val survivedMilliseconds: Long, // Time excluding paused intervals

    // === SHOP STATE ===
    val isShopOpen: Boolean,
    val shopIndex: Int,
    val purchasesThisWindow: Int,
    val maxPurchasesPerWindow: Int,
    val playerInsideShop: Boolean,
    val shopItems: List<ShopItem>, // Generated items for current shop

    // === UPGRADES & MODIFIERS ===
    val playerUpgrades: PlayerUpgrades,
    val activeRisk: RiskState?,
    val permanentMultiplierBonus: Double,
    val weaponStats: WeaponStats,
    val activePowerUpEffects: Map<PowerUpType, ActiveEffect>, // From PowerUpSystem

    // === DIFFICULTY ===
    val difficultyLevel: Int,

    // === RENDERING ASSETS (cached references) ===
    val powerUpSprite: ImageBitmap?,
    val spaceCenterSprite: ImageBitmap?,

    // === BACKGROUND STATE ===
    val backgroundScrollOffset: Float, // From ParallaxBackgroundManager

    // === INSTANT RESTART VISUAL CUE ===
    val restartFlashAlpha: Float = 0f  // 0.0 = no flash, 1.0 = full white overlay
) {
    companion object {
        /**
         * Helper to generate random float in range [min, max]
         */
        private fun randFloat(min: Float, max: Float): Float =
            Random.nextFloat() * (max - min) + min

        /**
         * Factory method for initial game state (primitives only).
         * Entity initialization happens in GameEngine.
         */
        fun initial(
            powerUpSprite: ImageBitmap?,
            spaceCenterSprite: ImageBitmap?
        ): GameState {
            return GameState(
                score = 0,
                earnedCurrency = 0,
                currentMultiplier = 1.0,
                playerHealth = 3,
                maxPlayerHealth = 3,
                isAlive = true,
                playerInvulnMs = 0L,
                gameTime = 0f,
                survivedMilliseconds = 0L,
                isShopOpen = false,
                shopIndex = 0,
                purchasesThisWindow = 0,
                maxPurchasesPerWindow = 3,
                playerInsideShop = false,
                shopItems = emptyList(),
                playerUpgrades = PlayerUpgrades(),
                activeRisk = null,
                permanentMultiplierBonus = 0.0,
                weaponStats = WeaponStats(),
                activePowerUpEffects = emptyMap(),
                difficultyLevel = 0,
                powerUpSprite = powerUpSprite,
                spaceCenterSprite = spaceCenterSprite,
                backgroundScrollOffset = 0f,
                restartFlashAlpha = 0f
            )
        }
    }
}
