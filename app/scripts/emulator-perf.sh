#!/usr/bin/env bash
# Performance measurement on the emulator (quality plan P1-10). Runs after emulator-smoke.sh,
# which installed the app and created a family. Usage:
#   emulator-perf.sh <out-dir> [runs]
#
# Measures with two independent instruments and compares them:
#   1. Android itself: `am start -W` → TotalTime (launch until the first frame is drawn).
#   2. The app: its own measurement (ColdStartProbe, AppModel), logged as "FamilyPerf" lines.
# The app's numbers are the ones Nils sees on his phone (Einstellungen → Messwerte), so this
# job checks that they agree with Android's. Emulator times are NOT the reference: the CI
# emulator renders in software and shares a busy runner. They are reported, not judged.
#
# Fails only if the instrument is broken: no in-app measurement, in-app and Android disagree
# grossly, or the app crashes. Writes $OUT/leistung-api<API>.csv and a Markdown summary.
set -uo pipefail

OUT="$1"; RUNS="${2:-10}"
PKG="io.github.respawnedguy.familyapp"
mkdir -p "$OUT"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
CSV="$OUT/leistung-api$API.csv"
FAILED=0
pass() { echo "OK    $1"; }
fail() { echo "FEHLER $1"; FAILED=1; }
info() { echo "INFO  $1"; }

# Last in-app value of a metric since the last `logcat -c` (empty if none). Waits up to $2 s.
app_value() { # <metric> <seconds>
  local end=$((SECONDS + $2)) v=""
  while [ $SECONDS -lt $end ]; do
    v="$(adb logcat -d -s FamilyPerf:I 2>/dev/null | grep "metric=$1 " | tail -1 | sed -n 's/.*ms=\([0-9]*\).*/\1/p')"
    [ -n "$v" ] && { echo "$v"; return 0; }
    sleep 1
  done
  return 1
}

cold_start() { # <label> [extra am args...] → prints "<android TotalTime> <app COLD_START> <app CAPTURE_READY>"
  local label="$1"; shift
  adb shell am force-stop "$PKG"
  sleep 2
  adb logcat -c
  local res total app cap
  res="$(adb shell am start -W "$@" -n "$PKG/.MainActivity" 2>&1 | tr -d '\r')"
  total="$(echo "$res" | sed -n 's/^TotalTime: *\([0-9]*\).*/\1/p')"
  app="$(app_value COLD_START 8 || true)"
  cap=""
  [ "$label" = "Erfassen" ] && cap="$(app_value CAPTURE_READY 6 || true)"
  # "-" keeps the three columns in place when a value is missing.
  echo "${total:--} ${app:--} ${cap:--}"
}

echo "== Leistung im Emulator, API $API ($(adb shell getprop ro.build.version.release | tr -d '\r')), $RUNS Läufe je Weg"
adb shell input keyevent 82 >/dev/null 2>&1 || true
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
echo "path,run,android_total_ms,app_cold_start_ms,app_capture_ready_ms" > "$CSV"

echo "-- Aufwärmen (erster Start nach der Installation zählt nicht)"
cold_start Symbol >/dev/null

for path in Symbol Erfassen; do
  echo "-- Kaltstart über \"$path\""
  for i in $(seq 1 "$RUNS"); do
    if [ "$path" = "Erfassen" ]; then
      row="$(cold_start Erfassen -a "$PKG.CAPTURE" --es trigger CI)"
    else
      row="$(cold_start Symbol)"
    fi
    read -r total app cap <<< "$row"
    [ "$total" = "-" ] && total=""; [ "$app" = "-" ] && app=""; [ "$cap" = "-" ] && cap=""
    echo "   Lauf $i: Android ${total:-?} ms · App ${app:-?} ms${cap:+ · bis Aufnahme läuft $cap ms}"
    # First run of each path: show what Android and the app logged, to explain any gap between them.
    if [ "$i" = 1 ]; then
      adb logcat -d 2>/dev/null | tr -d '\r' | grep -E "FamilyPerf|Displayed|Fully drawn|Start proc .*$PKG" | sed 's/^/      | /' | head -12
    fi
    echo "$path,$i,${total:-},${app:-},${cap:-}" >> "$CSV"
  done
done

adb logcat -d > "$OUT/logcat-perf-api$API.txt"
grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-perf-api$API.txt" && fail "Absturz/ANR während der Messung" || pass "kein Absturz während $((2 * RUNS + 1)) Kaltstarts"

# Memory right after a cold start (PSS, what Android shows as the app's memory use).
adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 5
PSS="$(adb shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' | awk '/TOTAL PSS:/ {print $3; exit} /^ *TOTAL +[0-9]/ {print $2; exit}')"
APK_BYTES="$(adb shell pm path "$PKG" 2>/dev/null | tr -d '\r' | head -1 | sed 's/^package://' | xargs -r -I{} adb shell stat -c %s {} 2>/dev/null | tr -d '\r')"

python3 - "$CSV" "$RUNS" "${PSS:-}" "${APK_BYTES:-}" "$API" "${GITHUB_STEP_SUMMARY:-}" <<'PY'
import csv, statistics, sys
path, runs, pss, apk, api, summary = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6]
rows = list(csv.DictReader(open(path)))
def col(p, c):
    return [int(r[c]) for r in rows if r["path"] == p and r[c]]
def p90(v):
    s = sorted(v); return s[max(0, -(-9 * len(s) // 10) - 1)]
def fmt(v):
    return f"{statistics.median(v):.0f} ms (90 %: {p90(v)} ms, n={len(v)})" if v else "keine Werte"
failed = False
lines = [f"### Leistung im Emulator, API {api}", "",
         "Emulatorwerte sind nicht die Referenz (Software-Grafik, geteilter Runner). Ziele gelten auf echten Geräten.", "",
         "| Weg | Android TotalTime | App: Kaltstart bis erstes Bild | App: bis Aufnahme läuft |", "| --- | --- | --- | --- |"]
for p in ("Symbol", "Erfassen"):
    a, b, c = col(p, "android_total_ms"), col(p, "app_cold_start_ms"), col(p, "app_capture_ready_ms")
    lines.append(f"| {p} | {fmt(a)} | {fmt(b)} | {fmt(c) if p == 'Erfassen' else '–'} |")
    print(f"INFO  {p}: Android {fmt(a)} · App {fmt(b)}" + (f" · bis Aufnahme läuft {fmt(c)}" if p == "Erfassen" else ""))
    # Instrument check 1: the app measured nearly every cold start.
    if len(b) < runs - 2:
        print(f"FEHLER {p}: App hat nur {len(b)} von {runs} Kaltstarts gemessen"); failed = True
    else:
        print(f"OK    {p}: App hat {len(b)} von {runs} Kaltstarts gemessen")
    # Instrument check 2: both instruments agree. The app starts counting at process start (a bit
    # after Android's launch timestamp) and stops just before the frame is drawn, so it reads
    # somewhat lower. A gross gap means the probe measures the wrong span.
    pairs = [(int(r["android_total_ms"]), int(r["app_cold_start_ms"])) for r in rows
             if r["path"] == p and r["android_total_ms"] and r["app_cold_start_ms"]]
    if len(pairs) >= 3:
        diff = statistics.median(t - x for t, x in pairs)
        ratio = statistics.median(x / t for t, x in pairs if t > 0)
        msg = f"{p}: App liegt im Median {diff:.0f} ms unter Android ({ratio:.0%} von TotalTime)"
        if diff < -200 or ratio < 0.3:
            print("FEHLER " + msg + " – Messpunkt prüfen"); failed = True
        else:
            print("OK    " + msg)
cap = col("Erfassen", "app_capture_ready_ms")
if not cap:
    print("INFO  bis Aufnahme läuft: im Emulator keine Spracherkennung (ohne Google-App), nur auf dem Handy messbar")
    lines += ["", "Bis Aufnahme läuft: im Emulator keine Spracherkennung, nur auf dem echten Gerät messbar."]
extra = []
if pss: extra.append(f"Arbeitsspeicher nach Kaltstart (PSS): {int(pss) / 1000:.1f} MB")
if apk: extra.append(f"APK installiert: {int(apk) / 1e6:.1f} MB")
for e in extra: print("INFO  " + e)
lines += [""] + [f"- {e}" for e in extra]
if summary:
    with open(summary, "a") as f: f.write("\n".join(lines) + "\n")
sys.exit(1 if failed else 0)
PY
[ $? -eq 0 ] || FAILED=1

echo
[ $FAILED -eq 0 ] && echo "ERGEBNIS Leistung API $API: Messkette in Ordnung (Werte siehe oben und $CSV)" || echo "ERGEBNIS Leistung API $API: Messkette fehlerhaft"
exit $FAILED
