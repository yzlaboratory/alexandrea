# NEXT

Handoff note for the next session. Self-contained — readable cold.

## Where the repo is (2026-04-23, end-of-day)

**MVP is deployed, reachable, and end-to-end verified.** Phase-0 provisioning,
the nightly backup pipeline, and the first live-VPS browser smoke test are all
behind us. Everything described in `specs/00-overview.md` through
`specs/04-data-model.md` is implemented, tested, and running on the production
VPS — and exercised end-to-end against it by both curl and a real Chromium.

Entry point: <http://178.105.45.232:8080/> (plain HTTP, HTTPS deferred — see
"Open threads" below).

- **VPS.** Hetzner `cx23` at `178.105.45.232` (IPv6: `2a01:4f8:1c18:11e7::1`),
  Ubuntu 24.04, provisioned through `deploy/terraform/` (state in the S3 bucket
  listed in `~/.entlib-terraform/s3_backend_bucket`, native `use_lockfile`).
  `entlib.service` is up, enabled, and restarts automatically.
- **Storage Box.** Hetzner `bx11` at `u581173.your-storagebox.de`. Snapshots
  live at `/entlib/` (note: *not* `/home/entlib/` — see quirks below).
- **Users seeded.** Two rows in `user`: `kira` (Kira) and `SexyGirl99` (Eileen).
  Passwords stashed at `~/.entlib-terraform/seeded-users.txt` and in the
  consolidated `~/.credentials` file (both mode 0600).
- **Nightly backup.** `/usr/local/bin/entlib-nightly-backup` + `/etc/cron.d/entlib-backup`
  fire at 03:15 UTC, upload a `.backup`-consistent SQLite snapshot to the Storage
  Box over SCP, prune snapshots older than 30 days via SFTP. Tested three times
  manually; restore drill passed (`PRAGMA integrity_check=ok`, row counts match).
- **Credentials.**
  - `~/.credentials` — TMDB key, Hetzner API token, seeded user passwords.
  - `~/.entlib-terraform/` — Terraform env, SSH keypair, storage box password.
  - `~/.ssh/id_ed25519_entlib(.pub)` — same admin key, also available under
    `~/.ssh/` with `Host entlib` / `Host entlib-backup` aliases in
    `~/.ssh/config` so `ssh entlib` / `sftp entlib-backup` just work.

### Commit log since the last handoff

`db849c3 Extend E2E smoke to cover entry removal and logout` is the tip on
`main`. The working tree is clean (only untracked: the local `entlib` build
artifact). All phase-0 fixes the previous handoff flagged as in-flight have
been committed and pushed. Notable landings this session:

```
db849c3  Extend E2E smoke to cover entry removal and logout
9a01576  Add full-stack E2E smoke test for the canonical user journey
d4c3fc7  Refresh NEXT.md: MVP deployed and reachable
87cfcbd  Opt CI/deploy workflows into Node 24 actions runtime
7d98028  Document no-domain path and Storage Box quirks in phase-0 runbook
ce315ef  Update restore runbook for SFTP-only Storage Box
b1f7b30  Support multiple Storage Box SSH keys via Terraform
5c7776b  Rewrite backup prune to use SFTP
```

### Phase progress

| Phase                                          | Status                                                   |
|------------------------------------------------|----------------------------------------------------------|
| 0. Provision VPS + Storage Box                 | done — `terraform apply` ran, outputs cached locally     |
| 0b. Base packages / firewall / service user    | done — VPS bootstrapped                                  |
| 0c. First binary + systemd unit                | done — binary deployed manually via scp                  |
| 0d. Seed users                                 | done — both rows in `user`                               |
| 0e. Start + verify                             | done — `/api/health` ok, login smoke test green          |
| 0f. Nightly backup + cron + remote dir         | done — cron at 03:15 UTC, 3 snapshots on Storage Box     |
| 0g. Restore drill                              | done — integrity ok, row counts match                    |
| 0h. Full-stack E2E smoke (local)               | done — `pnpm test:e2e`, real Chromium, TMDB stub, ~2.5s  |
| 0i. Live-VPS browser smoke                     | done 2026-04-23 — API + real-browser UI both green       |
| 1. First manual workflow-dispatch deploy       | **not started** — deploy.yml has never run yet           |
| 2. Promote deploy.yml to `push: main`          | not started — gated on 3 clean manual runs               |
| 3. Domain + Caddy + TLS + flip COOKIE_SECURE   | not started — user deferred domain decision              |
| 4. CI Node-24 flag                             | done — `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true`         |

## HTTP surface as it stands today

(Unchanged from the previous handoff — transcluded here so this note is
self-contained.)

Unauthenticated:
- `GET /api/health`
- `POST /login` — form `username` + `password`, sets `ENTLIB_SESSION`
- `POST /logout`

Authenticated (session cookie + CSRF):
- `GET /api/me`
- `GET /api/search?q=<query>`
- `GET /api/tmdb/title/{kind}/{id}` (`kind` ∈ `movie`/`series`)
- `POST /api/titles` — `{tmdb_id, kind}`; idempotent on `tmdb_id`
- `GET /api/titles/{id}`
- `GET /api/library?status=want,watching`
- `POST /api/library` — `{title_id, status?}`; 409 on duplicate
- `PATCH /api/library/{id}` — `{status}`
- `DELETE /api/library/{id}` — 204
- `POST /api/library/{id}/rating` — `{score: 0-5, note?}`; implicit → `watched`
- `DELETE /api/library/{id}/rating` — scoped to caller

## Non-obvious bits you may trip on (v0)

Previous entries still apply. New ones from phase-0 execution:

- **Hetzner Storage Box blocks `ssh <cmd>`.** Only SFTP / SCP / rsync work.
  The first version of `nightly-backup.sh` used `ssh ... "find -delete"` for
  prune; that fails with `exec request failed on channel 0`. The script now
  lists+deletes over SFTP instead, parsing the lexicographic timestamp embedded
  in each filename against a locally-computed cutoff. The same constraint hits
  the restore runbook's "list snapshots" step — it's been rewritten to use
  `sftp <<EOF ls -la $DIR EOF`.
- **Storage Box wants pubkeys in TWO formats.** Every ed25519 key in
  `~/.ssh/authorized_keys` on the Storage Box needs both the OpenSSH one-liner
  *and* an RFC 4716 (`---- BEGIN SSH2 PUBLIC KEY ----`) block. If you only paste
  the OpenSSH line, `sshd` logs "Server accepts key" but auth still fails
  (surprising but reproducible). Generate the PEM block with
  `ssh-keygen -e -f key.pub` and append.
- **Cannot update `ssh_keys` on an existing `hcloud_storage_box` in-place.**
  Terraform marks the attribute `forces replacement`, so changing it destroys
  and recreates the box (new username, new host). For additional keys after
  creation, edit `authorized_keys` over SFTP. The `storage_box_extra_ssh_keys`
  variable that was just added applies only to *new* storage boxes.
- **Storage Box SFTP chroot is at `/`, not `/home/<user>`.** The runbook's
  example `ENTLIB_BACKUP_DIR=/home/entlib` was wrong for this box; the live
  config uses `/entlib`. If you re-seed infrastructure, you can pick either.
- **`/etc/entlib` is `0750 root:entlib`, not group-writable.** The runbook's
  `sudo -u entlib ssh-keygen -f /etc/entlib/backup_ed25519` step fails with
  "Permission denied" because `entlib` only has group-read, not group-write.
  Generate as root, then `chown entlib:entlib` the private key. Updated in the
  runbook.
- **No Caddy, no TLS, no HTTPS.** Until a domain lands, entlib binds
  `0.0.0.0:8080` directly (UFW opens 8080/tcp). `ENTLIB_COOKIE_SECURE=false`
  in `/etc/entlib/env` because `Secure` cookies silently fail over HTTP.
  When a domain lands, the two changes are: install Caddy per runbook section 5,
  flip `ENTLIB_COOKIE_SECURE=true` in `/etc/entlib/env`, restart `entlib`.

## What to do first, in order

### 1. First workflow-dispatch deploy

`deploy.yml` has never run. Before promoting the trigger to `push: main`
(ADR 0001's "3rd clean manual deploy" gate), the workflow needs working
repo secrets set:

- `DEPLOY_SSH_KEY` — the private key from `~/.ssh/id_ed25519_entlib`
- `DEPLOY_KNOWN_HOSTS` — `ssh-keyscan 178.105.45.232` output
- `DEPLOY_SSH_USER` — `root`
- `DEPLOY_SSH_HOST` — `178.105.45.232`
- `DEPLOY_HEALTH_URL` — `http://178.105.45.232:8080/api/health`

The local `gh` CLI is authenticated with `repo` scope, so an agent *can*
populate these non-interactively with `gh secret set … --repo yzlaboratory/entertainment-library`.
The private-key upload is worth an explicit go-ahead from the user first,
but it's not an agent-blocker. After population, trigger a `workflow_dispatch`
run. After three clean runs, promote the trigger and remove the comment above
`on:` in `deploy.yml`.

### 2. Domain + Caddy + TLS (when ready)

The user has `svthalexweiler.de` already registered at Porkbun
(`~/.porkbun.env`) but deferred using it for entlib. When the decision lands:

1. Create A/AAAA records at the VPS's v4/v6 IPs (above).
2. Install Caddy per `docs/runbooks/phase-0-provisioning.md` §5 (reinstate
   that whole section — it was skipped this time around).
3. `echo ENTLIB_DOMAIN=<domain> >/etc/default/caddy`, `systemctl restart caddy`.
4. Flip `ENTLIB_COOKIE_SECURE=true` in `/etc/entlib/env`, restart `entlib`.
5. Drop the UFW 8080/tcp rule; keep 80 + 443 only.

## What is still **not** the agent's job

- Registering a domain (the Porkbun account is the user's).
- Deciding when to promote `deploy.yml` to push-on-main — that's a judgment
  call per ADR 0001.
- Rewriting already-merged ADRs (they supersede, they don't edit).

## Housekeeping

- Vestigial Spring Boot worktrees (`auth-baseline`, `auth-google-oidc`,
  `auth-polish`, `backend-skeleton`) still safe to delete.
- Per-slice branches remain pushed for traceability; prune if they're noise.

## Conventions to respect (unchanged)

- `~/.claude/CLAUDE.md`: loose TDD, worktrees, atomic commits with short
  expressive messages, push without asking, excessive happy/unhappy/edge
  tests, prettier on the frontend.
- `specs/` is prose only — no JSON Schema / OpenAPI / test fixtures there.
- Every ADR Follow-up also appears in `OPEN-QUESTIONS.md`.

## Things the agent got wrong (cumulative)

Prior entries still apply. New from this session:

- **Storage-Box ssh_keys is a replacement-only field.** Writing a
  `terraform plan` that accidentally rotates the Storage Box credentials is
  trivially easy — always check whether a change is marked `forces replacement`
  on Hetzner-managed resources before applying.
- **SFTP heredoc output includes the `sftp>` prompt line even with `-q`.** A
  too-permissive glob in the first backup-script prune (`*/entlib-*.sqlite`)
  matched the prompt line `sftp> ls -1 /entlib/entlib-*.sqlite`, queued a
  bogus `rm /sftp>` command, and only failed softly. Regex-anchor the filter
  to `^${DIR}/entlib-[0-9]{8}T[0-9]{6}Z\.sqlite$`, not a loose glob.
- **GNU `sort` over SFTP batch output is fine, but don't trust `tail -1` alone
  without first `grep -oE` filtering.** The list command's own echo line sorts
  after real filenames and will be selected by `tail -1` if not filtered out.
- **Chrome MCP `form_input` doesn't trigger React `onChange`.** It sets the
  DOM `.value` property directly; React reads from its own state, so a
  controlled form looks filled on screen but still submits with empty state.
  Use `computer.type` for real keystrokes against any React-controlled input,
  not `form_input`. Caused a "login silently does nothing" dead end during
  the 2026-04-23 browser smoke.
- **Default agent posture on GitHub repo secrets was too conservative.** The
  local `gh` token has `repo` scope, which covers Actions secret writes — an
  agent *can* run `gh secret set`. Worth confirming with the user before
  uploading a private SSH key, but not an agent-blocker the way "modify
  access controls" is.
