#!/usr/bin/env bash
set -euo pipefail

# perf_gate.sh — lightweight performance regression gate
# Purpose:
# - catch common allocation patterns in hot paths (map/filter/forEach/toList/toMap/etc.)
# - catch bitmap decode/scale usage in Kotlin (often accidental)
# - catch delay-based frame pacing
# - catch per-frame GameState rebuild (heuristic)
#
# Exceptions allowed only with: PERF_GATE_ALLOW (on the same line)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v rg >/dev/null 2>&1; then
  echo "❌ perf_gate.sh requires ripgrep (rg). Install it and re-run."
  echo "   Windows: 'choco install ripgrep' or 'scoop install ripgrep'"
  echo "   macOS:   'brew install ripgrep'"
  echo "   Linux:   use your package manager (apt/dnf/pacman)"
  exit 2
fi

# Treat all Kotlin under app/src/main/java as “hot candidates”.
# For small projects, this is acceptable. If it becomes noisy later,
# narrow to specific files listed in docs/HOT_PATHS.md.
KOTLIN_ROOT="$ROOT_DIR/app/src/main/java"
if [ ! -d "$KOTLIN_ROOT" ]; then
  echo "❌ Expected Kotlin source root not found: $KOTLIN_ROOT"
  exit 2
fi

FAILED=0

fail_match() {
  local label="$1"
  local pattern="$2"
  local path="$3"

  # Exclude allowed lines.
  if rg -n "$pattern" "$path" | rg -v "PERF_GATE_ALLOW" >/dev/null 2>&1; then
    echo "❌ PERF GATE FAIL: $label"
    echo "   Pattern: $pattern"
    echo "   Path: $path"
    echo "   Matches (first 30):"
    rg -n "$pattern" "$path" | rg -v "PERF_GATE_ALLOW" | head -n 30 || true
    echo
    FAILED=1
  fi
}

echo "Running perf gate..."
echo "Scanning: $KOTLIN_ROOT"
echo

# Hot-path allocation / iterator patterns
fail_match "map() usage"        '\.map\('     "$KOTLIN_ROOT"
fail_match "filter() usage"     '\.filter\('  "$KOTLIN_ROOT"
fail_match "forEach() usage"    '\.forEach\(' "$KOTLIN_ROOT"
fail_match "toList() usage"     'toList\('    "$KOTLIN_ROOT"
fail_match "toMap() usage"      'toMap\('     "$KOTLIN_ROOT"
fail_match "average() usage"    'average\(\)' "$KOTLIN_ROOT"
fail_match "removeAt() usage"   'removeAt\('  "$KOTLIN_ROOT"

# Bitmap decode/scale should not happen in Kotlin hot paths.
# (If you truly need it, allow with PERF_GATE_ALLOW and explain why.)
fail_match "Bitmap decode in Kotlin" 'BitmapFactory\.decodeResource' "$KOTLIN_ROOT"
fail_match "Bitmap scale in Kotlin"  'Bitmap\.createScaledBitmap'    "$KOTLIN_ROOT"

# Delay-based frame pacing is forbidden. (delay is allowed in non-frame contexts only with PERF_GATE_ALLOW.)
fail_match "delay() usage (review carefully)" 'delay\(' "$KOTLIN_ROOT"

# Heuristic: per-frame GameState rebuilds (adjust if your naming differs).
fail_match "Per-frame GameState construction" 'state\.value\s*=\s*GameState\(' "$KOTLIN_ROOT"

if [ "$FAILED" -ne 0 ]; then
  echo "❌ PERF GATE FAILED."
  echo "Fix the issues above OR add 'PERF_GATE_ALLOW: <reason>' on the exact line(s) as an explicit exception."
  exit 1
fi

echo "✅ PERF GATE PASSED."
