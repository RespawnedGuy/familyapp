# Spike: Rezept-Import ohne Server, Teil A (Regeln)

Wegwerfcode (Reifestufe Spike). Frage: Wie weit kommen feste Regeln allein, wenn sie aus einer Instagram-Bildunterschrift (oder Texterkennung, Transkript) ein Rezept bauen? Konzept und Abnahmekriterien: [docs/kochbibliothek.md](../../docs/kochbibliothek.md#nächster-schritt-spike-rezept-import).

Abnahme: ≥ 90 % der Zutatenzeilen gefunden, ≥ 80 % mit richtiger Menge und Einheit, keine erfundenen Zutaten. Findet der Baustein keine Zutatenliste, bleibt die Karte offen (`NO_INGREDIENTS`) statt zu raten.

## Ausführen

```
gradle test
gradle run --args="examples/samples examples/expected.csv"
```

Die Beispiele in `examples/` sind selbst geschrieben und zeigen nur, dass die Messung funktioniert. Aussagekräftig ist erst der Lauf mit echten Rezepten.

## Mit echten Rezepten (nur lokal, nie committen)

1. 30 Instagram-Links in eine Textdatei `links.txt` legen, einer pro Zeile.
2. Auf Surfacelab (yt-dlp, für Transkripte zusätzlich ffmpeg und der lokale Whisper-Server):
   `python3 tools/collect_samples.py links.txt ~/rezept-spike --transcript`
   Mit `--draft-with-claude` (und `ANTHROPIC_API_KEY`) schreibt Claude die Zutaten vor, sonst trägst du sie selbst ein.
3. `~/rezept-spike/expected-private.csv` durchsehen und korrigieren: eine Zeile pro Zutat, `id;250 g Mehl`.
4. `gradle run --args="~/rezept-spike/samples-private ~/rezept-spike/expected-private.csv"`

Format: ein Ordner pro Rezept mit `caption.txt`, optional `ocr.txt` und `transcript.txt`; `expected-private.csv` mit `id;line`, eine Zeile pro Zutat.

## Logik

- **Zeile → Zutat** (`IngredientLineParser`): Menge (auch `1/2`, `½`, `1,5`, `2-3`), Einheit aus fester Liste (g, EL, Zehe, Pck. …), Name, Notiz nach Komma oder in Klammern. Menge am Ende („Salz, 1 Prise“) wird erkannt.
- **Text → Rezept** (`RecipeTextParser`): Überschriften „Zutaten“, „Zubereitung“, „So geht's“; Unterüberschriften („Für den Teig:“) werden übersprungen. Ohne Überschrift zählt nur ein Block von mindestens drei kurzen Zeilen mit Menge, davon zwei mit Einheit. Hashtags, Erwähnungen und Aufrufe („Speichert euch das Rezept“) fliegen raus.
- **Mehrere Quellen**: Bildunterschrift, dann Text im Video, dann Transkript, jede für sich. Die Zutatenliste kommt aus der ersten Quelle, die eine hat.
- **Vergleich** (`Evaluation`, `IngredientKey`): Zutaten werden über einen Schlüssel verglichen (Kopfwort, Plural weg, Synonyme, wie in `preisvergleich`). „Erfunden“ zählt Zutaten, die es in der Erwartung gar nicht gibt.

Bekannte Grenze: Gesprochene Rezepte („Ihr nehmt drei Eier und einen Schuss Milch“) erkennen Regeln nicht, siehe Beispiel 03. Das ist der Fall für Gemini Nano in Teil B.
