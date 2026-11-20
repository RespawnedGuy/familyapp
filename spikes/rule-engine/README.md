# Spike: deterministische Regel-Engine

Wegwerfcode (Reifestufe Spike). Frage: Ordnen feste Regeln plus ein deutscher Datums-Parser echte Familienpost zuverlässig zu?

Abnahme: ≥ 80 % richtig zugeordnet oder korrekt offen gelassen, 0 Falschzuweisungen bei eindeutigen Treffern.

## Ausführen

```
gradle test
gradle run --args="examples/samples examples/rules.txt examples/expected.csv today=2026-11-20"
```

## Mit echter Post (nur lokal, nie committen)

1. Mails als `.eml` oder Briefe als `.txt` in `samples-private/` legen.
2. Regeln in `rules-private.txt` schreiben (Format siehe `examples/rules.txt`).
3. Erwartung je Datei in `expected-private.csv` (`file;expected(assigned|open);person;type`).
4. `gradle run --args="samples-private rules-private.txt expected-private.csv"`

Logik: Alle Bedingungen einer Regel müssen passen. Die spezifischste Regel gewinnt (Absender 2, Domain 1, Stichwort je 1). Gleich spezifische Regeln mit unterschiedlichem Ergebnis oder kein Treffer: Eintrag bleibt offen (HITL). Ein Fälligkeitsdatum wird nur gesetzt, wenn genau ein Datum gefunden wurde.
