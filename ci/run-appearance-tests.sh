#!/usr/bin/env bash
set -euo pipefail

api=${1:?Android API level is required}
results_dir=${2:-appearance-results}

mkdir -p "$results_dir"
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
adb shell input keyevent 82 || true
adb shell settings put system font_scale 1.0
adb shell cmd uimode night no

run_tests() {
    ./gradlew :app:connectedDebugAndroidTest "$@" \
        -Pandroid.injected.build.abi=x86_64 \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        -Pandroid.testInstrumentationRunnerArguments.package=com.saas.x11manager.appearance
}

set +e
run_tests > "$results_dir/instrumentation.log" 2>&1
result=$?
if [ "$result" -ne 0 ]; then
    echo "API $api instrumentation failed once; retrying after clearing transient app state" \
        | tee -a "$results_dir/instrumentation.log"
    adb shell am force-stop com.saas.x11manager >/dev/null 2>&1 || true
    adb shell pm clear com.saas.x11manager >/dev/null 2>&1 || true
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb shell input keyevent 82 >/dev/null 2>&1 || true
    run_tests --rerun-tasks >> "$results_dir/instrumentation.log" 2>&1
    result=$?
fi
set -e

adb pull /sdcard/Android/data/com.saas.x11manager/files/appearance-audit "$results_dir/" || result=1
test -f "$results_dir/appearance-audit/theme-matrix.jsonl" || result=1
adb logcat -d -s AndroidRuntime > "$results_dir/android-crashes.log" || true
cat "$results_dir/instrumentation.log"
exit "$result"
