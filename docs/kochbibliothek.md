# Familien-Kochbibliothek – Konzept und Brainstorming

Stand: 21.11.2026 · Status: **Entwurf zur Abstimmung** (noch kein Bau) · Reifestufe danach: Spike

## Kurzfassung

Rezepte kommen auf drei Wegen in eine gemeinsame Kochbibliothek in der App: Teilen aus Instagram, Foto oder Screenshot, und von Hand. Alles läuft auf dem Handy, ohne Server und ohne Mealie. Eine Warteschlange auf dem Gerät holt Text, Bilder und Ton, erkennt Text und Sprache lokal und baut daraus einen Rezeptvorschlag, den ein Mensch bestätigt. Einmal pro Woche holt ein Handy der Familie die Angebote der Märkte in der Nähe und schlägt Rezepte vor, deren Hauptzutaten gerade günstig sind oder Saison haben. Das Ergebnis geht per P2P-Sync an alle Geräte.

Was dieses Konzept anders macht als ein Feature-Brainstorm:

- Es baut auf vorhandenem, laufendem Code auf statt neu zu erfinden: Nils' Repo `preisvergleich` (Angebote von Aldi Nord, Lidl, Kaufland, REWE, Normalpreis-Gedächtnis mit 1.385 Produkten, Zutat-zu-Angebot-Abgleich) und den Rezept-Importer aus dem SurfaceLab (Stand 21.11.2026, Code gelesen). Jeder Schritt des Importers ist einzeln auf eine Lösung ohne Server abgebildet.
- Jede Technikaussage ist gegen die Primärquelle geprüft (ML-Kit-Doku, BGH-Rechtsprechung) und mit Datum belegt; was nicht geprüft werden konnte, ist markiert.
- Es endet mit einer Messmethode: eine Liste echter Instagram-Links, deren Zutaten ein Mensch geprüft hat. Dieselben Links laufen durch die Handy-Kette, und wir vergleichen Zutat für Zutat.

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

### Rezept-Importer im SurfaceLab (`instagram_to_mealie.py`, ca. 1.000 Zeilen, plus Teilen-PWA)

| Schritt | Heute auf dem Surface | Ohne Server auf dem Handy |
| --- | --- | --- |
| Teilen | PWA mit Web-Share-Target, schickt den Link an `POST /api/queue` | echtes Teilen-Ziel der App (gibt es im Prototyp v0.2 schon) |
| Warteschlange | ein Arbeits-Thread, Aufträge in `jobs.json`, überleben Neustart, gleicher Link wird nicht doppelt angelegt, letzte 50 gemerkt | WorkManager mit eindeutigem Auftrag je Link; gleiche Regeln übernehmen |
| Download | **yt-dlp ohne Anmeldung**, nur Tonspur (`bestaudio` → MP3 per ffmpeg), Bildunterschrift, Account, Vorschaubild. Karussell ohne Video wird erkannt, dann nur Bildunterschrift | yt-dlp läuft auch auf Android (Bibliothek `youtubedl-android`, siehe Weg 1) |
| Spracherkennung | lokaler Whisper-Server, Sprache fest `de` | ML Kit GenAI Speech Recognition auf dem Gerät |
| Rezept bauen | **Claude zuerst** (strukturierte Ausgabe nach festem JSON-Schema), Rückfall Ollama `qwen2.5:3b`; Bildunterschrift und Transkript je auf 3.000 Zeichen gekürzt | Regeln zuerst, Gemini Nano als Verstärker, Claude optional (siehe Empfehlung 7) |
| Fotos | bis 3 Fotos je Rezept, Bildmodell liest sie (Claude oder lokal `qwen3.5:9b` auf dem Prozessor, Zeitlimit 30 min) | ML Kit Texterkennung, dann Regeln oder Gemini Nano |
| Ziel | Mealie-API: Rezept, Tags, Kategorien, Bild, Fotos als Anhang, Hinweis „Mengen mit Originalfotos vergleichen“ | Vorschlagskarte im Eingang, dann Bibliothek |

Was wir daraus lernen:

1. **yt-dlp ohne Anmeldung reicht heute für Instagram.** Das ist die wichtigste Erkenntnis, denn damit ist der schwierigste Schritt gelöst, ohne dass die App ein Instagram-Konto braucht. Von einer Heim-IP aus; ob es vom Handy im Mobilfunknetz genauso geht, muss der Spike zeigen.
2. **Eingeblendeter Text im Video wird heute nicht gelesen**, nur Ton und Bildunterschrift. Texterkennung auf Videobildern ist eine echte Verbesserung gegenüber dem Stand.
3. **Die Qualität hängt heute am großen Cloud-Modell.** Nils hat Claude am 01.11.2026 ausdrücklich für diesen Importer freigegeben. Das lokale 3B-Modell ist nur Rückfall, das Bildmodell auf dem Prozessor ist sehr langsam. Gemini Nano auf dem Pixel 10 ist ebenfalls ein kleines Modell. Ob Regeln plus Nano an Claude heranreichen, ist die zentrale offene Frage, und sie lässt sich messen (siehe Spike).
4. Bewährte Prompt-Regeln übernehmen: „nichts erfinden, nichts weglassen“, „fehlt etwas, Feld leer lassen“, „mehrere Fotos sind ein Rezept“.
5. Bewährte Kleinigkeiten übernehmen: Tracking-Parameter aus dem Link entfernen, Doppelaufträge abfangen, Karussell ohne Video nicht als Fehler behandeln, Status mit Warteplatz anzeigen, „Du kannst die App schließen, das Rezept kommt trotzdem“.

## Die Kochbibliothek in der App (ohne Mealie)

Mealie fällt ganz weg, auch als Gegenstelle für Import oder Export. Die Bibliothek ist ein weiterer Teil des Familiengraphen, synchronisiert wie alles andere über ADR 0003.

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
- **Vorhandene Rezepte (optional):** Wer alte Rezepte behalten will, liest sie einmal als schema.org-JSON ein; Mealie und die meisten Rezept-Apps können so exportieren. Die App braucht dafür keine Mealie-Anbindung.

### Sync

Rezepte sind kleine Datensätze und laufen über den normalen Sync. Das Titelbild braucht Anhänge im Sync, die der P2P-Spike noch nicht hat (ADR 0002 sieht „Anhänge als verschlüsselte Blobs, adressiert über Hash“ vor). Originalvideos werden nicht gespeichert und nicht synchronisiert: zu groß und urheberrechtlich nicht nötig, weil der Link zur Quelle bleibt.

### Wo es in der Navigation sitzt

Die untere Leiste ist entschieden (Heute · Familie · Erfassen · Demnächst · Suchen). Die Kochbibliothek bekommt keinen eigenen Tab, sondern: Rezeptvorschlag der Woche als Karte unter **Heute**, die Bibliothek als Bereich unter **Familie**, Rezepte in **Suchen**. Neue Rezepte aus dem Import landen wie alles andere zuerst im **Eingang** als Vorschlagskarte.

## Grundfunktionen, die Mealie bisher übernimmt

Nils, 21.11.2026: Die App baut die Grundfunktionen von Mealie nach, damit Mealie ganz wegfallen kann: **Rezepte, Übersicht, Kochmodus und Einkaufsliste**. Alles läuft offline auf dem Handy und synchronisiert über ADR 0003.

### Rezept-Ansicht

- Titelbild, Titel, Quelle (Instagram-Account mit Link), Zeiten, Portionen.
- **Portionen umrechnen** mit Plus und Minus: Mengen werden aus den strukturierten Zutaten neu berechnet und sinnvoll gerundet (0,33 TL → „⅓ TL“, 1.050 g → „1,05 kg“). Zutaten ohne Menge („Salz“) bleiben, wie sie sind.
- Zutaten, Schritte, Notizen der Familie („doppelt Knoblauch“), wer es mag, zuletzt gekocht.
- Aktionen: **Kochen** (Kochmodus), **Auf die Einkaufsliste**, Bearbeiten, Teilen als Text oder schema.org-JSON.
- Original bleibt erreichbar: Link, Bildunterschrift, Transkript, Fotos der Vorlage.

### Übersicht

- Kacheln mit Bild und Titel, umschaltbar auf eine kompakte Liste.
- **Suche über Titel und Zutaten** („was mache ich mit Zucchini“), offline über eine Volltextsuche in der lokalen Datenbank (SQLite FTS, in KMP z. B. über SQLDelight).
- Filter als Chips: Kategorie, Schlagwort, Zeit (unter 30 min), „Kinder essen es“, „lange nicht gekocht“, **„im Angebot“** und **„Saison“** aus den Vorschlägen unten.
- Sortierung: zuletzt hinzugefügt (Standard), zuletzt gekocht, Name.
- Neue Rezepte aus dem Import, die noch nicht bestätigt sind, erscheinen mit Markierung „Vorschlag“, damit nichts doppelt gepflegt werden muss.

### Kochmodus

Der Kochmodus ist für fettige Finger und einen Blick aus einem Meter Entfernung gemacht.

- **Bildschirm bleibt an**, solange der Kochmodus offen ist (Android `FLAG_KEEP_SCREEN_ON`, iOS `isIdleTimerDisabled`), danach wieder normal.
- **Ein Schritt pro Bildschirm**, große Schrift, Weiter und Zurück als große Flächen am unteren Rand und per Wischen.
- **Zutaten des Schritts** stehen über dem Schritttext, mit umgerechneten Mengen. Zuordnung per Regel: Zutat-Name kommt im Schritttext vor. Vorher eine Übersicht „Alles bereitlegen“ zum Abhaken.
- **Timer aus dem Text**: „10 Minuten köcheln“ wird zu einer antippbaren Zeitangabe. Mehrere Timer gleichzeitig, sichtbar oben im Kochmodus und als laufende Benachrichtigung. Der Ablauf meldet sich mit Ton über einen eigenen Benachrichtigungskanal „Kochtimer“. Ob es zusätzlich vibriert, entscheidet die System-Einstellung, die App setzt kein Muster (Entscheidung „keine Vibration“).
- Beim Schließen: „Gekocht?“ als ein Tap, das setzt „zuletzt gekocht“ und hält die Vorschläge frisch.

### Einkaufsliste

Eine gemeinsame Liste für die Familie, synchronisiert auf alle Handys.

- **Aus Rezepten befüllen**, mit den gewählten Portionen. Gleiche Zutaten werden über den Zutat-Schlüssel zusammengeführt und Einheiten umgerechnet (500 g + 0,5 kg = 1 kg; EL und g werden nicht zusammengerechnet, sondern nebeneinander gezeigt). Jeder Eintrag merkt sich, für welches Rezept er ist.
- **Freie Einträge** per Tippen oder Sprache („Milch und Klopapier“), wie beim Erfassen.
- **Grundvorrat**: Salz, Öl, Mehl und andere Grundzutaten kommen nicht automatisch auf die Liste, sondern als ausgegraute Zeile „Hast du noch …?“ zum Antippen.
- **Sortiert nach Markt und Abteilung**: Einträge, die diese Woche im Angebot sind, zeigen Markt und Preis; der Rest nach Abteilung (Obst und Gemüse, Kühlregal …), damit man im Laden nicht zurückläuft.
- **Abhaken** mit einem Tap, Abgehaktes rutscht nach unten und verschwindet nach dem Einkauf. Zwei Personen im selben Laden sehen die Haken des anderen, sobald beide Handys sich erreichen (im selben WLAN oder über Tailscale).
- **Zuständigkeit**: „Einkaufen“ ist eine normale Aufgabe mit Person und Datum, wie alles andere in der App. Damit gilt auch die Eskalation.

Sync-Hinweis: Jeder Eintrag ist ein eigener Datensatz, Abhaken ist ein Feld mit „letzte Änderung gewinnt“ (LWW wie im Sync-Spike). Zwei Personen, die gleichzeitig Milch eintragen, ergeben zwei Einträge; die Liste fasst gleiche Zutat-Schlüssel in der Anzeige zusammen.

### Bewusst später

Wochenplan mit Kalender (Mealie „Meal Planner“): Der Wochenvorschlag unten deckt das Wichtigste ab. Ein eigener Plan mit Tagen kommt, wenn die Familie ihn vermisst. Ebenso Kochbücher (Sammlungen), Nährwerte und Vorratshaltung.

## Erfassen: drei Wege, eine Warteschlange

```
Teilen / Foto / Hand ──► Warteschlange (Gerät) ──► Text gewinnen ──► Rezept bauen ──► Vorschlagskarte ──► Bibliothek
                          sofort gespeichert        OCR, Sprache     Regeln zuerst,     Mensch prüft,
                          läuft im Hintergrund      lokal            lokale KI opt.     „Passt“
```

Prinzip „schnell rein, komplett später“: Teilen speichert sofort den Link als Eintrag (≤ 1 s, kein Dialog), die Verarbeitung läuft danach im Hintergrund (Android: WorkManager, eindeutiger Auftrag je Link, mit Wiederholung). Der Eintrag zeigt seinen Stand (wartet, holt, erkennt, fertig, braucht Hilfe). Geht ein Schritt nicht, bleibt der Eintrag offen und sagt, was fehlt, statt still zu verschwinden.

### Weg 1: Instagram teilen

Das Teilen-Menü von Instagram übergibt der App **nur einen Link** (`instagram.com/reel/…` oder `/p/…`), keine Datei. Der Knackpunkt ohne Server ist deshalb nicht die Erkennung, sondern **an Text und Ton zu kommen**.

| Weg | Wie ohne Server | Bewertung |
| --- | --- | --- |
| **A. yt-dlp in der App** (Empfehlung) | Bibliothek [`youtubedl-android`](https://github.com/yausername/youtubedl-android): bündelt yt-dlp und Python, ffmpeg als Zusatzmodul, yt-dlp lässt sich zur Laufzeit aktualisieren (`updateYoutubeDL`). Holt wie heute Bildunterschrift, Vorschaubild und Ton, zusätzlich das Video für die Texterkennung | dieselbe Technik wie im SurfaceLab, also erprobt. Aktualisierung von yt-dlp ohne neue App-Version, das ist wichtig, weil Instagram seine Seiten oft ändert. **Haken:** Lizenz GPL-3.0, siehe unten. APK wird größer (Python + ffmpeg, Größe im Spike messen) |
| B. WebView, in der der Nutzer selbst bei Instagram angemeldet ist | Bildunterschrift, Bild- und Video-URL aus der Seite lesen | Rückfall, wenn yt-dlp ohne Anmeldung gesperrt wird. Fragil bei Seitenumbauten |
| C. Datei teilen | Nutzer speichert das Reel (Instagram bietet bei vielen öffentlichen Reels „Herunterladen“) oder teilt Screenshots als Datei | immer möglich, ein Schritt mehr |

Danach, egal auf welchem Weg:

- **Bildunterschrift:** direkt in den Rezept-Baustein.
- **Text im Video** (eingeblendete Zutaten): alle 1–2 s ein Bild entnehmen (`MediaMetadataRetriever`), ML Kit Text Recognition (lateinische Schrift, offline), doppelte Zeilen zusammenfassen. Neu gegenüber dem SurfaceLab.
- **Gesprochenes:** Tonspur ohne Neukodierung aus dem MP4 lösen (`MediaExtractor`/`MediaMuxer` → M4A), dann Spracherkennung (unten).
- **Karussell-Bilder:** Texterkennung wie beim Foto.

**Lizenzfrage GPL-3.0:** `youtubedl-android` steht unter GPL-3.0 (yt-dlp selbst unter Unlicense). Eine App, die die Bibliothek einbindet und an andere Familien verteilt wird, müsste nach üblicher Auslegung selbst unter GPL-3.0 stehen und den Quelltext anbieten. Für Selfhost und ausgesuchte Familien kann das passen, für ein geschlossenes Abo-Produkt nicht. Alternativen: yt-dlp und Python selbst einbetten (Unlicense bzw. PSF-Lizenz, mehr Aufwand), oder den Download als getrenntes Zusatzmodul mit eigener Lizenz. Das ist eine Entscheidung für Nils (Empfehlung 15).

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
3. **Cloud-KI (optional):** Für den Rezept-Import gilt Nils' Freigabe vom 01.11.2026 weiter: Wer will, schaltet „Rezept-Import mit Claude“ ein, mit eigenem API-Schlüssel, direkt vom Handy an die API, ohne eigenen Server. Nach außen gehen dann nur Bildunterschrift, Transkript und Rezeptfotos, wie heute. Standard für andere Familien: aus.

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
- **Instagram:** Die Nutzungsbedingungen verbieten das Sammeln von Daten mit automatisierten Mitteln ohne Erlaubnis (aus dem Gedächtnis, vor Zitat prüfen). Der Abruf per yt-dlp ist ein vom Nutzer ausgelöster Einzelabruf eines öffentlichen Posts ohne Anmeldung, kein Massenabruf, und betrifft kein Konto. Restrisiko: Instagram sperrt den anmeldefreien Abruf (dann Rückfall WebView, dort wäre das eigene Konto betroffen) oder es kommt zu Abmahnungen gegen verteilte Apps mit Download-Funktion. Deshalb gibt es immer den Rückfallweg über geteilte Dateien.
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
3. **Die App baut die Mealie-Grundfunktionen nach:** Rezept-Ansicht mit Portionen umrechnen, Übersicht mit Suche über Zutaten, Kochmodus mit wachem Bildschirm und Timern aus dem Text, gemeinsame Einkaufsliste nach Markt und Abteilung. Alte Rezepte nur optional per schema.org-JSON übernehmen.
4. **Eine Warteschlange auf dem Gerät für alle drei Wege**: Teilen speichert sofort, Verarbeitung im Hintergrund, Stand sichtbar, Ergebnis als Vorschlagskarte im Eingang (HITL).
5. **Instagram wie im SurfaceLab über yt-dlp, jetzt in der App** (`youtubedl-android`, yt-dlp zur Laufzeit aktualisierbar), Rückfall angemeldete WebView, letzter Rückfall „Datei teilen“.
6. **Erkennung nur lokal:** ML Kit Text Recognition für Bilder und Videobilder, ML Kit GenAI Speech Recognition für Ton (Deutsch, Pixel 10). Beide Alpha-/Beta-APIs hinter eigenen Schnittstellen.
7. **Regeln zuerst, Gemini Nano als Verstärker** für Fließtext und Gesprochenes. Claude bleibt für den Import als Schalter erhalten (Nils' Freigabe vom 01.11.), mit eigenem Schlüssel direkt vom Handy, standardmäßig aus.
8. **Zutat-Schlüssel beim Import festlegen und bestätigen**, damit Angebots- und Saisonabgleich später ein Nachschlagen ist. Startkatalog aus Synonymen und Plural-Regeln von `preisvergleich`.
9. **Angebote holt ein Android-Handy per WorkManager**, wöchentlich mit WLAN und Ladegerät; das Ergebnis geht per Sync an alle. Dauer-Peer nur, wenn es ihn ohnehin gibt.
10. **Aldi Nord, Lidl, Kaufland zuerst, REWE nicht in die App**, weil es einen Bot-Schutz umgehen müsste. Jede Familie holt nur für sich, keine zentrale Angebotsdatenbank.
11. **Abgleichslogik aus `preisvergleich` nach Kotlin portieren** (`shared/`, reine Logik mit Tests), statt neu zu schreiben.
12. **Vorschläge deterministisch mit Begründung**, Montags als Karte unter Heute, Auswahl per Antippen, daraus Einkaufsliste mit Zuständigkeit.
13. **Videos und Originalmedien nicht speichern**, nur Titelbild (klein) und Quelllink.
14. **Erst ein Spike, dann Bau** (nächster Abschnitt). Der Prototyp-Thread v0.2 bleibt davon unberührt.
15. **Lizenz entscheiden, bevor Download-Code in die App kommt:** Für den Spike `youtubedl-android` nehmen (schnell, erprobt). Vor dem Verteilen an andere Familien entscheiden: App unter GPL-3.0 stellen oder yt-dlp selbst einbetten.
16. **Der SurfaceLab-Importer läuft weiter, bis die App ihn im Vergleich einholt.** Abschalten erst, wenn der Spike die Abnahmekriterien erfüllt.

## Nächster Schritt: Spike „Rezept-Import“

Ziel in einem Satz: Zeigen, dass aus 30 echten Instagram-Rezepten ohne Server und ohne Cloud-KI brauchbare Rezeptvorschläge entstehen.

Vorgehen:

1. **Vergleichsbasis ohne Mealie:** Nils legt 30 Instagram-Links in eine Textdatei. `spikes/recipe-import/tools/collect_samples.py` holt Bildunterschrift und Transkript; die Zutaten trägt Nils ein oder lässt sie von Claude vorschreiben (Qualität des heutigen Importers) und prüft sie (`expected-private.csv`). Ablage nur in `samples-private/`, wie beim Regel-Spike. Mischung prüfen: Rezept in Bildunterschrift, eingeblendeter Text, nur gesprochen, Karussell; fehlende Arten mit neuen Posts ergänzen.
2. **Teil A (JVM, in der Cloud testbar):** Bildunterschriften und vorhandene Transkripte durch den Regel-Baustein, gemessen an der Erwartung.
3. **Teil B (Pixel 10, kleine Test-App):** dieselben 30 Links durch yt-dlp auf dem Handy, Texterkennung aus Videobildern, Spracherkennung, Gemini Nano als Verstärker. Gemessen auf dem echten Gerät, im WLAN und im Mobilfunknetz.
4. Ergebnis als Tabelle: pro Link Regeln allein, Regeln + Nano, Claude, Abweichungen pro Zutat.

Abnahmekriterien:

- ≥ 90 % der Zutatenzeilen erkannt, ≥ 80 % mit richtiger Menge und Einheit, gemessen gegen `expected-private.csv`
- Kein erfundenes Rezept: Ist keine Zutatenliste zu finden, bleibt die Karte offen mit Hinweis (0 Fälle mit erfundenen Zutaten)
- Teilen bis fertiger Vorschlag im Median ≤ 60 s auf dem Pixel 10, ohne dass die App offen bleiben muss

Danach separat: Angebots-Abruf auf dem Handy (Kotlin-Port von `aldi.py`, `lidl.py`, `kaufland.py`) mit Vergleich gegen die Python-Ausgabe derselben Woche als Prüfung.

## Offene Punkte

- [ ] Welche Instagram-Posts scheitern heute im SurfaceLab (Logs, Fehler in `jobs.json`)? Das ergänzt die Vergleichsbasis um die schwierigen Fälle.
- [ ] APK-Größe mit `youtubedl-android` und ffmpeg im Spike messen.
- [ ] Welche Märkte und welche Filialen/PLZ genau? Aldi Nord oder Süd?
- [ ] Welche Handys hat die Familie? Für Spracherkennung „Advanced“ braucht es Pixel 10 oder ein Gerät mit Gemini Nano; iPhones bekommen später einen eigenen Spike.
- [ ] `robots.txt` und Nutzungsbedingungen der Märkte auf Vorbehalt nach § 44b UrhG prüfen.
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
- Rezept-Importer aus dem SurfaceLab (`instagram_to_mealie.py`, Teilen-PWA), von Nils hochgeladen am 21.11.2026
- youtubedl-android: https://github.com/yausername/youtubedl-android (abgerufen 21.11.2026)
