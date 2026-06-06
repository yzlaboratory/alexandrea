data "aws_caller_identity" "current" {}

# Default VPC of the account — the single EC2 instance lives here (ADR 0014).
data "aws_vpc" "default" {
  default = true
}

# Latest Amazon Linux 2023 arm64 AMI (matches t4g/Graviton instance_type).
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-arm64"]
  }
  filter {
    name   = "architecture"
    values = ["arm64"]
  }
}

# AWS-managed prefix list of CloudFront origin-facing IP ranges. The app's
# security group allows the app port only from these (ADR 0014).
data "aws_ec2_managed_prefix_list" "cloudfront_origin" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# AWS-managed CloudFront policies (no need to hand-roll our own).
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards everything EXCEPT the Host header, so the EC2 origin sees its own
# Host (origin.alexandrea.app) rather than the CloudFront alias.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}
