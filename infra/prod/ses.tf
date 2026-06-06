# Transactional mail identity on the mail subdomain (ADR 0023). DKIM + DMARC live
# on the subdomain only, insulating the root domain's reputation. Sandbox-exit is
# requested out-of-band once the DKIM CNAMEs validate.
resource "aws_sesv2_email_identity" "mail" {
  email_identity = local.mail_domain
}

# Easy-DKIM: three CNAMEs AWS issues for the identity.
resource "aws_route53_record" "ses_dkim" {
  count = 3

  zone_id = aws_route53_zone.main.zone_id
  name    = "${aws_sesv2_email_identity.mail.dkim_signing_attributes[0].tokens[count.index]}._domainkey.${local.mail_domain}"
  type    = "CNAME"
  ttl     = 300
  records = ["${aws_sesv2_email_identity.mail.dkim_signing_attributes[0].tokens[count.index]}.dkim.amazonses.com"]
}

# SPF for the mail subdomain.
resource "aws_route53_record" "ses_spf" {
  zone_id = aws_route53_zone.main.zone_id
  name    = local.mail_domain
  type    = "TXT"
  ttl     = 300
  records = ["v=spf1 include:amazonses.com ~all"]
}

# DMARC on the mail subdomain (monitoring-only at v1).
resource "aws_route53_record" "ses_dmarc" {
  zone_id = aws_route53_zone.main.zone_id
  name    = "_dmarc.${local.mail_domain}"
  type    = "TXT"
  ttl     = 300
  records = ["v=DMARC1; p=none; rua=mailto:dmarc@${local.mail_domain}"]
}
