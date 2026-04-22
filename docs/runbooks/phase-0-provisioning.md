# Phase 0 — Provision the VPS

Bring a fresh Hetzner CX22 (Ubuntu 24.04 LTS, Nuremberg `nbg1`, per ADR 0001) from "SSH login works" to "entlib is reachable at `https://<domain>` and a user can log in."

Do the manual bits first (the agent cannot): register a domain, create a Hetzner Cloud account and generate an API token (Security → API tokens, read+write), obtain a TMDB API key.

Then run section 0 below to provision the VPS and Storage Box via Terraform, point DNS at the server IPs it prints, and continue from section 1 on the new box.

Run everything in sections 1+ as `root` on the VPS unless a step specifies otherwise.

---

## 0. Provision infrastructure with Terraform

One-time prereqs on your workstation:

- Terraform ≥ 1.10 (needed for native S3 state locking)
- Hetzner Cloud API token (see intro)
- An SSH public key you want installed on the server and Storage Box
- A password for the Storage Box web UI (generate with `openssl rand -base64 24`)
- AWS credentials with read/write on the state bucket (see below)

State lives in the S3 bucket configured in `versions.tf` (region `eu-central-1`, object `entlib/terraform.tfstate`, versioned, SSE-S3 encrypted, public access blocked, native `use_lockfile` state locking). Point your AWS CLI at an identity that can `s3:GetObject`/`s3:PutObject`/`s3:DeleteObject` on that key and `s3:ListBucket` on the bucket.

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

With Caddy fronting (domain + TLS):

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

**No-domain variant** (serve plain HTTP on the public IP until DNS/TLS lands):

```bash
cat >/etc/entlib/env <<'EOF'
ENTLIB_DB_DSN=/var/lib/entlib/db.sqlite
ENTLIB_HTTP_ADDR=0.0.0.0:8080
ENTLIB_COOKIE_SECURE=false
TMDB_API_KEY=<paste TMDB v3 api key>
EOF
chown root:entlib /etc/entlib/env
chmod 0640 /etc/entlib/env
```

`ENTLIB_COOKIE_SECURE=false` is mandatory over plain HTTP — `Secure` cookies
silently fail and login looks broken from the browser. Flip both values back
(`127.0.0.1:8080` / `true`) when installing Caddy in §5.

## 5. Install Caddy (skip if no domain yet)

If a domain is ready and DNS points here, install Caddy and let it request
a Let's Encrypt cert. If you're standing the box up *before* a domain is
chosen (the current deployment's situation), skip this section, have
entlib bind `0.0.0.0:8080` directly (§4), and open `8080/tcp` in UFW
(already done in §2). You can add Caddy later with zero data migration —
install the package, drop in the Caddyfile, flip `ENTLIB_COOKIE_SECURE=true`,
and close 8080/tcp.

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
ENTLIB_BACKUP_DIR=/entlib
ENTLIB_BACKUP_SSHKEY=/etc/entlib/backup_ed25519
EOF
chown root:entlib /etc/entlib/backup.env
chmod 0640 /etc/entlib/backup.env
```

`ENTLIB_BACKUP_DIR` is relative to the SFTP chroot on the Storage Box —
`/entlib`, not `/home/entlib`. The directory doesn't exist by default;
create it over SFTP from your workstation with `mkdir entlib`.

Generate an SSH key for the Storage Box. `/etc/entlib` is mode `0750
root:entlib` (read-only for the service user), so generate as root, then
hand ownership of the key to `entlib`:

```bash
ssh-keygen -t ed25519 -N '' -f /etc/entlib/backup_ed25519 -C "entlib-backup@$(hostname)"
chown entlib:entlib /etc/entlib/backup_ed25519 /etc/entlib/backup_ed25519.pub
chmod 0600 /etc/entlib/backup_ed25519
chmod 0644 /etc/entlib/backup_ed25519.pub
```

Append the pubkey to the Storage Box's `authorized_keys`. Two gotchas:

1. `hcloud_storage_box.ssh_keys` is marked `forces replacement` by the
   Terraform provider — changing it destroys and recreates the box (new
   username, new host, new credentials). Don't `terraform apply`. Edit
   `authorized_keys` over SFTP instead.
2. Hetzner Storage Boxes require every ed25519 pubkey in `authorized_keys`
   to be present in BOTH OpenSSH format *and* RFC 4716 (`---- BEGIN SSH2
   PUBLIC KEY ----`) format. If you only paste the OpenSSH line, auth
   fails with `Permission denied` even though `sshd` logs "Server accepts key".

Generate the RFC 4716 block with `ssh-keygen -e`, then append both forms
from your workstation (you already have the admin key authorized):

```bash
# On the VPS:
ssh-keygen -e -f /etc/entlib/backup_ed25519.pub > /tmp/backup_rfc4716.txt
# Copy both the OpenSSH line (cat .../backup_ed25519.pub) and the RFC 4716
# block (cat /tmp/backup_rfc4716.txt) somewhere you can paste from.

# On your workstation, edit the remote authorized_keys over SFTP:
WORK=$(mktemp -d); cd "$WORK"
sftp u$NUM@u$NUM.your-storagebox.de:.ssh/authorized_keys ak.txt
# ...append the OpenSSH line, a blank line, then the RFC 4716 block...
sftp u$NUM@u$NUM.your-storagebox.de <<EOF
put ak.txt .ssh/authorized_keys
chmod 600 .ssh/authorized_keys
EOF
```

Install the backup script and register the cron job:

```bash
install -m 0755 deploy/backup/nightly-backup.sh /usr/local/bin/entlib-nightly-backup
cat >/etc/cron.d/entlib-backup <<'EOF'
15 3 * * * entlib /usr/local/bin/entlib-nightly-backup >>/var/log/entlib-backup.log 2>&1
EOF
chmod 0644 /etc/cron.d/entlib-backup
install -m 0640 -o entlib -g adm /dev/null /var/log/entlib-backup.log
```

Run the script once manually and confirm the snapshot lands on the Storage
Box before walking away:

```bash
sudo -u entlib /usr/local/bin/entlib-nightly-backup
sudo -u entlib sftp -q -i /etc/entlib/backup_ed25519 u$NUM@u$NUM.your-storagebox.de:/entlib <<'EOF'
ls -la
EOF
```

## 11. Hand-off checklist

- [ ] Domain resolves to the VPS
- [ ] `https://<domain>/api/health` returns `{"status":"ok"}`
- [ ] Both users can log in through the browser
- [ ] `journalctl -u entlib -n 50` is clean
- [ ] First manual backup uploaded and visible on the Storage Box
- [ ] Restore drill completed per [`restore-from-backup.md`](./restore-from-backup.md)
