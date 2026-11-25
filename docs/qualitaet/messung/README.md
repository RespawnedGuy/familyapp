# Messungen zur ISO-25010-Bewertung

Gehört zu [../iso-25010-bewertung.md](../iso-25010-bewertung.md). Kein Teil des App-Builds.

- `build.gradle.kts` kompiliert die Compose-freien Schichten aus `app/shared/src/commonMain` (domain, data, presentation, update) als Kotlin/JVM und startet die Tests aus `commonTest`. Läuft ohne Android SDK, also auch in Cloud-Sessions.
- `probes/CapacityProbe.kt` misst Größe und Dauer des Speicherformats bei 1.000, 5.000 und 20.000 Einträgen (M5).
- `probes/WriteFailureProbe.kt` zeigt, was bei einem Schreibfehler passiert (M6). Wird mit Plan-Punkt P0-1 zum echten Test in `commonTest`.
- `contrast.py` rechnet die Kontraste der Theme-Farben nach WCAG (M4).

Befehle in diesem Ordner:

```
gradle test detekt
python3 contrast.py
```

Ergebnisse: Tests und Messausgaben in `build/test-results/test/*.xml`, Abdeckung in `build/reports/jacoco/test/`, detekt in `build/reports/detekt/`.
