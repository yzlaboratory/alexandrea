#!/usr/bin/env bash
#
# nightly-backup.sh — snapshot the entlib SQLite DB, encrypt with age, and
# upload to a Hetzner Storage Box over scp. Also prunes snapshots older than
# 30 days on the remote.
#
# Invoked by /etc/cron.daily/entlib-backup (a one-line wrapper) or, preferably,
# by a systemd timer (deploy/systemd/entlib-backup.{service,timer} — add when
# runbooks land). All configuration is read from /etc/entlib/backup.env so
# this script carries no secrets.
#
# Required env (from /etc/entlib/backup.env):
#   ENTLIB_DB_PATH        absolute path to the live DB          e.g. /var/lib/entlib/db.sqlite
#   ENTLIB_BACKUP_RECIP   age recipient public key              e.g. age1...
#   ENTLIB_BACKUP_USER    Hetzner Storage Box username          e.g. u123456
#   ENTLIB_BACKUP_HOST    Hetzner Storage Box hostname          e.g. u123456.your-storagebox.de
#   ENTLIB_BACKUP_DIR     remote directory for snapshots        e.g. /home/entlib
#   ENTLIB_BACKUP_SSHKEY  ssh private key path                  e.g. /etc/entlib/backup_ed25519
# Optional:
#   ENTLIB_BACKUP_RETAIN  retention in days (default 30)
#
# The age recipient is a *public key*; the matching secret key lives off-box
# (the restore runbook documents where). Losing the VPS and the secret
# simultaneously is the failure mode backups cannot protect against — keep
# the secret key somewhere that is not this box.

set -euo pipefail

CONFIG_FILE="${ENTLIB_BACKUP_CONFIG:-/etc/entlib/backup.env}"
if [[ -r "$CONFIG_FILE" ]]; then
	# shellcheck disable=SC1090
	source "$CONFIG_FILE"
fi

: "${ENTLIB_DB_PATH:?ENTLIB_DB_PATH must be set (see $CONFIG_FILE)}"
: "${ENTLIB_BACKUP_RECIP:?ENTLIB_BACKUP_RECIP must be set}"
: "${ENTLIB_BACKUP_USER:?ENTLIB_BACKUP_USER must be set}"
: "${ENTLIB_BACKUP_HOST:?ENTLIB_BACKUP_HOST must be set}"
: "${ENTLIB_BACKUP_DIR:?ENTLIB_BACKUP_DIR must be set}"
: "${ENTLIB_BACKUP_SSHKEY:?ENTLIB_BACKUP_SSHKEY must be set}"
RETAIN_DAYS="${ENTLIB_BACKUP_RETAIN:-30}"

log() { printf '[entlib-backup] %s\n' "$*" >&2; }

command -v sqlite3 >/dev/null || { log "sqlite3 not installed"; exit 1; }
command -v age      >/dev/null || { log "age not installed";      exit 1; }
command -v scp      >/dev/null || { log "scp not installed";      exit 1; }
command -v ssh      >/dev/null || { log "ssh not installed";      exit 1; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORKDIR="$(mktemp -d -t entlib-backup.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT

SNAPSHOT="$WORKDIR/entlib-$STAMP.sqlite"
ENCRYPTED="$SNAPSHOT.age"
REMOTE_NAME="entlib-$STAMP.sqlite.age"

log "snapshot: $ENTLIB_DB_PATH -> $SNAPSHOT"
# .backup is safe under WAL; it produces a transactionally consistent copy
# without blocking writers.
sqlite3 "$ENTLIB_DB_PATH" ".backup '$SNAPSHOT'"

log "encrypt: $SNAPSHOT -> $ENCRYPTED"
age -r "$ENTLIB_BACKUP_RECIP" -o "$ENCRYPTED" "$SNAPSHOT"

SSH_OPTS=(-i "$ENTLIB_BACKUP_SSHKEY" -o StrictHostKeyChecking=yes -o BatchMode=yes)

log "upload: $ENCRYPTED -> $ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST:$ENTLIB_BACKUP_DIR/$REMOTE_NAME"
scp "${SSH_OPTS[@]}" "$ENCRYPTED" \
	"$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST:$ENTLIB_BACKUP_DIR/$REMOTE_NAME"

log "prune: removing snapshots older than $RETAIN_DAYS days on $ENTLIB_BACKUP_HOST"
# Hetzner Storage Box's restricted shell supports find(1).
ssh "${SSH_OPTS[@]}" "$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST" \
	"find '$ENTLIB_BACKUP_DIR' -maxdepth 1 -name 'entlib-*.sqlite.age' -type f -mtime +$RETAIN_DAYS -delete"

log "done: $REMOTE_NAME"
