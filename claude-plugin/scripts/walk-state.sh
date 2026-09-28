#!/usr/bin/env bash
# UserPromptSubmit hook: tell Claude where the user is in the Code Walk IDE tool window.
# The IDE plugin writes ~/.code-walk/state.line as "<project>\t<message>" on every step change.
# Pure bash on purpose — runs on every prompt, so no interpreter start-up.
STATE="$HOME/.code-walk/state.line"
[ -f "$STATE" ] || exit 0

# Ignore stale state (older than 12h): the user probably closed the walk.
now=$(date +%s)
if stat -f %m "$STATE" >/dev/null 2>&1; then mtime=$(stat -f %m "$STATE"); else mtime=$(stat -c %Y "$STATE"); fi
[ $((now - mtime)) -lt 43200 ] || exit 0

# `read` returns non-zero at EOF even when it filled the variables (file without a trailing
# newline), so test the variables, not the exit status.
IFS=$'\t' read -r proj msg < "$STATE" || true
[ -n "$msg" ] || exit 0

# Only speak up when the walk belongs to this project (either root may contain the other,
# since Rider opens the .sln directory and Claude runs at the git root).
here="${CLAUDE_PROJECT_DIR:-$PWD}"; here="${here%/}"; proj="${proj%/}"
if [ -n "$proj" ] && [ "$here" != "$proj" ]; then
  case "$here/" in "$proj/"*) ;; *) case "$proj/" in "$here/"*) ;; *) exit 0 ;; esac ;; esac
fi

printf '%s\n' "$msg"
