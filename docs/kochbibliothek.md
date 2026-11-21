# Familien-Kochbibliothek – Konzept und Brainstorming

Stand: 21.11.2026 · Status: **Entwurf zur Abstimmung** (noch kein Bau) · Reifestufe danach: Spike

## Kurzfassung

Rezepte kommen auf drei Wegen in eine gemeinsame Kochbibliothek in der App: Teilen aus Instagram, Foto oder Screenshot, und von Hand. Alles läuft auf dem Handy, ohne Server und ohne Mealie. Eine Warteschlange auf dem Gerät holt Text, Bilder und Ton, erkennt Text und Sprache lokal und baut daraus einen Rezeptvorschlag, den ein Mensch bestätigt. Einmal pro Woche holt ein Handy der Familie die Angebote der Märkte in der Nähe und schlägt Rezepte vor, deren Hauptzutaten gerade günstig sind oder Saison haben. Das Ergebnis geht per P2P-Sync an alle Geräte.

Was dieses Konzept anders macht als ein Feature-Brainstorm:

- Es baut auf vorhandenem, laufendem Code auf statt neu zu erfinden: Nils' Repo `preisvergleich` (Angebote von Aldi Nord, Lidl, Kaufland, REWE, Normalpreis-Gedächtnis mit 1.385 Produkten, Zutat-zu-Angebot-Abgleich) und den Instagram-Import im SurfaceLab (Fundstelle noch offen, siehe [Offene Punkte](#offene-punkte)).
- Jede Technikaussage ist gegen die Primärquelle geprüft (ML-Kit-Doku, BGH-Rechtsprechung) und mit Datum belegt; was nicht geprüft werden konnte, ist markiert.
- Es endet mit einer Messmethode: ein Spike mit 30 echten Instagram-Rezepten und einer Erwartungstabelle, wie beim Regel-Spike für Schulmails.

## Bezug zum Mental Load

Essensplanung ist ein Lehrbuchfall für die unsichtbare kognitive Arbeit aus [KONZEPT.md](KONZEPT.md): Jemand muss **antizipieren** (was essen wir diese Woche, was ist noch da), **Optionen finden** (Rezepte, Angebote), **entscheiden** (was gibt es) und **überwachen** (ist alles eingekauft). Die Kochbibliothek nimmt vor allem Phase 2 und 3 ab: Die App denkt die Woche vor und legt 3 bis 5 passende Rezepte hin. Die Entscheidung bleibt bei der Familie, als leichte Abstimmung (Phase 4), und die Einkaufsliste geht als normale Aufgabe in die Zuständigkeit einer Person (Phase 5).

Messgröße: Wer schlägt in der Familie die Gerichte der Woche vor, vorher und nachher (Anteil der Vorschläge je Person, Anteil aus der App).

## Rohmaterial: was schon existiert

### Repo `preisvergleich` (Python, Stand 19.11.2026)

| Teil | Was er tut | Übertragbar aufs Handy? |
| --- | --- | --- |
| `aldi.py` | liest `__NEXT_DATA__`-JSON von aldi-nord.de/angebote.html, einfacher HTTP-Abruf | ja, reines HTTP + JSON |
| `lidl.py` | findet Prospekt-IDs auf lidl.de, holt Produkte über `endpoints.leaflets.schwarz/v4/flyer` | ja, reines HTTP + JSON |
| `kaufland.py` | liest die Wochenangebotsseite von filiale.kaufland.de | ja, reines HTTP |
| `rewe.py` | Playwright-Browser, Marktsuche per PLZ, Cookie-Banner, Markt-Cache | **nein**: braucht einen echten Browser, außerdem rechtlich heikel (siehe unten) |
| `rezept_scraper.py` | Rezepte von Webseiten über `recipe-scrapers`, JSON-LD, Microdata, Chefkoch-`__NEXT_DATA__` | Teilweise: JSON-LD/Microdata lassen sich in Kotlin nachbauen |
| `gui.py` | Normalpreis-Gedächtnis, Zutat-Zerlegung (Menge, Einheit, Name), Suchwörter mit Synonymen und Plural, Verarbeitungs-Malus („Tee statt Minze“), Packungsrechnung | ja, reine Logik, gut portierbar nach Kotlin in `shared/` |

Auffälligkeiten beim Lesen, die das Konzept berücksichtigt:

1. Die PLZ wird nur bei REWE benutzt. Aldi, Lidl (`region_id=0`) und Kaufland liefern bundesweite oder Standard-Angebote. Regional heißt also bisher nur REWE. Für echte Regionalität braucht es pro Markt die Filiale (Kaufland hat Filialseiten, Lidl Regionen, Aldi Nord/Süd ist schon eine Region).
2. Die Lebensmittel-Erkennung ist eine Heuristik und irrt: „ALL SEASONS Junge große Bohnen, 720-ml-Glas“ steht im Normalpreis-Stand als `lebensmittel: false`. Für Rezeptvorschläge muss das per Klick korrigierbar sein und die Korrektur als Regel gemerkt werden (HITL wie im Regel-Spike).
3. Der Abgleich Zutat → Angebot ist textbasiert und läuft bei jeder Abfrage neu. Für die App ist es robuster, Zutaten **beim Import einmal** auf einen Zutat-Schlüssel abzubilden (bestätigt durch den Menschen) und Angebote auf denselben Schlüssel. Dann ist der Abgleich ein Nachschlagen statt einer Textsuche.

### Instagram-Import im SurfaceLab

Funktioniert laut Nils: Teilen-Button → App öffnet sich → Warteschlange → Bilder und Videos werden transkribiert → Mealie. Den Code habe ich noch nicht gesehen (nicht auf dem PC „Brainbug“, kein passendes GitHub-Repo). Annahme bis dahin: Er läuft auf dem Surface mit Server-Werkzeugen (z. B. Download per yt-dlp oder Instaloader, Whisper, Sprachmodell). Genau diese Schritte müssen aufs Handy, deshalb behandelt das Konzept jeden einzeln.

## Die Kochbibliothek in der App (ohne Mealie)

Mealie fällt weg. Die Bibliothek ist ein weiterer Teil des Familiengraphen, synchronisiert wie alles andere über ADR 0003.

### Datenmodell

Ein Rezept hält sich eng an [schema.org/Recipe](https://schema.org/Recipe). Das ist das Format, das Rezeptseiten als JSON-LD einbetten und das Mealie, Tandoor, Nextcloud Cookbook und `recipe-scrapers` lesen. So bleibt der Weg rein und raus offen, ohne eigenes Austauschformat.

| Feld | schema.org | Anmerkung |
| --- | --- | --- |
| Titel, Beschreibung | `name`, `description` | |
| Portionen | `recipeYield` | als Zahl gespeichert, für Umrechnen |
| Zeiten | `prepTime`, `cookTime`, `totalTime` | ISO 8601 |
| Zutaten | `recipeIngredient` (Text) | **zusätzlich strukturiert**: Menge, Einheit, Zutat-Schlüssel, Notiz („fein gehackt“) |
| Schritte | `recipeInstructions` | Liste von Schritten |
| Bild | `image` | ein Titelbild, klein (WebP, ca. 100 KB) für den Sync |
| Quelle | `isBasedOn` / `url`, `author` | Instagram-Link und Account bleiben immer sichtbar |
| Schlagwörter | `keywords`, `recipeCategory`, `recipeCuisine` | |
| Familie | – | wer mag es (je Person), „Kinder essen es“, zuletzt gekocht, Notizen, Status (Vorschlag / bestätigt) |

Zutat-Schlüssel sind ein eigener kleiner Katalog („zwiebel“, „hackfleisch-rind“), mit Synonymen und Plural aus `gui.py` als Startbestand. Angebote und Saisonkalender zeigen auf dieselben Schlüssel.

### Import und Export

- **Export:** pro Rezept JSON-LD (schema.org), gesammelt als ZIP mit Bildern. Das ist auch die Sicherung („Sicherung in Ordner“ aus dem Prototyp).
- **Einmalige Übernahme aus Mealie:** Nils' vorhandene Rezepte über die Mealie-API (`/api/recipes`, Token) oder Mealies Export-ZIP einlesen. Danach wird Mealie nicht mehr gebraucht.
- **Weg zurück zu Mealie** bleibt offen, weil Mealie schema.org-JSON importieren kann (vor Zusage mit einem Rezept testen, nicht geprüft).

### Sync

Rezepte sind kleine Datensätze und laufen über den normalen Sync. Das Titelbild braucht Anhänge im Sync, die der P2P-Spike noch nicht hat (ADR 0002 sieht „Anhänge als verschlüsselte Blobs, adressiert über Hash“ vor). Originalvideos werden nicht gespeichert und nicht synchronisiert: zu groß und urheberrechtlich nicht nötig, weil der Link zur Quelle bleibt.

### Wo es in der Navigation sitzt

Die untere Leiste ist entschieden (Heute · Familie · Erfassen · Demnächst · Suchen). Die Kochbibliothek bekommt keinen eigenen Tab, sondern: Rezeptvorschlag der Woche als Karte unter **Heute**, die Bibliothek als Bereich unter **Familie**, Rezepte in **Suchen**. Neue Rezepte aus dem Import landen wie alles andere zuerst im **Eingang** als Vorschlagskarte.

## Erfassen: drei Wege, eine Warteschlange

```
Teilen / Foto / Hand ──► Warteschlange (Gerät) ──► Text gewinnen ──► Rezept bauen ──► Vorschlagskarte ──► Bibliothek
                          sofort gespeichert        OCR, Sprache     Regeln zuerst,     Mensch prüft,
                          läuft im Hintergrund      lokal            lokale KI opt.     „Passt“
```

Prinzip „schnell rein, komplett später“: Teilen speichert sofort den Link als Eintrag (≤ 1 s, kein Dialog), die Verarbeitung läuft danach im Hintergrund (Android: WorkManager, eindeutiger Auftrag je Link, mit Wiederholung). Der Eintrag zeigt seinen Stand (wartet, holt, erkennt, fertig, braucht Hilfe). Geht ein Schritt nicht, bleibt der Eintrag offen und sagt, was fehlt, statt still zu verschwinden.

### Weg 1: Instagram teilen

Das Teilen-Menü von Instagram übergibt der App **nur einen Link** (`instagram.com/reel/…` oder `/p/…`), keine Datei. Der Knackpunkt ohne Server ist deshalb nicht die Erkennung, sondern **an Text und Ton zu kommen**.

| Quelle im Post | Wie ohne Server | Bewertung |
| --- | --- | --- |
| **Bildunterschrift** (bei Rezept-Posts oft das ganze Rezept) | Die App öffnet den Link in einer eingebetteten WebView, in der der Nutzer selbst bei Instagram angemeldet ist, und liest Bildunterschrift, Bild-URLs und Video-URL aus der Seite. Ein Abruf pro Teilen, vom Nutzer ausgelöst. | Liefert in den meisten Fällen genug. Fragil bei Seitenumbauten; Instagram verbietet automatisiertes Sammeln in den Nutzungsbedingungen (siehe Recht). |
| **Text im Video** (eingeblendete Zutaten) | Video-Datei aus der WebView laden, alle 1–2 s ein Bild entnehmen (`MediaMetadataRetriever`), ML Kit Text Recognition (lateinische Schrift, offline), doppelte Zeilen zusammenfassen | gut, rein lokal |
| **Gesprochenes** | Tonspur ohne Neukodierung aus dem MP4 lösen (`MediaExtractor`/`MediaMuxer` → M4A), dann ML Kit GenAI Speech Recognition mit `AudioSource.fromFile()` | geht lokal, siehe unten |
| **Karussell-Bilder** | Bilder laden, Texterkennung wie beim Foto | gut |
| **Rückfallweg ohne WebView** | Nutzer speichert das Reel (Instagram bietet bei vielen öffentlichen Reels „Herunterladen“) oder macht Screenshots und teilt die **Datei** an die App | immer möglich, ein Schritt mehr |

**Spracherkennung auf dem Gerät** (Quelle: [ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android), Seite vom 14.11.2026, abgerufen 21.11.2026):

- Nimmt fertige Dateien an (`AudioSource.fromFile()` / `fromUri()`), dekodiert MP3, WAV, FLAC, M4A, OGG selbst.
- Modus „Advanced“ (Gemini Nano): Deutsch (de-DE) ohne Beta-Kennzeichen, auf Pixel 10/11 und Geräten mit bestimmten Snapdragon-, MediaTek- und Exynos-Chips. Modus „Basic“: ab Android 12 (API 31), Deutsch als Beta.
- Status: **Alpha**, ohne SLA. Deshalb hinter einer Schnittstelle kapseln.
- Ausweichmodell für Geräte ohne Gemini Nano: MediaPipe Audio Transcriber mit Parakeet TDT 0.6B, rein lokal, aber ca. 600 MB Modell und eigene Dekodierung ([Doku](https://developers.google.com/edge/mediapipe/solutions/audio/audio_transcriber/android)). Erst nehmen, wenn ein Familiengerät es braucht.
- iPhone (später): Share-Extension, Apple Vision für Text, `SFSpeechRecognizer` mit Erkennung auf dem Gerät. Nicht geprüft, Spike nötig.

### Weg 2: Foto oder Screenshot

Kochbuchseite, Zettel von Oma, Screenshot eines Rezepts: ML Kit Text Recognition auf dem Gerät, dann derselbe Rezept-Baustein wie bei Instagram. Erfassen-Taste wie im Prototyp (Foto an gleicher Stelle). Mehrere Fotos zu einem Rezept zusammenfassen („Seite 2 hinzufügen“).

### Weg 3: von Hand

Kein Formular mit 12 Feldern. Ein Textfeld, in das man das Rezept tippt, diktiert oder einfügt (etwa von einer Webseite oder aus WhatsApp). Der Rezept-Baustein teilt es auf. Webseiten-Links laufen über JSON-LD wie in `rezept_scraper.py`, das liefert bei den meisten Rezeptseiten sofort ein vollständiges Rezept.

### Rezept bauen: Regeln zuerst, lokale KI optional

1. **Regeln (immer):** Abschnitte erkennen („Zutaten“, „Zubereitung“, „Für 4 Personen“), Zutatenzeilen per Muster Menge + Einheit + Name (Einheitenliste und `zerlege_zutat_und_menge` aus `gui.py`), Zeitangaben per Datums-/Dauer-Parser aus dem Regel-Spike, Emojis und Hashtags entfernen. Zutat-Namen auf Zutat-Schlüssel abbilden.
2. **Lokale KI (wenn auf dem Gerät vorhanden und eingeschaltet):** Wenn Regeln keine saubere Struktur finden (gesprochenes Rezept, Fließtext), formt Gemini Nano über die ML Kit Prompt API den Text in die Felder um. Die Prompt API nimmt auch Bilder an ([Android Developers Blog, 30.10.2025](https://android-developers.googleblog.com/2025/10/ml-kit-genai-prompt-api-alpha-release.html)); sie war bei Veröffentlichung Alpha, die Doku nennt sie inzwischen Beta.
3. **Cloud-KI:** aus, nur optional pro Funktion, wie im Gesamtkonzept.

Jedes Feld zeigt, woher es kommt (Regel, Texterkennung, Sprache, lokale KI), und der Mensch bestätigt die Karte. Das Original (Link, Bildunterschrift, Transkript) bleibt am Rezept, damit man nachsehen kann.

## Angebote und Vorschläge

### Wer holt die Angebote, ohne Server?

| Variante | Wie | Bewertung |
| --- | --- | --- |
| **A. Ein Android-Handy der Familie** (Empfehlung) | WorkManager, wöchentlich, nur mit WLAN und Ladegerät. Aldi, Lidl, Kaufland per HTTP + JSON. Ergebnis als normale Datensätze in den Sync, die anderen Geräte holen nichts selbst. | einfach, kein Zusatzgerät. Läuft nicht minutengenau, das ist für Wochenangebote egal. |
| B. Dauer-Peer im Tailnet (Surface, Synology) | Dieselbe Logik auf der JVM, optional zusätzlich REWE mit Browser | nur, wenn es den Dauer-Peer aus ADR 0003 ohnehin gibt |
| C. Beim Öffnen der App | Wenn die Angebote älter als 7 Tage sind | Rückfall, falls A nicht lief |
| iPhone | Hintergrundaufgaben unter iOS laufen nicht verlässlich | holt keine Angebote, bekommt sie per Sync |

Pro Woche und Markt holt nur ein Gerät (Kennung „Markt + Woche“, wer zuerst kommt). Prospekte wechseln bei den Discountern meist zum Wochenstart, also Sonntagabend oder Montagfrüh abrufen.

### Rechtliche Einordnung (keine Rechtsberatung)

- **Öffentliche Angebotsseiten automatisiert lesen ist nach BGH grundsätzlich zulässig**, solange keine technische Schutzmaßnahme überwunden wird und die Seite nicht spürbar belastet wird (BGH, 30.04.2014, I ZR 224/12, „Ryanair“, laut [LTO](https://www.lto.de/recht/hintergruende/h/bgh-urteil-izr22412-screen-scraping-flugdaten-automatisiert-auslesen-ryanair-reiseportal) und [Kanzlei Plutte](https://www.ra-plutte.de/bgh-zum-automatisierten-auslesen-fremder-websites-via-screen-scraping/)). Ein Verbot in den AGB allein reicht nach dem BGH nicht. Der Fall betraf Wettbewerbsrecht zwischen Unternehmen; eine private Familien-App ist kein Mitbewerber, das spricht eher für uns.
- **Datenbankherstellerrecht (§ 87b UrhG):** Einzelne, gezielte Abfragen sind keine „wesentlichen Teile“ (BGH I ZR 159/10, laut [WBS](https://www.wbs.legal/urheberrecht/ist-screen-scraping-legal-39848/)). Heikel ist das wiederholte, systematische Abziehen ganzer Bestände und vor allem das **Weitergeben**. Architekturfolge: Jede Familie holt nur für sich, es gibt keine zentrale Angebotsdatenbank, die an andere Familien verteilt wird. Das ist auch beim Abo-Modell so zu halten.
- **REWE nicht in die App:** `rewe.py` umgeht mit Browser-Tricks (`AutomationControlled` aus, `curl_cffi` für Akamai) einen Bot-Schutz. Das ist genau die „technische Schutzmaßnahme“, die der BGH als Grenze nennt. Für Nils' eigenen Desktop-Gebrauch ist das seine Abwägung, in eine verteilte App gehört es nicht.
- **Text- und Data-Mining (§ 44b UrhG)** erlaubt Vervielfältigung zum Auswerten, außer der Anbieter hat einen maschinenlesbaren Vorbehalt erklärt. Die `robots.txt` und Nutzungsbedingungen von aldi-nord.de, lidl.de und kaufland.de konnte ich aus der Cloud nicht abrufen, das ist vor dem Bau zu prüfen.
- **Instagram:** Die Nutzungsbedingungen verbieten das Sammeln von Daten mit automatisierten Mitteln ohne Erlaubnis (aus dem Gedächtnis, vor Zitat prüfen). Die WebView-Lösung ist ein vom Nutzer ausgelöster Einzelabruf wie im Browser, kein Massenabruf. Restrisiko: Instagram erkennt das Muster und sperrt die Anmeldung in der WebView, im schlimmsten Fall das Konto. Deshalb gibt es immer den Rückfallweg über geteilte Dateien.
- **Urheberrecht am Rezept:** Zutatenliste und Ablauf als solche sind in der Regel nicht geschützt, Fotos, Videos und ausformulierte Texte schon. Für die private Familienbibliothek deckt das die Privatkopie (§ 53 UrhG). Nicht öffentlich teilen, Quelle immer verlinken.

### Wie Vorschläge entstehen (deterministisch)

Pro Rezept und Woche zwei Werte, beide nachvollziehbar:

- **Angebot:** Anteil der Hauptzutaten (ohne Grundzutaten wie Salz, Öl, Mehl aus `GRUNDZUTATEN`), die diese Woche im Angebot sind, plus Ersparnis gegen das Normalpreis-Gedächtnis.
- **Saison:** Anteil der Gemüse- und Obstzutaten, die laut Saisonkalender gerade Saison haben. Saisonkalender als feste Tabelle in der App (Quelle z. B. Bundeszentrum für Ernährung, vor Übernahme Lizenz prüfen).

Dazu einfache Regeln: nicht vorschlagen, was in den letzten 3 Wochen gekocht wurde; Rezepte, die jemand nicht mag, nach hinten; „Kinder essen es“ nach vorne. Die Karte sagt immer warum: „Hackfleisch bei Lidl −30 %, Kürbis hat Saison“.

Ablauf in der Woche: Montag liegt unter **Heute** eine Karte „Diese Woche günstig: 5 Rezepte“. Jedes Familienmitglied tippt an, was es mag (leichte Abstimmung aus Phase 4). Daraus entsteht eine Einkaufsliste nach Markt sortiert, als Aufgabe mit Zuständigkeit.

## Empfehlungen (bitte kommentieren, Unkommentiertes gilt als angenommen)

1. **Mealie wird ersetzt.** Die Kochbibliothek lebt komplett in der App, im Familiengraphen, mit Sync über ADR 0003.
2. **schema.org/Recipe als Datenmodell und Austauschformat**, ergänzt um strukturierte Zutaten und Familienfelder. Export als JSON-LD-ZIP, keine eigene Formaterfindung.
3. **Einmalige Übernahme der vorhandenen Mealie-Rezepte** über Mealies API oder Export, danach kann Mealie aus.
4. **Eine Warteschlange auf dem Gerät für alle drei Wege**: Teilen speichert sofort, Verarbeitung im Hintergrund, Stand sichtbar, Ergebnis als Vorschlagskarte im Eingang (HITL).
5. **Instagram über eine angemeldete WebView in der App** (Bildunterschrift, Bilder, Video), mit dem Rückfallweg „Datei teilen“ (gespeichertes Reel, Screenshots).
6. **Erkennung nur lokal:** ML Kit Text Recognition für Bilder und Videobilder, ML Kit GenAI Speech Recognition für Ton (Deutsch, Pixel 10). Beide Alpha-/Beta-APIs hinter eigenen Schnittstellen.
7. **Regeln zuerst, Gemini Nano nur als Verstärker** für Fließtext und Gesprochenes; Cloud-KI bleibt aus.
8. **Zutat-Schlüssel beim Import festlegen und bestätigen**, damit Angebots- und Saisonabgleich später ein Nachschlagen ist. Startkatalog aus Synonymen und Plural-Regeln von `preisvergleich`.
9. **Angebote holt ein Android-Handy per WorkManager**, wöchentlich mit WLAN und Ladegerät; das Ergebnis geht per Sync an alle. Dauer-Peer nur, wenn es ihn ohnehin gibt.
10. **Aldi Nord, Lidl, Kaufland zuerst, REWE nicht in die App**, weil es einen Bot-Schutz umgehen müsste. Jede Familie holt nur für sich, keine zentrale Angebotsdatenbank.
11. **Abgleichslogik aus `preisvergleich` nach Kotlin portieren** (`shared/`, reine Logik mit Tests), statt neu zu schreiben.
12. **Vorschläge deterministisch mit Begründung**, Montags als Karte unter Heute, Auswahl per Antippen, daraus Einkaufsliste mit Zuständigkeit.
13. **Videos und Originalmedien nicht speichern**, nur Titelbild (klein) und Quelllink.
14. **Erst ein Spike, dann Bau** (nächster Abschnitt). Der Prototyp-Thread v0.2 bleibt davon unberührt.

## Nächster Schritt: Spike „Rezept-Import“

Ziel in einem Satz: Zeigen, dass aus 30 echten Instagram-Rezepten ohne Server und ohne Cloud-KI brauchbare Rezeptvorschläge entstehen.

Vorgehen:

1. Nils teilt 30 echte Rezept-Posts (gemischt: Rezept in Bildunterschrift, eingeblendeter Text, nur gesprochen, Karussell). Ablage nur in `samples-private/` mit `expected-private.csv` (Titel, Portionen, Zutaten mit Menge und Einheit), wie beim Regel-Spike.
2. **Teil A (JVM, in der Cloud testbar):** Regel-Baustein Text → Rezept, gemessen an der Erwartungstabelle.
3. **Teil B (Pixel 10, kleine Test-App):** WebView-Abruf, Texterkennung aus Videobildern, Spracherkennung, Gemini Nano als Verstärker. Gemessen auf dem echten Gerät.

Abnahmekriterien:

- ≥ 90 % der Zutatenzeilen erkannt, ≥ 80 % mit richtiger Menge und Einheit, gemessen gegen `expected-private.csv`
- Kein erfundenes Rezept: Ist keine Zutatenliste zu finden, bleibt die Karte offen mit Hinweis (0 Fälle mit erfundenen Zutaten)
- Teilen bis fertiger Vorschlag im Median ≤ 60 s auf dem Pixel 10, ohne dass die App offen bleiben muss

Danach separat: Angebots-Abruf auf dem Handy (Kotlin-Port von `aldi.py`, `lidl.py`, `kaufland.py`) mit Vergleich gegen die Python-Ausgabe derselben Woche als Prüfung.

## Offene Punkte

- [ ] **Wo liegt der Instagram-Import im SurfaceLab?** Pfad auf dem Surface oder Repo. Dann übernehme ich Prompt, Abläufe und Erfahrungen (welche Posts scheitern) in dieses Konzept.
- [ ] Welche Märkte und welche Filialen/PLZ genau? Aldi Nord oder Süd?
- [ ] Welche Handys hat die Familie? Für Spracherkennung „Advanced“ braucht es Pixel 10 oder ein Gerät mit Gemini Nano; iPhones bekommen später einen eigenen Spike.
- [ ] `robots.txt` und Nutzungsbedingungen der Märkte auf Vorbehalt nach § 44b UrhG prüfen.
- [ ] Mealie-Rückweg (schema.org-JSON importieren) mit einem Rezept testen.
- [ ] Anhänge im P2P-Sync (Titelbilder) als eigener Baustein, hängt am Sync-Thread.

## Quellen

- ML Kit GenAI Speech Recognition, Android: https://developers.google.com/ml-kit/genai/speech-recognition/android (Stand der Seite 14.11.2026, abgerufen 21.11.2026)
- ML Kit GenAI Prompt API, Alpha-Ankündigung: https://android-developers.googleblog.com/2025/10/ml-kit-genai-prompt-api-alpha-release.html (abgerufen 21.11.2026)
- MediaPipe Audio Transcriber, Android: https://developers.google.com/edge/mediapipe/solutions/audio/audio_transcriber/android (abgerufen 21.11.2026)
- BGH I ZR 224/12 (Ryanair), Besprechung LTO: https://www.lto.de/recht/hintergruende/h/bgh-urteil-izr22412-screen-scraping-flugdaten-automatisiert-auslesen-ryanair-reiseportal
- Kanzlei Plutte zum Screen Scraping: https://www.ra-plutte.de/bgh-zum-automatisierten-auslesen-fremder-websites-via-screen-scraping/
- WBS zu Screen Scraping und § 87b UrhG (Stand 2013): https://www.wbs.legal/urheberrecht/ist-screen-scraping-legal-39848/
- schema.org Recipe: https://schema.org/Recipe
- Repo `RespawnedGuy/preisvergleich`, Stand 19.11.2026
