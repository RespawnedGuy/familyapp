# ADR 0001: Tech-Stack – Kotlin Multiplatform + Ktor

- Status: Akzeptiert (OK von Nils am 2026-11-20)
- Datum: 2026-11-20
- Geändert: 2026-11-20 durch [ADR 0002](0002-local-first-sync.md) (Local-first statt Server mit Logik)

## Kontext

- Plattformen: Android und iPhone gemischt, später zentrales Familien-Dashboard auf Tablet.
- Grundprinzip: deterministische Regel-Engine + HITL. Cloud-KI optional, lokale KI erlaubt.
- ~~Mindestgerät: mit Server keines. Die Regeln müssen also auch serverseitig laufen können.~~ Seit ADR 0002 laufen die Regeln auf den Geräten; Mindestgerät im Sync-Spike messen.
- Betrieb (Nils, 20.11.): ~~Serverstruktur.~~ Local-first ohne Pflicht-Server (ADR 0002). Entwicklung auf dem Surface (Surfacelab, Linux), Test-Relay auf Nils' VPS, produktiv Abo-Relay und Selfhost-Relay.
- Geschäftsmodell: ausgesuchte Familien → Abo, plus Selfhost.
- Projektregeln: Schichten domain/data/presentation, Domain ohne Framework-Abhängigkeit, Offline-First ab Reifestufe Produkt.

## Entscheidung

- **Apps:** Kotlin Multiplatform (KMP) mit Compose Multiplatform für Android und iOS.
- **Geteilte Module:** `domain` (Regel-Engine, Datums-Parser, Fristen, Eskalation) als reines Kotlin ohne Abhängigkeiten; `data` (Repositories, lokale DB per SQLDelight, `sync` mit Änderungsprotokoll/CRDT, `crypto`, Mail-Abruf per IMAP); `protocol` (Datentypen und Signaturen, gemeinsam mit dem Relay).
- **Server:** ~~Ktor + PostgreSQL mit Logik~~ → blindes Relay (Ktor + SQLite: Briefkasten, blinder Wecker, Push anstoßen), ausgeliefert als Docker-Compose (gleiches Artefakt für Test-VPS, Selfhost und Abo). Dazu ein Push-Gateway, das nur wir betreiben. Optional ein Hausgerät, das als Familienmitglied ohne Bildschirm dieselbe Logik auf der JVM ausführt. Details: ADR 0002.
- **Mail-Eingang:** IMAP-Abruf eines Familienpostfachs auf den Geräten (optional auf dem Hausgerät per IMAP IDLE), nicht mehr auf einem Server.
- **KI:** Schnittstelle in `domain`, Implementierungen pro Plattform (lokal: ML Kit/Gemini Nano auf Android, Apple Vision/Foundation Models auf iOS) und optional Cloud. Standardmäßig keine aktiv.
- **iOS-Teilen-Menü:** kleine native Swift-Share-Extension, die nur an den Eingang übergibt.

## Begründung

1. Die Regel-Engine existiert genau einmal und läuft identisch auf Handy und optionalem Hausgerät. Kein doppelter Code, gleiche Tests.
2. Plattform-KI und Texterkennung sind native APIs; KMP greift über plattformspezifische Implementierungen direkt darauf zu.
3. Domain als reines Kotlin-Modul erfüllt die harte Regel „Domain importiert kein Framework“ technisch erzwingbar.

## Alternativen

- **Flutter:** eine UI, reif. Aber die Regel-Engine in Dart lässt sich nur mit einem Dart-Server teilen; native KI-APIs nur über Plugins.
- **Zwei native Apps (Kotlin + Swift):** beste Plattformintegration, aber doppelte Logik und doppelter Aufwand.
- **React Native:** wie Flutter, zusätzlich JavaScript-Server nötig, um Logik zu teilen.

## Folgen

- Compose Multiplatform auf iOS ist jünger als Flutter; UI-Details auf iOS früh im Spike prüfen.
- Exakte Versionen und Lockfiles werden beim ersten Code-Schritt festgelegt (harte Regel 3) und hier nachgetragen.
- Erster Schritt: Spike Regel-Engine mit 30 echten Mails/Briefen. Abnahme: ≥ 80 % richtig zugeordnet oder korrekt offen gelassen, keine Falschzuweisung bei „100 % eindeutig“.
