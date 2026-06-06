# Runtime secrets (ADR 0023). Created as SecureString placeholders; the real
# values are written out-of-band (console / `aws ssm put-parameter`) and Terraform
# ignores them thereafter, so secrets never enter state or git.
locals {
  secret_params = [
    "session-signing-key",    # Spring Session signing key
    "loki-write-token",       # Grafana Cloud Loki tenant write token (Alloy)
    "csrf-token-signing-key", # Spring Security CSRF token signing key
    "admin-password",         # admin-endpoint password
  ]
}

resource "aws_ssm_parameter" "secrets" {
  for_each = toset(local.secret_params)

  name  = "/alexandrea/prod/${each.key}"
  type  = "SecureString"
  value = "PLACEHOLDER-set-out-of-band"

  lifecycle {
    ignore_changes = [value]
  }
}
