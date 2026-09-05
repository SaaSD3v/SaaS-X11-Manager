#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

roots=(
  app/src/main/java
  embedded-lorie/src/main/java
  embedded-lorie/build.gradle
)

for root in "${roots[@]}"; do
  [ -e "$root" ] || {
    echo "::error::Missing source path: $root"
    exit 1
  }
done

# These identifiers and phrases belong to the retired dynamic-display design.
# The X11-0nly production source must model only the fixed :0 / X0 transport.
forbidden=(
  'X11DisplaySlot'
  'X11DisplayAllocator'
  'X11MonitorInfo'
  'getMonitors'
  'selectDisplaySlot'
  'displaySlotFromBindMounts'
  'PREF_KNOWN_MONITOR_SLOTS'
  'EXTRA_X11_DISPLAY'
  'selectedDisplay'
  'selectDisplay('
  'multi-display'
  'monitor slots'
  'per-display'
  'display-keyed'
  'several displays'
  'several X servers'
  'multiple X11 displays'
  'saas-x11-0'
  'saas-x11-1'
  '/display-0/'
  '/display-1/'
  'CmdEntryPoint :1'
  'CmdEntryPoint :2'
)

failed=0
for token in "${forbidden[@]}"; do
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    echo "::error::Retired dynamic-display token '$token' remains: $hit"
    failed=1
  done < <(grep -RFn -- "$token" "${roots[@]}" 2>/dev/null || true)
done

# Positive contract: the fixed runtime constants must remain explicit.
grep -Fq 'const val X11_DISPLAY = ":0"' app/src/main/java/com/saas/x11manager/util/Constants.kt
grep -Fq 'const val X11_SERVER_PROCESS = "saas-x11"' app/src/main/java/com/saas/x11manager/util/Constants.kt
grep -Fq 'const val X11_SOCK_FILE = "$X11_SOCK_DIR/X0"' app/src/main/java/com/saas/x11manager/util/Constants.kt

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "X11-0nly source audit passed: fixed display :0 / X0 only."
