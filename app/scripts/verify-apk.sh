#!/usr/bin/env bash
# Checks an APK for the usual sideloading pitfalls and prints a report.
# Usage: verify-apk.sh <apk> <expected-version-code> [expected-cert-sha256-file]
# Exit code != 0 if any check fails.
set -uo pipefail

APK="$1"
EXPECTED_VC="$2"
CERT_FILE="${3:-}"
PKG="io.github.respawnedguy.familyapp"
MIN_SDK="26"
TARGET_SDK="36"

BT="${ANDROID_HOME:?ANDROID_HOME not set}/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
FAILED=0
pass() { echo "OK    $1"; }
fail() { echo "FEHLER $1"; FAILED=1; }

echo "== Datei"
echo "Datei: $(basename "$APK")  Größe: $(stat -c %s "$APK") Bytes"
echo "SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)"
echo "Build-Tools: $BT"
[ "$(head -c 2 "$APK")" = "PK" ] && pass "ZIP-Signatur (PK) vorhanden" || fail "Datei ist kein ZIP/APK"
unzip -tq "$APK" >/dev/null 2>&1 && pass "ZIP vollständig und unbeschädigt" || fail "ZIP beschädigt oder abgeschnitten"
unzip -l "$APK" AndroidManifest.xml classes.dex >/dev/null 2>&1 && pass "AndroidManifest.xml und classes.dex enthalten" || fail "Manifest oder classes.dex fehlt"

echo
echo "== Signatur (apksigner)"
SIG="$("$BT/apksigner" verify --verbose --print-certs "$APK" 2>&1)"; SIG_RC=$?
echo "$SIG" | grep -vE '^WARNING: META-INF' 
[ $SIG_RC -eq 0 ] && pass "apksigner verify erfolgreich" || fail "apksigner verify fehlgeschlagen"
grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$SIG" && pass "Signaturschema v2" || fail "Signaturschema v2 fehlt"
grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" <<<"$SIG" && pass "Signaturschema v3" || fail "Signaturschema v3 fehlt"
CERT="$(echo "$SIG" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')"
echo "Zertifikat SHA-256: $CERT"
grep -q "CN=Android Debug" <<<"$SIG" && fail "mit Android-Debug-Schlüssel signiert" || pass "kein Android-Debug-Schlüssel"
if [ -n "$CERT_FILE" ]; then
  EXPECTED_CERT="$(grep -vE '^\s*(#|$)' "$CERT_FILE" | head -1 | tr -d '[:space:]:' | tr 'A-F' 'a-f')"
  [ "$CERT" = "$EXPECTED_CERT" ] && pass "Zertifikat = fester Release-Schlüssel (Updates möglich)" || fail "Zertifikat weicht vom Release-Schlüssel ab ($EXPECTED_CERT)"
fi

echo
echo "== Ausrichtung (zipalign, 16-KB-Seiten)"
"$BT/zipalign" -c -P 16 4 "$APK" && pass "zipalign 4 / 16 KB" || fail "zipalign-Prüfung fehlgeschlagen"

echo
echo "== Manifest (aapt2 dump badging)"
BADGING="$("$BT/aapt2" dump badging "$APK" 2>&1)"
echo "$BADGING" | grep -vE "^application-label-|^locales:|^densities:"
grep -q "^package: name='$PKG'" <<<"$BADGING" && pass "Paketname $PKG" || fail "Paketname ist nicht $PKG"
grep -q "versionCode='$EXPECTED_VC'" <<<"$BADGING" && pass "versionCode $EXPECTED_VC" || fail "versionCode ist nicht $EXPECTED_VC"
grep -qE "^(min)?[sS]dkVersion:'$MIN_SDK'" <<<"$BADGING" && pass "minSdk $MIN_SDK (Android 8.0)" || fail "minSdk ist nicht $MIN_SDK"
grep -q "^targetSdkVersion:'$TARGET_SDK'" <<<"$BADGING" && pass "targetSdk $TARGET_SDK" || fail "targetSdk ist nicht $TARGET_SDK"
grep -q "^launchable-activity:" <<<"$BADGING" && pass "startbare Activity vorhanden (App-Symbol erscheint)" || fail "keine startbare Activity"
grep -q "application-debuggable" <<<"$BADGING" && fail "APK ist debuggable" || pass "nicht debuggable"
grep -qi "testOnly" <<<"$BADGING" && fail "APK ist testOnly (Installation wird abgelehnt)" || pass "nicht testOnly"
if grep -q "^native-code:" <<<"$BADGING"; then
  grep "^native-code:" <<<"$BADGING" | grep -q "arm64-v8a" && pass "native Bibliotheken enthalten arm64-v8a" || fail "native Bibliotheken ohne arm64-v8a"
else
  pass "keine nativen Bibliotheken (läuft auf jeder CPU-Architektur)"
fi


echo
echo "== Netzwerk (Manifest)"
XMLTREE="$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" 2>&1)"
grep -qE "networkSecurityConfig|usesCleartextTraffic" <<<"$XMLTREE" \
  && fail "Manifest lockert die Netzwerksicherheit (nur im e2e-Testbuild erlaubt)" \
  || pass "nur HTTPS (keine Ausnahme für unverschlüsseltes HTTP)"

echo
echo "== Sicherung und Umzug (Manifest, Bedrohungsmodell GE-2)"
grep -qE "allowBackup.*=(false|\(type 0x12\)0x0)" <<<"$XMLTREE" \
  && pass "keine Cloud-Sicherung des App-Speichers (allowBackup=false)" || fail "allowBackup ist nicht false"
grep -q "dataExtractionRules" <<<"$XMLTREE" \
  && pass "Regeln gegen Kopie auf ein neues Handy (dataExtractionRules)" || fail "dataExtractionRules fehlen"

echo
echo "== Kontakte ohne Adressbuch-Berechtigung (Bedrohungsmodell KT-2, KT-3)"
for PERM in READ_CONTACTS WRITE_CONTACTS CALL_PHONE; do
  grep -q "uses-permission: name='android.permission.$PERM'" <<<"$BADGING" \
    && fail "Berechtigung $PERM im Manifest (Kontakte nur über Systemauswahl und Teilen)" \
    || pass "keine Berechtigung $PERM"
done

echo
[ $FAILED -eq 0 ] && echo "ERGEBNIS: alle Prüfungen bestanden" || echo "ERGEBNIS: mindestens eine Prüfung fehlgeschlagen"
exit $FAILED
