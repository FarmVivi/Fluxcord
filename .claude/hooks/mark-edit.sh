#!/usr/bin/env bash
# PostToolUse hook (Edit|Write): remember that a source file changed since the last verified build.
# Input: hook JSON on stdin. No jq dependency (Git Bash on Windows).
input=$(cat)
file=$(printf '%s' "$input" | grep -o '"file_path":"[^"]*"' | head -1 | sed 's/"file_path":"//; s/"$//')
case "$file" in
  *.java|*.kt|*pom.xml|*.yml|*.yaml|*.properties|*.sql)
    dir="${CLAUDE_PROJECT_DIR:-.}/.claude/state"
    mkdir -p "$dir"
    date +%s > "$dir/last-edit"
    printf '%s\n' "$file" >> "$dir/edited-files"
    ;;
esac
exit 0
