# FamilyApp auf dem iPhone

Stand 28.11.2026. Plan, noch keine Umsetzung. Quellen am Ende.

## Kurzfassung

- Der gemeinsame Code (Domain, Daten, Darstellung, alle Bildschirme in Compose) läuft auch auf iOS. Das Modul `shared` hat schon die Ziele `iosArm64` und `iosSimulatorArm64` und einen Einstieg `MainViewController()`. Er wurde aber nie gebaut, weil kein CI-Lauf auf macOS existiert.
- Ein Mac ist nicht nötig. GitHub Actions hat macOS-Runner, für öffentliche Repos kostenlos. Das Xcode-Projekt wird aus einer Textdatei erzeugt (XcodeGen), signiert wird im CI.
- Ohne die Mitgliedschaft im Apple Developer Program (99 € im Jahr) gibt es nur Basteln mit 7-Tage-Signatur. Für Familien außerhalb des eigenen Haushalts führt kein Weg an den 99 € vorbei.
- Der Weg für „ausgewählte Familien“: erst TestFlight (bis 10 000 Tester per Link), später, sobald es ein Abo gibt, eine nicht gelistete App im App Store.
- Arbeit: etwa 15 plattformspezifische Teile brauchen ein iOS-Gegenstück. Die meisten sind klein, weil die App schon über Schnittstellen (`PlatformServices`, `SyncCrypto`, `SyncPlatform`, `TextStore`) gebaut ist und nicht über `expect/actual`.

## Was schon da ist und was fehlt

Die Plattformteile hängen an Schnittstellen in `commonMain`. Android füllt sie in `androidMain` und `androidApp`, iOS füllt bisher nur Speicher, Datum, Uhrzeit und Wiederherstellungscode (`iosMain/.../MainViewController.kt`). Alles andere meldet auf iOS „nicht verfügbar“ oder ist `null`, die App startet trotzdem (Erfassen per Tippen).

| Teil | Android heute | iOS-Gegenstück | Aufwand |
| --- | --- | --- | --- |
| Speicher | `AndroidTextStore` | `IosTextStore` (da) | – |
| Sprache erfassen | `AndroidSpeech` (SpeechRecognizer) | `SFSpeechRecognizer` mit `requiresOnDeviceRecognition` (bleibt auf dem Gerät, passt zur KI-Prämisse) | M |
| Foto | Kamera-Intent | `UIImagePickerController` bzw. `PHPicker` | S |
| Sicherung in Ordner | SAF (`SafBackupFiles`, `BackupFolder`) | `UIDocumentPicker` mit Security-Scoped Bookmark, Dateien-App/iCloud Drive | M |
| Backup-Krypto (AES-GCM, HKDF) | `BackupCrypto` (javax.crypto) | CryptoKit, gleiche Algorithmen; Prüfung mit festen Testvektoren aus den JVM-Tests | M |
| Sync-Krypto (P-256-ECDH, AES-256-GCM, HMAC, SHA-256) | `JcaSyncCrypto` | CryptoKit (`P256.KeyAgreement`, `AES.GCM`); öffentlicher Schlüssel als SPKI-DER wie auf Android | M |
| Sync-Transport | `TcpTransport`, NSD | Network.framework (`NWListener`, `NWConnection`, `NWBrowser` für Bonjour `_familyapp._tcp`); Info.plist braucht `NSLocalNetworkUsageDescription` und `NSBonjourServices` | L |
| Sync-Ordner (Briefkasten) | `SyncFiles` | wie Sicherung, über `UIDocumentPicker` | M |
| QR-Code zeigen | ZXing | CoreImage `CIQRCodeGenerator` | S |
| QR-Code scannen | ZXing-Scanner | VisionKit `DataScannerViewController` (ab iOS 16) | S |
| Teilen-Ziel (Instagram-Link, Text, Bild) | `SEND`-Intents | Share Extension (eigenes Ziel in Xcode), Übergabe über App Group an die App | M |
| Instagram-Seiten, Bilder, Videos, Märkte, Gemini/Claude | `HttpURLConnection` (7 Dateien) | `NSURLSession`, oder einmal Ktor-Client mit Darwin- und Android-Engine in `commonMain` (nur Maven Central). Größenbegrenzung aus `BoundedRead` übernehmen | M |
| WebView-Rückfall Instagram | `WebViewInstagramPages` | `WKWebView` | M |
| Rezeptbild aus Videoframe | `AndroidRecipePhotos` | `AVAssetImageGenerator` | S |
| Frist-Erinnerungen | `AndroidReminders` (AlarmManager) | `UNUserNotificationCenter`, lokal; iOS hält höchstens 64 geplante, also nur die nächsten planen | M |
| Kontakte, Anrufen, SMS, Mail | Intents | `CNContactPickerViewController`, `tel:`, `sms:`, `mailto:` | S |
| Schnellkachel | Quick-Settings-Kachel | Steuerelement im Kontrollzentrum (iOS 18) und App Intent für Aktionstaste und Kurzbefehle | M |
| In-App-Update | ADR 0005 | entfällt: TestFlight bzw. App Store aktualisiert selbst (`updates = null` ist schon vorgesehen) | – |
| Messungen, Bildschirm an | `ColdStartProbe` u. a. | `ProcessInfo`, `isIdleTimerDisabled` | S |

S = unter einem halben Tag, M = etwa ein Tag, L = mehrere Tage.

Swift-only-APIs (CryptoKit, VisionKit, Share Extension, App Intents) lassen sich aus Kotlin nicht direkt aufrufen. Lösung: Die Schnittstellen bleiben in Kotlin, die iOS-App implementiert sie in Swift und übergibt sie an `MainViewController(...)`. Der Rest (Foundation, UIKit, Network.framework, UserNotifications) geht direkt aus `iosMain`.

### Grenzen, die bleiben

- **Abgleich nur im Vordergrund.** iOS lässt keine App im Hintergrund lauschen (ADR 0003). Ein iPhone gleicht ab, wenn es geöffnet wird. Der Dauer-Peer `tools/family-peer` im Tailnet löst das für zwei iPhones, der Ordner-Briefkasten auch.
- **Ordner für Android und iPhone gemeinsam.** iCloud Drive ist auf Android kaum erreichbar, Google Drive auf iOS nur über die Dateien-App. Synology Drive bzw. WebDAV und der Dauer-Peer funktionieren auf beiden.
- **Eine VPN-Verbindung gleichzeitig.** Wer ein Firmen-VPN auf dem iPhone hat, kann Tailscale nicht parallel nutzen (ADR 0003).
- **Teilen aus Instagram** öffnet auf iOS die Share Extension, nicht die App. Die Extension legt den Link ab, die App holt ihn beim nächsten Öffnen. Das knappe Speicherlimit der Extension reicht dafür, der Gemini-Upload läuft dann in der App.

## Vertriebswege

| Weg | Kosten | Wer kann installieren | Haltbarkeit | Passt zu |
| --- | --- | --- | --- | --- |
| Kostenlose Apple-ID, AltStore + AltServer auf Windows | 0 € | nur die eigenen Geräte, höchstens 3 Apps | Signatur 7 Tage, PC muss wöchentlich im selben WLAN neu signieren | einmal selbst ausprobieren |
| Ad-hoc (Developer Program) | 99 €/Jahr | 100 registrierte iPhones je Jahr, Geräte-ID nötig | 1 Jahr | eigener Haushalt, wenig sinnvoll neben TestFlight |
| **TestFlight** (Developer Program) | 99 €/Jahr | bis 10 000 externe Tester per Link, kein Geräte-ID-Sammeln | Build 90 Tage, neue Builds kommen automatisch | **ausgewählte Familien, Erprobung** |
| **Nicht gelistete App im App Store** | 99 €/Jahr, beim Abo 15 % (Small Business Program) bzw. in der EU weniger per Link nach außen | jeder mit dem Link | dauerhaft | **Abo-Phase** |
| Öffentlich im App Store | wie oben | alle | dauerhaft | später, wenn gewollt |
| EU: Web-Vertrieb oder alternativer Marktplatz (z. B. AltStore PAL) | 99 €/Jahr, 5 % auf digitale Verkäufe | alle in der EU | dauerhaft | **nicht erreichbar**: Apple verlangt u. a. Finanznachweis (Dun & Bradstreet, Wirtschaftsprüfer, 1 Mio. USD Akkreditiv) oder 1 Mio. Installationen; Notarisierung bleibt Pflicht |

Selfhost betrifft auf dem iPhone nur den Dauer-Peer bzw. später das Relay. Die App selbst kommt immer über Apple.

Auch TestFlight und die nicht gelistete App gehen durch Apples Prüfung (TestFlight: kurze Beta-Prüfung beim ersten Build einer Version). Kritisch dabei: Rezeptimport per Instagram-Seite und WebView, und die Gemini-Nutzung mit eigenem Schlüssel. Beides ist erlaubt, braucht aber klare Texte in den Datenschutzangaben („App Privacy“).

Mitgliedschaft als Einzelperson zeigt den echten Namen als Anbieter in TestFlight und App Store. Als Organisation braucht es eine Firma mit D-U-N-S-Nummer.

## Bauen ohne Mac

1. **macOS-Runner** (`runs-on: macos-15`) im CI, kostenlos fürs öffentliche Repo.
2. **Xcode-Projekt aus Text:** `app/iosApp/project.yml` für XcodeGen, daraus erzeugt das CI `iosApp.xcodeproj`. Nichts muss in Xcode geklickt werden, Änderungen sind im Diff lesbar.
3. **Signatur:** App-Store-Connect-API-Schlüssel als Secret, `xcodebuild -allowProvisioningUpdates` legt Zertifikat und Profil selbst an. Alternativ Zertifikat (`.p12`) und Profil als Secrets wie beim Android-Schlüssel.
4. **Hochladen** zu TestFlight mit fastlane `pilot` oder `xcrun altool --upload-app`.
5. **Prüfen:** `./gradlew :shared:iosSimulatorArm64Test` lässt alle `commonTest`-Tests auf iOS laufen. Dazu ein Start im iOS-Simulator mit Bildschirmfoto als Rauchtest, wie heute im Android-Emulator.

UI-Arbeit bleibt im Desktop-Fenster mit Hot Reload, das zeigt denselben Compose-Code.

## Stufen

| Stufe | Inhalt | Kosten | Nils muss |
| --- | --- | --- | --- |
| **0 Kompiliert** | macOS-Job im CI: `shared` für iOS bauen, `commonTest` im Simulator. Fehler im vorhandenen iOS-Einstieg beheben. | 0 € | nichts |
| **1 Startet im Simulator** | XcodeGen-Projekt, App startet im Simulator, Bildschirmfoto im CI. Erfassen per Text, Speicher, Personen, Aufgaben, Rezepte, Einkauf. | 0 € | nichts |
| **2 Auf dem eigenen iPhone** | Kern-Gegenstücke: Sprache, Foto, Erinnerungen, Sicherung in Ordner, Netzwerk (Märkte, Rezeptimport, Gemini). Unsigniertes IPA aus dem CI, selbst signiert per AltStore. | 0 € | ein iPhone, AltServer auf dem PC installieren, wöchentlich neu signieren lassen |
| **3 Familie koppelt** | QR zeigen und scannen, Krypto, Bonjour/TCP, Ordner-Briefkasten. Abgleich iPhone ↔ Android ↔ Dauer-Peer im Test. | 0 € | wie Stufe 2 |
| **4 TestFlight** | Teilen-Erweiterung, Kontrollzentrum, Signatur und Upload im CI, Datenschutzangaben. | 99 €/Jahr | Developer Program abschließen, API-Schlüssel anlegen und als Secret eintragen (Anleitung kommt mit) |
| **5 Abo** | nicht gelistete App, Abo über App Store bzw. EU-Link nach außen. | 99 €/Jahr + Provision | Entscheidung Abo-Modell, Steuer- und Bankangaben bei Apple |

Stufen 0 und 1 sind risikolos und kosten nichts. Sie zeigen, wie viel des vorhandenen Codes ohne Änderung auf iOS läuft.

## Empfehlungen

- **I1** Stufen 0 und 1 jetzt umsetzen, ohne Apple-Konto.
- **I2** Plattformteile über die vorhandenen Schnittstellen, kein Umbau auf `expect/actual`. Swift-only-APIs implementiert die iOS-App und übergibt sie.
- **I3** HTTP einmal in `commonMain` mit Ktor-Client (Darwin- und Android-Engine), statt sieben Dateien doppelt zu schreiben. Größengrenzen bleiben wie in `BoundedRead`.
- **I4** Krypto auf iOS mit CryptoKit, Gleichheit mit Android über feste Testvektoren in `commonTest` belegen. Neue Eingänge (Share Extension, Bonjour) ins Bedrohungsmodell.
- **I5** Xcode-Projekt per XcodeGen aus `app/iosApp/project.yml`, nie von Hand gepflegt.
- **I6** Kein In-App-Update auf iOS, Updates über TestFlight bzw. App Store.
- **I7** Vertrieb: TestFlight ab Stufe 4, nicht gelistete App erst mit Abo. Kein Ad-hoc, kein EU-Web-Vertrieb.
- **I8** 99 € erst ausgeben, wenn Stufe 3 auf einem echten iPhone läuft.
- **I9** iPhones gleichen beim Öffnen sofort ab. Für Familien mit mehreren iPhones den Dauer-Peer oder den Ordner-Briefkasten empfehlen.
- **I10** Mindestversion iOS 16 (VisionKit-Scanner, aktuelle Compose-Unterstützung).

## Offene Fragen an Nils

- Welche iPhones gibt es in der Familie (Modell, iOS-Version)? Ohne echtes iPhone endet die Erprobung bei Stufe 1.
- Developer Program als Einzelperson (echter Name sichtbar) oder später als Firma?

## Quellen

Abgerufen am 28.11.2026.

- [Changes for apps in the European Union (Apple Developer)](https://developer.apple.com/support/apps-in-the-eu/) und [Apple Newsroom, 18.08.2026](https://www.apple.com/newsroom/2026/08/apple-announces-changes-for-apps-in-the-european-union/): ab 1.10.2026 Core Technology Commission 5 %, Zulassungskriterien für alternativen Vertrieb, Notarisierung bleibt.
- [Unlisted app distribution (Apple Developer)](https://developer.apple.com/support/unlisted-app-distribution)
- [TestFlight overview (App Store Connect Help)](https://www.developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview)
- ADR 0003 (Grenzen iOS im Hintergrund, 64 Benachrichtigungen, ein VPN) und ADR 0006 (iOS fehlt: QR, Bonjour, Hintergrund).
