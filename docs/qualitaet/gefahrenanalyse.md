# Gefahrenanalyse (FMEA-light)

Stand 26.11.2026, Plan-Nummer P1-9 aus der [ISO-25010-Bewertung](iso-25010-bewertung.md). Sie betrachtet die beiden Gefahren, die eine Familien-App wirklich anrichten kann:

1. **Ein Termin ist falsch, fehlt oder wird zu spät bemerkt** (Kind ohne Turnbeutel, verpasste Anmeldefrist, Mahnung).
2. **Daten sind weg** (Handy verloren, Datei kaputt, falsche Sicherung).

## Bewertung

Jede Zeile wird in drei Größen von 1 bis 5 bewertet:

- **S Schwere**: 1 = lästig, 3 = Ärger in der Familie oder Mehrarbeit, 5 = Frist verpasst mit Folgen oder Daten unwiederbringlich verloren
- **A Auftreten**: 1 = kaum denkbar, 3 = kommt bei manchen Familien vor, 5 = häufig
- **E Entdeckung**: 1 = fällt sofort auf, 3 = fällt beim nächsten Öffnen auf, 5 = fällt erst auf, wenn es zu spät ist

**RPZ** = S × A × E (höchstens 125). Als **hoch** gilt RPZ ≥ 30 oder S = 5. Die Bewertung „vorher“ beschreibt den Stand vor der Bewertung am 25.11.2026, „jetzt“ den Stand mit allen Gegenmaßnahmen auf `main`.

Abnahmekriterium aus dem Plan: Jede Zeile, die **vorher** hoch war, hat einen automatischen Test oder eine Plan-Nummer.

## Gefahr 1: Termin falsch, fehlt oder zu spät

| # | Fehler | Ursache | Wirkung | S | A | E | RPZ vorher | Gegenmaßnahme | RPZ jetzt | Test oder Plan |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F1 | Falsches Datum vorgeschlagen und mit einem Tap bestätigt | Parser deutet Nummern, Bereiche oder Bezugsdaten als Frist („Kapitel 4.1.“, „15.-19.6.“, „Rechnung vom 12.11.“), „29.2.“ im Nicht-Schaltjahr | Familie plant mit falschem Tag | 4 | 3 | 4 | **48** | Diese Fälle sind behoben; Vorschläge sind gestrichelt und nennen ihre Herkunft („„bis Freitag“ → Fr 27.11.“); zwei Daten ergeben keinen Vorschlag | 4 × 1 × 3 = 12 | `DateParserPropertiesTest` (12 Eigenschaften über 1 000 Tage), `DateCorpusTest` (44 Sätze, 0 falsch), privater Korpus möglich |
| F2 | Frist bleibt ohne Datum | Unklarer Text, zwei Daten, Abkürzungen („am Do“) | Karte fragt „Bis wann?“ | 3 | 3 | 2 | 18 | Karte bleibt im Eingang, bis jemand entscheidet (HITL) | 18 | `twoDifferentDatesSuggestNothing` |
| F3 | Bestätigte Frist wird nicht rechtzeitig gesehen | App wird nicht geöffnet | Frist verstreicht | 4 | 4 | 5 | **80** | Morgennachricht am Fälligkeitstag und am Tag danach, „Heute wichtig“ beim Öffnen, Eskalation an alle | 4 × 2 × 2 = 16 | `ReminderPlannerTest`, Emulator „Erinnerung für die Frist geplant“ |
| F4 | Erinnerung kommt nie an | Benachrichtigungen in Android verweigert oder später abgeschaltet | Wie F3, aber man verlässt sich auf die App | 4 | 3 | 5 | **60** | Frage nach der Erlaubnis im passenden Moment; Einstellungen zeigen einen Hinweis, wenn Android die Benachrichtigungen blockiert; „Heute wichtig“ funktioniert ohne Erlaubnis | 4 × 2 × 3 = 24 | `notificationPermissionIsAskedOnceWhenTheFirstDeadlineIsConfirmed`; Hinweis nur im Code (Android-Aufruf), kein automatischer Test |
| F5 | Wecker fehlt nach Neustart, Update oder „Beenden erzwingen“ | Android löscht Wecker | Keine Morgennachricht | 4 | 2 | 5 | **40** | Neu planen bei `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` und bei jedem App-Start; verpasste Läufe werden beim nächsten Lauf zu einer Nachricht zusammengefasst | 4 × 1 × 4 = 16 | `runsMissedWhileThePhoneWasOffBecomeOneNotificationWithTheNewestStatePerItem`; „Beenden erzwingen“ bleibt ohne Wecker bis zum nächsten Öffnen (Android-Regel) |
| F6 | Eskalation erreicht die anderen nicht | Ohne Sync kennt nur das erfassende Gerät die Frist | Zweite Person erfährt nichts | 4 | 4 | 5 | **80** | Kopplung und Sync über das Heimnetz (PR #29, ADR 0006); Eskalation läuft auf jedem Gerät, das die Frist kennt | 4 × 2 × 4 = 32 | `threeDevicesConvergeByGossipWithoutAAndCEverMeeting`, Emulator-Kopplungstest; Rest P2-1/P2-2 |
| F7 | Erinnerung zur falschen Uhrzeit | Zeitzone oder Sommerzeit | Nachricht nachts | 2 | 2 | 2 | 8 | Wecker wird aus lokaler Zeit jedes Mal neu berechnet | 8 | `nothingArrivesInTheQuietHours` |
| F8 | Erinnerung für schon Erledigtes | Anderes Gerät noch nicht synchronisiert | Ärger, Misstrauen | 2 | 3 | 1 | 6 | Neutrale Texte ohne Vorwurf („Details siehst du in der App“) | 6 | `notificationIsShortNeutralAndNamesNoItem` |
| F9 | Falsche Person versehentlich bestätigt | Fehltipp | Sache gilt als vergeben | 3 | 3 | 2 | 18 | Rückgängig, Anzeigedauer nach Android-Empfehlung verlängerbar; Transparenz: alle sehen alle Stapel | 18 | `undoAfterCaptureRemovesTheItem`, `AccessibilityTest.feedbackIsAnnouncedByScreenReaders` |
| F10 | Zu viele Nachrichten, Erinnerungen werden ignoriert | Jede Frist einzeln gemeldet | Gewöhnung, echte Fristen gehen unter | 3 | 4 | 4 | **48** | Höchstens eine Nachricht am Tag, ohne Details (QA-12) | 3 × 1 × 2 = 6 | `atMostOneNotificationPerDayWhateverTheStages` |

## Gefahr 2: Daten weg

| # | Fehler | Ursache | Wirkung | S | A | E | RPZ vorher | Gegenmaßnahme | RPZ jetzt | Test oder Plan |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| D1 | Änderung wird nicht gespeichert | Speicher voll, Schreibfehler | App stürzte ab oder speicherte dauerhaft nicht mehr | 5 | 2 | 5 | **50** | Fehler wird abgefangen, bleibender Hinweis, jede Änderung versucht es erneut | 5 × 2 × 1 = 10 | `RepositoryRobustnessTest` (P0-1) |
| D2 | Gespeicherte Datei unlesbar | Halb geschriebene Datei, Fehler einer alten Version | App startet leer | 5 | 1 | 2 | 10 | Atomares Schreiben über Temp-Datei; unlesbare Datei wird beiseitegelegt statt überschrieben | 10 | `brokenFileIsKeptAndAppStartsEmpty` |
| D3 | Handy verloren, keine zweite Kopie | Kein Sicherungsordner, kein zweites Gerät | Alles weg | 5 | 3 | 5 | **75** | Verschlüsselte Sicherung in einen Ordner, Kopplung mit zweitem Gerät, nach der ersten Kopplung Angebot für die Sicherung | 5 × 2 × 5 = 50 | P0-3, PR #29. Offen: Hinweis für Familien mit nur einem Gerät und ohne Sicherungsordner, neue Plan-Nummer **P1-11** |
| D4 | Wiederherstellungscode verloren | Nicht aufgeschrieben | Sicherung nicht mehr lesbar | 5 | 3 | 5 | **75** | Code steht jederzeit in den Einstellungen, solange das Handy lebt; zweites gekoppeltes Gerät braucht keinen Code | 5 × 2 × 4 = 40 | `wrongOrMissingCodeIsReportedNotGuessed`; Rest P1-11 (Hinweis nennt auch den Code) |
| D5 | Falsche, ältere Sicherung wiederhergestellt | Fehlgriff | Neuere Einträge weg | 5 | 2 | 3 | **30** | Stand davor wird aufbewahrt, Rückgängig, Hinweis auf ältere Sicherung | 5 × 2 × 1 = 10 | `restoreOfAnOlderBackupWarnsAndCanBeUndone` (P0-2) |
| D6 | Update verliert Felder | Neues Speicherformat | Daten stillschweigend weg | 5 | 2 | 5 | **50** | Schema-Nummer, Migration, neuere Dateien werden nicht überschrieben | 5 × 1 × 1 = 5 | `FamilyJsonMigrationTest`, Emulator „Eintrag übersteht das Update“ (P0-7) |
| D7 | Sync überschreibt Neueres, Gelöschtes kommt zurück | Falsche Zusammenführung | Arbeit doppelt, Vertrauen weg | 4 | 3 | 4 | **48** | Stempel mit Hybrid-Uhr, Grabsteine | 4 × 1 × 3 = 12 | `newestVersionWinsAndTombstoneBeatsOlderEdit`, `deleteLeavesATombstoneAndUndoRevivesTheItem` |
| D8 | Foto weg, obwohl der Eintrag es braucht | Aufräumen zu früh | Beleg fehlt | 3 | 2 | 4 | 24 | Nur Fotos ohne Eintrag und älter als 1 Stunde | 24 | `orphanPhotosAreFoundButFreshOnesAreKept` |
| D9 | Sicherung im Cloud-Ordner von Dritten lesbar | Klartext-JSON | Familiendaten offen | 5 | 3 | 5 | **75** | Verschlüsselt (FAB1), keine lesbaren Namen im Ordner | 5 × 1 × 5 = 25 | `nothingReadableEndsUpInTheFolder` (P0-3) |

## Ergebnis

- 14 von 19 Zeilen waren vorher hoch. Alle 14 haben jetzt einen automatischen Test; D3 und D4 zusätzlich die neue Plan-Nummer P1-11.
- Jetzt noch hoch (RPZ ≥ 30 oder S = 5): F6 (32, bis der Sync auf allen Familiengeräten läuft) sowie D1, D2, D3, D4, D5, D6, D9 wegen der Schwere 5. Die Schwere lässt sich nicht senken, nur Auftreten und Entdeckung; bei D3 und D4 ist das mit P1-11 der nächste Schritt.
- Neu umgesetzt mit dieser Analyse: der Hinweis in den Einstellungen, wenn Android die Benachrichtigungen blockiert (F4).

## P1-11 (neu): Hinweis „Nur auf diesem Handy“

Wenn mindestens 10 Einträge existieren, kein Sicherungsordner gewählt und kein zweites Gerät gekoppelt ist, zeigt „Heute“ einmal eine ruhige Karte: „Deine Einträge liegen nur auf diesem Handy. Sicherung einrichten oder ein zweites Gerät koppeln.“ Wegklicken ist erlaubt, sie kommt dann erst nach 30 weiteren Einträgen wieder. Keine Systemnachricht (QA-12). Abnahme: Test mit Beispieldaten für Anzeigen, Nicht-Anzeigen und Wegklicken.
