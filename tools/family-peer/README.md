# family-peer – das Hausgerät

Die Sync-Logik der App als Kommandozeilenprogramm für einen Rechner, der immer an ist (Surface, Synology mit Docker oder Java, Raspberry Pi). Es ist kein Server: Es koppelt sich wie ein weiteres Handy, kennt den Familienschlüssel und sammelt und verteilt Änderungen. So bekommen zwei Handys ihre Einträge auch dann, wenn sie nie gleichzeitig offen sind (ADR 0003, ADR 0006).

## Bauen

Java 21 und Gradle 8.14, nur Maven Central nötig:

```
cd tools/family-peer
gradle installDist
build/install/family-peer/bin/family-peer
```

## Einrichten

1. Auf dem Handy: Einstellungen → Geräte und Abgleich → „Handy koppeln“ → „Code als Text zeigen“, Code kopieren.
2. Auf dem Hausgerät (Code in Anführungszeichen):
   ```
   family-peer join ~/familyapp-haus "FAMILYAPP1:…" --name Surface
   ```
   Beide zeigen eine sechsstellige Prüfzahl. Stimmt sie, auf dem Hausgerät `j` tippen und auf dem Handy „Hausgerät (keine Person)“ wählen und „Stimmt“.
3. Dauerbetrieb:
   ```
   family-peer serve ~/familyapp-haus
   ```
   Optional zusätzlich über einen geteilten Ordner: `--folder /pfad/zum/Syncthing-Ordner`.

Unterwegs erreichen die Handys das Hausgerät über Tailscale (Adresse 100.x), zu Hause im WLAN. Port 47420 nur im lokalen Netz bzw. Tailnet freigeben, nie ins Internet.

## Wichtig

- Das Verzeichnis (`~/familyapp-haus`) enthält in `sync.json` den Familienschlüssel. Nie in ein Repository, eine Sicherung ohne Verschlüsselung oder einen geteilten Ordner legen.
- Das Programm setzt das Verzeichnis auf `rwx------` und `sync.json` auf `rw-------` (Linux, Synology, macOS), andere Konten des Rechners kommen nicht hinein. Die Familiendaten liegen darin aber unverschlüsselt (anders als auf dem Handy gibt es keine Android-Sandbox): Festplattenverschlüsselung einschalten (BitLocker, LUKS, verschlüsseltes Synology-Volume). Wer Administrator auf dem Rechner ist, kann alles lesen ([Bedrohungsmodell](../../docs/qualitaet/bedrohungsmodell.md), HG-1).
- Im Tailnet per ACL nur die Familiengeräte an Port 47420 lassen (HG-2).
- Umlaute in Argumenten brauchen eine UTF-8-Umgebung (`LC_ALL=C.UTF-8` unter Linux).
- Hausgerät und App brauchen dieselbe App-Version (Datenschema), sonst lehnen sie den Abgleich mit Hinweis ab.

## Weitere Befehle

`family-peer sync <dir> [--via host:port]` einmal abgleichen · `show <dir>` Personen und Einträge · `add <dir> <text>` Eintrag erfassen · `init`/`invite` zum Ausprobieren zwischen zwei Rechnern ohne Handy. `--via host:port` schickt alle Verbindungen an eine Adresse (z. B. adb-Portweiterleitung zum Emulator).
