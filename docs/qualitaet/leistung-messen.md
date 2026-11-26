# Leistung auf dem eigenen Handy messen

Stand 26.11.2026 · Qualitätsplan P1-10 · Ziel aus Gestaltungsentscheidung D8

Diese Anleitung braucht nur das Handy, keinen PC und keine Kommandozeile. Dauer: rund 15 Minuten, dazu ein paar Tage normale Nutzung für den Akku.

## Was gemessen wird und woran es sich misst

Die App misst selbst mit der Uhr des Handys und speichert die Werte nur lokal. Sie stehen unter **Einstellungen → Messwerte (nur lokal)**.

| Messwert | von … bis … | Ziel (Median) | Quelle des Ziels |
| --- | --- | --- | --- |
| Bis die Aufnahme läuft | Tipp auf Kachel, Knopf oder Verknüpfung (bei gesperrtem Handy: Entsperren) bis das Mikrofon zuhört | ≤ 2,0 s | D8 (a) |
| Speichern nach Sprechende | Handy merkt, dass du aufgehört hast zu sprechen, bis „Gespeichert“ | ≤ 1,0 s | D8 (b) |
| Kaltstart bis erstes Bild | App war ganz beendet, bis zum ersten Bild | ≤ 1,0 s (Richtwert) | Gestaltungsdoc rechnet mit 0,8 s App-Start |
| Arbeitsspeicher, App-Daten | Momentaufnahme beim Öffnen der Einstellungen | kein Ziel, nur beobachten | – |
| Akku | aus den Android-Einstellungen, nicht aus der App | unter 1 % pro Tag (Richtwert) | – |

Zählt wird der **Median**, also der mittlere Wert. Ein einzelner langsamer Lauf ist kein Fehlschlag. Die App zeigt zusätzlich, wie lange 9 von 10 Läufen höchstens gebraucht haben.

Was die App nicht messen kann: deine eigene Bedienzeit, also das Herunterwischen der Schnelleinstellungen und den Tipp selbst. Das Gestaltungsdoc schätzt dafür gut eine Sekunde.

Damit die Zahlen stimmen, misst der Emulator im CI bei jedem Build dasselbe mit zwei Instrumenten: mit Androids eigener Startzeit und mit der Messung der App. Weichen beide stark voneinander ab, schlägt der Build fehl. Die Emulatorzeiten selbst sind keine Referenz, weil der Emulator ohne Grafikkarte läuft. Die Referenz ist dein Handy.

## Vorbereitung (einmal)

1. Die App auf die neueste Version bringen (sie meldet ein Update beim Start, sonst in den Einstellungen unter „Update“ prüfen).
2. Akku über 50 %, **Energiesparmodus aus**. Der Energiesparmodus bremst das Handy und verfälscht die Zeiten.
3. Kachel „Erfassen“ in die Schnelleinstellungen legen: zweimal von oben wischen, auf den Stift tippen, die Kachel „Erfassen“ nach oben in die aktiven Kacheln ziehen.
4. In der App: **Einstellungen → Messwerte → „Messreihe neu beginnen“**. Damit zählen alte Werte nicht mit.

## Messreihe (rund 15 Minuten)

Sag bei jeder Aufnahme etwas Kurzes wie „Test eins“, hör dann einfach auf zu sprechen und tippe **nicht** auf „Fertig“. Nur so misst die App „Speichern nach Sprechende“ ohne Tipp. Die Testkarten danach im Eingang nach links wischen (irrelevant).

**A. Kaltstart, 10-mal**

1. App ganz beenden: von unten nach oben wischen und halten, bis die Übersicht der offenen Apps erscheint, dann die FamilyApp nach oben wegschieben.
2. Etwa 5 Sekunden warten.
3. App über das Symbol öffnen.

**B. Kachel nach dem Entsperren, 10-mal**

1. Handy sperren (Seitentaste) und wieder entsperren.
2. Sofort von oben wischen und auf die Kachel „Erfassen“ tippen.
3. „Test“ sagen, aufhören zu sprechen, warten bis „Gespeichert“ erscheint.

Bei fünf dieser zehn Läufe die App vorher wie in A beenden, bei den anderen fünf nicht. So sind beide Fälle dabei: App war noch im Speicher oder musste neu starten.

**C. Knopf in der App, 5-mal**

Auf den runden Erfassen-Knopf unten in der Mitte tippen, „Test“ sagen, aufhören zu sprechen.

## Ergebnis ansehen und schicken

1. **Einstellungen → Messwerte** öffnen. Dort steht je Messwert der Median und ob das Ziel erreicht ist, für „Bis die Aufnahme läuft“ auch je Weg (Kachel, Knopf, Symbol).
2. Auf **„Messwerte teilen“** tippen und die Tabelle an Claude schicken, zum Beispiel in diesen Thread der Claude-App oder per Mail an dich selbst. Die Tabelle enthält nur Zeiten, Handymodell, Android- und App-Version, keine Einträge und keine Namen.

Claude trägt die Werte in die Bewertung ein ([iso-25010-bewertung.md](iso-25010-bewertung.md), Abschnitt 6, P1-10).

Steht bei „Kaltstart“ weniger als 10 Messungen, war die App beim Öffnen noch nicht ganz beendet. Das ist kein Fehler: Die App zählt nur echte Kaltstarts.

## Speicher (1 Minute)

- In der App unter Messwerte: „Arbeitsspeicher jetzt“ und „App-Daten“.
- Gegenprobe in Android: **Einstellungen → Apps → FamilyApp → Speicher und Cache**. „App-Daten“ in der FamilyApp sollte ungefähr „Nutzerdaten“ plus „Cache“ dort entsprechen.

## Akku (nach 2–3 Tagen normaler Nutzung)

Die App kann ihren eigenen Akkuverbrauch nicht zuverlässig messen, Android schon.

1. **Einstellungen → Akku → Akkunutzung**.
2. Oben auf **„Nach App anzeigen“** umstellen (je nach Android-Version „Apps“).
3. FamilyApp suchen. Steht sie nicht in der Liste, liegt ihr Verbrauch unter der Anzeigeschwelle, das ist gut.
4. Steht sie drin: antippen und einen Screenshot (Seitentaste + Leiser gleichzeitig) an Claude schicken. Wichtig sind der Anteil in Prozent und die Zeit „im Hintergrund“.

Im Hintergrund tut die App zweierlei: einmal am Tag um 8 Uhr prüft sie Fristen, und etwa alle 15 Minuten schaut sie nach gekoppelten Geräten zum Abgleich (Android legt solche Läufe zusammen). Fällt der Akku auf, ist der Abgleich der erste Verdacht.

## Zweites Gerät (optional)

Der Plan sieht zusätzlich ein Android-8-Gerät vor, weil ältere Handys langsamer sind. Wenn eins da ist, dieselbe Messreihe dort wiederholen. Ohne zweites Gerät gilt die Messung auf dem Pixel, ergänzt um die Android-8-Werte aus dem Emulator (mit Vorsicht, siehe oben).

## Für Entwickler

- Messpunkte: `ColdStartProbe` (androidApp), `AppModel.startCapture`/`onReady` und `onEndOfSpeech` (shared), Speicher in `AndroidPlatform.resourceUsage`. Logik und CSV in `domain/Measurements.kt`, getestet im Qualitätstor.
- Jede neue Messung steht auch im Logcat (Tag `FamilyPerf`), ohne Familiendaten.
- CI: `app/scripts/emulator-perf.sh` im Emulator-Job, Bericht `pruefbericht-leistung-api26.txt` / `-api36.txt` und `leistung-api*.csv` am Release, Übersicht in der Job-Zusammenfassung.
- Warum kein Macrobenchmark: Er braucht ein eigenes Testmodul aus Google Maven und misst im CI-Emulator dieselbe Startzeit wie `am start -W`, nur mit mehr Aufwand. Er wird sinnvoll zusammen mit einem Baseline Profile (P2-5), dann auf einem echten Gerät.
