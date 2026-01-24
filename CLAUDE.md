# CLAUDE.md - AI Assistant Guide for Space Shooter Project

## Project Overview

This is a **performance-critical 2D Android Space Shooter game** built with Kotlin and Jetpack Compose. The project is engineered for **stable 60fps performance** with strict allocation-free hot paths and deterministic frame pacing.

**Core Philosophy:** Performance-first design with zero tolerance for frame drops or garbage collection in critical paths.

### Key Stats
- **Language:** Kotlin 1.9.20 (JVM 17)
- **Framework:** Jetpack Compose + Canvas rendering
- **Target:** Android API 26+ (targetSdk 34)
- **Architecture:** Vsync-driven game engine with mutable world state + immutable snapshots
- **Lines of Code:** ~3,000 lines across 4 Kotlin files

---

## Critical: Read These First

Before making ANY changes, you MUST read these foundational documents in order:

1. **`PROJECT_CONTEXT.md`** - Engineering invariants and performance guarantees
2. **`docs/HOT_PATHS.md`** - Hot path identification and constraints
3. **`docs/AI_DEV_PLAYBOOK.md`** - Development workflow and CI/CD process

**These are not suggestions—they are hard constraints.** Violating these rules will cause performance regressions and frame drops.

---

## Codebase Structure

```
spaceshootem/
├── app/
│   ├── build.gradle.kts          # App-level build config
│   └── src/main/
│       ├── java/com/shooter/space/
│       │   ├── MainActivity.kt    # UI layer & rendering (2,114 lines)
│       │   ├── GameEngine.kt      # Core game logic (713 lines)
│       │   ├── GameState.kt       # Immutable state snapshot (93 lines)
│       │   └── InputEvent.kt      # Input event abstraction (39 lines)
│       ├── AndroidManifest.xml
│       └── res/drawable/          # Game sprites (8 PNG files, ~5.1MB)
├── docs/
│   ├── AI_DEV_PLAYBOOK.md        # Development workflow
│   └── HOT_PATHS.md              # Performance constraints
├── tools/
│   └── perf_gate.sh              # Performance regression scanner
├── build.gradle.kts              # Root build config
├── PROJECT_CONTEXT.md            # Engineering invariants
└── README.md                     # User-facing documentation
```

---

## Architecture Overview

### Layer Separation

```
UI Layer (Compose)
    ↕ StateFlow + InputEvent
Game Engine (Mutable World)
    ↕ Primitives Only
Game State (Immutable Snapshot)
```

#### 1. UI Layer (`MainActivity.kt`)
- **Screens:** `MenuScreen`, `GameScreen`, `ShopOverlay`
- **Rendering:** Allocation-free Canvas drawing (player, enemies, bullets, power-ups, backgrounds)
- **Input:** Touch drag → `InputEvent.Move`
- **Frame Loop:** `withFrameNanos` vsync-driven (16.67ms baseline)
- **Performance Monitoring:** `RollingStats` (60-frame ring buffer), `DebugMetrics` overlay

#### 2. Game Engine (`GameEngine.kt`)
- **Owns:** All mutable entity lists (player, enemies, bullets, stars, spaceCenter)
- **Exposes:** Stable references (`playerRef`, `enemiesRef`, etc.) for renderer
- **Publishes:** Primitives-only `GameState` (NO per-frame entity copying)
- **Core Systems:**
  - `DifficultyScaler` - Adaptive spawn rate, speed, health
  - `PowerUpSystem` - World pickups + timed buffs
  - `EnemyBehaviorController` - AI state machine (IDLE, APPROACH, STRAFE, FLEE)
  - `ParallaxBackgroundManager` - 2-layer scrolling background

#### 3. Game State (`GameState.kt`)
- **Type:** Data class with ONLY primitives (Int, Long, Float, Double, Boolean)
- **Purpose:** One-way state publication to UI (no reverse mutation)
- **Contents:** Score, currency, multiplier, health, timers, shop state, upgrades

#### 4. Input Events (`InputEvent.kt`)
- **Sealed Class:** `Move`, `PauseToggle`, `ShopEvent`, `DebugToggle`
- **Flow:** UI → Engine (one-way, no direct state mutation)

---

## Performance Constraints (CRITICAL)

### Hot Paths (60 Hz, Every Frame)

These code paths execute **60 times per second** and MUST be allocation-free:

#### 1. Frame Pacing Loop (`MainActivity.kt`)
```kotlin
// ✅ CORRECT
withFrameNanos { nanos ->
    val dtMs = (nanos - lastFrameNanos) / 1_000_000f
    gameEngine.update(dtMs.coerceIn(0f, 50f))
}

// ❌ FORBIDDEN
delay(16)  // Causes frame drift
```

**Rules:**
- Use `withFrameNanos` ONLY for frame pacing
- Clamp dt to 0-50ms (prevents spiral of death)
- NO `delay()`, NO `Thread.sleep()`

#### 2. Engine Update (`GameEngine.update()`)
```kotlin
// ✅ CORRECT - Indexed loop, in-place update
for (i in 0 until bullets.size) {
    bullets[i].x += bullets[i].velocityX * dtMs
}

// ❌ FORBIDDEN - Allocates iterator + filtered list
bullets = bullets.filter { it.alive }.map {
    it.copy(x = it.x + it.velocityX * dtMs)
}
```

**Rules:**
- Use **indexed loops ONLY**: `for (i in 0 until list.size)`
- Update entities **in-place** (mutate, don't copy)
- NO `.map()`, `.filter()`, `.forEach()`, `.toList()`, `.toMap()`
- NO `removeAt()` during iteration (causes index shifts)
- NO string formatting or concatenation

#### 3. Rendering (`Canvas.drawIntoCanvas {}`)
```kotlin
// ✅ CORRECT - Reuse cached bitmap
drawImage(cachedPlayerSprite, ...)

// ❌ FORBIDDEN - Decodes/scales every frame
val sprite = BitmapFactory.decodeResource(...)
drawImage(sprite.scale(...), ...)
```

**Rules:**
- Decode/scale bitmaps ONCE in initialization
- NO per-frame `Paint` creation (reuse static instances)
- NO per-frame bitmap operations
- NO string formatting in draw calls

#### 4. Collision Detection
```kotlin
// ✅ CORRECT - Simple circle collision
val dist = sqrt((dx * dx) + (dy * dy))
if (dist < radius1 + radius2) { /* collision */ }

// ❌ FORBIDDEN - Cartesian product with allocations
bullets.forEach { b ->
    enemies.filter { it.alive }.forEach { e -> /* ... */ }
}
```

**Rules:**
- Use indexed loops with early exit
- Prefer O(n) or O(n log n) complexity
- Avoid nested `.forEach()` / `.filter()` chains

### Performance Gate Script

**ALWAYS run before committing:**
```bash
bash tools/perf_gate.sh && ./gradlew assembleDebug
```

The script scans for forbidden patterns:
- `.map()`, `.filter()`, `.forEach()`, `.toList()`, `.toMap()`
- `average()`, `removeAt()`, bitmap operations in hot paths
- `delay()` in frame loop
- Per-frame `GameState` construction

**Exceptions:** Add `// PERF_GATE_ALLOW: <reason>` comment with justification.

---

## Key Systems & Mechanics

### Difficulty System
- **Progression:** By time survived OR score (whichever is further)
- **Scaling:**
  - Spawn interval: 1000ms → 300ms (exponential decay 0.95^level)
  - Enemy speed: 1.0x → 2.5x (linear +0.05x per level)
  - Enemy health: 1 → 5 hits (every 5 levels)
- **Implementation:** `DifficultyScaler` in `GameEngine.kt`

### Power-Up System
- **Types:** HEALTH, SHIELD, FIREPOWER, MULTISHOT, DOUBLE_REWARD
- **Mechanics:**
  - World pickups (collision radius 30px)
  - Timed buffs (8-12 seconds, tier-scaled)
  - Stackable effects
- **Rendering:** 5×5 sprite atlas (`bonuses_0001.png`)

### Shop System
- **Trigger:** Auto-opens when player enters SpaceCenter collision zone
- **Mechanics:**
  - 3 purchases per shop window
  - 30-second cooldown before next spawn
  - Cost scaling: `baseCost * (1 + shopIndex^0.6) * debtMultiplier`
- **Items:** Fire Rate, Bullet Speed, Score/Currency Boost, Glass Cannon, Debt Advance, etc.
- **State:** Game updates FREEZE when shop is open (only timers tick)

### Enemy AI
- **States:** IDLE, APPROACH, STRAFE, FLEE
- **Update Interval:** 200ms (NOT every frame—avoids thrashing)
- **Size Tiers:** SMALL (60%), MEDIUM (25%), LARGE (12%), ELITE (3%)
- **Visuals:** 80% procedural shapes, 20% sprites

### Background Parallax
- **Layers:** 2 layers (far scrolls at 120px/s, near is static)
- **Assets:** `parallax_background_001.png`, `parallax_background_002.png`
- **Rendering:** Seamless vertical tiling, bitmaps decoded ONCE

---

## Development Workflow

### Making Changes

1. **Research Phase:**
   - Read `PROJECT_CONTEXT.md`, `docs/HOT_PATHS.md`, `docs/AI_DEV_PLAYBOOK.md`
   - Identify files you'll modify
   - Determine if changes touch hot paths

2. **Implementation Phase:**
   - Make **minimal diffs** (don't refactor surrounding code)
   - If modifying hot paths:
     - Use indexed loops
     - Avoid allocations
     - Reuse objects
     - Add `// PERF_GATE_ALLOW` if needed
   - Update comments to explain WHY, not WHAT

3. **Verification Phase:**
   ```bash
   bash tools/perf_gate.sh     # Check for forbidden patterns
   ./gradlew assembleDebug     # Verify build succeeds
   ```

4. **Testing Phase:**
   - Install APK on device/emulator
   - Enable debug overlay (5 quick taps in top-right corner)
   - Verify FPS stays at 60 (frame time ≤16.67ms)
   - Play for 2+ minutes to check for memory leaks

5. **Documentation Phase:**
   - Update this file if architecture changes
   - Update `PROJECT_CONTEXT.md` if constraints change
   - Update `docs/HOT_PATHS.md` if new hot paths added

### Commit Workflow

**Branch:** Always work on `claude/claude-md-mksfn6ffno5e54fn-dY9cm`

```bash
# Check current branch
git status

# Stage changes
git add <files>

# Commit with descriptive message
git commit -m "Brief summary of change

- Detailed bullet point 1
- Detailed bullet point 2"

# Push to feature branch
git push -u origin claude/claude-md-mksfn6ffno5e54fn-dY9cm
```

**Commit Message Guidelines:**
- Imperative mood ("Add feature" not "Added feature")
- First line ≤50 chars
- Explain WHY, not WHAT (code shows what)
- Reference issue numbers if applicable

---

## Common Patterns & Anti-Patterns

### ✅ Correct Patterns

#### Entity Updates
```kotlin
// Update in-place with indexed loop
for (i in 0 until bullets.size) {
    bullets[i].x += bullets[i].velocityX * dtMs
    bullets[i].y += bullets[i].velocityY * dtMs
}
```

#### Entity Removal
```kotlin
// Two-pass: mark dead, then compact
for (i in 0 until enemies.size) {
    if (enemies[i].health <= 0) enemies[i].alive = false
}
enemies.removeAll { !it.alive }  // OUTSIDE hot path (e.g., once per second)
```

#### Collision Detection
```kotlin
// Early exit with indexed loops
for (i in 0 until bullets.size) {
    for (j in 0 until enemies.size) {
        if (!bullets[i].alive || !enemies[j].alive) continue
        val dx = bullets[i].x - enemies[j].x
        val dy = bullets[i].y - enemies[j].y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < bullets[i].radius + enemies[j].radius) {
            // Handle collision
        }
    }
}
```

### ❌ Anti-Patterns

#### Functional Chains
```kotlin
// ❌ Allocates 3+ intermediate lists per frame
val hits = bullets
    .filter { it.alive }
    .map { b -> enemies.filter { e -> e.alive && collides(b, e) } }
    .flatten()
```

#### Per-Frame String Ops
```kotlin
// ❌ Allocates string every frame
drawText("Score: $score", ...)

// ✅ Use remember {} to cache, update ≤1x per second
val scoreText = remember(score) { "Score: $score" }
```

#### Iterator Allocations
```kotlin
// ❌ Allocates iterator
for (bullet in bullets) { /* ... */ }

// ✅ Indexed loop
for (i in 0 until bullets.size) { /* ... */ }
```

#### Entity Copying
```kotlin
// ❌ Copies entire entity list every frame
val newBullets = bullets.map { it.copy(x = it.x + it.vx * dt) }

// ✅ Mutate in-place
for (i in 0 until bullets.size) {
    bullets[i].x += bullets[i].vx * dt
}
```

---

## Testing & Debugging

### Debug Overlay

Enable by tapping 5 times quickly in top-right corner of screen (debug builds only).

**Displays:**
- FPS (frames drawn per second)
- Frame time (ms, 60-frame rolling average)
- Update time (ms)
- Draw time (ms)
- Entity counts (enemies, bullets, stars)
- Spawn interval
- Difficulty level
- Score, currency, multiplier
- Background layer info

**Performance Targets:**
- FPS: 60 ± 0 (no drops)
- Frame time: ≤16.67ms
- Update time: ≤8ms
- Draw time: ≤8ms

### Build Commands

```bash
# Clean build
./gradlew clean

# Debug build (includes performance overlay)
./gradlew assembleDebug

# Release build (ProGuard enabled)
./gradlew assembleRelease

# Install to connected device
./gradlew installDebug

# Run performance gate + build
bash tools/perf_gate.sh && ./gradlew assembleDebug
```

### Profiling

For deeper analysis:
```bash
# Enable profiling in Android Studio
# Run > Profile 'app' with CPU profiler
# Look for:
# - GC events (should be ZERO during gameplay)
# - Frame drops (should be ZERO)
# - Allocation hotspots
```

---

## File-Specific Guidance

### `MainActivity.kt` (UI & Rendering)

**What it does:**
- Composable screens (MenuScreen, GameScreen, ShopOverlay)
- Canvas rendering for all game entities
- Frame loop with vsync pacing
- Input handling (touch drag)
- Performance monitoring

**When to modify:**
- Adding new UI screens
- Changing rendering logic
- Adding new visual effects
- Modifying input handling

**Hot paths:**
- `GameScreen` composable (entire frame loop)
- All `draw*()` functions
- `RollingStats.update()`

**Key rules:**
- NO bitmap decode/scale in draw functions
- Reuse `Paint` objects (static or `remember {}`)
- Cache sprite references in `GameState`
- Update debug overlay ≤1x per second

### `GameEngine.kt` (Game Logic)

**What it does:**
- Core game loop (`update()` method)
- Entity management (player, enemies, bullets, etc.)
- Collision detection
- Difficulty scaling
- Power-up system
- Shop mechanics
- Persistence (SharedPreferences)

**When to modify:**
- Adding new game mechanics
- Changing difficulty progression
- Adding new enemy types
- Modifying collision logic
- Adding new power-ups

**Hot paths:**
- Entire `update()` method
- All collision loops
- Entity update loops

**Key rules:**
- Indexed loops ONLY
- In-place entity updates
- NO `.filter()`, `.map()`, `.forEach()`
- Entity removal: mark dead, compact outside hot path
- Publish `GameState` ONCE per frame (not inside loops)

### `GameState.kt` (State Snapshot)

**What it does:**
- Immutable data class with primitives only
- Published once per frame to UI layer
- Contains all visible game state

**When to modify:**
- Adding new UI-visible state
- Adding new upgrade types
- Adding new shop items

**Key rules:**
- ONLY primitives (Int, Long, Float, Double, Boolean)
- NO entity lists (use stable refs from GameEngine instead)
- NO per-frame copying of entities
- Keep size minimal (every byte copied 60x per second)

### `InputEvent.kt` (Input Abstraction)

**What it does:**
- Sealed class for all input events
- One-way flow: UI → Engine

**When to modify:**
- Adding new input types (gestures, buttons)
- Adding new shop interactions

**Key rules:**
- Keep events lightweight (copied on every input)
- Use sealed class hierarchy for type safety

---

## Adding New Features

### Example: Adding a New Power-Up Type

1. **Update `PowerUpType` enum:**
   ```kotlin
   enum class PowerUpType {
       HEALTH, SHIELD, FIREPOWER, MULTISHOT, DOUBLE_REWARD,
       NEW_POWERUP  // Add here
   }
   ```

2. **Add sprite to atlas:**
   - Update `bonuses_0001.png` (5×5 grid)
   - Or add new drawable resource

3. **Implement effect in `GameEngine.applyPowerUpEffect()`:**
   ```kotlin
   PowerUpType.NEW_POWERUP -> {
       // Apply instant effect OR add to activeEffects
   }
   ```

4. **Update `updatePowerUpEffects()` if timed:**
   ```kotlin
   PowerUpType.NEW_POWERUP -> {
       // Apply per-frame effect
   }
   ```

5. **Add rendering in `MainActivity.drawPowerUp()`:**
   ```kotlin
   PowerUpType.NEW_POWERUP -> {
       // Draw sprite or shape
   }
   ```

6. **Test:**
   - Run `bash tools/perf_gate.sh && ./gradlew assembleDebug`
   - Verify no frame drops with debug overlay

### Example: Adding a New Enemy Type

1. **Update `EnemySize` or `EnemyVariant` enum** (if needed)

2. **Modify `spawnEnemy()` in `GameEngine.kt`:**
   ```kotlin
   // Add to spawn logic with probability
   val type = when (random.nextFloat()) {
       in 0f..0.6f -> EnemySize.SMALL
       in 0.6f..0.85f -> EnemySize.MEDIUM
       in 0.85f..0.97f -> EnemySize.LARGE
       else -> EnemySize.NEW_TYPE  // Add here
   }
   ```

3. **Add rendering logic in `MainActivity.drawEnemy()`:**
   ```kotlin
   when (enemy.variant) {
       EnemyVariant.NEW_TYPE -> {
           // Draw shape or sprite
       }
   }
   ```

4. **Test spawn rates and performance**

---

## Common Tasks

### Adjusting Difficulty Progression
- **File:** `GameEngine.kt`
- **Class:** `DifficultyScaler`
- **Properties:** `baseSpawnInterval`, `minSpawnInterval`, `spawnDecayFactor`, `baseEnemySpeed`, `speedIncrement`

### Changing Player Stats
- **File:** `GameEngine.kt`
- **Variables:** `maxHealth`, `playerSize`, `playerSpeed`, `baseFireCooldown`

### Adding Shop Items
- **File:** `MainActivity.kt`
- **Function:** `generateShopItems()`
- **Add to:** `shopPool` list with name, cost formula, and effect lambda

### Modifying Scoring
- **File:** `GameEngine.kt`
- **Function:** `update()`
- **Section:** Search for `// Score & currency calculation`

### Changing Background
- **File:** `MainActivity.kt`
- **Class:** `ParallaxBackgroundManager`
- **Replace:** `parallax_background_001.png`, `parallax_background_002.png` in `res/drawable/`

---

## Build Configuration

### Gradle Dependencies

Key libraries:
```kotlin
// Compose UI
androidx.compose.ui:ui
androidx.compose.material3:material3
androidx.compose.ui:ui-graphics

// Lifecycle & Coroutines
androidx.lifecycle:lifecycle-runtime-ktx
org.jetbrains.kotlinx:kotlinx-coroutines-core
org.jetbrains.kotlinx:kotlinx-coroutines-android
```

### Kotlin Compiler Options
- JVM Target: 17
- Compose Compiler Extension: 1.5.4
- Language Version: 1.9

### ProGuard (Release Builds)
- Enabled for release builds
- Rules in `proguard-rules.pro` (if exists)

---

## Troubleshooting

### Performance Issues

**Symptom:** FPS drops below 60

**Diagnosis:**
1. Enable debug overlay (5 taps in top-right)
2. Check frame time (should be ≤16.67ms)
3. Check entity counts (enemies, bullets)

**Common Causes:**
- Allocations in hot paths → Run `perf_gate.sh`
- Too many entities → Reduce spawn rate or add culling
- Expensive draw operations → Cache bitmaps, reuse Paint objects
- Unoptimized collision detection → Use spatial partitioning (if needed)

**Fix:**
- Profile with Android Studio CPU profiler
- Look for GC events during gameplay
- Identify allocation hotspots
- Refactor to indexed loops + in-place updates

### Build Failures

**Symptom:** `./gradlew assembleDebug` fails

**Common Causes:**
1. **Kotlin syntax error** → Check error message, fix syntax
2. **Missing import** → Add import statement
3. **Type mismatch** → Check function signatures
4. **Perf gate failure** → Run `bash tools/perf_gate.sh` to see violations

**Fix:**
- Read error message carefully
- Fix indicated file/line
- Re-run build

### Runtime Crashes

**Symptom:** App crashes on launch or during gameplay

**Diagnosis:**
1. Check logcat: `adb logcat | grep -i exception`
2. Look for stack trace

**Common Causes:**
- Null pointer exception → Check for null values
- Index out of bounds → Check array access in loops
- Resource not found → Verify drawable paths
- Division by zero → Check dt clamping

**Fix:**
- Add null checks where needed
- Validate array indices
- Use `coerceIn()` for bounds
- Clamp division denominators

---

## Advanced Topics

### Adding Spatial Partitioning

If collision detection becomes a bottleneck (unlikely with current entity counts):

1. Implement quadtree or grid-based partitioning
2. Update in `GameEngine.update()` ONCE per frame
3. Query only nearby entities in collision loops
4. Profile to verify performance gain (don't optimize prematurely)

### Adding Particle Effects

1. Create `Particle` data class with position, velocity, lifetime
2. Add `particles: MutableList<Particle>` to `GameEngine`
3. Update particles in `update()` with indexed loop
4. Draw particles in `MainActivity.drawParticles()`
5. **CRITICAL:** Reuse dead particles (object pooling) to avoid allocations

### Save System Enhancement

Current: SharedPreferences for high score + currency

To add:
1. Serialize `PlayerUpgrades` data class to JSON
2. Save on game over
3. Load on game start
4. Use Kotlin serialization or Gson

### Multiplayer/Leaderboard

Not currently supported. Would require:
- Backend API (Firebase, Supabase, etc.)
- Network calls in coroutines (NOT in frame loop)
- UI for leaderboard screen

---

## Version History & Migrations

### Current Version
- Initial release with core gameplay loop
- Performance-optimized rendering
- Shop system with 8 item types
- Power-up system with 5 types
- Difficulty scaling
- Parallax backgrounds

### Future Considerations
- Achievement system
- Daily challenges
- Boss enemies
- Different weapons/ships
- Sound effects & music (using SoundPool, loaded in init)

When adding new features, maintain the performance-first philosophy:
- Profile first, optimize only if needed
- Prefer simple solutions over complex abstractions
- Keep hot paths allocation-free
- Document performance tradeoffs

---

## Questions & Support

### Documentation
- **Architecture:** This file (CLAUDE.md)
- **Constraints:** PROJECT_CONTEXT.md
- **Hot Paths:** docs/HOT_PATHS.md
- **Workflow:** docs/AI_DEV_PLAYBOOK.md
- **User Guide:** README.md

### Performance Concerns
- Run `bash tools/perf_gate.sh` before asking
- Profile with Android Studio if FPS drops
- Check debug overlay for frame times

### Build Issues
- Clean build: `./gradlew clean`
- Check Gradle version (8.2)
- Check Kotlin version (1.9.20)
- Verify Android SDK installed

---

## Checklist for AI Assistants

Before making ANY change:

- [ ] Read `PROJECT_CONTEXT.md`
- [ ] Read `docs/HOT_PATHS.md`
- [ ] Read `docs/AI_DEV_PLAYBOOK.md`
- [ ] Identified files to modify
- [ ] Determined if hot paths are affected
- [ ] Planned minimal diff (no unnecessary refactoring)

Before committing:

- [ ] Ran `bash tools/perf_gate.sh` (passed)
- [ ] Ran `./gradlew assembleDebug` (succeeded)
- [ ] Tested on device/emulator
- [ ] Verified FPS stays at 60 with debug overlay
- [ ] No GC events during gameplay (if profiled)
- [ ] Updated documentation if architecture changed

Commit message includes:

- [ ] Brief summary (≤50 chars)
- [ ] WHY the change was made
- [ ] Impact on gameplay/performance
- [ ] Files modified

---

## Final Notes

This codebase is **not a typical Android app**—it's a performance-critical real-time system with zero tolerance for allocations in hot paths. The constraints may seem strict, but they're what enable stable 60fps on mid-range devices.

**When in doubt:**
1. Read the docs (PROJECT_CONTEXT.md, HOT_PATHS.md, AI_DEV_PLAYBOOK.md)
2. Run the performance gate
3. Profile before optimizing
4. Prefer simplicity over cleverness

**Remember:** The goal is not just to make the game work—it's to make it work smoothly at 60fps with minimal GC pressure. Every allocation in a hot path is a frame drop waiting to happen.

Happy coding! 🚀
