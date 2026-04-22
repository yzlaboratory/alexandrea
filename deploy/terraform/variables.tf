variable "hcloud_token" {
  description = "Hetzner Cloud API token with read+write scope (console.hetzner.cloud → Security → API tokens). Prefer TF_VAR_hcloud_token over putting it in terraform.tfvars."
  type        = string
  sensitive   = true
}

variable "ssh_public_key" {
  description = "OpenSSH public key authorized on the server (root) and on the Storage Box (contents of e.g. ~/.ssh/id_ed25519.pub)."
  type        = string
}

variable "ssh_key_name" {
  description = "Label used for the hcloud_ssh_key resource."
  type        = string
  default     = "entlib-admin"
}

variable "server_name" {
  description = "Hostname of the CX22 server (must be a valid RFC 1123 hostname)."
  type        = string
  default     = "entlib"
}

variable "server_type" {
  description = "Hetzner Cloud server type. ADR 0001 picked CX22 (2 vCPU / 4 GB / 40 GB) but Hetzner retired the CX-2x generation; cx23 is the direct replacement with identical specs."
  type        = string
  default     = "cx23"
}

variable "server_image" {
  description = "OS image for the server."
  type        = string
  default     = "ubuntu-24.04"
}

variable "server_location" {
  description = "Server location (nbg1 = Nuremberg, per ADR 0001)."
  type        = string
  default     = "nbg1"
}

variable "storage_box_name" {
  description = "Storage Box display name in the Hetzner console."
  type        = string
  default     = "entlib-backups"
}

variable "storage_box_type" {
  description = "Storage Box tier. bx11 is the smallest (1 TB) — SQLite snapshots for two users are kilobytes."
  type        = string
  default     = "bx11"
}

variable "storage_box_location" {
  description = "Storage Box location. Co-location with the VPS is unnecessary for snapshots this small."
  type        = string
  default     = "hel1"
}

variable "storage_box_password" {
  description = "Password for the Storage Box root account. Used for the Hetzner web UI; scp authenticates via the SSH key above. Minimum 8 characters."
  type        = string
  sensitive   = true
}
