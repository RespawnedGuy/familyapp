# ADR 0008: Rezepte aus Reels per wählbarer Cloud-KI, Gemini liest das Video

- Status: Angenommen (Nils, 27.11.2026, Entscheidungskarte im Thread „Rezepte von Instagram einlesen“: „Gemini (kostenlos, Video)“)
- Datum: 2026-11-27
- Ersetzt: ADR 0007, Entscheidung 6 (Transkription auf dem Gerät als nächster Schritt)
- Bezug: [ADR 0007](0007-instagram-text-ohne-yt-dlp.md), [Bedrohungsmodell](../qualitaet/bedrohungsmodell.md) TE-11, TE-12

## Kontext

Caption-Abruf und Teilen laufen auf dem Handy (Nils, 27.11.2026). Bei den meisten Rezept-Reels steht das Rezept aber nicht in der Caption, sondern wird gesprochen oder als Text ins Video eingeblendet, oft nur mit Musik darunter. Nils schlug eine Transkript-KI mit Aufbereitung vor und nannte kostenlose Anbieter (Gemini, Perplexity, Duck.ai, OpenRouter).

Geprüft am 27.11.2026 in der Dokumentation der Anbieter:

| Anbieter | Befund | Folge |
| --- | --- | --- |
| Google Gemini (Schlüssel aus AI Studio) | Gratisstufe für die Flash-Modelle, nimmt Video mit Bild und Ton und antwortet nach JSON-Schema. Inhalte der Gratisstufe nutzt Google zur Produktverbesserung | **Standard** |
| Groq | Whisper (large-v3 / -turbo), nimmt mp4 direkt, Gratisstufe; nur Ton | später wählbar |
| OpenRouter | ein Schlüssel für viele Modelle, eigener Transkriptions-Endpunkt (kostet pro Nutzung); ob ein Gratis-Modell Ton nimmt, ist nicht dokumentiert | später wählbar |
| Claude | schon eingebaut, nur Text, bezahlt | bleibt wählbar |
| ML Kit GenAI Speech Recognition (auf dem Gerät) | transkribiert Tondateien, Deutsch über Gemini Nano u. a. auf dem Pixel 10, Alpha | später als private Option |
| Perplexity | keine Gratisstufe, kein Ton, Websuche-Produkt | verworfen |
| Duck.ai | keine offizielle API, nur inoffizielle Nachbauten des Web-Chats | verworfen |

Plan mit Quellen: Claude-Dokument „Plan: Wählbare KI für Rezepte aus Instagram-Videos“ (im Thread verlinkt).

## Entscheidung

1. **Gemini ist der empfohlene Anbieter.** Findet die Regel-Engine in der Caption keine Zutaten und ist ein Gemini-Schlüssel eingetragen, lädt die App das Video des Reels und schickt Video und Caption in **einem** Aufruf an Gemini (`gemini-flash-latest`, generateContent, `response_schema`). Gemini liest Gesprochenes und Einblendungen. Die Antwort geht durch denselben Zutaten-Parser und bleibt ein Vorschlag mit Rückgängig.
2. **Anbieter am Schlüssel erkannt.** Die Person fügt nur ihren Schlüssel ein; `AIza…` ist Gemini, `sk-ant-…` Claude. Unbekannte Schlüssel nimmt die App nicht an. So braucht es keine zusätzliche Auswahl, und weitere Anbieter (Groq `gsk_`, OpenRouter `sk-or-`) lassen sich genauso ergänzen.
3. **Video nur von Instagrams CDN.** Die Adresse kommt aus der Einbettungsseite (`video_url`) oder `og:video`, nie aus dem geteilten Link; nur `https` auf `*.cdninstagram.com` / `*.fbcdn.net`, höchstens 50 MB, keine Weiterleitungen, keine Cookies. Das Video liegt nur im Cache und wird nach der Antwort gelöscht.
4. **Kleine Videos inline, große über die Files API.** Bis 14 MB geht das Video base64 im Aufruf mit (Googles Grenze 20 MB pro Aufruf), darüber wird es hochgeladen, die App wartet auf „ACTIVE“ (höchstens 90 s), fragt und löscht die Datei bei Google wieder.
5. **Kein stilles Weiterreichen.** Scheitert Gemini, sagt die App warum (Schlüssel, Kontingent, Region, nicht erreichbar); sie fragt keinen anderen Anbieter. Klappt nur der Video-Download nicht, liest Gemini die Caption allein.
6. **Ohne SDK**, wie bei Claude: `HttpURLConnection` und selbst gebautes JSON (`GeminiRecipeRequest`, getestet).

## Folgen

- Kostenlos für die Familie, solange die Gratisstufe reicht; die genauen Tagesgrenzen zeigt Google nur in AI Studio. Bei Fehler 429 sagt die App das.
- Öffentliche Reels gehen an Google und dürfen dort zur Produktverbesserung genutzt werden. Das passt zur Freigabe „Rezepte kopieren ist unkritisch“; Familiendaten gehen nie hinaus. Der Hinweis steht in den Einstellungen.
- `gemini-flash-latest` wechselt Google mit zwei Wochen Vorlauf; eine feste Version würde bei der Abschaltung ohne App-Update brechen.
- **Nicht geprüft:** ob Instagrams CDN das Video an den App-Abruf ausliefert (die Cloud-Umgebung erreicht weder Instagram noch Google), und das Feldformat von generateContent gegen die echte API (die Doku ist zwischen generateContent und der neuen Interactions API uneinheitlich; gewählt sind die snake_case-Felder der Referenzbeispiele). Prüfen: einen Reel ohne Rezept in der Caption auf dem Handy teilen.
- Nächste Schritte laut Plan: Schlüssel in den Android Keystore und nicht mehr in die Familien-Sicherung; Groq und OpenRouter; Transkription auf dem Gerät; Messung der Anbieter im Spike `spikes/recipe-import/`.
