# FamilyApp auf dem Android-Handy installieren

Gilt für Android 8.0 und neuer. Einmal eingerichtet, gehen Updates genauso.

Die erste Installation geht von Hand. Danach aktualisiert sich die App selbst (siehe unten).

## Weg A: über GitHub (Browser)

1. Releases öffnen: `github.com/RespawnedGuy/familyapp-releases/releases` (öffentlich, keine Anmeldung nötig). Das oberste Release nehmen (Name „FamilyApp 0.x.y“). „CI-Testbuild“-Einträge im privaten Repo nie installieren.
2. Weiter mit Schritt 3.
3. Unter „Assets“ auf `familyapp-0.x.y.apk` tippen. Falls Chrome warnt („Datei kann schädlich sein“): „Trotzdem herunterladen“.
4. Download antippen. Beim ersten Mal fragt Android, ob Chrome Apps installieren darf: „Einstellungen“ → „Dieser Quelle vertrauen“ einschalten → zurück.
5. „Installieren“. Falls Google Play Protect fragt („Unbekannte App“): „Weitere Details“ → „Trotzdem installieren“. Einen Scan anzubieten ist in Ordnung.
6. „Öffnen“. Es erscheint der Platzhalter-Bildschirm „FamilyApp“ mit Versionsnummer.

## Weg B: über Synology Drive

Liegt die APK im Ordner `FamilyApp/_apk` deines Synology Drive: in der Synology-Drive-App öffnen, herunterladen, antippen, weiter ab Schritt 4.

## Updates

- Die App fragt beim Start höchstens einmal am Tag `github.com` nach einer neuen Version. Abschaltbar unter „Täglich automatisch prüfen“, jederzeit von Hand mit „Nach Updates suchen“.
- Gibt es eine neue Version: „Installieren“ tippen. Beim ersten Mal fragt Android, ob FamilyApp Apps installieren darf: „Erlauben“ → „Dieser Quelle vertrauen“ → zurück → „Erneut versuchen“.
- Android zeigt dann „Möchtest du diese App aktualisieren?“ → „Aktualisieren“. Danach FamilyApp neu öffnen. Deine Daten bleiben erhalten.
- Die App prüft vorher Prüfsumme und Signatur. Meldet sie „Prüfsumme stimmt nicht“ oder „Signatur passt nicht“, wurde nichts installiert. Dann nicht von Hand installieren, sondern melden.
- Alternative ohne die eingebaute Funktion: die App [Obtainium](https://github.com/ImranR98/Obtainium) mit `https://github.com/RespawnedGuy/familyapp-releases` einrichten.

## Prüfen, ob die Datei echt ist (optional)

Neben der APK liegt `familyapp-0.x.y.apk.sha256`. Die Zeichenkette muss zum Prüfbericht passen. Das Zertifikat muss den Fingerprint aus `app/signing/release-cert-sha256.txt` haben.

## Wenn etwas schiefgeht

| Meldung | Ursache und Abhilfe |
| --- | --- |
| „App nicht installiert“ / „Paket steht in Konflikt“ | Es ist schon eine Version mit anderem Schlüssel installiert (z. B. ein Testbuild). Alte App deinstallieren, dann neu installieren. |
| „App nicht installiert“ beim Update | Neue Version ist älter als die installierte. Immer das oberste Release nehmen. |
| „Parsing-Fehler“ / „Fehler beim Parsen des Pakets“ | Download abgebrochen. Datei löschen, neu laden, Größe mit dem Release vergleichen. |
| Datei heißt `.zip` | Aus dem Actions-Tab geladen statt aus Releases. Releases benutzen. |
| Installieren-Knopf ausgegraut | Ein Overlay (Bildschirmfilter, Blaulichtfilter, Chat-Bubble) liegt darüber. Kurz ausschalten. |
| „Für dein Gerät blockiert“ (ab 2027) | Google-Entwicklerverifizierung; siehe ADR 0004. Bis dahin per `adb install` möglich. |
