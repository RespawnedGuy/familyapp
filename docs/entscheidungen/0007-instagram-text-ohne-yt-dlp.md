# ADR 0007: Rezepttext von Instagram auf dem Handy holen, ohne yt-dlp, Claude als Schalter

- Status: Angenommen (Nils, 26.11.2026, Entscheidungskarte im Thread „Rezepte von Instagram einlesen“)
- Datum: 2026-11-26
- Ersetzt: Empfehlung 5 und 15 in [docs/kochbibliothek.md](../kochbibliothek.md) (yt-dlp per `youtubedl-android` in der App)
- Bezug: [ADR 0003](0003-p2p-sync-im-selben-netz.md) (kein Server), [Bedrohungsmodell](../qualitaet/bedrohungsmodell.md) TE-9 bis TE-11

## Kontext

Das Teilen-Menü von Instagram übergibt der App nur einen Link. Seit PR #40 wird daraus ein leerer Rezeptvorschlag, den Text muss jemand von Hand einfügen. Das Konzept empfahl yt-dlp über die Bibliothek `youtubedl-android`, mit offener Lizenzfrage. Der Importer im SurfaceLab und `spikes/recipe-import/tools/collect_samples.py` holen Caption und Transkript schon mit yt-dlp und Whisper, aber auf dem Server. In der App soll es ohne Server gehen.

Geprüft am 26.11.2026:

| Weg | Befund | Folge |
| --- | --- | --- |
| **Einbettungsseite** `instagram.com/p/<code>/embed/captioned/` | Ohne Anmeldung abgerufen (Heim-IP, Browser ohne Cookies): bei 11 von 13 öffentlichen Beiträgen steht die **ganze Caption** in einem JSON (`contextJSON` → `gql_data.shortcode_media.edge_media_to_caption`), dazu Benutzername, `is_video` und bei Reels die Video-Adresse. Bei den 2 übrigen (Bildbeiträge) ist das JSON `null`, die Caption steht aber im sichtbaren Block `<div class="Caption">` mit `<br>` | Hauptweg. Eine Abfrage, etwa 250 KB |
| **Beitragsseite**, Open-Graph `og:description` | Bei allen 13 Beiträgen die ganze Caption, auch bei 2.100 Zeichen, mit Vorsatz „N likes, M comments - konto am Datum: "…"“ | Zweiter Versuch, etwa 600 KB |
| **oEmbed** (`graph.facebook.com/instagram_oembed`) | Braucht ein Zugriffstoken einer Meta-App mit App-Prüfung. Ohne Server stünde das Token in jeder APK | verworfen |
| **yt-dlp** (Version 2026.11.23, Quelltext `extractor/instagram.py` gelesen) | Ohne Anmeldung nutzt yt-dlp die GraphQL-Schnittstelle von Instagram nur mit **Browser-Imitation** (`impersonate=True`, curl_cffi); ohne sie gibt es diesen Weg nicht. curl_cffi ist in `youtubedl-android` nicht enthalten. yt-dlp selbst steht unter der Unlicense (gemeinfrei), `youtubedl-android` unter GPL-3.0 | in der App verworfen |
| **Angemeldete WebView** | Ginge, betrifft aber das Instagram-Konto der Person | nicht gebaut |
| **Text, den Instagram beim Teilen mitgibt** | nur der Link | bleibt Auslöser |

Lizenz: Das Repo ist öffentlich und hat noch keine Lizenzdatei (alle Rechte vorbehalten). Vertrieb an ausgesuchte Familien mit Abo ist geplant. Eine GPL-3.0-Bibliothek in der APK würde verlangen, die ganze App unter GPL-3.0 mit Quelltext weiterzugeben.

## Entscheidung

1. **Eigener schlanker Abruf in Kotlin, keine GPL-Abhängigkeit.** `HttpInstagramPages` (androidMain, nur JDK) lädt die Einbettungsseite, bei Misserfolg die Beitragsseite. `InstagramPage` (commonMain, reine Textarbeit) liest die Caption an drei Stellen: JSON, Caption-Block, Open Graph.
2. **Fester Host, Pfad nur aus dem Shortcode gebaut**, keine Cookies, keine Anmeldung, Weiterleitungen werden nicht verfolgt (Weiterleitung = Anmeldewand), höchstens 3 MB, 10 s Zeitlimit.
3. **Ergebnis bleibt ein Rezeptvorschlag.** Die Caption geht durch dieselben Regeln wie eingefügter Text, der Vorschlag bleibt „Vorschlag“, bis ein Mensch bestätigt. Hat jemand den Vorschlag in der Zwischenzeit geändert, wird nichts überschrieben. Rückgängig nimmt nur den geholten Text zurück.
4. **Geht der Abruf nicht**, sagt die App das („Beschreibung in Instagram kopieren und im Vorschlag einfügen“), der Link bleibt gespeichert.
5. **Cloud-KI als Schalter (Nils, 26.11.2026: „Rezepte kopieren ist unkritisch“).** Findet die Regel-Engine in der geholten Caption keine Zutaten und hat die Person in den Einstellungen einen eigenen Anthropic-API-Schlüssel eingetragen, liest Claude (`claude-opus-5-5`, Aufwand `low`, strukturierte Ausgabe nach JSON-Schema, serverseitige Ausweichmodelle bei Ablehnung) den Text. An Anthropic geht **nur die öffentliche Caption**, keine Familiendaten, keine eingefügten oder geteilten Privattexte. Die Antwort geht durch denselben Zutaten-Parser und bleibt ein Vorschlag. Ohne Schlüssel ist nichts davon aktiv. Der Schlüssel liegt wie der Wiederherstellungscode nur in den Einstellungen dieses Handys (und in der verschlüsselten Sicherung), er wird nicht abgeglichen.
   - **Ohne SDK:** Das offizielle Java-SDK (`anthropic-java` 3.8.0) bringt allein im Kern-JAR etwa 43 MB mit, dazu OkHttp und Jackson. Die APK hat heute 7 MB und R8 ist aus. Für eine einzige optionale Anfrage nutzt die App deshalb `HttpURLConnection` und baut den JSON-Body selbst (`ClaudeRecipeRequest`, getestet). Kommt später mehr Claude-Nutzung dazu (z. B. Fotos von Rezepten), lohnt der Wechsel aufs SDK zusammen mit R8.
6. **Abgelöst durch [ADR 0008](0008-waehlbare-ki-gemini-video.md) (Gemini liest das Video).** Ursprünglich: **Nächster Schritt Transkription:** Steht in der Caption keine Zutatenliste und ist der Beitrag ein Video, holt die App die Video-Adresse aus derselben Einbettungsseite und erkennt die Sprache auf dem Gerät (statt Whisper im SurfaceLab). Eigener PR.

## Folgen

- Keine Lizenzpflicht aus dem Abruf; die Lizenz der App bleibt frei wählbar (eine Lizenzdatei fehlt noch, das ist eine eigene Entscheidung).
- Kein Python, kein ffmpeg in der APK.
- Instagram kann die Seiten jederzeit ändern. yt-dlp würde Änderungen ohne App-Update nachziehen, wir brauchen dann eine neue App-Version. Gegenmittel: drei unabhängige Fundstellen, Rückfall auf Einfügen von Hand, und die Tests beschreiben die erwartete Struktur.
- **Nicht geprüft:** ob Instagram einen Abruf mit dem TLS-Fingerabdruck von Android (`HttpURLConnection`) genauso beantwortet wie einen Browser. Die Cloud-Umgebung erreicht instagram.com nicht, der Test lief im Browser. Prüfen: auf dem Handy einen Reel teilen. Antwortet Instagram dem App-Abruf mit der Anmeldewand, ist der Rückfall eine **unsichtbare WebView ohne Anmeldung**, die dieselbe Einbettungsseite lädt (echter Chromium-Fingerabdruck).
- Nutzungsbedingungen von Instagram: Sie untersagen das automatisierte Sammeln von Daten ohne Erlaubnis. Hier ruft eine Person einen einzelnen öffentlichen Beitrag ab, den sie gerade selbst geteilt hat, über die Seite, die Instagram zum Einbetten auf fremden Webseiten anbietet, ohne Konto und ohne Massenabruf. Restrisiko: Instagram sperrt die Seite oder beanstandet eine verteilte App. Dann bleibt das Einfügen von Hand.
- Cloud-KI: Kosten trägt die Person über ihren eigenen Schlüssel (wenige Cent pro Rezept). Die Caption ist fremder Text und könnte Anweisungen an die KI enthalten (Prompt-Injection). Folgen sind begrenzt: keine Werkzeuge, nur ein Schema mit Titel, Zutaten, Schritten, das Ergebnis ist ein Vorschlag mit Rückgängig (Bedrohungsmodell TE-11).
- Datenschutz: Instagram sieht die IP-Adresse des Handys und welchen Beitrag es abruft. Die Person hat den Beitrag eben in Instagram angesehen, Instagram erfährt also nichts Neues.
