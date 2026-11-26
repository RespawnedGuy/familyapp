# FamilyApp – Projekt-CLAUDE.md

Reifestufe: **Spike** für `spikes/` (Wegwerfcode, wird nie zum Produkt). `app/` ist das echte KMP-Projektgerüst (Android-App, iOS vorbereitet), aktuell nur Platzhalter-Bildschirm.

Die allgemeinen Regeln stehen in der übergeordneten CLAUDE.md von Nils. Chat und Doku auf Deutsch, Code, Bezeichner und Commits auf Englisch.

## Befehle

| Zweck | Befehl (in `spikes/rule-engine/`) |
| --- | --- |
| test | `gradle test` |
| lint | – (im Spike nicht eingerichtet) |
| build | `gradle build` |
| Spike ausführen | `gradle run --args="<samples-dir> <rules-file> [expected.csv]"` |

Sync-Spike (in `spikes/sync/`): `gradle test`, `gradle build`, `gradle run` (Relay auf Port 8080), `docker compose up -d --build` (vorher `.env` aus `.env.example`). Umgebungsvariablen: `RELAY_PORT`, `RELAY_DB_PATH`, `RELAY_TIMER_INTERVAL_SECONDS`, `RELAY_PUBLIC_PORT`.

App (in `app/`, Android SDK nötig, in Cloud-Sessions nicht vorhanden): `./gradlew :shared:testDebugUnitTest`, `./gradlew :androidApp:assembleRelease`, Desktop-Fenster mit Hot Reload `./gradlew :shared:hotRunJvm --auto`, Desktop-/JVM-Tests `./gradlew :shared:jvmTest` (prüft auch die Penpot-Tokens gegen `Theme.kt`). Signierte APK, Prüfung und Emulator-Test baut nur GitHub Actions (`.github/workflows/android-apk.yml`, ADR 0004); Ergebnis als GitHub-Release. Secrets: `FAMILYAPP_KEYSTORE_BASE64`, `FAMILYAPP_KEYSTORE_PASSWORD`, `RELEASES_REPO_TOKEN` (veröffentlicht in das öffentliche Update-Repo `familyapp-releases`, ADR 0005). Build-Typ `e2e` nur für den Update-Test im CI, nie veröffentlichen.

Spike `spikes/recipe-import/`: `gradle test`, `gradle run --args="<samples-dir> [expected.csv]"`; Vergleichsdaten aus einer Linkliste per `tools/collect_samples.py` (siehe README dort).

Spike `spikes/p2p-sync/`: `gradle test`, `gradle build`, CLI per `gradle installDist` und `build/install/p2p-sync/bin/p2p-sync` (siehe README dort).

## Ordner

- `docs/` Konzept, `docs/kochbibliothek.md` Kochbibliothek mit Rezeptimport und Angeboten, `docs/entscheidungen/` ADRs, `docs/gestaltung/` Optik, Haptik, Klickwege, `docs/android-installieren.md` Sideloading-Anleitung, `docs/ui-bearbeiten.md` UI in Android Studio bearbeiten (Vorschauen in `app/shared/src/androidMain/.../preview/`), `docs/ui-werkzeuge.md` Werkzeugwahl für gemeinsame UI-Arbeit (Penpot, Hot Reload), `docs/gestaltung/penpot/` Penpot-Anleitung und Design-Tokens
- `app/` KMP-App: `shared/` (gemeinsamer Compose-Code), `androidApp/`, `iosApp/` (vorbereitet), `scripts/` (APK-Prüfung, Emulator-Test)
- `spikes/rule-engine/` Spike: deterministische Regel-Engine + deutscher Datums-Parser
- `spikes/sync/` Spike: Local-first-Sync über blindes Relay (ADR 0002)
- `spikes/p2p-sync/` Spike: Gerätesync ohne Server (WLAN/Tailscale, optional geteilter Ordner), ADR 0003
- `spikes/recipe-import/` Spike: Rezept aus Bildunterschrift/Transkript per Regeln, Messung gegen geprüfte Instagram-Rezepte

## Datenschutz

Echte Familienpost nur in `samples-private/` und `expected-private.csv` ablegen. Beides steht in `.gitignore` und darf nie committet werden.

Android-Signaturschlüssel (`*.p12`, `*.jks`, `_signing/`) nie committen.
