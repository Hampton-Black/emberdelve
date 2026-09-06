#!/usr/bin/env bash
# PreCompact: record which beads issues were claimed, for the next turn to pick up.
#
# Compaction events cannot inject context — the runtime rejects hookSpecificOutput
# with hookEventName "PreCompact" or "PostCompact" (only PreToolUse, UserPromptSubmit,
# SessionStart and friends are accepted). So this writes a note to disk and
# user-prompt-submit.sh injects it on the first prompt after the compaction.
set -u

sid=$(cat | jq -r '.session_id // "unknown"' 2>/dev/null || echo unknown)
flag="${TMPDIR:-/tmp}/claude-bd-recompact.${sid}"

{ bd list --status=in_progress --json 2>/dev/null || echo '[]'; } \
  | jq -r 'if length > 0
           then "Beads issues claimed before the compaction and still in progress: "
                + ([.[] | .id + " (" + .title + ")"] | join("; "))
           else "" end' \
  > "$flag" 2>/dev/null || : > "$flag"

exit 0
