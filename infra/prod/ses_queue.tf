# SQS consumer for SES bounce/complaint feedback (ADR 0023). The ses_events SNS
# topic fans out to this queue; the backend long-polls it and reacts (e.g. flag
# the account, surface the failure to the user). Account-wide suppression already
# stops re-sends automatically — this queue is for the app to *know* it happened.

# Dead-letter queue: messages the backend fails to process maxReceiveCount times
# land here instead of being lost or looping forever. Held the full 14 days.
resource "aws_sqs_queue" "ses_events_dlq" {
  name                      = "${local.name_prefix}-ses-events-dlq"
  message_retention_seconds = 1209600
}

# Main queue the backend reads. Visibility timeout exceeds expected per-message
# processing so an in-flight message isn't redelivered mid-handle.
resource "aws_sqs_queue" "ses_events" {
  name                       = "${local.name_prefix}-ses-events"
  visibility_timeout_seconds = 30
  message_retention_seconds  = 345600 # 4 days

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.ses_events_dlq.arn
    maxReceiveCount     = 5
  })
}

# Only the main queue may redrive into the DLQ.
resource "aws_sqs_queue_redrive_allow_policy" "ses_events_dlq" {
  queue_url = aws_sqs_queue.ses_events_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.ses_events.arn]
  })
}

# Subscribe the queue to the topic. SNS-envelope delivery (raw off): the backend
# unwraps the SES event from .Message, and more subscribers can be added later.
resource "aws_sns_topic_subscription" "ses_events_sqs" {
  topic_arn            = aws_sns_topic.ses_events.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.ses_events.arn
  raw_message_delivery = false
}

# Allow only the ses_events topic to enqueue here.
resource "aws_sqs_queue_policy" "ses_events" {
  queue_url = aws_sqs_queue.ses_events.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowSESEventsTopic"
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.ses_events.arn
      Condition = {
        ArnEquals = { "aws:SourceArn" = aws_sns_topic.ses_events.arn }
      }
    }]
  })
}
