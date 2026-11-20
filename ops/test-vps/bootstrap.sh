#!/usr/bin/env bash
# Hardens a fresh Debian 13 test VPS for the FamilyApp relay.
#
# Run once as root, from a session that comes in over Tailscale:
#   bash bootstrap.sh --admin-user nils --deploy-key "ssh-ed25519 AAAA... familyapp-ci-deploy"
#
# What it does (idempotent, safe to re-run):
#   - updates the system, enables unattended security upgrades
#   - installs Docker Engine + Compose plugin from download.docker.com
#   - creates an admin user (sudo, keeps its own SSH keys and adds root's) and a deploy user
#     (docker group, key restricted to the relay-deploy forced command)
#   - SSH: keys only, no root login, only these two users
#   - ufw: SSH only via tailscale0; public 80/443 for Caddy in front of the relay
set -euo pipefail

ADMIN_USER=""
DEPLOY_KEY=""
DEPLOY_USER="deploy"
APP_DIR="/opt/familyapp"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  echo "usage: $0 --admin-user NAME --deploy-key 'ssh-ed25519 AAAA... comment'" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --admin-user) ADMIN_USER="${2:-}"; shift 2 ;;
    --deploy-key) DEPLOY_KEY="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Preflight: refuse anything that could lock us out -----------------------
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$ADMIN_USER" =~ ^[a-z][a-z0-9_-]{0,30}$ ]] || usage
[[ "$ADMIN_USER" != "$DEPLOY_USER" && "$ADMIN_USER" != "root" ]] || die "admin user must not be root or $DEPLOY_USER"
[[ "$DEPLOY_KEY" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+(\ .*)?$ ]] || die "--deploy-key must be one ssh-ed25519 public key line"
[[ -f "$SCRIPT_DIR/relay-deploy" ]] || die "relay-deploy must lie next to this script"

# shellcheck source=/dev/null
. /etc/os-release
[[ "${ID:-}" == "debian" && "${VERSION_CODENAME:-}" == "trixie" ]] || die "expected Debian 13 (trixie), found ${PRETTY_NAME:-unknown}"

command -v tailscale >/dev/null || die "tailscale not installed; SSH will only be reachable via the tailnet"
tailscale status >/dev/null 2>&1 || die "tailscale is not up (run: tailscale up)"
ip link show tailscale0 >/dev/null 2>&1 || die "interface tailscale0 missing"
TS_IP="$(tailscale ip -4 | head -n1)"

if [[ -n "${SSH_CONNECTION:-}" ]]; then
  CLIENT_IP="${SSH_CONNECTION%% *}"
  # Tailscale CGNAT range 100.64.0.0/10 = 100.64.x.x .. 100.127.x.x
  if ! [[ "$CLIENT_IP" =~ ^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\. ]]; then
    die "this SSH session comes from $CLIENT_IP, not via Tailscale. Reconnect via the tailnet ($TS_IP) first."
  fi
fi

# The admin user gets the union of root's keys and any keys it already has, so an
# existing login of that user keeps working.
ADMIN_KEYS_FILE="/home/$ADMIN_USER/.ssh/authorized_keys"
if ! grep -qsEv '^[[:space:]]*(#|$)' /root/.ssh/authorized_keys "$ADMIN_KEYS_FILE"; then
  die "no SSH keys in /root/.ssh/authorized_keys or $ADMIN_KEYS_FILE; $ADMIN_USER could not log in"
fi

export DEBIAN_FRONTEND=noninteractive

# --- System updates -----------------------------------------------------------
log "Updating system"
apt-get update -q
apt-get full-upgrade -yq
apt-get install -yq ca-certificates curl ufw unattended-upgrades sudo

cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONF

# --- Docker Engine (official repo, deb822) ------------------------------------
log "Installing Docker Engine + Compose plugin"
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<CONF
Types: deb
URIs: https://download.docker.com/linux/debian
Suites: ${VERSION_CODENAME}
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
CONF
apt-get update -q
apt-get install -yq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

if [[ ! -f /etc/docker/daemon.json ]]; then
  # Bounded container logs so a chatty relay cannot fill the disk.
  cat > /etc/docker/daemon.json <<'CONF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
CONF
  systemctl restart docker
fi
systemctl enable --now docker

# --- Users --------------------------------------------------------------------
log "Admin user $ADMIN_USER"
id "$ADMIN_USER" >/dev/null 2>&1 || adduser --disabled-password --comment "" "$ADMIN_USER"
usermod -aG sudo "$ADMIN_USER"
# Key-only login means no password to type for sudo; access is guarded by the SSH key + tailnet.
echo "$ADMIN_USER ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/90-$ADMIN_USER"
chmod 0440 "/etc/sudoers.d/90-$ADMIN_USER"
visudo -cf "/etc/sudoers.d/90-$ADMIN_USER" >/dev/null
install -d -m 0700 -o "$ADMIN_USER" -g "$ADMIN_USER" "/home/$ADMIN_USER/.ssh"
MERGED_KEYS="$(cat /root/.ssh/authorized_keys "$ADMIN_KEYS_FILE" 2>/dev/null | grep -Ev '^[[:space:]]*(#|$)' | awk '!seen[$0]++')"
printf '%s\n' "$MERGED_KEYS" > "$ADMIN_KEYS_FILE.new"
install -m 0600 -o "$ADMIN_USER" -g "$ADMIN_USER" "$ADMIN_KEYS_FILE.new" "$ADMIN_KEYS_FILE"
rm -f "$ADMIN_KEYS_FILE.new"

log "Deploy user $DEPLOY_USER (forced command only)"
# A real shell is required: sshd runs the forced command via the user's shell.
id "$DEPLOY_USER" >/dev/null 2>&1 || adduser --disabled-password --comment "FamilyApp CI deploy" --shell /bin/sh "$DEPLOY_USER"
usermod -aG docker "$DEPLOY_USER"
install -m 0755 -o root -g root "$SCRIPT_DIR/relay-deploy" /usr/local/bin/relay-deploy
install -d -m 0755 -o root -g root "/home/$DEPLOY_USER/.ssh"
printf 'restrict,command="/usr/local/bin/relay-deploy" %s\n' "$DEPLOY_KEY" > "/home/$DEPLOY_USER/.ssh/authorized_keys"
chown root:root "/home/$DEPLOY_USER/.ssh/authorized_keys"
chmod 0644 "/home/$DEPLOY_USER/.ssh/authorized_keys"

# compose.yaml and Caddyfile here belong to root: the deploy key can load images and
# restart the stack, but cannot change what the stack mounts or exposes.
install -d -m 0755 -o root -g root "$APP_DIR"

# --- SSH ----------------------------------------------------------------------
log "Hardening sshd"
# Debian includes sshd_config.d/*.conf first and the first value wins, so 10-* beats
# e.g. a cloud-init 50-cloud-init.conf that enables passwords.
cat > /etc/ssh/sshd_config.d/10-familyapp.conf <<CONF
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
AllowUsers $ADMIN_USER $DEPLOY_USER
MaxAuthTries 3
X11Forwarding no
CONF
sshd -t
for check in "passwordauthentication no" "permitrootlogin no" "kbdinteractiveauthentication no"; do
  sshd -T | grep -qx "$check" || die "sshd effective config lacks '$check'"
done

# --- Firewall -----------------------------------------------------------------
log "Configuring ufw"
ufw default deny incoming
ufw default allow outgoing
ufw allow in on tailscale0 comment 'tailnet (SSH for admin + CI)'
ufw allow 80/tcp comment 'Caddy HTTP (ACME + redirect)'
ufw allow 443/tcp comment 'Caddy HTTPS (relay)'
ufw allow 443/udp comment 'Caddy HTTP/3'
ufw allow 41641/udp comment 'tailscale direct connections'
ufw --force enable

systemctl reload ssh

# --- Summary ------------------------------------------------------------------
TS_NAME="$(tailscale status --json 2>/dev/null | sed -n 's/.*"DNSName": *"\([^"]*\)\.".*/\1/p' | head -n1)"
log "Done"
echo "Docker:  $(docker version --format '{{.Server.Version}}'), Compose $(docker compose version --short)"
echo "Tailnet: ${TS_NAME:-?} ($TS_IP)"
echo
echo "Test NOW in a second window, before closing this one:"
echo "  ssh $ADMIN_USER@${TS_NAME:-$TS_IP}"
echo
echo "GitHub repository variable TEST_VPS_HOST:"
echo "  ${TS_NAME:-$TS_IP}"
echo "GitHub repository variable TEST_VPS_KNOWN_HOSTS (one line):"
echo "  ${TS_NAME:-$TS_IP} $(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)"
