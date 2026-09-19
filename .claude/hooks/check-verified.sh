#!/usr/bin/env bash
# Stop hook: refuse to end the turn while source files were edited after the last successful Maven run.
# Second pass (stop_hook_active=true) always passes to avoid an infinite loop.
input=$(cat)
if printf '%s' "$input" | grep -q '"stop_hook_active":true'; then
  exit 0
fi
dir="${CLAUDE_PROJECT_DIR:-.}/.claude/state"
[ -f "$dir/last-edit" ] || exit 0
edit=$(cat "$dir/last-edit" 2>/dev/null || echo 0)
build=$(cat "$dir/last-build" 2>/dev/null || echo 0)
if [ "$edit" -gt "$build" ]; then
  # JSON-escape backslashes (Windows paths) and quotes
  files=$(sort -u "$dir/edited-files" 2>/dev/null | tail -n 15 | tr '\n' ' ' | sed 's/\\/\\\\/g; s/"/\\"/g')
  printf '{"decision":"block","reason":"Source files were modified but no successful Maven build/test ran afterwards (%s). Run the /verify skill (at minimum `mvn -q -pl <module> -am compile`, ideally `mvn test -pl fluxcord-core`) and fix any failure before finishing. If the change truly needs no build (docs only), say so explicitly and touch .claude/state/last-build."}\n' "$files"
  exit 2
fi
exit 0
