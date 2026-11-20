# ADR 0002: Local-first mit E2E-Verschlüsselung und blindem Relay

- Status: Akzeptiert (OK von Nils am 2026-11-20)
- Datum: 2026-11-20
- Ändert: [ADR 0001](0001-tech-stack.md), Abschnitt „Server“ (siehe „Folgen für ADR 0001“)

## Kontext

Nils (20.11.): „So wenig Aufwand wie möglich, die wenigsten haben einen Server zuhause. Wenn es gar nicht anders geht, optional mit Mini-PC, Raspi oder VPS.“ ADR 0001 sah dagegen einen Ktor-Server mit PostgreSQL vor, auf dem Regeln, Mail-Abruf und Eskalation laufen.

Jede Variante muss diese harten Anforderungen aus dem [Konzept](../KONZEPT.md) erfüllen:

| # | Anforderung | Was sie technisch verlangt |
| --- | --- | --- |
| A1 | Eskalation: Frist verpasst → Zuständige, +1 Tag → alle | Zeitauslöser, die auch feuern, wenn niemand die App offen hat, und die nicht feuern, wenn schon erledigt ist |
| A2 | Mail-Import + deterministische Regeln, Cloud-KI nur optional | Etwas holt Mails ab und lässt die Regel-Engine darüber laufen |
| A3 | Alle sehen die Stapel aller | Sync zwischen allen Geräten der Erwachsenen, auch wenn sie nie gleichzeitig online sind |
| A4 | HITL-Eingang | Offene Einträge erscheinen auf allen Geräten, Bestätigung von einem Gerät gilt für alle, ohne Konflikt |
| A5 | Wenig Aufwand für die Familie | Kein Server zu Hause als Pflicht |
| A6 | Abo und Selfhost | Gleiche Software, zwei Betriebsarten |

## Plattformgrenzen (geprüft an Primärquellen, Stand 20.11.2026)

Diese Fakten entscheiden den Vergleich. Wo nicht selbst nachgelesen, steht „prüfen“.

1. **iOS: Push geht nur über einen Server mit dem APNs-Schlüssel der App.** Wer eine Push-Nachricht an unsere iOS-App schicken will, braucht unseren APNs-Schlüssel. Ein Selfhost-Relay auf der Synology kann das nicht selbst. Bewährtes Muster: ntfy leitet bei Selfhost nur eine „poll request“ mit Nachrichten-ID über ntfy.sh an APNs weiter, das Handy holt den Inhalt dann vom eigenen Server ([ntfy-Doku, Config](https://github.com/binwiederhier/ntfy/blob/main/docs/config.md); [Known issues](https://docs.ntfy.sh/known-issues/)). Ohne diese Weiterleitung kann die Zustellung laut ntfy „Stunden dauern“.
2. **iOS: Code im Hintergrund läuft nur, wann das System will.** `BGAppRefreshTask` hat keinen garantierten Zeitpunkt; stille Pushes (`content-available`) werden gedrosselt und nach dem Wegwischen der App gar nicht zugestellt (Apple-Doku „Pushing background updates“, prüfen). Ein iPhone synchronisiert also zuverlässig nur, wenn die App offen ist oder eine sichtbare Push-Nachricht kommt.
3. **iOS: Lokale Notifications feuern ohne laufende App, aber höchstens 64 gleichzeitig geplant.** „There is a limit of 64 […] per app. This is a system limit and there is no way around it“ ([Apple Engineer, Forum 811171, Jan. 2026](https://developer.apple.com/forums/thread/811171)). Vor dem Anzeigen läuft kein eigener Code, die App kann also nicht prüfen, ob die Aufgabe inzwischen erledigt ist.
4. **iOS: Notification Service Extension (NSE)** läuft nur für Remote-Pushes mit `mutable-content`, etwa 30 s lang, und kann den Inhalt entschlüsseln. Damit ist E2E-verschlüsselter Push möglich (so machen es Signal und Delta Chat, prüfen). Ob die NSE dabei geplante lokale Notifications löschen darf, ist offen und gehört in den Spike.
5. **Android: Exakte Alarme sind ab Android 14 standardmäßig verweigert** (`SCHEDULE_EXACT_ALARM`). `USE_EXACT_ALARM` ist für „Calendar or alarm clock apps“ erlaubt und wird bei Installation gewährt, setzt aber die Play-Richtlinie voraus. Ausweichen: `setWindow()` mit mindestens 10 min Fenster, `WorkManager` periodisch ab 15 min ([Android 14, Exact alarms](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)). Für Fristen reicht ein 10-Minuten-Fenster. Anders als iOS kann Android beim Auslösen eigenen Code ausführen und vorher den Stand prüfen.
6. **Direktverbindung zwischen Handys** braucht beide Geräte gleichzeitig online und erreichbar. Über Netzgrenzen hinweg braucht auch „echtes P2P“ einen Vermittlungsdienst (Signalisierung, NAT-Durchbruch, Fallback-Relay). Beispiel iroh: setzt selbst Relay-Server ein; die Swift/Kotlin-Bindings waren 2025 pausiert ([iroh-Blog 12.02.2025](https://www.iroh.computer/blog/ffi-updates)) und sind heute wieder als „Official“ gelistet ([iroh languages](https://docs.iroh.computer/languages)), aber kein KMP.

**Kernbefund:** A1 und A3 scheitern ohne ein immer erreichbares Stück im Internet, weil iPhones im Hintergrund weder zuverlässig empfangen noch rechnen. Dieses Stück muss aber **nichts verstehen**: Es reicht ein Briefkasten für verschlüsselte Pakete plus ein Wecker, der zur richtigen Zeit eine Push-Nachricht auslöst.

## Varianten

| Variante | A1 Eskalation | A2 Mail | A3/A4 Sync | A5 Aufwand Familie | A6 Abo + Selfhost | Vertrauen/Datenschutz |
| --- | --- | --- | --- | --- | --- | --- |
| **V1 Reines P2P** (WLAN/Bluetooth, z. B. Nearby/Multipeer) | ✗ nur lokale Notifications, keine Absage bei „erledigt“ | ◐ Handy holt ab, iOS unzuverlässig | ✗ nur im selben Netz gleichzeitig | ✔ nichts | ◐ kein Abo-Mehrwert | ✔ |
| **V2 Cloud-Speicher als Transport** (iCloud/Google Drive) | ✗ kein Wecker, kein Push | ◐ wie V1 | ◐ iCloud nicht für Android; Drive-Ordner teilen braucht Google-Konto aller und OAuth-Prüfung von Google (prüfen) | ◐ Konto-Verknüpfung | ✗ Abhängigkeit von Big Tech | ◐ Daten bei Google/Apple, E2E möglich |
| **V3 Mail als Transport** (IMAP, Vorbild Delta Chat) | ✗ kein Wecker; Push nur über eigenen Notifier wie Delta Chat | ✔ Postfach ist ohnehin da | ◐ funktioniert, aber Ordner-, Spam- und Quotenprobleme je Anbieter | ◐ Postfach-Zugang einrichten | ◐ | ✔ E2E möglich |
| **V4 Klassischer Server** (ADR 0001: Ktor + PostgreSQL, Logik auf dem Server) | ✔ | ✔ IMAP IDLE | ✔ | ✗ Selfhost braucht Server; Abo braucht uns | ✔ | ✗ Server sieht alle Familiendaten; E2E unmöglich, weil Regeln dort laufen |
| **V5 Fertige Sync-Plattform** (siehe unten) | ◐ je nach Produkt | ◐ | ✔ | ◐ | ✗ meist Server-Komponente fremder Firma oder Lizenzkosten pro Gerät | ✗ meist kein E2E |
| **V6 Local-first + E2E + blindes Relay** (Empfehlung) | ✔ blinde Termin-Pushes + lokale Notifications als Netz | ✔ Handy holt ab; optional Hausgerät als Helfer | ✔ Briefkasten für verschlüsselte Änderungen | ✔ Abo: nichts; Selfhost: ein Container | ✔ gleiche Relay-Software | ✔ Relay sieht nur Metadaten (wer, wann, wie groß) |

### Fertige Sync-Lösungen mit KMP-Bezug (V5)

| Lösung | KMP | E2E | Ohne Server | Bewertung |
| --- | --- | --- | --- | --- |
| Ditto | eigene Kotlin- und Swift-SDKs, kommerziell | nein (Ditto-Cloud) | P2P-Mesh über BLE/WLAN ja | Stark im P2P, aber proprietär, Lizenz pro Gerät, Selfhost unklar. Widerspricht „Cloud nicht vertrauenswürdig“. |
| PowerSync | offizielles Kotlin-SDK | nein | nein (Postgres + PowerSync-Service) | Gute Technik, aber genau der Server, den wir vermeiden wollen. |
| Couchbase Lite (Kotbase) | Community-KMP | nein | P2P nur Enterprise | Sync Gateway nötig. |
| Realm Device Sync | – | – | – | Von MongoDB zum 30.09.2025 eingestellt. Warnendes Beispiel für Anbieterabhängigkeit. |
| Automerge | offizielle Bindings für JS, Rust, Swift; JVM nur über Community (prüfen) | selbst bauen | Sync-Server nötig (automerge-repo) | Mächtig für Texte. Für unser Modell aus Datensätzen mit einzelnen Feldern überdimensioniert, und auf zwei Plattformen zwei verschiedene native Bindings. |
| Matrix (Trixnity, KMP) | ja | ja (Megolm) | nein (Homeserver) | E2E und Selfhost gelöst, aber schwere Protokolle und Geräteverifizierung als UX-Last. Fallback-Option, falls eigenes Relay scheitert. |
| cr-sqlite | Native Extension | nein | Transport selbst | Elegant, aber Entwicklung ruht seit 2024 (prüfen). |

Keine fertige Lösung erfüllt gleichzeitig KMP, E2E, Selfhost und keine Lizenzkosten pro Gerät.

## Entscheidung

**V6: Local-first-Apps, die über ein blindes Relay synchronisieren. Das Relay kann bei uns (Abo) oder bei der Familie (Selfhost) laufen. Ein Hausgerät ist optional, nie Pflicht.**

### Bausteine

1. **Lokale Daten sind die Wahrheit.** Jedes Gerät hält den kompletten Familiengraphen in SQLite (SQLDelight). Die App funktioniert offline vollständig.
2. **Sync als Änderungsprotokoll mit einfachem CRDT, in reinem Kotlin.** Jede Änderung ist eine Operation (Datensatz, Feld, Wert, Hybrid Logical Clock, Gerät). Konfliktregel: Last-Writer-Wins pro Feld, Mengen als Add-Wins-Set, Löschen als Grabstein. Das reicht für unser Modell (Termine, Aufgaben, Zuständigkeit, Status), weil wir keine gemeinsam bearbeiteten Fließtexte haben. Fachlich heikle Fälle bekommen eigene Regeln statt Zufall nach Uhrzeit, z. B. „erledigt“ gewinnt gegen „Frist verschoben“. Eigenbau, weil ca. 300–500 Zeilen deterministisch testbar sind, während Automerge zwei native Bindings bedeutet.
3. **E2E-Verschlüsselung.** Ein symmetrischer Familienschlüssel (XChaCha20-Poly1305 über libsodium) verschlüsselt jede Operation. Neues Gerät per QR-Code oder Einladungslink (Schlüssel im URL-Fragment, geht nie an den Server). Gerät entfernen → Schlüssel rotieren. MLS (RFC 9420) ist für 2–8 Geräte überdimensioniert, bleibt aber der Weg, falls Gruppen größer werden.
4. **Blindes Relay** (Ktor + SQLite, ein Container, geschätzt unter 100 MB RAM). Es kann nur drei Dinge:
   - **Briefkasten:** verschlüsselte Operationen pro Familie annehmen und ausliefern, bis alle Geräte sie bestätigt haben.
   - **Blinder Wecker:** Ein Handy hinterlegt „stelle zum Zeitpunkt T an Gerätegruppe G diesen verschlüsselten Inhalt zu, außer jemand sagt vorher Absage-Token K“. Das Relay kennt T, G und K, aber nicht den Inhalt. Damit wird die Eskalation auch ohne laufende App zuverlässig, inklusive Absage bei „erledigt“.
   - **Push auslösen:** sichtbare Push-Nachricht mit verschlüsseltem Inhalt, auf iOS entschlüsselt die NSE ihn.
5. **Push-Gateway von uns** (wie bei ntfy). Ein Selfhost-Relay leitet nur „Gerät X hat Post“ an unser Gateway weiter, das die APNs/FCM-Zustellung macht. Das Gateway sieht ein Push-Token und eine Uhrzeit, nie Inhalte. Für Android ohne Google-Dienste später UnifiedPush.
6. **Regeln und Mail-Abruf laufen auf den Handys.** Die Familie richtet ein Familienpostfach ein (eigene Adresse beim bestehenden Mailanbieter), Weiterleitungen landen dort. Jedes Gerät darf abholen: Android zuverlässig per WorkManager (15 min), iOS beim Öffnen und opportunistisch. Doppelte Verarbeitung ist harmlos, weil die Regel-Engine deterministisch ist und Einträge eine aus der Message-ID abgeleitete ID bekommen. Das Postfach ist der Puffer, den die Familie schon hat.
7. **Optionales Hausgerät (Raspi, Mini-PC, Synology, VPS)** ist kein Server, sondern **ein weiteres Familienmitglied ohne Bildschirm**: dieselbe KMP-Logik auf der JVM, mit Familienschlüssel. Es holt Mails per IMAP IDLE sofort ab, wendet Regeln an und kann zusätzlich das Relay hosten. Nur für Familien, die schnelleren Mail-Import oder volle Unabhängigkeit wollen.
8. **LAN-Direktsync** (mDNS/Bonjour) ist eine spätere Optimierung, keine Grundlage.

### Wie die Eskalation (A1) ohne Server mit Logik funktioniert

Fristen sind im Voraus bekannt. Wird eine Frist gesetzt oder geändert, macht das Gerät zwei Dinge:

1. **Netz 1, blinder Wecker:** zwei Termin-Pushes beim Relay hinterlegen (T = Frist an die Zuständige, T + 1 Tag an alle), je mit Absage-Token. Wer erledigt, schickt die Absage mit der Operation. Feuert zuverlässig, auch wenn alle Apps zu sind.
2. **Netz 2, lokal:** Jedes Gerät plant für die nächsten Fristen lokale Notifications (iOS: die 64 nächsten, neu geplant bei jedem Öffnen und Sync; Android: `setWindow` oder `USE_EXACT_ALARM`, prüft beim Auslösen den Stand). Greift, wenn das Relay oder das Netz ausfällt.

Damit niemand doppelt benachrichtigt wird, ersetzt ein angekommener Push die lokale Notification mit gleicher ID. Offene Restrisiken (für den Spike): Ein iPhone ohne Netz zeigt eine lokale Eskalation, obwohl die Aufgabe auf einem anderen Gerät schon erledigt ist. Der Text sagt deshalb neutral „Frist X ist abgelaufen, Stand prüfen“. Und ein Gerät, das eine neue Frist noch nie gesehen hat, plant keine lokale Notification, hier hilft nur Netz 1.

**Ohne jedes Relay (reines P2P) ist A1 nicht erfüllbar**: Lokale Notifications allein können nicht abgesagt werden, wenn auf einem anderen Gerät erledigt wurde, und neue Fristen erreichen das andere Handy nicht.

### Relay im Detail

Ein Ktor-Dienst mit SQLite als ein Docker-Image, identisch für Abo und Selfhost. Geschätzt ca. 1.500 Zeilen Kotlin.

| Baustein | Umsetzung | Was das Relay dabei sieht |
| --- | --- | --- |
| Geräte-Identität | Jedes Gerät hat ein Ed25519-Schlüsselpaar und signiert jede Anfrage (Zeitstempel + Nonce gegen Wiederholung). Neues Gerät wird von einem vorhandenen bestätigt (QR-Code). | Öffentliche Schlüssel, zufällige Familien-ID |
| Briefkasten | Verschlüsselte Operationen ablegen und ab Cursor abholen; WebSocket für Live-Sync bei offener App. Löschen, sobald alle Geräte bestätigt haben. Anhänge als verschlüsselte Blobs, adressiert über Hash, Größenlimit. Größen auf feste Stufen aufgefüllt. | Größe, Zeitpunkt, Absender-Gerät |
| Blinder Wecker | Eintrag: Zeitpunkt T, Empfänger-Geräte, Chiffrat, Hash des Absage-Codes. Absage mit dem Code löscht ihn. Zeitgeber prüft alle 30 s; mindestens einmal zustellen, Handy filtert Doppelte über feste ID. | T, Empfänger, dass abgesagt wurde |
| Push anstoßen | Meldet dem Gateway nur „Gerät mit Kennung X wecken“. | – |

**Push-Gateway (nur von uns betrieben):** hält APNs-Schlüssel und FCM-Zugang, ordnet Kennungen Push-Tokens zu, nimmt nur Anfragen registrierter Relays an, begrenzt die Rate. Muster wie ntfy.

**Betrieb:** Abo auf einem EU-VPS, Sicherung per Litestream (SQLite nach S3); da alles verschlüsselt ist, darf die Sicherung extern liegen. Selfhost per `docker compose up`. Der eigentliche Selfhost-Aufwand ist die Erreichbarkeit per HTTPS von unterwegs: DDNS + Let's Encrypt + Portfreigabe (bei Synology in DSM enthalten) oder Tailscale/Cloudflare Tunnel. Für die meisten Familien bleibt das Abo Standard.

**Prüfung:** Das Protokoll (Datentypen, Signaturen, Verschlüsselung) ist ein gemeinsames Kotlin-Modul für App und Relay. Tests: Gerät eine Woche offline, Operationen doppelt und in falscher Reihenfolge, Relay-Neustart mit fälligen Weckern (kein Wecker geht verloren). Ein Auszug der Relay-Datenbank zeigt nur Chiffrate.

### Betriebsarten

| Betriebsart | Familie braucht | Wir betreiben | Daten bei uns |
| --- | --- | --- | --- |
| **Abo** (Standard) | Handys + Familienpostfach | Relay + Push-Gateway (EU) | nur verschlüsselt + Metadaten |
| **Selfhost leicht** | Relay-Container auf Synology/Raspi/VPS | Push-Gateway | Push-Token + Uhrzeit |
| **Selfhost voll** | zusätzlich Hausgerät als Mitglied | Push-Gateway | Push-Token + Uhrzeit |

Ganz ohne uns geht iOS-Push technisch nicht (Plattformgrenze 1). Die Familie verliert dann nur die zuverlässige Eskalation auf iPhones, alles andere läuft weiter.

## Begründung

1. Erfüllt A1–A6 als einzige Variante, ohne dass eine Familie einen Server braucht.
2. Löst die offene Konzeptfrage „E2E trotz serverseitiger Regeln?“: Regeln laufen nicht mehr auf einem Server, sondern auf Geräten der Familie. Damit passt es zu „Cloud nicht vertrauenswürdig“, auch für unsere eigene Cloud.
3. Die Regel-Engine aus dem Spike ist reines Kotlin und läuft unverändert auf Handy und Hausgerät.
4. Das Relay ist so dumm, dass es kaum Pflege braucht, kaum Angriffsfläche bietet und als Abo günstig zu betreiben ist. Ein Datenleck beim Relay verrät keine Inhalte (DSGVO Art. 9: Gesundheitsdaten der Kinder).
5. Kein Anbieter-Lock-in (Lehre aus Realm Sync).

## Folgen

### Für ADR 0001 (umgesetzt am 2026-11-20)

- **Server:** „Ktor + PostgreSQL mit Regeln, IMAP-Abruf und Eskalation“ wird zu „Ktor-Relay mit SQLite, nur Briefkasten, blinder Wecker und Push“. PostgreSQL entfällt.
- **Mail-Eingang:** IMAP-Abruf wandert vom Server auf die Geräte (und optional auf das Hausgerät).
- **Geteilte Module:** `data` bekommt `sync` (Änderungsprotokoll, CRDT, HLC) und `crypto`. `domain` bleibt unverändert, Eskalation als reine Zeitberechnung bleibt dort.
- **Docker-Compose-Paket** bleibt, enthält aber nur Relay, optional Hausgerät-Dienst. Push-Gateway ist ein eigener, nur von uns betriebener Dienst.
- **Kontext „Mindestgerät mit Server keines“** gilt nicht mehr: Regeln laufen auf dem Handy. Laufzeit und Akkuverbrauch auf einem älteren iPhone im Sync-Spike messen.

### Neue Risiken und Aufwand

- **Schlüsselverlust:** Verlieren alle Geräte den Familienschlüssel, sind die Daten weg. Gegenmaßnahme: Wiederherstellungsschlüssel zum Ausdrucken beim Einrichten, optional im Passwortmanager.
- **Schema-Migrationen** müssen mit alten App-Versionen auf anderen Geräten zusammenleben. Operationen tragen eine Schemaversion, unbekannte Felder werden aufbewahrt und nicht verworfen.
- **CRDT-Eigenbau** ist eine eigene Fehlerquelle. Gegenmaßnahme: eigenschaftsbasierte Tests (gleiche Operationen in beliebiger Reihenfolge → gleicher Zustand auf allen Geräten).
- **Datenwachstum:** Das Protokoll wird regelmäßig zu einem Schnappschuss verdichtet. Anhänge (Fotos, PDFs) gehen verschlüsselt als eigene Blobs übers Relay.
- **App-Store:** Push-Gateway und Relay-Betrieb sind Teil des Abos; Selfhost-Nutzer brauchen trotzdem unser Gateway, das muss in den Bedingungen stehen.

### Nächster Schritt: Spike „Sync und Eskalation“

Wegwerfcode unter `spikes/sync/`, beantwortet nur die Fragen, die dieses ADR offen lässt.

Abnahmekriterien:

1. Zwei Android-Geräte und ein iPhone, nie gleichzeitig online, erreichen nach 1.000 zufälligen Operationen denselben Zustand (Test mit festem Zufalls-Seed).
2. Eskalation per blindem Wecker: 20 Fristen, App auf dem iPhone weggewischt. Alle 20 Pushes kommen innerhalb von 5 Minuten an, keine bei vorher erledigten Aufgaben.
3. Klären: Darf die iOS-NSE geplante lokale Notifications löschen? Wie oft holt `BGAppRefreshTask` in einer Woche Alltag wirklich ab (Messung)?
