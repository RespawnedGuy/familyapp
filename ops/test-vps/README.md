# Test-VPS: Zugang und Deploy

Hier läuft das Relay (ADR 0002) zum Testen. Diese Notiz enthält keine Geheimnisse: Schlüssel liegen nur bei GitHub (Secrets) und auf Nils' PC.

## Wie der Zugang funktioniert

```
Handys (ohne Tailscale) ──HTTPS 443──▶ Caddy ──▶ Relay   (öffentlich, Subdomain von Nils)

Nils (Admin) ───────┐
                    ├─SSH 22, nur über Tailscale──▶ VPS
GitHub Actions ─────┘   (Runner = kurzlebiges Tailnet-Gerät tag:ci)
       ▲
Claude (Cloud) stößt den Workflow an und liest die Logs
```

- **Cloud-Sessions von Claude können kein SSH.** Sie haben nur ausgehendes HTTPS über einen Proxy. Deshalb geht jeder Zugriff aus der Cloud über den Workflow `test-vps` (Actions → test-vps → Run workflow), jede Ausführung steht im Actions-Verlauf.
- **SSH ist nur über das Tailnet erreichbar.** Der Server gehört allein dem Relay (Debian 13 ohne Plesk). `bootstrap.sh` setzt ufw auf: SSH nur auf `tailscale0`, öffentlich nur 80/443 für Caddy. Zusätzlich ist TCP 22 in der IONOS-Cloud-Firewall geschlossen.
- **Achtung Docker und ufw:** Ports, die ein Container veröffentlicht (`ports:` in compose.yaml), umgehen ufw. Deshalb veröffentlicht nur Caddy 80/443, alle anderen Dienste bleiben im Docker-Netz oder binden an `127.0.0.1`.
- **Zwei Benutzer, root-Login aus, nur Schlüssel:**
  - `nils`: Admin mit sudo ohne Passwort (Login nur per Schlüssel über das Tailnet). Behält eigene Schlüssel, die von root kommen dazu. Das Skript prüft als `nils`, ob `sudo` wirklich geht, **bevor** es den root-Login abschaltet, und bricht sonst ab.
  - `AllowUsers nils deploy`: andere Benutzer kommen per SSH gar nicht herein.
  - `deploy`: in der Docker-Gruppe, aber sein Schlüssel ist per `restrict,command=` an [`relay-deploy`](relay-deploy) gebunden. Erlaubt sind nur `ping`, `status`, `logs [dienst]`, `load` (Image über stdin), `up`. Keine Shell, keine Weiterleitungen.
- **`/opt/familyapp/compose.yaml` gehört root.** Der Deploy-Schlüssel kann Images laden und den Stack neu starten, aber nicht ändern, was der Stack einbindet oder öffnet. Änderungen daran macht der Admin.
- **Tailscale-Zugang ohne gespeichertes Geheimnis:** Der Workflow meldet sich per Workload Identity Federation (GitHub-OIDC) an, nur vom Branch `main`. Die Tailscale-Policy erlaubt `tag:ci` nur `tcp:22` auf `tag:familyapp-vps`.
- **Nachvollziehbar:** jede Aktion von `deploy` steht im Journal (`journalctl -t relay-deploy`), jeder Lauf im Actions-Verlauf.

Bekannte Grenze: Wer in der Docker-Gruppe beliebige Befehle ausführen kann, ist faktisch root. Das ist der Grund für die Forced-Command-Sperre; `relay-deploy` darf deshalb nie eine Aktion bekommen, die freie Argumente an Docker durchreicht.

## Einrichtung (einmalig, macht Nils)

Reihenfolge so wählen, dass der Server nie ohne Zugang dasteht. `<vps>` ist im Folgenden `tender-cori`.

### 1. Tailscale vorbereiten (Admin-Konsole)

1. Alten Eintrag des Servers löschen (Machines → … → Remove), damit der Name frei wird.
2. **Policy** (Access controls):

   ```jsonc
   "tagOwners": {
     "tag:ci":            ["autogroup:admin"],
     "tag:familyapp-vps": ["autogroup:admin"]
   },
   "grants": [
     {"src": ["autogroup:member"], "dst": ["*"], "ip": ["*"]},
     {"src": ["tag:ci"], "dst": ["tag:familyapp-vps"], "ip": ["tcp:22"]}
   ]
   ```

   Die Standardregel `"src": ["*"], "dst": ["*"]` muss raus, sonst darf auch der CI-Runner alles. `autogroup:member` ersetzt sie: eigene Geräte dürfen alles, getaggte Geräte nur, was eine eigene Regel erlaubt.
3. **Federated Identity:** Settings → Trust credentials → OpenID Connect, Aussteller GitHub. Subject `repo:RespawnedGuy/familyapp:ref:refs/heads/main`, Scope Auth Keys (write), Tag `tag:ci`. Client-ID und Audience notieren (beides kein Geheimnis).
4. **Auth Key für den Server:** Settings → Keys → Generate auth key, einmalig, Tag `tag:familyapp-vps`. Nur für Schritt 4, nicht speichern.

### 2. PC vorbereiten (PowerShell, im Repo-Klon)

```powershell
ssh-keygen -R tender-cori
git pull
ssh-keygen -t ed25519 -C "familyapp-ci-deploy" -f "$env:USERPROFILE\.ssh\familyapp_ci_deploy"
```

`ssh-keygen -R` auch für die alte Tailnet-IP und die öffentliche IP ausführen. Beim Deploy-Schlüssel die Passphrase leer lassen (der Runner kann keine eingeben).

### 3. IONOS: neu installieren

Cloud Panel → Server → Neu installieren: Image **Debian 13** (ohne Plesk), SSH-Schlüssel `id_ed25519` von Brainbug hinterlegen. Firewall-Richtlinie: 80 und 443 offen, 8443 löschen, 22 vorerst offen lassen.

### 4. Tailscale auf dem Server (über die öffentliche IP, als root)

```bash
hostnamectl set-hostname tender-cori
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up --auth-key=<key aus 1.4>
```

Danach abmelden und als root über das Tailnet neu verbinden: `ssh root@tender-cori`.

### 5. Härten (über das Tailnet)

```powershell
scp ops/test-vps/bootstrap.sh ops/test-vps/relay-deploy root@tender-cori:/root/
ssh root@tender-cori
```

```bash
bash /root/bootstrap.sh --admin-user nils --deploy-key "<Inhalt von familyapp_ci_deploy.pub>"
```

Das Skript bricht ab, wenn die Sitzung nicht über Tailscale kommt, Plesk gefunden wird oder `sudo` für `nils` nicht geht. **Die root-Sitzung offen lassen** und in einem zweiten Fenster `ssh nils@tender-cori` und `sudo -v` testen. Notfalls hilft die Remote-Konsole im IONOS-Panel. Am Ende stehen die Werte für `TEST_VPS_HOST` und `TEST_VPS_KNOWN_HOSTS` in der Ausgabe (beides öffentlich).

### 6. IONOS-Firewall

Erst wenn Schritt 5 klappt: Regel für TCP 22 in der IONOS-Firewall entfernen.

### 7. GitHub (Repo → Settings → Secrets and variables → Actions)

| Art | Name | Inhalt |
| --- | --- | --- |
| Secret | `TEST_VPS_SSH_KEY` | Inhalt von `familyapp_ci_deploy` (privater Teil) |
| Variable | `TEST_VPS_HOST` | Ausgabe von bootstrap.sh |
| Variable | `TEST_VPS_KNOWN_HOSTS` | Ausgabe von bootstrap.sh (eine Zeile) |
| Variable | `TS_FEDERATED_CLIENT_ID` | aus Schritt 1.3 |
| Variable | `TS_FEDERATED_AUDIENCE` | aus Schritt 1.3 |

Privaten Schlüssel in die Zwischenablage:

```powershell
Get-Content "$env:USERPROFILE\.ssh\familyapp_ci_deploy" -Raw | Set-Clipboard
```

Danach kann die private Datei vom PC gelöscht werden; bei Bedarf wird einfach ein neues Paar erzeugt.

### 8. Verbindungstest

Actions → test-vps → Run workflow → `ping`. Erwartet: `ok tender-cori <zeit>`, Docker- und Compose-Version.

## Schlüssel wechseln oder Zugang sperren

- Neues Schlüsselpaar erzeugen, öffentlichen Teil auf dem VPS in `/home/deploy/.ssh/authorized_keys` hinter `restrict,command="/usr/local/bin/relay-deploy" ` eintragen, Secret ersetzen.
- Sofort sperren: Zeile in `/home/deploy/.ssh/authorized_keys` löschen oder die Federated Identity in Tailscale entfernen.
