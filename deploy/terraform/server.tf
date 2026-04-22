resource "hcloud_ssh_key" "admin" {
  name       = var.ssh_key_name
  public_key = var.ssh_public_key
}

resource "hcloud_server" "entlib" {
  name        = var.server_name
  image       = var.server_image
  server_type = var.server_type
  location    = var.server_location
  ssh_keys    = [hcloud_ssh_key.admin.name]

  public_net {
    ipv4_enabled = true
    ipv6_enabled = true
  }
}
