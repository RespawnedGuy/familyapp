#!/usr/bin/env bash
# Quality plan P1-4: runs LargeFontScreensTest (shared/src/androidInstrumentedTest) on a running
# emulator: every screen at 100 % and 200 % font size, light and dark, with screenshots, text
# overflow checks and (Android 14+) the Accessibility Test Framework.
# Usage: emulator-font-scale.sh <shared-androidTest-apk> <out-dir>
set -uo pipefail

APK="$1"; OUT="$2"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
DIR="$OUT/schrift-api$API"
mkdir -p "$DIR"

R="$(adb install -r -t "$APK" 2>&1)"; echo "$R"
echo "$R" | grep -q Success || { echo "FEHLER Test-APK ließ sich nicht installieren"; exit 1; }
RUNNER="$(adb shell pm list instrumentation | tr -d '\r' | grep -o 'io\.github\.respawnedguy\.familyapp\.shared\.test/[^ ]*' | head -1)"
[ -n "$RUNNER" ] || { echo "FEHLER Instrumentierung nicht gefunden"; adb shell pm list instrumentation; exit 1; }
PKG="${RUNNER%%/*}"
echo "== Große Schrift, API $API, $RUNNER"

adb logcat -c
adb shell am instrument -w -e class io.github.respawnedguy.familyapp.LargeFontScreensTest "$RUNNER" > "$DIR/instrument-raw.txt" 2>&1
adb logcat -d > "$DIR/logcat.txt"

# Screenshots and per-screen reports from the test app's own folder.
adb pull "/sdcard/Android/data/$PKG/files/schrift/." "$DIR/" >/dev/null 2>&1 || \
  adb exec-out run-as "$PKG" sh -c 'cd files/schrift && tar cf - .' | tar xf - -C "$DIR" 2>/dev/null || true
cat "$DIR"/*-schrift*.txt > "$DIR/befunde.txt" 2>/dev/null || true
echo
# Only screens with findings, each under its "== name" line.
awk '/^==/{h=$0; next} /^(FEHLER|HINWEIS)/{if(h){print h; h=""} print}' "$DIR/befunde.txt"
echo
SHOTS="$(ls "$DIR"/*.png 2>/dev/null | wc -l)"
echo "Screenshots: $SHOTS (Artefakt emulator-api$API, Ordner schrift-api$API)"
# "OK (n tests)" only appears when every test passed.
if grep -q '^OK (' "$DIR/instrument-raw.txt"; then
  echo "ERGEBNIS Große Schrift API $API: $(grep '^OK (' "$DIR/instrument-raw.txt")"
  exit 0
fi
grep -E '^(Tests run|FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg|Process crashed)' "$DIR/instrument-raw.txt" || tail -30 "$DIR/instrument-raw.txt"
echo "ERGEBNIS Große Schrift API $API: mindestens eine Prüfung fehlgeschlagen"
exit 1
