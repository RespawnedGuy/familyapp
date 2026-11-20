# iOS-App (vorbereitet)

Das gemeinsame Modul `shared` hat schon die iOS-Ziele `iosArm64` und `iosSimulatorArm64` und liefert ein Framework `Shared` mit dem Einstieg `MainViewController()`.

Das Xcode-Projekt wird erst auf einem Mac angelegt (Xcode nötig), z. B. über den KMP-Wizard, und ruft `MainViewController()` aus `Shared` auf. Auf Linux und Windows überspringt Gradle die iOS-Ziele.
