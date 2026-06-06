# Bootstrap layer (ADR 0023). Creates the three things that must exist *before*
# the prod stack can run from CI: the Terraform state bucket, the GitHub OIDC
# provider, and the CI deploy role. State is deliberately LOCAL here — the state
# bucket cannot store the state of its own creation, and bootstrap changes are
# rare. The local state file is gitignored.
terraform {
  required_version = ">= 1.10"

  # No backend block => local backend. See note above.

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "alexandrea"
      Env       = "prod"
      ManagedBy = "terraform"
      Layer     = "bootstrap"
    }
  }
}
