# Angebote, Saison und Essensvorschläge – Konzept

Stand: 27.11.2026 · Status: **angenommen** (PR #50), Stufe 1a umgesetzt · baut auf [kochbibliothek.md](kochbibliothek.md) auf

## Umsetzungsstand

**Stufe 1a (27.11.2026), ohne Netz, im Qualitätstor getestet:**

- Saisonkalender `domain/offers/SeasonCalendar.kt`: 52 Arten mit Saison und Lager, Schlüssel entstehen aus dem Namen wie bei Rezeptzeilen, Komposita („Hokkaidokürbis“) finden den längsten Eintrag, Doppelgänger wie Knoblauch, Schnittlauch, Kichererbsen zählen nie. Neue Synonyme in `IngredientKey`: Äpfel/Apfel, Karotte/Möhre, Porree/Lauch, Zwetschge/Pflaume, Rote Beete/Bete, Paprikaschote/Paprika.
- Abgleich `OfferMatch`: Kompositum-Regel, Verarbeitungs-Sperre (Joghurt, Saft, Mark, Sauce, Pflanzen …), „Passt nicht“-Schlüssel. Tests mit Titeln nach dem Muster echter Angebote („Reis“ ist kein Eis, „Seelachs“ als bekannter Fehlgriff, den „Passt nicht“ abfängt).
- Vorschläge `Suggestions`: Punkte, Begründungen, Ausschluss (21 Tage, geplant, unbestätigt), Zutatenvorschläge, Gültigkeit der Angebote.
- Essensplan: `PlannedMeal` als zusätzliches Feld in Daten und Abgleich (Schema bleibt 2), `MealPlanRepository` (planen, verschieben, entfernen, beim Kochen abhaken), `MealPlan` (heute, demnächst, nächster freier Tag).
- „Wann? / Für wie viele?“ als Aktion `ShoppingActions.planAndAdd`: Liste und Plan in einem Schritt, ein Rückgängig für beides.
- Zuletzt genutzte Personenzahl pro Handy (`Settings.lastServings`), Vorgabe beim Öffnen eines Rezepts; Rezepte ohne Portionsangabe starten immer bei 1 und ändern die Vorgabe nicht.

**Noch nicht drin (Stufe 1b):** Abruf und Parser der Märkte, Einstellungen › Einkauf mit PLZ-Tabelle, Oberfläche (Vorschläge unter Heute, Blatt „Wann/Wie viele“, Plan unter Heute und Demnächst).

## Kurzfassung

Unter **Einstellungen › Einkauf** gibt man einmal eine Adresse ein und hakt die Märkte an, in denen die Familie einkauft. Die App findet über die Filialsuchen der Märkte selbst die nächste Filiale, holt beim Öffnen einmal pro Woche deren Angebote und gleicht sie mit einem festen Saisonkalender und den Zutaten der eigenen Rezepte ab. Unter **Heute** liegen dann beim App-Start drei bis fünf Vorschläge mit Begründung („Hackfleisch bei Aldi −24 %, Grünkohl hat Saison“). Ein Tipp fragt **wann** und **für wie viele** (vorbelegt mit der zuletzt genutzten Zahl), dann landen die Zutaten auf der Einkaufsliste und das Gericht steht an dem Tag im Plan. Es gibt keine Benachrichtigung, keinen Server und keine Cloud-KI.

Was dieses Konzept anders macht als ein Feature-Entwurf:

- **Jede Datenquelle ist am 27.11.2026 live abgerufen**, nicht aus dem Gedächtnis oder aus `preisvergleich` übernommen: Aldi Süd, Aldi Nord, Lidl, Kaufland, Nominatim-Regeln, Overpass, GeoNames, BZfE. Dabei kamen zwei Annahmen des Konzepts vom 21.11. ins Wanken (Lidl-Lebensmittel stehen nicht in der Prospekt-Schnittstelle; Overpass ist als App-Rückgrat nicht verlässlich), und eine bessere Quelle kam dazu (Aldi Süd liefert Angebote je Filiale als sauberes JSON).
- **Die Adresse verlässt das Handy nicht.** Statt sie an einen Geocoder zu schicken, rechnet die App nur mit der Postleitzahl und einer mitgelieferten PLZ-Tabelle. Nach außen geht höchstens der Mittelpunkt des PLZ-Gebiets.
- **Es endet mit Prüfungen, die nicht von mir abhängen:** ein Abruftest auf Nils' echtem Handy (die Instagram-Erfahrung zeigt, dass Abrufe aus der Cloud nichts über das Handy sagen), ein Abgleichtest gegen eine echte Angebotswoche, und ein Abgleich des Saisonkalenders mit dem BZfE-Poster.

## Bezug zum Mental Load

„Was essen wir diese Woche?“ ist reine Antizipations- und Entscheidungsarbeit (Phase 1 bis 3 aus [KONZEPT.md](KONZEPT.md)): Jemand muss Prospekte im Kopf haben, wissen, was gerade Saison hat, und das mit den Rezepten der Familie verbinden. Die App übernimmt das Suchen und Verbinden, die Entscheidung bleibt beim Menschen (HITL), und das Ergebnis ist sofort für alle sichtbar: Einkaufsliste und Essensplan sind gemeinsam.

Messgröße wie in der Kochbibliothek: Anteil der geplanten Gerichte, die aus einem Vorschlag kamen, und wer sie geplant hat.

## Rohmaterial: Was die Quellen am 27.11.2026 wirklich liefern

Alle Abrufe liefen im eingebauten Browser auf Nils' Rechner (normaler Heimanschluss), weil die Cloud-Umgebung keine dieser Seiten erreicht.

| Markt | Quelle | Angebote je Filiale? | Inhalt | robots.txt |
| --- | --- | --- | --- | --- |
| **Aldi Süd** | JSON-Schnittstelle der Website `asl.api.aldi-sued.de/commerce/v3/product-search`, Kategorie „Wochenangebote“, Parameter `servicePoint` = Filialnummer | **ja**: 111 bis 121 Angebote je Filiale (vier Filialen in vier Bundesländern verglichen), gleicher Kern, regionale Unterschiede | Name, Marke, Preis in Cent, Streichpreis, Ersparnis („24 %“), Kategorien („Frischeprodukte im Angebot“, „Hackfleisch“) | erlaubt (`/tools` und Suche gesperrt, Schnittstelle liegt auf eigener Subdomain) |
| Aldi Süd, Filialen | Filialsuche über den Dienstleister Uberall (`uberall.com/api/storefinders/…/locations/all`), von aldi-sued.de selbst geladen | – | 2.024 Filialen mit Nummer (`identifier`, z. B. „B918“ = `servicePoint`), Koordinaten, Adresse | – |
| **Aldi Nord** | `__NEXT_DATA__`-JSON in `aldi-nord.de/angebote.html` (wie `aldi.py`) | nein, bundesweit | 257 Aktionspreise mit Gültigkeit von/bis, Grundpreis, Streichpreis | erlaubt |
| **Kaufland** | Server-gerendertes HTML von `filiale.kaufland.de/angebote/uebersicht.html` | **ja**, die Seite zeigt die Angebote einer Filiale; ohne Auswahl ordnet Kaufland nach IP-Standort zu | Name, Menge, Grundpreis, Preis, alter Preis, Rabatt, Kategorien („Obst, Gemüse, Pflanzen“, „Fleisch, Geflügel, Wurst“), Gültigkeit je Tag | Übersicht erlaubt; `/*.klstorefinder*` und Angebots-Detailseiten gesperrt |
| Kaufland, Filiale | `/.klstorebygeo.json?lat=…&lng=…` liefert die nächste Filiale mit Nummer (geprüft mit einem öffentlichen Platz: Berlin, „DE8920“) | – | Filialnummer, Name, Öffnungszeiten | erlaubt (nicht gesperrt) |
| **Lidl** | Prospekt-Schnittstelle `endpoints.leaflets.schwarz/v4/flyer` (wie `lidl.py`) | regional („region“ im Prospekt, mehrere Fassungen derselben Woche) | **Nur Non-Food als Produkte** (184 Produkte im Aktionsprospekt KW 48, alle aus Haushalt, Garten, Wein …). Lebensmittel stehen nur als Seitenbild im Prospekt, dazu je Seite Schlagwörter aus der Texterkennung („Avocado, Spitzkohl, Bio Gurken, Zucchini“) ohne Preise und Rezept-Links („rezepte.lidl.de/…?q=Spitzkohl“) | `*id=*` und `*search?q=*` gesperrt, Prospekt-Schnittstelle auf eigener Domain |
| REWE | – | – | Bot-Schutz (siehe kochbibliothek.md, Empfehlung 10) | – |

### Grundsatz: nur Märkte, die wir automatisch lesen dürfen (Nils, 27.11.2026)

Technische Bot-Abwehr wird nie umgangen. Ein Markt kommt nur in die App, wenn **alle** Punkte erfüllt sind, sonst lassen wir ihn weg:

1. Die benutzten Pfade sind in der `robots.txt` nicht gesperrt.
2. Die Nutzungsbedingungen enthalten keinen Vorbehalt gegen automatisches Auslesen (§ 44b Abs. 3 UrhG, maschinenlesbar oder ausdrücklich).
3. Der Abruf klappt mit einer normalen HTTP-Anfrage der App, ehrlich als App gekennzeichnet. Kein Captcha, keine JavaScript-Prüfung, kein Nachahmen eines Browser-Fingerabdrucks, keine unsichtbare WebView, kein Wechsel der Kennung, wenn eine Anfrage abgelehnt wird.
4. Wird ein Markt später abgelehnt (HTTP 403, 429, Challenge-Seite), schaltet die App ihn ab und zeigt „Kaufland lässt sich gerade nicht automatisch lesen“. Es gibt keinen zweiten Versuch mit anderen Mitteln.

Das ist strenger als beim Instagram-Abruf (dort rettet eine WebView den Abruf, ADR 0007): Für Angebote gibt es diesen Rückfall ausdrücklich nicht. Deshalb ist Prüfung 1 auf dem echten Handy ein Tor: Ein Markt, der dort nur mit Tricks antwortet, fällt raus. REWE fällt schon jetzt raus.

Folgen für das Konzept:

1. **Aldi Süd ist die beste Quelle**, nicht Aldi Nord: Filialgenau, strukturiert, mit Ersparnis. Aldi Nord bleibt bundesweit, das reicht für Rezeptvorschläge.
2. **Lidl liefert für Lebensmittel nur „ist im Angebot“, keinen Preis.** Für einen Rezeptvorschlag genügt das („Spitzkohl ist bei Lidl im Angebot“). Preise aus den Seitenbildern per Texterkennung auf dem Handy (ML Kit) wären möglich, sind aber eine eigene Messung wert und kommen nicht in Stufe 1. Vor dem Bau gegen die Ausgabe von `lidl.py` prüfen, ob es dort einen anderen Weg gibt, den ich nicht sehe.
3. **Kaufland:** Der Weg von der Filialnummer zur filialgenauen Angebotsseite ist noch offen (die Auswahl läuft im Browser über ein Skript). Rückfall, der heute schon funktioniert: Das Handy ruft die Seite vom Heimanschluss ab, Kaufland ordnet nach IP-Standort zu. Unterwegs im Mobilfunk kann das danebenliegen, deshalb nur im WLAN abrufen oder die Filiale prüfen, die die Seite nennt.
4. **Alle drei Quellen sind undokumentierte Website-Schnittstellen.** Sie können sich jede Woche ändern. Deshalb: ein Adapter je Markt, jeder mit eigenem Test gegen eine gespeicherte (gekürzte, erfundene) Antwort, und ein sichtbarer Zustand je Markt („Aldi Süd: 118 Angebote, Kaufland: Abruf fehlgeschlagen“). Fällt ein Markt aus, laufen die anderen weiter.

### Adresse und Händlersuche

| Weg | Was nach außen geht | Bewertung |
| --- | --- | --- |
| Nominatim (OSM-Geocoder) | die Adresse | Nutzungsregeln: „Please do not submit personal data“, App muss den Dienst „ohne Software-Update“ wechseln können, Autovervollständigung verboten ([Nominatim Usage Policy](https://operations.osmfoundation.org/policies/nominatim/), abgerufen 27.11.2026). Eine Wohnadresse ist personenbezogen. **Nein.** |
| Android-Geocoder | die Adresse an Google | widerspricht „Cloud nicht vertrauenswürdig“. **Nein.** |
| Overpass (OSM-Abfrage „Supermärkte im Umkreis“) | Koordinaten | Die Betreiber nennen ausdrücklich „Setting up an app for more than just OSM mappers and relying on the public instances as backend“ als Fehlgebrauch ([Overpass-Handbuch, Commons](https://dev.overpass-api.de/overpass-doc/en/preface/commons.html)). Am 27.11. antwortete overpass-api.de mit HTTP 502, ein Spiegel lief in die Zeitüberschreitung. **Nur als spätere Option.** |
| **PLZ-Tabelle in der App** (Empfehlung) | nichts | GeoNames-Postleitzahlen für Deutschland mit Mittelpunkt-Koordinaten, Lizenz CC BY 4.0, Namensnennung in „Über die App“ ([GeoNames readme](https://download.geonames.org/export/zip/readme.txt)). Etwa 8.000 Zeilen, gepackt rund 100 kB. |

Die Märkte selbst kennen ihre Filialen besser als OSM, und für die Angebote braucht es ohnehin **deren** Filialnummer, keine OSM-Kennung. Also:

- **Aldi Süd:** Filialliste (Uberall) einmal laden und zwischenspeichern, nächste Filiale zum PLZ-Mittelpunkt. Liegt keine Aldi-Süd-Filiale im Umkreis von 15 km, ist es Aldi-Nord-Gebiet.
- **Kaufland:** `klstorebygeo` mit dem PLZ-Mittelpunkt.
- **Lidl, Aldi Nord:** bundesweite bzw. regionale Prospekte, keine Filiale nötig.
- Die App zeigt die gefundene Filiale mit Ort und Entfernung („Kaufland Musterstadt, ca. 3 km“). Stimmt sie nicht, wählt man eine andere aus der Liste (HITL statt Raten).

Die Adresse selbst wird gespeichert, wie Nils sie eintippt, damit man sie wiedererkennt, benutzt wird nur die PLZ. Wer nur die PLZ eintippt, bekommt dasselbe Ergebnis.

## Saisonkalender

Eine feste Tabelle im Code, je Zutat-Schlüssel und Monat eine von drei Stufen:

- **Saison**: Ernte aus heimischem Anbau (Freiland oder geschützter Anbau), Beispiel Grünkohl im November.
- **Lager**: heimische Ware aus dem Lager, Beispiel Möhren, Äpfel, Kartoffeln im Winter. Zählt halb.
- nichts.

Ganzjährige Zuchtware (Champignons) und Südfrüchte sind nicht drin, weil sie kein Signal sind; Südfrüchte mit Saison im Anbauland können später als vierte Stufe kommen.

**Lizenz:** Die BZfE-Infografiken stehen unter CC BY-ND 4.0, das Poster „Der Saisonkalender“ (Art.-Nr. 3488) unter „© BLE, Nutzung nur mit vorheriger Zustimmung“ (beides aus den PDFs vom 27.11.2026). Erntemonate sind Tatsachen und als solche nicht geschützt; die App übernimmt deshalb weder Grafik noch Tabelle des BZfE, sondern hat eine eigene Zusammenstellung, die gegen das BZfE-Poster geprüft wird (Prüfung 3). Quelle wird in „Über die App“ genannt.

Die Tabelle umfasst in Stufe 1 rund 50 Gemüse- und Obstarten, deren Schlüssel genau die sind, die `IngredientKey` aus Rezeptzeilen macht („Möhren“ → `möhre`, „Kürbis“ → `kürbis`). Ein Test stellt sicher, dass jeder Tabellen-Schlüssel aus seinem eigenen Namen entsteht, sonst findet der Abgleich nie etwas.

## Abgleich Angebot → Zutat

Produktnamen sind anders gebaut als Rezeptzeilen: „Deutsches Rinderhackfleisch 500 g“, „Eisbergsalat, Stück“, „Erdbeer-Joghurt 4 x 125 g“. Die letzte Wortregel aus `IngredientKey` greift dort nicht. Deshalb ein eigener, kleiner Abgleich:

1. Nur Lebensmittel-Kategorien des Marktes (Aldi Süd: „Frischeprodukte“, „Fleisch & Fisch“ …; Kaufland: „Obst, Gemüse“, „Fleisch, Geflügel, Wurst“ …). Bei Lidl nur Schlagwörter von Seiten mit Lebensmitteln.
2. Menge, Einheit, Packungsangaben und Marke entfernen, jedes Wort auf den Stamm bringen.
3. Ein Angebot passt zu einem Zutat-Schlüssel, wenn ein Wort gleich ist oder auf den Schlüssel endet (Kompositum „Rinderhackfleisch“ endet auf „hackfleisch“).
4. **Verarbeitungs-Sperre** wie in `preisvergleich` (`gui.py`, „Tee statt Minze“): Steht ein Wort wie Joghurt, Saft, Tee, Chips, Eis, Fruchtgummi, Aufstrich dabei, zählt es nicht als frische Zutat. „Erdbeer-Joghurt“ ist kein Erdbeer-Angebot.
5. **Korrigierbar:** Auf der Vorschlagskarte steht, welches Angebot gezählt wurde. „Passt nicht“ merkt sich für die Familie „dieses Wort ist nicht diese Zutat“ (wie die Regel-Korrekturen im Eingang).

## Wie Vorschläge entstehen

Jeden Tag beim ersten Öffnen, rein auf dem Gerät, deterministisch:

- **Rezeptvorschläge** aus den bestätigten Rezepten der Bibliothek. Punkte je Rezept = Anteil der Hauptzutaten (ohne Grundzutaten wie Salz, Öl, Mehl) im Angebot + Anteil der Obst- und Gemüsezutaten mit Saison (Lager halb). Nicht vorgeschlagen wird, was in den letzten 21 Tagen gekocht wurde oder schon im Plan steht. Bei Gleichstand gewinnt, was länger nicht gekocht wurde. Höchstens 5, mindestens ein Treffer nötig.
- **Zutatenvorschläge**, wenn die Bibliothek noch klein ist: Saison-Zutaten, die zusätzlich im Angebot sind („Grünkohl hat Saison, bei Kaufland −20 %“). Tippen zeigt die passenden Rezepte (Suche gibt es schon) oder setzt die Zutat direkt auf die Liste.
- Jede Karte sagt **warum**, mit Markt und Ersparnis, wo bekannt.
- Wochenwechsel: Angebote gelten meist Montag bis Samstag. Ab Donnerstag zählen auch die angekündigten Angebote der nächsten Woche, wenn der Plantag dort liegt (Aldi Nord und Kaufland liefern Gültigkeitsdaten mit).

**Wo sie erscheinen:** Ein Abschnitt unter **Heute**, „Essen diese Woche“, und unter Familie › Kochbibliothek oben. Keine Benachrichtigung, auch nicht bei neuen Angeboten (Prämisse vom 26.11.).

**Cloud-KI:** Für Rezepte freigegeben, aber hier nicht nötig. Optional später: „Rezeptidee aus diesen Zutaten“ mit dem schon vorhandenen Schlüssel aus den Einstellungen, standardmäßig aus, Ergebnis landet als Rezeptvorschlag (HITL) in der Bibliothek.

## Ablauf: Vorschlag → Einkaufsliste

1. Tipp auf „Kürbissuppe“ öffnet ein Blatt mit zwei Fragen:
   - **Wann?** Chips: Heute, Morgen, die restlichen Tage dieser Woche, „Ohne Tag“. Vorbelegt: der nächste Tag ohne geplantes Gericht.
   - **Für wie viele?** Plus und Minus, **vorbelegt mit der zuletzt genutzten Zahl** auf diesem Handy. Beim allerersten Mal: die Portionen des Rezepts.
2. „Auf die Einkaufsliste“ legt die Zutaten mit den gewählten Portionen an (wie heute: Grundzutaten als „Hast du noch …?“) und trägt das Gericht in den Plan ein. Rückmeldung mit Rückgängig: „Kürbissuppe für Do, 4 Personen: 7 Zutaten auf der Liste.“
3. Einträge aus Angeboten zeigen auf der Liste den Markt („bei Aldi Süd, 1,69 €“), damit man weiß, wo man es holt.

Zwei Feinheiten, die sonst schiefgehen:

- **Rezepte ohne Portionsangabe:** Heute rechnet die App „4 Personen“ dann als 4-fache Menge. Mit der zuletzt genutzten Zahl würde das ständig passieren. Ohne Portionsangabe bleibt die Menge deshalb wie im Rezept, und das Blatt sagt „Rezept ohne Portionsangabe, Mengen wie im Rezept“.
- **Die zuletzt genutzte Zahl gilt überall**, auch beim normalen „Auf die Einkaufsliste“ und im Kochmodus, sonst hat die App zwei Vorgaben, die sich widersprechen. Sie liegt pro Handy (nicht abgeglichen): Wer für den Geburtstag 12 einstellt, ändert nicht die Vorgabe der anderen.

### Essensplan

„Wann“ braucht einen Ort. Ein geplantes Gericht ist ein eigener kleiner Datensatz (Rezept, Tag, Portionen, wer geplant hat), abgeglichen wie Rezepte und Einkaufsliste als zusätzliches Feld, das ältere App-Versionen ignorieren (Schema bleibt 2). Sichtbar:

- unter **Heute** eine Zeile „Heute: Kürbissuppe (4)“, Tippen öffnet das Rezept mit 4 Portionen, „Kochen“ startet den Kochmodus;
- unter **Demnächst** am jeweiligen Tag;
- „Gekocht?“ am Ende des Kochmodus hakt das geplante Gericht ab.

Kein Wochenplan-Editor mit Raster; der Plan entsteht nur aus „Wann?“ und lässt sich dort verschieben oder löschen.

## Wann wird abgerufen?

Das Konzept vom 21.11. sah vor, dass ein Android-Handy per WorkManager abruft und das Ergebnis über den Abgleich an alle verteilt. Vorschlag jetzt: **Jedes Gerät ruft selbst ab, beim Öffnen der App, wenn die gespeicherten Angebote älter als 3 Tage sind oder die Woche gewechselt hat**, im Hintergrund ohne Warten, und nur im WLAN (wegen Kaufland, siehe oben, und Datenvolumen). Gründe:

- Das geht auch auf dem iPhone (Abruf im Vordergrund ist dort kein Problem, nur Hintergrundaufgaben sind unzuverlässig).
- Keine Abstimmung, welches Gerät dran ist, kein neuer Datensatztyp im Abgleich.
- Die Last ist trotzdem klein: zwei bis vier Geräte × drei Märkte × einmal pro Woche.
- Rechtlich bleibt es dabei: Jede Familie holt nur für sich, keine zentrale Angebotsdatenbank (kochbibliothek.md, Rechtliche Einordnung).

Die gespeicherten Angebote bleiben auf dem Gerät und kommen nicht in die Sicherung (sie sind nach einer Woche wertlos). Der Ort und die gewählten Märkte werden dagegen abgeglichen, damit nur einer sie eintragen muss.

## Datenmodell (Skizze)

```kotlin
// abgeglichen, wie Rezepte (neuester Stempel gewinnt), zusätzliche Felder in FamilyData
data class ShoppingPlace(postcode: String, label: String, markets: List<MarketChoice>, stamp)
data class MarketChoice(chain: Chain, storeId: String?, storeName: String?)
data class PlannedMeal(id, recipeId, day: LocalDate?, servings: Int, plannedBy, done: Boolean, deleted, stamp)

// nur auf dem Gerät
data class Offer(chain, title, priceCents: Int?, oldPriceCents: Int?, savingPercent: Int?,
                 validFrom: LocalDate?, validUntil: LocalDate?, categories: List<String>)
data class OfferSnapshot(chain, storeId, fetchedMs, offers: List<Offer>, error: String?)
Settings.lastServings: Int?    // pro Handy
```

Bausteine: `domain/offers/` (Saisonkalender, Abgleich, Punkte, rein und getestet), `data/offers/` (ein Adapter je Markt, Android `HttpURLConnection` wie bei Instagram, Antworten als feste Testdateien), `presentation/` (Vorschläge, Blatt „Wann/Wie viele“).

## Bedrohungsmodell (Ergänzung für docs/qualitaet/bedrohungsmodell.md)

Neue Eingänge: Antworten von vier fremden Websites. Sie sind nicht vertrauenswürdig: Größe begrenzen (5 MB je Antwort, weil die Kaufland-Seite 3,8 MB HTML hat; JSON-Antworten 2 MB), nur JSON/HTML parsen, nie ausführen, Bild-URLs nicht automatisch laden, Texte nur als Text anzeigen, Links aus Angeboten nicht übernehmen. Nach außen geht nur der PLZ-Mittelpunkt (Kaufland) bzw. eine Filialnummer (Aldi Süd), nie die Adresse.

## Empfehlungen (bitte kommentieren, Unkommentiertes gilt als angenommen)

1. **Ort per Adresse oder PLZ in Einstellungen › Einkauf, benutzt wird nur die PLZ**, aufgelöst über eine mitgelieferte GeoNames-PLZ-Tabelle. Kein Geocoder, die Adresse verlässt das Handy nicht.
2. **Händlersuche über die Filialsuchen der Märkte** (Aldi Süd über Uberall, Kaufland über `klstorebygeo`), nicht über OpenStreetMap. Gefundene Filiale wird angezeigt und ist änderbar. Overpass höchstens später und abschaltbar.
3. **Stufe 1 mit Aldi Süd, Aldi Nord, Kaufland, Lidl, sofern sie den Grundsatz oben erfüllen.** Keine Bot-Abwehr umgehen, kein WebView-Rückfall; wer ablehnt, wird abgeschaltet. Lidl nur „im Angebot“ ohne Preis, solange Lebensmittel dort nur als Prospektbild vorliegen. REWE nicht.
4. **Ein Adapter je Markt mit eigenem Test und sichtbarem Zustand**, ein ausgefallener Markt stört die anderen nicht.
5. **Jedes Gerät ruft selbst ab**, beim Öffnen, wenn älter als 3 Tage oder neue Woche, nur im WLAN. Ersetzt Empfehlung 9 der Kochbibliothek (WorkManager auf einem Android-Handy plus Abgleich).
6. **Saisonkalender als eigene feste Tabelle** mit „Saison“ und „Lager“ (halb), etwa 50 Arten, geprüft gegen das BZfE-Poster, ohne dessen Grafik oder Tabelle zu übernehmen.
7. **Eigener Abgleich Produktname → Zutat-Schlüssel** mit Kompositum-Regel und Verarbeitungs-Sperre, Korrektur „Passt nicht“ wird für die Familie gemerkt.
8. **Vorschläge deterministisch mit Begründung**, höchstens 5, nichts aus den letzten 21 Tagen, beim App-Start unter Heute, keine Benachrichtigung. Zusätzlich Zutatenvorschläge (Saison + Angebot), solange die Bibliothek klein ist.
9. **Tipp → „Wann?“ und „Für wie viele?“ → Einkaufsliste und Essensplan** in einem Schritt mit Rückgängig. „Wann“ vorbelegt mit dem nächsten freien Tag.
10. **Zuletzt genutzte Personenzahl pro Handy, überall als Vorgabe**; Rezepte ohne Portionsangabe werden nie hochgerechnet.
11. **Essensplan als kleiner abgeglichener Datensatz**, sichtbar unter Heute und Demnächst, kein eigener Planungs-Bildschirm.
12. **Markt und Preis an Listeneinträgen aus Angeboten.**
13. **Cloud-KI nicht in Stufe 1**; „Rezeptidee aus diesen Zutaten“ später als Schalter mit dem vorhandenen Schlüssel.
14. **Erst Abruf auf dem echten Handy beweisen, dann Oberfläche bauen** (Prüfung 1). Die Instagram-Erfahrung (HttpURLConnection bekam auf dem Handy keine Seite) zeigt, dass Abrufe aus Browser oder Cloud nichts über das Handy sagen.

## Umsetzung in Stufen

1. **Stufe 1a (in der Cloud testbar, Qualitätstor):** Saisonkalender, Produkt-Abgleich, Punkte, „zuletzt genutzte Zahl“, Essensplan-Datensatz und Abgleich, Parser der vier Märkte gegen Testdateien.
2. **Stufe 1b (Handy):** Adapter mit echtem Abruf, Einstellungen › Einkauf, Diagnose-Zeile je Markt, Vorschläge unter Heute, Blatt „Wann/Wie viele“. Signierte APK über CI wie immer.
3. **Später:** Lidl-Preise per Texterkennung aus Prospektseiten, Netto/Penny/Edeka prüfen, Südfrüchte-Saison, Rezeptidee per KI, Einkaufsliste nach Markt sortiert.

## Prüfung

1. **Abruf auf dem echten Handy (Tor):** Nils trägt seine PLZ ein. Ein Markt, der mit der normalen App-Anfrage abgelehnt wird oder eine Challenge-Seite liefert, fällt raus. Abnahme für die übrigen: Für jeden der vier Märkte zeigt die Diagnose-Zeile mindestens 30 Lebensmittel-Angebote (Lidl: mindestens 10 Schlagwörter), Aldi Süd und Kaufland nennen eine Filiale, die er kennt.
2. **Abgleich gegen eine echte Woche:** Die Angebote einer Woche (aus Prüfung 1, nur auf dem Handy, nicht im Repo) gegen 20 Rezepte der Familienbibliothek. Nils markiert jede gezählte Zuordnung als richtig oder falsch. Abnahme: höchstens 1 von 20 Zuordnungen falsch, kein verarbeitetes Produkt (Joghurt, Saft …) als frische Zutat.
3. **Saisonkalender:** Jede Zeile der App-Tabelle gegen das BZfE-Poster; Abweichungen von mehr als einem Monat an Haupt-Erntezeiten werden korrigiert oder begründet.
4. **Ablauf:** Vom App-Start bis „steht auf der Liste“ höchstens 3 Tipps, wenn Tag und Personenzahl passen (Karte, „Auf die Einkaufsliste“; die Vorbelegung spart den Rest).

## Offene Punkte

- [ ] Kaufland: Wie kommt die App filialgenau an die Angebotsseite (Parameter oder Cookie)? Bis dahin IP-Zuordnung im WLAN.
- [ ] Lidl: Welche Region gilt für welche Filiale (mehrere Prospektfassungen derselben Woche)? `lidl.py`-Ausgabe zum Vergleich.
- [ ] **Tor vor dem Bau jedes Adapters:** `robots.txt` von `asl.api.aldi-sued.de` und `endpoints.leaflets.schwarz` sowie die Nutzungsbedingungen der vier Märkte auf einen Vorbehalt nach § 44b UrhG prüfen. Fällt ein Markt durch, kommt er nicht in die App.
- [ ] Bleibt Uberall als Filialquelle für Aldi Süd stabil? Rückfall: Filial-Sitemap `aldi-sued.de/sitemap_stores.xml`.

## Quellen (alle abgerufen am 27.11.2026)

- Aldi Süd: `www.aldi-sued.de/robots.txt`, `www.aldi-sued.de/angebote` (Netzwerkabrufe der Seite), `www.aldi-sued.de/filialen`
- Aldi Nord: `www.aldi-nord.de/robots.txt`, `www.aldi-nord.de/angebote.html`
- Lidl: `www.lidl.de/robots.txt`, `www.lidl.de/c/online-prospekte/s10005610`, Prospekt-Schnittstelle `endpoints.leaflets.schwarz/v4/flyer`
- Kaufland: `filiale.kaufland.de/robots.txt`, `filiale.kaufland.de/angebote/uebersicht.html`, `filiale.kaufland.de/.klstorebygeo.json`
- Nominatim Usage Policy: https://operations.osmfoundation.org/policies/nominatim/
- Overpass API, Commons: https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
- GeoNames Postal Codes, Lizenz: https://download.geonames.org/export/zip/readme.txt
- BZfE Saisonkalender: https://www.bzfe.de/kueche-und-alltag/einkaufen/der-saisonkalender, Infografiken Gemüse und Obst (CC BY-ND 4.0), Poster 3488/2024 (© BLE)
