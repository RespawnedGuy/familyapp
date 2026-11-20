# ADR 0003: Erster Schritt ohne Server – Gerätesync im selben Netz (WLAN oder Tailscale)

- Status: Vorgeschlagen
- Datum: 2026-11-20
- Ergänzt: [ADR 0002](0002-local-first-sync.md) (verschiebt das Relay, ändert nichts an Datenmodell, CRDT und Verschlüsselung)

## Kontext

Nils (20.11.): „Wir bauen erstmal die ‚nur im selben WLAN‘-Option und haben ja Tailscale als VPN. So fällt der Server weg, die Geräte sind technisch im gleichen Netzwerk und nix ist nach außen offen.“ Der Aufbau des Test-VPS ist gestoppt. Später kam die Frage dazu, ob geteilte Cloud-Ordner (Google Drive, iCloud) eine Alternative ohne Tailscale sind.

ADR 0002 hatte reines P2P (Variante V1) verworfen, weil die Eskalation (A1) und der Abgleich ohne gleichzeitige Erreichbarkeit (A3) daran scheitern. Diese Befunde bleiben richtig. Neu ist die Abwägung: Für den ersten Schritt mit der eigenen Familie ist ein Betrieb ohne jeden Server wichtiger als A1 in voller Stärke. Das Relay bleibt der Weg für später.

## Entscheidung

1. **Die Geräte synchronisieren direkt miteinander.** Im WLAN zu Hause über die LAN-Adresse, unterwegs über die Tailscale-Adresse. Für den Code ist beides eine TCP-Verbindung zu einer IP. Kein Gerät öffnet einen Port ins Internet.
2. **Protokoll, CRDT und Krypto bleiben wie in ADR 0002** (HLC, Last-Writer-Wins pro Feld, Grabsteine, Ed25519-Geräteschlüssel, Familienschlüssel mit ChaCha20-Poly1305). Ersetzt wird nur der Transport.
3. **Abgleich per Gossip mit Versionsvektor.** Jedes Gerät hält das vollständige Änderungsprotokoll aller Geräte und gibt beim Treffen alles weiter, was dem anderen fehlt, auch fremde Änderungen. Dadurch reicht es, wenn sich Geräte paarweise und zu verschiedenen Zeiten treffen: A gibt an B, B später an C, A und C müssen sich nie sehen.
4. **Jede Änderung ist vom Ursprungsgerät signiert.** Ein weiterreichendes Gerät kann sie nicht verändern oder in fremdem Namen erzeugen.
5. **Verbindung nur zwischen Familiengeräten:** Handshake mit flüchtigem X25519-Schlüssel (Forward Secrecy), danach beweisen beide ihren Geräteschlüssel und zeigen ihre Mitgliedskette. Fremde Geräte werden vor dem ersten Datensatz abgewiesen. Über Tailscale ist das doppelt verschlüsselt. Das ist gewollt, weil im WLAN kein WireGuard dazwischen liegt und derselbe Code beide Wege bedient.
6. **Pairing per QR-Code mit Bestätigung auf beiden Geräten, kein Konto.** Details unten.
7. **Transport hinter einer Schnittstelle.** Das Gerät kennt nur „was fehlt dir?“ und „nimm diese Änderung“. Direktverbindung und ein geteilter Ordner sind zwei Transporte darüber. Das Relay aus ADR 0002 wird später ein dritter, ohne Umbau am Kern.
8. **Empfohlen, nicht Pflicht: ein Dauer-Peer im Tailnet.** Ein Gerät ohne Bildschirm (Synology, Surface, Raspi), auf dem dieselbe Logik als weiteres Familienmitglied läuft. Es ist kein Server: Es kennt den Familienschlüssel wie jedes Handy, ist nur im Tailnet erreichbar und sammelt und verteilt Änderungen. Das ist das „Hausgerät“ aus ADR 0002, Baustein 7.

## Geräte finden

| Weg | Funktioniert | Grenze |
| --- | --- | --- |
| mDNS/DNS-SD (Android NSD, iOS Bonjour), Dienst `_familyapp._tcp` | im selben WLAN | Über Tailscale nicht, weil Tailscale kein Multicast weiterleitet (prüfen). iOS fragt ab iOS 14 nach „Lokales Netzwerk“ (`NSLocalNetworkUsageDescription`, `NSBonjourServices` in der Info.plist). Gäste- und Firmen-WLANs blocken Multicast oft. |
| Gemerkte Adressen pro Gerät | WLAN und Tailscale | Beim Pairing ausgetauscht und bei jedem Treffen aktualisiert: letzte LAN-IP, Tailscale-IP (100.x) und MagicDNS-Name. Ohne Tailscale unterwegs nicht erreichbar. |

Reihenfolge beim Abgleich: erst mDNS-Treffer, dann gemerkte Adressen, je 2–3 s Timeout. Der mDNS-Eintrag verrät nur die Protokollversion. Wer zu welcher Familie gehört, zeigt sich erst im verschlüsselten Handshake, damit ein fremdes Gerät im WLAN keine Familienkennung mitlesen kann.

## Pairing

1. Das einladende Gerät zeigt einen QR-Code: Familien-ID, eigene Geräte-ID (Hash des öffentlichen Schlüssels), ein einmaliges 256-Bit-Geheimnis, Adressen, Ablauf nach 10 Minuten.
2. Das neue Gerät verbindet sich. Die Sitzungsschlüssel hängen vom Geheimnis ab. Ohne den QR-Code öffnet sich nicht einmal der erste verschlüsselte Frame.
3. Beide signieren das Protokoll ihres Handshakes. Das neue Gerät prüft, dass der Schlüssel des Einladenden zur Geräte-ID im QR-Code passt.
4. Beide Bildschirme zeigen denselben 6-stelligen Code und den Namen des anderen Geräts. Beide Menschen bestätigen. Die Bestätigung auf dem einladenden Gerät schützt vor einem abfotografierten QR-Code, denn dort sieht man, welches Gerät beitreten will.
5. Erst dann unterschreibt das einladende Gerät die Aufnahme und übergibt den Familienschlüssel, die Mitgliederliste und die bekannten Adressen. Die Einladung ist danach verbraucht.

Die Mitgliederliste ist eine nur wachsende Menge unterschriebener Aufnahmen, deren Kette beim Gründer endet (die Familien-ID ist die Geräte-ID des Gründers). Jeder Handshake gleicht sie mit ab. So kennt C ein bei B gekoppeltes Gerät, bevor sich die beiden je gesehen haben.

## Was ohne Server nicht geht (ehrlich)

| Thema | Folge | Abmilderung |
| --- | --- | --- |
| **iPhones gleichen nur im Vordergrund ab.** Eine iOS-App kann im Hintergrund nicht als Gegenstelle lauschen. `BGAppRefreshTask` läuft selten und zu einem Zeitpunkt, den iOS wählt (ADR 0002, Grenze 2). | Zwei iPhones gleichen sich praktisch nur ab, wenn beide gleichzeitig offen sind. | Beim Öffnen sofort abgleichen. Ein Dauer-Peer löst das: Jedes iPhone trifft ihn beim Öffnen. |
| **Android gleicht periodisch ab.** WorkManager frühestens alle 15 Minuten. Ein dauerhaft lauschender Dienst (Foreground Service „dataSync“) ist ab Android 15 auf 6 Stunden pro Tag begrenzt (prüfen: Android-15-Verhaltensänderungen). | Ein Android-Handy ist für andere nur zeitweise erreichbar. | Wie oben: Dauer-Peer oder Abgleich beim Öffnen. |
| **Eskalation an andere (A1).** Ohne Server weckt niemand ein fremdes Handy: Push nach iOS geht nur mit unserem APNs-Schlüssel (ADR 0002, Grenze 1), auch ein Dauer-Peer kann das nicht. | „Frist verpasst“ erreicht die anderen nur, wenn ihr Gerät die Frist schon kennt. Ist eine Aufgabe auf einem Gerät erledigt, das sich noch nicht abgeglichen hat, kommt die Erinnerung trotzdem. | Jedes Gerät plant lokale Notifications für alle Fristen, die es kennt (iOS: die nächsten 64). Text neutral: „Frist X ist abgelaufen, Stand prüfen“. Eigene Fristen funktionieren voll. |
| **Unterwegs braucht jedes Mitglied Tailscale.** | Für Nils' Familie okay. Für andere Familien widerspricht das „so wenig Aufwand wie möglich“. Auf iOS ist immer nur ein VPN aktiv (prüfen), wer schon ein Firmen-VPN nutzt, hat ein Problem. Nutzerlimit im kostenlosen Tailscale-Plan prüfen. | Später das Relay aus ADR 0002 als dritter Transport. |
| **Datenwachstum.** Jedes Gerät hält das volle Protokoll aller Geräte. | Bei Familien mit wenigen Änderungen pro Tag über Jahre unkritisch (Spike: rund 0,5 KB pro Änderung, weil jede auf mindestens 256 Byte aufgefüllt und einzeln signiert wird; 1.000 Änderungen ≈ 0,5 MB). | Später verdichten, sobald alle bekannten Geräte eine Änderung haben (Versionsvektoren zeigen das). |

## Geteilter Cloud-Ordner als zweiter Transport

Nils' Frage: Geht es ohne Tailscale über Google Drive oder iCloud? Technisch ja, als Briefkasten. Jedes Gerät legt seine Änderungen als verschlüsselte, signierte Dateien ab und liest die der anderen. Der große Vorteil: Die Geräte müssen nicht gleichzeitig online sein, damit wäre das iPhone-Problem gelöst. Der Spike zeigt, dass das hinter derselben Schnittstelle funktioniert (`FolderMailbox`): Jede Datei wird genau einmal geschrieben und nie geändert, der Name enthält das schreibende Gerät, deshalb gibt es keine Sync-Konflikte und kein Sperren. Der Ordner sieht nur Chiffrate.

Die Hürde ist nicht die Technik, sondern der Zugriff aus einer App auf einen Ordner, den mehrere Konten teilen:

| Anbieter | Bewertung |
| --- | --- |
| **iCloud** | Scheidet für gemischte Familien aus. CloudKit-Freigaben gibt es nur zwischen Apple-Konten, Android hat keinen sinnvollen Zugang. |
| **Google Drive** | Der unkritische Scope `drive.file` erlaubt nur Dateien, die die App selbst angelegt hat oder die der Nutzer ihr über den Google Picker gibt („files … that the user shares with an app while using the Google Picker API“). Ob ein von Konto A angelegter und mit Konto B geteilter Ordner samt später hinzukommender Dateien für die App auf B lesbar ist, sagt die Doku nicht, das muss ein Test mit zwei Konten klären. Der Picker ist für Android dokumentiert, für iOS nicht. Der volle Scope `drive` ist „restricted“, nur bestimmte App-Arten dürfen ihn nutzen, mit OAuth-Prüfung und Sicherheitsbewertung, falls Daten über Server laufen. Quellen: [Drive-Scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [Picker für Desktop und Mobile](https://developers.google.com/workspace/drive/picker/guides/overview-desktop) (beide abgerufen am 20.11.2026). |
| **Dropbox, OneDrive** | Ähnliche Fragen (App-Ordner lassen sich in der Regel nicht zwischen Konten teilen, prüfen). |
| **WebDAV (Synology, Nextcloud)** | Technisch am einfachsten. Braucht aber einen erreichbaren Speicher, also wieder Tailscale oder eine Portfreigabe. Für Nils' Familie mit Synology im Tailnet eine echte Option, gleichwertig zum Dauer-Peer. |

Kein Push, kein Wecker: Auch mit Cloud-Ordner bleibt die Eskalation an andere so begrenzt wie oben. Abgleich nur beim Öffnen und periodisch.

**Bewertung:** Der Cloud-Ordner ist eine gute Ergänzung für unterwegs ohne Tailscale, sobald der Zugriff geklärt ist. Für den ersten Schritt reicht Direktverbindung plus Dauer-Peer. Vor einer Entscheidung für Google Drive steht ein kleiner Test mit zwei Google-Konten (Android und iOS), ob `drive.file` mit geteiltem Ordner trägt.

## Folgen

- ADR 0002 bleibt die Zielarchitektur, das Relay wird verschoben, nicht gestrichen. Datenmodell, Protokoll und Krypto sind dieselben, das Relay wird ein weiterer Transport.
- Der Test-VPS bleibt pausiert.
- Neu im späteren `data/sync`-Modul: Versionsvektor, Gossip-Sitzung, signierte Änderungen, Mitgliederkette, Transport-Schnittstelle.
- In der App statt Eigenbau: eine geprüfte Handshake-Implementierung (Noise XX) und libsodium (XChaCha20-Poly1305). Der Spike nutzt nur JDK-Bausteine und einen selbst gebauten SIGMA-artigen Handshake, das ist für ein Produkt zu riskant.
- Nicht im Spike und offen: Gerät entfernen und Schlüssel rotieren, Verdichtung, Schema-Migrationen, Anhänge, mDNS auf echten Geräten, Akku- und Laufzeitmessung auf einem älteren iPhone.

## Prüfung

Spike `spikes/p2p-sync`, 16 Tests, alle grün:

1. Drei Peers, 1.000 zufällige Änderungen, Uhren um mehrere Minuten verstellt, zufällige Paar-Treffen, A und C treffen sich nie direkt, ein Viertel der Treffen bricht mitten im Transfer ab. Mit fünf festen Seeds gleicher Endstand auf allen drei und auf einem frischen Gerät, das alles in umgekehrter Reihenfolge bekommt. Gegenprobe: Ohne Weiterreichen fremder Änderungen schlägt der Test fehl.
2. Über echte TCP-Verbindungen: Sync über einen Dauer-Peer, fremdes Gerät abgewiesen. In-Memory: manipulierter Frame bricht ab ohne Schaden, verändertes oder gefälschtes Weiterreichen wird erkannt, Mitgliedskette mit Selbst-Aufnahme abgelehnt.
3. Pairing über TCP: gleicher 6-stelliger Code auf beiden Seiten, danach Abgleich, auch mit einem dritten Gerät, das die Aufnahme nie direkt gesehen hat. Falsches Geheimnis, falscher Einladender, Ablehnung auf einer Seite, zweite Nutzung und abgelaufene Einladung schlagen fehl.
4. Cloud-Ordner gemischt mit LAN gleicher Endstand, kein Klartext im Ordner, Konfliktkopien und doppeltes Veröffentlichen schaden nicht.

Zusätzlich lässt sich der Spike per Kommandozeile auf zwei echten Rechnern im WLAN oder im Tailnet ausprobieren (siehe README).
