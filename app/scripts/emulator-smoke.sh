#!/usr/bin/env bash
# Installs the APK on a running emulator, starts it, checks the screen, then installs
# an update over it. Usage:
#   emulator-smoke.sh <apk> <update-probe-apk> <out-dir> [previous-release-apk]
set -uo pipefail

APK="$1"; PROBE="$2"; OUT="$3"; PREV="${4:-}"
PKG="io.github.respawnedguy.familyapp"
mkdir -p "$OUT"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
FAILED=0
pass() { echo "OK    $1"; }
fail() { echo "FEHLER $1"; FAILED=1; }
vc() { adb shell dumpsys package "$PKG" | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2; }

echo "== Emulator API $API ($(adb shell getprop ro.build.version.release | tr -d '\r'))"
adb shell input keyevent 82 >/dev/null 2>&1 || true   # unlock screen

if [ -n "$PREV" ] && [ -f "$PREV" ]; then
  echo "-- Vorherige Release-Version installieren"
  R="$(adb install "$PREV" 2>&1)"; echo "$R"
  echo "$R" | grep -q Success && pass "vorherige Version installiert (versionCode $(vc))" || fail "vorherige Version ließ sich nicht installieren"
  echo "-- Neue Version darüber installieren (Update)"
  R="$(adb install -r "$APK" 2>&1)"; echo "$R"
  echo "$R" | grep -q Success && pass "Update von vorheriger Release-Version" || fail "Update von vorheriger Release-Version abgelehnt"
else
  R="$(adb install "$APK" 2>&1)"; echo "$R"
  echo "$R" | grep -q Success && pass "Erstinstallation" || fail "Erstinstallation abgelehnt"
fi
NEW_VC="$(vc)"; echo "installiert: versionCode $NEW_VC"

adb logcat -c
START="$(adb shell am start -W -n "$PKG/.MainActivity" 2>&1)"; echo "$START"
echo "$START" | grep -q "Status: ok" && pass "Activity gestartet" || fail "Activity-Start fehlgeschlagen"
sleep 8
adb shell pidof "$PKG" >/dev/null 2>&1 && pass "App läuft nach 8 s noch" || fail "App-Prozess beendet"
adb logcat -d > "$OUT/logcat-api$API.txt"
grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-api$API.txt" && fail "Absturz/ANR im Logcat" || pass "kein Absturz im Logcat"

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml "$OUT/ui-api$API.xml" >/dev/null 2>&1
grep -q 'text="FamilyApp"' "$OUT/ui-api$API.xml" && pass "Startbildschirm zeigt \"FamilyApp\"" || fail "Text \"FamilyApp\" nicht auf dem Bildschirm"
adb exec-out screencap -p > "$OUT/screen-api$API.png" || true

echo "-- Update-Probe (versionCode +1, gleicher Schlüssel) darüber installieren"
R="$(adb install -r "$PROBE" 2>&1)"; echo "$R"
PROBE_VC="$(vc)"
if echo "$R" | grep -q Success && [ "$PROBE_VC" -gt "$NEW_VC" ]; then pass "Update auf versionCode $PROBE_VC"; else fail "Update-Probe abgelehnt"; fi

echo
[ $FAILED -eq 0 ] && echo "ERGEBNIS API $API: alle Prüfungen bestanden" || echo "ERGEBNIS API $API: mindestens eine Prüfung fehlgeschlagen"
exit $FAILED
