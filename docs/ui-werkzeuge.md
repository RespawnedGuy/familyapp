# Werkzeuge, um gemeinsam an der Oberfläche zu arbeiten

- Stand: 2026-11-25, Status: Empfehlung (wartet auf OK von Nils)
- Frage: Mit welchem kostenlosen oder selbst gehosteten Drag-and-drop-Werkzeug können Nils und Claude gemeinsam an der FamilyApp arbeiten?
- Rahmen: Kotlin Multiplatform + Compose Multiplatform 1.9.0, Android + iOS, local-first ohne Server, E2E-verschlüsselt (ADR 0001–0003). Regel-Engine und Logik sind reines Kotlin und werden geteilt.

## Kurzfassung

1. **Kein Low-Code-Builder erzeugt Compose-Code.** Dyad, Appsmith und FlutterFlow erzeugen React-, Web- oder Flutter-Apps. Wer sie als Werkzeug für die echte App nimmt, wechselt den Stack und verliert die geteilte Kotlin-Regel-Engine. Googles einziger Figma→Compose-Konverter (Relay) ist eingestellt.
2. **Empfehlung in drei Stufen, alle kostenlos:**
   - **A. Android Studio wieder zum Laufen bringen** (Ursache des Abbruchs gefunden, siehe unten). Darin: Vorschau, interaktiver Modus, Claude Code als Plugin direkt in Android Studio.
   - **B. Compose Hot Reload** über ein Desktop-Fenster: Code ändern, die laufende App ändert sich sofort. Kleiner Umbau, eigener PR.
   - **C. Penpot als Drag-and-drop-Zeichenbrett** (Open Source, MPL-2.0, Cloud kostenlos oder selbst gehostet auf der Synology). Nils schiebt Bildschirme zusammen, Claude liest sie über Penpots offiziellen MCP-Server und setzt sie in Compose um, und umgekehrt.
3. **Nicht empfohlen:** Appsmith, Dyad, FlutterFlow, Stack-Wechsel. Begründung unten.

Arbeitsteilung danach: Nils **gestaltet** (Penpot) und **feilt** (Android Studio, Hot Reload, Texte, Farben, Abstände). Claude **baut um** (Logik, neue Bildschirme, Übertragung aus Penpot) und prüft per CI. Gemeinsamer Ort bleibt das Git-Repo mit Branch + PR.

## Warum der Android-Studio-Thread abgebrochen ist

Zwei Dinge kamen zusammen:

1. Der Thread lief kurz nach dem PR ins Nutzungslimit und konnte nicht mehr antworten.
2. Nils' Fehler `build.gradle.kts:83:9: Unresolved reference 'density'` hat eine eindeutige Ursache: Zeile 83 in `app/androidApp/build.gradle.kts` ist `density { isEnable = false }`. Das **Android Gradle Plugin 9.0 hat Density-Splits samt DSL entfernt** ([AGP-9.0-Versionshinweise](https://developer.android.com/build/releases/agp-9-0-0-release-notes): „AGP 9.0 removes support for creating split APKs based on screen density. The functionality and the related APIs have been removed.“). Im Repo ist AGP 8.13.0 festgelegt, dort existiert die Zeile noch. Der Fehler tritt also nur auf, wenn Android Studio lokal auf AGP 9 hochgestuft hat, sehr wahrscheinlich über den Dialog „Upgrade Assistant“ / „Update Android Gradle Plugin“ beim ersten Öffnen. Das ist eine Schlussfolgerung aus Zeilennummer und Versionshinweisen, nicht auf Nils' Rechner geprüft.

Lösung:

- Lokal: die von Android Studio geänderten Gradle-Dateien verwerfen (*Git → Rollback* auf `libs.versions.toml`, `gradle-wrapper.properties`, `build.gradle.kts`) und das AGP-Upgrade beim nächsten Mal mit „Don't ask again for this project“ ablehnen.
- Im Repo (Vorschlag, eigener kleiner PR): die Zeile `density { isEnable = false }` löschen. Sie ist ohnehin wirkungslos (Density-Splits sind standardmäßig aus), und dann stolpert auch ein späteres AGP-9-Upgrade nicht mehr darüber. Ein echtes Upgrade auf AGP 9 ist mehr Arbeit (KMP-Module brauchen dort ein anderes Android-Plugin) und gehört in einen eigenen Schritt nach ADR 0004.

## Bewertung der Optionen

Kriterien in der Reihenfolge ihres Gewichts: (1) passt zum Stack, also landet am Ende in Compose-Code, (2) Drag-and-drop für Nils, (3) Claude kann mitarbeiten, (4) kostenlos oder Selfhost, (5) kein Server-Zwang für die App selbst.

| Option | Stack-Passung | Drag-and-drop | Claude arbeitet mit | Kosten / Lizenz | Urteil |
| --- | --- | --- | --- | --- | --- |
| **Android Studio** (Vorschau, interaktiv, Claude-Code-Plugin) | echter Code | nein (Compose hat keinen Layout-Editor) | ja, im selben Projekt | kostenlos | **A: Basis** |
| **Compose Hot Reload** (Desktop-Fenster) | echter Code | nein, aber sofortige Wirkung | ja | Apache 2.0, kostenlos | **B: empfohlen** |
| **Penpot** + offizieller MCP-Server | Entwurf, Claude überträgt | **ja** | ja, liest und zeichnet | MPL-2.0, Cloud gratis, Selfhost gratis | **C: empfohlen** |
| Dyad | React/Web | nein, Chat-basiert | eigener KI-Schlüssel nötig | Apache 2.0 (außer `src/pro`) | nur Wegwerf-Prototyp |
| Appsmith | Web, braucht Server + Datenquelle | ja | kaum | Apache 2.0 (Community) | passt nicht |
| FlutterFlow | Flutter/Dart, Stack-Wechsel | ja | nein | Gratis-Plan ohne Code-Export und ohne Teamarbeit | passt nicht |
| Figma + Relay | war Compose | ja | – | Relay eingestellt | entfällt |

### A. Android Studio (vorhanden, nur reparieren)

- Vorschauen für alle Bildschirme mit Beispielfamilie gibt es seit PR #22 (`docs/ui-bearbeiten.md`). Interaktiver Modus erlaubt Wischen und Antippen.
- **Ehrlich:** Für Compose gibt es in Android Studio **kein Drag-and-drop**. Der grafische Layout-Editor gilt nur für die alten XML-Layouts. Änderungen passieren im Code, die Vorschau zeigt sie.
- **Claude direkt in Android Studio:** Das Claude-Code-Plugin unterstützt Android Studio offiziell ([Doku](https://code.claude.com/docs/en/jetbrains): „Supported IDEs … Android Studio“). Claude sieht die geöffnete Datei und Markierung, zeigt Änderungen im Diff-Fenster der IDE. Nils markiert z. B. eine Karte und sagt „mehr Abstand, Titel fetter“, sieht den Diff und die Vorschau. Das kommt einem gemeinsamen Builder am nächsten.
- Optional: Android Studio hat „AI Actions → Edit Component“ direkt in der Vorschau ([Doku](https://developer.android.com/studio/gemini/transform-ui)). Das nutzt Gemini in der Cloud. Es sieht nur Quellcode, keine Familiendaten, verstößt also nicht gegen das Prinzip „Cloud-KI nie mit Nutzerdaten“. Ob Nils Cloud-KI auch fürs Entwickeln will, ist seine Entscheidung; Claude ist ebenfalls Cloud-KI.

### B. Compose Hot Reload

- Was: Die App läuft in einem Desktop-Fenster, jede Code-Änderung erscheint sofort im laufenden Zustand, ohne Neustart. Im Gegensatz zur Vorschau mit echter Logik statt Beispieldaten ([Kotlin-Doku](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html)).
- Voraussetzung laut Doku: ein **Desktop-Ziel (JVM)** im Projekt, Kotlin ≥ 2.1.20 (wir: 2.2.20), Java 21 / JetBrains Runtime, Android Studio ab Otter 2025.2.1 mit dem Kotlin-Multiplatform-Plugin. Ab Compose Multiplatform **1.10.0** ist Hot Reload eingebaut; bei uns (1.9.0) wird das Gradle-Plugin einzeln hinzugefügt oder Compose MP angehoben.
- Aufwand bei uns klein: `commonMain` hat **keine `expect`-Deklarationen** (geprüft), es fehlen also keine plattformspezifischen Teile. Nötig sind `jvm("desktop")` in `shared/build.gradle.kts`, eine `main()` mit `Window { App(...) }` und ein Startpunkt. Kamera und Mikrofon gibt es im Desktop-Fenster nicht; dort zeigt das Erfassen-Blatt nur den Tipp-Modus.
- Nebeneffekt: Das Desktop-Ziel ist auch eine Grundlage für das spätere Familien-Tablet/-Dashboard auf einem PC, ohne Mehraufwand. Es wird nicht ausgeliefert, solange wir das nicht entscheiden.

### C. Penpot als Drag-and-drop-Werkzeug

- Penpot ist ein Open-Source-Designwerkzeug im Browser, Lizenz **MPL-2.0** ([LICENSE im Repo](https://github.com/penpot/penpot/blob/develop/LICENSE)). Cloud unter design.penpot.app ist kostenlos („Sign up, it's free!“, „Unlimited teams and projects“, [penpot.app/code](https://penpot.app/code)); Selfhost per Docker Compose ist ebenfalls kostenlos.
- **Offizieller MCP-Server** ([README](https://github.com/penpot/penpot/tree/develop/mcp)): „enables LLMs to perform data queries, transformation and creation operations“. Claude kann also Penpot-Seiten lesen (Rahmen, Abstände, Farben, Texte), ändern und neu anlegen. Er läuft lokal per `npx -y @penpot/mcp@latest` oder als gehosteter Remote-Server. Die offizielle Docker-Compose-Datei (Penpot 2.18) bringt den MCP-Server als eigenen Container `penpot-mcp` gleich mit.
- Penpots Inspect-Modus gibt **nur CSS, HTML und SVG** aus, kein Compose. Die Übertragung nach Compose macht Claude, nicht ein Konverter. Das ist gewollt: Claude nutzt dabei die vorhandenen Bausteine (`InboxCard`, `PersonChip`, `FamilyIcons`, Farben aus `Theme.kt`) statt fremden Code zu erzeugen.
- Ablauf:
  1. Claude legt einmalig in Penpot eine Bibliothek aus den vorhandenen Bausteinen und Farben an (Code → Design), damit Nils mit echten Teilen zieht statt mit Rechtecken.
  2. Nils baut oder ändert einen Bildschirm per Drag-and-drop.
  3. Claude liest den Rahmen über MCP, setzt ihn in Compose um, öffnet einen PR; Nils prüft in der Vorschau oder auf dem Handy („FamilyApp Dev“).
- **Einschränkung, wichtig:** Diese Cloud-Umgebung kommt nicht an penpot.app heran (Netzwerkregel, hier am 25.11. geprüft). Die MCP-Verbindung funktioniert also mit Claude Code auf Nils' PC (Desktop-App oder Plugin in Android Studio), nicht aus einem Projekt-Thread heraus. Ohne MCP geht es auch: Rahmen als PNG plus Inspect-CSS in den Thread geben.
- Selfhost auf der Synology ist optional. Die Compose-Datei startet sechs Container (Frontend, Backend, Exporter, MCP, PostgreSQL, Valkey). Für zwei Personen reicht die kostenlose Cloud, es fließen dort nur Entwürfe mit Beispieldaten, keine Familiendaten.

### Warum nicht Dyad, Appsmith, FlutterFlow

- **Dyad** ([README](https://github.com/dyad-sh/dyad)): lokaler KI-App-Builder „like Lovable, v0, or Bolt“, Apache 2.0 außerhalb von `src/pro` (dort FSL 1.1). Erzeugt Web-Apps (React). Kein Drag-and-drop im eigentlichen Sinn, sondern Chat, und man braucht eigene KI-Schlüssel. Für die echte App nutzlos, weil nichts davon in Compose landet. Höchstens für einen schnellen klickbaren Wegwerf-Entwurf, und den kann Penpot auch, mit wiederverwendbaren Bausteinen.
- **Appsmith** ([LICENSE](https://github.com/appsmithorg/appsmith/blob/release/LICENSE), Apache 2.0): gebaut für interne Web-Werkzeuge über Datenbanken und APIs, läuft als Server. Widerspricht local-first und E2E-Verschlüsselung (ADR 0002/0003) und kann keine Android/iOS-App erzeugen.
- **FlutterFlow** ([Planvergleich](https://docs.flutterflow.io/accounts-billing/plan-comparison), Stand 17.11.2026): Gratis-Plan ohne „Download project source code“, ohne APK, mit nur einer bearbeitenden Person und ohne Echtzeit-Zusammenarbeit. Code-Export ab „Basic“ (39 $/Monat), gemeinsames Arbeiten ab „Pro“ (80 $/Monat, 2 Personen). Dazu ein Stack-Wechsel zu Dart: Regel-Engine, Datums-Parser, Sync und Krypto müssten neu geschrieben werden, das hat ADR 0001 bewusst ausgeschlossen. Kosten und Umbau stehen in keinem Verhältnis zum Nutzen.
- **Figma + Relay**: Googles Relay (Figma → Compose) wurde zum 30.04.2025 eingestellt, die Doku-Seite leitet heute auf die allgemeine Compose-Doku um.

## Vorgeschlagene nächste Schritte

1. Nils: lokales AGP-Upgrade zurücknehmen (siehe oben), Vorschau öffnen, Claude-Code-Plugin in Android Studio installieren.
2. Claude, kleiner PR: Zeile `density { … }` entfernen.
3. Claude, PR „Desktop-Ziel + Compose Hot Reload“ mit Anleitungsabschnitt in `docs/ui-bearbeiten.md`. Abnahme: `./gradlew :desktopApp:hotRunDesktop` (o. ä.) öffnet die App, eine Textänderung erscheint ohne Neustart; Android-Build und CI bleiben grün.
4. Nils: Penpot-Konto (Cloud) anlegen. Danach Claude (auf Nils' PC mit MCP): Baustein-Bibliothek aus dem Code anlegen, ein Bildschirm als Probelauf hin und zurück.

Abnahme für das Gesamtziel: Nils ändert einen Bildschirm selbst (Penpot oder Code), sieht das Ergebnis innerhalb von Sekunden und bekommt es als PR mit grünem CI in die App.
