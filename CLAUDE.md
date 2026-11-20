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

Spike `spikes/p2p-sync/`: `gradle test`, `gradle build`, CLI per `gradle installDist` und `build/install/p2p-sync/bin/p2p-sync` (siehe README dort).

## Ordner

- `docs/` Konzept, `docs/entscheidungen/` ADRs
- `spikes/rule-engine/` Spike: deterministische Regel-Engine + deutscher Datums-Parser
- `spikes/p2p-sync/` Spike: Gerätesync ohne Server (WLAN/Tailscale, optional geteilter Ordner), ADR 0003

## Datenschutz

Echte Familienpost nur in `samples-private/` und `expected-private.csv` ablegen. Beides steht in `.gitignore` und darf nie committet werden.
