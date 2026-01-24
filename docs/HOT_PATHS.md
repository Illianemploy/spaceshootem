# Hot Paths — Must Remain Allocation-Free

These locations run every frame (60Hz or near). Keep them allocation-free and iterator-free.

## Frame pacing loop (vsync)
- File: app/src/main/java/**/MainActivity.kt
- Location: coroutine loop using `withFrameNanos` that calls `gameEngine.update(dtMs)` and triggers redraw.
- Rules:
  - no `delay(...)` frame pacing
  - no per-frame object construction
  - only minimal “frame tick” state to invalidate Canvas

## Engine update
- File: app/src/main/java/**/GameEngine.kt
- Function: `update(dtMs: Long)` and any functions called every frame from update.
- Rules:
  - indexed loops only
  - no `.map/.filter/.forEach`, no `toList/toMap`
  - no list rebuilding / snapshot copying per frame
  - no per-frame allocations (objects, collections, strings)

## Rendering / Canvas draw
- File: app/src/main/java/**/MainActivity.kt (Compose Canvas draw block) OR renderer file if extracted
- Rules:
  - no allocations; no string formatting
  - no bitmap decode/scale
  - reuse Paint/Rect/Matrix (if used at all)
  - renderer does not mutate engine state

## Collision + spawn loops
- File(s): wherever collisions and spawns are processed (called from `update`)
- Rules:
  - avoid temporary lists (no filtering into new lists)
  - keep complexity predictable (O(n) or O(n log n) only with justification)
