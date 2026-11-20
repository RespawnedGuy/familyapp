# FamilyApp – Konzept gegen Mental Load

Stand: 2026-11-20 · Lebende Fassung mit Diagramm: https://claude.ai/artifact/4CbdF3YKiVBzBRKpYJBGuU

## Kurzfassung

Die App nimmt der Familie vor allem das **Vorausdenken und Nachhalten** ab, nicht das Entscheiden. Genau diese zwei Phasen landen laut Forschung überproportional bei einer Person, und sie sind unsichtbar. Alles kommt in Sekunden rein (Sprache, Foto, Weiterleiten), feste Regeln schlagen danach vor, zu wem etwas gehört, wer sich kümmert und bis wann, und ihr bestätigt mit einem Klick (HITL). Lokale KI ist erlaubt, Cloud-KI nur optional.

Was dieses Konzept anders macht als ein Feature-Brainstorm:

- Jedes Feature hängt an einer belegten Phase des Mental Load (Daminger 2019, Dean et al. 2022, Ciciolla & Luthar 2019, Offer 2013) und hat eine Messgröße, an der wir sehen, ob es wirkt.
- Es nimmt ein Risiko ernst, das Familien-Apps meist ignorieren: Wer die App pflegt, wird zur Haushalts-Managerin. Die App darf den Mental Load nicht verschieben, sondern muss ihn verteilen.
- Es endet mit einem Testplan (Tagebuchstudie, validierte Fragebögen, Telemetrie), damit wir nicht nur glauben, dass es hilft.

## Forschungsgrundlage

Mental Load ist kognitive plus emotionale Arbeit für den Haushalt, und das Belastende ist weniger die Menge als die alleinige Verantwortung dafür.

| Quelle | Methode | Befund, der das Design bestimmt |
| --- | --- | --- |
| [Daminger 2019, American Sociological Review 84(4)](https://inequality.hks.harvard.edu/publications/cognitive-dimension-household-labor) | 70 Interviews, 35 Paare | Kognitive Arbeit = Bedürfnisse **antizipieren**, Optionen **identifizieren**, **entscheiden**, Fortschritt **überwachen**. Frauen übernehmen mehr Antizipieren und Überwachen; beim Entscheiden etwa gleich verteilt. Oft für beide Partner unsichtbar, häufiger Streitpunkt. |
| Dean, Churchill & Ruppanner 2022, Community, Work & Family ([Abstract](https://findanexpert.unimelb.edu.au/scholarlywork/1615678-the-mental-load--building-a-deeper-theoretical-understanding-of-how-cognitive-and-emotional-labor-overload-women-and-mothers)) | Theoretische Synthese | Mental Load = kognitive + emotionale Arbeit; unsichtbar, grenzenlos, dauerhaft. (Seite nicht abrufbar, Merkmale aus dem Gedächtnis, vor Zitat prüfen.) |
| [Ciciolla & Luthar 2019, Sex Roles 81](https://asu.elsevierpure.com/en/publications/invisible-household-labor-and-ramifications-for-adjustment-mother/) | 393 Mütter, USA | Mehrheit organisiert Termine und Ordnung allein. Gefühl unverhältnismäßiger Verantwortung hängt mit geringerem Wohlbefinden und geringerer Partnerzufriedenheit zusammen. |
| [Offer 2013, ASA-Jahrestagung](https://www.sciencedaily.com/releases/2013/08/130811005331.htm) | 500 Family Study, 402 Mütter, 291 Väter, Zeittagebuch | Mütter ca. 29 h, Väter ca. 24 h mentale Arbeit pro Woche. Nur bei Müttern verschlechtert familienbezogenes Nachdenken das Wohlbefinden: „eher Qualität als Quantität“. |

**Phasenmodell** (1–4 nach Daminger, 5–7 Ergänzung aus Dean und Ciciolla):

1. **Bemerken**: Eine Information taucht auf.
2. **Antizipieren**: Erkennen, dass etwas ansteht, bevor es dringend wird.
3. **Optionen finden**: Recherchieren, vergleichen, Termine abfragen.
4. **Entscheiden**: Auswahl treffen, oft gemeinsam.
5. **Zuständigkeit übergeben**: Wer kümmert sich, komplett inklusive Nachhalten.
6. **Überwachen**: Erledigt? Läuft es? Nachfassen?
7. **Emotionale Arbeit**: Gefühle der Kinder, Beziehungen zu Großeltern und Freunden.

Quer dazu die Merkmale nach Dean et al.: **unsichtbar**, **grenzenlos**, **dauerhaft**.

Masicampo & Baumeister (2011, JPSP): Ein konkreter Plan für ein offenes Ziel reduziert aufdringliche Gedanken daran. Stützt Prämisse 2 (aus dem Gedächtnis, vor Zitat prüfen).

## Phasen → Features

| Phase | Automatisierung | Visualisierung | Messgröße |
| --- | --- | --- | --- |
| 1 Bemerken | Erfassen per Sprache, Foto, Teilen, Mail-Weiterleitung; Aufnahme läuft ≤ 2 s nach Entsperren. Kein Pflichtfeld. | Inbox-Zähler, der von selbst schrumpft | Sekunden bis Aufnahme läuft; Einträge je Familienmitglied |
| 2 Antizipieren | **Vorausschau-Pakete** nach Lebenslage (U-Untersuchungen, Impfungen, Ferien je Bundesland, Reifenwechsel, TÜV, Steuerfrist, Geburtstage). Rückwärtspläne aus Fristen. Folgeaufgaben aus Dokumenten. | „Demnächst“-Zeitleiste, 6 Wochen | Anteil Aufgaben vor Dringlichkeit angelegt; Last-Minute-Aktionen |
| 3 Optionen finden | Familienwissen wiederverwenden: Kontakte, frühere Vorgänge gleichen Typs | Vorschlagskarte an der Aufgabe | Zeit bis Entscheidung |
| 4 Entscheiden | Leichte Abstimmung (2–3 Optionen) statt Chat; Entscheidung mit Begründung gespeichert | Stapel offener Entscheidungen | Offene Entscheidungen > 7 Tage |
| 5 Zuständigkeit übergeben | **Volle Übergabe** inkl. Erinnern und Nachhalten; **Transparenz**: alle sehen die Stapel aller, können priorisieren und Ergebnisse sehen | Stapel pro Person, für alle einsehbar | Bereiche mit voller Zuständigkeit beim zweiten Elternteil |
| 6 Überwachen | **Gestufte Eskalation**: Frist verpasst → zuständige Person; einen Tag später → alle Mitglieder. Dazu Vertrags- und Abofristen | Ampel pro Vorgang; „Läuft von allein“-Liste | Erinnerungen, die ein Mensch senden musste |
| 7 Emotionale Arbeit | **Nicht im Produktkern** (manuell zu pflegen, wird kaum genutzt). Nur Pflegefreies bleibt: Geburtstage aus Kontakten | – | – |

- **Unsichtbar → sichtbar:** Lastkarte für Vorgänge mit fester Frist oder festem Termin: wer zuständig, welcher Stand. Delegiert heißt angenommen, Liegengelassenes ist sichtbar.
- **Grenzenlos → Zeitfenster:** Wöchentlicher 10-Minuten-Check-in mit vorbereiteter Tagesordnung, Ruhezeiten.
- **Dauerhaft → Abschluss erlebbar:** „Erledigt bis“-Bereiche; die App sagt, was man vergessen darf.

## Capture-Flows: schnell rein, komplett später

Beim Erfassen keine Frage, kein Pflichtfeld, keine Kategorie. Ziel (entschieden 20.11.2026, siehe [gestaltung/optik-haptik-klickwege.md](gestaltung/optik-haptik-klickwege.md)): Aufnahme läuft ≤ 2 s nach Entsperren, gespeichert ohne weiteren Tap ≤ 1 s nach Sprechende. Die Sprechzeit zählt nicht als Aufwand.

| Kanal | Typische Situation | Was Regeln daraus vorschlagen |
| --- | --- | --- |
| Sprache (Widget, Sperrbildschirm, Assistent, Watch) | „Mia braucht bis Freitag Turnschuhe“ | Aufgabe + Frist + Person + Einkauf |
| Foto | Kita-Zettel, Amtsbrief, Kassenbon | Texterkennung, Datum, Absender, Folgeaufgaben; Original angehängt |
| Teilen-Menü | Link, PDF, Screenshot | Idee, Dokument, Termin oder Entscheidungsvorlage |
| Mail-Weiterleitung an Familienadresse | Arzttermin, Schulmail, Vertrag | Termin, Frist, Lieferung; Absender wird gelernt |
| Messenger-Weiterleitung | Kita- oder Klassen-Gruppe | Termin oder Aufgabe |
| Kalender-Abo (ICS) | Ferien, Müllabfuhr, Verein | Termine ohne Tippen |
| Schnelltext | Drei Wörter | Wie Sprache |

## Regel-Engine statt Cloud-KI

Deterministische Regeln füllen jeden Eintrag vor (zu wem, wer kümmert sich, bis wann, Typ), ein Mensch bestätigt per Klick (HITL). Metadaten per Klick als Chips ergänzbar. Lokale KI auf dem Gerät ist freigegeben (z. B. Pixel 10, Text- und Spracherkennung des OS). Cloud-KI ist optional, standardmäßig aus, pro Funktion abschaltbar.

**Entschieden (Nils, 20.11.): HITL ist das Prinzip.** Was eine Regel zu 100 % eindeutig zuordnet, wird direkt zugewiesen. Alles andere bleibt im Eingang offen, bis jemand bestätigt oder es als irrelevant markiert. Alle Erwachsenen sehen den offenen Eingang.

| Regeltyp | Beispiel | Ergebnis |
| --- | --- | --- |
| Absender-Domain | `@schulederkinder.de` | Vorgang „Schule“, Zuständige des Bereichs |
| Absender | `xyz@…` | fester Typ und Person |
| Stichwörter kombiniert | „Mia“ **und** Adresse der Kinderärztin | Arzttermin für Mia |
| Quelle | Kalender-Abo, Kita-Gruppe | Termin, alle |
| Datum im Text | „bis 12.03.“, „am Freitag“ | Frist/Termin per Datums-Parser |

Regeln entstehen aus Klicks („Künftig immer so?“), aus Bekanntem im Familiengraphen (Namen, Adressen, Domains) und aus Vorlagen. Reihenfolge: Regeln → lokale KI (wenn keine Regel greift) → Cloud-KI (nur wenn an); nur eindeutige Regeltreffer werden direkt zugewiesen, KI-Ergebnisse immer bestätigt. Jeder Eintrag zeigt, welche Regel gegriffen hat; spezifischere Regel gewinnt.

Sparring: Regelpflege darf kein neuer Mental Load werden (kein Pflicht-Editor). Lokale KI gibt es nicht auf jedem Gerät, die App muss mit Regeln allein vollständig sein. Bestätigte KI-Vorschläge werden auf Wunsch zu Regeln.

**Sparring zu Prämisse 2:** „Komplett später“ darf nicht „jemand komplettiert später“ heißen. Ziel: ≥ 80 % der Einträge ohne Nacharbeit fertig, Rückfragen reihum verteilt.

## Datenmodell: alles an einem Ort

Ein einziger Familiengraph, jede Ansicht ist ein Blick darauf.

Fluss: Erfassen (5 Kanäle) → Inbox (Original bleibt) → Regel-Engine (lokale KI optional, Cloud-KI wenn an) → 100 % eindeutig: Familiengraph / sonst: Vorschlagskarte, offen bis bestätigt oder irrelevant → Ansichten (Demnächst, Zuständigkeiten, Lastkarte, Wochen-Check-in).

- **Personen** (auch Kinderarzt, Lehrerin, Oma), entstehen aus Inhalten
- **Objekte**: Termin, Aufgabe, Frist, Dokument, Wissen, Einkauf, Idee
- **Vorgänge** bündeln Objekte („Klassenfahrt Mai“)
- **Bereiche** mit Zuständigkeit („Gesundheit Kinder“)
- **Quellen**: Herkunft jedes Eintrags

Architektur: Neue Funktionen brauchen keine neuen Datensilos. Schema-Entscheidung als erstes ADR in `docs/entscheidungen/`.

## Onboarding ohne Setup

1. App öffnen, sofort erfassen; Anmeldung (Apple/Google/Passkey) erst beim Speichern.
2. Eine optionale Frage: Jahrgänge der Kinder → Vorausschau-Pakete füllen „Demnächst“ sofort.
3. Personen entstehen aus Inhalten („Ist Mia euer Kind?“).
4. Partner per Link einladen, mit Grund („4 Dinge betreffen Lisa“).
5. Quellen im passenden Moment anbieten (Kalender-Abo, Familienadresse, Messenger).

Abnahmekriterium: Testperson ohne Erklärung speichert ersten Eintrag in < 60 s und sieht ≥ 5 vorausgedachte Termine.

## Abgrenzung zu FamilyWall und TimeTree

Dort pflegt die Familie die App, hier pflegt die App die Familienorganisation. (Linke Spalte = Einschätzung aus der Aufgabe; vor öffentlicher Aussage selbst testen.)

| Kriterium | Klassische Familien-Apps | Unsere App |
| --- | --- | --- |
| Start | Familie, Mitglieder, Kalender, Listen einrichten | Sofort erfassen |
| Eingabe | Formulare | Sprache, Foto, Weiterleiten; Regeln füllen vor, Klick bestätigt |
| Vorausdenken | Nur Eingetragenes | Vorausschau-Pakete, Folgeaufgaben |
| Nachhalten | Erinnerung an Eintragende | Volle Übergabe, App fasst nach |
| Sichtbarkeit der Last | Aufgabenlisten | Lastkarte |
| Wissen | Verstreut | Ein Familiengraph |
| Fundament | Feature-Liste | Phasenmodell mit Messgrößen |

## Nils' Funktionsideen eingeordnet

Sechs der acht Ideen passen direkt ins Konzept. Die Portal-Anbindungen (LernSax/HortPro, Essensanbieter) sind der größte Hebel, aber auch das größte technische Risiko.

| Idee | Phase(n) | Wann | Sparring |
| --- | --- | --- | --- |
| Gemeinsamer Kalender, Bedienung + Import | 1, 6 | MVP | Im MVP nur Import (ICS, Foto, Mail), Export als Abo-Link; Zwei-Wege-Sync später |
| Gemeinsame To-dos | 5, 6 | MVP | Wischen statt Formular; Zuständigkeit wichtiger als Listen |
| Einkaufs- und Essensplaner mit Angeboten | 2, 3, 4 | Stufe 2 | Angebotsdaten: offene Schnittstellen fehlen oft, Datenpartner klären oder Prospekte per Foto |
| Rezeptimport (Foto, Screenshot, Instagram, TikTok) | 1 | Stufe 2 | Erkennung lokal oder per Klick ergänzt; Videos brauchen Transkription; nur privat speichern, Quelle verlinken |
| Sprache statt Tippen | 1 | MVP | Auch für Befehle („verschieb den Zahnarzt“) |
| Bildaufnahme mit Routing | 1, 2 | MVP | Routing-Genauigkeit = Abnahmekriterium (80 %) |
| Nachrichten aus LernSax, HortPro | 1, 6 | Spike vor Stufe 2 | Schnittstelle ungeklärt; Scraping mit Zugangsdaten heikel und fragil. Zwischenlösung: Portal-Mails an Familienadresse weiterleiten |
| Essen bestellen/abbestellen beim Caterer | 5, 6 | Spike vor Stufe 2 | Jeder Caterer eigenes Portal; erst euren prüfen, sonst Erinnerung + Direktlink mit Frist |
| Gemeinsame Kontakte | 3 | MVP (leicht) | Nur familienrelevante Kontakte, nicht ganzes Adressbuch |

Architektur: Portale, Händler und Caterer als austauschbare Konnektoren hinter einer gemeinsamen Schnittstelle, die nur Einträge in die Inbox liefert.

Annahme: Familie in Sachsen (LernSax) → erstes Ferien-Paket Sachsen.

## MVP-Schnitt

Ziel: Ein Elternpaar erfasst zwei Wochen lang alles ohne Setup, und der weniger belastete Partner übernimmt spürbar Antizipieren und Nachhalten.

Abnahmekriterien:

- Erfassen: Aufnahme läuft im Median ≤ 2 s nach Entsperren, Speichern ohne Tap
- ≥ 80 % der Vorschläge ohne Änderung bestätigt (ohne Cloud-KI)
- Nach 2 Wochen hat der zweite Elternteil ≥ 2 Bereiche mit voller Zuständigkeit

| Drin im MVP | Bewusst später | Begründung |
| --- | --- | --- |
| Sprache, Foto, Teilen-Menü, Schnelltext | Messenger, Smartwatch | Messenger rechtlich/technisch aufwendig |
| Mail-Weiterleitung | Postfach anbinden | Keine Postfachrechte nötig |
| Eingang mit Regel-Vorschlägen und Bestätigung per Klick | Folgeaufgaben für alle Dokumenttypen | Erst Schulbriefe/Arzttermine perfekt |
| Termine, Aufgaben, Fristen, Dokumente im Graphen | Verträge, Finanzen, Inventar | Kern zuerst, Modell offen |
| Volle Übergabe | Rotationen, Bereichs-Kartenspiel | Kern gegen Ungleichverteilung |
| Vorausschau-Paket Kinder | Auto, Haus, Steuern, Pflege | Häufigster Auslöser |
| Wöchentlicher Check-in | Emotionale Hinweise, Wochenrückblick | Adressiert „grenzenlos“ |
| Einfache Lastkarte | Verlauf, Phasen-Aufschlüsselung | Früh testen, Konfliktpotenzial |
| Ein Kalender-Abo (Ferien) | Zwei-Wege-Sync | Eigene Baustelle |

## Validierung

Tagebuchstudie, 8–12 Paare, 5 Wochen: Woche 1 ohne App (Basis), Daminger-Sortierinterview, Wochen 2–5 mit App + Telemetrie, Abschlussinterview getrennt.

| Messgröße | Instrument | Ziel |
| --- | --- | --- |
| Verteilung Antizipieren/Überwachen | Daminger-Sortieraufgabe vorher/nachher | Anteil des weniger belasteten Partners steigt |
| Wahrgenommene Belastung | NASA-TLX (Hart & Staveland 1988), wöchentlich | sinkt beim höher belasteten Partner |
| Beziehungszufriedenheit | CSI-4 (Funk & Rogge 2007) | nicht schlechter |
| Aufwand beim Erfassen | Telemetrie | Median ≤ 2 s bis Aufnahme läuft |
| Trefferquote der Regeln | Telemetrie | ≥ 80 % ohne Änderung bestätigt, Anteil aus Regeln steigt |
| Ungleichheit in der App | Erfassungen, Übernahmen, Rückfragen je Person | Richtung 50:50 |
| Nachhalten durch die App | Erinnerungen App vs. Mensch | steigt |

Fragebogen-Angaben aus dem Gedächtnis; deutsche validierte Fassungen vor Einsatz heraussuchen.

## Risiken und offene Fragen

Größtes Risiko: Der weniger belastete Partner nutzt die App nicht.

- **App wird zur Managerin-Rolle** → Zuständigkeiten aktiv verteilen, Rückfragen reihum
- **Lastkarte löst Streit aus** → Entschieden (Nils): nur Vorgänge mit fester Frist/festem Termin; Verbindlichkeit vor Harmonie, Liegengelassenes ist sichtbar, kein heimliches Miterledigen
- **Erkennung liest Datum falsch** → Original angehängt, hohe Schwelle, Rückfrage statt Annahme
- **Datenschutz** (DSGVO, Art. 9) → EU-Hosting, On-Device-OCR wo möglich, kein Training auf Nutzerdaten
- **Benachrichtigungsflut** → Bündeln, Ruhezeiten

Beantwortet (Nils, 20.11.):

- Zielgruppe: alle drei (Elternpaare, Getrennterziehende, Pflege der Eltern), gleiche Mechanik
- Kinder: machen mit (eigenes Handy oder Tablet-Dashboard), keine MVP-Priorität, aber im Datenmodell vorgesehen
- Geschäftsmodell: ausgesuchte Familien → Abo, plus Selfhost
- Plattformen: Android und iPhone; Mindestgerät mit Server keines, lokale KI testen
- KI: lokale KI erlaubt, Cloud-KI optional
- Tech-Stack: Vorschlag in docs/entscheidungen/0001-tech-stack.md (KMP + Compose Multiplatform, Ktor + PostgreSQL)

Neu offen:

- [ ] Gehostete Version = dieselbe Software wie Selfhost?
- [ ] Ende-zu-Ende-Verschlüsselung trotz serverseitiger Regeln?
