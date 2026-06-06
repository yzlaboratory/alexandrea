# Prod stack (ADR 0014, ADR 0023). State lives in the bucket the bootstrap layer
# created, with S3-native locking (use_lockfile — Terraform 1.10+, no DynamoDB).
terraform {
  required_version = ">= 1.10"

  backend "s3" {
    bucket       = "alexandrea-tfstate"
    key          = "prod/terraform.tfstate"
    region       = "eu-central-1"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
