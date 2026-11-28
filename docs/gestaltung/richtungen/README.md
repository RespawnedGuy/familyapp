# Optik-Richtungen: weg vom generischen Look

- Status: **Gewählt: A, organisch** (Nils, 28.11.2026: „A ist besser, aber sehr kantig, mach es organischer“). Dazu entschieden: keine Wischgesten, nur Knöpfe; „Irrelevant“ heißt „Löschen“.
- Datum: 2026-11-28
- Ansicht mit Mockups: [optik-richtungen.html](optik-richtungen.html) (im Browser öffnen), Bilder: [ist.png](ist.png), [dir-a.png](dir-a.png), [dir-b.png](dir-b.png), [dir-c.png](dir-c.png), gewählt: [dir-a-organisch.png](dir-a-organisch.png)
- Grundlage: [optik-haptik-klickwege.md](../optik-haptik-klickwege.md), `app/shared/.../ui/Theme.kt`, [Penpot-Tokens](../penpot/familyapp-tokens.json)

Nils (28.11.2026): „Die Optik ist sehr generisch und wenig intuitiv.“ Dieses Dokument hält Befund, Grundsätze und drei Richtungen fest. Es ändert noch keinen Code.

## Befund

Gezählt nach dem pixelslop-Katalog für generische KI-Oberflächen (1 bis 3 Punkte je Muster, ab 4 „schlampig“): **9 Punkte**.

| Muster | Wo im Code | Pkt. |
| --- | --- | --- |
| Karte um alles | Jede Fläche ist `clip(RoundedCornerShape(16.dp)).background(c.card)` | 2 |
| Redundanter Erklärtext | `family_load_hint`, `family_people_hint`, „Regel: …“ auf jeder Eingangskarte | 2 |
| Flache Schriftskala | 12 · 13 · 14 · 15 · 17 · 18 sp, Sprünge um Faktor 1,1 | 1 |
| Standardschrift | Systemschrift | 1 |
| Generischer Schatten | Erfassen-Knopf `shadow(6.dp)` | 1 |
| Bekannte Farbwelt | Creme-Grund #F6F5F2 mit Petrol-Akzent #0E6B66 | 1 |
| Pillen überall | Tabs, Zähler, Chips, Knöpfe 12–16 dp rund | 1 |

Gewichtiger als die Optik: „Heute“ zeigt kein Datum und keine Rangfolge; eine Eingangskarte hat sechs Zeilen und vier gleich laute Bedienelemente; Personen erscheinen nur als 20-dp-Punkte.

## Grundsätze

1. Ein Ordnungsprinzip pro Bildschirm.
2. Hierarchie durch Schrift und Raum (Sprünge 1,5–2×), Flächen nur für eigene Objekte.
3. Ein Primärknopf pro Ansicht (Hick-Hyman).
4. Personenfarben sind Material: sie dürfen Flächen füllen (D6 bleibt).
5. Datum, Wochentag und KW stehen immer oben (Nielsen 6, Wiedererkennen).
6. Form statt Erklärtext: gestrichelt = offen, rot = überfällig; Regelbegründung erst beim Antippen.
7. Ruhe als Funktion: keine Verläufe, kein Glow, keine Gamification.
8. WCAG 2.2 AA, 48 dp, 200 % Schrift. Eine eingebundene Schrift skaliert in Compose über `sp` genauso wie die Systemschrift.

Fest bleiben: Navigation A mit Erfassen in der Mitte, keine Vibration, feste Farben, die fünf Familie-Tabs, die vier Personenfarben mit Initiale.

## Drei Richtungen

| | A Familienplaner | B Eins nach dem anderen | C Tageslauf |
| --- | --- | --- | --- |
| Ordnet nach | Personen (Wandkalender mit Spalte je Person) | Entscheidungen (eine Karte groß, Rest als Stapel) | Zeit (Achse mit Jetzt-Linie) |
| Mental-Load-Phase (Daminger 2019) | überwachen, verteilen | entscheiden | vorausdenken |
| Schrift | Archivo, schmal, Großbuchstaben | Atkinson Hyperlegible Next | Instrument Sans, tabellarische Ziffern |
| Farbe | Tinte #14213D auf Weiß, Personenfarben als Flächen | kühles Grau #E9EDF2, Tinte #101828, Marker-Gelb #FFD84D für Fehlendes | Graugrün #F2F4F0, Signalblau #1F4FD1 |
| Kontrast Text | 16,0 : 1 | 15,1 : 1 | 14,4 : 1 |
| 200 % Schrift | Raster wird Liste | problemlos | Spuren werden Liste |
| Umbau | hoch | mittel | hoch |

Alle Kontraste nach der WCAG-Formel für relative Leuchtdichte nachgerechnet, alle Paare ≥ 4,5 : 1 (weiß auf Personenfarben 5,3–6,9, Tinte auf Gelb 12,8, Rot auf Grund ≥ 5,6). Alle Schriften stehen unter der SIL Open Font License.

**Empfehlung:** B als Grundlage, für Familie › Aufgaben das Wochenraster aus A, die Zeitachse aus C später für Demnächst. Unabhängig davon sofort sinnvoll: Datum und KW auf Heute, Erklärtexte streichen, Regelbegründung erst beim Antippen, ein lauter Knopf pro Karte, eigene Schrift.

## Gewählt: A, organisch

Gleiche Ordnung wie A (Spalte je Person), aber weich:

| | A (kantig) | A organisch |
| --- | --- | --- |
| Schrift | Archivo, schmal, Großbuchstaben | Rubik, normale Schreibweise |
| Spalten | Rasterlinien | durchgehende helle Farbspuren je Person, runde Köpfe |
| Formen | eckig | Radien 10–20 px, Erfassen als abgerundetes Quadrat |
| Personen | farbiges Rechteck mit Initiale | Kreis mit Initiale, Offenes als gestrichelter Kreis „?“ |
| Überfällig | roter Rahmen | weiches rotes Etikett mit Text |
| Farben | Tinte #14213D auf Weiß | Tinte #1E2333 auf #FBFAF8, Spuren #E3EAF6 · #F5E6DB · #EEE3F4 · #E0EFE5, Rot #A3241C auf #FBE4E1 |

Kontrast nachgerechnet: Text auf allen Spuren ≥ 12,6 : 1, Sekundärtext #545A6B ≥ 5,5 : 1, Rot ≥ 6,0 : 1, Weiß auf Tinte 15,6 : 1.

## Prüfung

1. Fünf-Sekunden-Test, fünf Personen je Richtung: „Was musst du heute tun? Wer hat am meisten offen?“ Ziel 4 von 5.
2. Erster Klick für „Lisas Aufgabe übernehmen“ und „Elternabend bestätigen“, Ziel 4 von 5.
3. Präferenz mit einem Satz Begründung.
4. Slop-Zählung nach dem Umbau wiederholen, Ziel ≤ 2 Punkte.
5. Neue Tokens in `familyapp-tokens.json` und `Theme.kt`, Kontrolle über `:shared:jvmTest`; Bildschirmfotos bei 100 und 200 % aus dem Emulator-Lauf.

## Quellen

- Anthropic: Improving frontend design through skills (12.11.2025), https://claude.com/blog/improving-frontend-design-through-skills
- pixelslop: AI Slop Pattern Catalog v0.3.8, https://cdn.jsdelivr.net/npm/pixelslop@0.3.8/dist/skill/resources/ai-slop-patterns.md
- Impeccable: Anti-Patterns, https://www.mintlify.com/pbakaus/impeccable/concepts/anti-patterns
- Nielsen (1994), Hick (1952) / Hyman (1953), Gestaltgesetze, Daminger (2019), Masicampo & Baumeister (2011): aus dem Gedächtnis, vor Zitat prüfen.
