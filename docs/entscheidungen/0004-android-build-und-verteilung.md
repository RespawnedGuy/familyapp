# ADR 0004: Android-Build und Verteilung als signierte Universal-APK

- Status: Vorgeschlagen
- Datum: 2026-11-20
- Bezug: [ADR 0001](0001-tech-stack.md) (KMP + Compose Multiplatform)

## Kontext

Nils will die App ohne Download- und Installationsfehler auf Android-Handys installieren, zunächst per Sideloading in der eigenen Familie, ohne Play Store. Cloud-Sessions haben kein Android SDK. Das Repo ist privat.

Typische Ursachen für fehlgeschlagene Sideload-Installationen:

| Ursache | Gegenmaßnahme |
| --- | --- |
| unsignierte APK oder Debug-/testOnly-Build | Release-Build, Signatur v2+v3, CI prüft `testOnly` und `debuggable` |
| Signatur wechselt zwischen Builds („App nicht installiert“ beim Update) | ein fester Release-Schlüssel als Actions-Secret, CI vergleicht den Zertifikats-Fingerprint mit `app/signing/release-cert-sha256.txt` |
| niedrigerer versionCode (Downgrade) | versionCode = Run-Nummer, steigt immer |
| AAB oder Split-APKs statt einer Datei | eine Universal-APK, Splits aus, keine ABI-Filter |
| Download als .zip (Actions-Artefakt) oder abgeschnitten | Verteilung als Release-Asset mit MIME-Typ `application/vnd.android.package-archive`, SHA-256 daneben, CI prüft ZIP-Integrität |
| minSdk zu hoch fürs Gerät | minSdk 26 (Android 8.0) |
| App startet nicht | CI installiert und startet auf Emulator Android 8 (API 26) und 16 (API 36) und prüft den Bildschirmtext |

## Entscheidung

1. **Projekt in `app/`**: Gradle-Wrapper 8.14.3 (mit SHA-256 der Distribution), Version-Katalog `gradle/libs.versions.toml` mit exakten Versionen: Kotlin 2.2.20 (wie die Spikes), Compose Multiplatform 1.9.0, AGP 8.13.0, activity-compose 1.10.1, compileSdk/targetSdk 36, minSdk 26, JDK 21. Module `shared` (gemeinsamer Compose-Code, Android + iOS-Ziele) und `androidApp`.
2. **App-ID `io.github.respawnedguy.familyapp`** (Nils, 20.11.2026). Dauerhaft.
3. **Signatur**: PKCS12-Schlüssel (RSA 4096, gültig bis 2054), Alias `familyapp`. Im CI aus den Secrets `FAMILYAPP_KEYSTORE_BASE64` und `FAMILYAPP_KEYSTORE_PASSWORD`. Sicherung bei Nils außerhalb von Git. Ohne Secrets baut das CI mit einem Wegwerf-Schlüssel und veröffentlicht nur einen Testbuild.
4. **Workflow `.github/workflows/android-apk.yml`**: bauen, Unit-Tests, `apksigner`/`zipalign`/`aapt2`-Prüfung, Emulator-Test (Installation, Start, Update auf versionCode+1, auf `main` zusätzlich Update von der vorigen Release-Version), Veröffentlichung. Auf `main` mit Release-Schlüssel entsteht ein Release `v0.1.<run>`, sonst ein Vorab-Release `ci-<branch>` mit dem Hinweis „nicht installieren“. Prüfberichte und Screenshots hängen jeweils an.
5. **R8 aus** und **`allowBackup=false`** (Familiendaten werden E2E-verschlüsselt, kein unverschlüsseltes Cloud-Backup).

## Alternativen

- **Play Store (interner Test):** bequeme Updates, aber Entwicklerkonto, Review und Google als Pflichtstelle. Später möglich, die App-ID passt.
- **F-Droid/Obtainium:** Obtainium kann GitHub-Releases verfolgen, beim privaten Repo nur mit Token. Prüfen, sobald es mehrere Familien gibt.
- **Debug-Schlüssel im Repo:** einfacher, aber öffentlich bekannter Schlüssel und gegen die Projektregel.

## Folgen

- Geht der Release-Schlüssel verloren, sind keine Updates mehr möglich, nur Deinstallieren und Neuinstallieren (Daten weg).
- Ab Januar 2027 verlangt Google auf zertifizierten Android-Geräten weltweit, dass sideloaded Apps von verifizierten Entwicklern stammen. Dann Entwicklerkonto (Android Developer Console) anlegen und App-ID plus Zertifikat registrieren, oder per `adb install` installieren. Vor Januar 2027 neu prüfen.
- Gradle-Lockfiles fehlen noch (Auflösung nur im CI möglich); nachziehen, sobald echte Abhängigkeiten dazukommen.
- Versionen sind konservativ gewählt (bekannt kompatibel). Aktualisierung auf neuere Kotlin-/Compose-Versionen als eigener Schritt.
