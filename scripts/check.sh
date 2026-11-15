#!/usr/bin/env bash
# check (multi-module) — Android Lint (NewApi) on every module + ktlint on
# every module's Kotlin sources, run on the Termux build phone.
#
# Multi-module port of the global `check` command: the global one assumes a
# single-module layout (:app only) and would silently skip the library
# modules. This script mirrors its flow (adb sync -> ssh -> live output) but
# runs `./gradlew lintDebug` across all modules and ktlint over all module
# sources. The PC is only the editor; everything heavy runs on the phone.
#
# Usage:
#   scripts/check.sh                 # check everything on the phone
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR_NAME="$(basename "$ROOT")"
SRC="$ROOT"

if ! command -v adb >/dev/null 2>&1; then
    for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        if [ -n "$d" ] && [ -x "$d/platform-tools/adb" ]; then
            PATH="$d/platform-tools:$PATH"
            break
        fi
    done
fi
command -v adb >/dev/null 2>&1 || { echo "error: adb not found" >&2; exit 1; }

BUILD="${BUILD_SERIAL:-}"
if [ -z "$BUILD" ]; then
    DEV_SERIALS=()
    while IFS= read -r line; do
        serial="${line%%[[:space:]]*}"
        state="$(printf '%s\n' "$line" | awk '{print $2}')"
        [ "$state" = "device" ] || continue
        case "$serial" in emulator-*) ;; *) DEV_SERIALS+=("$serial") ;; esac
    done < <(adb devices -l | tail -n +2)
    if [ "${#DEV_SERIALS[@]}" -eq 1 ]; then
        BUILD="${DEV_SERIALS[0]}"
    else
        echo "error: multiple/no physical adb devices; set BUILD_SERIAL" >&2
        exit 1
    fi
fi

TERMUX_USER="${TERMUX_USER:-u0_a423}"
TERMUX_PORT="${TERMUX_PORT:-8022}"
TERMUX_HOST="${TERMUX_HOST:-}"
if [ -z "$TERMUX_HOST" ]; then
    case "$BUILD" in
        *:*) TERMUX_HOST="${BUILD%%:*}" ;;
        *)
            TERMUX_HOST="127.0.0.1"
            adb -s "$BUILD" forward "tcp:$TERMUX_PORT" "tcp:$TERMUX_PORT" >/dev/null
            ;;
    esac
fi
if [ -z "${TERMUX_KEY:-}" ]; then
    for k in "$HOME/.ssh/termux_phone" "$HOME/.ssh/id_ed25519"; do
        [ -f "$k" ] && TERMUX_KEY="$k" && break
    done
fi
[ -n "${TERMUX_KEY:-}" ] || { echo "error: no SSH key; set TERMUX_KEY" >&2; exit 1; }
SSH=(ssh -i "$TERMUX_KEY" -p "$TERMUX_PORT" -o ConnectTimeout=10 "$TERMUX_USER@$TERMUX_HOST")

STAGE="/sdcard/Download"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/check-on-phone.XXXXXX")"
TMP="$TMP_DIR/$PROJECT_DIR_NAME-check.tar.gz"
EXCLUDE_FILE="$TMP_DIR/excludes"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "[1/3] Packing source..."
printf '%s\n' \
    "$PROJECT_DIR_NAME/app/build" \
    "$PROJECT_DIR_NAME/build" \
    "$PROJECT_DIR_NAME/.gradle" \
    "$PROJECT_DIR_NAME/local.properties" \
    "$PROJECT_DIR_NAME/.idea" \
    "$PROJECT_DIR_NAME/screenshots" \
    "$PROJECT_DIR_NAME/.kotlin" \
    "$PROJECT_DIR_NAME/.git" \
    "$PROJECT_DIR_NAME/*/rust/target" \
    "$PROJECT_DIR_NAME/*/build" >"$EXCLUDE_FILE"
tar --exclude-from="$EXCLUDE_FILE" -czf "$TMP" -C "$(dirname "$SRC")" "$PROJECT_DIR_NAME"
echo "      archive: $(du -h "$TMP" | awk '{print $1}')"

echo "[2/3] Syncing to phone..."
adb -s "$BUILD" shell mkdir -p "$STAGE" >/dev/null 2>&1 || true
adb -s "$BUILD" push "$TMP" "$STAGE/$PROJECT_DIR_NAME-check.tar.gz" >/dev/null
"${SSH[@]}" bash -s -- "$STAGE/$PROJECT_DIR_NAME-check.tar.gz" <<'REMOTE'
archive="$1"
cd "$HOME" || exit 1
tar -xzf "$archive" || exit 1
rm -f "$archive"
printf '      source synced to %s\n' "$HOME"
REMOTE

echo "[3/3] Running checks on the phone..."
"${SSH[@]}" bash -s -- "$PROJECT_DIR_NAME" <<'REMOTE'
set -euo pipefail
PROJECT_DIR_NAME="$1"
cd "$HOME/$PROJECT_DIR_NAME"
chmod +x ./gradlew
[ -f local.properties ] || printf 'sdk.dir=%s\n' "$HOME/android-sdk" > local.properties

printf '\n>>> Android Lint (NewApi, all modules)\n'
./gradlew lintDebug --configuration-cache --console=plain

printf '\n>>> ktlint (all module sources)\n'
KTLINT_BIN="$HOME/.cache/check-on-phone/ktlint-1.8.0"
if [ ! -x "$KTLINT_BIN" ]; then
    mkdir -p "$HOME/.cache/check-on-phone"
    curl -fsSL https://github.com/pinterest/ktlint/releases/download/1.8.0/ktlint -o "$KTLINT_BIN"
    chmod +x "$KTLINT_BIN"
fi
mapfile -d '' files < <(find . -path ./.git -prune -o -path '*/build' -prune -o -path '*/target' -prune -o -name '*.kt' -print0)
if [ "${#files[@]}" -gt 0 ]; then
    "$KTLINT_BIN" "${files[@]}"
fi
echo
echo "Checks passed."
REMOTE
