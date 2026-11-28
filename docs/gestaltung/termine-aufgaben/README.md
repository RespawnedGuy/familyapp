# Termine und Aufgaben: ein Kalender, ein Stapel

- Status: Konzept, wartet auf Nils' Wahl (T1–T5)
- Datum: 2026-11-28
- Ansicht mit Mockups: [termine-aufgaben.html](termine-aufgaben.html), Bilder [demnaechst.png](demnaechst.png), [konzept1.png](konzept1.png), [konzept3.png](konzept3.png)
- Anlass: Nils' Kommentare auf der Optik-Seite (28.11.2026): „Wir haben damit 2 Kalender … doppelt als auch redundant“ und „Statt Spalten, die viel zu eng sind, nutzen wir Farben, Buchstaben und Profilbilder (optional).“

## Modell

| | 1 · Termin, Frist, Start | 2 · Handlung, Aufgabe, Routine |
| --- | --- | --- |
| Kern | fester Zeitpunkt, meist von außen | tun, wenn Zeit oder Gelegenheit ist |
| Frage | Woran muss ich bald denken? | Was kann ich jetzt erledigen? |
| Ansicht | Kalender (Demnächst) | Stapel (Familie › Aufgaben, Heute › Erledigen) |
| Regel | Uhrzeit/„Termin“ → Termin; „ab“, „Start“ → Start | sonst Handlung; „bis …“ → Frist; „jeden“, „alle 2 Wochen“ → Routine |

Brücke: Eine Handlung mit Frist lebt im Stapel und steht im Kalender am Fristtag als eigene Zeile „Frist“ mit Titel.

Heute kennt ein Eintrag nur `due: LocalDate` (`domain/Model.kt`). Neu nötig: Art, Uhrzeit (optional Ende), Startdatum, Wiederholung, grober Aufwand. Bestehende Einträge mit Datum werden Handlungen mit Frist.


## Demnächst: Liste oder Kacheln (Nils, 28.11.2026)

Nils: „Der Kalender mit Fähnchen funktioniert nicht. Ich möchte Termine direkt sehen.“ Daher keine Fähnchen und keine Monatsübersicht, die erst Antippen verlangt. Oben ein Umschalter, die App merkt sich die Wahl.

- **Liste:** alle Termine, Tage als Überschrift (Heute, Morgen, Wochentag), darunter nach Uhrzeit.
- **Kacheln:** die nächsten 7 Tage in zwei Spalten groß, bis 5 Einträge je Tag, sonst „+x weitere“. Danach bis Monatsende drei Spalten, je 3 Einträge, sonst „+x weitere“.
- Jeder Eintrag: Zeile 1 Uhrzeit (oder „ganztags“, „Frist“ rot, „Start“ grün) und alle Beteiligten als Farbpunkt mit Initiale, überlappend; Zeile 2 Titel, bis zu zwei Zeilen, nicht abgeschnitten.
- Sortierung je Tag: ganztägig, Frist und Start zuerst, dann nach Uhrzeit.
- Tippen auf eine Kachel öffnet den Tag als Liste.
- Schriftgrößen in der App: Kacheln mindestens 12 sp (groß) und 11 sp (klein); ab 130 % Systemschrift nur eine Spalte. Im Mockup ist die Schrift wegen des Rahmens kleiner.

## Konzepte

1. **Feste Orte plus Linse auf Heute (empfohlen).** Demnächst = einziger Kalender; Familie › Aufgaben = Stapel ohne Spalten, Personen als Farbe, Initiale, optional Foto; Heute mit Linse Überblick · Erledigen · Planen, Vorwahl: bis 10 Uhr Überblick, sonst Erledigen, wenn in 2 Std. kein Termin. Taps: morgens 0, zu Hause 0–1, Zahnarzt 1.
2. **Nur feste Orte.** Heute immer Überblick. Taps: 0 / 2 / 1. Einfacher.
3. **Zeitfenster (später).** Heute als Ablauf, Handlungen füllen freie Fenster nach Aufwand. Taps 0 / 0 / 1, braucht Dauer und Termin-Ende.

## Entscheidungen (Vorschlag)

| # | Frage | Vorschlag |
| --- | --- | --- |
| T1 | Zwei Arten im Modell | ja |
| T2 | Demnächst | einziger Kalender: Liste oder Kacheln, Termine ohne Antippen lesbar |
| T3 | Familie › Aufgaben | Stapel ohne Spalten, Personenreihe oben (Lastkarte) |
| T4 | Heute | Linse mit Vorwahl nach Uhrzeit und nächstem Termin |
| T5 | Erkennung | deterministische Regel, unsicher fragt die Karte |

Die Lastkarte zählt weiter nur feste Fristen und Termine (20.11.2026).
