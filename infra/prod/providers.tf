# Default provider: eu-central-1 holds everything except the CloudFront cert.
provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

# CloudFront's ACM certificate MUST live in us-east-1 regardless of origin region
# (ADR 0023). This is the only resource outside eu-central-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = local.tags
  }
}
