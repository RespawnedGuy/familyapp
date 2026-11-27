# Bedrohungsmodell: Teilen, Kopplung und Abgleich

- Stand: 26.11.2026, Code von `main` nach PR #29 und #30 plus den Härtungen aus diesem PR
- Plan-Nummer: P1-8 aus der [ISO-25010-Bewertung](iso-25010-bewertung.md)
- Bezug: [ADR 0003](../entscheidungen/0003-p2p-sync-im-selben-netz.md) (kein Server), [ADR 0006](../entscheidungen/0006-kopplung-und-abgleich-umsetzung.md) (Umsetzung von Kopplung und Abgleich)
- Methode: Datenflussdiagramm mit Vertrauensgrenzen, dann STRIDE je Element (Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege). Jede Bedrohung hat entweder eine Maßnahme mit Test oder steht unter „Offen“ mit Begründung.

## 1. Was geschützt wird

| Schutzgut | Warum es zählt |
| --- | --- |
| Familiendaten: Einträge, Fotos von Briefen, Namen der Kinder, Fristen | Privat, teils über Kinder; ein Leck ist nicht rückholbar |
| Familienschlüssel (`sync.json`) | Wer ihn hat, liest und schreibt alles, auch über den geteilten Ordner |
| Wiederherstellungscode | Öffnet die verschlüsselte Sicherung |
| Verlässlichkeit der Daten | Ein untergeschobener oder gelöschter Termin ist ein verpasster Termin |
| Verfügbarkeit von Erfassen und Abgleich | „Schnell rein“ muss immer gehen, Fristen müssen auf allen Geräten ankommen |

## 2. Wer angreift

| Kürzel | Angreifer | Realistisch, weil |
| --- | --- | --- |
| A1 | Fremde App auf demselben Handy | Das Teilen-Ziel ist für alle Apps offen (gewollt) |
| A2 | Fremder im selben Netz | Gäste-WLAN, Café, Hotspot, Nachbar mit WLAN-Passwort; der Abgleich lauscht auf Port 47420 |
| A3 | Ordnerdienst oder Mitnutzer des geteilten Ordners | Google, Synology-Admin, jemand mit Freigabe auf den Ordner |
| A4 | Jemand mit Blick auf den Bildschirm | QR-Code beim Koppeln, Screenshot, Bildschirmaufnahme |
| A5 | Finder oder Dieb eines Handys | Handys gehen verloren |
| A6 | Ehemaliges Familienmitglied | Zielgruppe sind auch Getrennterziehende: das Gerät war rechtmäßig gekoppelt |
| A7 | Anderes Konto auf dem Rechner des Hausgeräts | Surface, NAS oder Mini-PC mit mehreren Nutzern |

Nicht betrachtet: wer Root/Administrator auf einem Familiengerät hat oder Schadsoftware mit diesen Rechten (dagegen hilft keine App), Angriffe auf Tailscale selbst, die Update-Prüfung (eigene Prüfungen in ADR 0005), iOS (noch nicht gebaut), Lieferkette der Bibliotheken.

## 3. Datenfluss und Vertrauensgrenzen

```mermaid
flowchart LR
  subgraph Handy["Handy (Android-Sandbox)"]
    Share["Teilen-Ziel<br/>MainActivity ACTION_SEND"]
    App["FamilyApp<br/>Familiendaten, Fotos"]
    Key[("sync.json<br/>Familienschlüssel")]
    Server["Abgleich-Server<br/>TCP 47420"]
  end
  OtherApp["Andere App<br/>(A1)"] -- "TB1: Intent, content://" --> Share
  App -- "TB6: HTTPS, nur Lesen" --> Insta["www.instagram.com<br/>öffentliche Beitragsseiten"]
  App -. "TB6: HTTPS, nur mit Schlüssel" .-> Claude["api.anthropic.com<br/>(optional)"]
  Share --> App
  Screen["QR-Code / Code als Text"] -- "TB4: optisch (A4)" --> NewPhone["Neues Gerät"]
  NewPhone -- "TB2: WLAN/Tailnet (A2)" --> Server
  Peer["Anderes Familiengerät<br/>oder Hausgerät"] -- "TB2: WLAN/Tailnet (A2)" --> Server
  App -- "TB3: Dateien, nur Chiffretext (A3)" --> Folder[("Geteilter Ordner<br/>Syncthing, Drive, Synology")]
  Folder --> Peer
  subgraph Rechner["Rechner des Hausgeräts"]
    House["family-peer"]
    HouseFiles[("Verzeichnis mit<br/>Daten und Schlüssel")]
  end
  House --- HouseFiles
  OtherUser["Anderes Konto (A7)"] -. "TB5: Dateirechte" .-> HouseFiles
  House -- "TB2" --> Server
```

| Grenze | Was sie trennt | Was darüber geht |
| --- | --- | --- |
| TB1 | App-Sandbox ↔ andere Apps | Text und Bilder über „Teilen“ |
| TB2 | Gerät ↔ Netz | Handshake (Klartext: Zufall, flüchtiger Schlüssel), danach nur AES-GCM-Rahmen; DNS-SD-Eintrag `_familyapp._tcp` |
| TB3 | Gerät ↔ Ordnerdienst | `familyapp-abgleich/<Geräte-ID>.fas`, `familyapp-abgleich-fotos/*.fap`, Sicherung `FAB1` |
| TB4 | Mensch ↔ Mensch | QR-Code mit Einmalgeheimnis, sechsstellige Prüfzahl |
| TB5 | Prozess ↔ andere Konten des Rechners | Dateien des Hausgeräts |
| TB6 | Gerät ↔ fremde Dienste im Internet | Abruf öffentlicher Instagram-Seiten (ADR 0007); optional Caption an Claude |

## 4. Bedrohungen und Maßnahmen

Status: ✓ abgesichert und geprüft · ◐ abgesichert mit Restrisiko · ✗ offen (siehe Abschnitt 5). **Neu** heißt: mit diesem PR eingebaut.

### Teilen (TB1, A1)

| ID | STRIDE | Bedrohung | Maßnahme | Beleg | Status |
| --- | --- | --- | --- | --- | --- |
| TE-1 | I, E | Eine fremde App schickt einen `file://`-Link oder einen Link auf den eigenen FileProvider der FamilyApp. Die App würde dann ihre eigenen Dateien (Familiendaten, `sync.json` mit dem Familienschlüssel) als „Foto“ in einen Eintrag kopieren, und der Abgleich verteilt sie (Confused Deputy) | Nur `content://`; Anbieter der eigenen App werden abgelehnt, erkannt am Namen und über den PackageManager, bei Fehler gilt der Anbieter als eigener. **Neu:** auch andere eigene Anbieter, nicht nur `.files` | `SharedInputTest.onlyContentFromAnotherAppIsOpened`; Emulator: „file://-Link …“, „Link auf den eigenen FileProvider“ | ✓ |
| TE-2 | D | Eine riesige Datei füllt Speicher und Sicherung | Kopie bricht nach 20 MB ab, egal welche Größe der Absender angibt | `SharedInputTest.copyStopsRightAfterTheLimit…`, `exactlyTheLimitIsStillAccepted`; Emulator: „21-MB-Bild abgelehnt“ | ✓ |
| TE-3 | T, D | Etwas, das kein Bild ist (HTML, APK), landet als Foto im Eintrag; ein Bild mit absurd vielen Bildpunkten bringt die Anzeige zum Absturz | **Neu:** Typ vom Anbieter (nicht vom Absender) muss `image/*` sein, danach muss der Bilddecoder aus den kopierten Bytes eine Größe lesen, höchstens 100 Megapixel | `SharedInputTest.onlyImagesAreTakenAsPhoto`, `decodedSizeDecidesNotTheClaimedType`; Emulator: „HTML-Datei abgelehnt“, „HTML mit Bildtyp abgelehnt“ | ✓ |
| TE-4 | D | Ein geteilter Text mit vielen MB bläht Daten, Sicherung und jeden Abgleich auf | **Neu:** Text auf 20 000 Zeichen gekürzt | `SharedInputTest.sharedTextIsJoinedAndCut` | ✓ |
| TE-5 | S | Eine fremde App legt ungefragt Einträge an | Gewollt („schnell rein“). Der Eintrag liegt im Eingang mit Quelle „geteilt“ und Rückgängig, Person und Datum bestätigt ein Mensch | Gestaltungsdoc, HITL-Prinzip | ◐ akzeptiert |
| TE-6 | (Verlässlichkeit) | Ein abgelehntes Bild verschwindet still, die Person glaubt, der Brief sei gespeichert | **Neu:** Meldung „Gespeichert ohne Bild. …“ mit Grund, der Text bleibt erhalten | `AppModelTest.refusedSharedImage…` (2 Tests); Emulator prüft jede Meldung | ✓ |
| TE-7 | S, T | Eine fremde App schickt Text, der wie ein Rezept aussieht, oder einen Instagram-Link; er landet ungefragt in der Kochbibliothek statt im Eingang (Kochbibliothek, 26.11.2026) | Nur Regeln entscheiden, nichts geht ins Netz: Zutatenliste **und** Schritte, oder ein Link auf einen Instagram-Beitrag mit höchstens 40 Zeichen daneben. Mit Bild bleibt es immer ein Eintrag. Landet als „Vorschlag“ mit Rückgängig, ein Mensch bestätigt | `RecipeImportTest.schoolMailIsNotARecipe`, `ingredientsWithoutStepsStayACapture`, `instagramProfileLinkIsNoRecipe`; `KitchenModelTest.recipeTextWithPhotoStaysACapture` | ✓ |
| TE-9 | S, T, I | Ein geteilter Link bringt die App dazu, eine fremde Adresse abzurufen (SSRF über das Handy) oder fremde HTML-Seiten als Rezept zu übernehmen (Instagram-Abruf, ADR 0007) | **Neu:** Abgerufen wird nur `https://www.instagram.com` mit Pfad aus dem Shortcode (`/p/<code>/` oder `/p/<code>/embed/captioned/`, Code nur `[A-Za-z0-9_-]`, höchstens 64 Zeichen); andere Pfade schickt der Abruf gar nicht ab. Keine Cookies, keine Anmeldung | `InstagramPostTest.shortcodeOnlyFromInstagramPostLinks`, `pathsAreBuiltFromTheShortcodeOnly`; `HttpInstagramPagesTest.onlyThePathsInstagramLinkBuildsAreRequested`, `normalPageIsReadWithoutCookies` | ✓ |
| TE-10 | D, T | Instagram (oder wer sich dazwischenschaltet) liefert riesige, kaputte oder umgeleitete Seiten; der Parser hängt oder übernimmt Unsinn | **Neu:** nur Status 200 und `text/html`, Weiterleitungen werden nicht verfolgt, höchstens 3 MB, 10 s Zeitlimit; JSON per Zeichenscan statt Regex, Caption höchstens 5 000 Zeichen; Ergebnis bleibt Vorschlag mit Rückgängig; hat jemand den Vorschlag schon geändert, wird nichts überschrieben | `HttpInstagramPagesTest` (Größe, Umleitung, Typ, Status), `InstagramPostTest.overlongCaptionIsRefused`, `loginWallAndForeignPagesGiveNothing`; `KitchenModelTest.recipeChangedWhileFetchingIsNotOverwritten` | ✓ |
| TE-11 | I, T | Optionale Cloud-KI (Claude): Familiendaten gehen an Anthropic, der API-Schlüssel landet woanders, oder eine Caption mit versteckten Anweisungen steuert die KI (Prompt-Injection) | **Neu:** aus ohne Schlüssel; nur die öffentliche Caption eines geteilten Instagram-Beitrags geht hin, und nur wenn die Regeln keine Zutaten finden; Schlüssel nur als Header an die feste Adresse `api.anthropic.com`, nie im Body, nicht im Abgleich; Antwort nur als JSON nach festem Schema, durch den Zutaten-Parser, Werte begrenzt, Ergebnis bleibt Vorschlag | `RecipeAiTest` (Schema, Begrenzung, Fehler), `HttpClaudeRecipeAiTest.recipeComesBackWithTheRightHeaders`; `KitchenModelTest.cloudAiStaysUnusedWithoutKeyOrWhenRulesSucceed`, `cloudAiReadsWhatTheRulesCannotAndItStaysASuggestion` | ◐ Schlüssel liegt unverschlüsselt im App-Speicher wie der Wiederherstellungscode |
| TE-8 | T, E | Geteilter oder getippter Text bringt einen Link mit anderem Schema (`intent:`, `javascript:`, `file:`), den „Quelle öffnen“ an das System gibt | Nur `http(s)://` wird als Quelle übernommen und geöffnet, alles andere bleibt Text; Tracking-Parameter (`igsh`, `utm_…`) werden entfernt | `RecipeImportTest.onlyWebLinksAreTaken`, `trackingParametersAreRemovedOthersKept` | ✓ |

### Kopplung per QR-Code (TB2, TB4, A2, A4)

| ID | STRIDE | Bedrohung | Maßnahme | Beleg | Status |
| --- | --- | --- | --- | --- | --- |
| KO-1 | S | Jemand fotografiert den QR-Code und koppelt sein eigenes Gerät | Einladende Person sieht Namen und Gerät und muss „Stimmt“ tippen; Code gilt 10 Minuten und nur einmal | `SyncControllerTest.declinedPairingTransfersNothing`, `wrongSecretExpiredAndForeignCodesFail` | ✓ |
| KO-2 | S, T | Jemand mit dem QR-Geheimnis setzt sich zwischen die Geräte | Prüfzahl aus dem ganzen Handshake (beide flüchtigen Schlüssel); ein Mittelsmann hat einen Versuch und trifft mit 1 : 1 000 000 | `SyncProtocolTest.manInTheMiddleWithTheQrSecretGetsDifferentDigits` | ◐ wirkt nur, wenn die Menschen die Zahlen vergleichen |
| KO-3 | S, D | Jemand rät das Geheimnis über das Netz oder macht den Code durch Fehlversuche ungültig | 256 Bit; erst ein bewiesenes Geheimnis verbraucht den Code, ein falsches Geheimnis oder ein Port-Scanner nicht | `SyncControllerTest.wrongSecretExpiredAndForeignCodesFail` (nach dem gefälschten Code lädt das Gerät weiter ein) | ✓ |
| KO-4 | I | QR-Code oder „Code als Text“ landet in einem Screenshot, einer Bildschirmaufnahme oder der Vorschau der letzten Apps | Wirkt wie KO-1 (Bestätigung nötig, 10 Minuten) | – | ✗ M2 |
| KO-5 | E | Der Familienschlüssel geht an ein Gerät, bevor beide zugestimmt haben | `Welcome` erst nach beiden Zusagen | `SyncControllerTest.declinedPairingTransfersNothing` | ✓ |
| KO-6 | S | Der QR-Code verweist auf ein anderes Gerät als das, mit dem man spricht | Neues Gerät prüft die Geräte-ID aus dem QR-Code | `SyncProtocolTest.joinerRefusesADeviceThatIsNotTheOneFromTheQrCode` | ✓ |

### Direkter Abgleich im WLAN oder Tailnet (TB2, A2)

| ID | STRIDE | Bedrohung | Maßnahme | Beleg | Status |
| --- | --- | --- | --- | --- | --- |
| NE-1 | I | Mithören im WLAN | AES-256-GCM, flüchtige P-256-Schlüssel je Sitzung (Forward Secrecy) | `HandshakeTest.sameSecretOpensEncryptedFramesBothWays`, `everySessionHasFreshKeys` | ✓ |
| NE-2 | S, T | Fremdes Gerät gibt sich als Familiengerät aus, verändert oder wiederholt Nachrichten | Vorab geteilter Schlüssel aus dem Familienschlüssel im Handshake (Muster Noise NNpsk0), Zählernonce je Richtung | `HandshakeTest.strangerWithoutFamilyKeyCannotOpenTheFirstFrame`, `changedOrReplayedFrameIsRejected`, `SyncControllerTest.foreignFamilyDeviceCannotSync` | ✓ |
| NE-3 | D | Ein Fremder schickt vier Bytes „Rahmen mit 32 MB“, die App reserviert den Speicher, bevor irgendein Geheimnis geprüft ist; wenige Verbindungen beenden die App | **Neu:** Bis die Gegenseite das Geheimnis bewiesen hat (die ersten zwei Rahmen), höchstens 64 KB je Rahmen | `TcpTransportTest.aHugeFirstFrameIsRefusedBeforeAnyMemoryIsReserved`, `afterTheHandshakeFramesMayBePhotoSized` | ✓ |
| NE-4 | D | Viele stumme Verbindungen halten den Server je 200 s fest | **Neu:** höchstens 8 Verbindungen gleichzeitig, wer vor dem Handshake 10 s schweigt, fliegt | `TcpTransportTest.aSilentStrangerIsDroppedQuickly`, `connectionsBeyondTheLimitAreClosed…` | ◐ ein Fremder im WLAN kann den Direktabgleich verzögern; Ordner und spätere Runden gleichen trotzdem ab |
| NE-5 | I | Erkennbarkeit: DNS-SD-Eintrag „FamilyApp“, Antwort auf einen Handshake; der Server lauscht auch im Café-WLAN | Eintrag und Handshake verraten nichts über die Familie, nur dass die App läuft | ADR 0006 | ✗ M3 |
| NE-6 | S, R | Innerhalb der Familie kann jedes gekoppelte Gerät sich als jedes andere ausgeben und `changedBy`/`doneBy` frei setzen; „wer hat erledigt“ beruht auf Vertrauen | Keine Signatur pro Gerät (bewusste Abweichung, ADR 0006) | – | ✗ M4 |
| NE-7 | T | Ein Familiengerät schickt bösartige Datensätze: Fotonamen mit `../`, übergroße Fotos | Fotonamen geprüft, Fotos ≤ 20 MB, Rahmen ≤ 32 MB | `SyncProtocolTest.photoNamesCannotEscapeThePhotoFolder`, `BackupFolderTest.photoNamesFromABackupCannotEscapeThePhotoFolder` | ◐ Anzahl der Datensätze unbegrenzt (gekoppeltes Gerät gilt als vertrauenswürdig) |
| NE-8 | T | Ungültiger Kurvenpunkt im Handshake (Invalid-Curve-Angriff) oder Müll statt Handshake | Die Krypto-Bibliothek der Plattform lehnt Punkte neben der Kurve ab, Müll scheitert am Format; ohne Familienschlüssel nützte ein Treffer ohnehin nichts, weil beide Seiten flüchtige Schlüssel verwenden | **Neu:** `HandshakeTest.publicKeyOffTheCurveIsRefused` (JDK; auf Android prüft Conscrypt/BoringSSL gleich), `garbageInsteadOfHandshakeIsRefused` | ✓ |

### Geteilter Ordner und Sicherung (TB3, A3)

| ID | STRIDE | Bedrohung | Maßnahme | Beleg | Status |
| --- | --- | --- | --- | --- | --- |
| OR-1 | I | Ordnerdienst liest die Familiendaten | Nur Chiffretext (AES-256-GCM, eigener Schlüssel per HKDF aus dem Familienschlüssel) | `SyncControllerTest.sharedFolderSyncsWithoutBothBeingOnline` („The folder only sees ciphertext“), `BackupFolderTest.nothingReadableEndsUpInTheFolder` | ◐ sichtbar bleiben Anzahl der Geräte (Dateinamen), Dateigrößen, Zeitpunkte, Anzahl Fotos |
| OR-2 | T | Dateien vertauschen, umbenennen, verändern oder eine alte Fassung zurückspielen | Dateiname als zusätzliche Authentisierung im GCM; alte Fassungen verlieren, weil pro Datensatz der neuere Stempel gewinnt | `BackupFolderTest.changedFileIsRejected`, `SyncMergeTest.newestVersionWinsAndTombstoneBeatsOlderEdit` | ✓ |
| OR-3 | D | Eine riesige Datei im Ordner füllt beim Einlesen den Speicher oder lässt jeden Abgleich scheitern | **Neu:** Lesen höchstens 32 MB pro Datei (Sicherung und Abgleich); eine zu große Abgleichdatei wird übersprungen, die anderen gleichen weiter ab | `BoundedReadTest` (4 Tests), `SyncControllerTest.sharedFolderSyncsWithoutBothBeingOnline` („Riesendatei“) | ✓ |
| OR-4 | D | Ordnerdienst löscht Dateien oder hält sie zurück | Nur Verfügbarkeit; „zuletzt abgeglichen“ steht in den Einstellungen, der Direktabgleich läuft weiter | – | ◐ akzeptiert |

### Hausgerät (TB5, A7)

| ID | STRIDE | Bedrohung | Maßnahme | Beleg | Status |
| --- | --- | --- | --- | --- | --- |
| HG-1 | I | Ein anderes Konto auf dem Rechner liest `sync.json` (Familienschlüssel) und die Familiendaten | **Neu:** `sync.json` entsteht mit `rw-------` (vor dem ersten Byte), das Verzeichnis bekommt `rwx------`; README empfiehlt Festplattenverschlüsselung | `SyncFilesTest.theFileWithTheFamilyKeyIsReadableByItsOwnerOnly` | ◐ Administrator liest alles, Daten liegen unverschlüsselt auf der Platte |
| HG-2 | S, D | Hausgerät ist dauernd erreichbar und damit dauernd Ziel von NE-3 bis NE-5 | Dieselben Maßnahmen wie NE-3/NE-4; README: Port nur im LAN/Tailnet, Tailscale-ACL auf Familiengeräte | `TcpTransportTest` | ◐ |

### Gerät (A5, A6)

| ID | STRIDE | Bedrohung | Maßnahme | Beleg | Status |
| --- | --- | --- | --- | --- | --- |
| GE-1 | I, S | Verlorenes Handy oder ehemaliges Familienmitglied: das Gerät behält den Familienschlüssel und kann weiter lesen und schreiben, über den geteilten Ordner sogar von überall | Keine (ADR 0006, „Grenzen“). Bildschirmsperre des Handys schützt nur vor dem Finder | – | ✗ M1, **höchstes Restrisiko** |
| GE-2 | I | Ab Android 12 kopiert der Umzug auf ein neues Handy den App-Speicher trotz `allowBackup="false"`. Das neue Handy hätte denselben Familienschlüssel und dieselbe Geräte-ID; beide überschreiben sich im geteilten Ordner gegenseitig die Datei | **Neu:** `dataExtractionRules` schließen Cloud-Sicherung und Umzug aus; ein neues Handy wird per QR-Code gekoppelt | `verify-apk.sh` prüft `allowBackup=false` und `dataExtractionRules` in jeder APK | ✓ |

## 5. Offen

| # | Maßnahme | Gegen | Warum noch nicht | Wann |
| --- | --- | --- | --- | --- |
| M1 | **Gerät entfernen mit Schlüsselwechsel**: neuer Familienschlüssel, verteilt nur an die übrigen Geräte, neuer Ordnerschlüssel, alte Ordnerdateien ersetzt | GE-1, NE-6 | Braucht Bedienkonzept (wer darf entfernen, was passiert mit einem Gerät, das gerade offline ist) und Protokollerweiterung | Vor der Weitergabe an weitere Familien; für Getrennterziehende Voraussetzung |
| M2 | `FLAG_SECURE`, solange QR-Code oder Code als Text sichtbar sind (kein Screenshot, leere Vorschau in den letzten Apps) | KO-4 | Klein; gehört in den Kopplungsbildschirm, der gerade in einem anderen Thread geändert wird | Mit der nächsten Änderung am Kopplungsbildschirm |
| M3 | Direktabgleich nur in vertrauten Netzen: beim Koppeln gesehenes Heim-WLAN und Tailscale, sonst Server aus; gleichzeitig die Android-16-Berechtigung „Geräte im lokalen Netz“ | NE-5, NE-4 | Ändert Bedienung und hängt an der offenen Android-16-Frage aus ADR 0006 | Mit der Android-16-Berechtigung |
| M4 | Geräteschlüssel und Signatur pro Datensatz | NE-6 | Ed25519 erst ab API 33; P-256-ECDSA ginge ab API 26 | Mit P2-2 |

## 6. Wie geprüft wird

- `tools/quality-check`: `gradle check` führt alle oben genannten Unit-Tests aus (auch echte Sockets auf localhost für NE-3/NE-4 und echte Dateien für OR-3/HG-1).
- CI-Emulator (Android 8 und 16): `app/scripts/emulator-share-e2e.sh` spielt fremde Apps. `adb` schickt einen `file://`-Link und einen Link auf den eigenen FileProvider; die Test-App `app/e2eShareSource` teilt wie eine echte Galerie (eigener Anbieter, Leserecht per Intent) ein 21-MB-Bild, eine HTML-Datei und HTML mit Bildtyp. Jede Ablehnung muss mit Grund auf dem Bildschirm stehen; ein echtes Bild muss als Foto im Eingang erscheinen. Bericht `pruefbericht-teilen-api<NN>.txt` am Release.
- `app/scripts/verify-apk.sh` prüft in jeder APK `allowBackup=false` und `dataExtractionRules` (GE-2).

## 7. Pflege

Dieses Dokument wird angepasst, wenn ein neuer Eingang dazukommt (z. B. Mail-Abruf, Widget, Relay aus ADR 0002), sich ein Format oder der Handshake ändert, oder eine Maßnahme aus Abschnitt 5 umgesetzt ist. Neue Tests oder Prüfungen, die eine Bedrohung abdecken, nennen ihre ID im Kommentar (Suche nach `TE-`, `NE-`, `OR-`, `HG-`, `GE-`).
