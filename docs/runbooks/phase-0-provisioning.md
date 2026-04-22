# Phase 0 — Provision the VPS

Bring a fresh Hetzner CX22 (Ubuntu 24.04 LTS, Nuremberg `nbg1`, per ADR 0001) from "SSH login works" to "entlib is reachable at `https://<domain>` and a user can log in."

Do the manual bits first (the agent cannot): register a domain, create a Hetzner Cloud account and generate an API token (Security → API tokens, read+write), obtain a TMDB API key.

Then run section 0 below to provision the VPS and Storage Box via Terraform, point DNS at the server IPs it prints, and continue from section 1 on the new box.

Run everything in sections 1+ as `root` on the VPS unless a step specifies otherwise.

---

## 0. Provision infrastructure with Terraform

One-time prereqs on your workstation:

- Terraform ≥ 1.6
- Hetzner Cloud API token (see intro)
- An SSH public key you want installed on the server and Storage Box
- A password for the Storage Box web UI (generate with `openssl rand -base64 24`)

```bash
cd deploy/terraform
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars — set hcloud_token, ssh_public_key, storage_box_password.
# Prefer exporting TF_VAR_hcloud_token over writing the token to disk.

terraform init
terraform plan
terraform apply
```

Record the four outputs — you need them later:

- `server_ipv4` and `server_ipv6` → create DNS A + AAAA records for your domain pointing at these. Wait for propagation before section 5 so Caddy can obtain a Let's Encrypt cert.
- `storage_box_host` and `storage_box_username` → go into `/etc/entlib/backup.env` in section 10.

State lives locally in `deploy/terraform/terraform.tfstate`. Back it up out-of-band; losing it means losing the ability to cleanly `terraform apply` against these resources (you'd have to `terraform import` or destroy + recreate).

SSH to the server as `root@<server_ipv4>` using the key you passed in and continue below.

## 1. Base packages

```bash
apt update
apt upgrade -y
apt install -y curl ca-certificates sqlite3 ufw debian-keyring debian-archive-keyring apt-transport-https
```

## 2. Firewall

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
```

## 3. Service user and directories

```bash
useradd --system --home /var/lib/entlib --shell /usr/sbin/nologin entlib
install -d -o entlib -g entlib -m 0750 /var/lib/entlib
install -d -o root   -g entlib -m 0750 /etc/entlib
```

## 4. App env file — `/etc/entlib/env`

Created once. Readable by the service user only.

```bash
cat >/etc/entlib/env <<'EOF'
ENTLIB_DB_DSN=/var/lib/entlib/db.sqlite
ENTLIB_HTTP_ADDR=127.0.0.1:8080
ENTLIB_COOKIE_SECURE=true
TMDB_API_KEY=<paste TMDB v3 api key>
EOF
chown root:entlib /etc/entlib/env
chmod 0640 /etc/entlib/env
```

## 5. Install Caddy

```bash
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  >/etc/apt/sources.list.d/caddy-stable.list
apt update
apt install -y caddy
```

Copy the repo's Caddyfile and set the domain:

```bash
install -m 0644 deploy/caddy/Caddyfile /etc/caddy/Caddyfile
echo "ENTLIB_DOMAIN=entlib.example.com" >/etc/default/caddy   # substitute real domain
install -d -m 0755 /var/log/caddy
chown caddy:caddy /var/log/caddy
systemctl restart caddy
```

Caddy will request a Let's Encrypt certificate on first request to the domain.

## 6. First binary

Two options, same result:

**From your workstation:**

```bash
make build                         # produces ./entlib locally
scp entlib root@<host>:/tmp/entlib.new
```

**From a CI artifact:** download `entlib-linux-amd64` from the latest green CI run (see `.github/workflows/ci.yml`), `scp` it to `/tmp/entlib.new` on the VPS.

Then on the VPS:

```bash
install -m 0755 -o root -g root /tmp/entlib.new /usr/local/bin/entlib
rm -f /tmp/entlib.new
```

## 7. systemd unit

```bash
install -m 0644 deploy/systemd/entlib.service /etc/systemd/system/entlib.service
systemctl daemon-reload
systemctl enable entlib
```

Don't start it yet — the DB isn't seeded.

## 8. Seed the first users

Run as the service user so the DB file ends up owned by `entlib`. See [`seed-users.md`](./seed-users.md) for the full command + password rules.

```bash
sudo -u entlib env ENTLIB_DB_DSN=/var/lib/entlib/db.sqlite \
  /usr/local/bin/entlib seed --display-name Kira --username kira --password '<≥14 chars>'
# repeat for the second user
```

## 9. Start and verify

```bash
systemctl start entlib
systemctl status entlib --no-pager
curl -fsS https://<domain>/api/health   # {"status":"ok"}
```

Then open `https://<domain>/` in a browser and log in with one of the seeded accounts.

## 10. Nightly backup

Backup env file:

```bash
cat >/etc/entlib/backup.env <<'EOF'
ENTLIB_DB_PATH=/var/lib/entlib/db.sqlite
ENTLIB_BACKUP_USER=u123456                            # Hetzner Storage Box username
ENTLIB_BACKUP_HOST=u123456.your-storagebox.de
ENTLIB_BACKUP_DIR=/home/entlib
ENTLIB_BACKUP_SSHKEY=/etc/entlib/backup_ed25519
EOF
chown root:entlib /etc/entlib/backup.env
chmod 0640 /etc/entlib/backup.env
```

Generate an SSH key for the Storage Box, install the backup script, and register a cron job:

```bash
sudo -u entlib ssh-keygen -t ed25519 -N '' -f /etc/entlib/backup_ed25519
# Paste /etc/entlib/backup_ed25519.pub into the Hetzner Storage Box UI.

install -m 0755 deploy/backup/nightly-backup.sh /usr/local/bin/entlib-nightly-backup
cat >/etc/cron.d/entlib-backup <<'EOF'
15 3 * * * entlib /usr/local/bin/entlib-nightly-backup >>/var/log/entlib-backup.log 2>&1
EOF
```

Run the script once manually and confirm the snapshot lands on the Storage Box before walking away.

## 11. Hand-off checklist

- [ ] Domain resolves to the VPS
- [ ] `https://<domain>/api/health` returns `{"status":"ok"}`
- [ ] Both users can log in through the browser
- [ ] `journalctl -u entlib -n 50` is clean
- [ ] First manual backup uploaded and visible on the Storage Box
- [ ] Restore drill completed per [`restore-from-backup.md`](./restore-from-backup.md)
- [ ] `deploy/terraform/terraform.tfstate` backed up somewhere that is not your workstation
