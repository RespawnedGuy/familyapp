# ADR 0005: Update-Funktion in der Android-App über ein öffentliches Releases-Repo

- Status: Vorgeschlagen (Update-Quelle von Nils entschieden am 2026-11-20)
- Datum: 2026-11-20
- Bezug: [ADR 0004](0004-android-build-und-verteilung.md) (signierte Universal-APK), [ADR 0003](0003-p2p-sync-im-selben-netz.md) (kein Server)

## Kontext

Nils will, dass sich die App selbst aktualisiert. Die App wird per Sideloading verteilt, nicht über einen Store. Das Code-Repo ist privat. Eine App, die private GitHub-Releases liest, bräuchte einen Token in jeder APK. Den kann jeder aus der APK auslesen, und er öffnet das private Repo.

## Entscheidung

1. **Update-Quelle:** ein öffentliches Repo `RespawnedGuy/familyapp-releases`, das nur fertige APKs, ihre Prüfsumme und `update.json` enthält. Code, Konzept und Prüfberichte bleiben im privaten Repo. Das CI veröffentlicht dort mit einem Fine-grained-Token (`RELEASES_REPO_TOKEN`, Schreibrecht nur auf dieses Repo). Kein eigener Server; GitHub ist reine Download-Quelle.
2. **`update.json`** pro Release: `versionCode`, `versionName`, `apkUrl`, `sha256`, `sizeBytes`, `minSdk`, `releaseNotes`. Abruf über `…/releases/latest/download/update.json` (keine API, kein Rate-Limit).
3. **Prüfungen in der App, bevor Android gefragt wird:**
   - `apkUrl` muss mit `https://github.com/RespawnedGuy/familyapp-releases/releases/download/` beginnen und auf `.apk` enden.
   - Download höchstens so groß wie angekündigt, danach Größe und SHA-256 gleich.
   - Paketname gleich, versionCode gleich der Ankündigung, Signaturzertifikat gleich dem der installierten App.
   - Bei jeder Abweichung wird die Datei gelöscht und eine verständliche Meldung angezeigt.
4. **Installation über den PackageInstaller** (Session-API). Der Mensch tippt „Installieren“ und bestätigt den System-Dialog. Beim ersten Mal erlaubt er „Unbekannte Apps installieren“ für FamilyApp; die App führt direkt in die Einstellung.
5. **Prüfung höchstens einmal täglich beim Start**, abschaltbar, plus Knopf „Nach Updates suchen“. Gesendet wird nichts außer dem Abruf selbst (User-Agent mit App-Version).
6. **Schichten:** domain (`UpdateManifestParser`, `UpdatePolicy`) und presentation (`UpdateController`, `UpdateSection`) in `shared/commonMain`; data (`AndroidUpdatePlatform`) in `shared/androidMain`. iOS hat keinen Updater (später TestFlight/App Store).
7. **Debug-Builds** haben keinen Updater (anderer Paketname). Ein Build-Typ `e2e` holt Updates von `http://10.0.2.2:8000/` und erlaubt nur dafür unverschlüsseltes HTTP; er wird nie veröffentlicht. `verify-apk.sh` stellt sicher, dass die Release-APK keine solche Ausnahme enthält.

## Prüfung

- 24 Unit-Tests in `shared/commonTest` (Parser, Regeln, Ablauf mit Fake-Plattform).
- Im CI auf Android 8 und 16 ein echtes Update im Emulator: falsche Prüfsumme wird abgelehnt, fremde Signatur wird abgelehnt, Version 100 → 101 wird nach Bestätigung installiert.

## Alternativen

- **Token in der App:** verworfen, Token wäre öffentlich.
- **Haupt-Repo öffentlich:** einfacher, aber Code und Konzept wären öffentlich.
- **Synology im Tailnet:** nichts öffentlich, aber nur für Geräte im Tailnet und mehr Einrichtung.
- **Obtainium:** braucht keinen eigenen Code und funktioniert mit dem öffentlichen Releases-Repo ohne Token. Bleibt als Ausweichweg.
- **Update per P2P von einem anderen Familiengerät:** passt zu ADR 0003, später denkbar, sobald der Sync-Transport steht.

## Folgen

- Die APKs sind öffentlich herunterladbar. Sie enthalten keine Geheimnisse; Familiendaten liegen nie in der APK.
- Der Token läuft nach einem Jahr ab (Erinnerung nötig). Fehlt er, warnt das CI und veröffentlicht nur im privaten Repo.
- Ab Januar 2027 gelten Googles Regeln zur Entwicklerverifizierung auch für Updates per PackageInstaller (siehe ADR 0004).
