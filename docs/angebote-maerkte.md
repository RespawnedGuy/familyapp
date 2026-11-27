# Angebote: Welche Märkte die App lesen darf (Stufe 2)

Stand: 27.11.2026, Konzept. Fortsetzung von [angebote-und-vorschlaege.md](angebote-und-vorschlaege.md), dort gilt der Grundsatz „nur Märkte, die wir automatisch lesen dürfen“ unverändert.

Auftrag (Nils, 27.11.2026): „weitere filialen und anbieter zeigen: wir haben große anbieter aber nur wenige. es wird also zeit größer zu denken. schau welche 50 märkte in deutschland üblich sind und integriere sie, wieder unter der prämisse technische barrieren nicht zu übertreten“.

## Kurzfassung

- **50 Märkte geprüft.** Die Liste stammt aus dem Top-30-Ranking des Lebensmittelhandels 2025 (NIQ TradeDimensions, Umsatz 2024), ergänzt um Vertriebslinien, Regional- und Bioketten.
- **Geprüft wird jede Plattform, nicht jeder Markt einzeln.** Die Edeka-Seite deckt zum Beispiel EDEKA, E center, E neukauf, E aktiv, E xpress, nah & gut, NP-Markt, Frischemarkt und Scheck-in ab.
- **Drin (8 von 50): die 4 aus Stufe 1 (Aldi Süd, Aldi Nord, Lidl, Kaufland), dazu neu:**
  - **Penny je Verkaufsregion**,
  - **Norma**, **Netto (Stavenhagen)**, **denn's BioMarkt**.
  - Globus war im Konzept drin und ist beim Bau rausgefallen, siehe Nr. 19.
- **Raus (16).** Gründe:
  - **Edeka** mit allen Linien: Technisch wäre Edeka filialgenau und sauber lesbar. Die Nutzungsbedingungen verbieten aber automatisches Auslesen ohne Zustimmung, ausdrücklich auch Preis- und Angebotsinformationen, und behalten Text- und Data-Mining nach § 44b UrhG vor. Damit fällt Edeka nach Punkt 2 unseres Grundsatzes raus.
  - **Botabwehr:** Netto Marken-Discount, Marktkauf, REWE, nahkauf, Rossmann, Müller, Metro und Knuspr.
  - **Sammelportale:** marktguru verbietet automatisches Auslesen, kaufDA und MeinProspekt verbieten das Kopieren der Daten.
- **Offen (18):** Die Seite antwortet ehrlich, aber die Angebote stehen nur als PDF oder kommen erst nach einer Marktwahl per Browser-Skript. Wir umgehen nichts. Ein sauberer Weg ohne Cookies oder Skript ist dort noch nicht gefunden.
- **Passt nicht (8):** Globus (Angebotsseite ist das ganze Sortiment), Großhandel mit Kundenkarte, reine Getränkemärkte, reine Liefer-Apps, Dauerpreis-Drogerie.
- **Neu ist „weitere Filialen zeigen“.** Für jeden Markt mit filialgenauen Angeboten (Aldi Süd, Kaufland, Penny) zeigt die App die nächsten Filialen mit Entfernung. Man wählt eine aus, die App rät nicht.

## Wie geprüft wurde (Rohmaterial statt Zusammenfassungen)

1. **Marktliste:** Top 30 LEH 2025 aus der Lebensmittel Praxis (Quelle NIQ TradeDimensions), die EHI-Übersicht der Biosupermärkte (Februar 2025) und eine Übersicht der Regionalketten, gegengeprüft gegen die Marktsuche von Edeka. Diese Suche liefert die Vertriebslinie jedes Marktes mit („E center“, „NP-Markt“, „Frischemarkt“).
2. **robots.txt:** Jede Datei wurde vollständig gelesen. Maßgeblich ist die Gruppe `User-agent: *`, denn die App nennt sich ehrlich „FamilyApp“ und steht in keiner Bot-Liste.
3. **Echte App-Anfrage:** Jede Seite wurde aus der Cloud mit genau dem Verhalten von `HttpMarkets` abgerufen:
   - ein GET mit der Kennung `FamilyApp (Android; private family app reading weekly offers; +https://github.com/RespawnedGuy/familyapp)`,
   - ohne Cookies, ohne Weiterleitungen, ohne Skript.

   Gewertet wurde, was zurückkam: der Status, die Größe und ob es eine Challenge-Seite war (Akamai „Access Denied“, „Challenge“, Captcha).
4. **Nutzungsbedingungen:** Jede Quelle, die drin ist, wurde auf einen Vorbehalt gegen automatisches Auslesen geprüft (§ 44b Abs. 3 UrhG), siehe Abschnitt „Nutzungsbedingungen“.
5. **Was das nicht beweist:** Ein Rechenzentrum wird von Botabwehr eher abgelehnt als ein Handy im Heim-WLAN.
   - Ein „lesbar“ aus der Cloud ist deshalb ein starkes Zeichen.
   - Ein „abgelehnt“ aus der Cloud heißt: Der Markt setzt technische Botabwehr ein (Akamai Bot Manager, Challenge-Seiten). Nach Nils' Grundsatz lassen wir ihn weg, statt auszuprobieren, ob das Handy zufällig durchkommt.
   - Das Tor bleibt Prüfung 1 auf dem echten Handy.

## Die 50 Märkte

Legende:
- ✅ drin: geprüft und mit der App-Anfrage lesbar.
- ❌ raus: technische Botabwehr.
- 🟡 offen: erreichbar, aber noch kein sauberer Weg zu den Angeboten.
- ⚪ passt nicht.

### Discounter

| # | Markt | Status | Quelle und Befund |
| --- | --- | --- | --- |
| 1 | Aldi Süd | ✅ seit Stufe 1 | JSON `asl.api.aldi-sued.de`, Vorgabe-Filiale der Website |
| 2 | Aldi Nord | ✅ seit Stufe 1 | `__NEXT_DATA__` von `aldi-nord.de/angebote.html`, bundesweit |
| 3 | Lidl | ✅ seit Stufe 1 | Prospekt-Schnittstelle, Lebensmittel ohne Preis |
| 4 | **Penny** | ✅ **neu** | Filialliste `/.rest/market` (2.134 Märkte mit Koordinaten und Verkaufsregion, einmal je Woche laden), Angebote `/.rest/offers/by-category/{KW}/{Kategorie}?region={Verkaufsregion}` als JSON mit Titel, Menge, Preis, Streichpreis, Grundpreis. Die Kategorien stehen in `penny.de/angebote` (`data-category-name`). robots.txt: `Allow: /`. Test 27.11.: Kategorie „kuehlregal“ für eine Region, HTTP 200, 14 kB JSON |
| 5 | Netto Marken-Discount (Edeka) | ❌ | HTTP 403 „Access Denied“ (Akamai) schon auf der Startseite. Ein Apify-Anbieter schreibt dazu: „netto-online.de sits behind Akamai and blocks datacenter IPs“ |
| 6 | **Norma** | ✅ **neu** | Server-gerendertes HTML, bundesweit: `/de/angebote/obst-und-gemuese/` (79 Produktboxen mit Preis und Grundpreis), dazu die Tagesseiten „ab Montag, …“ aus der Übersicht. robots.txt sperrt für `*` nur `/ws/` und einen Unternehmenspfad. Benannte Werkzeuge wie `Python-urllib` und `Wget` sind ganz gesperrt, die App ist keines davon und nennt sich ehrlich |
| 7 | **Netto (Stavenhagen, „mit Hund“)** | ✅ **neu** | Server-gerendertes HTML `netto.de/angebote/…` (Spar Stars, Hammer Donnerstag, Knaller Wochenende), Preise im Text („-16 % 2.49 statt 2.99“). robots.txt: `Allow: /` |

### Supermärkte und SB-Warenhäuser

| # | Markt | Status | Quelle und Befund |
| --- | --- | --- | --- |
| 8 | EDEKA | ❌ Nutzungsbedingungen | Technisch lesbar: Marktsuche `edeka.de/api/marketsearch/markets?searchstring={PLZ}` (JSON mit Name, Adresse, Koordinaten, Vertriebslinie, Link). Die Angebote stehen server-gerendert unter `edeka.de/maerkte/{Markt}/angebote/`: Jede Karte trägt ein JSON-Attribut `data-tracking-event` mit Name, Preis, Kategorie und Gültigkeit. Test 27.11. mit einem Markt in 10115: 261 Angebote, davon 227 mit Preis, 30 App-Coupons (werden weggelassen), 4 bundesweite. robots.txt: `Allow: /`. **Aber** die [Nutzungsbedingungen](https://www.edeka.de/services/nutzungsbedingungen/) (Stand November 2026) sagen in § 2: „Das automatisierte Auslesen, Abrufen, Erfassen, Extrahieren … von Inhalten dieser Website mittels automatisierter Verfahren … ist ohne vorherige ausdrückliche Zustimmung von EDEKA nicht gestattet.“ § 1 nennt ausdrücklich „Preis- und Angebotsinformationen“, § 3 behält Text- und Data-Mining nach § 44b UrhG vor. Zulässig nur mit Zustimmung oder über eine von Edeka bereitgestellte Schnittstelle (§ 4) |
| 9 | E center | ❌ wie Edeka | gleiche Seite, Vertriebslinie „E center“ |
| 10 | E neukauf, E aktiv markt | ❌ wie Edeka | Vertriebslinien „E neukauf“, „E aktiv markt“ |
| 11 | nah & gut, NP-Markt | ❌ wie Edeka | „nah & gut“, „NvK“, „NP-Markt“ |
| 12 | Frischemarkt, E xpress | ❌ wie Edeka | Test mit einem Frischemarkt in Hamburg: 45 Angebote |
| 13 | Scheck-in Center | ❌ wie Edeka | erscheint als „E center“ |
| 14 | Simmel | 🟡 | Simmel-Märkte stehen als EDEKA in der Marktsuche (dieser Weg ist zu, siehe 8). Die eigene Seite `simmel.de` hat nur PDF-Handzettel |
| 15 | Marktkauf | ❌ | eigene Seite `marktkauf.de` HTTP 403 „Access Denied“ (Akamai). In der Edeka-Suche verlinkt der Markt auf `dein-marktkauf.de`, nicht auf eine Edeka-Angebotsseite |
| 16 | REWE | ❌ | Challenge-Seite mit Captcha (HTTP 403, 252 kB), wie schon in Stufe 1 |
| 17 | nahkauf (REWE) | ❌ | läuft über die REWE-Plattform, `nahkauf.de/angebote/` gibt 404, Marktangebote auf rewe.de |
| 18 | Kaufland | ✅ seit Stufe 1 | filialgenau über `klstorebygeo` und `kloffers` |
| 19 | Globus | ⚪ beim Bau raus | `produkte.globus.de/angebote-der-woche/` ist lesbar (Shopware, robots.txt sperrt nur Konto, Warenkorb und Vergleich), aber beim Bau am 27.11. zeigte sich: Die Seite listet rund 3.500 Artikel des Sortiments auf 147 Seiten mit je etwa 1,3 MB, keine Wochenangebote im engeren Sinn. Das wären jede Woche rund 190 MB und 147 Anfragen für Preise der Vorgabe-Filiale. Das passt weder zur höflichen Abfrage noch zum Handy-Datenvolumen. Neu prüfen, wenn Globus eine eigene Seite nur mit Wochenangeboten anbietet |
| 20 | tegut | 🟡 | Marktwahl über ein POST-Formular mit TYPO3-Prüfsumme, die Angebote je Markt hängen an der Sitzung. Ohne Cookie kein Weg gefunden. robots.txt: `Crawl-Delay: 5` |
| 21 | HIT | 🟡 | `hit.de/angebote` liefert die Seite ohne Angebote, sie kommen erst nach der Marktwahl per Skript |
| 22 | famila (Nordwest, Bünting) | 🟡 | WordPress, `/angebote/` gibt 404. robots.txt sperrt `/*?` und `/wp-json/`, damit sind Abfragen mit Parametern tabu |
| 23 | famila Nordost (Bartels-Langness) | 🟡 | `/angebote-1/` ohne Preise im HTML (Prospekt-Viewer) |
| 24 | combi, Jibi (Bünting) | 🟡 | `jibi.de` leitet auf `combi.de` um. Der Onlineshop zeigt „Aktuelle Angebote“, die Produkte kommen aber per Skript, und robots.txt sperrt `/api/*` |
| 25 | Markant (Bünting) | 🟡 | `markant-markt.de` nicht erreichbar (robots.txt beim Abruf ohne Antwort) |
| 26 | Citti | 🟡 | Wochen- und Aktionsprospekte nur als PDF oder Blätterkatalog („Online anschauen“, „PDF herunterladen“) |
| 27 | K+K Klaas & Kock | 🟡 | `/angebote/` leitet auf eine Nachrichtenseite um, keine Preise im HTML |
| 28 | Wasgau | 🟡 | WordPress-Schnittstelle `wp-json/wasgau/v1` hat Routen für Produkte und Märkte, aber keine für Angebote. Für Formulare läuft eine Altcha-Prüfung |
| 29 | V-Markt (Kaes) | 🟡 | regionale Angebotsseiten (München, Schwaben, Hallertau) ohne Preise im HTML |
| 30 | Feneberg | 🟡 | Angebotsseite ohne Preise im HTML (Prospekt) |
| 31 | Konsum Dresden | 🟡 | „Paradenpreis-Angebote“ als PDF |
| 32 | CAP-Markt | 🟡 | keine Angebotsseite gefunden (`/angebote/` 404) |
| 33 | Mix Markt | 🟡 | keine Angebotsseite gefunden (`/de/angebote/` 404) |

### Bio

| # | Markt | Status | Quelle und Befund |
| --- | --- | --- | --- |
| 34 | **denn's BioMarkt** und BioMarkt-Verbund | ✅ **neu** | Gatsby-Datei `biomarkt.de/page-data/angebote/page-data.json`, 83 Angebote mit Preis, Streichpreis, Grundpreis, Gültigkeit und Bio-Merkmalen (vegan, laktosefrei). Gültig „in allen teilnehmenden BioMärkten“. robots.txt (über die Weiterleitung von denns-biomarkt.de) wird vor dem Bau noch einmal direkt gelesen |
| 35 | Alnatura | 🟡 | `/angebote` erreichbar. robots.txt sperrt `/api`, und die Angebote kommen per Skript |
| 36 | Bio Company | 🟡 | keine Angebotsseite unter `/angebote/` (404) |

### Drogerien

| # | Markt | Status | Quelle und Befund |
| --- | --- | --- | --- |
| 37 | dm | ⚪ | Dauerpreise statt Wochenangebote. Die Startseite ist ein Skript-Gerüst mit Challenge-Hinweis |
| 38 | Rossmann | ❌ | Challenge-Seite (HTTP 200, 3 kB, „Challenge“). robots.txt sperrt zusätzlich ClaudeBot |
| 39 | Müller | ❌ | Challenge-Seite (HTTP 403) |
| 40 | Budni | 🟡 | erreichbar, Preise und Angebote erst nach „Filiale auswählen“ |

### Großhandel, Getränke, Lieferdienste

| # | Markt | Status | Quelle und Befund |
| --- | --- | --- | --- |
| 41 | Metro | ❌ | HTTP 403. Ohnehin Großhandel mit Kundenkarte |
| 42 | Selgros (Transgourmet) | ⚪ | Großhandel mit Kundenkarte, Angebote je Markt |
| 43 | Transgourmet, Stroetmann, Hamberger, Weiling, Brülle & Schmeltzer | ⚪ | Großhandel für Gewerbe und Naturkostläden, keine Endkunden-Angebote |
| 44 | Trinkgut (Edeka) | ⚪ | lesbar (server-gerendert), aber nur Getränke, für Rezepte ohne Nutzen |
| 45 | Getränke Hoffmann, Fleggaard | ⚪ | Getränke beziehungsweise Grenzhandel |
| 46 | Picnic | ⚪ | nur App. robots.txt sperrt `/api/` für alle genannten Bots |
| 47 | Knuspr | ❌ | HTTP 403 mit Challenge |
| 48 | Flink | ⚪ | nur App, keine Wochenangebote |

### Sammelportale

| # | Quelle | Status | Befund |
| --- | --- | --- | --- |
| 49 | kaufDA und MeinProspekt (Bonial) | ❌ Nutzungsbedingungen | Sie würden alle Märkte auf einmal abdecken. [AGB](https://www.kaufda.de/AGB) § 3 e: „Unzulässig ist das Kopieren, Weitergeben, Senden oder Veröffentlichen der Daten in irgendeiner Form“. robots.txt sperrt Prospekt-Viewer und Schnittstellen (`/brochure-viewer/brochure`, `/api/frontend/`, `/contentViewer/`). Außerdem gehören die Inhalte den Märkten |
| 50 | marktguru | ❌ Nutzungsbedingungen | [AGB](https://info.marktguru.de/agb) 4 e: „Untersagt ist insbesondere auch der Einsatz von Computerprogrammen zum automatischen Auslesen von Daten.“ |

## Nutzungsbedingungen

Geprüft wurden die Nutzungsbedingungen, AGB oder das Impressum, die von der Fußzeile verlinkt sind. Dazu kamen ein maschinenlesbarer Vorbehalt (`/.well-known/tdmrep.json`, Kopfzeile `tdm-reservation`) und die robots.txt.

| Quelle | Befund | Folge |
| --- | --- | --- |
| Edeka | § 2 verbietet automatisches Auslesen ohne Zustimmung, § 3 behält TDM nach § 44b UrhG vor, „zusätzlich in maschinenlesbarer Form“ | **raus** |
| marktguru | AGB 4 e verbietet automatisches Auslesen | **raus** |
| kaufDA, MeinProspekt | AGB § 3 e verbieten das Kopieren der Daten | **raus** |
| Penny | Impressum ohne Klausel, keine Website-Nutzungsbedingungen gefunden (`/nutzungsbedingungen` 404), kein `tdmrep.json` | drin |
| Norma | Impressum nur mit allgemeinem Urheberrechtshinweis („Eine Verwendung von Teilen der Website bedarf einer ausdrücklichen Zustimmung“), kein Vorbehalt gegen automatisches Auslesen, kein `tdmrep.json` | drin, gleiche Lesart wie bei Aldi und Kaufland in Stufe 1: Die App liest Preise für die eigene Familie und veröffentlicht nichts |
| Netto (Stavenhagen) | Impressum ohne Klausel, kein `tdmrep.json` | drin |
| Globus | Impressum (`globus.de/impressum.php`) ohne Klausel, kein `tdmrep.json` | erlaubt, aber beim Bau raus (Nr. 19) |
| denn's BioMarkt | Impressum nur mit allgemeinem Urheberrechtshinweis, kein `tdmrep.json` | drin |

Das ist eine Lesart der veröffentlichten Texte, keine Rechtsberatung. Bei jeder Änderung eines Adapters wird die Prüfung wiederholt, wie in Stufe 1.

**Edeka wird nicht angefragt.** Entscheidung Nils, 27.11.2026: „Nein, wir zwingen hier niemanden modern zu werden. Wer nicht automatisch ausgelesen werden möchte, wird nicht ausgelesen und kommt als Händler einfach nicht in Frage.“ Edeka bleibt draußen, ebenso jeder andere Markt, der automatisches Auslesen ablehnt.

## Weitere Filialen zeigen

Heute rät die App die Filiale:
- Aldi Süd nimmt die Vorgabe der Website.
- Kaufland nimmt die nächste Filiale zum PLZ-Mittelpunkt.

Neu:

1. **Je Markt mit filialgenauen Angeboten** (Aldi Süd, Kaufland, Penny) zeigt Einstellungen › Einkauf die **nächsten fünf Filialen** mit Name, Ort und ungefährer Entfernung. Eine davon ist gewählt, ein Tipp wählt eine andere.
2. **Die Entfernung rechnet das Handy** aus dem PLZ-Mittelpunkt und den Koordinaten der Filialliste:
   - Penny liefert die Koordinaten aller Märkte in einer Liste, nach außen geht nichts.
   - Kaufland nimmt den PLZ-Mittelpunkt, wie bisher.
3. **Mehrere Filialen desselben Markts** (zum Beispiel zwei Penny-Märkte in verschiedenen Verkaufsregionen) sind möglich, höchstens drei je Markt. Die Angebote werden zusammengeführt, und am Listeneintrag steht die Filiale („bei Penny Musterstraße, 1,99 €“).
4. **Märkte ohne Filialbezug** (Aldi Nord, Lidl, Norma, Netto, denn's) zeigen „bundesweit“ beziehungsweise „Vorgabe-Filiale der Website“. Das ist ehrlich, statt eine Filiale vorzutäuschen.

## Was sich in der App ändert

- **Einstellungen › Einkauf:**
  - Die Märkte sind gruppiert: Discounter, Supermärkte, Bio.
  - Vorausgewählt sind nur die Märkte, die es in der Nähe gibt: Aldi-Gebiet wie bisher über die PLZ, Penny, wenn seine Filialliste im Umkreis von 10 km einen Markt hat.
  - Alle anderen sind abwählbar.
  - „Offen“ und „raus“ stehen ausgegraut mit dem Grund darunter („REWE lässt sich nicht automatisch lesen“). Damit niemand glaubt, die App habe sie vergessen.
- **Abruf:** wie bisher beim App-Start, wenn älter als 3 Tage oder neue Woche, nur im WLAN.
  - Mehr Märkte heißen mehr Anfragen: Penny braucht etwa 25 Kategorie-Abrufe je Woche (gebaut: höchstens 20).
  - Deshalb gibt es höchstens eine Anfrage gleichzeitig je Host und eine Sekunde Pause zwischen zwei Anfragen an denselben Host. Bei tegut, falls es später dazukommt: 5 Sekunden, laut `Crawl-Delay`.
- **Zustandszeile je Markt** wie bisher („Penny Musterstraße: 118 Angebote“).
- **Bedrohungsmodell:**
  - Neue Hosts kommen in `MarketUrls.ALLOWED_HOSTS`.
  - Je Adapter prüft ein Test die Begrenzungen: Größe, Anzahl der Seiten, Format der Markt-IDs (Penny-Region nur `[0-9A-Z-]`, Penny-Kategorie nur `[a-z0-9-]`).
  - Die Einträge AN-1 bis AN-8 gelten weiter. AN-9 kommt dazu: Fremde IDs aus einer Antwort gehen nur in die URL, wenn sie dem Muster entsprechen.

## Empfehlungen (bitte kommentieren, Unkommentiertes gilt als angenommen)

1. **Stufe 2a: Penny (je Region), Norma, Netto, denn's** (Globus beim Bau raus, siehe Nr. 19) als neue Adapter, jeder mit eigenem Test gegen eine gekürzte, erfundene Antwort im Format der echten.
2. **Weitere Filialen zeigen**: die nächsten fünf je Markt mit Entfernung, auswählbar, bis zu drei je Markt. Die Entfernung rechnet das Handy.
3. **Botabwehr und Verbote in den Nutzungsbedingungen bleiben Ausschluss**: Edeka mit allen Linien, Netto Marken-Discount, Marktkauf, REWE, nahkauf, Rossmann, Müller, Metro und Knuspr kommen nicht in die App. Sie stehen ausgegraut mit Grund in der Liste.
4. **Keine Sammelportale** (kaufDA, MeinProspekt, marktguru), auch wenn sie bequem alle Märkte liefern würden. Die App liest nur beim Markt selbst.
5. **Die 18 offenen Märkte werden nicht weiter verfolgt** (Entscheidung Nils, 27.11.2026). Wer seine Angebote nur als PDF oder hinter einer Marktwahl per Skript zeigt, kommt nicht in Frage. Bietet ein Markt später eine offen lesbare Angebotsseite an, wird er neu geprüft.
6. **Keine Texterkennung aus PDF-Prospekten** (Entscheidung Nils, 27.11.2026).
7. **Höfliche Abrufe**: eine Anfrage gleichzeitig je Host, eine Sekunde Pause, `Crawl-Delay` wird beachtet, wo er gesetzt ist.
8. **Edeka wird nicht angefragt** (Entscheidung Nils, 27.11.2026).

## Umsetzungsstand Stufe 2a (27.11.2026)

Gebaut:
- **Penny:** Die App lädt die Filialliste, rechnet auf dem Handy die Entfernung zum PLZ-Mittelpunkt und nimmt die nächste Filiale. Nach außen geht dabei nichts außer der Abfrage der ganzen Liste. Gelesen werden die Lebensmittel-Kategorien der aktuellen Woche für die Verkaufsregion dieser Filiale (ohne Drogerie, Getränke, Pflanzen, höchstens 20 Kategorien).
- **Weitere Penny-Filialen:** Einstellungen › Einkauf zeigt die gewählte Filiale und die nächsten vier weiteren mit ungefährer Entfernung. „Wählen“ übernimmt eine für die ganze Familie und liest nur Penny sofort neu. Eine neue PLZ setzt die Wahl zurück.
- **Norma:** Obst und Gemüse plus die Tagesseiten der Woche („ab Montag …“), höchstens 6 Seiten, bundesweit.
- **Netto (Stavenhagen):** fünf feste Angebotsseiten, bundesweit.
- **denn's BioMarkt:** eine JSON-Datei, nur sichtbare Angebote für Deutschland.
- **Höflichkeit:** höchstens eine Anfrage je Sekunde an denselben Host, alles nacheinander (Bedrohungsmodell AN-10).
- **Penny** ist für neue Orte vorausgewählt. Norma, Netto und denn's schaltet man in Einstellungen › Einkauf zu.
- Unter der Marktliste steht, wer nicht dabei ist und warum.

Noch nicht gebaut (aus dem Konzept, bewusst verschoben):
- Mehrere Filialen desselben Markts gleichzeitig (Punkt 3 oben). Heute gilt eine Filiale je Markt.
- Filialauswahl für Kaufland und Aldi Süd als Liste. Kaufland behält die Suche über eine PLZ.
- Gruppierung Discounter, Supermärkte, Bio und Vorauswahl nach Filiale im 10-km-Umkreis.

Tor bleibt Prüfung 1 auf dem echten Handy.

## Prüfung

1. **Abruf auf dem echten Handy (Tor), wie in Stufe 1:** Jeder neue Markt zeigt in der Zustandszeile mindestens 30 Lebensmittel-Angebote, oder er wird ehrlich abgeschaltet. Penny nennt eine Filiale, die Nils kennt.
2. **Filialwahl:** Die fünf angezeigten Filialen eines Markts stimmen mit der Filialsuche auf der Website des Markts für dieselbe PLZ überein (Stichprobe: drei PLZ, Penny und Kaufland).
3. **Zuordnung:** wie Prüfung 2 der Stufe 1, mit den neuen Märkten, höchstens 1 von 20 falsch.

## Quellen (abgerufen am 27.11.2026)

- Marktliste: [Lebensmittel Praxis, Top-30-Ranking 2025](https://lebensmittelpraxis.de/zentrale-management/43556-top-30-ranking-comeback-der-vollsortimenter.html), [EHI-Umsatzranking Vertriebslinien](https://lebensmittelpraxis.de/handel-aktuell/45804-ehi-retail-institute-edeka-fuehrt-umsatz-ranking-an.html), [Marktübersicht Biohandel](https://lebensmittelpraxis.de/handel-aktuell/43167-marktuebersicht-zum-biohandel-dennree-dominiert-weiter-die-bio-supermarktketten.html), [Regionale Supermärkte](https://www.wunderweib.de/15-supermaerkte-deutschland-die-es-nicht-ueberall-gibt-99967.html) (Stand 2017, nur für die Namen)
- robots.txt aller genannten Hosts, direkt gelesen
- Nutzungsbedingungen: [Edeka](https://www.edeka.de/services/nutzungsbedingungen/), [kaufDA/Bonial](https://www.kaufda.de/AGB), [marktguru](https://info.marktguru.de/agb), Impressum von Penny, Norma, Netto, Globus und denn's BioMarkt
- Hinweise auf Botabwehr Dritter: [Apify Netto-Scraper](https://apify.com/studio-amba/netto-scraper), [Apify DE-Supermarket-Offers](https://apify.com/nerdrx/de-supermarket-offers) (nur als Hinweis, gewertet wurde der eigene Abruf)
- Eigene Abrufe mit der App-Kennung aus der Cloud: Status, Größe und Challenge-Merkmale je Seite (Tabelle oben)
