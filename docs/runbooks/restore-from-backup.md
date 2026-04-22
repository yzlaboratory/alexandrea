# Restore from backup

Bring the SQLite DB back from a `deploy/backup/nightly-backup.sh` snapshot.

Two realistic starting points:

1. **The same VPS** — db got corrupted or wiped, but the box is still there.
2. **A brand new VPS** — original VPS is gone. Provision per [`phase-0-provisioning.md`](./phase-0-provisioning.md) first, then come back here before step 8 ("Seed the first users") — restore replaces seeding.

Both paths converge after step 3.

Prerequisites:

- `sqlite3`, `scp` on the VPS (phase 0 installs them).
- Storage Box credentials in `/etc/entlib/backup.env` so the same key/username/host that wrote the snapshots can read them back.

Run as `root` unless noted.

---

## 1. Pick a snapshot

Hetzner Storage Boxes don't allow arbitrary `ssh` commands — only SFTP/SCP. List via SFTP:

```bash
. /etc/entlib/backup.env
sftp -q -i "$ENTLIB_BACKUP_SSHKEY" "$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST" <<EOF
ls -la $ENTLIB_BACKUP_DIR
EOF
```

Pick one. Default is "most recent" unless you're intentionally rolling back further. Snapshot filenames embed a lexicographically sortable UTC timestamp, so `sort | tail -1` over a file list gives you the newest.

## 2. Stop the service

```bash
systemctl stop entlib
```

Leave it stopped until step 5 — restoring under a running writer corrupts the DB.

## 3. Fetch the snapshot

```bash
SNAP=entlib-20260418T030000Z.sqlite   # whichever you picked
scp -i "$ENTLIB_BACKUP_SSHKEY" \
  "$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST:$ENTLIB_BACKUP_DIR/$SNAP" \
  /tmp/
```

## 4. Sanity-check the snapshot

```bash
sqlite3 /tmp/$SNAP 'PRAGMA integrity_check;'
# expect: ok
sqlite3 /tmp/$SNAP 'SELECT count(*) FROM "user";'
```

A non-`ok` integrity check means the snapshot itself is bad — try the next-newest one.

## 5. Install the DB

Take a defensive copy of whatever is currently there (even if it's garbage):

```bash
if [ -f /var/lib/entlib/db.sqlite ]; then
  mv /var/lib/entlib/db.sqlite /var/lib/entlib/db.sqlite.pre-restore.$(date -u +%Y%m%dT%H%M%SZ)
  rm -f /var/lib/entlib/db.sqlite-wal /var/lib/entlib/db.sqlite-shm
fi

install -m 0640 -o entlib -g entlib /tmp/$SNAP /var/lib/entlib/db.sqlite
rm -f /tmp/$SNAP
```

## 6. Start the service

```bash
systemctl start entlib
systemctl status entlib --no-pager
curl -fsS https://<domain>/api/health
```

Log in through the browser with an account from the snapshot era. Confirm `/api/me` returns the expected `display_name`.

## 7. After a successful restore

- Delete the `db.sqlite.pre-restore.*` fallback once you're confident (keep for a week if unsure).
- Re-run `/usr/local/bin/entlib-nightly-backup` manually so the first post-restore snapshot exists immediately, not 24 hours later.

## Restore drill (run this at phase-0 hand-off, and every quarter)

Fetch the most recent snapshot into `/tmp`, run `PRAGMA integrity_check`, and diff the table row counts against the live DB. No service interruption required. If the drill fails, the backup pipeline is broken — stop and fix before trusting it.

One-liner on the VPS (run as `root`; scps as `entlib` so file ownership stays sane):

```bash
. /etc/entlib/backup.env
LATEST=$(sudo -u entlib sftp -q -i "$ENTLIB_BACKUP_SSHKEY" "$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST" <<EOF 2>/dev/null | grep -oE 'entlib-[0-9]{8}T[0-9]{6}Z\.sqlite' | sort | tail -1
ls -1 $ENTLIB_BACKUP_DIR/entlib-*.sqlite
EOF
)
TMP=$(sudo -u entlib mktemp -d)
sudo -u entlib scp -q -i "$ENTLIB_BACKUP_SSHKEY" \
  "$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST:$ENTLIB_BACKUP_DIR/$LATEST" "$TMP/$LATEST"
sudo -u entlib sqlite3 "$TMP/$LATEST" 'PRAGMA integrity_check;'
for T in user user_credential session title library_entry rating; do
  S=$(sudo -u entlib sqlite3 "$TMP/$LATEST" "SELECT count(*) FROM $T")
  L=$(sudo -u entlib sqlite3 /var/lib/entlib/db.sqlite "SELECT count(*) FROM $T")
  printf '  %-18s snap=%s live=%s\n' "$T" "$S" "$L"
done
sudo -u entlib rm -rf "$TMP"
```

Expect `ok` from `integrity_check` and matching counts for every table the restore would replace.
