# ADR 0001: Tech-Stack – Kotlin Multiplatform + Ktor

- Status: Akzeptiert (OK von Nils am 2026-11-20)
- Datum: 2026-11-20

## Kontext

- Plattformen: Android und iPhone gemischt, später zentrales Familien-Dashboard auf Tablet.
- Grundprinzip: deterministische Regel-Engine + HITL. Cloud-KI optional, lokale KI erlaubt.
- Mindestgerät: mit Server keines. Die Regeln müssen also auch serverseitig laufen können.
- Betrieb (Nils, 20.11.): Serverstruktur. Entwicklung auf dem Surface (Surfacelab, Linux), produktiv Selfhost und Cloud-Varianten.
- Geschäftsmodell: ausgesuchte Familien → Abo, plus Selfhost.
- Projektregeln: Schichten domain/data/presentation, Domain ohne Framework-Abhängigkeit, Offline-First ab Reifestufe Produkt.

## Entscheidung

- **Apps:** Kotlin Multiplatform (KMP) mit Compose Multiplatform für Android und iOS.
- **Geteilte Module:** `domain` (Regel-Engine, Datums-Parser, Fristen, Eskalation) als reines Kotlin ohne Abhängigkeiten; `data` (Repositories, Sync, lokale DB per SQLDelight).
- **Server:** Ktor + PostgreSQL, ausgeliefert als Docker-Compose (gleiches Artefakt für Entwicklung auf dem Surface, Selfhost und Cloud). Mail-Eingang per IMAP-Abruf eines Familienpostfachs.
- **KI:** Schnittstelle in `domain`, Implementierungen pro Plattform (lokal: ML Kit/Gemini Nano auf Android, Apple Vision/Foundation Models auf iOS) und optional Cloud. Standardmäßig keine aktiv.
- **iOS-Teilen-Menü:** kleine native Swift-Share-Extension, die nur an den Eingang übergibt.

## Begründung

1. Die Regel-Engine existiert genau einmal und läuft identisch auf Gerät und Server. Kein doppelter Code, gleiche Tests.
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
