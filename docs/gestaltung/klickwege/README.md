# Klickwege Küche: Essen planen, Einkaufsliste, Erfassen, Markt

- Status: Vorschlag, wartet auf Nils' OK (K1–K4)
- Datum: 2026-11-28
- Ansicht mit Mockups: [klickwege-kueche.html](klickwege-kueche.html), Bild: [mockups.png](mockups.png) (Stil: Richtung A organisch)
- Anlass, Nils 28.11.2026: „möglichst wenige Klicks zum Ziel und geführte Bewegungen. Ein Essen planen muss schnell gehen, es einem Einkauf hinzufügen ebenfalls. Dinge auf die Einkaufsliste setzen mit Erfassen. Gleichzeitig müssen wir angeben, wo etwas gekauft werden muss, nach Angeboten.“

**Ziel:** Essen planen, Dinge auf die Liste setzen und den Markt festlegen gelingen jeweils in höchstens zwei Taps ab Heute.

**Abnahmekriterien:**
1. „Milch, Eier und Backpapier“ über Erfassen ergibt drei Listeneinträge mit Markt, ohne weiteren Tap (Unit-Test der Regel, Emulator-Test im CI).
2. Ein vorgeschlagenes Essen ist mit 2 Taps ab Heute geplant, fünf Abende mit höchstens 6.
3. Jeder offene Listeneintrag hat einen Markt; ein passendes Angebot gewinnt, sonst Stammmarkt (Unit-Test).

## Gezählt: heute und vorgeschlagen

| Aufgabe | Heute im Code | Taps | Vorschlag | Taps |
| --- | --- | --- | --- | --- |
| Vorgeschlagenes Essen planen | „3 Essensvorschläge ansehen“ → Vorschlag → Tag → Übernehmen (`OffersScreens.kt`) | 4 | freier Tag in der Wochenleiste → Gericht | 2 |
| Eigenes Rezept planen | nicht möglich; Rezeptseite kann nur „auf die Einkaufsliste“ ohne Tag (`RecipeScreens.kt:225`) | – | freier Tag → „Anderes Rezept suchen“ → Treffer | 3 |
| Fünf Abende planen | fünfmal oben | ≈ 20 | geführt, Blatt springt zum nächsten freien Tag | 6 |
| Zutaten auf die Liste | automatisch beim Planen (`planAndAdd`) | 0 | bleibt, jetzt mit Markt | 0 |
| Eine Sache auf die Liste | Familie → Einkauf → scrollen → Feld → Hinzufügen (`ShoppingScreen.kt`) | 4 | Erfassen, sprechen | 1 |
| Drei Sachen auf die Liste | dreimal Feld + Hinzufügen | 8 | Erfassen, sprechen | 1 |
| Markt festlegen | nicht möglich; nur Einträge aus einem Angebot tragen einen Hinweis (`ShoppingEntry.offer`) | – | automatisch; ändern per Tap auf den Markt | 0 (2) |
| Liste im Laden öffnen | Familie → Einkauf → an Vorschlägen vorbei | 2+ | Zeile auf Heute | 1 |

Erfassen speichert heute immer als Aufgabe in den Eingang (`AppModel.save`).

## Vorschlag

1. **Erfassen versteht Einkauf (K1).** Deterministische Regel: Signalwörter („kaufen“, „besorgen“, „Einkauf“, „auf die Liste“) oder alle Teile sind bekannte Lebensmittel (Zutaten der eigenen Rezepte, Saisonkalender, Angebotstitel). Teilt bei Komma und „und“, zeigt 1 s Vorschau, speichert ohne Tap auf die Liste; „Ist eine Aufgabe“ schickt es in den Eingang. Unsicher → Eingang wie heute, Karte bekommt „Auf die Einkaufsliste“. Rückgängig wie immer.
2. **Markt nach Angebot (K2).** Jeder offene Eintrag wird mit den Angeboten der gewählten Märkte abgeglichen (gleicher Abgleich wie die Essensvorschläge), der günstigste gewinnt. Ohne Angebot: zuletzt für diese Sache gewählter Markt, sonst Stammmarkt (Einstellungen › Einkauf, Vorgabe erster Markt). Läuft ein Angebot ab, fällt der Eintrag zurück. Tap auf den Markt → Auswahl mit Preisen; Rückmeldung bietet „Künftig immer bei …?“ an.
3. **Liste nach Markt (K3).** Tab Einkauf: oben „Ich bin bei …“ (zeigt nur diesen Markt), Liste nach Markt gruppiert mit Preis und Summe, darunter Wochenleiste, darunter alle Angebote. Ändert die Reihenfolge vom 27.11. (Vorschläge oben).
4. **Geführtes Planen (K4).** Wochenleiste (7 Tage) auf Heute und im Tab Einkauf. Freier Tag → Blatt mit Vorschlägen nach Angebot und Saison, zuletzt Gekochtem und Suche, Portionen vorbelegt. Antippen plant, setzt Zutaten mit Markt auf die Liste und springt zum nächsten freien Tag. „Planen“ zusätzlich auf jeder Rezeptseite.

Fest bleibt: Navigation A, keine Wischgesten, keine Vibration, Erfassen startet sofort Sprache, Bot-Abwehr wird nie umgangen (nur die schon erlaubten Märkte).

## Umsetzung in Stufen

| Stufe | Inhalt | Schichten | Tests |
| --- | --- | --- | --- |
| 1 | Erfassen → Liste, Markt nach Angebot, Liste nach Markt | domain (`ShoppingCaptureRule`, `MarketAssignment`), data (`ShoppingEntry.market`, abwärtskompatibel), presentation, ui | Unit-Tests Regel und Zuordnung, Emulator „Milch, Eier und Backpapier“ |
| 2 | Wochenleiste, geführtes Planen, „Planen“ auf der Rezeptseite | presentation (`MealActions`), ui | Unit-Tests „nächster freier Tag“, Tap-Zählung im Emulator |
| 3 | Optik A organisch (Theme, Schrift Rubik, Formen) | ui, Penpot-Tokens | `:shared:jvmTest` Token-Abgleich, Bildschirmfotos 100/200 % |

Neues Eingabeformat (gesprochene Einkaufsliste) wird im Bedrohungsmodell nachgetragen (Länge, Anzahl Teile begrenzen).
