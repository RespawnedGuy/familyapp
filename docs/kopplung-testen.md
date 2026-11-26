# Kopplung und Abgleich auf echten Geräten testen

Was die CI nicht kann: zwei echte Handys, Kamera, WLAN-Erkennung, Tailscale, Hintergrund und echte Ordnerdienste. Die CI prüft im Emulator die Kopplung mit dem Hausgerät und den Abgleich in beide Richtungen (`app/scripts/emulator-pairing-e2e.sh`).

Voraussetzung: beide Handys haben dieselbe App-Version aus dem PR-Build.

| # | Schritte | Erwartet |
| --- | --- | --- |
| 1 | Handy A (deine Familie): Einstellungen → Geräte und Abgleich → „Handy koppeln“. Handy B: neu installiert, beim Start „Ich wurde eingeladen“, Name eintragen, QR-Code scannen. Beide im selben WLAN. | Beide zeigen dieselbe sechsstellige Zahl. A schlägt „Lisa (neu)“ vor bzw. eine vorhandene Person gleichen Namens. Nach „Stimmt“ auf beiden: B zeigt eure Einträge und Fotos, A bietet die Sicherung mit Wiederherstellungscode an. |
| 2 | Auf B einen Eintrag erfassen, A offen lassen. | Nach wenigen Sekunden auf A sichtbar, mit Foto. Umgekehrt genauso. Eine Person zuweisen oder erledigen auf A kommt auf B an. |
| 3 | Neue Einladung auf A, auf B scannen und auf A „Stimmt nicht“. Danach denselben QR-Code noch einmal scannen. | B: „Kopplung abgebrochen. Nichts wurde übertragen.“ Zweiter Versuch scheitert, der Code gilt nur einmal. |
| 4 | Tailscale auf beiden an, B ins Mobilfunknetz (WLAN aus), beide Apps offen, auf B „Jetzt abgleichen“. | „Abgeglichen mit …“ über die 100.x-Adresse. Ohne Tailscale: verständlicher Hinweis, keine Fehlermeldung mit Technikbegriffen. |
| 5 | Auf A und B unter „Abgleich über einen Ordner“ → „Ordner wählen“ denselben Syncthing- bzw. Synology-Drive-Ordner wählen. A offline nehmen, auf B etwas erfassen, B schließen, dann A wieder online und öffnen. | Eintrag kommt über den Ordner auf A an, ohne dass B erreichbar war. Im Ordner liegen nur `.fas`/`.fap`-Dateien, kein Klartext. |
| 6 | Wie 5, aber App auf A nicht öffnen und 15–30 Minuten warten (Akku-Optimierung für FamilyApp auf „Nicht eingeschränkt“). | Der Eintrag ist beim nächsten Öffnen sofort da. Es kommt keine Benachrichtigung über den Abgleich. |
| 7 | Hausgerät (Surface): `tools/family-peer/README.md` folgen, `serve` laufen lassen. Dann A und B nie gleichzeitig öffnen. | Einträge wandern über das Hausgerät. Auf dem Handy steht es als „Hausgerät“ ohne Person in der Geräteliste. |
| 8 | Handy drehen beim Scannen, Code als Text kopieren und auf B einfügen statt scannen. | Scanner folgt der Drehung. Einfügen funktioniert wie Scannen. |

Bitte bei Abweichungen den Text der Meldung und welche Schritte davor liefen notieren.
