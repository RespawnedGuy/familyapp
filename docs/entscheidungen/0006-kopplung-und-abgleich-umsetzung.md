# ADR 0006: Kopplung per QR-Code und Abgleich – wie es in der App umgesetzt ist

- Status: Angenommen (mit PR #29)
- Datum: 2026-11-26
- Setzt um: [ADR 0003](0003-p2p-sync-im-selben-netz.md) (Direktabgleich im WLAN oder Tailnet, geteilter Ordner, Hausgerät)
- Weicht ab von: ADR 0002/0003 bei Krypto-Bausteinen und Datenmodell des Abgleichs (siehe „Abweichungen“)

## Kontext

ADR 0003 legt fest: kein Server, Geräte gleichen direkt ab, Kopplung per QR-Code mit Bestätigung auf beiden Geräten, kein Konto. Nils (26.11.): Abgleich über gängige Cloud-Dienste (Google Drive) oder einen lokalen Ordner mit Syncthing war schon Teil der Planung. Die App muss ab Android 8 (API 26) laufen und soll keine Google-Play-Dienste und keine zusätzliche Krypto-Bibliothek brauchen.

## Entscheidung

1. **Krypto nur mit Bordmitteln der Plattform (javax.crypto).** P-256-ECDH mit flüchtigen Schlüsseln, AES-256-GCM, SHA-256, HMAC/HKDF (RFC 5869, mit dem Testvektor aus dem RFC geprüft). Ed25519/X25519 gibt es auf Android erst ab API 33, eine Zusatzbibliothek (Tink, BouncyCastle) wäre neue Lieferkette.
2. **Ein Handshake für Kopplung und Abgleich**, nach dem Muster von Noise NNpsk0: beide schicken einen flüchtigen Schlüssel und 32 Zufallsbytes, das gemeinsame Geheimnis geht zusammen mit einem vorab geteilten Schlüssel (psk) in HKDF. Beim Abgleich ist der psk aus dem Familienschlüssel abgeleitet, bei der Kopplung das Einmalgeheimnis aus dem QR-Code. Wer den psk nicht kennt, scheitert an der ersten verschlüsselten Nachricht. Jede Richtung hat eigene Schlüssel und Zählernonces.
3. **Kopplung:** Der QR-Code enthält Name, Geräte-ID, Adressen (WLAN, Tailscale), 32 Byte Einmalgeheimnis und Ablauf (10 Minuten). Beide Geräte zeigen eine sechsstellige Prüfzahl aus dem Handshake-Transkript. Erst wenn beide Personen „Stimmt“ tippen, schickt das einladende Gerät Familienschlüssel und Daten. Der Code gilt nur einmal. Wer den QR-Code nur abfotografiert, bekommt eine andere Prüfzahl (Test `manInTheMiddleWithTheQrSecretGetsDifferentDigits`).
4. **Abgleich zustandsbasiert:** Jeder Datensatz (Person, Eintrag, Grabstein) hat einen Zeitstempel (HLC aus PR #25). Geräte tauschen eine Übersicht „ID → neuester Stempel“ und schicken nur, was beim anderen älter ist oder fehlt, auch Datensätze fremder Geräte. Neuerer Stempel gewinnt pro Datensatz, ein neuerer Grabstein löscht. Damit konvergieren drei Geräte, auch wenn sich zwei davon nie treffen (Test mit fünf Zufallsfolgen).
5. **Zwei Wege, ein Format:**
   - **Direkt** über TCP, Port 47420 (sonst ein freier), Geräte im WLAN per DNS-SD `_familyapp._tcp`, über Tailscale per gemerkter Adresse.
   - **Geteilter Ordner** (Syncthing, Synology Drive, Google Drive über die Dateiauswahl des Systems): jedes Gerät schreibt seinen Stand verschlüsselt in `familyapp-abgleich/<Geräte-ID>.fas`, Fotos in `familyapp-abgleich-fotos/`. Der Ordnerdienst sieht nur Chiffretext. Das funktioniert auch, wenn nie zwei Geräte gleichzeitig online sind.
6. **Hausgerät als Kommandozeilenprogramm** (`tools/family-peer`), kompiliert aus denselben Quellen wie die App. Es koppelt sich wie ein Handy (ohne Person), bleibt erreichbar und gleicht jede Minute ab. Es dient auch als zweites Gerät im Emulator-Test der CI.
7. **Hintergrund:** Android gleicht alle 15 Minuten über WorkManager ab (kürzester erlaubter Abstand), solange die App offen ist jede Minute und zwei Sekunden nach einer Änderung. Der Abgleich erzeugt keine Benachrichtigungen (Prämisse 26.11.: Wichtiges erscheint beim Öffnen der App).
8. **Der Familienschlüssel liegt nur in `sync.json` im App-Speicher**, nie in den Familiendaten, der Sicherung oder im geteilten Ordner.

## Abweichungen von ADR 0002/0003

| ADR 0002/0003 | Umsetzung | Grund |
| --- | --- | --- |
| Ed25519-Geräteschlüssel, jede Änderung signiert | keine Signatur pro Datensatz; wer den Familienschlüssel hat, darf schreiben | Ed25519 erst ab API 33. In einer Familie vertraut jedes gekoppelte Gerät dem anderen ohnehin; Signaturen schützen erst, wenn Geräte entfernt werden können (siehe Grenzen). |
| X25519, ChaCha20-Poly1305 | P-256, AES-256-GCM | Bordmittel ab API 26, AES hat Hardwarebeschleunigung |
| Änderungsprotokoll mit Versionsvektor, LWW pro Feld | Zustand mit Stempel pro Datensatz, LWW pro Datensatz | einfacher und robust bei verlorenen Nachrichten; gleichzeitige Änderungen am selben Eintrag auf zwei Handys verlieren die ältere Fassung ganz statt nur des Feldes. Für eine Familie selten, später auf Feldebene erweiterbar. |
| Mitgliedskette beim Handshake | gemeinsamer Familienschlüssel als psk | ohne Signaturen gibt es nichts zu verketten |

## Grenzen (bewusst offen)

- **Kein Entfernen eines Geräts, kein Schlüsselwechsel.** Ein verlorenes Handy kennt den Familienschlüssel weiter. Abhilfe später: neuer Schlüssel, an alle übrigen Geräte verteilt.
- **Zwei Handys im Hintergrund erreichen sich nicht direkt.** Dafür sind der geteilte Ordner und das Hausgerät da.
- **Android 16+ kann Zugriff aufs lokale Netz einschränken** (Berechtigung für Geräte im lokalen Netz). Ab dem Zielsystem, das es erzwingt, muss die App sie anfragen.
- **iOS ist nicht gebaut.** Dort fehlen QR-Scanner, Bonjour-Einträge in der Info.plist und Hintergrundabgleich.
- **Gleiche App-Version nötig:** Unterschiedliche Datenschema-Versionen lehnen den Abgleich mit Hinweis ab, statt Daten zu verlieren.

## Prüfung

- `tools/quality-check`: `gradle check` (Handshake inkl. falscher Schlüssel und Manipulation, Kopplung über echtes TCP inkl. Ablehnen, falscher und abgelaufener Code, fremde Familie, Ordnerabgleich nur mit Chiffretext, Konvergenz dreier Geräte, Pfad-Ausbruch bei Fotonamen).
- CI-Emulator (Android 8 und 16): `app/scripts/emulator-pairing-e2e.sh` koppelt die App mit dem Hausgerät, vergleicht die Prüfzahlen beider Seiten und gleicht in beide Richtungen ab.
- Echte Geräte: Testschritte in PR #29.
