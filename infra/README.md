# Infrastructure (Terraform)

Realizes ADR 0023 (and the stack of ADR 0014) as Terraform. Two layers:

| Layer | State | Who runs it | What it creates |
|-------|-------|-------------|-----------------|
| [`bootstrap/`](bootstrap) | **local** (gitignored) | operator, once, with local AWS creds | tfstate S3 bucket, GitHub OIDC provider, CI deploy role |
| [`prod/`](prod) | **S3** (`alexandrea-tfstate`) | `plan` locally; **`apply` from CI** (ADR 0023) | Route 53, ACM (us-east-1), CloudFront, S3 (SPA + backups), EC2 + EIP + SG, instance role, SSM params, SES |

`bootstrap/` exists to solve a chicken-and-egg: the state bucket can't hold the
state of its own creation, and CI can't create the very role it logs in with. So
those three foundational resources are made first, with local state.

## Topology (ADR 0014 / 0023)

One CloudFront distribution is the single virtual host. `default` behavior serves
the React SPA from a private S3 bucket (OAC, edge-cached); `/api/*` forwards to the
EC2 instance (`origin.alexandrea.app`, HTTP, no caching). A 404/403 → `/index.html`
rule keeps React Router deep links alive. The EC2 security group accepts the app
port only from CloudFront's origin-facing prefix list; ops access is SSM Session
Manager (no SSH). TLS is an ACM cert in us-east-1 (the only out-of-region resource).

## One-time bootstrap

```sh
cd infra/bootstrap
terraform init
terraform apply
```

Requires local AWS creds with permission to create an S3 bucket, an IAM OIDC
provider, and an IAM role. ADR 0023 sanctions local creds for exactly this
(bootstrap + ad-hoc reads).

## Prod stack — plan locally

```sh
cd infra/prod
terraform init      # uses the S3 backend the bootstrap created
terraform validate
terraform plan
```

`apply` is **not** run locally — it runs from CI under the OIDC role (ADR 0023).
The CI workflow is built in a later pass.

## Manual steps before the first real `apply`

These are outside this repo and gate a live deployment:

1. **NS delegation.** After the prod `apply` creates the Route 53 zone, set
   `alexandrea.app`'s nameservers (output `route53_nameservers`) at Porkbun
   (Porkbun API: `domain/updateNs`). ACM DNS validation and CloudFront aliases
   stay pending until this propagates.
2. **SSM secret values.** Replace the placeholders under `/alexandrea/prod/*`:
   ```sh
   aws ssm put-parameter --overwrite --type SecureString \
     --name /alexandrea/prod/session-signing-key --value "$(openssl rand -base64 48)"
   ```
   (Repeat for `csrf-token-signing-key`, `loki-write-token`, `admin-password`.)
   Terraform ignores changes to these values, so they never enter state or git.
3. **SES sandbox exit.** Once the DKIM CNAMEs validate, request production access
   for SES in eu-central-1 (24–48h AWS review). Until then SES only sends to
   verified recipients.

## Conventions

- Everything project-related is prefixed `alexandrea-prod-*` / `/alexandrea/prod/*`
  — the marker for "delete to nuke v1" (ADR 0023).
- Anything in the AWS console not in `prod/` is drift.
- `.terraform.lock.hcl` is committed (pins provider versions); state and
  `*.tfvars` are gitignored.
