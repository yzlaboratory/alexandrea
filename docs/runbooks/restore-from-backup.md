# Restore from backup

Bring the SQLite DB back from a `deploy/backup/nightly-backup.sh` snapshot.

Two realistic starting points:

1. **The same VPS** — db got corrupted or wiped, but the box is still there.
2. **A brand new VPS** — original VPS is gone. Provision per [`phase-0-provisioning.md`](./phase-0-provisioning.md) first, then come back here before step 8 ("Seed the first users") — restore replaces seeding.

Both paths converge after step 3.

Prerequisites:

- `age`, `sqlite3`, `scp` on the VPS (phase 0 installs them).
- The **age secret key** (`identity.txt`) from wherever you stashed it at phase-0 hand-off. Not on the VPS. If this is lost, the backups cannot be decrypted — full stop.
- Storage Box credentials in `/etc/entlib/backup.env` so the same key/username/host that wrote the snapshots can read them back.

Run as `root` unless noted.

---

## 1. Pick a snapshot

```bash
. /etc/entlib/backup.env
ssh -i "$ENTLIB_BACKUP_SSHKEY" "$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST" \
  "ls -lt $ENTLIB_BACKUP_DIR/entlib-*.sqlite.age | head"
```

Pick one. Default is "most recent" unless you're intentionally rolling back further.

## 2. Stop the service

```bash
systemctl stop entlib
```

Leave it stopped until step 6 — restoring under a running writer corrupts the DB.

## 3. Fetch the snapshot

```bash
SNAP=entlib-20260418T030000Z.sqlite.age   # whichever you picked
scp -i "$ENTLIB_BACKUP_SSHKEY" \
  "$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST:$ENTLIB_BACKUP_DIR/$SNAP" \
  /tmp/
```

## 4. Decrypt

The age **secret key** (an `identity.txt` line beginning `AGE-SECRET-KEY-…`) lives off-box. Get it onto the VPS temporarily — prefer piping from your workstation so it never touches disk:

```bash
# From your workstation:
cat path/to/identity.txt | ssh root@<host> \
  "age -d -i /dev/stdin -o /tmp/entlib-restored.sqlite /tmp/$SNAP"
```

Or, if you must stage the key on the VPS, put it under `/root/` with `0600` and **delete it immediately after** this step.

## 5. Sanity-check the decrypted file

```bash
sqlite3 /tmp/entlib-restored.sqlite 'PRAGMA integrity_check;'
# expect: ok
sqlite3 /tmp/entlib-restored.sqlite 'SELECT count(*) FROM "user";'
```

A non-`ok` integrity check means the snapshot itself is bad — try the next-newest one.

## 6. Install the DB

Take a defensive copy of whatever is currently there (even if it's garbage):

```bash
if [ -f /var/lib/entlib/db.sqlite ]; then
  mv /var/lib/entlib/db.sqlite /var/lib/entlib/db.sqlite.pre-restore.$(date -u +%Y%m%dT%H%M%SZ)
  rm -f /var/lib/entlib/db.sqlite-wal /var/lib/entlib/db.sqlite-shm
fi

install -m 0640 -o entlib -g entlib /tmp/entlib-restored.sqlite /var/lib/entlib/db.sqlite
rm -f /tmp/entlib-restored.sqlite /tmp/$SNAP
```

## 7. Start the service

```bash
systemctl start entlib
systemctl status entlib --no-pager
curl -fsS https://<domain>/api/health
```

Log in through the browser with an account from the snapshot era. Confirm `/api/me` returns the expected `display_name`.

## 8. After a successful restore

- Delete the `db.sqlite.pre-restore.*` fallback once you're confident (keep for a week if unsure).
- If the age secret key ever touched the VPS disk, shred it: `shred -u /root/identity.txt` (or similar) and rotate the key pair at the next backup cycle.
- Re-run `/usr/local/bin/entlib-nightly-backup` manually so the first post-restore snapshot exists immediately, not 24 hours later.

## Restore drill (run this at phase-0 hand-off, and every quarter)

Decrypt the most recent snapshot into `/tmp`, run `PRAGMA integrity_check`, and diff the table row counts against the live DB. No service interruption required. If the drill fails, the backup pipeline is broken — stop and fix before trusting it.
