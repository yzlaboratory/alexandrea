# Seed users

`entlib seed` is the only way to create or rotate accounts (per ADR 0003 — there is no self-service signup and no password-reset UI in v0).

## Command

```bash
sudo -u entlib env ENTLIB_DB_DSN=/var/lib/entlib/db.sqlite \
  /usr/local/bin/entlib seed \
    --display-name 'Kira' \
    --username kira \
    --password '<password>'
```

What happens:

- If the username does not exist, a new `user` + `user_credential` row pair is created.
- If it exists, the display name and password are rotated in place. Sessions for that user survive the rotation; revoke them manually if rotation is reactive (see "Revoke sessions" below).
- **The `username` becomes the `user.id`.** Opaque to callers, but grep-able in the DB. Don't rely on it being human-readable from the UI.

Always run as the `entlib` service user so the SQLite file's ownership stays consistent.

## Password rules (ADR 0003)

- **Minimum 14 characters.** The binary rejects shorter inputs; do not try to work around it.
- No complexity rules. Length is the floor.
- Use a password manager — these are not memorized credentials.

Suggested generator:

```bash
openssl rand -base64 24 | tr -d '/+=' | cut -c1-20
```

20 chars of URL-safe base64 clears the floor with room to spare.

## When to run it

| Situation                                     | Command                                   |
|-----------------------------------------------|-------------------------------------------|
| First-time seed at phase-0                    | `seed --display-name … --username … …`    |
| Adding a second/third user                    | Same, with a new `--username`             |
| Password rotation (routine)                   | Same command, new `--password`            |
| Forgotten password                            | Same — no separate reset path exists      |
| Renaming display name only                    | Same, keep `--password` the same          |

## Avoiding passwords in shell history

`--password '…'` on the command line lands in `root`'s `~/.bash_history` and `ps auxf` transiently. Two safer forms:

**Read from a file:**

```bash
read -s -p 'password: ' PW && echo
sudo -u entlib env ENTLIB_DB_DSN=/var/lib/entlib/db.sqlite \
  /usr/local/bin/entlib seed --display-name Kira --username kira --password "$PW"
unset PW
history -d -1   # drop the last history entry; repeat if needed
```

**Leading space (bash HISTCONTROL=ignorespace):**

Prefix the command with a space. Bash skips adding it to history — check `echo $HISTCONTROL` contains `ignorespace` first.

## Revoke sessions

Rotating a password does **not** invalidate existing sessions. If rotation is reactive (compromise, shared device), drop the session rows too:

```bash
sudo -u entlib sqlite3 /var/lib/entlib/db.sqlite \
  "DELETE FROM session WHERE user_id='kira';"
```

The user is kicked to the login screen on their next request.

## Verify

```bash
curl -c /tmp/c.txt https://<domain>/api/health >/dev/null
CSRF=$(awk '/ENTLIB_CSRF/ {print $7}' /tmp/c.txt)
curl -b /tmp/c.txt -c /tmp/c.txt \
  -H "X-CSRF-Token: $CSRF" \
  -d 'username=kira&password=<the new password>' \
  https://<domain>/login
curl -b /tmp/c.txt https://<domain>/api/me
# {"display_name":"Kira","user_id":"kira"}
rm /tmp/c.txt
```

A 200 with the right `user_id` and `display_name` is the success signal.
