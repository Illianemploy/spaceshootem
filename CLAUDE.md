# CLAUDE.md — Master AI Assistant Contract (SpaceShooter)

This repo is a performance-critical 2D Android game with a vsync-driven frame loop.
Hot paths must remain allocation-free to sustain stable 60fps.

## Canonical Docs (MUST READ BEFORE CODING)
These are the source of truth. Read them in this order:
1) PROJECT_CONTEXT.md
2) docs/HOT_PATHS.md
3) docs/AI_DEV_PLAYBOOK.md
4) tools/perf_gate.sh

**Precedence:** If this file conflicts with any canonical doc, the canonical doc wins.
This file is intentionally short and must not duplicate detailed rules.

## Mandatory Response Format (Every Code Change)
1) Doctrine Readback (3–6 bullets): rules that apply to THIS change
2) Plan (3–8 bullets)
3) Patch: minimal diff only (exact files/sections + code)
4) Perf Gate Check: what you avoided / any PERF_GATE_ALLOW (with reason)
5) Verification: build commands + what to test manually
6) Doctrine Improvement Suggestions: concrete edits to docs if you found gaps

If you cannot read the canonical docs, stop and ask for them. Do not guess.

## Non-Negotiables (Top Constraints)
- Frame pacing uses vsync (`withFrameNanos`); no `delay()` / `sleep()` for timing.
- Clamp dt (per existing codebase rules) to avoid spiral-of-death.
- No allocations in hot paths (update, collision loops, rendering paths).
- Rendering is read-only: UI/Canvas must not mutate engine/world state.
- `GameState` is primitives-only (no bitmaps, lists, entities, or object graphs).
- Hot path loops use indexed iteration; avoid iterator allocations and functional chains.
- No bitmap decode/scale, object creation, or string formatting inside draw paths.
- Minimal diffs: no drive-by refactors, no “cleanup” unrelated to the task.
- Run `bash tools/perf_gate.sh && ./gradlew assembleDebug` before finalizing.
- If an exception is unavoidable, add `// PERF_GATE_ALLOW: <reason>` and justify.

That’s the contract. Task-specific instructions follow in the user prompt.
