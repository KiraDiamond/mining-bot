#!/usr/bin/env bash
set -euo pipefail

ROOT="${ROOT:-$HOME/baritone-task-client}"
PRISM_DIR="${PRISM_DIR:-$ROOT/prism}"
INSTANCE_ID="${INSTANCE_ID:-baritone-26.2-native}"
SERVER="${1:-${SERVER:-209.25.141.24:1306}}"
BOT_ID="${TASK_BOT_ID:-default}"
LAUNCH_LOG="${TASK_LAUNCH_LOG:-$ROOT/logs/prism-headless-${BOT_ID}.log}"
PROFILE_ARG=()

if [[ "${PROFILE:-}" != "" ]]; then
  PROFILE_ARG=(--profile "$PROFILE")
fi

mkdir -p "$ROOT/logs"
exec xvfb-run -a -s "-screen 0 1280x720x24" prismlauncher \
  --dir "$PRISM_DIR" \
  --launch "$INSTANCE_ID" \
  --server "$SERVER" \
  "${PROFILE_ARG[@]}" \
  >"$LAUNCH_LOG" 2>&1
