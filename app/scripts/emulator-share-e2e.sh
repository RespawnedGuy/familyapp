#!/usr/bin/env bash
# Share target end to end (quality plan P1-8, threat model docs/qualitaet/bedrohungsmodell.md,
# TE-1 to TE-4). The FamilyApp accepts "Teilen" from every app, so this test plays hostile apps:
#   - file:// link into the app's own files and a link to its own FileProvider (adb as sender)
#   - 21 MB image, an HTML file, HTML bytes that claim to be a JPEG (test app e2eShareSource,
#     which shares like a real gallery: own provider, URI grant)
# and one honest image that must arrive. Each refusal has to be said on screen, the entry text is kept.
# Runs after emulator-smoke.sh (app installed, family set up).
# Usage:
#   emulator-share-e2e.sh <share-source.apk> <out-dir>
set -uo pipefail

SOURCE_APK="$1"; OUT="$2"
PKG="io.github.respawnedguy.familyapp"
SRC="io.github.respawnedguy.e2eshare"
mkdir -p "$OUT"
export LC_ALL=C.UTF-8
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
FAILED=0
pass() { echo "OK    $1"; }
fail() { echo "FEHLER $1"; FAILED=1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
# No single adb call may hang the job: on API 26 one share call once blocked for hours.
adb() { timeout 90 adb "$@"; }  # timeout runs the adb binary, not this function
SIZE="$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)"; W="${SIZE%x*}"; H="${SIZE#*x}"
swipe_up() { adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 300; sleep 1; }
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
  while [ $SECONDS -lt $end ]; do seen "$1" && return 0; sleep 1; done
  echo "nicht gefunden: '$1' – sichtbare Texte:"
  grep -o 'text="[^"]\+"' "$OUT/ui.xml" 2>/dev/null | sort -u | head -25 | sed 's/^/    /'
  return 1
}
wait_gone() { # <regex> <seconds>: the message of the previous case must be gone before the next one
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do seen "$1" || return 0; sleep 1; done
  return 1
}
from_adb() { # <uri> <text>: adb plays an app that sends a link (no URI grant possible)
  adb shell am start -W -n "$PKG/.MainActivity" -a android.intent.action.SEND -t image/jpeg \
    --eu android.intent.extra.STREAM "$1" --es android.intent.extra.TEXT "'$2'" >/dev/null 2>&1
}
from_app() { # <kind> <text>: the test app shares one of its files with a read grant
  # Fresh task each time: a second start must not just resurface the previous share.
  adb shell am start -W --activity-clear-task -n "$SRC/.ShareSourceActivity" --es kind "$1" --es text "'$2'" >/dev/null 2>&1
}
expect() { # <label> <regex of the message>
  if wait_text "$2" 20; then pass "$1"; else fail "$1"; fi
  adb exec-out screencap -p > "$OUT/share-$(echo "$1" | tr -c 'a-zA-Z0-9' '-' | cut -c1-40)-api$API.png" || true
  wait_gone "$2" 15 || true
}

echo "== Teilen von fremden Apps (API $API)"
adb install -r "$SOURCE_APK" >/dev/null && pass "Test-App zum Teilen installiert" || { fail "Test-App nicht installierbar"; exit 1; }
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2
seen "Ausblenden" && adb shell input tap $(python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "Ausblenden" "$PKG")
adb logcat -c

NOT_OTHER="Gespeichert ohne Bild\. Es werden nur Bilder angenommen, die eine andere App teilt\."
NO_IMAGE="Gespeichert ohne Bild\. Die geteilte Datei ist kein Bild\."

from_adb "file:///data/data/$PKG/files/familyapp.json" "E2E Dateilink"
expect "file://-Link auf die eigenen Familiendaten abgelehnt" "$NOT_OTHER"

from_adb "content://$PKG.files/photos/familyapp.json" "E2E eigener Provider"
expect "Link auf den eigenen FileProvider abgelehnt" "$NOT_OTHER"

from_app big "E2E zu gross"
expect "21-MB-Bild abgelehnt" "Gespeichert ohne Bild\. Das Bild ist gr..er als 20 MB\."

from_app html "E2E HTML"
expect "HTML-Datei abgelehnt (Typ vom Anbieter)" "$NO_IMAGE"

from_app fake "E2E falsches JPEG"
expect "HTML mit Bildtyp abgelehnt (Inhalt geprüft)" "$NO_IMAGE"

from_app ok "E2E echtes Bild"
expect "echtes Bild angenommen" "Gespeichert\. Liegt oben im Eingang\."
FOUND=0
for _ in 1 2 3 4 5; do
  seen "Foto" && { FOUND=1; break; }
  swipe_up
done
[ $FOUND -eq 1 ] && pass "Foto erscheint im Eingang" || fail "Foto fehlt im Eingang"
adb exec-out screencap -p > "$OUT/screen-share-api$API.png" || true

echo "-- Kochbibliothek: geteilter Instagram-Link wird Rezeptvorschlag (TE-7)"
tap() { seen "$1" && adb shell input tap $(python3 "$HERE/ui-tap.py" "$OUT/ui.xml" "$1" "$PKG"); }
adb shell am start -W -n "$PKG/.MainActivity" -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "'https://www.instagram.com/reel/E2Etest1/?igsh=abc'" >/dev/null 2>&1
# The app then tries to fetch the caption (ADR 0007). The fake post has none, so the first message is
# soon replaced by "nicht abrufbar": either one shows the link was saved as a suggestion.
# Waits longer than expect(): two page requests with 10 s connect and read timeouts each.
LINK_SAVED="(Link als Rezeptvorschlag gespeichert|Text von Instagram nicht abrufbar)\..*"
wait_text "$LINK_SAVED" 60 && pass "Instagram-Link als Rezeptvorschlag gespeichert" || fail "Instagram-Link als Rezeptvorschlag gespeichert"
wait_gone "$LINK_SAVED" 20 || true
tap "Familie"
if wait_text "Kochbibliothek" 10 && tap "Rezepte" && wait_text "Rezept von Instagram" 10; then
  pass "Vorschlag steht in der Kochbibliothek unter Familie"
else
  fail "Vorschlag fehlt in der Kochbibliothek"
fi
tap "Rezept von Instagram"
wait_text "Zu diesem Link fehlt der Rezepttext\..*" 10 && pass "Rezept zeigt, dass der Text fehlt" || fail "Hinweis auf fehlenden Text fehlt"
adb exec-out screencap -p > "$OUT/screen-recipe-api$API.png" || true
for _ in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 1; done

adb logcat -d > "$OUT/logcat-share-api$API.txt"
grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-share-api$API.txt" && fail "Absturz/ANR beim Teilen" || pass "kein Absturz beim Teilen"
adb uninstall "$SRC" >/dev/null 2>&1 || true
echo
[ $FAILED -eq 0 ] && echo "ERGEBNIS Teilen API $API: alle Prüfungen bestanden" \
  || echo "ERGEBNIS Teilen API $API: mindestens eine Prüfung fehlgeschlagen"
exit $FAILED
