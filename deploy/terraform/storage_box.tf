resource "hcloud_storage_box" "backups" {
  name             = var.storage_box_name
  storage_box_type = var.storage_box_type
  location         = var.storage_box_location
  password         = var.storage_box_password

  ssh_keys = [var.ssh_public_key]

  access_settings = {
    reachable_externally = true
    samba_enabled        = false
    ssh_enabled          = true
    webdav_enabled       = false
    zfs_enabled          = false
  }
}
