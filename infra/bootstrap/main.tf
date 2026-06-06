data "aws_caller_identity" "current" {}

# ---------------------------------------------------------------------------
# Terraform state bucket
# ---------------------------------------------------------------------------
# The prod stack stores its state here with S3-native locking (Terraform 1.10+;
# no DynamoDB lock table needed — ADR 0023). Versioned so a corrupted/clobbered
# state can be rolled back; SSE-S3 encrypted; all public access blocked.
resource "aws_s3_bucket" "tfstate" {
  bucket = var.tfstate_bucket
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------------------------------------------------------------------------
# GitHub Actions OIDC provider
# ---------------------------------------------------------------------------
# Registers GitHub's token issuer as a trusted identity provider so Actions
# workflows can mint short-lived AWS credentials with no static keys (ADR 0023).
data "tls_certificate" "github_oidc" {
  url = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github_oidc.certificates[0].sha1_fingerprint]
}

# ---------------------------------------------------------------------------
# CI deploy role
# ---------------------------------------------------------------------------
# One project-scoped role GitHub Actions assumes. Trust is pinned to THIS repo
# by the `sub` claim (ADR 0023) — tokens from any other repo are rejected.
data "aws_iam_policy_document" "ci_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:*"]
    }
  }
}

resource "aws_iam_role" "ci" {
  name                 = var.ci_role_name
  assume_role_policy   = data.aws_iam_policy_document.ci_trust.json
  max_session_duration = 3600
}

# The deploy policy is the single place that grows when the prod stack gains a
# new resource type (ADR 0023: "intentional friction"). It covers exactly the
# services the prod stack manages, plus read/write on the state bucket. Resource
# scoping is by project naming where ARNs are predictable; service-level "*" is
# used where the services don't support useful resource-level scoping at apply
# time (CloudFront, ACM, much of EC2/Route53).
data "aws_iam_policy_document" "ci_deploy" {
  # Terraform state: read, write, and S3-native lock on this bucket only.
  statement {
    sid     = "TerraformState"
    effect  = "Allow"
    actions = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket", "s3:GetBucketVersioning"]
    resources = [
      aws_s3_bucket.tfstate.arn,
      "${aws_s3_bucket.tfstate.arn}/*",
    ]
  }

  # Services the prod stack provisions. Kept service-scoped; tighten per-resource
  # in a later hardening pass once the stack ARNs are stable.
  statement {
    sid    = "ProvisionProdStack"
    effect = "Allow"
    actions = [
      "route53:*",
      "acm:*",
      "cloudfront:*",
      "ec2:*",
      "s3:*",
      "ssm:*",
      "ses:*",
      "iam:*", # instance role + profile lifecycle
      "sts:GetCallerIdentity",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "ci_deploy" {
  name   = "deploy"
  role   = aws_iam_role.ci.id
  policy = data.aws_iam_policy_document.ci_deploy.json
}
