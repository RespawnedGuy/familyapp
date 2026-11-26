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
HERE="$(cd "$(dirname "$0")" && pwd)"
dump() { # writes $OUT/ui.xml
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
wait_text() { # <regex> <seconds>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    dump && python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "$1" "$PKG" >/dev/null && return 0
    sleep 2
  done
  echo "nicht gefunden: '$1' – sichtbare Texte:"
  grep -o 'text="[^"]\+"' "$OUT/ui.xml" 2>/dev/null | sort -u | head -25 | sed 's/^/    /'
  return 1
}
tap_text() { # <regex> <seconds>
  wait_text "$1" "$2" || return 1
  local xy; xy="$(python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "$1" "$PKG")" || return 1
  adb shell input tap $xy
}

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

echo "-- Kernweg: Familie anlegen, per Text erfassen, Karte mit einem Tap bestätigen, Neustart"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
if tap_text "@EditText" 20; then
  sleep 1; adb shell input text "Nils"; sleep 1
  # Enter = "Fertig" on the keyboard. On small screens the open keyboard can cover the button.
  adb shell input keyevent 66; sleep 2
  dump && python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "Los geht.s" "$PKG" >/dev/null && tap_text "Los geht.s" 5
  wait_text "Heute" 10 && pass "Familie angelegt (Onboarding)" || fail "Onboarding abgeschlossen, aber \"Heute\" fehlt"
else
  fail "Onboarding-Feld fehlt"
fi
wait_text "Alles sortiert.*" 15 && pass "leerer Eingang zeigt \"Alles sortiert\"" || fail "leerer Eingang nicht sichtbar"
tap_text "Erfassen" 10 && pass "Erfassen geöffnet" || fail "Erfassen-Knopf fehlt"
tap_text "Tippen" 15 && pass "Wechsel zu Tippen im Erfassen-Blatt" || fail "\"Tippen\" im Erfassen-Blatt fehlt"
# The Android 8 keyboard autocorrects "bis" to "bus" after the space; the title check allows that.
sleep 2; adb shell input text "Hausschuhe%sbis%sDonnerstag"; sleep 1
adb shell input keyevent 66; sleep 2
if dump && python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "Speichern" "$PKG" >/dev/null; then tap_text "Speichern" 5; fi
wait_text "Hausschuhe b.s Donnerstag.*" 10 && pass "Eintrag liegt im Eingang" || fail "Eintrag nicht im Eingang"
wait_text ".*bis (Morgen|Do [0-9]{2}\.[0-9]{2}\.).*" 5 && pass "Regel hat das Datum vorgeschlagen" || fail "Datumsvorschlag fehlt"
adb exec-out screencap -p > "$OUT/screen-inbox-api$API.png" || true
tap_text "Mach ich" 10 && pass "Karte mit einem Tap bestätigt" || fail "\"Mach ich\" fehlt"
wait_text "Alles sortiert.*" 10 && pass "Eingang danach leer" || fail "Eingang nicht leer"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
wait_text "Hausschuhe b.s Donnerstag.*" 20 && pass "Eintrag übersteht Neustart (unter \"Meins\")" || fail "Eintrag nach Neustart weg"
adb exec-out screencap -p > "$OUT/screen-today-api$API.png" || true
adb logcat -d > "$OUT/logcat-core-api$API.txt"
grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-core-api$API.txt" && fail "Absturz/ANR im Kernweg" || pass "kein Absturz im Kernweg"

echo "-- Update-Probe (versionCode +1, gleicher Schlüssel) darüber installieren"
R="$(adb install -r "$PROBE" 2>&1)"; echo "$R"
PROBE_VC="$(vc)"
if echo "$R" | grep -q Success && [ "$PROBE_VC" -gt "$NEW_VC" ]; then pass "Update auf versionCode $PROBE_VC"; else fail "Update-Probe abgelehnt"; fi
# Quality plan P0-7: data written before an update must still be there afterwards.
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
wait_text "Hausschuhe b.s Donnerstag.*" 20 && pass "Eintrag übersteht das Update" || fail "Eintrag nach Update weg"

echo
[ $FAILED -eq 0 ] && echo "ERGEBNIS API $API: alle Prüfungen bestanden" || echo "ERGEBNIS API $API: mindestens eine Prüfung fehlgeschlagen"
exit $FAILED
