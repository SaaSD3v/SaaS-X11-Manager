#!/bin/sh
# SaaS DroidSpaces Audio Auto v3.0.2 FINAL RELEASE
# Modern native DroidSpaces PulseAudio bridge with automatic TCP fallback.
# Run from the normal Termux shell (NOT from su/root).

set -u

VERSION="3.0.2"
ACTION="setup"
MODE="auto"                 # auto | native | tcp
TARGET_CONTAINER=""
APPLY_ALL=0
DO_TEST=0
RESTORE_STATE=0
RESTART_GUI=1
INSTALL_BOOT=0
QUIET=0
PORT="4713"
LISTEN="127.0.0.1"

MANAGED="SaaS DroidSpaces Audio Auto"
STATE_DIR_NAME=".saas-droidspaces-audio"
SESSION_ID="$(date +%s 2>/dev/null || printf '0').$$"

# This file is intentionally generated from the audited v3.0.2 helper. The
# complete helper is installed as an APK asset and integrity-checked by
# PulseAudioFixManager before execution.
#
# Placeholder guard: packaging/tests must replace this short source with the
# full audited helper whose SHA-256 is validated by PulseAudioFixManager.
printf '%s\n' '[-] Embedded PulseAudio helper payload is incomplete' >&2
exit 90
