#!/usr/bin/env bash
# End-to-end test of the in-app updater on a running emulator.
# Usage: emulator-update-e2e.sh <dir with e2e-100.apk e2e-101.apk e2e-102-foreign.apk> <out-dir>
# The e2e build type fetches http://10.0.2.2:8000/update.json (the runner, seen from the emulator).
set -uo pipefail

DIR="$1"; OUT="$2"
PKG="io.github.respawnedguy.familyapp"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
FAILED=0
pass() { echo "OK    $1"; }
fail() { echo "FEHLER $1"; FAILED=1; }
vc() { adb shell dumpsys package "$PKG" | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2; }

SERVE="$(mktemp -d)"
cp "$DIR"/*.apk "$SERVE/"
python3 -m http.server 8000 --bind 0.0.0.0 --directory "$SERVE" >"$OUT/http-api$API.log" 2>&1 &
HTTP_PID=$!
trap 'kill $HTTP_PID 2>/dev/null' EXIT
sleep 1

manifest() { # <versionCode> <file> [sha256-override]
  local file="$SERVE/$2"
  local sha="${3:-$(sha256sum "$file" | cut -d' ' -f1)}"
  cat > "$SERVE/update.json" <<JSON
{"versionCode":$1,"versionName":"e2e-$1","apkUrl":"http://10.0.2.2:8000/$2","sha256":"$sha","sizeBytes":$(stat -c %s "$file"),"minSdk":26,"releaseNotes":"E2E-Test"}
JSON
}

dump() { # writes $OUT/ui.xml
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

wait_text() { # <regex> <seconds> [package]
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    dump && python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "$1" "${3:-}" >/dev/null && return 0
    sleep 2
  done
  return 1
}

tap_text() { # <regex> <seconds> [package]
  wait_text "$1" "$2" "${3:-}" || return 1
  local xy; xy="$(python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "$1" "${3:-}")" || return 1
  adb shell input tap $xy
}

echo "== Update-Ende-zu-Ende-Test, Emulator API $API"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$DIR/e2e-100.apk" | grep -q Success && pass "e2e-Version 100 installiert" || fail "e2e-Version 100 nicht installiert"
# Equivalent of the user allowing "install unknown apps" for FamilyApp once.
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow

echo "-- Fall 1: falsche Prüfsumme"
manifest 101 e2e-101.apk "$(printf '0%.0s' $(seq 64))"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
tap_text "Installieren" 40 "$PKG" && pass "Update-Hinweis erscheint beim Start" || fail "kein Update-Hinweis beim Start"
wait_text "Fehler: Prüfsumme stimmt nicht.*" 60 "$PKG" && pass "falsche Prüfsumme abgelehnt" || fail "falsche Prüfsumme nicht gemeldet"
[ "$(vc)" = "100" ] && pass "nichts installiert (versionCode 100)" || fail "versionCode hat sich geändert: $(vc)"

echo "-- Fall 2: fremde Signatur"
manifest 102 e2e-102-foreign.apk
tap_text "Nach Updates suchen" 20 "$PKG"
tap_text "Installieren" 40 "$PKG" || fail "Update 102 nicht angeboten"
wait_text "Fehler: Signatur passt nicht.*" 60 "$PKG" && pass "fremde Signatur abgelehnt" || fail "fremde Signatur nicht gemeldet"
[ "$(vc)" = "100" ] && pass "nichts installiert (versionCode 100)" || fail "versionCode hat sich geändert: $(vc)"

echo "-- Fall 3: echtes Update auf 101"
manifest 101 e2e-101.apk
tap_text "Nach Updates suchen" 20 "$PKG"
tap_text "Installieren" 40 "$PKG" || fail "Update 101 nicht angeboten"
if tap_text "(?i)(update|install|aktualisieren|installieren)" 60 "packageinstaller"; then
  pass "System-Installationsdialog bestätigt"
else
  fail "System-Installationsdialog nicht gefunden"
  adb exec-out screencap -p > "$OUT/e2e-dialog-api$API.png" || true
fi
NEW=""
for _ in $(seq 30); do NEW="$(vc)"; [ "$NEW" = "101" ] && break; sleep 2; done
[ "$NEW" = "101" ] && pass "Update installiert (versionCode 101)" || fail "Update nicht installiert (versionCode $NEW)"
sleep 3
adb exec-out screencap -p > "$OUT/e2e-after-update-api$API.png" || true

grep -E '"GET [^"]+"' "$OUT/http-api$API.log" | sed 's/^/HTTP /' | tail -20
echo
[ $FAILED -eq 0 ] && echo "ERGEBNIS UPDATE-E2E API $API: alle Prüfungen bestanden" || echo "ERGEBNIS UPDATE-E2E API $API: mindestens eine Prüfung fehlgeschlagen"
exit $FAILED
