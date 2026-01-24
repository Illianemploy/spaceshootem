# AI Dev Playbook — Android Space Shooter

This repo prioritizes deterministic frame pacing, allocation-free hot paths, and debuggability.
These rules apply to humans and AIs.

## Workflow (required for any change)
1) Read: `PROJECT_CONTEXT.md` + `docs/HOT_PATHS.md`.
2) Identify what you will change and what must not change (gameplay feel, difficulty, timings).
3) Identify which hot paths are touched.
4) Implement the smallest safe diff. Avoid drive-by refactors.
5) Run verification:
   - `bash tools/perf_gate.sh` (or `./tools/perf_gate.sh`)
   - `./gradlew assembleDebug`
6) Provide deliverables in your response:
   - list of files changed
   - summary per file
   - key code blocks (not full files)
   - explicit statement: “no gameplay changes” (or list any unavoidable changes)

## Hot Path Rules (60Hz areas)
Hot path includes: frame loop, `GameEngine.update`, renderer/Canvas draw, collision/spawn loops.

Required patterns in hot paths:
- Vsync frame loop (`withFrameNanos`) with dt clamp
- Indexed loops: `for (i in 0 until list.size) { ... }`
- Stable, engine-owned mutable world model updated in place
- Cached derived stats (compute on change, not per frame)
- Reuse paints/rects/scratch buffers; no per-frame allocation

Banned patterns in hot paths (unless PERF_GATE_ALLOW with reason):
- `.map(` `.filter(` `.forEach(` `toList(` `toMap(`
- `average()` `removeAt(`
- `String.format` or string interpolation in per-frame loops
- `BitmapFactory.decodeResource` / `Bitmap.createScaledBitmap` in update/draw
- Per-frame world snapshot rebuilds (e.g., `state.value = GameState(...)` inside frame pacing loop)
- `delay(...)` as the main timing mechanism for frame pacing

## Debug overlay rules
- Update metrics + formatted strings at most once per second.
- Draw uses cached strings; no per-frame formatting.
- Include: drawn FPS, updateAvgMs, drawAvgMs, entity counts, “budget ok” indicator.

## Definition of Done (for any performance/engine change)
Build:
- `./gradlew assembleDebug` succeeds.

Device sanity (debug overlay):
- Drawn FPS ~60 idle and moving (unless thermals)
- updateAvgMs + drawAvgMs typically < 16ms combined
- No periodic hitching consistent with GC spikes
- Entity counts stable (no runaway spawn loops)

## When rules can be broken
Only if ALL apply:
- add `// PERF_GATE_ALLOW: <reason>` on the exact line(s)
- explain why it’s safe
- include measurement evidence (overlay/profiler notes)
- provide rollback note (how to revert/disable quickly)
