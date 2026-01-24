package com.shooter.space

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import com.shooter.space.BuildConfig
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * GameEngine owns all mutable game state and exposes stable references for rendering.
 * UI layer observes `state.value` for primitives and reads entity lists directly.
 *
 * CRITICAL: Entity lists (player, enemies, bullets, stars) are accessed via stable
 * references to eliminate per-frame allocation/copying. NO map{} or copy() per frame.
 *
 * Core responsibilities:
 * - Process frame updates via update(dtMs)
 * - Handle input via onInput(event)
 * - Publish primitive state to state.value (NO entity copying)
 * - Maintain game rules, collision detection, spawning, scoring
 */
class GameEngine(
    private val context: Context,
    private val screenWidth: Float,
    private val screenHeight: Float,
    powerUpSprite: ImageBitmap?,
    spaceCenterSprite: ImageBitmap?,
    private val backgroundManager: ParallaxBackgroundManager
) {
    // === MUTABLE INTERNAL STATE ===
    private var player = Player(screenWidth / 2, screenHeight - 150f)
    private val enemies = mutableListOf<Enemy>()
    private val bullets = mutableListOf<Bullet>()
    private val enemyBullets = mutableListOf<EnemyBullet>()
    private val stars = mutableListOf<Star>()
    private var spaceCenter: SpaceCenter? = null

    // === PUBLIC STABLE REFERENCES (for rendering, NO per-frame copy) ===
    // UI reads these directly to avoid allocation. Mutable, but rendering is read-only.
    val playerRef: Player get() = player
    val enemiesRef: List<Enemy> get() = enemies
    val bulletsRef: List<Bullet> get() = bullets
    val enemyBulletsRef: List<EnemyBullet> get() = enemyBullets
    val starsRef: List<Star> get() = stars
    val spaceCenterRef: SpaceCenter? get() = spaceCenter
    val powerUpsRef: List<WorldPowerUp> get() = powerUpSystem.worldPowerUps

    // Game metrics
    private var score = 0
    private var survivalScore = 0  // Score from surviving (time-based)
    private var killScore = 0       // Score from kills (combat-based)
    private var earnedCurrency = 0
    private var currentMultiplier = 1.0
    private var playerHealth = 3
    private val maxPlayerHealth = 3
    private var isAlive = true

    // Timing
    private var gameTime = 0f
    private var gameStartTime = 0L
    private var pausedTime = 0L // Accumulated ms spent in shop
    private var survivedMilliseconds = 0L
    private var lastUpdateTime = 0L

    // Shop state
    private var isShopOpen = false
    private var shopIndex = 0
    private var purchasesThisWindow = 0
    private val maxPurchasesPerWindow = 3
    private var playerInsideShop = false
    private var shopExitTime = 0L
    private var canAutoOpenShop = true
    private var shopItems = emptyList<ShopItem>()

    // Upgrades & risks
    private var playerUpgrades = PlayerUpgrades()
    private var activeRisk: RiskState? = null
    private var permanentMultiplierBonus = 0.0
    private var weaponStats = WeaponStats()

    // Systems
    private val difficultyScaler = DifficultyScaler()
    private val powerUpSystem = PowerUpSystem()

    // Debug stress testing (primitives only, debug builds only)
    private var debugConfig = DebugConfig()

    // Cached sprites
    private val powerUpSprite: ImageBitmap? = powerUpSprite
    private val spaceCenterSprite: ImageBitmap? = spaceCenterSprite

    // Frame tracking
    private var lastFireTime = 0L
    private var lastSpawnTime = 0L
    private var lastPowerUpSpawnTime = 0L
    private var lastDifficultyUpdate = 0L
    private var cachedSpawnInterval = 1000L

    // Enemy bullet cap (Phase 2)
    private val MAX_ENEMY_BULLETS = 200

    // Shop constants
    private val shopRespawnSeconds = 30

    // Combat constants (Phase 1)
    private val PLAYER_IFRAMES_MS = 300L  // Player invulnerability duration

    // Combat state
    private var playerInvulnMs = 0L  // Player invulnerability timer

    // === PUBLISHED STATE (primitives only, NO entity copying) ===
    val state = mutableStateOf(
        GameState.initial(powerUpSprite, spaceCenterSprite)
    )

    /**
     * Get current spawn interval for debug display.
     */
    fun getSpawnInterval(): Long = cachedSpawnInterval

    /**
     * Helper to generate random float in range [min, max]
     */
    private fun randFloat(min: Float, max: Float): Float =
        Random.nextFloat() * (max - min) + min

    init {
        // Initialize stars
        repeat(100) {
            stars.add(
                Star(
                    x = Random.nextInt(0, screenWidth.toInt() + 1).toFloat(),
                    y = Random.nextInt(0, screenHeight.toInt() + 1).toFloat(),
                    size = randFloat(1f, 3f),
                    speed = randFloat(2f, 5f),
                    layer = Random.nextInt(0, 3)
                )
            )
        }
        publishSnapshot()
    }

    /**
     * Main update loop - called every frame with delta time in milliseconds.
     * Currently maintains 16ms fixed timestep for behavior compatibility.
     *
     * @param dtMs Delta time in milliseconds (will be real delta after Checkpoint 2)
     */
    fun update(dtMs: Long) {
        if (!isAlive) return

        val currentTime = System.currentTimeMillis()
        if (gameStartTime == 0L) {
            gameStartTime = currentTime
            lastUpdateTime = currentTime
        }

        // Calculate survived time (excluding shop pauses)
        survivedMilliseconds = (currentTime - gameStartTime) - pausedTime
        gameTime = survivedMilliseconds / 1000f

        // === SPACE CENTER & SHOP LOGIC ===
        updateSpaceCenter(currentTime, dtMs)

        // If shop is open, pause game updates but track paused time
        if (isShopOpen) {
            pausedTime += dtMs
            powerUpSystem.update(0L) // Freeze power-up timers
            publishSnapshot()
            return
        }

        // === ACTIVE GAME UPDATES (only when shop closed) ===

        // Update multiplier
        currentMultiplier = calculateMultiplier(survivedMilliseconds) +
                           playerUpgrades.scoreBoostPercent / 100.0 +
                           permanentMultiplierBonus

        // Update star parallax
        updateStars(dtMs)

        // Tick combat timers (invulnerability, etc.)
        tickCombatTimers(dtMs)

        // Apply player velocity decay (frame-independent)
        val decayFactor = 0.85.pow((dtMs / 16.0)).toFloat()
        player.velocityX *= decayFactor
        player.velocityY *= decayFactor

        // Auto-fire bullets
        updateWeaponFiring(currentTime)

        // Update bullets
        updateBullets(dtMs)

        // Update difficulty (every 500ms)
        if (currentTime - lastDifficultyUpdate > 500) {
            difficultyScaler.update(survivedMilliseconds, score)
            cachedSpawnInterval = difficultyScaler.getSpawnInterval()
            lastDifficultyUpdate = currentTime
        }

        // Spawn enemies (with debug stress multiplier if enabled)
        if (currentTime - lastSpawnTime > cachedSpawnInterval) {
            val spawnCount = if (debugConfig.enabled) debugConfig.spawnMultiplier else 1
            // Respect entity caps: spawn up to multiplier but don't exceed reasonable limits
            val actualCount = min(spawnCount, 50 - enemies.size).coerceAtLeast(1)
            for (spawnIdx in 0 until actualCount) {  // Indexed loop, no iterator
                spawnEnemy()
            }
            lastSpawnTime = currentTime
        }

        // Handle debug burst spawn (one-shot, then reset)
        if (debugConfig.spawnBurst > 0) {
            val burstCount = min(debugConfig.spawnBurst, 50 - enemies.size).coerceAtLeast(0)
            for (burstIdx in 0 until burstCount) {  // Indexed loop, no iterator
                spawnEnemy()
            }
            // Reset burst counter after consuming
            debugConfig = debugConfig.copy(spawnBurst = 0)
        }

        // Spawn power-ups (test: every 10s)
        if (currentTime - lastPowerUpSpawnTime > 10000) {
            spawnPowerUp()
            lastPowerUpSpawnTime = currentTime
        }

        // Update power-up system
        powerUpSystem.update(dtMs)

        // Check power-up pickups
        checkPowerUpCollisions()

        // Update enemies
        updateEnemies(dtMs)

        // Update enemy bullets (Phase 2)
        updateEnemyBullets(dtMs)

        // Check bullet-enemy collisions
        checkBulletEnemyCollisions()

        // Check enemy bullet-player collisions (Phase 2)
        checkEnemyBulletPlayerCollisions(currentTime)

        // Check player-enemy collisions (contact damage)
        checkPlayerEnemyCollisions()

        // Award score and currency based on survival time
        awardScoreAndCurrency(currentTime)

        // Update active risk challenges
        updateRiskChallenges(dtMs)

        // Update background
        backgroundManager.update(dtMs / 1000f)

        lastUpdateTime = currentTime
        publishSnapshot()
    }

    /**
     * Handle input events from UI layer.
     */
    fun onInput(event: InputEvent) {
        when (event) {
            is InputEvent.Move -> {
                player.x = event.absolutePosition.x.coerceIn(0f, screenWidth)
                player.y = event.absolutePosition.y.coerceIn(0f, screenHeight)
                player.velocityX = event.dragAmount.x
                player.velocityY = event.dragAmount.y
            }
            is InputEvent.PauseToggle -> {
                // Not used - pause handled automatically by shop
            }
            is InputEvent.ShopEvent.Purchase -> {
                handlePurchase(event.itemId)
            }
            is InputEvent.ShopEvent.Close -> {
                isShopOpen = false
            }
            is InputEvent.ShopEvent.Open -> {
                isShopOpen = true
            }
            else -> {} // Other events handled elsewhere
        }
        publishSnapshot()
    }

    /**
     * Convenience method for handling player drag input.
     * Called by UI layer when player drags on screen.
     */
    fun handlePlayerDrag(dx: Float, dy: Float) {
        val newX = (player.x + dx).coerceIn(0f, screenWidth)
        val newY = (player.y + dy).coerceIn(0f, screenHeight)

        onInput(
            InputEvent.Move(
                dragAmount = Offset(dx, dy),
                absolutePosition = Offset(newX, newY)
            )
        )
    }

    /**
     * Convenience method for purchasing shop items.
     * Called by UI layer when player clicks purchase button.
     */
    fun purchaseShopItem(item: ShopItem) {
        onInput(InputEvent.ShopEvent.Purchase(item.id))
    }

    /**
     * Convenience method for closing shop.
     * Called by UI layer when player clicks close button.
     */
    fun closeShop() {
        onInput(InputEvent.ShopEvent.Close)
    }

    /**
     * Restart the game with fresh state.
     */
    fun restart() {
        player = Player(screenWidth / 2, screenHeight - 150f)
        enemies.clear()
        bullets.clear()
        stars.clear()
        spaceCenter = null

        score = 0
        earnedCurrency = 0
        currentMultiplier = 1.0
        playerHealth = maxPlayerHealth
        isAlive = true

        gameTime = 0f
        gameStartTime = 0L
        pausedTime = 0L
        survivedMilliseconds = 0L

        isShopOpen = false
        shopIndex = 0
        purchasesThisWindow = 0
        playerInsideShop = false
        shopExitTime = 0L
        canAutoOpenShop = true
        shopItems = emptyList()

        playerUpgrades = PlayerUpgrades()
        activeRisk = null
        permanentMultiplierBonus = 0.0
        weaponStats = WeaponStats()

        difficultyScaler.reset()
        powerUpSystem.reset()

        // Reinitialize stars
        repeat(100) {
            stars.add(
                Star(
                    x = Random.nextInt(0, screenWidth.toInt() + 1).toFloat(),
                    y = Random.nextInt(0, screenHeight.toInt() + 1).toFloat(),
                    size = randFloat(1f, 3f),
                    speed = randFloat(2f, 5f),
                    layer = Random.nextInt(0, 3)
                )
            )
        }

        publishSnapshot()
    }

    // === PRIVATE UPDATE METHODS ===

    private fun updateSpaceCenter(currentTime: Long, dtMs: Long) {
        // Spawn space center if needed (30s cooldown after exit)
        if (spaceCenter == null && (shopExitTime == 0L || currentTime - shopExitTime > shopRespawnSeconds * 1000)) {
            spaceCenter = SpaceCenter(
                x = randFloat(screenWidth * 0.3f, screenWidth * 0.7f),
                y = -200f,
                size = 400f,
                rotation = 0f,
                timeAlive = 0f,
                speed = 1.5f,
                isActive = true
            )
        }

        // Update space center position (only when shop closed)
        spaceCenter?.let { center ->
            if (!isShopOpen) {
                center.y += center.speed
                center.rotation += 0.5f
                center.timeAlive += dtMs / 1000f

                // Remove if off-screen
                if (center.y > screenHeight + center.size) {
                    spaceCenter = null
                }
            }
        }

        // Check collision with player to auto-open/close shop
        spaceCenter?.let { center ->
            val collisionRadius = center.size * 0.325f
            val dx = player.x - center.x
            val dy = player.y - center.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            val wasInside = playerInsideShop
            playerInsideShop = distance < collisionRadius

            // Auto-open shop when entering
            if (playerInsideShop && !wasInside && canAutoOpenShop) {
                isShopOpen = true
                shopIndex++
                purchasesThisWindow = 0
                shopItems = generateShopItems(shopIndex, survivedMilliseconds / 1000)
                canAutoOpenShop = false
            }

            // Auto-close shop when exiting
            if (!playerInsideShop && wasInside) {
                isShopOpen = false
                shopExitTime = currentTime
                spaceCenter = null
                canAutoOpenShop = true
            }
        }
    }

    private fun updateStars(dtMs: Long) {
        val speedScale = dtMs / 16f // Scale speed relative to 16ms baseline
        var i = 0
        while (i < stars.size) {
            val star = stars[i]
            star.y += star.speed * speedScale
            if (star.y > screenHeight) {
                star.y = 0f
                star.x = Random.nextInt(0, screenWidth.toInt() + 1).toFloat()
            }
            i++
        }
    }

    private fun updateWeaponFiring(currentTime: Long) {
        val fireRateModifier = 1.0f - playerUpgrades.fireRateLevel * 0.2f
        val effectiveFireRate = (weaponStats.baseFireRate * fireRateModifier).toLong()

        if (currentTime - lastFireTime > effectiveFireRate) {
            val bulletSpeed = 20f * (1f + playerUpgrades.bulletSpeedLevel * 0.3f)

            // Compute projectile count without allocating lists
            val projectileCount = if (powerUpSystem.activeEffects.containsKey(PowerUpType.MULTISHOT)) {
                3  // Multishot gives 3 bullets
            } else {
                1  // Base single bullet
            }

            // Fire bullets
            var i = 0
            while (i < projectileCount) {
                val offsetX = if (projectileCount > 1) {
                    (i - projectileCount / 2) * 20f
                } else 0f

                bullets.add(
                    Bullet(
                        x = player.x + offsetX,
                        y = player.y - player.size / 2,
                        speed = bulletSpeed
                    )
                )
                i++
            }

            lastFireTime = currentTime
        }
    }

    private fun updateBullets(dtMs: Long) {
        val speedScale = dtMs / 16f // Scale speed relative to 16ms baseline

        // Use reverse loop for in-place removal (avoid allocation)
        var i = bullets.size - 1
        while (i >= 0) {
            val bullet = bullets[i]
            bullet.y -= bullet.speed * speedScale

            if (bullet.y < 0) {
                bullets.removeAt(i)
            }
            i--
        }
    }

    private fun spawnEnemy() {
        val sizeTier = randomSizeTier()
        val size = when (sizeTier) {
            SizeTier.SMALL -> randFloat(40f, 60f)
            SizeTier.MEDIUM -> randFloat(70f, 90f)
            SizeTier.LARGE -> randFloat(100f, 130f)
            SizeTier.ELITE -> randFloat(130f, 160f)
        }

        val baseHealth = difficultyScaler.getEnemyHealth()
        val health = if (sizeTier == SizeTier.ELITE) baseHealth + 1 else baseHealth
        val enemyType = Random.nextInt(0, 5)

        // Initialize shoot cooldown based on enemy type
        val initialCooldown = if (enemyType <= 2) {
            Random.nextLong(900L, 1401L)  // type 0-2: slower firing
        } else {
            Random.nextLong(600L, 1101L)  // type 3-4: faster firing
        }

        val enemy = Enemy(
            x = randFloat(size, screenWidth - size),
            y = -size,
            size = size,
            speed = 3f * difficultyScaler.getSpeedMultiplier(),
            type = enemyType,
            timeAlive = 0f,
            rotation = 0f,
            health = health,
            sizeTier = sizeTier,
            behaviorController = EnemyBehaviorController(),
            visualStyle = randomVisualStyle(),
            combat = CombatStats(
                hp = health,
                maxHp = health,
                contactDamage = 1,  // Standard contact damage
                invulnRemainingMs = 0L
            ),
            shootCooldownMs = initialCooldown
        )

        enemies.add(enemy)
    }

    private fun spawnPowerUp() {
        val type = PowerUpType.entries[Random.nextInt(0, PowerUpType.entries.size)]
        val tier = Random.nextInt(0, 5)

        powerUpSystem.worldPowerUps.add(
            WorldPowerUp(
                id = System.currentTimeMillis(),
                type = type,
                x = randFloat(50f, screenWidth - 50f),
                y = randFloat(50f, screenHeight * 0.3f),
                tier = tier,
                alive = true
            )
        )
    }

    private fun checkPowerUpCollisions() {
        val pickupRadius = 30f

        // Use reverse loop for in-place removal (avoid allocation)
        var i = powerUpSystem.worldPowerUps.size - 1
        while (i >= 0) {
            val powerUp = powerUpSystem.worldPowerUps[i]
            val dx = player.x - powerUp.x
            val dy = player.y - powerUp.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            if (distance < pickupRadius) {
                applyPowerUp(powerUp.type, powerUp.tier)
                powerUpSystem.worldPowerUps.removeAt(i)
            }
            i--
        }
    }

    private fun applyPowerUp(type: PowerUpType, tier: Int) {
        when (type) {
            PowerUpType.HEALTH -> {
                playerHealth = min(playerHealth + 1, maxPlayerHealth)
            }
            else -> {
                powerUpSystem.activateEffect(type, tier)
            }
        }
    }

    private fun updateEnemies(dtMs: Long) {
        val dtSec = dtMs / 1000f

        var i = 0
        while (i < enemies.size) {
            val enemy = enemies[i]
            enemy.timeAlive += dtSec
            enemy.rotation += 1f * (dtMs / 16f) // Scale rotation by delta time

            // Update behavior controller with correct combat health
            val c = enemy.combat
            enemy.behaviorController?.update(
                enemy = enemy,
                playerX = player.x,
                playerY = player.y,
                deltaTime = dtSec,
                health = c.hp,
                maxHealth = c.maxHp
            )

            // Apply behavior movement
            val state = enemy.behaviorController?.currentState ?: EnemyState.IDLE
            val speedMultiplier = when (state) {
                EnemyState.APPROACH -> 0.5f
                EnemyState.STRAFE -> 0.7f
                EnemyState.FLEE -> 0.3f
                EnemyState.IDLE -> 1.0f
            }

            val speedScale = dtMs / 16f // Scale speed relative to 16ms baseline
            enemy.y += enemy.speed * speedMultiplier * speedScale

            // Enemy shooting logic (Phase 2)
            enemy.shootCooldownMs = (enemy.shootCooldownMs - dtMs).coerceAtLeast(0L)

            if (enemy.shootCooldownMs == 0L && enemy.y >= 0f && enemy.y <= screenHeight && enemyBullets.size < MAX_ENEMY_BULLETS) {
                // Spawn enemy bullet (respects cap)
                val bulletSpeed = 12f
                val bulletDamage = 1
                val bulletRadius = 6f
                val spawnX = enemy.x
                val spawnY = enemy.y + enemy.size * 0.5f

                val vx: Float
                val vy: Float

                if (enemy.type <= 2) {
                    // STRAIGHT_DOWN
                    vx = 0f
                    vy = bulletSpeed
                } else {
                    // AIM_AT_PLAYER
                    val dx = player.x - spawnX
                    val dy = player.y - spawnY
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist > 0f) {
                        vx = (dx / dist) * bulletSpeed
                        vy = (dy / dist) * bulletSpeed
                    } else {
                        vx = 0f
                        vy = bulletSpeed
                    }
                }

                enemyBullets.add(
                    EnemyBullet(
                        x = spawnX,
                        y = spawnY,
                        vx = vx,
                        vy = vy,
                        damage = bulletDamage,
                        radius = bulletRadius
                    )
                )

                // Reset cooldown
                enemy.shootCooldownMs = if (enemy.type <= 2) {
                    Random.nextLong(900L, 1401L)
                } else {
                    Random.nextLong(600L, 1101L)
                }

                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Combat", "Enemy type ${enemy.type} fired bullet at (${spawnX.toInt()}, ${spawnY.toInt()})")
                }
            }

            i++
        }

        // Remove off-screen enemies (reverse loop for performance)
        var j = enemies.size - 1
        while (j >= 0) {
            if (enemies[j].y > screenHeight + enemies[j].size) {
                enemies.removeAt(j)
            }
            j--
        }
    }

    private fun updateEnemyBullets(dtMs: Long) {
        val speedScale = dtMs / 16f

        // Update positions and remove off-screen bullets
        var i = enemyBullets.size - 1
        while (i >= 0) {
            val bullet = enemyBullets[i]
            bullet.x += bullet.vx * speedScale
            bullet.y += bullet.vy * speedScale

            val margin = 50f
            if (bullet.y > screenHeight + margin || bullet.y < -margin ||
                bullet.x < -margin || bullet.x > screenWidth + margin) {
                enemyBullets.removeAt(i)
            }
            i--
        }
    }

    private fun checkBulletEnemyCollisions() {
        // Use reverse index loops to avoid allocation-heavy Set<> patterns
        // Use squared distance to avoid sqrt() in hot loop

        var bulletIdx = bullets.size - 1
        while (bulletIdx >= 0) {
            val bullet = bullets[bulletIdx]
            var bulletHit = false

            var enemyIdx = enemies.size - 1
            while (enemyIdx >= 0) {
                val enemy = enemies[enemyIdx]

                val dx = bullet.x - enemy.x
                val dy = bullet.y - enemy.y
                val distSq = dx * dx + dy * dy
                val radiusSq = (enemy.size / 2) * (enemy.size / 2)

                if (distSq < radiusSq) {
                    // Apply damage via combat system
                    applyDamageToEnemy(enemy, 1, DamageSource.PLAYER_BULLET)

                    // Remove bullet (already hit)
                    bullets.removeAt(bulletIdx)
                    bulletHit = true

                    // Remove enemy if dead
                    if (enemy.combat.hp == 0) {
                        enemies.removeAt(enemyIdx)
                    }

                    break  // Bullet can only hit one enemy
                }

                enemyIdx--
            }

            if (!bulletHit) {
                bulletIdx--
            }
        }
    }

    private fun checkPlayerEnemyCollisions() {
        val currentTime = System.currentTimeMillis()

        // Use reverse index loop to allow safe removal during iteration
        // Use squared distance to avoid sqrt() in hot loop
        var i = enemies.size - 1
        while (i >= 0) {
            val enemy = enemies[i]

            val dx = player.x - enemy.x
            val dy = player.y - enemy.y
            val distSq = dx * dx + dy * dy
            val collisionRadius = (player.size + enemy.size) / 2
            val radiusSq = collisionRadius * collisionRadius

            if (distSq < radiusSq) {
                // Get contact damage from enemy's combat stats
                val contactDamage = enemy.combat.contactDamage

                // Apply damage to player via combat system (respects i-frames)
                val damageApplied = applyDamageToPlayer(contactDamage, DamageSource.CONTACT, currentTime)

                // Remove enemy on contact (regardless of i-frames)
                enemies.removeAt(i)

                // Only break if damage was applied (prevents multi-hit during i-frames)
                if (damageApplied) {
                    break
                }
            }

            i--
        }
    }

    private fun checkEnemyBulletPlayerCollisions(nowMs: Long) {
        // Use reverse index loop for safe removal
        // Use squared distance to avoid sqrt() in hot loop
        val playerRadius = player.size * 0.5f

        var i = enemyBullets.size - 1
        while (i >= 0) {
            val bullet = enemyBullets[i]

            val dx = player.x - bullet.x
            val dy = player.y - bullet.y
            val distSq = dx * dx + dy * dy
            val collisionRadius = playerRadius + bullet.radius
            val radiusSq = collisionRadius * collisionRadius

            if (distSq < radiusSq) {
                // Apply damage to player (respects i-frames)
                val damageApplied = applyDamageToPlayer(bullet.damage, DamageSource.ENEMY_BULLET, nowMs)

                // Remove bullet regardless of i-frames result (bullet consumed on contact)
                enemyBullets.removeAt(i)

                if (BuildConfig.DEBUG && damageApplied) {
                    android.util.Log.d("Combat", "Player hit by enemy bullet for ${bullet.damage} damage")
                }
            }

            i--
        }
    }

    // ============================================================================
    // COMBAT SYSTEM (Phase 1)
    // ============================================================================

    /**
     * Tick down combat timers (invulnerability, etc.).
     * Called once per frame, NO allocations.
     */
    private fun tickCombatTimers(deltaMs: Long) {
        // Player invulnerability timer
        if (playerInvulnMs > 0) {
            playerInvulnMs = (playerInvulnMs - deltaMs).coerceAtLeast(0L)
        }

        // Enemy invulnerability timers (tick down CombatStats)
        var i = 0
        while (i < enemies.size) {
            val combat = enemies[i].combat
            if (combat.invulnRemainingMs > 0) {
                combat.invulnRemainingMs = (combat.invulnRemainingMs - deltaMs).coerceAtLeast(0L)
            }
            i++
        }
    }

    /**
     * Apply damage to player with invulnerability frame support.
     * Supports SHIELD power-up damage reduction (Phase 2B).
     * @return true if damage was applied, false if blocked by i-frames
     */
    private fun applyDamageToPlayer(
        amount: Int,
        source: DamageSource,
        nowMs: Long
    ): Boolean {
        // Check invulnerability
        if (playerInvulnMs > 0) {
            return false  // Damage blocked by i-frames
        }

        // Apply SHIELD damage reduction if active
        val shieldMultiplier = powerUpSystem.getShieldDamageMultiplier()
        var damageToApply = (amount * shieldMultiplier).toInt()
        if (damageToApply < 1) damageToApply = 1  // Minimum 1 damage (keep game non-trivial)

        // Apply damage
        playerHealth = (playerHealth - damageToApply).coerceAtLeast(0)

        // Set invulnerability frames
        playerInvulnMs = PLAYER_IFRAMES_MS

        // Check death
        if (playerHealth <= 0) {
            isAlive = false
        }

        if (BuildConfig.DEBUG) {
            val shieldInfo = if (shieldMultiplier < 1.0f) " (shield: ${amount}→${damageToApply})" else ""
            android.util.Log.d("Combat", "Player hit by $source for $damageToApply damage$shieldInfo (HP: $playerHealth/$maxPlayerHealth)")
        }

        return true  // Damage applied
    }

    /**
     * Apply damage to enemy with combat stats.
     * Handles death logic exactly once.
     * @return true if damage was applied, false if blocked
     */
    private fun applyDamageToEnemy(
        enemy: Enemy,
        amount: Int,
        source: DamageSource
    ): Boolean {
        val combat = enemy.combat

        // Check invulnerability (enemies have minimal or no i-frames by default)
        if (combat.invulnRemainingMs > 0) {
            return false  // Damage blocked
        }

        // Track if this will be a kill (hp transition to 0)
        val wasAlive = combat.hp > 0

        // Apply damage
        combat.hp = (combat.hp - amount).coerceAtLeast(0)

        // Sync legacy health field for compatibility
        enemy.health = combat.hp

        // Award kill score exactly once (only on hp transition to 0)
        if (wasAlive && combat.hp == 0) {
            killScore += 10

            if (BuildConfig.DEBUG) {
                android.util.Log.d("Combat", "Enemy killed by $source (HP: 0/${combat.maxHp})")
            }
        }

        return true  // Damage applied
    }

    private fun awardScoreAndCurrency(currentTime: Long) {
        // Score: 1 point per 100ms (survival) + kill bonuses
        survivalScore = (survivedMilliseconds / 100).toInt()
        score = survivalScore + killScore  // Combine survival + kill scores

        // Currency: 1 $M per second
        val baseCurrency = survivedMilliseconds / 1000
        earnedCurrency = (baseCurrency * currentMultiplier).toInt()
    }

    private fun updateRiskChallenges(dtMs: Long) {
        activeRisk?.let { risk ->
            val remaining = risk.timeRemaining - dtMs
            if (remaining <= 0) {
                risk.onSuccess()
                activeRisk = null
            } else {
                activeRisk = risk.copy(timeRemaining = remaining)
            }
        }
    }

    private fun handlePurchase(itemId: String) {
        if (purchasesThisWindow >= maxPurchasesPerWindow) return

        val item = shopItems.firstOrNull { it.id == itemId } ?: return

        // Calculate actual cost with scaling
        val debtMultiplier = if (playerUpgrades.debtPenaltyShopsRemaining > 0) 1.5 else 1.0
        val actualCost = calculateItemCost(item.baseCost, shopIndex, debtMultiplier)

        if (earnedCurrency >= actualCost) {
            earnedCurrency -= actualCost
            purchasesThisWindow++
            applyShopItem(item)

            // Regenerate shop items
            shopItems = generateShopItems(shopIndex, survivedMilliseconds / 1000)
        }
    }

    private fun applyShopItem(item: ShopItem) {
        when (item.type) {
            ShopItemType.FIRE_RATE -> playerUpgrades.fireRateLevel++
            ShopItemType.BULLET_SPEED -> playerUpgrades.bulletSpeedLevel++
            ShopItemType.SCORE_BOOST -> playerUpgrades.scoreBoostPercent += 15.0
            ShopItemType.CURRENCY_BOOST -> playerUpgrades.currencyBoostPercent += 15.0
            else -> {} // Other types handled separately
        }
    }

    private fun calculateMultiplier(survivedMs: Long, scalingFactor: Double = 0.6, maxMultiplier: Double = 10.0): Double {
        val timeInSeconds = survivedMs / 1000.0
        val multiplier = 1.0 + scalingFactor * ln(1.0 + timeInSeconds)
        return min(multiplier, maxMultiplier)
    }

    /**
     * Publish primitive state to UI layer (ZERO allocations: primitives only).
     * Entity lists are accessed via stable references (playerRef, enemiesRef, etc.)
     * to eliminate per-frame map{} and copy() allocations.
     */
    private fun publishSnapshot() {
        state.value = GameState(
            score = score,
            earnedCurrency = earnedCurrency,
            currentMultiplier = currentMultiplier,
            playerHealth = playerHealth,
            maxPlayerHealth = maxPlayerHealth,
            isAlive = isAlive,
            playerInvulnMs = playerInvulnMs,
            gameTime = gameTime,
            survivedMilliseconds = survivedMilliseconds,
            isShopOpen = isShopOpen,
            shopIndex = shopIndex,
            purchasesThisWindow = purchasesThisWindow,
            maxPurchasesPerWindow = maxPurchasesPerWindow,
            playerInsideShop = playerInsideShop,
            shopItems = shopItems,
            playerUpgrades = playerUpgrades,  // Shared reference, no copy
            activeRisk = activeRisk,  // Shared reference, no copy
            permanentMultiplierBonus = permanentMultiplierBonus,
            weaponStats = weaponStats,  // Shared reference, no copy
            activePowerUpEffects = powerUpSystem.activeEffects,  // Shared reference, no toMap()
            difficultyLevel = difficultyScaler.getCurrentLevel(),
            powerUpSprite = powerUpSprite,
            spaceCenterSprite = spaceCenterSprite,
            backgroundScrollOffset = 0f
        )
    }

    /**
     * Get debug config for UI display
     */
    fun getDebugConfig(): DebugConfig = debugConfig

    /**
     * Handle debug stress testing input events (debug builds only).
     * Called at safe point at start of update().
     */
    fun handleDebugStress(event: InputEvent.DebugStress) {
        when (event) {
            is InputEvent.DebugStress.SetSpawnMultiplier -> {
                debugConfig = debugConfig.copy(
                    enabled = event.multiplier > 1,
                    spawnMultiplier = event.multiplier
                )
            }
            is InputEvent.DebugStress.TriggerBurst -> {
                debugConfig = debugConfig.copy(spawnBurst = event.count)
            }
        }
    }
}

/**
 * Debug stress testing configuration (primitives only).
 * Controls spawn rate multiplication and burst spawning for testing.
 */
data class DebugConfig(
    val enabled: Boolean = false,
    val spawnMultiplier: Int = 1,
    val spawnBurst: Int = 0  // Consumed when > 0, then reset to 0
)
