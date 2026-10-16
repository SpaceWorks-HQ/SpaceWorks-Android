#!/usr/bin/env bash
# Run from the repository root after android-emulator-runner has booted one emulator.
set -euo pipefail
profile="${1:?device profile required}"
case "$profile" in
  compact) size=640x1136; density=320; font=1; rotation=0; night=no ;;
  small-phone) size=720x1280; density=320; font=1; rotation=0; night=no ;;
  common-phone) size=1179x2556; density=480; font=1; rotation=0; night=no ;;
  phone) size=1080x2400; density=420; font=1; rotation=0; night=no ;;
  large) size=1440x3120; density=480; font=1.3; rotation=0; night=yes ;;
  small-tablet) size=1200x1920; density=320; font=1; rotation=0; night=no ;;
  wide-tablet) size=1680x2520; density=320; font=1; rotation=0; night=no ;;
  tablet) size=1600x2560; density=320; font=1; rotation=0; night=no ;;
  tablet-landscape) size=1600x2560; density=320; font=2; rotation=1; night=yes ;;
  large-tablet) size=2048x2732; density=320; font=2; rotation=0; night=no ;;
  *) echo "Unknown device profile: $profile" >&2; exit 2 ;;
esac
animation_scale=1
if [[ "$profile" == large-tablet ]]; then animation_scale=0; fi
mkdir -p "validation/$profile"
cleanup() {
  adb shell wm size reset || true
  adb shell wm density reset || true
  adb shell settings put system font_scale 1 || true
  adb shell settings put system accelerometer_rotation 1 || true
  adb shell cmd uimode night auto || true
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 1 || true
  done
}
trap cleanup EXIT
adb shell wm size "$size"
adb shell wm density "$density"
adb shell settings put system font_scale "$font"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation "$rotation"
adb shell cmd uimode night "$night"
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$setting" "$animation_scale"
  adb shell settings get global "$setting" >> "validation/$profile/animation-settings.txt"
done
# The fresh Google APIs emulator's launcher can hang while processing display/font changes.
# Stop only known emulator launchers before the app starts; the test window guard separately
# records/reconciles an actual launcher ANR and fails app/unknown error dialogs.
for launcher in com.google.android.apps.nexuslauncher com.android.launcher3 com.android.launcher; do
  adb shell am force-stop "$launcher" || true
done
adb shell wm size > "validation/$profile/window-size.txt"
adb shell wm density > "validation/$profile/window-density.txt"
python3 - "$profile" "$size" <<'PY'
import pathlib, re, sys
reported = pathlib.Path('validation', sys.argv[1], 'window-size.txt').read_text()
sizes = re.findall(r'(?:Physical|Override) size: (\d+x\d+)', reported)
if not sizes or sizes[-1] != sys.argv[2]:
    raise SystemExit(f'Display size was clamped: requested {sys.argv[2]}, reported {reported!r}')
PY
python3 - "$profile" "$size" "$density" "$font" "$rotation" "$night" "$animation_scale" <<'PY'
import json, pathlib, sys
keys = ['profile', 'physical_size_override', 'density', 'font_scale', 'rotation', 'night', 'animation_scale']
pathlib.Path('validation', sys.argv[1], 'configuration.json').write_text(
    json.dumps(dict(zip(keys, sys.argv[1:])), indent=2) + '\n', encoding='utf-8')
PY
# The tests own screenshot capture inside the app sandbox, while screens are visible.
# Ask AGP to collect that directory before its test cleanup uninstalls the app.
app_id=$(python3 -c 'import json; print(json.load(open("branding/brand.json"))["android_application_id"])')
result=0
./gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.deviceProfile=$profile" "-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Android/data/$app_id/files/validation" || result=$?
mkdir -p "validation/$profile/screenshots"
additional=app/build/outputs/connected_android_test_additional_output
if [[ -d "$additional" ]]; then
  while IFS= read -r -d '' screenshot; do
    cp -- "$screenshot" "validation/$profile/screenshots/$(basename "$screenshot")"
  done < <(find "$additional" -type f -name '*.png' -print0)
fi
if adb shell test -d "/sdcard/Android/data/$app_id/files/validation"; then
  adb pull "/sdcard/Android/data/$app_id/files/validation" "validation/$profile/screenshots"
fi
adb shell dumpsys window displays > "validation/$profile/display.txt"
adb shell dumpsys window windows > "validation/$profile/windows-after-tests.txt"
if [[ "$result" == 0 && -z "$(find "validation/$profile/screenshots" -type f -name '*.png' -print -quit)" ]]; then
  echo 'UI tests passed but screenshot evidence was not collected.' >&2
  result=1
fi
exit "$result"
