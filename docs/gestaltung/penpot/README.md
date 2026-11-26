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

## 3. Claude direkt mit Penpot verbinden (MCP, auf dem eigenen PC)

Aus Claudes Cloud-Umgebung (Projekt-Threads) ist Penpot nicht erreichbar. Die Verbindung klappt mit Claude Code auf dem eigenen PC, z. B. im Plugin in Android Studio. Quelle der Schritte: [README des offiziellen Penpot-MCP-Servers](https://github.com/penpot/penpot/tree/develop/mcp).

Einmalig: [Node.js](https://nodejs.org/) 20 oder neuer installieren.

Jedes Mal:

1. Terminal öffnen (unter Windows Git Bash) und starten: `npx -y @penpot/mcp@latest`. Das startet den MCP-Server (Port 4401) und das Penpot-Plugin (Port 4400). Fenster offen lassen.
2. In Penpot die Datei öffnen → **Plugins** → Plugin über die Adresse `http://localhost:4400/manifest.json` laden → Plugin öffnen → **Connect to MCP server**. Status muss „Connected“ zeigen. Chrome fragt nach Zugriff aufs lokale Netzwerk: erlauben. Plugin-Fenster und Penpot-Tab offen lassen.

Einmalig in Claude Code (Terminal):

```
claude mcp add --transport http penpot http://localhost:4401/mcp
```

Danach z. B. in Claude Code: „Lies das Board ‚Heute neu‘ aus Penpot und setze es in `Screens.kt` als `TodayScreen` um, mit den vorhandenen Bausteinen.“

## Selfhost (optional)

Penpot ist Open Source (MPL-2.0). Die offizielle Docker-Compose-Datei startet Frontend, Backend, Exporter, MCP-Server, PostgreSQL und Valkey (sechs Container), läuft also auf der Synology mit Container Manager. Lohnt sich erst, wenn die Cloud nicht mehr reicht oder Entwürfe nicht bei Penpot liegen sollen.
