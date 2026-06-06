variable "region" {
  description = "Primary AWS region (ADR 0023: eu-central-1)."
  type        = string
  default     = "eu-central-1"
}

variable "domain" {
  description = "Apex domain. CloudFront alias sits at the apex (ADR 0023)."
  type        = string
  default     = "alexandrea.app"
}

variable "instance_type" {
  description = "EC2 instance type for the app origin. t4g = Graviton/arm64; the backend Docker image must be built arm64 to match."
  type        = string
  default     = "t4g.small"
}

variable "app_port" {
  description = "Port the Spring Boot app listens on behind CloudFront."
  type        = number
  default     = 8080
}

variable "backups_bucket" {
  description = "S3 bucket for daily SQLite dumps (ADR 0014/0023). Host cron writes here (ADR 0017)."
  type        = string
  default     = "alexandrea-prod-backups"
}

variable "root_volume_gb" {
  description = "EBS root volume size. Holds the OS, Docker images, and the SQLite database file."
  type        = number
  default     = 20
}
