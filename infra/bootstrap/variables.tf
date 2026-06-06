variable "region" {
  description = "Primary AWS region for the project (ADR 0023: eu-central-1)."
  type        = string
  default     = "eu-central-1"
}

variable "github_repo" {
  description = "owner/name of the GitHub repo allowed to assume the CI role via OIDC."
  type        = string
  default     = "yzlaboratory/alexandrea"
}

variable "tfstate_bucket" {
  description = "Name of the S3 bucket that holds the prod stack's Terraform state."
  type        = string
  default     = "alexandrea-tfstate"
}

variable "ci_role_name" {
  description = "Name of the IAM role GitHub Actions assumes via OIDC."
  type        = string
  default     = "alexandrea-prod-github-actions"
}
