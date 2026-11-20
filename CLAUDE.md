# FamilyApp – Projekt-CLAUDE.md

Reifestufe: **Spike** (nur `spikes/` enthält Code; Wegwerfcode, wird nie zum Produkt).

Die allgemeinen Regeln stehen in der übergeordneten CLAUDE.md von Nils. Chat und Doku auf Deutsch, Code, Bezeichner und Commits auf Englisch.

## Befehle

| Zweck | Befehl (in `spikes/rule-engine/`) |
| --- | --- |
| test | `gradle test` |
| lint | – (im Spike nicht eingerichtet) |
| build | `gradle build` |
| Spike ausführen | `gradle run --args="<samples-dir> <rules-file> [expected.csv]"` |

## Ordner

- `docs/` Konzept, `docs/entscheidungen/` ADRs
- `spikes/rule-engine/` Spike: deterministische Regel-Engine + deutscher Datums-Parser

## Datenschutz

Echte Familienpost nur in `samples-private/` und `expected-private.csv` ablegen. Beides steht in `.gitignore` und darf nie committet werden.
