# UI selbst bearbeiten (Android Studio)

Kurzanleitung, um Bildschirme der App in Android Studio anzusehen, zu ändern, auf dem Handy auszuprobieren und als Pull Request zurückzugeben.

## 1. Einmalig einrichten

1. **Aktuelles Android Studio** installieren (2025.1 „Narwhal“ oder neuer). Beim ersten Start das vorgeschlagene Android SDK mitinstallieren.
2. **Repo klonen**, nicht den Synology-Ordner nehmen: *File → New → Project from Version Control*, URL `https://github.com/RespawnedGuy/familyapp`, Zielordner z. B. `C:\dev\familyapp`.
   Grund: Gradle schreibt beim Bauen tausende Dateien (`build/`, `.gradle/`), die Synology Drive sonst ständig synchronisiert, und ohne Git-Klon gibt es keinen Branch/PR.
3. **Den Ordner `app` öffnen**, nicht das ganze Repo: *File → Open → `C:\dev\familyapp\app`*. Nur dort liegt `settings.gradle.kts`; öffnet man die Repo-Wurzel, erkennt Android Studio kein Gradle-Projekt und zeigt keine Vorschau.
4. Gradle-Sync abwarten (unten rechts Fortschrittsbalken, beim ersten Mal einige Minuten). Falls nach einem JDK gefragt wird: *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* auf das mitgelieferte **JetBrains Runtime 21** stellen.

In der Projektansicht oben links „Project“ statt „Android“ wählen, dann sieht man die Ordner wie im Repo.

## 2. Welche Datei ist welcher Bildschirm

Alle Pfade unter `app/shared/src/commonMain/kotlin/io/github/respawnedguy/familyapp/`. Der Code dort gilt für Android und später iPhone.

| Was man sieht | Datei | Funktion |
| --- | --- | --- |
| Rahmen: obere Leiste, untere Navigation, Erfassen-Knopf, Rückgängig-Leiste, Update-Hinweis, Willkommen (Onboarding) | `App.kt` | `TopBar`, `BottomBar`, `NavItem`, `FeedbackBar`, `UpdateBanner`, `Onboarding` |
| Heute (Eingang + Meins) | `ui/Screens.kt` | `TodayScreen` |
| Familie (Lastkarte, Personen, Person anlegen) | `ui/Screens.kt` | `FamilyScreen`, `MemberDialog` |
| Demnächst (6 Wochen) | `ui/Screens.kt` | `UpcomingScreen` |
| Suchen | `ui/Screens.kt` | `SearchScreen` |
| Einstellungen (über das eigene Profilbild oben rechts) | `ui/SettingsScreen.kt` | `SettingsScreen` |
| Erfassen-Blatt (Sprechen, Tippen, Foto) | `ui/CaptureSheet.kt` | `CaptureSheet` |
| Eingang-Karte mit Chips „Wer?“/„Wann?“ | `ui/Cards.kt` | `InboxCard`, `PersonChip`, `DateChip` |
| Aufgabenzeile, Detail-Dialog | `ui/Cards.kt` | `TaskRow`, `ItemDialog` |
| Personen-Punkt, Chip, Überschrift, Leertext, Wischen | `ui/Components.kt` | `PersonDot`, `DecisionChip`, `SectionHeader`, `EmptyNote`, `SwipeToDecide` |
| Farben (hell/dunkel), Personenfarben, Ecken, Animationsdauer | `ui/Theme.kt` | `LightFamilyColors`, `DarkFamilyColors`, `Motion` |
| Symbole | `ui/Icons.kt` | `FamilyIcons` |

Texte stehen direkt im Code (z. B. `Text("Alles sortiert …")`), Größen in `dp` (Abstände) und `sp` (Schrift). Logik (was passiert beim Tippen) liegt in `presentation/AppModel.kt`, die Daten in `domain/` und `data/`: für reine Optik muss man dort nichts anfassen.

## 3. Vorschau öffnen

Die Vorschauen liegen in `app/shared/src/androidMain/kotlin/io/github/respawnedguy/familyapp/preview/`:

- `Previews.kt`: ein Bild pro Bildschirm (Heute, Heute leer, Familie, Demnächst, Suchen, Einstellungen, Willkommen, Erfassen in drei Zuständen) und für Karten, Zeilen und Bausteine, jeweils **hell und dunkel**.
- `PreviewData.kt`: Beispielfamilie (Nils, Lisa, Mia, Oma Gerda) mit Einträgen. „Heute“ ist fest Montag, 23.11.2026, damit „überfällig“ immer gleich aussieht. Hier Titel, Namen oder Daten ändern, um andere Fälle zu sehen.

So geht's:

1. `Previews.kt` öffnen, oben rechts im Editor **Split** wählen.
2. Nach einer Änderung im UI-Code oben im Vorschaufenster auf **Build & Refresh** (Kreispfeil) klicken. Kleine Änderungen (Farben, Texte, dp) erscheinen oft sofort.
3. Bequem arbeiten: Tab von `Previews.kt` rechts anklicken → *Split Right*. Links z. B. `Screens.kt` bearbeiten, rechts die Vorschau sehen.
4. **Interaktiver Modus**: an einem Vorschaubild auf das Symbol „Start Interactive Mode“ klicken. Dann kann man Karten wischen, Chips antippen usw. Kamera und Mikrofon gibt es in der Vorschau nicht.

Warum in `androidMain` und nicht neben dem UI-Code? Android Studio zeigt Vorschauen aus `androidMain` zuverlässig an; Vorschauen in `commonMain` gehen mit unserer Compose-Version noch nicht stabil. Die Vorschauen rufen trotzdem genau den gemeinsamen UI-Code auf, iOS bleibt unberührt.

Wenn die Vorschau „Render problem“ meldet: erst *Build → Make Project*, dann Refresh. Hilft das nicht, Fehlermeldung kopieren und im Thread schicken.

## 4. Auf dem eigenen Handy ausprobieren

Android Studio baut eine **Debug-Version**. Sie heißt auf dem Handy **„FamilyApp Dev“**, hat eine eigene App-ID (`…familyapp.debug`) und eigene Daten. Deshalb:

- Sie läuft **neben** der echten FamilyApp, es gibt keinen Signaturkonflikt und die echte App und ihre Daten bleiben unangetastet.
- Sie bekommt keine Updates über den Update-Kanal und startet leer (eigenes Profil anlegen).

Einrichten (einmalig):

1. Am Handy *Einstellungen → Über das Telefon → Build-Nummer* 7× antippen → Entwickleroptionen sind aktiv.
2. *Einstellungen → System → Entwickleroptionen → USB-Debugging* einschalten (oder *Kabelloses Debugging*, dann in Android Studio *Device Manager → Pair Devices Using Wi-Fi*).
3. Handy per USB anschließen, Zugriff am Handy erlauben.

Starten: oben in der Werkzeugleiste Konfiguration **androidApp** und das Handy wählen, dann ▶ **Run**. Nach Änderungen erneut ▶ (oder *Apply Changes*, Blitz-Symbol).

Die richtige, signierte App baut weiterhin nur GitHub Actions (ADR 0004). Bitte keine eigenen Release-Builds aufs Handy spielen.

## 5. Änderungen zurückgeben (Branch + Pull Request)

Alles direkt in Android Studio, unten rechts ist der Git-Branch angezeigt.

1. **Vor dem Ändern**: Git-Widget unten rechts → `main` → *Update* (holt den neuesten Stand). Dann *New Branch…*, Name z. B. `ui/heute-abstaende`.
2. Ändern, in der Vorschau und auf dem Handy prüfen.
3. **Commit**: `Strg+K`, geänderte Dateien ankreuzen, Nachricht auf Englisch (z. B. `Increase spacing on today screen`), *Commit*.
4. **Push**: `Strg+Umschalt+K` → *Push*. Beim ersten Mal fragt Android Studio nach dem GitHub-Login (*Log in via GitHub*).
5. Auf github.com im Repo erscheint „Compare & pull request“ → anklicken → *Create pull request*. Alternativ im Thread einfach den Branch-Namen nennen, dann übernimmt Claude den PR und prüft den CI-Lauf.

Der PR startet automatisch den GitHub-Actions-Lauf (Tests, APK, Emulator-Test). Erst wenn der grün ist, mergen.

Tipps gegen Konflikte:

- Kleine Branches, ein Thema pro Branch, zügig mergen.
- Wenn parallel ein Claude-Thread an denselben Dateien arbeitet (z. B. Version 0.3), vorher kurz Bescheid geben; Konflikte entstehen vor allem in `Screens.kt` und `Cards.kt`.
- Nie direkt auf `main` committen.
