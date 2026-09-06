#!/usr/bin/env bash
# UserPromptSubmit: after a compaction, re-inject the beads rules once.
#
# This is the restore half of pre-compact.sh. It is a no-op on every prompt
# except the first one after a compaction, so the 5KB of `bd prime` is paid
# once rather than every turn.
set -u

sid=$(cat | jq -r '.session_id // "unknown"' 2>/dev/null || echo unknown)
flag="${TMPDIR:-/tmp}/claude-bd-recompact.${sid}"

[ -f "$flag" ] || exit 0

{ bd prime 2>/dev/null; cat "$flag"; } \
  | jq -Rsc '{hookSpecificOutput: {hookEventName: "UserPromptSubmit", additionalContext: .}}' \
  2>/dev/null || true

rm -f "$flag"
exit 0
