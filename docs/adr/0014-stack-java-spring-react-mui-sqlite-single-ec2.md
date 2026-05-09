# Stack: Java/Spring Boot + React 19 with MUI, CloudFront over EC2 + S3 as a single virtual host, SQLite on EC2, GitHub Actions → Docker deploy

The application is fronted by **one CloudFront distribution** that
the browser sees as a single virtual host (e.g.
`https://app.example.com/`). CloudFront routes by path to two
origins behind it:

- **Backend** — **Java 25** + **Spring Boot 4**, built with
  **Gradle (Kotlin DSL)**, running on a single AWS EC2 instance.
  CloudFront forwards `/api/*` requests to this origin with no
  edge caching. The backend is unaware of CloudFront — it serves
  its REST API as if directly addressed.
- **Frontend** — **React 19** + **MUI** (Material UI), built with
  **Vite**. Static bundle deployed to an S3 bucket. CloudFront
  serves every other path from S3 with edge caching. A CloudFront
  error-page rule rewrites 404 responses on the S3 origin to
  `/index.html` so React Router can resolve client-side paths —
  including the path-segment detail-overlay URLs of ADR 0008.
- **Database** — SQLite, single file on the EC2 instance's disk.
  Holds every persistent table the app needs: users, sessions,
  watchlist/library rows, ratings, completion dates, shares,
  per-(user, surface, media_type) preferences, the catalog cache
  of ADR 0007, the email rate-limit bucket of ADR 0011, and every
  one-shot token (verification / reset / revert).
- **Deployment** — one GitHub Actions workflow with two artifacts:
  the Spring Boot Docker image is pushed to a registry and pulled
  by the EC2 instance; the React build is `aws s3 sync`'d to the
  S3 bucket and a CloudFront cache invalidation runs against the
  static path set. Both artifacts are released atomically per
  workflow run.

## Why CloudFront over both origins (single virtual host)

Two simpler architectures were considered and rejected:

- **Split origin on separate hostnames** (`app.example.com` for
  the SPA on S3+CloudFront, `api.example.com` for the API on
  EC2). Rejected because cookies cross an origin boundary —
  ADR 0012's `HttpOnly; Secure; SameSite=Strict` cookies cannot
  be sent across origins, forcing `SameSite=None` and CORS
  preflights on every auth-bearing request. The auth surface
  area triples for no architectural gain.
- **Spring Boot serves everything from EC2** (one Docker image
  with the React bundle embedded as static resources, a
  fall-through rule to `index.html`). Genuinely simpler v1 ops,
  but loses edge caching, global low-latency, CloudFront's
  baseline DDoS shielding, and ACM TLS termination. At our
  scale the cost dimension is a wash; we're paying ops
  complexity for the operational floor that CloudFront
  provides.

The chosen shape — **CloudFront as a single virtual host with
two behaviors** — keeps the auth-cookie simplicity of same-origin
while giving the SPA edge caching:

- **The browser sees one origin.** Sessions per ADR 0012 are
  plain `HttpOnly; Secure; SameSite=Strict` cookies on
  `app.example.com`. CORS does not enter the picture. The split
  is invisible to the SPA and to the auth flows.
- **Edge caching for the SPA bundle is free.** CloudFront's
  always-free tier (1 TB egress, 10M requests/month) covers
  indie-scale traffic indefinitely; spike absorption is
  built-in.
- **TLS terminates at CloudFront via ACM.** Free certs, auto-
  renewal, strong defaults. The EC2 origin can speak HTTP-only
  behind CloudFront (locked down by security group + origin
  request policy) or terminate TLS itself if preferred.
- **The path-segment URL contract of ADR 0008 holds.** A
  CloudFront error-page rule maps S3-origin 404s to
  `/index.html` with HTTP 200 so React Router resolves
  `/movies/catalog/27205` and friends client-side.
- **API and SPA versions deploy together.** GitHub Actions
  releases both artifacts in one workflow; cache invalidation
  pins the deploy boundary. The cross-version skew window is
  bounded by CloudFront's invalidation latency (typically
  ≤1 minute).

## Why SQLite for production

SQLite for production is the controversial call. Postgres is the
reflexive default; we chose against it deliberately:

- **Single-writer is fine for a personal tracker.** The
  application is per-user — a user's writes don't compete with
  another user's writes for the same rows. Catalog-cache writes
  are coalesced by `(provider, external_id, media_type)`. Total
  write QPS for an indie product is small.
- **Operational simplicity is enormous.** No separate database
  process, no connection pooling tier, no RDS bill, no
  replication topology, no failover playbook. The DB is a file
  on disk.
- **Backups are EBS snapshots.** Optionally complemented by
  scheduled `VACUUM INTO` dumps shipped to S3.
- **Litestream-style streaming replication can be added later**
  if a hot standby ever becomes worth it. Today it isn't.
- **All app state lives in one place.** Sessions, rate-limit
  buckets, cache, preferences, business data — same DB, same
  transactional boundary. No "I forgot to restart Redis"
  failure mode.

Accepted costs:

- **Single-instance only.** Horizontal scaling requires a real
  database (or Litestream + read replicas). v1 explicitly does
  not scale horizontally.
- **Deployment is not zero-downtime.** Container restart drops
  in-flight requests. For a personal tracker this is fine.
- **Long-running write transactions can block readers** under
  default journal mode. We use `WAL` mode (`PRAGMA
  journal_mode=WAL`) to relax that.

## Why MUI

MUI is a deliberate pick rather than rolling our own component
library:

- **Material Design has strong a11y defaults** out of the box —
  focus management, keyboard handling, ARIA roles — which lifts
  ADR 0010's WCAG 2.1 AA bar without per-component effort. We
  still audit, but the floor is high.
- **The components map cleanly to the spec surfaces.** `Drawer
  anchor="bottom"` for the mobile bottom-sheet of
  `view-entry-detail.md`; `Dialog` for the centered desktop
  modal; `Chip` for filter chips; `Snackbar` for the toast
  notifications used throughout the specs.
- **Theming covers contrast.** MUI's palette + the contrast
  utilities make 4.5:1 enforcement straightforward.
- **MUI is large.** Tree-shaking + the modular import pattern
  (`@mui/material/Button` not `@mui/material`) keeps the bundle
  honest. We accept the bundle-size cost for the velocity gain.

## Backups, observability, and security tokens

- **Backup strategy is a daily SQLite dump to a versioned S3
  bucket.** A cron on the EC2 instance runs `sqlite3 entlib.db
  ".backup /tmp/entlib-<timestamp>.db"`, ships the file to
  `s3://entlib-backups/YYYY/MM/DD/`, and removes the local
  copy. The S3 bucket has versioning on and a lifecycle rule
  (transition to Glacier after 30 days, expire after a chosen
  retention). EBS snapshots are deliberately **not used** —
  the only stateful artifact is the SQLite file, every other
  layer is reproducible from the Docker image and Terraform,
  and `VACUUM INTO` produces a guaranteed-consistent copy that
  a snapshot of a live volume cannot.
- **Observability is CloudWatch Logs only for v1.** Spring Boot
  emits structured JSON logs to stdout; the Docker container's
  log driver ships them to CloudWatch. No APM, no metrics
  pipeline, no distributed tracing in v1 — those are deferred
  in `OOS.md` and revisited if real ops needs emerge.
- **Every unguessable token in the system is 128-bit
  URL-safe random**, generated from a CSPRNG and stored in
  SQLite. This covers session ids (ADR 0012), email-verification
  tokens (signup + email-change), password-reset tokens, email-
  change revert tokens, and Share URL tokens (`share-top-rated.md`).
  A consistent format means one helper, one length, one expiry-
  table shape across all token-bearing flows.

## Consequences

- **Anything new that needs persistent state goes in SQLite.**
  Adding Redis, DynamoDB, or any other store requires a new
  ADR justifying why SQLite is insufficient.
- **The single instance is a SPOF** during the deploy window
  and during EC2 maintenance. We accept this for v1.
- **The cache layer of ADR 0007** is a SQLite table with TTL
  columns and lazy expiry on read. No external cache
  dependency.
- **The email rate-limit bucket of ADR 0011** is a SQLite table
  with rolling-window counters per recipient address. Cheap and
  transactional.
- **Sessions per ADR 0012** are SQLite rows keyed by an opaque
  session id; revocation is a `DELETE`. The sliding 30-day
  renewal updates `last_seen_at` on every authenticated
  request.
- **The CloudFront SPA-fallback rule is load-bearing.** The
  CloudFront distribution must rewrite S3-origin 404s on
  `text/html` requests to `/index.html` with HTTP 200, or every
  deep-link refresh of a path-segment overlay URL (per
  ADR 0008) breaks. This is one CloudFront error-pages
  configuration entry that future infra changes must preserve.
- **CloudFront cache invalidation runs on every deploy.** S3
  sync uploads new bundle hashes; the invalidation runs against
  `/index.html` and any unhashed assets so users pick up the
  new build within the invalidation window. Hashed asset names
  (`main.<hash>.js`) make invalidation essentially free for
  long-lived assets.
- **The EC2 instance is reachable only via CloudFront** in
  steady state. The security group should restrict the
  Spring Boot port to the CloudFront managed prefix list (or
  the AWS-managed prefix list `com.amazonaws.global.cloudfront.origin-facing`).
  Direct EC2 IP exposure leaks the architecture and bypasses
  ACM/edge protection.
- **MUI's `ThemeProvider`** carries the contrast tokens; any
  custom component must consume the theme rather than hardcode
  colors, or it will silently violate ADR 0010.
- **`SameSite=Strict` is the only CSRF defense.** Because the
  SPA and the API share an origin (single CloudFront virtual
  host), and session cookies are `SameSite=Strict`, no cross-site
  request can carry the session cookie and CSRF is structurally
  impossible against authenticated endpoints. Spring Security's
  CSRF token filter is therefore **disabled**. If a future
  architecture change splits the SPA and API onto separate
  origins, CSRF protection must be re-introduced (token filter
  re-enabled, or alternative defense) and this ADR superseded.
- **Recovery point objective is ≤24 hours.** With daily SQLite
  dumps to S3, the worst-case data loss after an EC2 instance or
  EBS volume failure is everything written since the last backup
  ran. Recovery time objective is *"however long it takes to spin
  up a fresh EC2, restore the latest dump, and point CloudFront
  at the new origin"* — minutes, not seconds. Both numbers are
  v1-acceptable for a personal tracker; tighter RPO/RTO would
  require streaming replication (Litestream) or a real database,
  both deferred.
