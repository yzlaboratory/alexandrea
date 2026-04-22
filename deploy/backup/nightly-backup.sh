#!/usr/bin/env bash
#
# nightly-backup.sh — snapshot the entlib SQLite DB and upload it to a
# Hetzner Storage Box over scp. Also prunes snapshots older than 30 days
# on the remote.
#
# Invoked by /etc/cron.daily/entlib-backup (a one-line wrapper) or, preferably,
# by a systemd timer (deploy/systemd/entlib-backup.{service,timer} — add when
# runbooks land). All configuration is read from /etc/entlib/backup.env so
# this script carries no secrets.
#
# Required env (from /etc/entlib/backup.env):
#   ENTLIB_DB_PATH        absolute path to the live DB          e.g. /var/lib/entlib/db.sqlite
#   ENTLIB_BACKUP_USER    Hetzner Storage Box username          e.g. u123456
#   ENTLIB_BACKUP_HOST    Hetzner Storage Box hostname          e.g. u123456.your-storagebox.de
#   ENTLIB_BACKUP_DIR     remote directory for snapshots        e.g. /home/entlib
#   ENTLIB_BACKUP_SSHKEY  ssh private key path                  e.g. /etc/entlib/backup_ed25519
# Optional:
#   ENTLIB_BACKUP_RETAIN  retention in days (default 30)
#
# Snapshots are uploaded as plain SQLite files. The threat model ("two
# people's list of movies they've rated") does not justify the operational
# risk of managing encryption keys. If that changes, revisit.

set -euo pipefail

CONFIG_FILE="${ENTLIB_BACKUP_CONFIG:-/etc/entlib/backup.env}"
if [[ -r "$CONFIG_FILE" ]]; then
	# shellcheck disable=SC1090
	source "$CONFIG_FILE"
fi

: "${ENTLIB_DB_PATH:?ENTLIB_DB_PATH must be set (see $CONFIG_FILE)}"
: "${ENTLIB_BACKUP_USER:?ENTLIB_BACKUP_USER must be set}"
: "${ENTLIB_BACKUP_HOST:?ENTLIB_BACKUP_HOST must be set}"
: "${ENTLIB_BACKUP_DIR:?ENTLIB_BACKUP_DIR must be set}"
: "${ENTLIB_BACKUP_SSHKEY:?ENTLIB_BACKUP_SSHKEY must be set}"
RETAIN_DAYS="${ENTLIB_BACKUP_RETAIN:-30}"

log() { printf '[entlib-backup] %s\n' "$*" >&2; }

command -v sqlite3 >/dev/null || { log "sqlite3 not installed"; exit 1; }
command -v scp      >/dev/null || { log "scp not installed";      exit 1; }
command -v ssh      >/dev/null || { log "ssh not installed";      exit 1; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORKDIR="$(mktemp -d -t entlib-backup.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT

SNAPSHOT="$WORKDIR/entlib-$STAMP.sqlite"
REMOTE_NAME="entlib-$STAMP.sqlite"

log "snapshot: $ENTLIB_DB_PATH -> $SNAPSHOT"
# .backup is safe under WAL; it produces a transactionally consistent copy
# without blocking writers.
sqlite3 "$ENTLIB_DB_PATH" ".backup '$SNAPSHOT'"

SSH_OPTS=(-i "$ENTLIB_BACKUP_SSHKEY" -o StrictHostKeyChecking=yes -o BatchMode=yes)
REMOTE="$ENTLIB_BACKUP_USER@$ENTLIB_BACKUP_HOST"

log "upload: $SNAPSHOT -> $REMOTE:$ENTLIB_BACKUP_DIR/$REMOTE_NAME"
scp "${SSH_OPTS[@]}" "$SNAPSHOT" "$REMOTE:$ENTLIB_BACKUP_DIR/$REMOTE_NAME"

# Prune old snapshots. Hetzner Storage Box blocks ssh-exec, so we do
# list+delete over SFTP. Snapshot filenames embed a lexicographically
# sortable UTC stamp (entlib-YYYYMMDDTHHMMSSZ.sqlite), so we compute a
# cutoff stamp locally and delete anything older than it.
log "prune: removing snapshots older than $RETAIN_DAYS days on $ENTLIB_BACKUP_HOST"
CUTOFF="$(date -u -d "-${RETAIN_DAYS} days" +%Y%m%dT%H%M%SZ)"
LIST_OUT="$WORKDIR/list.out"
sftp -q "${SSH_OPTS[@]}" "$REMOTE" >"$LIST_OUT" 2>/dev/null <<EOF
ls -1 $ENTLIB_BACKUP_DIR/entlib-*.sqlite
EOF

BATCH="$WORKDIR/rm.batch"
: >"$BATCH"
while IFS= read -r path; do
	# sftp -q still prints "sftp> <command>" prompt lines; skip them by
	# requiring an exact-dir prefix and a snapshot-shaped filename. The
	# regex also drops stray whitespace or any line containing glob
	# metacharacters (the prompt line ends with "...entlib-*.sqlite").
	[[ "$path" =~ ^${ENTLIB_BACKUP_DIR}/entlib-[0-9]{8}T[0-9]{6}Z\.sqlite$ ]] || continue
	fname=${path##*/}
	stamp=${fname#entlib-}
	stamp=${stamp%.sqlite}
	if [[ "$stamp" < "$CUTOFF" ]]; then
		printf 'rm %s\n' "$path" >>"$BATCH"
	fi
done <"$LIST_OUT"

if [[ -s "$BATCH" ]]; then
	COUNT=$(wc -l <"$BATCH")
	log "prune: deleting $COUNT stale snapshot(s)"
	sftp -q -b "$BATCH" "${SSH_OPTS[@]}" "$REMOTE" >/dev/null
else
	log "prune: no snapshots older than cutoff $CUTOFF"
fi

log "done: $REMOTE_NAME"
