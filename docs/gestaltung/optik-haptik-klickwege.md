# Optik, Haptik und Klickwege

- Status: Entwurf zum Sparring. Entschieden: D1 Navigation A, keine Vibration (20.11.2026). D2–D8 offen.
- Datum: 2026-11-20
- Klickbare Skizze: https://claude.ai/artifact/D46DuTRTcBt5pky1nVV32g (Variante A mit Tap-Zähler, Varianten B und C, Pairing, Benachrichtigungen)
- Grundlage: [KONZEPT.md](../KONZEPT.md), [ADR 0003](../entscheidungen/0003-p2p-sync-im-selben-netz.md)

Was dieses Dokument anders macht als ein Moodboard:

- Jeder Klickweg ist **gezählt** (Taps und Wischgesten) und für das Erfassen zusätzlich als Zeit nach dem Keystroke-Level-Model geschätzt. Die Skizze zählt mit, damit wir Zahlen an echten Händen prüfen statt zu glauben.
- Bewegung und Benachrichtigungen sind auf **konkrete Plattform-APIs** (Android, iOS, Compose) abgebildet, damit die Umsetzung nichts mehr erfinden muss. Vibration ist bewusst gestrichen (Abschnitt Haptik).
- Farben sind auf **Kontrast nach WCAG 2.2** nachgerechnet, und am Ende steht ein **Testskript** mit prüfbaren Zielwerten.

## Kernaufgaben und wie oft sie vorkommen

Die Häufigkeit ist geschätzt (eine Familie mit zwei Erwachsenen, zwei Kindern), nicht gemessen. Sie bestimmt, welche Wege am kürzesten sein müssen.

| # | Aufgabe | Wer | Geschätzt pro Woche | Daraus folgt |
| --- | --- | --- | --- | --- |
| K1 | Erfassen (Sprache, Foto, Teilen, Text) | alle | 15–30 | Kürzester Weg der App, auch ohne die App zu öffnen |
| K2 | Eingang abarbeiten (bestätigen, ändern, irrelevant) | alle | 15–30 | Ein Tap pro Karte, keine Dialoge |
| K3 | Eigene Aufgaben abhaken | alle | 10–20 | Auf dem Startbildschirm, ein Tap oder Wischen |
| K4 | Stapel der anderen ansehen, übernehmen | alle | 3–7 | Höchstens zwei Taps, Transparenz ist Prinzip |
| K5 | Demnächst und Fristen ansehen | alle | 3–7 | Eigenes Ziel, ein Tap |
| K6 | Auf Überfällig-Hinweis reagieren | Zuständige, dann alle | 0–3 | Direkt aus der Benachrichtigung |
| K7 | Familienwissen suchen | alle | 1–3 | Eigenes Ziel, kein Menü |
| K8 | Onboarding, Pairing | einmal je Gerät | – | Kein Konto, beide Seiten bestätigen |

## Navigation: drei Varianten

Gezählt wird jeder Tap und jede Wischgeste, ab geöffneter App auf dem Startbildschirm. Sprechen und Tippen von Inhalt zählen nicht.

| Aufgabe | A: Heute · Familie · Erfassen · Demnächst · Suchen | B: Ein Strom mit Filtern | C: Stapel pro Person |
| --- | --- | --- | --- |
| K1 Sprache erfassen | **1** | **1** | **1** |
| K1 Foto erfassen | 3 (Erfassen, Foto, Auslöser) | **2** | 3 |
| K2 Vorschlag bestätigen | **1** | **1** | 1, plus 1 je Sitzung für die Eingang-Spalte |
| K2 Fehlende Person ergänzen | **2** | **2** | 2, plus Spaltenwechsel |
| K2 Als irrelevant markieren | **1** | **1** | 1, plus Spaltenwechsel |
| K3 Eigene Aufgabe abhaken | **1** | **1** | **1** |
| K4 Lisas Stapel ansehen | 2 | **1** (Filter) | **1** (wischen) |
| K4 Lisas Aufgabe übernehmen | **2** | **2** | **2** |
| K5 Nächste 6 Wochen | **1** | **1** (Filter) | 2 (eigener Bildschirm fehlt) |
| K7 Suchen | **1** | 2 | 2 |
| **Taps pro Woche** (Mitte der Schätzung, Rechnung unten) | ≈ 104 | **≈ 90** | ≈ 113 |

| | Stärke | Schwäche |
| --- | --- | --- |
| **A** | Trennt „braucht Entscheidung“ (Eingang) klar von „ist zu tun“ (Meins). Bekanntes Muster (Navigationsleiste nach Material 3, Tab Bar nach HIG), 3–5 Ziele sind in beiden Richtlinien vorgesehen. Familie ist ein eigenes Ziel, Transparenz hat einen festen Ort. | Foto braucht einen Tap mehr als B. Fünf Einträge in der Leiste sind das Maximum. |
| **B** | Wenigste Taps, ein einziger Bildschirm, Erfassen-Leiste wie in einem Messenger. | Entscheidungen, eigene und fremde Aufgaben und Termine mischen sich. Mehr gleichzeitig sichtbare Elemente heißt mehr Last im Arbeitsgedächtnis. Die Stapel der anderen verschwinden hinter einem Filter. |
| **C** | Stärkste Transparenz: Jede Person hat sichtbar ihren Stapel. Auf dem Tablet hervorragend, weil Spalten nebeneinander passen. | Auf dem Handy passt nur eine Spalte; ab drei Erwachsenen (Getrennterziehende, Großeltern) wird viel gewischt. Kein guter Ort für Termine und Suche. |

**Empfehlung:** A auf dem Handy, obwohl B ein paar Taps spart: Die Trennung von Entscheidung und Aufgabe und der feste Ort für die Stapel der anderen sind Kern des Konzepts, die Taps lassen sich in A beim Foto zurückholen. Die Spaltenansicht aus C wird die Ansicht „Familie“ auf dem Tablet. Von B übernehmen wir die Idee, dass der Startbildschirm Eingang und eigene Aufgaben zusammen zeigt („Heute“).

Rechnung (Mittelwert der Häufigkeit × Taps): K1 22,5 × (Sprache + Foto)/2 = 45 / 34 / 45; K2 22,5 × 1,2 (jede fünfte Karte braucht eine Ergänzung) = 27, bei C plus 7 Spaltenwechsel bei geschätzt 7 Sitzungen; K3 15; K4 5 × Taps; K5 5 × Taps; K7 2 × Taps. Ergebnis A 104, B 90, C 113. B spart gegenüber A etwa 14 Taps pro Woche, fast alle beim Foto. Den Unterschied holt A zurück, wenn Foto in A ebenfalls zwei Taps braucht (siehe D3: langes Drücken auf Erfassen öffnet direkt die Kamera, oder ein Foto-Widget). Die Häufigkeiten sind Schätzungen und gehören nach zwei Wochen Nutzung durch gemessene ersetzt.

## Erfassen: schneller als die App zu öffnen

Ziel aus dem Konzept: „Erfassen in unter 5 s ab Entsperren.“ Nachgerechnet mit dem Keystroke-Level-Model (Card, Moran & Newell 1983: mentale Vorbereitung M ≈ 1,35 s). Annahmen, die an eigenen Messungen kalibriert werden müssen: Tap inklusive Zielen ≈ 0,5 s, Wischen ≈ 0,5 s, Sprechen ≈ 2,5 Wörter/s, Erkennen des Sprechendes ≈ 1 s.

| Weg | Ablauf | Overhead bis Aufnahme läuft | Gesamt bei „Mia braucht bis Donnerstag neue Hausschuhe“ (7 Wörter) |
| --- | --- | --- | --- |
| App-Symbol, dann Erfassen | M, Tap Symbol, App-Start ≈ 0,8 s, Tap Erfassen | ≈ 3,2 s | ≈ 7,0 s |
| Schnellkachel (Android) / Steuerelement im Kontrollzentrum oder Action Button (iOS) | M, Wischen, Tap | ≈ 2,4 s | ≈ 6,2 s |
| Widget auf dem Startbildschirm | M, Tap | ≈ 1,9 s | ≈ 5,7 s |
| Teilen-Menü aus Mail, Galerie, Browser | Tap Teilen, Tap App | ≈ 1,0 s (M schon erledigt) | ≈ 1,5 s, ohne Sprechen |

**Befund:** Mit Sprechzeit ist „unter 5 s“ für Sprache nicht zu schaffen, die Sprechzeit ist aber Inhalt, nicht Aufwand. **Vorschlag (D8):** Zielwerte ersetzen durch (a) Aufnahme läuft spätestens 2 s nach Entsperren, (b) gespeichert ohne weiteren Tap spätestens 1 s nach Sprechende, (c) kein Pflichtfeld.

Regeln fürs Erfassen:

1. Der Erfassen-Knopf **startet sofort die Spracheingabe**. Foto und Tippen sind im selben Blatt einen Tap entfernt. Abbrechen durch Wischen nach unten oder Tippen daneben.
2. **Speichern ohne Knopf:** Nach Sprechende speichert die App selbst und zeigt „Gespeichert. Liegt oben im Eingang.“ mit Rückgängig.
3. Was Regeln sicher erkennen (Kindname, Datum), steht danach schon an der Karte. Alles andere bleibt offen.
4. Einstiege außerhalb der App sind Plattformcode und **nicht Compose Multiplatform**: Android `TileService` (Schnellkachel), App-Shortcuts (`ShortcutManager`, langes Drücken aufs Symbol), Widget (Jetpack Glance), Teilen-Ziel. iOS: Steuerelement für Kontrollzentrum und Sperrbildschirm (`ControlWidget`, ab iOS 18), Action Button und Siri über App Intents, Widget (WidgetKit), Share Extension. Im Modulplan als eigene `androidMain`- und Swift-Bausteine vorsehen.
5. Erkennung bleibt auf dem Gerät: Android `SpeechRecognizer` mit On-Device-Modell bzw. ML Kit, iOS `SFSpeechRecognizer` mit `requiresOnDeviceRecognition`, Texterkennung mit ML Kit bzw. Vision. Verfügbarkeit für Deutsch auf älteren Geräten prüfen.

## Eingang: eine Karte, ein Tap

Anatomie einer Karte (von oben):

1. Quelle und Alter, klein: „Mail · Grundschule am Park · vor 2 Std.“
2. Titel, eine bis zwei Zeilen
3. **Höchstens drei Chips:** betrifft (Person), kümmert sich (Person), bis wann. Fehlendes erscheint gestrichelt als Frage („Wer kümmert sich?“).
4. Begründung, klein: welche Regel gegriffen hat. Das hält die Automatik nachvollziehbar und macht falsche Regeln sichtbar.
5. Rechts unten „Passt“, links daneben „Irrelevant“.

Verhalten:

| Situation | Weg | Taps |
| --- | --- | --- |
| Vorschlag stimmt | „Passt“ oder nach rechts wischen | 1 |
| Person fehlt oder ist falsch | Chip antippen, Person wählen. Die Wahl ist die Bestätigung. | 2 |
| Datum ändern | Chip antippen, Schnellwahl (Heute, Morgen, Freitag, Nächste Woche, Kein Datum) | 2, dann „Passt“ |
| Unwichtig | „Irrelevant“ oder nach links wischen | 1 |
| Daraus eine Regel machen | In der Rückmeldung nach dem Ändern: „Künftig immer so“ | +1, freiwillig |

- **Rückgängig statt Nachfrage.** Kein Bestätigungsdialog, jede Aktion zeigt 6 s lang eine Rückmeldung mit „Rückgängig“ (Raskin 2000, „The Humane Interface“: Rückgängig schlägt Warndialoge, weil Warnungen weggeklickt werden; aus dem Gedächtnis, vor Zitat prüfen).
- **„Künftig immer so?“ ist nie ein Dialog**, sondern ein Angebot in der Rückmeldung. Wer es ignoriert, hat nichts falsch gemacht. Das hält Regelpflege aus dem Pflichtteil (Konzept: „kein Pflicht-Editor“).
- **Abschluss erlebbar:** Ist der Eingang leer, steht dort „Alles sortiert. Darüber musst du gerade nicht nachdenken.“ Das zielt auf offene Schleifen, die sonst im Kopf bleiben (Masicampo & Baumeister 2011, im Konzept schon genannt).
- **Drei Chips als Obergrenze:** Das Arbeitsgedächtnis hält etwa vier Einheiten gleichzeitig (Cowan 2001, Behavioral and Brain Sciences 24; aus dem Gedächtnis, vor Zitat prüfen). Titel plus drei Angaben passen hinein.

## Heute, Familie, Demnächst, Suchen

- **Heute** (Start): oben der Eingang mit Zähler, darunter „Meins“, Überfälliges zuerst und rot mit Text („überfällig seit gestern“), nie nur über Farbe. Am Ende „diese Woche erledigt: 6“.
- **Familie:** oben die **Lastkarte**, nur feste Fristen und Termine, je Erwachsenem ein Balken mit rotem Anteil für Überfälliges und dem Text „1 überfällig“. Antippen filtert die Liste darunter auf diese Person. Fremde Aufgaben tragen „Übernehme ich“. Übernehmen ist sichtbar für alle („Lisa sieht, dass du … machst“), heimliches Erledigen gibt es nicht. Darunter, wie viele Dinge im Eingang noch niemandem gehören.
- **Demnächst:** sechs Wochen nach Kalenderwochen, Vorausgedachtes (Pakete, Kalender-Abos) ist als solches markiert, offene Zuständigkeit als gestrichelter Kreis mit Fragezeichen.
- **Suchen:** Familienwissen (Kontakte, Dokumente, frühere Entscheidungen mit Begründung).
- Profil und Einstellungen hinter dem eigenen Avatar oben rechts, Regeln dort unter „Regeln“.

Der Erfassen-Knopf sitzt **in der Mitte der Navigationsleiste**, leicht erhaben (D2). Material 3 sieht den FAB eigentlich unten rechts vor. Die Mitte ist mit beiden Daumen gleich gut erreichbar, und Erfassen ist die häufigste Aufgabe (Hoober 2013, Studie zur Handhaltung: viele bedienen einhändig, mit wechselnder Hand; aus dem Gedächtnis, vor Zitat prüfen).

## Visuelle Sprache

Ruhig, warm, wenig Farbe. Farbe trägt nur Bedeutung: Akzent für die Hauptaktion, Rot für überfällig, Personenfarben für Zuordnung.

| Token | Hell | Dunkel | Kontrast hell auf Grund #F6F5F2 / Karte #FFFFFF | Kontrast dunkel auf #151513 |
| --- | --- | --- | --- | --- |
| Grund | #F6F5F2 | #151513 | – | – |
| Karte | #FFFFFF | #201F1C | – | – |
| Text | #1D1C1A | #ECEAE4 | 15,6 / 17,0 | 15,2 |
| Text sekundär | #5C5A55 | #B0ADA6 | 6,3 / 6,9 | 8,2 |
| Text tertiär (Metazeilen) | #6E6B65 | – | 4,9 / 5,3 | – |
| Akzent (Passt, Erfassen) | #0E6B66 | #5FC4BC | 5,8 / 6,3, Weiß darauf 6,3 | 8,8 |
| Überfällig | #B3261E | #FF8A80 | 6,0 / 6,5 | 8,0 |
| Bald fällig | #8A5300 | #F2B85B | 5,8 / 6,3 | 10,3 |
| Person 1 (Blau) | #2F5DA8 | #8FB0EC | 5,9 / 6,5, Weiß darauf 6,5 | 8,4 |
| Person 2 (Orange) | #A84B0C | #F0A06A | 5,2 / 5,7, Weiß darauf 5,7 | 8,7 |
| Person 3 (Lila) | #7B3FA0 | #C9A0E6 | 6,3 / 6,9, Weiß darauf 6,9 | 8,4 |
| Person 4 (Grün) | #2C7A4B | #7FCB9B | 4,8 / 5,3, Weiß darauf 5,3 | 9,5 |

Alle Werte erfüllen WCAG 2.2 AA für Text (4,5:1), nachgerechnet mit der Formel für relative Leuchtdichte. Die Personenfarben haben fast gleiche Helligkeit (relative Leuchtdichte 0,10–0,15). Deshalb trägt **jeder Personen-Punkt immer die Initiale**, Farbe allein reicht für Farbfehlsichtige nicht.

- **Schrift:** Systemschrift (Roboto bzw. SF Pro), weil beide mit der Schriftgrößen-Einstellung des Systems skalieren. Fließtext 15–16 sp, Metazeilen 12 sp, nichts unter 11 sp. Layout muss bei 200 % Schriftgröße funktionieren.
- **Formen:** Karten mit 16 dp Radius, Chips 16 dp hoch-rund, Bottom Sheet 28 dp oben (Material-3-Werte).
- **Dynamische Farbe (Material You):** nur für die Oberfläche denkbar, nie für Personenfarben, weil Lisas Orange auf allen Geräten Lisas Orange bleiben muss. Empfehlung: aus (D6), dann sehen Android und iPhone gleich aus.
- **Dunkelmodus** von Anfang an, Werte oben.
- **Keine Gamification:** keine Punkte, Ranglisten oder Konfetti. Sichtbar ist, was offen und was überfällig ist.

## Haptik

**Entschieden (Nils, 20.11.2026): Die App vibriert nicht.** Keine haptische Rückmeldung bei Gesten, Bestätigen, Aufnahme oder Pairing, keine eigenen Vibrationsmuster für Benachrichtigungen. Rückmeldung kommt nur sichtbar: Bewegung der Karte, Farbwechsel an der Wisch-Schwelle, Hinweis unten mit Rückgängig. Benachrichtigungen folgen den Ton- und Vibrations-Einstellungen, die die Person im System für die App bzw. den Kanal festlegt; die App setzt dort nichts zusätzlich.

Für die Umsetzung heißt das: kein `LocalHapticFeedback`, keine `HapticFeedbackConstants`, keine `UIFeedbackGenerator`. Android-Benachrichtigungskanäle bekommen kein eigenes Vibrationsmuster (kein `setVibrationPattern`); ob es vibriert, entscheidet die System-Einstellung der Person. Haptik, die das System selbst auslöst (z. B. die Tastatur), bleibt unberührt.

## Bewegung

| Moment | Dauer | Kurve (Material 3) |
| --- | --- | --- |
| Karte fliegt nach Bestätigen aus der Liste, Liste rückt nach | 200 ms | Standard `cubic-bezier(0.2, 0, 0, 1)` |
| Karte schnappt zurück, wenn die Schwelle nicht erreicht ist | 200 ms | Standard |
| Bottom Sheet „Erfassen“ öffnet | 250–400 ms | Emphasized decelerate `cubic-bezier(0.05, 0.7, 0.1, 1)` |
| Rückmeldung unten erscheint | 250 ms | Standard |
| Aufnahme läuft | Pulsieren 1,4 s, endlos | – |

- Bewegung erklärt, wohin etwas geht (Karte wandert nach „Meins“ bzw. zur Person), nicht als Deko.
- „Bewegung reduzieren“ (iOS `UIAccessibility.isReduceMotionEnabled`, Android „Animationen entfernen“ bzw. Animator-Dauer 0) schaltet alles auf Überblenden oder sofort. Die Skizze tut das über `prefers-reduced-motion`.

## Benachrichtigungen und Eskalation

| Kanal (Android) / Stufe (iOS) | Inhalt | Ton (Vibration nach System-Einstellung, keine eigene) | Zeitpunkt |
| --- | --- | --- | --- |
| Überfällig · an dich | Frist verpasst, eigene Aufgabe. Aktionen „Erledigt“, „Abgeben“ | ja | gesammelt 7:30 |
| Überfällig · an alle | +1 Tag, Aufgabe einer anderen Person. Aktionen „Übernehme ich“, „Ansehen“. Text neutral: „Stand prüfen“, weil das Gerät nicht wissen kann, ob schon erledigt (ADR 0003) | ja | gesammelt 7:30 |
| Heute fällig | eigene Fristen und Termine. Aktion „Erledigt“ | ja | morgens bzw. vor dem Termin |
| Eingang | „3 neue Dinge warten“ | lautlos, gebündelt | zweimal täglich |

- Ruhezeit 21–7 Uhr, einstellbar. Gesammelt statt einzeln über den Tag, weil Unterbrechungen Stress erhöhen, auch wenn die Arbeit schneller wird (Mark, Gudith & Klocke 2008, CHI „The cost of interrupted work“; aus dem Gedächtnis, vor Zitat prüfen).
- iOS: Stufe `active`, nicht `timeSensitive` (braucht eine Berechtigung und ist für Familienfristen zu laut). Max. 64 geplante lokale Benachrichtigungen (ADR 0003), also die nächsten 64 Fristen planen.
- Jede Aktion in der Benachrichtigung ist ein Klick, ohne die App zu öffnen.

## Onboarding und Pairing

Schnell rein: Die erste Person legt die Familie an, indem sie die App öffnet und den eigenen Namen tippt. Danach sofort Erfassen. Kein Konto, keine E-Mail.

| Schritt | Einladendes Gerät (Nils) | Neues Gerät (Lisa) |
| --- | --- | --- |
| 1 | Familie → „Person hinzufügen“ | „Ich wurde eingeladen“ |
| 2 | „Jemand mit eigenem Handy“ → QR erscheint (10 min, einmalig) | Name tippen → „Weiter zum Scannen“ |
| 3 | – | Kamera erkennt QR von selbst |
| 4 | sieht „Lisa · Pixel 9 möchte beitreten“ und 6-stelligen Code → „Stimmt“ | sieht „Familie von Nils“ und denselben Code → „Stimmt“ |
| **Taps** | **3** | **3** plus Name |

- Der Name wird **vor** dem Scannen abgefragt, damit das einladende Gerät beim Bestätigen sieht, wer beitritt, nicht nur welches Gerät (Schutz gegen abfotografierte Codes, ADR 0003).
- „Jemand ohne Handy“ (Kind, Oma) braucht nur einen Namen, Farbe wird vergeben.
- **Wiederherstellungscode** (D7): nicht beim ersten Start, sondern nach dem ersten Pairing oder nach 10 Einträgen als Karte im Eingang: „Sichere deinen Wiederherstellungscode“. Gegenargument: Wer vorher das Handy verliert, verliert die Daten. Bei zwei gekoppelten Geräten liegen sie aber auf dem zweiten.

## Tablet (später)

`NavigationSuiteScaffold` aus Material 3 Adaptive schaltet je nach Fensterbreite zwischen Navigationsleiste (Handy) und Navigationsschiene (Tablet). Auf dem Tablet zeigt „Familie“ die Spalten aus Variante C nebeneinander. Ein zentrales Familien-Tablet bekommt zusätzlich „Demnächst“ als Wochenansicht im Querformat.

## Barrierefreiheit

- Tippflächen mindestens 48 × 48 dp (Material 3) bzw. 44 × 44 pt (HIG), WCAG 2.2 verlangt 24 × 24 px als Untergrenze (2.5.8).
- **Jede Wischgeste hat einen Knopf** (WCAG 2.5.1, Zeigergesten). Für TalkBack und VoiceOver werden „Passt“, „Irrelevant“ und „Erledigt“ zusätzlich als eigene Aktionen angeboten (Compose `semantics { customActions }`, iOS `accessibilityCustomActions`).
- Status nie nur über Farbe: „überfällig seit gestern“ als Text, Initialen in Personen-Punkten.

## Offene Entscheidungen für Nils

| # | Frage | Empfehlung | Alternative |
| --- | --- | --- | --- |
| D1 | Navigation | **Entschieden (Nils, 20.11.2026): A** (Heute, Familie, Erfassen, Demnächst, Suchen), Tablet mit Spalten aus C | – |
| D2 | Wo sitzt Erfassen? | **Mitte der Leiste**, beidhändig erreichbar | FAB unten rechts (Material-3-Standard) |
| D3 | Was macht der Erfassen-Knopf? | **Tippen startet sofort Sprache, langes Drücken öffnet die Kamera** (Foto dann 2 Taps), Text einen Tap entfernt | Erst Auswahl (je 1 Tap mehr) |
| D4 | Bestätigt das Wählen einer fehlenden Person die Karte? | **Ja**, 2 Taps statt 3 | Immer zusätzlich „Passt“ (einheitlicher) |
| D5 | Wischgesten | **Rechts Passt/erledigt, links irrelevant**, immer mit Knopf und Rückgängig | Keine Gesten |
| D6 | Farbschema | **Feste App- und Personenfarben** | Material You auf Android |
| D7 | Wiederherstellungscode | **Nach erstem Pairing bzw. 10 Einträgen anbieten** | Beim ersten Start |
| D8 | Zielwert Erfassen | **Aufnahme läuft ≤ 2 s nach Entsperren, Speichern ohne Tap ≤ 1 s nach Sprechende** | „< 5 s gesamt“ wie im Konzept |

## Prüfung: woran wir sehen, ob es stimmt

1. **Klickzahlen in der Skizze:** Variante A hat sieben Testaufgaben mit Zielwert und zählt mit. Abnahme: Jede Aufgabe ist mit dem Zielwert lösbar (Stand jetzt: K1 1, K2 1 bzw. 2, K3 1, K4 2, K5 1, Übernehmen 2).
2. **Flurtest mit 5 Personen** (davon mindestens 2, die die App nicht kennen; 5 Personen finden den Großteil der Bedienprobleme, Nielsen & Landauer 1993, aus dem Gedächtnis, vor Zitat prüfen). Ohne Erklärung die sieben Aufgaben lösen lassen, mitzählen, laut denken lassen. Ziel: ≥ 80 % der Aufgaben mit höchstens Zielwert + 1, keine Aufgabe von mehr als einer Person abgebrochen.
3. **Erster Klick:** Für „Lisas Stapel ansehen“ und „Fristen ansehen“ messen, ob der erste Tap richtig sitzt (Ziel ≥ 4 von 5).
4. **Telemetrie im Prototyp** (lokal, ohne Server): Zeit von App-Start bzw. Kachel bis Aufnahme läuft, Median ≤ 2 s; Anteil der Karten, die mit einem Tap erledigt werden, ≥ 70 %.
5. **Kontrast** im fertigen Theme automatisiert prüfen (Werte oben sind die Sollwerte), Layout bei 200 % Schriftgröße und mit TalkBack/VoiceOver einmal komplett durchgehen.

## Quellen

- Material Design 3: Navigation bar, FAB, Bottom sheets, Motion (Easing and duration tokens), Color roles; m3.material.io
- Apple Human Interface Guidelines: Tab bars, Notifications, Accessibility; developer.apple.com/design/human-interface-guidelines
- Android Developers: Notification channels, `TileService`, Jetpack Glance; developer.android.com
- WCAG 2.2: 1.4.3 Kontrast, 2.5.1 Zeigergesten, 2.5.8 Zielgröße (Minimum); w3.org/TR/WCAG22
- Card, Moran & Newell (1983): The Psychology of Human-Computer Interaction (Keystroke-Level-Model)
- Cowan (2001), Masicampo & Baumeister (2011), Mark, Gudith & Klocke (2008), Hoober (2013), Raskin (2000), Nielsen & Landauer (1993): wie im Text vermerkt, aus dem Gedächtnis zitiert und vor einer Veröffentlichung zu prüfen. Konkrete Seiten der Plattform-Richtlinien wurden in dieser Sitzung nicht abgerufen; API-Namen und Level stammen aus dem Gedächtnis und werden beim ersten Code geprüft.
