# Spike: Gerätesync ohne Server (WLAN oder Tailscale)

Wegwerfcode (Reifestufe Spike). Beantwortet die Fragen aus [ADR 0003](../../docs/entscheidungen/0003-p2p-sync-im-selben-netz.md): Konvergieren Familiengeräte, die sich nur paarweise und nie alle gleichzeitig treffen, wenn sie sich direkt verbinden? Bleibt das sicher, wenn Geräte fremde Änderungen weiterreichen? Wie sieht Pairing ohne Konto aus?

HLC, LWW-Register, Ed25519 und ChaCha20-Poly1305 sind aus `spikes/sync` (PR #3) übernommen. Neu ist alles ab dem Transport.

## Aufbau

| Paket | Inhalt |
| --- | --- |
| `crypto` | Ed25519-Geräteschlüssel, Familienschlüssel (ChaCha20-Poly1305 mit Padding), HKDF, X25519, Frame-Verschlüsselung mit Zähler-Nonce. Nur JDK 21. |
| `core` | HLC + LWW (unverändert), `Change` (verschlüsselt, vom Ursprungsgerät signiert, lückenlose Nummer pro Gerät), `ChangeLog` mit Versionsvektor, `Membership` (unterschriebene Aufnahmen, Kette bis zum Gründer), `FamilyPeer` |
| `net` | `FrameChannel` (In-Memory für Simulation, TCP für LAN/Tailscale), `SecureChannel` (Handshake), `SyncSession` (Gossip), `PeerServer`/`PeerClient` |
| `pairing` | QR-Einladung, Pairing mit 6-stelligem Code und Bestätigung auf beiden Seiten |
| `mailbox` | `FolderMailbox`: geteilter Ordner als Briefkasten-Transport, `BlobStore` als Schnittstelle für Drive/WebDAV |
| `cli` | Kommandozeile zum Ausprobieren auf echten Rechnern |

## Ablauf einer Sitzung

1. Flüchtiger X25519-Austausch, daraus zwei Richtungsschlüssel (Familien-ID steckt in der Ableitung).
2. Verschlüsselt: Jede Seite signiert das Handshake-Protokoll mit ihrem Geräteschlüssel und schickt ihre Mitgliederliste. Nichtmitglieder werden abgewiesen.
3. Versionsvektoren austauschen, dann schickt jede Seite alles, was der anderen fehlt, in Blöcken zu 200, auch fremde Änderungen. Jede Änderung wird sofort angewendet, ein Abbruch verliert also nichts.

## Tests (`gradle test`, 16 Tests)

| Kriterium | Test |
| --- | --- |
| 1 Drei Peers, 1.000 Änderungen, A und C nie direkt, abgebrochene Sitzungen, 5 Seeds → gleicher Endstand | `GossipConvergenceTest` |
| 2 Fremdes Gerät, manipulierter Frame, verändertes/gefälschtes Weiterreichen, gefälschte Mitgliedskette | `SecurityTest`, `TcpSyncTest` |
| 3 QR-Pairing mit gleichem Code auf beiden Seiten, Ablehnung, falsches Geheimnis, Einmaligkeit, Ablauf | `PairingTest` |
| Cloud-Ordner gemischt mit LAN, kein Klartext im Ordner, Konfliktkopien | `MailboxTest` |

Gegenprobe gemacht: Gibt ein Gerät nur eigene Änderungen weiter, schlägt `GossipConvergenceTest` fehl.

## Auf zwei Rechnern ausprobieren

```
gradle installDist
P=build/install/p2p-sync/bin/p2p-sync

# Rechner 1 (z. B. Surface im Tailnet)
$P init ~/fa-nils Nils
$P set ~/fa-nils termin1 title "Elternabend"
$P serve ~/fa-nils 7420 192.168.178.20:7420 surface.<tailnet>.ts.net:7420
#   druckt einen Pairing-Code, bestätigt das Pairing per Tastatur, bleibt als Dauer-Peer laufen

# Rechner 2
$P join ~/fa-anna Anna 'familyapp-pair:…'    # gleicher 6-stelliger Code auf beiden Seiten → y
$P sync ~/fa-anna                            # alle bekannten Adressen der Reihe nach
$P set ~/fa-anna einkauf milch "2 l"
$P sync ~/fa-anna
$P show ~/fa-anna

# Ohne Netz zueinander: geteilter Ordner (z. B. Synology-Drive- oder Nextcloud-Ordner)
$P mailbox ~/fa-anna /pfad/zum/geteilten/ordner
```

Der Zustand liegt in `<dir>/peer.json`, **inklusive privatem Schlüssel und Familienschlüssel**: nur Testdaten, Ordner außerhalb des Repos anlegen. Während `serve` läuft, das gleiche Verzeichnis nicht parallel mit `set` ändern (zwei Prozesse, eine Datei).

## Bewusst nicht im Spike

- mDNS (kommt in der App über Android NSD / iOS Bonjour; Multicast lässt sich im Container nicht sinnvoll testen)
- Gerät entfernen, Schlüssel rotieren, Verdichtung des Protokolls, Anhänge, Schema-Migrationen
- Google-Drive- oder WebDAV-Anbindung (nur `BlobStore`-Schnittstelle plus lokaler Ordner)
- Geprüfter Handshake (Noise XX) und libsodium statt Eigenbau
