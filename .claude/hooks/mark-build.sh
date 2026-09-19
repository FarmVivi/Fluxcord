#!/usr/bin/env bash
# PostToolUse hook (Bash): when a Maven build/test command succeeded, record it as the last verified build.
input=$(cat)
cmd=$(printf '%s' "$input" | grep -o '"command":"[^"]*"' | head -1)
case "$cmd" in
  *mvn*|*mvnw*|*build.sh*) ;;
  *) exit 0 ;;
esac
# PostToolUse only fires on tool success, but a Maven failure can still come back as text: double-check.
if printf '%s' "$input" | grep -q 'BUILD FAILURE\|COMPILATION ERROR\|Tests run:.*Failures: [1-9]\|Tests run:.*Errors: [1-9]'; then
  exit 0
fi
dir="${CLAUDE_PROJECT_DIR:-.}/.claude/state"
mkdir -p "$dir"
date +%s > "$dir/last-build"
rm -f "$dir/edited-files"
exit 0
