# The single EC2 instance that runs the Spring Boot container + SQLite on disk
# (ADR 0014). Reachable only via CloudFront; ops access is SSM Session Manager,
# not SSH.
resource "aws_security_group" "app" {
  name_prefix = "${local.name_prefix}-app-"
  description = "Alexandrea app origin: app port only from CloudFront origin-facing ranges"
  vpc_id      = data.aws_vpc.default.id

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "app_from_cloudfront" {
  security_group_id = aws_security_group.app.id
  description       = "App port from CloudFront origin-facing prefix list only (ADR 0014)"
  ip_protocol       = "tcp"
  from_port         = var.app_port
  to_port           = var.app_port
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin.id
}

resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  description       = "Allow all outbound (catalog providers, SES, SSM, Loki)"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_instance" "app" {
  ami                  = data.aws_ami.al2023.id
  instance_type        = var.instance_type
  iam_instance_profile = aws_iam_instance_profile.app.name

  vpc_security_group_ids = [aws_security_group.app.id]

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required" # IMDSv2 only
  }

  root_block_device {
    volume_size = var.root_volume_gb
    volume_type = "gp3"
    encrypted   = true
  }

  tags = {
    Name = "${local.name_prefix}-app"
  }
}

# Stable address so the CloudFront origin record doesn't move on stop/start.
resource "aws_eip" "app" {
  instance = aws_instance.app.id
  domain   = "vpc"

  tags = {
    Name = "${local.name_prefix}-app"
  }
}
