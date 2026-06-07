output "route53_nameservers" {
  description = "Set these as alexandrea.app's nameservers at Porkbun to delegate the zone (ADR 0023)."
  value       = aws_route53_zone.main.name_servers
}

output "cloudfront_domain" {
  description = "CloudFront distribution domain (apex/www alias targets)."
  value       = aws_cloudfront_distribution.main.domain_name
}

output "app_public_ip" {
  description = "Elastic IP of the EC2 app origin (origin.alexandrea.app target)."
  value       = aws_eip.app.public_ip
}

output "instance_id" {
  description = "EC2 instance id (for SSM Session Manager)."
  value       = aws_instance.app.id
}

output "frontend_bucket" {
  description = "S3 bucket the React build is synced to on deploy."
  value       = aws_s3_bucket.frontend.id
}

output "backups_bucket" {
  description = "S3 bucket for daily SQLite dumps."
  value       = aws_s3_bucket.backups.id
}

output "ses_configuration_set" {
  description = "Configuration set the backend must send with (carries suppression + reputation tracking)."
  value       = aws_sesv2_configuration_set.mail.configuration_set_name
}

output "ses_events_topic_arn" {
  description = "SNS topic the backend subscribes to for bounce/complaint feedback."
  value       = aws_sns_topic.ses_events.arn
}

output "ses_events_queue_url" {
  description = "SQS queue the backend long-polls for SES bounce/complaint events."
  value       = aws_sqs_queue.ses_events.id
}

output "ses_events_queue_arn" {
  description = "ARN of the SES events SQS queue."
  value       = aws_sqs_queue.ses_events.arn
}
