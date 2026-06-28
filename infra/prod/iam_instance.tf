# Instance role: the EC2 host reads runtime secrets from SSM, sends mail via SES,
# writes daily SQLite backups to S3, and is reachable via SSM Session Manager.
data "aws_iam_policy_document" "instance_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${local.name_prefix}-instance"
  assume_role_policy = data.aws_iam_policy_document.instance_assume.json
}

# Session Manager access (no SSH ingress on the security group).
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "instance" {
  # Runtime secrets under /alexandrea/prod/* (ADR 0023). Nothing broader.
  statement {
    sid       = "ReadSecrets"
    effect    = "Allow"
    actions   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/alexandrea/prod/*"]
  }

  # Decrypt the SecureString params via the default SSM KMS key only.
  statement {
    sid       = "DecryptSecrets"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }

  # Transactional mail from the verified SES identity (ADR 0021/0023).
  statement {
    sid       = "SendEmail"
    effect    = "Allow"
    actions   = ["ses:SendEmail", "ses:SendRawEmail"]
    resources = [aws_sesv2_email_identity.mail.arn]
  }

  # Daily SQLite dump uploads (host cron — ADR 0017).
  statement {
    sid       = "WriteBackups"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.backups.arn}/*"]
  }

  # Long-poll SES bounce/complaint events from SQS and remove handled messages.
  statement {
    sid       = "ConsumeSesEvents"
    effect    = "Allow"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.ses_events.arn]
  }
}

resource "aws_iam_role_policy" "instance" {
  name   = "app"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.instance.json
}

resource "aws_iam_instance_profile" "app" {
  name = "${local.name_prefix}-instance"
  role = aws_iam_role.app.name
}
