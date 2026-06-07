# Bounce/complaint handling (ADR 0023). Prerequisite for SES production access:
# AWS grants sandbox exit only when offending recipients are demonstrably
# suppressed and feedback is monitored. Three layers:
#   1. account suppression list — AWS auto-suppresses hard bounces + complaints,
#      so we never re-send to them (the core "we suppress offenders" control);
#   2. a configuration set carrying that suppression + reputation metrics, which
#      the backend sends with (name exported below);
#   3. an SNS event destination so the app can react in-app (flag the account,
#      stop retries) instead of only relying on AWS's silent suppression.

# 1. Account-wide suppression: any address that hard-bounces or complains is
# added to the managed list and skipped on future sends, account-wide.
resource "aws_sesv2_account_suppression_attributes" "main" {
  suppressed_reasons = ["BOUNCE", "COMPLAINT"]
}

# 3a. Topic the backend subscribes to for bounce/complaint feedback. Left
# unencrypted: SES publishes as the ses.amazonaws.com service principal, which
# cannot use the AWS-managed SNS key (alias/aws/sns); a customer-managed KMS key
# is deferred to the hardening pass. Payload is delivery metadata, not secrets.
resource "aws_sns_topic" "ses_events" {
  name = "${local.name_prefix}-ses-events"
}

# Allow SES (this account only) to publish bounce/complaint events to the topic.
resource "aws_sns_topic_policy" "ses_events" {
  arn = aws_sns_topic.ses_events.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowSESPublish"
      Effect    = "Allow"
      Principal = { Service = "ses.amazonaws.com" }
      Action    = "sns:Publish"
      Resource  = aws_sns_topic.ses_events.arn
      Condition = {
        StringEquals = { "AWS:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })
}

# 2. Configuration set the backend sends with. Carries suppression and turns on
# reputation metrics (bounce/complaint rates) so we can watch for SES thresholds.
resource "aws_sesv2_configuration_set" "mail" {
  configuration_set_name = "${local.name_prefix}-mail"

  reputation_options {
    reputation_metrics_enabled = true
  }

  suppression_options {
    suppressed_reasons = ["BOUNCE", "COMPLAINT"]
  }
}

# 3b. Publish the events that signal a bad recipient (or a send we should know
# failed) to SNS. Opens/clicks are intentionally excluded — no tracking at v1.
resource "aws_sesv2_configuration_set_event_destination" "sns" {
  configuration_set_name = aws_sesv2_configuration_set.mail.configuration_set_name
  event_destination_name = "sns-bounce-complaint"

  event_destination {
    enabled = true

    sns_destination {
      topic_arn = aws_sns_topic.ses_events.arn
    }

    matching_event_types = ["BOUNCE", "COMPLAINT", "REJECT", "RENDERING_FAILURE", "DELIVERY_DELAY"]
  }
}
