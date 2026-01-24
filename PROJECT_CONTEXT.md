# Project Context — Android Space Shooter

## Summary
A 2D space shooter built in Android Studio using Kotlin + Compose Canvas with a custom vsync-driven game loop. The goal is stable, deterministic ~60fps on a real mid-range Android device, with low GC pressure and an architecture that can grow (bosses, simple upgrades/shop) without rewrites.

## Engineering Invariants (non-negotiable)
1) Frame pacing is vsync-driven (`withFrameNanos` or Choreographer). No `delay(16)` frame loops.
2) Hot path (update + draw) must be allocation-free: no new objects/collections/strings per frame.
3) Renderer is pure: rendering reads state; rendering never mutates game state.
4) No per-frame world snapshots: do not rebuild large state objects or copy entity lists each frame.
5) One-way flow: Input → Commands → Engine Update → Render.
6) Hot loops use indexed iteration (avoid `.map/.filter/.forEach` and iterator allocations).
7) Assets (bitmaps) are decoded/scaled only during init/resize, never in update/draw.
8) Debug overlay: metrics and formatted strings update at most once per second.

Exceptions to invariants require:
- an inline `PERF_GATE_ALLOW: <reason>` comment, AND
- a short justification + measurement note.

## Feature Scope (keep it showable, not bloated)
IN for demo:
- Touch movement + shooting
- Enemy waves, collisions/damage, score/multiplier
- Pause/resume, restart
- One simple upgrade/shop system (3–6 upgrades)
- One boss encounter (1 boss archetype)
- Debug overlay: update/draw ms, drawn FPS, entity counts, worst-frame indicator

OUT for now:
- Online features (accounts, leaderboards)
- Inventory/equipment UI and loadouts
- Multi-currency economy, crafting, rarity tiers
- Heavy procedural generation beyond simple wave tables

## Asset Policy (prevents “stretched” visuals)
Background/parallax layers:
- Scale to screen width preserving aspect ratio (no stretching).
- If scaled height < screen height → tile vertically.
- If scaled height > screen height → crop vertically (no rescale per frame).

Sprites:
- Decide intended on-screen size in dp (e.g., ship ~96dp).
- Pre-scale bitmaps once per asset (preserve aspect ratio); no per-frame scaling.

## How to start a new AI chat (copy/paste)
Before changing code: read `PROJECT_CONTEXT.md`, `docs/AI_DEV_PLAYBOOK.md`, and `docs/HOT_PATHS.md`.
Summarize the invariants and list the hot paths you will touch. Then proceed with minimal diffs.
