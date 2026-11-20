# Test-VPS: Zugang und Deploy

Hier läuft das Relay (ADR 0002) zum Testen. Diese Notiz enthält keine Geheimnisse: Schlüssel liegen nur bei GitHub (Secrets) und auf Nils' PC.

## Wie der Zugang funktioniert

```
Handys (ohne Tailscale) ──HTTPS 443──▶ Caddy ──▶ Relay        (öffentlich, über die Domain)

Nils (Admin) ───────┐
                    ├─SSH 22, nur über Tailscale──▶ VPS
GitHub Actions ─────┘   (Runner = kurzlebiges Tailnet-Gerät tag:ci)
       ▲
Claude (Cloud) stößt den Workflow an und liest die Logs
```

- **Cloud-Sessions von Claude können kein SSH.** Sie haben nur ausgehendes HTTPS über einen Proxy. Deshalb geht jeder Zugriff aus der Cloud über den Workflow `test-vps` (Actions → test-vps → Run workflow), jede Ausführung steht im Actions-Verlauf.
- **SSH ist nur über das Tailnet erreichbar.** ufw lässt Port 22 nur auf `tailscale0` zu, öffentlich offen sind nur 80/443 (Caddy) und 41641/udp (Tailscale).
- **Zwei Benutzer, root-Login aus, nur Schlüssel:**
  - `nils`: Admin mit sudo, Schlüssel von root übernommen.
  - `deploy`: in der Docker-Gruppe, aber sein Schlüssel ist per `restrict,command=` an [`relay-deploy`](relay-deploy) gebunden. Erlaubt sind nur `ping`, `status`, `logs [dienst]`, `load` (Image über stdin), `up`. Keine Shell, keine Weiterleitungen.
- **`/opt/familyapp/compose.yaml` und `Caddyfile` gehören root.** Der Deploy-Schlüssel kann Images laden und den Stack neu starten, aber nicht ändern, was der Stack einbindet oder öffnet. Änderungen daran macht der Admin.
- **Tailscale-Zugang ohne gespeichertes Geheimnis:** Der Workflow meldet sich per Workload Identity Federation (GitHub-OIDC) an, nur vom Branch `main`. Die Tailscale-Policy erlaubt `tag:ci` nur `tcp:22` auf `tag:familyapp-vps`.
- **Nachvollziehbar:** jede Aktion von `deploy` steht im Journal (`journalctl -t relay-deploy`), jeder Lauf im Actions-Verlauf.

Bekannte Grenze: Wer in der Docker-Gruppe beliebige Befehle ausführen kann, ist faktisch root. Das ist der Grund für die Forced-Command-Sperre; `relay-deploy` darf deshalb nie eine Aktion bekommen, die freie Argumente an Docker durchreicht.

## Einrichtung (einmalig, macht Nils)

### 1. Deploy-Schlüssel auf dem PC erzeugen (PowerShell)

```powershell
ssh-keygen -t ed25519 -C "familyapp-ci-deploy" -f "$env:USERPROFILE\.ssh\familyapp_ci_deploy"
```

Bei der Passphrase zweimal Enter drücken (der Runner kann keine eingeben). Den öffentlichen Teil braucht Schritt 2:

```powershell
Get-Content "$env:USERPROFILE\.ssh\familyapp_ci_deploy.pub"
```

### 2. VPS härten (per Tailscale als root verbinden)

Skripte vom PC auf den VPS kopieren (im Repo-Klon, `<vps>` = Tailnet-Name des VPS):

```powershell
scp ops/test-vps/bootstrap.sh ops/test-vps/relay-deploy root@<vps>:/root/
ssh root@<vps>
```

Auf dem VPS:

```bash
bash /root/bootstrap.sh --admin-user nils --deploy-key "ssh-ed25519 AAAA... familyapp-ci-deploy"
```

Das Skript bricht ab, wenn die Sitzung nicht über Tailscale kommt, Tailscale nicht läuft oder root keine Schlüssel hat. **Diese root-Sitzung offen lassen** und in einem zweiten Fenster `ssh nils@<vps>` und `sudo -v` testen. Notfalls hilft die Remote-Konsole im IONOS-Panel.

Am Ende gibt das Skript die Werte für `TEST_VPS_HOST` und `TEST_VPS_KNOWN_HOSTS` aus (beides öffentlich).

### 3. IONOS-Firewall

Im IONOS Cloud Panel unter Netzwerk → Firewall-Richtlinien: TCP 80, TCP 443, UDP 443 und UDP 41641 erlauben. Port 22 kann öffentlich zu, sobald `ssh nils@<vps>` über Tailscale klappt.

### 4. Tailscale (Admin-Konsole)

1. **Policy** (Access controls), ergänzen:

   ```jsonc
   "tagOwners": {
     "tag:ci":            ["autogroup:admin"],
     "tag:familyapp-vps": ["autogroup:admin"]
   },
   "grants": [
     // ... bestehende Regeln ...
     {"src": ["tag:ci"], "dst": ["tag:familyapp-vps"], "ip": ["tcp:22"]}
   ]
   ```

   **Wichtig:** Steht in der Policy noch die Standardregel `"src": ["*"], "dst": ["*"]`, darf jedes Gerät alles, auch der CI-Runner zu deinen PCs. Dann diese Regel durch `{"src": ["autogroup:member"], "dst": ["*"], "ip": ["*"]}` ersetzen: deine eigenen Geräte dürfen weiter alles, getaggte Geräte nur, was eine eigene Regel erlaubt.

2. **VPS taggen:** Machines → VPS → Edit ACL tags → `tag:familyapp-vps`.

3. **Federated Identity anlegen:** Settings → Trust credentials → Credential hinzufügen → OpenID Connect, Aussteller GitHub. Subject: `repo:RespawnedGuy/familyapp:ref:refs/heads/main`. Scope: Auth Keys (write), Tag `tag:ci`. Client-ID und Audience notieren.

### 5. GitHub (Repo → Settings → Secrets and variables → Actions)

| Art | Name | Inhalt |
| --- | --- | --- |
| Secret | `TEST_VPS_SSH_KEY` | Inhalt von `familyapp_ci_deploy` (privater Teil) |
| Variable | `TEST_VPS_HOST` | Ausgabe von bootstrap.sh |
| Variable | `TEST_VPS_KNOWN_HOSTS` | Ausgabe von bootstrap.sh (eine Zeile) |
| Variable | `TS_FEDERATED_CLIENT_ID` | aus Schritt 4.3 |
| Variable | `TS_FEDERATED_AUDIENCE` | aus Schritt 4.3 |

Privaten Schlüssel in die Zwischenablage:

```powershell
Get-Content "$env:USERPROFILE\.ssh\familyapp_ci_deploy" -Raw | Set-Clipboard
```

Danach kann die private Datei vom PC gelöscht werden; bei Bedarf wird einfach ein neues Paar erzeugt.

### 6. Verbindungstest

Actions → test-vps → Run workflow → `ping`. Erwartet: `ok <hostname> <zeit>`, Docker- und Compose-Version.

## Schlüssel wechseln oder Zugang sperren

- Neues Schlüsselpaar erzeugen, öffentlichen Teil auf dem VPS in `/home/deploy/.ssh/authorized_keys` hinter `restrict,command="/usr/local/bin/relay-deploy" ` eintragen, Secret ersetzen.
- Sofort sperren: Zeile in `/home/deploy/.ssh/authorized_keys` löschen oder die Federated Identity in Tailscale entfernen.
