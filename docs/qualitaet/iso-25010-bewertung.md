# Qualitätsbewertung nach ISO/IEC 25010:2023 und Umsetzungsplan

- Stand: 25.11.2026, Code-Stand `main` @ `fe577d5` (App-Version 0.2.x, Reifestufe Prototyp), offener PR #21 (nur Konzept-Backlog, ohne Einfluss)
- Bewertet: `app/` (KMP-App), Konzept, ADRs 0001–0005, Gestaltungsdoc. Die Spikes nur dort, wo die App auf ihnen aufbaut (Sync, Regel-Engine).
- Auftrag (Nils, 25.11.): „Beurteile die App nach ISO 25010 und entwirf einen Plan, die Punkte einzubauen.“

## Was diese Bewertung anders macht

1. **Gemessen statt geschätzt.** Die Befunde stützen sich auf Messungen am Code: Testlauf mit Zeilen- und Zweigabdeckung (JaCoCo), statische Prüfung (detekt 1.23.8), Kontrastberechnung aller Theme-Farben nach WCAG-Formel, eine Kapazitätsmessung des Speicherformats und eine Fehlerinjektion beim Schreiben. Alles liegt reproduzierbar in [`tools/quality-check/`](../../tools/quality-check/).
2. **Die Mental-Load- und HITL-Grundsätze sind als prüfbare Qualitätsanforderungen formuliert** (Abschnitt 2) und den ISO-Merkmalen zugeordnet. So wird „schnell rein, komplett später“ zu etwas, das ein Test brechen kann.
3. **Der Plan hat für jeden Punkt ein Abnahmekriterium und ein Werkzeug**, und er ist nach Risiko sortiert (Datenverlust und Sicherheit vor Komfort), nicht nach ISO-Kapitel.

## 1 Grundlage und Methode

### 1.1 Das Modell

ISO/IEC 25010:2023 beschreibt die **Produktqualität** mit 9 Merkmalen und 40 Untermerkmalen. Die Merkmale und Untermerkmale unten sind gegen [iso25000.com/…/iso-25010](https://iso25000.com/en/iso-25000-standards/iso-25010) geprüft (abgerufen am 25.11.2026, alle Unterseiten). Die Seite nennt kein Ausgabejahr; die Begriffe (Interaction Capability, Flexibility, Safety, Faultlessness, Resistance, Inclusivity, Self-descriptiveness) gibt es erst in der Ausgabe 2023.

Aus dem Gedächtnis, nicht an der Quelle geprüft: Gegenüber 2011 wurde Usability zu Interaction Capability, Portability zu Flexibility (plus Scalability), Safety kam neu dazu, Security bekam Resistance, Maturity heißt jetzt Faultlessness. Die Nutzungsqualität (Quality in Use) steht seit 2023 in einer eigenen Norm, ISO/IEC 25019. Kennzahlen zu den Merkmalen definiert ISO/IEC 25023, Qualitätsanforderungen ISO/IEC 25030, den Bewertungsablauf ISO/IEC 25040. Diese Bewertung folgt dem Ablauf von 25040 vereinfacht: Anforderungen festlegen (Abschnitt 2), messen (Abschnitt 1.3), bewerten (Abschnitt 3), Maßnahmen ableiten (Abschnitt 5).

### 1.2 Bewertungsmaßstab

Bewertet wird gegen zwei Ziele, weil die App gerade zwischen beiden steht:

- **Z1 Prototyp für die eigene Familie** (jetzt, Reifestufe Prototyp, ein Gerät, kein Sync).
- **Z2 Ausgewählte Familien mit Abo** (Konzept „MVP-Schnitt“ und Vertrieb).

| Note | Bedeutung |
| --- | --- |
| 3 | erfüllt und durch Test oder Messung belegt |
| 2 | im Wesentlichen erfüllt, Beleg lückenhaft |
| 1 | Ansatz vorhanden, deutliche Lücke |
| 0 | fehlt |
| n. a. | für Z1 noch nicht anwendbar (z. B. weil kein zweites Gerät existiert) |

### 1.3 Messungen

Alle Messungen laufen in der Cloud ohne Android SDK: [`tools/quality-check/build.gradle.kts`](../../tools/quality-check/build.gradle.kts) kompiliert die Compose-freien Schichten `domain`, `data`, `presentation`, `update` aus `app/shared/src/commonMain` als Kotlin/JVM und startet dieselben Tests aus `commonTest`. Befehl im Ordner `tools/quality-check`: `gradle check`. Die Werte unten sind der Stand vor der Umsetzung (25.11.2026); der Stand danach steht in Abschnitt 6.

| # | Messung | Ergebnis |
| --- | --- | --- |
| M1 | Unit-Tests der App (`commonTest` ohne `AppInfoTest`, der Compose braucht) | **86 Tests, 0 Fehler** |
| M2 | Abdeckung (JaCoCo) auf diesen Schichten | Zeilen **87 %**, Zweige **54 %**. domain 93/54, data 86/58, presentation 78/43, update 88/67 (Zeilen/Zweige in %). UI-Schicht (`ui/`, `App.kt`, rund 1.600 Zeilen) hat **keine** Unit- oder Compose-Tests, nur den Emulator-Smoke-Test |
| M3 | detekt 1.23.8, Standardregeln, App-Code | 337 Befunde: 129 MagicNumber, 111 MaxLineLength, 50 FunctionNaming (Fehlalarm: Composables heißen groß), 13 UnusedPrivateMember (zum Teil Previews), 7 SwallowedException, 6 LongParameterList, 6 TooManyFunctions, 5 ReturnCount, 3 LongMethod, 3 CyclomaticComplexMethod (`FamilyScreen` 18, `SwipeToDecide` 18, `GermanDateParser.find` 16) |
| M4 | Kontrast aller Text-/Grundfarben (WCAG-Formel, [Skript](../../tools/quality-check/contrast.py)) | Alle Textpaare ≥ 4,5:1 (kleinster Wert: Personenfarbe 4 hell auf Grund 4,82:1, `textTertiary` hell auf Grund 4,87:1). Initialen auf Personen-Punkten: hell ≥ 5,26:1, dunkel ≥ 8,35:1. Rahmen `outline` gegen Karte nur 1,45:1 |
| M5 | Kapazität des Speicherformats ([Probe](../../tools/quality-check/probes/CapacityProbe.kt)), JVM in der Cloud, nicht Handy | 1.000 Einträge: 456 KB JSON, Schreiben 10 ms, Lesen 38 ms. 5.000: 2,3 MB, 31 / 49 ms. 20.000: 9,2 MB, 75 / 138 ms. Ansichten (Eingang, Lastkarte, Demnächst, Suche) zusammen ≤ 26 ms bei 20.000 |
| M6 | Fehlerinjektion: Speicher wirft beim Schreiben „No space left on device“ (Probe, inzwischen der Test `RepositoryRobustnessTest`) | Die Ausnahme verlässt den Speicher-Coroutine-Job. Auf Android (`MainScope()` ohne Handler) beendet das den Prozess. Mit Handler wird danach **nie wieder gespeichert**, auch wenn wieder Platz ist (1 Schreibversuch für 2 Änderungen) |
| M7 | Code-Durchsicht aller App-Dateien (rund 4.000 Zeilen), CI-Workflow, Skripte | Befunde mit Datei und Zeile in Abschnitt 3 |

Nicht gemessen (braucht ein Gerät oder das Android SDK): Kaltstart, Latenz Kachel bis Mikrofon auf echter Hardware, Speicher- und Akkuverbrauch, TalkBack-Durchgang, Layout bei 200 % Schrift. Diese stehen als Messaufgaben im Plan.

## 2 Mental Load und HITL als Qualitätsanforderungen

Die Grundsätze aus [KONZEPT.md](../KONZEPT.md) und den Entscheidungen von Nils, formuliert nach ISO/IEC 25030: Jede Anforderung hat ein ISO-Merkmal, eine Prüfmethode und einen Ist-Stand.

| ID | Anforderung | ISO-Untermerkmal | Prüfung | Ist |
| --- | --- | --- | --- | --- |
| QA-1 | Erfassen ohne Frage und ohne Pflichtfeld; Aufnahme läuft ≤ 2 s nach Auslöser, gespeichert ohne Tap ≤ 1 s nach Sprechende (D8) | Zeitverhalten, Bedienbarkeit | In-App-Messung (gibt es), plus Messlauf auf Referenzgerät | gemessen nur in der App, kein Referenzwert dokumentiert |
| QA-2 | Eine Regel setzt **nie** „kümmert sich“; Vorschläge nur für „betrifft“ und „bis wann“ | Sicherheit (Safety): betriebliche Beschränkung | Unit-Test | erfüllt im Code (`CaptureRules` setzt kein `assignee`), Test `captureAppliesRulesAsSuggestionsOnly` |
| QA-3 | Mehrdeutiges ergibt **keinen** Vorschlag, sondern eine Frage | Safety: ausfallsicher | Unit-Test | erfüllt, Tests `twoNamesSuggestNobody`, `twoDatesSuggestNoDate` |
| QA-4 | Jeder Vorschlag zeigt, welche Regel gegriffen hat | Selbstbeschreibungsfähigkeit | UI-Test | erfüllt im Code („Regel: …“ auf der Karte), kein UI-Test |
| QA-5 | Nichts verschwindet still: offen bleibt offen, bis ein Mensch bestätigt oder „irrelevant“ wählt | Safety, Funktionale Korrektheit | Unit-Test | erfüllt (z. B. `removingMemberSendsTheirOpenItemsBackToInbox`), aber M6 zeigt einen stillen Datenverlust beim Speichern |
| QA-6 | Transparenz: Jede Statusänderung ist einer Person zuordenbar (wer hat erledigt, wer übernommen) | Security: Zurechenbarkeit | Unit-Test | **Lücke**: `Item` hat `createdBy`, aber kein `doneBy`/`changedBy`; `Stamp` kennt nur das Gerät |
| QA-7 | Fehlbedienung ist ohne Dialog rückgängig zu machen | Benutzerfehlerschutz | Unit-Test | erfüllt für Karten (6-s-Rückgängig), **nicht** für „Wiederherstellen“ |
| QA-8 | ≥ 80 % der Vorschläge ohne Änderung bestätigt (ohne Cloud-KI) | Funktionale Angemessenheit | Telemetrie lokal | Ein-Tap-Quote wird in den Einstellungen gezählt, Ziel dort 70 %; Abweichung zum Konzept (80 %) klären |
| QA-9 | Die App darf keinen neuen Mental Load erzeugen: Benachrichtigungen gebündelt, Ruhezeiten, Eskalation erst Zuständige, +1 Tag alle | Safety: Gefahrenwarnung; Nutzerbindung | Unit-Test mit Fake-Uhr | erfüllt seit P1-1 (`ReminderPlannerTest`), siehe Abschnitt 6 |
| QA-10 | Alles wirkt ohne Cloud-KI und ohne Server; Spracherkennung standardmäßig auf dem Gerät | Vertraulichkeit, Verfügbarkeit | Code-Prüfung, APK-Prüfung | erfüllt (`allowOnlineSpeech = false`, einzige Internetnutzung ist der Updater) |
| QA-11 | Beide Erwachsene sehen denselben Stand (Sync) | Funktionale Vollständigkeit, Interoperabilität | Konvergenztest | **fehlt in der App** (Spike vorhanden) |
| QA-12 | So wenig Benachrichtigungen wie möglich: höchstens eine Systemnachricht pro Tag, ohne Eintragsdetails; Wichtiges beim Öffnen der App (Nils, 26.11.2026) | Interaktionsfähigkeit, Sicherheit (Gefahrlosigkeit) | Test mit Fake-Uhr: ≤ 1 Nachricht pro Tag | erfüllt (`ReminderPlannerTest`) |

QA-6, QA-9 und QA-11 sind die drei Stellen, an denen die App das Kernversprechen gegen Mental Load noch nicht halten kann. Ohne Sync pflegt eine Person die App für alle, also genau das Risiko „App wird zur Managerin-Rolle“ aus dem Konzept.

## 3 Bewertung pro Merkmal

Schreibweise: Datei:Zeile beziehen sich auf `app/shared/src/commonMain/kotlin/io/github/respawnedguy/familyapp/` (kurz `…/`), wenn nicht anders angegeben.

### 3.1 Funktionale Eignung (Functional Suitability)

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Vollständigkeit | Vorhanden: Erfassen per Sprache, Foto, Text, Teilen, Kachel, Verknüpfung; Eingang mit HITL; Meins; Lastkarte; Demnächst (6 Wochen); Suche; Sicherung. Aus dem MVP-Schnitt fehlen: Kopplung und Sync, Eskalation, Benachrichtigungen, Mail-Weiterleitung, Texterkennung im Foto, Vorausschau-Paket Kinder, Wochen-Check-in, Ferien-Abo | 2 | 1 | Sync und Eskalation sind Kern, nicht Kür (QA-9, QA-11) |
| Korrektheit | Regel-Spike: 0 Falschzuweisungen bei 24 echten Mails. App: 86 Tests grün (M1), Datums-Parser 17 Tests. Zweigabdeckung domain nur 54 % (M2). Kein Test mit echten gesprochenen Sätzen (Spracherkennung liefert andere Schreibweisen als Mails, z. B. „bus Donnerstag“ statt „bis Donnerstag“ im Emulator) | 2 | 1 | Korpus echter Sprachtranskripte, Messung „falsches Datum“ |
| Angemessenheit | Ein-Tap-Quote wird lokal gezählt (`AppModel.oneTapShare`), Latenz D8 wird lokal gemessen (`recordLatency`). Ziele stehen in den Einstellungen, aber es gibt keine Auswertung über mehrere Wochen | 2 | 2 | Kennzahlen exportierbar machen (für Tagebuchstudie) |

### 3.2 Leistungseffizienz (Performance Efficiency)

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Zeitverhalten | D8-Messung in der App vorhanden (Median der letzten 50). Kaltstart-Trigger über `Process.getStartElapsedRealtime()` sauber gelöst (`MainActivity.kt` im androidApp). Keine Messung auf Referenzgerät, kein Macrobenchmark, kein Baseline Profile, R8 aus (ADR 0004) | 2 | 1 | Referenzwert auf Pixel und auf Android-8-Gerät; Baseline Profile |
| Ressourcennutzung | Jede Änderung schreibt die **ganze** Datei neu und ruft `fsync` (`AndroidTextStore.write`). Fotos werden in voller Kameraauflösung gespeichert und beim Löschen eines Eintrags **nicht** entfernt (`FamilyRepository.delete`, …/data/FamilyRepository.kt:221). Geteilte Bilder ohne Größengrenze kopiert (`AndroidPlatform.importSharedImage`) | 2 | 1 | Verwaiste Fotos löschen, Fotos verkleinern, Größengrenze |
| Kapazität | M5: Bei 5.000 Einträgen 2,3 MB pro Schreibvorgang, bei 20.000 9,2 MB. Eine Familie mit 5 Einträgen am Tag erreicht 5.000 nach knapp 3 Jahren; Erledigtes wird nie archiviert. Auf einem älteren Handy ist mit einem Mehrfachen der JVM-Zeiten zu rechnen (nicht gemessen) | 2 | 1 | Vor Sync auf satzweisen Speicher (SQLite) wechseln; das Änderungsprotokoll aus ADR 0003 braucht das ohnehin |

### 3.3 Kompatibilität (Compatibility)

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Koexistenz | Debug-Build mit eigener App-ID läuft neben Release (PR #22). Updater nur in Release. Keine Hintergrunddienste, die andere Apps stören | 3 | 3 | – |
| Interoperabilität | Rein: Teilen-Ziel für Text und Bild. Raus: lesbare JSON-Sicherung. Kein Kalender-Export (ICS), kein Kalender-Import, keine Mail-Weiterleitung. Datenformat hat `schema = 1`, aber keinen Migrationspfad (`FamilyJson.decode` verlässt sich auf `ignoreUnknownKeys`; Umbenennen oder Entfernen eines Felds bricht alte Dateien) | 2 | 1 | ICS-Abo-Link (Konzept), Schema-Migration mit Golden Files |

### 3.4 Interaktionsfähigkeit (Interaction Capability)

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Erkennbarkeit der Eignung | Onboarding mit einem Feld und Leitsatz „Schnell rein, komplett später.“ Konzept-Kriterium „erster Eintrag < 60 s ohne Erklärung“ ist nicht gemessen | 2 | 1 | 5-Personen-Test (siehe Plan) |
| Erlernbarkeit | Ein Muster überall: Karte, Chips, Wischen mit Knopf, Rückgängig. Keine Messung | 2 | 1 | wie oben |
| Bedienbarkeit | Ein Tap pro Karte möglich (D4), Erfassen mittig (D2), Kachel und Verknüpfung, Zurück-Taste sauber (`AppModel.handleBack`) | 3 | 2 | – |
| Benutzerfehlerschutz | Rückgängig für Karten, Löschen und Entfernen einer Person (`Undo` stellt genau die berührten Datensätze wieder her). **Wiederherstellen ersetzt alle Daten ohne Rückfrage und ohne Rückgängig** (`AppModel.restoreFromFile`, `FamilyRepository.restoreBackup`); eine alte Sicherung überschreibt neuere Daten | 2 | 1 | Schnappschuss vor dem Wiederherstellen + Rückgängig, Warnung bei älterer Sicherung |
| Nutzerbindung | Feste Farben, ruhige Bewegung, keine Vibration (entschieden). Kein Hinweis „was man vergessen darf“ (Konzept: Abschluss erlebbar), nur „diese Woche erledigt“ | 2 | 2 | – |
| Inklusivität | Kontraste erfüllt (M4). Personen-Punkte immer mit Initiale (D6). TalkBack-Aktionen „Passt/Irrelevant/Erledigt“ vorhanden (`Cards.kt`). Lücken: Entscheidungs-Chips nur 40 dp hoch (`Components.kt`, `DecisionChip`, Soll laut Gestaltungsdoc 48 dp); untere Leiste mit fester Höhe 80 dp (`App.kt`, `BottomBar`/`NavItem`), bei 200 % Schrift werden Beschriftungen abgeschnitten (Doc fordert 200 %); Rückmeldeleiste ist keine Live-Region, TalkBack sagt „Gespeichert“ nicht an (WCAG 4.1.3); feste 6 s für „Rückgängig“ ohne `getRecommendedTimeoutMillis` (WCAG 2.2.1); Tabs ohne `selected`-Zustand; keine Überschriften-Semantik; alle Texte hart im Kotlin-Code, keine Ressourcen, daher keine zweite Sprache und keine Pluralformen | 1 | 1 | siehe Plan P1-6 |
| Nutzerunterstützung | Fehlermeldungen in klarem Deutsch mit Ausweg („Tippen geht immer.“). Kein Hilfetext zu Lastkarte und Regeln außer der Kurzzeile | 2 | 2 | – |
| Selbstbeschreibungsfähigkeit | Stark: Jede Karte sagt, welche Regel was vorgeschlagen hat; gestrichelt = Vorschlag, durchgezogen = Entscheidung eines Menschen (`DecisionChip`) | 3 | 3 | – |

### 3.5 Zuverlässigkeit (Reliability)

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Fehlerfreiheit | 86 Tests grün, Emulator-Smoke auf API 26 und 36 mit Kernweg (Erfassen, Datumsvorschlag, „Mach ich“, Neustart). UI ohne automatische Tests außer diesem Weg | 2 | 1 | Compose-UI-Tests, Zweigabdeckung |
| Verfügbarkeit | Voll offline, kein Server, kein Konto (QA-10) | 3 | 3 | – |
| Fehlertoleranz | Gut: atomarer Austausch mit `fsync` (`AndroidTextStore`), unlesbare Datei wird beiseitegelegt statt überschrieben (Test `brokenFileIsKeptAndAppStartsEmpty`). **Schwer:** Ein Schreibfehler (voller Speicher, I/O-Fehler) beendet die App oder stoppt alles weitere Speichern still (M6, …/data/FamilyRepository.kt:69–72) | 1 | 1 | P0-1 |
| Wiederherstellbarkeit | Automatische Sicherung in einen gewählten Ordner, 3 s nach Änderung (`AppModel.startAutoBackup`), Wiederherstellen vorhanden. Lücken: Sicherung ist Opt-in; **Fotos sind nicht in der Sicherung** (nur Pfade); nach Neuinstallation zeigen die Pfade ins Leere. Kein Test „Daten überstehen ein App-Update“ (der Update-Test im CI installiert die alte Version ohne Daten) | 2 | 1 | Fotos in die Sicherung, Update-mit-Daten-Test |

### 3.6 Informationssicherheit (Security)

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Vertraulichkeit | App-Daten im privaten Speicher (Android-Sandbox plus dateibasierte Verschlüsselung des Systems), `allowBackup=false`, Sprache standardmäßig offline. **Aber:** Die Sicherung ist lesbares JSON und landet typischerweise in einem synchronisierten Ordner (Synology Drive, Google Drive). Das widerspricht der Begründung für `allowBackup=false` („kein unverschlüsseltes Cloud-Backup“). Inhalte sind teils Gesundheitsdaten von Kindern (Arzttermine), also DSGVO Art. 9 | 2 | 0 | Sicherung verschlüsseln (P0-3) |
| Integrität | Updater vorbildlich: URL-Präfix, Größe, SHA-256, Paketname, versionCode und Signaturzertifikat geprüft (ADR 0005), im Emulator mit falscher Prüfsumme und fremdem Schlüssel getestet. Daten: atomar geschrieben. Sync-Integrität (Signaturen pro Änderung) nur im Spike | 3 | 2 | – |
| Nichtabstreitbarkeit | Einzelgerät: n. a. Spike signiert jede Änderung mit Ed25519 (ADR 0003) | n. a. | 1 | bei Sync übernehmen |
| Zurechenbarkeit | Nur `createdBy` und Gerät im `Stamp`. Wer erledigt, übernommen oder umbenannt hat, wird nicht gespeichert (QA-6). Für „alle sehen die Stapel aller, kein heimliches Erledigen“ ist das ein inhaltlicher, nicht nur technischer Mangel | 1 | 0 | P0-5 |
| Authentizität | Einzelgerät: n. a. Kopplung per QR mit Bestätigung auf beiden Geräten im Spike; ADR 0003 verlangt für das Produkt Noise XX und libsodium statt Eigenbau | n. a. | 1 | bei Sync |
| Widerstandsfähigkeit | Teilen-Ziel ist exportiert (gewollt). `importSharedImage` öffnet jede übergebene URI ohne Schema-Prüfung und ohne Größengrenze; eine fremde App kann so z. B. eine `file://`-URI auf die eigene `familyapp.json` schicken (Datei landet als „Foto“ in einem Eintrag, verlässt das Gerät aber nicht). Wiederherstellen liest die gewählte Datei komplett in den Speicher (`readBytes`). Kein Bedrohungsmodell dokumentiert | 2 | 1 | P1-8 |

### 3.7 Wartbarkeit (Maintainability)

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Modularität | Saubere Schichten domain → data → presentation → ui, Plattform hinter `PlatformServices`, Speicher hinter `TextStore`. `AppModel` (348 Zeilen) bündelt Navigation, Erfassen, Sicherung und Kennzahlen und wird mit jeder Funktion wachsen | 3 | 2 | `AppModel` vor Sync teilen |
| Wiederverwendbarkeit | `GermanDateParser` existiert zweimal (Spike und App) und ist bereits auseinandergelaufen (Diff nicht leer). Regel-Engine des Spikes ist in der App nur teilweise übernommen (`CaptureRules`) | 2 | 1 | eine Quelle, Spike als Testkorpus nutzen |
| Analysierbarkeit | Kommentare erklären das Warum, ADRs vollständig. Keine Protokollierung, kein lokaler Absturzbericht: Wenn bei Nils etwas schiefgeht, gibt es nichts zum Nachsehen | 2 | 1 | lokales Fehlerprotokoll, nur auf Wunsch teilbar |
| Änderbarkeit | Exakte Versionen (Katalog), aber keine Gradle-Lockfiles (ADR 0004 nennt das als offen). Keine statische Prüfung im CI; detekt findet 337 Stellen (M3), davon die meisten Stil, relevant sind 7 verschluckte Ausnahmen und 3 zu komplexe Funktionen | 2 | 1 | detekt + Compose-Regeln mit Baseline im CI |
| Testbarkeit | Sehr gut in den unteren Schichten: Uhr, IDs, Speicher und Plattform sind injizierbar, Fakes vorhanden. UI ist nicht testbar ohne Emulator, und es gibt keine Compose-Tests | 2 | 1 | Compose-UI-Tests (P1-4) |

### 3.8 Flexibilität (Flexibility)

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Anpassbarkeit | iOS ist vorbereitet, wird aber nirgends gebaut. Ohne iOS-Build im CI kann `commonMain` unbemerkt JVM- oder Android-Abhängigkeiten bekommen | 2 | 1 | iOS-Kompilierung im CI (wöchentlich reicht) |
| Skalierbarkeit | Nur 4 Personenfarben (`PERSON_COLOR_COUNT = 4`, …/domain/Model.kt:7); die fünfte Person bekommt eine schon vergebene Farbe. Patchwork- oder Pflegefamilien haben schnell mehr als 4 Personen. Datenmenge siehe 3.2 | 2 | 1 | Palette auf 8 erweitern, Kontrast nachrechnen |
| Installierbarkeit | Vorbildlich: signierte Universal-APK, `verify-apk.sh`, Emulator-Installation auf Android 8 und 16, Update-Test, Sideloading-Anleitung. Risiko: Googles Entwicklerverifizierung ab Januar 2027 (ADR 0004) | 3 | 2 | Entwicklerkonto vor Januar 2027 |
| Ersetzbarkeit | Kein Import aus Google Kalender/TimeTree/FamilyWall; Umstieg heißt Neuanfang. Export vorhanden (JSON) | 1 | 1 | ICS-Import |

### 3.9 Sicherheit im Sinne von Gefahrlosigkeit (Safety)

Für eine Familien-App heißt Safety: Niemand kommt zu Schaden, weil die App einen Termin falsch, gar nicht oder zu spät zeigt (Impftermin, Medikament, Abgabefrist, Abholung) oder weil Daten verloren gehen.

| Untermerkmal | Befund mit Beleg | Z1 | Z2 | Lücke |
| --- | --- | --- | --- | --- |
| Betriebliche Beschränkung | HITL ist im Code verankert: Regeln setzen nie „kümmert sich“ (QA-2), eine Karte gilt erst als bestätigt, wenn Person und Datum feststehen (`readyToConfirm`) | 3 | 3 | – |
| Risikoerkennung | Keine Gefahrenanalyse dokumentiert. Konzept nennt „Erkennung liest Datum falsch“ als Risiko, gemessen wird es nicht | 1 | 0 | FMEA-light (P1-9) |
| Ausfallsicher | Mehrdeutig ergibt keinen Vorschlag (QA-3), Original bleibt erhalten. Gegenbeispiel: Speicherfehler (M6) | 2 | 1 | P0-1 |
| Gefahrenwarnung | Überfällig wird rot angezeigt und bleibt in „Demnächst“ sichtbar (`Views.upcoming`). Aber **ohne Benachrichtigung** sieht es nur, wer die App öffnet. Die gestufte Eskalation aus dem Konzept fehlt | 1 | 0 | P1-1 |
| Sichere Integration | Updater ersetzt die App nur nach Bestätigung, prüft Signatur; Daten überleben ein Update bisher ungeprüft (3.5) | 2 | 1 | Update-mit-Daten-Test |

### 3.10 Übersicht

| Merkmal | Z1 (Prototyp, eigene Familie) | Z2 (ausgewählte Familien) | Größte Lücke |
| --- | --- | --- | --- |
| Funktionale Eignung | 2 | 1 | Sync und Eskalation fehlen |
| Leistungseffizienz | 2 | 1 | Ganzdatei-Speicher, verwaiste Fotos |
| Kompatibilität | 2,5 | 2 | keine Schema-Migration, kein ICS |
| Interaktionsfähigkeit | 2 | 1,5 | Barrierefreiheit (200 %, Live-Region, Zielgröße), Wiederherstellen ohne Rückgängig |
| Zuverlässigkeit | 2 | 1,5 | Schreibfehler beendet das Speichern |
| Informationssicherheit | 2 | 1 | Klartext-Sicherung, fehlende Zurechenbarkeit |
| Wartbarkeit | 2 | 1 | keine statische Prüfung, UI ungetestet |
| Flexibilität | 2 | 1,5 | iOS ungebaut, 4 Farben |
| Safety | 2 | 1 | keine Warnung bei verpasster Frist |

Kurz: Für einen Prototyp nach drei Projekttagen ist die Qualität hoch. Besonders stark sind Installierbarkeit und Update-Integrität, Selbstbeschreibung der Vorschläge, HITL im Code und Testbarkeit der unteren Schichten. Für Z2 fehlen vor allem Dinge, die Daten und Vertrauen schützen.

## 4 Was ein Prüfer zusätzlich verlangen würde

- **Gefahren- und Datenschutz-Folgenabschätzung** vor der ersten fremden Familie. Gesundheitsdaten von Kindern fallen unter DSGVO Art. 9; eine DSFA nach Art. 35 ist bei dieser Datenart naheliegend (rechtlich prüfen lassen, keine Rechtsberatung).
- **Bedrohungsmodell (STRIDE)** für Teilen-Ziel, Sicherung, Updater und später Kopplung und Sync. Die ADRs enthalten viele Einzelentscheidungen, aber keine Gesamtsicht.
- **Regressionsdaten aus echter Nutzung** statt nur gebauter Beispiele: der private Korpus des Regel-Spikes plus echte Sprachtranskripte, ausgewertet wie im Spike (`expected-private.csv`), nie im Repo.
- **Messung auf Referenzgeräten**: einem aktuellen Pixel und dem ältesten unterstützten Gerät (Android 8). Emulatoren sagen über Zeitverhalten wenig.

## 5 Umsetzungsplan

Sortiert nach Risiko. Jeder Punkt nennt Ziel, Abnahmekriterium und Werkzeug. Aufwand grob in Personentagen (PT) für Claude-Arbeit plus CI-Lauf.

### P0 Datenverlust und Vertrauen (vor Kopplung und Sync, also in oder direkt nach 0.3)

| # | Maßnahme | Abnahmekriterium | Werkzeug | Merkmal | PT |
| --- | --- | --- | --- | --- | --- |
| P0-1 | **Schreibfehler abfangen**: Speichern läuft in `runCatching` mit Wiederholung; Fehler wird als dauerhafter Hinweis gezeigt („Speichern fehlgeschlagen, Speicher voll?“); nächste Änderung versucht es erneut | `WriteFailureProbe` wird zum echten Test: nach einem Fehler und freiem Speicher sind beide Änderungen auf Platte, kein Absturz | `commonTest`, Fake-`TextStore` | Fehlertoleranz, Safety | 0,5 |
| P0-2 | **Wiederherstellen absichern**: vorher automatischer Schnappschuss, danach 6-s-Rückgängig plus Eintrag „Vorigen Stand zurückholen“ in den Einstellungen; Warnung, wenn die Sicherung älter ist als der neueste Stempel auf dem Gerät | Test: Wiederherstellen einer älteren Sicherung, danach Rückgängig, Stand identisch zum Ausgang | `commonTest` | Benutzerfehlerschutz, Wiederherstellbarkeit | 1 |
| P0-3 | **Sicherung verschlüsseln** mit dem Wiederherstellungscode (D7). Umgesetzt mit AES-256-GCM und HKDF-SHA256 aus dem JDK statt libsodium (siehe Abschnitt 6) | Test wie `PlaintextScanTest` im Sync-Spike: kein Titel, kein Name im Sicherungsbyte-Strom; Wiederherstellen mit falschem Code scheitert verständlich | libsodium-Bindung für KMP (Auswahl prüfen), `commonTest` | Vertraulichkeit | 2 |
| P0-4 | **Fotos in die Sicherung** (ZIP mit JSON + Bildern) und **verwaiste Fotos löschen** beim Löschen eines Eintrags und beim Start | Test: Eintrag mit Foto löschen, Datei ist weg; Sicherung → Neuinstallation → Wiederherstellen, Foto sichtbar (Emulator-Skript) | `commonTest`, `emulator-smoke.sh` | Wiederherstellbarkeit, Ressourcen | 1 |
| P0-5 | **Zurechenbarkeit**: `changedBy` (Mitglied) in jeden `Stamp`, `doneBy` am Eintrag; Anzeige „erledigt von Lisa“ und in der Lastkarte | Test: jede Repository-Aktion setzt `changedBy = selfId`; UI zeigt „von …“ bei Erledigtem | `commonTest` | Zurechenbarkeit (QA-6) | 1 |
| P0-6 | **Grabsteine statt hartem Löschen** (ADR 0002 sieht sie vor). Ohne sie kommen gelöschte Einträge beim ersten Sync zurück | Test: Löschen erzeugt Grabstein mit Stempel; Ansichten zeigen nichts; Zusammenführen zweier Stände (vorbereitet) lässt Gelöschtes gelöscht | `commonTest` | Korrektheit, Integrität | 0,5 |
| P0-7 | **Daten überstehen Update und Schema-Wechsel**: Golden Files für jedes veröffentlichte Schema in `commonTest/resources`, Migration pro Version; im CI vorige Release installieren, Eintrag anlegen, neue Version drüber, Eintrag prüfen | Test lädt jede Golden File ohne Verlust; Emulator-Bericht enthält „Eintrag übersteht Update“ | `commonTest`, `emulator-smoke.sh` | Wiederherstellbarkeit, Sichere Integration | 1 |

### P1 Qualitätstore im CI und die wichtigsten Produktlücken (vor der ersten fremden Familie)

| # | Maßnahme | Abnahmekriterium | Werkzeug | Merkmal | PT |
| --- | --- | --- | --- | --- | --- |
| P1-1 | **Benachrichtigungen und gestufte Eskalation** (lokal, ohne Server, wie ADR 0003): Frist fällig → Zuständige; +1 Tag → alle Geräte, die die Frist kennen; Bündelung, Ruhezeit 21–7 Uhr; Text neutral | Tests mit Fake-Uhr: richtige Person, richtige Zeit, keine Meldung für Erledigtes, höchstens eine gebündelte Meldung pro Stunde; Emulator: Meldung erscheint nach Zeitsprung | `AlarmManager` (exakt nur wo nötig) bzw. WorkManager, `POST_NOTIFICATIONS` ab Android 13 | Gefahrenwarnung, Vollständigkeit (QA-9) | 3 |
| P1-2 | **Statische Prüfung**: detekt mit Compose-Regeln (`io.nlopez.compose.rules:detekt`), Baseline für den Bestand, `FunctionNaming` für `@Composable` freigeben; Android Lint voll (`lintRelease`) mit Baseline, Kategorie Accessibility und Security als Fehler | CI schlägt bei **neuen** Befunden fehl; Baseline schrumpft je Release | detekt, Android Lint | Änderbarkeit, Analysierbarkeit | 1 |
| P1-3 | **Abdeckung als Tor**: Kover (JetBrains, KMP-fähig) im App-Build, Schwellen ab Ist-Wert: domain ≥ 90 % Zeilen / ≥ 70 % Zweige, data ≥ 85 / 65, presentation ≥ 80 / 55; Bericht als Release-Anhang | CI schlägt unter Schwelle fehl | Kover | Testbarkeit, Fehlerfreiheit | 0,5 |
| P1-4 | **Compose-UI-Tests mit Barrierefreiheitsprüfung** für die 5 Bildschirme und das Erfassen-Blatt, im Emulator-Job; zusätzlich Screenshot-Tests bei Schriftgröße 1,0 und 2,0, hell und dunkel | 0 Fehler der Accessibility Test Framework-Prüfungen (Zielgröße, Kontrast, Beschriftung); kein abgeschnittener Text bei 2,0 (Screenshot-Vergleich) | `compose-ui-test` mit Accessibility-Checks (in Compose 1.8+ eingebaut, Version prüfen), Roborazzi oder Paparazzi | Inklusivität, Fehlerfreiheit | 2 |
| P1-5 | **Datumsparser absichern**: eigenschaftsbasierte Tests (z. B. „nie ein Datum in der Vergangenheit für ‚nächsten Freitag‘“, „zwei Daten → kein Vorschlag“), dazu ein privater Korpus echter Sprachtranskripte; Kennzahl „falsches Datum vorgeschlagen“ | Falsch-Datum-Quote **0** im Korpus (falsch ist gefährlicher als keins); Mehrdeutig ≤ 20 % | Kotest Property, Auswertung wie Regel-Spike, `*-private` nie committen | Korrektheit, Safety | 1,5 |
| P1-6 | **Barrierefreiheit beheben**: Chips ≥ 48 dp, `BottomBar` mit Mindest- statt fester Höhe, Rückmeldeleiste als `liveRegion = Polite`, Anzeigedauer über `AccessibilityManager.getRecommendedTimeoutMillis`, `selected` für Tabs, `heading()` für Überschriften, Radio-Zeilen als `selectable` | Durch P1-4 automatisch geprüft; einmal TalkBack-Durchgang aller Klickwege durch Nils (Checkliste aus dem Gestaltungsdoc) | Compose Semantics | Inklusivität | 1 |
| P1-7 | **Texte in Ressourcen** (Compose Multiplatform Resources), Pluralformen; Voraussetzung für iOS-Lokalisierung und Barrierefreiheit (Sprache setzbar) | Keine deutschen String-Literale mehr in `ui/` (Lint-Regel oder detekt `StringLiteralDuplication` + eigene Prüfung) | CMP Resources | Inklusivität, Anpassbarkeit | 1,5 |
| P1-8 | **Härtung Eingänge**: Teilen nur `content://`, nicht die eigene Authority, Größengrenze 20 MB, MIME-Prüfung; Wiederherstellen mit Größengrenze; kurzes STRIDE-Bedrohungsmodell als `docs/qualitaet/bedrohungsmodell.md` | Instrumentierter Test: `file://`-URI und Übergröße werden abgelehnt | Emulator-Test | Widerstandsfähigkeit | 1 |
| P1-9 | **Gefahrenanalyse (FMEA-light)** für „Termin falsch, fehlt, zu spät“ und „Daten weg“: Ursache, Wirkung, Erkennung, Gegenmaßnahme, Test | Jede Zeile mit Bewertung ≥ hoch hat einen Test oder eine Plan-Nummer | Tabelle in `docs/qualitaet/` | Risikoerkennung | 0,5 |
| P1-10 | **Referenzmessung auf Geräten**: Kaltstart, Kachel → Mikrofon bereit (D8), Speichern nach Sprechende, auf Nils' Pixel und einem Android-8-Gerät; Kennzahlen aus der App exportierbar (CSV in den Ordner der Sicherung) | Median Kachel → Mikrofon ≤ 2 s auf beiden Geräten, dokumentiert in diesem Doc | vorhandene In-App-Messung, Macrobenchmark optional | Zeitverhalten (QA-1) | 0,5 |

### P2 Vor Abo und weiteren Plattformen

| # | Maßnahme | Abnahmekriterium | Werkzeug | Merkmal |
| --- | --- | --- | --- | --- |
| P2-1 | Satzweiser Speicher (SQLDelight oder Room KMP) mit Änderungsprotokoll, als Unterbau für Sync | M5 wiederholt: eine Änderung schreibt < 10 KB unabhängig von der Datenmenge | SQLDelight/Room | Kapazität, Ressourcen |
| P2-2 | Sync aus dem Spike in die App, mit Noise XX und libsodium (ADR 0003) | Konvergenztest aus dem Spike läuft gegen die App-Implementierung; fremdes Gerät abgewiesen | Spike-Tests übernehmen | Vollständigkeit, Authentizität, Nichtabstreitbarkeit |
| P2-3 | iOS-Framework im CI kompilieren (wöchentlich, macOS-Runner kostet mehr Minuten) | `linkDebugFrameworkIosSimulatorArm64` grün | GitHub Actions macOS | Anpassbarkeit |
| P2-4 | Personenpalette auf 8 Farben, Kontrast mit `contrast.py` nachgerechnet | Alle Paare ≥ 4,5:1, keine doppelte Farbe bis 8 Personen | Skript | Skalierbarkeit |
| P2-5 | R8 an (mit Regeln für kotlinx.serialization), Baseline Profile | APK-Größe und Kaltstart gemessen vor/nach | AGP | Ressourcen, Zeitverhalten |
| P2-6 | DSFA und Datenschutzhinweis, Entwicklerverifizierung bei Google vor Januar 2027 | Dokument liegt vor; Installation auf zertifiziertem Gerät nach Stichtag getestet | – | Vertraulichkeit, Installierbarkeit |
| P2-7 | 5-Personen-Test „erster Eintrag ohne Erklärung“ und Tagebuchstudie aus dem Konzept | < 60 s Median; Fragebögen wie im Konzept | – | Erkennbarkeit, Erlernbarkeit (Nutzungsqualität nach ISO 25019) |

### Reihenfolge

1. **Jetzt (rund 1 Woche Claude-Arbeit):** P0-1 bis P0-7 in zwei PRs. P0-1, P0-2, P0-5, P0-6 sind klein und betreffen nur `data`; P0-3, P0-4, P0-7 brauchen Emulator und eine Bibliotheksauswahl.
2. **Parallel als CI-PR:** P1-2 und P1-3 (Baseline, kein Code-Umbau nötig).
3. **Danach:** P1-1 (Eskalation) und P1-4/P1-6 (Barrierefreiheit), dann Sync (P2-1, P2-2).

Wiederholung dieser Bewertung: nach jedem Minor-Release die Messungen in [`tools/quality-check/`](../../tools/quality-check/) neu laufen lassen und die Tabelle in 1.3 fortschreiben.

## 6 Umsetzungsstand

### 26.11.2026: P0 komplett, P1-2 und P1-3 als CI-Tor, Teile von P1-8

Nils hat den Plan am 26.11. freigegeben („Go“).

| # | Umgesetzt | Beleg |
| --- | --- | --- |
| P0-1 | `FamilyRepository.persist` fängt jeden Schreibfehler ab, `saveProblem` zeigt einen bleibenden Hinweis mit „Erneut speichern“; jede spätere Änderung versucht es wieder. `AndroidTextStore` räumt eine halb geschriebene Temp-Datei weg, der iOS-Speicher meldet Fehler statt sie zu verschlucken | `RepositoryRobustnessTest.failedWriteIsReportedAndLaterChangesAreSavedAgain`, `retrySaveWritesTheCurrentState` |
| P0-2 | Vor dem Wiederherstellen wird der jetzige Stand als Datei aufbewahrt (`familyapp-vor-wiederherstellung.json`); 6 s Rückgängig und dauerhaft „Stand vor der Wiederherstellung zurückholen“ in den Einstellungen; Hinweis, wenn die Sicherung älter ist. Datensätze behalten beim Wiederherstellen ihre Stempel, damit eine alte Sicherung später keinen Sync gewinnt | `restoreOfAnOlderBackupWarnsAndCanBeUndone`, `stateBeforeRestoreCanBeBroughtBackLater`, `restoreKeepsStampsSoAnOldBackupCannotWinASync` |
| P0-3 | Sicherung im Ordner ist verschlüsselt: Format „FAB1“, AES-256-GCM, Schlüssel per HKDF-SHA256 aus dem Wiederherstellungscode, Dateiname als zusätzliche Authentisierung. Der Code (20 Zeichen Crockford-Base32, rund 100 Bit) entsteht beim Wählen des Ordners und steht in den Einstellungen. Ein langsamer Passwort-Hash ist unnötig, weil der Code zufällig ist und kein ausgedachtes Passwort; das hält die Sicherung mit vielen Fotos schnell. Die alte lesbare `familyapp-sicherung.json` wird nach der ersten verschlüsselten Sicherung gelöscht, kann aber weiter wiederhergestellt werden | `BackupFolderTest` (u. a. `nothingReadableEndsUpInTheFolder`, `wrongOrMissingCodeIsReportedNotGuessed`, `changedFileIsRejected`) |
| P0-4 | Fotos liegen verschlüsselt in `familyapp-fotos/` neben der Sicherung, jedes nur einmal geschrieben und entfernt, wenn kein Eintrag es mehr braucht. Wiederherstellen packt sie in den App-Speicher und biegt die Pfade um. Verwaiste Fotos im App-Speicher werden beim Start gelöscht (älter als 1 Stunde, damit Rückgängig in der Sitzung funktioniert) | `roundTripRestoresDataAndPhotos`, `unusedPhotosAndThePlainOldBackupAreRemoved`, `restoredPhotosPointToTheUnpackedFiles`, `orphanPhotosAreFoundButFreshOnesAreKept` |
| P0-5 | `changedBy` an jedem Eintrag, `doneBy` beim Erledigen. Die Suche zeigt „erledigt von Lisa“, die Lastkarte zählt pro Person, was diese Woche erledigt wurde | `everyChangeRecordsWhoMadeIt`, `loadChartCountsWhatEachPersonFinishedThisWeek` |
| P0-6 | Löschen eines Eintrags oder einer Person hinterlässt einen Grabstein ohne Inhalt; Rückgängig entfernt ihn wieder | `deleteLeavesATombstoneAndUndoRevivesTheItem`, `removingAMemberLeavesATombstone` |
| P0-7 | Speicherformat jetzt `schema = 2`, `FamilyJson.decode` hebt ältere Dateien an und verweigert Dateien einer neueren App (statt beim nächsten Schreiben Felder zu verlieren). Golden File von Schema 1, erzeugt mit dem Code von `main` @ `fe577d5`. Emulator-Test prüft zusätzlich, dass ein Eintrag das Update übersteht | `FamilyJsonMigrationTest`, `emulator-smoke.sh` („Eintrag übersteht das Update“) |
| P1-2, P1-3 | Workflow `quality.yml`: Tests, Abdeckungs-Untergrenze (Zeilen 88 %, Zweige 55 %) und detekt mit Baseline (280 Altbefunde), schlägt bei neuen Befunden fehl. Gegenprobe: eine Datei mit verschluckter Ausnahme lässt `detekt` scheitern | `tools/quality-check`, `gradle check` |
| P1-8 (Teil) | Teilen nimmt nur `content://`-Adressen fremder Apps an und höchstens 20 MB; Fotonamen aus einer Sicherung können den Fotoordner nicht verlassen | `photoNamesFromABackupCannotEscapeThePhotoFolder`; Teilen-Prüfung nur im Code, Emulator-Test fehlt noch |

Messwerte danach (`gradle check` am 26.11.2026): 112 App-Tests grün (vorher 86), Abdeckung Zeilen 89 %, Zweige 56 % (vorher 87 / 54).

Abweichung vom Gestaltungsdoc: Der Wiederherstellungscode (D7) erscheint jetzt schon, wenn jemand einen Sicherungsordner wählt, nicht erst nach dem ersten Pairing. Ohne Code gäbe es keine verschlüsselte Sicherung. Die Karte „Sichere deinen Wiederherstellungscode“ nach Pairing oder 10 Einträgen bleibt für alle, die keinen Ordner gewählt haben.

### 26.11.2026: P1-1 Erinnerungen und gestufte Eskalation

Nils hat P1 am 26.11. gestartet („go“).

| Regel | Umsetzung | Beleg |
| --- | --- | --- |
| Frist fällig → Zuständige | Wer sich kümmert, bekommt um 8 Uhr am Tag der Frist „Heute fällig: …“ und, falls noch offen, am Tag danach „Frist abgelaufen“ | `ownDeadlineRemindsOnTheDayAndOnceMoreWhenMissed` |
| +1 Tag → alle | Alle anderen Geräte, die die Frist kennen, melden sie einen Tag nach der verpassten Frist (Tag +2). Hat niemand die Karte übernommen oder liegt sie noch im Eingang, melden alle gleich am Tag +1 | `someoneElsesDeadlineReachesMeOneDayAfterItWasMissed`, `deadlineWithoutAnyoneInChargeReachesEveryoneRightAfterIt` |
| Kein Nörgeln | Höchstens drei Meldungen pro Eintrag, nie täglich; Erledigtes, Irrelevantes und Einträge ohne Datum melden nie; alles um 8 Uhr, also nie in der Ruhezeit 21–7 Uhr | `finishedDismissedAndUndatedItemsNeverRemind`, `nothingArrivesInTheQuietHours` |
| Bündelung | Was zur selben Zeit fällig ist, kommt als eine Meldung („2 Fristen brauchen heute einen Blick“); eine feste Meldungs-ID ersetzt die vorige statt zu stapeln. War das Handy aus, fasst der nächste Lauf alles Verpasste zu einer Meldung zusammen, je Eintrag nur der neueste Stand | `remindersAtTheSameMomentAreBundledIntoOne`, `runsMissedWhileThePhoneWasOffBecomeOneNotificationWithTheNewestStatePerItem` |
| Neutraler Text | Kein Vorwurf, weil das Gerät ohne Sync nicht wissen kann, ob jemand anderes es schon erledigt hat | `notificationIsShortNeutralAndNamesNoItem` |
| Kein Altlasten-Schwall | Beim ersten Lauf wird nur „jetzt“ als geprüft vermerkt; alte Fristen melden sich nicht nachträglich | `firstRunShowsNothingOldButPlansTheNextAlarm` |
| Erlaubnis im richtigen Moment | Android 13+ fragt einmal, wenn die erste Frist bestätigt wird, nicht beim ersten Start; nie, wenn Erinnerungen aus sind | `notificationPermissionIsAskedOnceWhenTheFirstDeadlineIsConfirmed`, `switchedOffRemindersNeverAskForThePermission` |
| Abschaltbar | Einstellungen → „An Fristen erinnern“ (ganze Zeile ist ein Schalter); aus = kein Wecker, keine Meldung | `switchedOffMeansNoNotificationAndNoAlarm` |

Technik: Die Regeln sind eine reine Funktion `ReminderPlanner` (gemeinsamer Code, Fake-Uhr-Tests im Qualitätstor). Android stellt genau einen ungenauen Wecker (`AlarmManager.setAndAllowWhileIdle`, keine Berechtigung für exakte Wecker nötig, ein paar Minuten Verzug um 8 Uhr sind egal), plant nach jeder Änderung (2 s entprellt), nach Neustart (`BOOT_COMPLETED`) und nach einem Update neu. Kanal „Fristen“ ohne Vibration (Gestaltungsentscheidung). Emulator-Test prüft, dass nach „Mach ich“ ein Wecker geplant ist.

Grenzen: Ohne Sync erinnert ein Gerät nur an Fristen, die es selbst kennt; bis P2-1 ist das in der Regel nur das Gerät, auf dem erfasst wurde. Das Akzeptanzkriterium „Meldung erscheint nach Zeitsprung im Emulator“ ist noch nicht automatisiert, geprüft ist der geplante Wecker. iOS bekommt Erinnerungen mit der iOS-Portierung.

Messwerte danach (`gradle check`): 127 App-Tests grün, Abdeckung Zeilen 89 %, Zweige 57 %.

Nachgeschärft am 26.11.2026 nach Nils' Prämisse „so wenig Benachrichtigungen wie möglich; wichtige Infos beim Starten der App, nicht über den Tag verteilt; bewusst organisieren statt Hintergrundrauschen“ (QA-12, neu):

| Regel | Umsetzung | Beleg |
| --- | --- | --- |
| Höchstens eine Systemnachricht pro Tag | Alle Stufen fallen auf 8 Uhr und werden zu einer Nachricht gebündelt; zehn Fristen in allen Stufen ergeben pro Morgen höchstens eine | `atMostOneNotificationPerDayWhateverTheStages` |
| Kurz, ohne Einzelheiten | Nur „Dringende Frist heute“, „Eine Frist ist abgelaufen“ oder „N Fristen brauchen heute einen Blick“, dazu „Details siehst du in der App.“ Kein Eintragstitel, also auch nichts Privates auf dem Sperrbildschirm | `notificationIsShortNeutralAndNamesNoItem` |
| Details beim Öffnen | Oben auf „Heute“ steht die Karte „Heute wichtig“ mit jeder dringenden Frist und dem Grund („heute fällig, bei dir“, „Frist war Mi 25.11., Lisa kümmert sich“, „noch niemand zuständig“). Dieselben Regeln wie die Nachricht, nur der neueste Stand je Eintrag, eigene zuerst | `appShowsTheLatestStagePerItemOwnThingsFirst`, `appLinesSayWhyWithoutBlame` |
| Eskalationsstufen | Bleiben als Regel, wer wann davon erfährt, erzeugen aber keine eigene Nachricht mehr, sondern zählen in der einen Morgennachricht mit | wie oben |

QA-12 (neu): Die App sendet höchstens eine Systemnachricht pro Tag und Gerät, ohne Eintragsdetails; alles Weitere zeigt sie beim Öffnen. Messgröße: Nachrichten pro Tag ≤ 1 (Test mit Fake-Uhr).

### 26.11.2026: P1-6 Barrierefreiheit, P1-4 begonnen

| Lücke aus 3.4 | Umsetzung |
| --- | --- |
| Chips 40 dp | Entscheidungs-Chips mindestens 48 dp hoch; ebenso Kartentitel, Schalterzeilen, Personenart im Personendialog (jetzt eine Radiogruppe) und Farbfelder (44 → 48 dp) |
| Feste Höhe der unteren Leiste | Mindesthöhe 80 dp statt fester Höhe, ebenso die untere Zeile im Erfassen-Blatt |
| Rückmeldeleiste ohne Ansage | Höfliche Live-Region; TalkBack sagt „Gespeichert.“ an (WCAG 4.1.3) |
| Feste 6 s für „Rückgängig“ | Anzeigedauer über die empfohlene Zeit des Systems (`calculateRecommendedTimeoutMillis`, Android-Einstellung „Zeit zum Reagieren“), WCAG 2.2.1 |
| Tabs ohne Auswahl | Tabs sind `selectable` mit `Role.Tab`; TalkBack sagt „ausgewählt“ |
| Keine Überschriften | Abschnitts- und Einstellungstitel sind Überschriften; mit TalkBack springt man von Abschnitt zu Abschnitt |
| Radio-Zeilen | „Erster Tap auf Erfassen“ ist eine Radiogruppe, die ganze Zeile ist das Ziel |

Prüfung (Anfang von P1-4): `AccessibilityTest` in `app/shared/src/jvmTest` stellt die echten Bildschirme „Heute“ und „Einstellungen“ mit Beispieldaten in einem 411 dp breiten Fenster dar. Er prüft, dass jedes antippbare Element mindestens 48 × 48 dp Tippfläche hat, dass genau der aktive Tab als ausgewählt gilt, dass es Überschriften gibt und dass die Rückmeldung in einer Live-Region steht. Läuft im CI-Job `build` (`:shared:jvmTest`). Der erste Lauf fand echte Lücken (Schalterzeile 24 dp, Personenart als kleiner Text), die jetzt behoben sind.

Noch nicht geprüft: Prüfungen des Accessibility Test Framework auf Android (Kontrast, Beschriftung) und Screenshots bei Schriftgröße 2,0; beides braucht den Emulator-Job. Ein TalkBack-Durchgang durch Nils steht aus.

### 26.11.2026: P1-7 Texte in Ressourcen, Schritt 1

Die App nutzt Compose Multiplatform Resources. Alle Texte stehen in `app/shared/src/commonMain/composeResources/values/strings.xml`, Deutsch ist die Grundsprache (`values/`), eine Übersetzung käme nach `values-<sprache>/`. Der Code holt sie mit `stringResource(Res.string.…)`; die Klasse `Res` ist modulintern (`io.github.respawnedguy.familyapp.resources`).

Umgestellt in Schritt 1: der Rahmen in `App.kt` (Willkommen, obere Leiste, Navigation, Speicherproblem, Wiederherstellungscode, Rückgängig, Update-Hinweis) und das Erfassen-Blatt (`ui/CaptureSheet.kt`), zusammen 32 Texte. Der Wortlaut ist unverändert, die Emulator-Tests finden die Knöpfe also weiter über ihren Text.

Messung und Tor: `UiStringsTest` im Qualitätstor zählt die Text-Literale (Zeichenketten mit Buchstaben, ohne Kommentare und `$`-Platzhalter) in `App.kt`, `ui/` und `update/UpdateSection.kt` und vergleicht sie mit `tools/quality-check/config/ui-strings-baseline.txt`. Eine Datei darf nie mehr Texte im Code haben als dort steht, neue UI-Dateien starten bei 0; die Zahlen werden mit jedem Schritt gesenkt (Sperrklinke wie bei der detekt-Baseline). Stand: 214 Texte im Code, `CaptureSheet.kt` bei 0, `App.kt` bei 2 (Versionsanzeige, zieht mit den Einstellungen um).

Nächste Schritte, je ein kleiner PR: (2) `Components.kt` und `Cards.kt` mit Pluralformen für „vor 3 Std.“/„vor 2 Tagen“, (3) `Screens.kt`, (4) `SettingsScreen.kt` mit `AppInfo.versionLabel` und `UpdateSection.kt`, (5) `SyncScreens.kt`, (6) Texte aus `presentation/AppModel.kt` (Rückmeldungen wie „Gespeichert.“) und Android-Benachrichtigungen, die nicht in Composables entstehen (`getString`). Danach ist das Abnahmekriterium „keine deutschen String-Literale mehr in `ui/`“ erfüllt, wenn die Baseline leer ist.

### 26.11.2026: P1-10 Messpunkte für die Referenzmessung

| Teil | Umsetzung | Beleg |
| --- | --- | --- |
| Bis die Aufnahme läuft (D8 a) | Gab es schon; die Kachel misst jetzt ab dem Entsperren statt ab dem Tipp auf das gesperrte Handy, wie D8 es formuliert | `voiceCaptureSavesWithoutTapAndRecordsLatency` |
| Speichern nach Sprechende (D8 b) | Neu: von `onEndOfSpeech` des Erkenners bis gespeichert; ein Tipp auf „Fertig“ zählt als Sprechende und wird getrennt ausgewiesen („Fertig getippt“), weil D8 „ohne Tipp“ verlangt | `savingAfterTheSpeakerStopsIsMeasuredFromTheEndOfSpeech`, `tappingFertigCountsAsTheEndOfSpeechAndIsLabelledSo` |
| Kaltstart | Neu: Prozessstart bis erstes Bild (`ColdStartProbe`), nur wenn der Prozess für diesen Start entstand; dazu `reportFullyDrawn` | `coldStartsAreKeptOnlyWhenPlausible` |
| Auswertung | Median, 90-%-Wert (nächster Rang, ohne Interpolation) und Ziel je Messwert; je Messwert die letzten 50 Werte, damit häufige Kaltstarts die selteneren Aufnahme-Messungen nicht verdrängen | `MeasurementsTest` |
| Export | „Messwerte teilen“ schickt eine CSV (RFC 4180) über das Android-Teilen-Menü, nur Zeiten, Gerät und Versionen. Abweichung vom Plan: Teilen statt Datei im Sicherungsordner, weil das ohne PC direkt in die Claude-App oder Mail geht | `csvHoldsTimesAndDeviceButNoFamilyData`, `measurementsAreSharedAsCsvAndCanStartOver` |
| Speicher | Arbeitsspeicher (PSS) und Größe der App-Dateien in den Einstellungen und in der CSV | – |
| Gegenprobe im CI | `emulator-perf.sh` startet die App je 10-mal kalt über Symbol und Erfassen (Android 8 und 16) und vergleicht die Messung der App mit Androids `am start -W` (TotalTime). Schlägt fehl, wenn die App kaum misst oder grob abweicht; Emulatorzeiten werden nur berichtet | `pruefbericht-leistung-api*.txt`, `leistung-api*.csv` am Release |
| Anleitung | [leistung-messen.md](leistung-messen.md): Messreihe auf dem Handy ohne PC, Akku über die Android-Einstellungen | – |

Bewusst nicht gemacht: Macrobenchmark. Er misst im CI-Emulator dieselbe Spanne wie `am start -W`, braucht aber ein eigenes Modul aus Google Maven. Sinnvoll erst mit Baseline Profile (P2-5) auf echtem Gerät.

**Referenzwerte (offen, trägt Claude nach Nils' Messung ein):**

| Gerät | Bis Aufnahme läuft, Kachel (Median / 90 %) | Speichern nach Sprechende | Kaltstart | Arbeitsspeicher | Akku/Tag |
| --- | --- | --- | --- | --- | --- |
| Pixel (Nils) | – | – | – | – | – |
| Android 8 (falls vorhanden) | – | – | – | – | – |

Abnahme P1-10 ist erst erfüllt, wenn die Pixel-Zeile ausgefüllt ist und der Median „bis Aufnahme läuft“ ≤ 2 s liegt.

### 26.11.2026: P1-8 Bedrohungsmodell und Härtung der Eingänge

[Bedrohungsmodell](bedrohungsmodell.md) nach STRIDE für Teilen, Kopplung, Direktabgleich, geteilten Ordner, Hausgerät und Gerät: 28 Bedrohungen, jede mit Maßnahme und Test oder als offene Maßnahme M1 bis M4. Beim Durchgehen des Codes gefunden und gleich behoben:

| Befund | Umsetzung | Beleg |
| --- | --- | --- |
| Teilen prüfte weder Typ noch Inhalt; nur der FileProvider `.files` galt als eigener Anbieter; abgelehnte Bilder verschwanden still | Typ vom Anbieter muss `image/*` sein, der Bilddecoder muss eine Größe lesen (≤ 100 MP), alle eigenen Anbieter abgelehnt, Text ≤ 20 000 Zeichen, Meldung mit Grund | `SharedInputTest`, `AppModelTest.refusedSharedImage…`, Emulator `emulator-share-e2e.sh` |
| Ein Fremder im WLAN konnte mit 4 Bytes 32 MB Speicher pro Verbindung belegen, unbegrenzt viele Verbindungen je 200 s offen halten | Vor dem Handshake höchstens 64 KB pro Rahmen, 10 s Wartezeit, 8 Verbindungen | `TcpTransportTest` |
| Wiederherstellen und Ordnerabgleich lasen jede Datei ganz in den Speicher; eine kaputte Riesendatei hätte jeden Abgleich scheitern lassen | Höchstens 32 MB pro Datei, zu große Abgleichdatei wird übersprungen | `BoundedReadTest`, `SyncControllerTest` |
| Ab Android 12 hätte der Umzug auf ein neues Handy Familienschlüssel und Geräte-ID mitkopiert | `dataExtractionRules` | `verify-apk.sh` |
| Hausgerät: `sync.json` mit Standardrechten lesbar für andere Konten | `rw-------`, Verzeichnis `rwx------` | `SyncFilesTest` |

Abnahmekriterium „`file://`-URI und Übergröße werden abgelehnt“ prüft jetzt der Emulator-Test auf Android 8 und 16 (dazu eigener Anbieter, HTML, HTML mit Bildtyp und ein echtes Bild). Höchstes Restrisiko: Ein verlorenes Handy oder ein ehemaliges Familienmitglied behält den Familienschlüssel (M1, Gerät entfernen mit Schlüsselwechsel).

Messwerte danach (`gradle check`, zusammen mit P1-7 Schritt 1): 178 App-Tests grün, Abdeckung Zeilen 91 %, Zweige 62 %.

Noch offen aus P1: P1-4 Rest (Android-Prüfungen, Screenshots bei 200 % Schrift), P1-5 Datumsparser-Korpus, P1-7 Texte in Ressourcen (begonnen), P1-9 FMEA, P1-10 Referenzwerte vom Gerät.
