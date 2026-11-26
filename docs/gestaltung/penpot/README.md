# Penpot: Bildschirme per Drag-and-drop entwerfen

Penpot ist das Zeichenbrett, der Compose-Code bleibt die Wahrheit. Nils entwirft oder ändert Bildschirme in Penpot, Claude überträgt sie nach Compose und öffnet einen PR. Begründung der Werkzeugwahl: [ui-werkzeuge.md](../../ui-werkzeuge.md).

In Penpot landen nur Entwürfe mit Beispieldaten (Nils, Lisa, Mia, Oma Gerda), **nie echte Familiendaten**. Deshalb reicht die kostenlose Cloud; Selfhost auf der Synology ist möglich, aber nicht nötig.

## 1. Einmalig einrichten (ca. 10 Minuten)

1. Konto auf **design.penpot.app** anlegen (kostenlos).
2. Neues Projekt „FamilyApp“, darin eine Datei „FamilyApp UI“.
3. **Farben und Ecken importieren:** In der Datei links den Reiter **Tokens** öffnen → unten **Tools → Import → Single JSON file** → [`familyapp-tokens.json`](familyapp-tokens.json) wählen (aus dem Repo-Klon, `docs/gestaltung/penpot/`).
   Danach gibt es drei Sets: `Basis` (Eckenradien 16 und 28), `Hell` und `Dunkel` (alle App-Farben, `color.person.1` bis `.4` sind die Personenfarben). `Basis` + `Hell` sind schon aktiv; für dunkle Entwürfe `Hell` aus- und `Dunkel` einschalten.
   Die Datei folgt Penpots Mehr-Set-Format mit `$themes` und `$metadata` (wie Penpots eigene Testdaten). Ohne diese beiden Schlüssel liest Penpot die ganze Datei als ein einziges Set und lehnt die Namen ab.
4. Ein Board (Rahmen) **412 × 900** anlegen, das entspricht dem Desktop-Fenster und etwa einem Pixel-Handy in dp. 1 px in Penpot = 1 dp in Compose.

Die Farben in der Datei stimmen garantiert mit der App überein: Ein Test (`PenpotTokensTest`) vergleicht sie bei jedem PR mit `Theme.kt`. Ändert sich eine Farbe in der App, schlägt der Test fehl, bis die JSON-Datei angepasst ist; danach in Penpot erneut importieren.

## 2. Ablauf

1. **Entwerfen:** Bildschirm oder Baustein zusammenschieben. Farben immer über die Tokens wählen (Linksklick auf ein Token = Füllfarbe), dann kann Claude sie eindeutig `LocalFamilyColors` zuordnen. Ebenen sinnvoll benennen („InboxCard“, „Chip Wer“), das erleichtert die Übertragung.
2. **Übergeben**, eine der zwei Arten:
   - **Mit MCP (empfohlen, auf Nils' PC):** Claude liest das Board direkt aus Penpot, siehe Abschnitt 3.
   - **Ohne MCP (geht überall, auch aus einem Projekt-Thread):** Board als PNG exportieren (*Export → PNG, 2×*) und im Thread hochladen; bei kniffligen Stellen zusätzlich aus dem Reiter **Inspect** den Code (CSS) kopieren.
3. **Umsetzen:** Claude baut den Entwurf mit den vorhandenen Bausteinen (`InboxCard`, `PersonChip`, `FamilyIcons`, …) in Compose, öffnet einen PR, Nils prüft im Hot-Reload-Fenster oder auf dem Handy.

Auch umgekehrt möglich: Claude zeichnet einen bestehenden Bildschirm aus dem Code nach Penpot, als Ausgangspunkt für Änderungen.

## 3. Claude direkt mit Penpot verbinden (MCP)

Quelle: [Penpot-Hilfe zum MCP-Server](https://help.penpot.app/mcp/). Es gibt zwei Wege; der gehostete Remote-Server ist der einfache.

### A. Remote-Server mit MCP-Key (empfohlen)

Kein Node.js, kein Terminal-Server, kein Plugin per Adresse laden.

1. In Penpot: **Your account → Integrations → MCP Server** → Status einschalten → Key erzeugen. Der Key wird nur einmal angezeigt, es gibt einen pro Person; neu erzeugen macht den alten sofort ungültig.
2. Die angezeigte Server-URL kopieren. Sie hat die Form `https://design.penpot.app/mcp/stream?userToken=…` und **enthält den Key**. Deshalb wie ein Passwort behandeln: nicht in Chats, Threads oder ins Repo kopieren.
3. Bei Claude eintragen, eine der Arten:
   - **claude.ai / Claude-App:** *Einstellungen → Connectors → Add custom connector* ([claude.ai/customize/connectors](https://claude.ai/customize/connectors)), Name „Penpot“, URL einfügen. So empfiehlt es Penpot für Claude Desktop.
   - **Claude Code auf dem PC** (Terminal): `claude mcp add --transport http penpot "<URL>"` (URL in Anführungszeichen wegen `?`).
4. In Penpot die Datei öffnen → **File → MCP Server → Connect**. Der Penpot-Tab muss offen und aktiv bleiben (nur ein Tab gleichzeitig); Claude arbeitet immer auf der gerade geöffneten Seite.

Grenzen laut Penpot: Der Remote-Server hat keinen Zugriff auf lokale Dateien (Bilder von der Festplatte importieren geht nicht, Export nicht direkt in eine Datei). Für den Weg Entwurf → Compose reicht das. Penpot rät, mit lesenden Aufträgen zu beginnen; Schreiben ist möglich. MCP ausschalten oder trennen stoppt alle Änderungen sofort.

### B. Lokaler Server (nur nötig für Dateizugriff)

Einmalig [Node.js](https://nodejs.org/) 20+ installieren. Dann jedes Mal `npx -y @penpot/mcp@stable` im Terminal starten (MCP-Server Port 4401, Plugin Port 4400), in Penpot das Plugin über `http://localhost:4400/manifest.json` laden und **Connect to MCP server** klicken. Einmalig in Claude Code: `claude mcp add --transport http penpot http://localhost:4401/mcp`. Details: [README des Penpot-MCP-Servers](https://github.com/penpot/penpot/tree/develop/mcp).

### Danach

Zum Beispiel: „Lies das Board ‚Heute neu‘ aus Penpot und setze es in `Screens.kt` als `TodayScreen` um, mit den vorhandenen Bausteinen.“

## Selfhost (optional)

Penpot ist Open Source (MPL-2.0). Die offizielle Docker-Compose-Datei startet Frontend, Backend, Exporter, MCP-Server, PostgreSQL und Valkey (sechs Container), läuft also auf der Synology mit Container Manager. Lohnt sich erst, wenn die Cloud nicht mehr reicht oder Entwürfe nicht bei Penpot liegen sollen.
