# Qualitätstor und Messungen

Gehört zu [docs/qualitaet/iso-25010-bewertung.md](../../docs/qualitaet/iso-25010-bewertung.md). Kein Teil des App-Builds, läuft ohne Android SDK.

- `build.gradle.kts` kompiliert die Compose-freien Schichten aus `app/shared/src/commonMain` (domain, data, presentation, update) und die JVM-Sicherungslogik aus `androidMain/.../data` als Kotlin/JVM und startet die Tests aus `commonTest` und `androidUnitTest/.../data`.
- `gradle check`: Tests, Abdeckungs-Untergrenze (JaCoCo) und detekt mit `config/detekt.yml` und `config/baseline.xml`. Im CI: `.github/workflows/quality.yml`.
- `probes/CapacityProbe.kt` misst Größe und Dauer des Speicherformats bei 1.000, 5.000 und 20.000 Einträgen (M5); Ausgabe in `build/test-results/test/TEST-CapacityProbe.xml`.
- `contrast.py` rechnet die Kontraste der Theme-Farben nach WCAG (M4): `python3 contrast.py`.

Abdeckung steigt, Untergrenze in `build.gradle.kts` nachziehen. Neue detekt-Befunde beheben, die Baseline nur verkleinern (`gradle detektBaseline` erst nach dem Beheben).

Berichte: `build/reports/jacoco/test/`, `build/reports/detekt/`, `build/reports/tests/`.
