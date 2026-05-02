# ADR 0001: v0 Hosting & Runtime — Go on a Hetzner VPS

- **Status:** Accepted
- **Date:** 2026-04-17
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —

## Context

The MVP is tiny: four entities, two users, one shared library (see `specs/`). It needs to be reachable from the open internet behind TLS. Uptime is not critical, but an unattended process crash should self-recover — being "down" feels bad even when it's technically acceptable. Future users may arrive; no specific scaling target.

## Decision

Run the whole thing on a **single Hetzner Cloud VPS**, written in **Go**, behind **Caddy** for TLS, supervised by **systemd**.

Concretely:

- **VPS:** Hetzner Cloud **CX22** in Nuremberg (`nbg1`). 2 vCPU, 4 GB RAM, 40 GB SSD. ~€4.50/month.
- **OS:** Ubuntu 24.04 LTS.
- **Runtime:** Go (latest stable). The app compiles to a single static binary.
- **Process manager:** systemd unit with `Restart=always` and a small restart backoff.
- **Reverse proxy:** Caddy, automatic TLS via Let's Encrypt, proxying to the Go process on a localhost port.
- **Deploy:** `scp` the binary + `systemctl restart`. Manual initially; a ~30-line GitHub Actions workflow once manual deploys get tedious.

HTTP router, templating vs. SPA, and DB choice are deliberately deferred to their own ADRs — this one only fixes the box and the runtime.

## Rationale

- **Single static binary** matches a single-VPS deploy better than any alternative. No JRE, no `node_modules`, no virtualenv, no interpreter upgrade drama.
- **~30–50 MB resident** leaves the 4 GB box effectively empty. Zero resource pressure at any scale we're likely to reach.
- **`Restart=always`** on systemd is the cheapest correct answer to "down feels bad." Process crashes self-recover within a couple of seconds.
- **Caddy** makes HTTPS a three-line config; no manual certbot, no renewal cron.
- **Scales fine.** A single Go process on this box comfortably handles hundreds of concurrent users. If we ever outgrow it, we outgrow "two users" first.
- **Go is boring.** Standard library covers nearly everything we need for this scope (`net/http`, `database/sql`, `html/template`). Few framework decisions to relitigate.

## Alternatives considered

- **TypeScript (Hono on Bun or Node + Fastify).** Same-language front+back is appealing. Rejected for v0 because it adds a bundler, a dependency tree, and a heavier runtime for no scope-specific benefit.
- **Spring Boot / Java.** Previous pick. ~400 MB baseline RSS is disproportionate on a 4 GB box for four CRUD tables; JVM ceremony outweighs productivity at this size.
- **Python / FastAPI.** Fine at this scope, but deploy means managing an interpreter + deps on the VPS. A single binary wins.
- **Rust.** Overkill. Slower to write CRUD than Go, no meaningful runtime benefit here.
- **Managed PaaS (Fly, Railway, Render).** ~2–4× the cost, proprietary deploy models. No benefit over a €5 VPS at this scale.
- **Self-host on a Raspberry Pi at home.** Cheapest possible, but requires home-network reliability, DDNS, and physical attention. Cloud is worth the ~€5.

## Consequences

### Positive
- ~€5/month all-in for compute. Budget is trivial.
- One binary, one host, one systemd unit. Easy mental model.
- HTTPS and auto-restart are solved by off-the-shelf tools with minimal config.
- No vendor lock-in: it's a Linux box and a Go binary.

### Negative
- **Single point of failure.** VPS dies → app is down until it's back. Acceptable for two users; will need revisiting if others come to depend on it.
- **Writing Go templates instead of JSX** means a less glamorous frontend authoring experience. Acceptable at this scope; revisit in a later ADR if we decide on an SPA.
- **No horizontal scale.** Go can scale vertically on this box, but we can't load-balance two copies against one SQLite file (if that ends up being the datastore). Accepted until we need otherwise.

### Follow-ups
- Pick the HTTP router (stdlib vs. `chi` vs. `echo`) — probably in the ADR covering the backend shape.
- Decide server-rendered (htmx + `html/template`) vs. SPA — separate ADR.
- Provision the VPS and a domain.
- Write the backup runbook once the datastore ADR lands.

## Sources

- [Hetzner Cloud pricing](https://www.hetzner.com/cloud)
- [Go `net/http` documentation](https://pkg.go.dev/net/http)
- [Caddy — Automatic HTTPS](https://caddyserver.com/docs/automatic-https)
- [systemd `Restart=` options](https://www.freedesktop.org/software/systemd/man/systemd.service.html#Restart=)
