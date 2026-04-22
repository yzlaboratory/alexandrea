terraform {
  # 1.10 introduced native S3 state locking via `use_lockfile`, removing the
  # DynamoDB table that the S3 backend historically required.
  required_version = ">= 1.10.0"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.60"
    }
  }

  backend "s3" {
    bucket       = "entlib-tfstate-c20675"
    key          = "entlib/terraform.tfstate"
    region       = "eu-central-1"
    encrypt      = true
    use_lockfile = true
  }
}
