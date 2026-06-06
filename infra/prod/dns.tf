# Route 53 hosted zone for the apex domain. Porkbun's NS records delegate the
# whole zone here (ADR 0023) — that delegation is done out-of-band (Porkbun API)
# once this zone exists; the nameservers to set are in outputs.tf.
resource "aws_route53_zone" "main" {
  name = var.domain
}

# Apex and www both alias to CloudFront (ADR 0023: CloudFront alias at the apex;
# www is served identically, not redirected — canonicalization is deferred to the
# app/CDN-function layer if SEO ever needs it). One definition, two names.
resource "aws_route53_record" "cloudfront_alias" {
  for_each = toset([var.domain, "www.${var.domain}"])

  zone_id = aws_route53_zone.main.zone_id
  name    = each.value
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}

# EC2 (API) origin record -> the instance's Elastic IP.
resource "aws_route53_record" "origin" {
  zone_id = aws_route53_zone.main.zone_id
  name    = local.origin_fqdn
  type    = "A"
  ttl     = 300
  records = [aws_eip.app.public_ip]
}
