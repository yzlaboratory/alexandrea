output "tfstate_bucket" {
  description = "S3 bucket holding the prod stack's Terraform state. Wire into infra/prod/versions.tf backend."
  value       = aws_s3_bucket.tfstate.id
}

output "ci_role_arn" {
  description = "ARN GitHub Actions assumes via OIDC. Use as role-to-assume in the deploy workflow."
  value       = aws_iam_role.ci.arn
}

output "github_oidc_provider_arn" {
  description = "ARN of the GitHub Actions OIDC provider."
  value       = aws_iam_openid_connect_provider.github.arn
}
