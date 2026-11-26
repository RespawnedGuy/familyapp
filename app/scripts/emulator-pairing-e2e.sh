#!/usr/bin/env bash
# Pairing and sync end to end: the app on the emulator invites, the house device program
# (tools/family-peer, same sync code) joins through an adb port forward, both compare the
# six digits, then entries travel both ways. Runs after emulator-smoke.sh, which leaves the
# app installed with the family "Nils" and the entry "Hausschuhe bis Donnerstag".
# Usage:
#   emulator-pairing-e2e.sh <family-peer-bin> <out-dir>
set -uo pipefail

PEER="$1"; OUT="$2"
PKG="io.github.respawnedguy.familyapp"
PORT=47420
mkdir -p "$OUT"
export LC_ALL=C.UTF-8
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
HOUSE="$OUT/house-api$API"   # contains the family key: CI scratch only, deleted at the end
rm -rf "$HOUSE"
FAILED=0
pass() { echo "OK    $1"; }
fail() { echo "FEHLER $1"; FAILED=1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
SIZE="$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)"; W="${SIZE%x*}"; H="${SIZE#*x}"
swipe_up() { adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 300; sleep 1; }
swipe_down() { adb shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 300; sleep 1; }
dump() {
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
seen() { dump && python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "$1" "$PKG" >/dev/null; }
wait_text() { # <regex> <seconds>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do seen "$1" && return 0; sleep 2; done
  echo "nicht gefunden: '$1' – sichtbare Texte:"
  grep -o 'text="[^"]\+"' "$OUT/ui.xml" 2>/dev/null | sort -u | head -25 | sed 's/^/    /'
  return 1
}
tap_text() { # <regex> <seconds>
  wait_text "$1" "$2" || return 1
  adb shell input tap $(python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "$1" "$PKG")
}
scroll_to() { # <regex>: scrolls down the page, then back up, until the text is on screen
  for dir in up up up up up up up up down down down down down down down down; do
    seen "$1" && return 0
    if [ $dir = up ]; then swipe_up; else swipe_down; fi
  done
  wait_text "$1" 2
}
finish() {
  adb forward --remove tcp:$PORT >/dev/null 2>&1 || true
  rm -rf "$HOUSE"
  echo
  [ $FAILED -eq 0 ] && echo "ERGEBNIS Kopplung API $API: alle Prüfungen bestanden" \
    || echo "ERGEBNIS Kopplung API $API: mindestens eine Prüfung fehlgeschlagen"
  exit $FAILED
}

echo "== Kopplung und Abgleich mit dem Hausgerät (API $API)"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2
seen "Ausblenden" && tap_text "Ausblenden" 3
tap_text "Einstellungen" 15 && pass "Einstellungen geöffnet" || { fail "Einstellungen nicht erreichbar"; finish; }
scroll_to "Handy koppeln" && tap_text "Handy koppeln" 5 && pass "Kopplung gestartet" || { fail "\"Handy koppeln\" fehlt"; finish; }
wait_text "QR-Code zum Koppeln" 10 && pass "QR-Code wird gezeigt" || fail "QR-Code fehlt"
adb exec-out screencap -p > "$OUT/pairing-qr-api$API.png" || true
tap_text "Code als Text zeigen" 10
CODE=""
for _ in 1 2 3 4 5; do
  dump && CODE="$(grep -o 'FAMILYAPP1:[A-Za-z0-9_=-]*' "$OUT/ui.xml" | head -1)"
  [ -n "$CODE" ] && break
  swipe_up
done
[ -n "$CODE" ] && pass "Code als Text lesbar (${#CODE} Zeichen)" || { fail "Code nicht als Text gefunden"; finish; }

adb forward tcp:$PORT tcp:$PORT >/dev/null
"$PEER" join "$HOUSE" "$CODE" --name "CI-Hausgeraet" --via 127.0.0.1:$PORT --yes > "$OUT/peer-join-api$API.txt" 2>&1 &
JOIN_PID=$!
wait_text "Stimmen die Zahlen.*" 30 && pass "App fragt nach der Prüfzahl" || fail "Prüfzahl-Frage fehlt in der App"
adb exec-out screencap -p > "$OUT/screen-confirm-api$API.png" || true
APP_SAS="$(grep -o 'Prüfzahl [0-9 ]*' "$OUT/ui.xml" | head -1 | tr -cd '0-9')"
PEER_SAS=""
for _ in $(seq 1 15); do
  PEER_SAS="$(grep -o 'Prüfzahl: [0-9 ]*' "$OUT/peer-join-api$API.txt" | head -1 | tr -cd '0-9')"
  [ -n "$PEER_SAS" ] && break
  sleep 1
done
echo "Prüfzahl App: $APP_SAS, Hausgerät: $PEER_SAS"
if [ -n "$APP_SAS" ] && [ ${#APP_SAS} -eq 6 ] && [ "$APP_SAS" = "$PEER_SAS" ]; then
  pass "beide Seiten zeigen dieselbe sechsstellige Prüfzahl"
else
  fail "Prüfzahlen fehlen oder unterscheiden sich"
fi
seen "Hausgerät \(keine Person\)" && pass "Hausgerät als Besitzer angeboten" || fail "Auswahl \"Hausgerät\" fehlt"
# Small screens: the buttons sit below the owner choices.
scroll_to "Stimmt" && tap_text "Stimmt" 5
wait "$JOIN_PID"; JOIN_EXIT=$?
cat "$OUT/peer-join-api$API.txt"
[ $JOIN_EXIT -eq 0 ] && pass "Hausgerät ist gekoppelt" || fail "Hausgerät meldet Fehler beim Koppeln (Exit $JOIN_EXIT)"
wait_text "Gekoppelt" 15 && pass "App meldet \"Gekoppelt\"" || fail "App zeigt kein \"Gekoppelt\""
scroll_to "Fertig" && tap_text "Fertig" 5
scroll_to "(?!.*möchte).*CI-Hausgeraet.*" && pass "Hausgerät steht in der Geräteliste" || fail "Hausgerät fehlt in der Geräteliste"

echo "-- Abgleich in beide Richtungen"
"$PEER" add "$HOUSE" "Turnbeutel packen" >/dev/null
"$PEER" sync "$HOUSE" --via 127.0.0.1:$PORT 2>&1 | tee "$OUT/peer-sync-api$API.txt"
"$PEER" show "$HOUSE" 2>&1 | tee "$OUT/peer-show-api$API.txt"
grep -qE "Hausschuhe b.s Donnerstag" "$OUT/peer-show-api$API.txt" && pass "Eintrag der App ist auf dem Hausgerät" || fail "Eintrag der App fehlt auf dem Hausgerät"
# Restart: the synced entry must also have been saved, and the app opens on "Heute".
sleep 4
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2
seen "Ausblenden" && tap_text "Ausblenden" 3
wait_text "Turnbeutel packen.*" 20 && pass "Eintrag vom Hausgerät erscheint in der App" || fail "Eintrag vom Hausgerät fehlt in der App"
adb exec-out screencap -p > "$OUT/screen-synced-api$API.png" || true
adb logcat -d > "$OUT/logcat-pairing-api$API.txt"
grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-pairing-api$API.txt" && fail "Absturz/ANR beim Koppeln" || pass "kein Absturz beim Koppeln"
finish
