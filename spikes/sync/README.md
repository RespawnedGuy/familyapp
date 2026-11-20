# Spike: Sync und blindes Relay

Wegwerfcode (Reifestufe Spike). Beantwortet die offenen Fragen aus [ADR 0002](../../docs/entscheidungen/0002-local-first-sync.md): Konvergieren Geräte, die nie gleichzeitig online sind, über ein Relay, das nur Chiffrate sieht? Feuert der blinde Wecker zuverlässig und lässt er sich absagen?

## Aufbau

| Paket | Inhalt |
| --- | --- |
| `protocol` | Datentypen, Ed25519-Signatur pro Anfrage, ChaCha20-Poly1305 mit Größen-Padding (alles JDK 21, keine Krypto-Bibliothek) |
| `client` | Hybrid Logical Clock, Operation, Last-Writer-Wins-Register pro Feld, simuliertes Gerät, signierter Relay-Client |
| `relay` | Ktor-Endpunkte (Briefkasten, Wecker, WebSocket), SQLite-Speicher, Zeitgeber, Push nur als Log |

Im Spike sind die drei Teile Pakete in einem Gradle-Projekt. Im Produkt werden sie getrennte KMP-Module (`protocol` gemeinsam für App und Relay).

## API

Alle Anfragen unter `/v1/families/{familyId}` außer `devices` sind signiert (Header `X-Device`, `X-Timestamp`, `X-Nonce`, `X-Signature` über Methode, Pfad, Zeit, Nonce, SHA-256 des Bodys). Abgelehnt werden falsche Signatur, Uhrzeit mehr als 5 min daneben und wiederholte Nonce.

| Methode | Pfad | Zweck |
| --- | --- | --- |
| POST | `/v1/families` | Familie mit erstem Gerät anlegen |
| POST | `/v1/families/{id}/devices` | Gerät aufnehmen, signiert von einem vorhandenen Gerät |
| POST / GET | `/v1/families/{id}/envelopes[?after=seq]` | verschlüsselte Änderungen ablegen / ab Cursor holen |
| POST | `/v1/families/{id}/ack` | Empfang bestätigen; was alle haben, wird gelöscht |
| POST | `/v1/families/{id}/timers` | blinden Wecker hinterlegen |
| POST | `/v1/families/{id}/timers/{timerId}/cancel` | Wecker mit Absage-Code löschen |
| WS | `/v1/families/{id}/live` | Live-Hinweis auf neue Änderungen bei offener App |
| GET | `/health` | Healthcheck |

## Ergebnis der Abnahmekriterien

| # | Kriterium | Test |
| --- | --- | --- |
| 1 | 3 Geräte, nie gleichzeitig online, 1.000 zufällige Änderungen (Seed 2026) → gleicher Zustand, Briefkasten danach leer | `RelaySyncTest` |
| 2 | 20 Wecker, 5 abgesagt, Relay-Neustart dazwischen, erster Push-Versuch scheitert → genau 15 feuern an je 3 Geräte | `BlindTimerTest` |
| 3 | Relay-Datenbank enthält keinen Klartext (mit Gegenprobe, dass der Scan Klartext findet) | `PlaintextScanTest` |

Dazu: CRDT konvergiert bei beliebiger Reihenfolge und Duplikaten (`CrdtConvergenceTest`), Manipulation, Replay, falsche Uhrzeit, fremdes Gerät, gefälschte Aufnahme und falscher Familienschlüssel werden abgelehnt (`RelaySecurityTest`).

## Befehle

```
gradle test          # alle Tests
gradle build
gradle run           # Relay lokal auf Port 8080, DB relay.db
docker compose up -d --build   # Relay im Container (vorher .env aus .env.example anlegen)
```

Umgebungsvariablen: `RELAY_PORT` (8080), `RELAY_DB_PATH` (relay.db, im Container /data/relay.db), `RELAY_TIMER_INTERVAL_SECONDS` (30), in Compose zusätzlich `RELAY_PUBLIC_PORT`.

## Bewusst nicht im Spike

- Push-Gateway (APNs/FCM): Push wird nur geloggt.
- HTTPS: im Test-Deployment per Reverse-Proxy (z. B. Caddy) davor.
- Fachliche Konfliktregeln („erledigt“ gewinnt gegen „Frist verschoben“), Schlüsselrotation, Anhänge, Verdichtung zu Schnappschüssen, Schema-Migrationen.
- Rate-Limits und Größenquoten pro Familie.
