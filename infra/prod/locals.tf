locals {
  name_prefix = "alexandrea-prod"

  # SES sends from a subdomain so root-domain reputation is insulated (ADR 0023).
  mail_domain = "mail.${var.domain}"

  # Stable name CloudFront uses as the EC2 (API) origin. CloudFront custom
  # origins need a domain name, not a raw IP — this A record points at the EIP.
  origin_fqdn = "origin.${var.domain}"

  tags = {
    Project   = "alexandrea"
    Env       = "prod"
    ManagedBy = "terraform"
  }
}
