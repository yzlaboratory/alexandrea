output "server_ipv4" {
  description = "Public IPv4 of the entlib server. Use for the DNS A record."
  value       = hcloud_server.entlib.ipv4_address
}

output "server_ipv6" {
  description = "Public IPv6 of the entlib server. Use for the DNS AAAA record."
  value       = hcloud_server.entlib.ipv6_address
}

output "storage_box_host" {
  description = "Hostname of the Storage Box. Set ENTLIB_BACKUP_HOST in /etc/entlib/backup.env."
  value       = hcloud_storage_box.backups.server
}

output "storage_box_username" {
  description = "Username of the Storage Box root account. Set ENTLIB_BACKUP_USER in /etc/entlib/backup.env."
  value       = hcloud_storage_box.backups.username
}
