# Single CloudFront virtual host over two origins (ADR 0014):
#   default behavior -> S3 SPA bundle (edge-cached)
#   /api/*           -> EC2 backend (no caching, full forward)
# A 404/403 -> /index.html(200) rule keeps React Router deep links working
# (ADR 0014, ADR 0008).
resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${local.name_prefix} single virtual host"
  default_root_object = "index.html"
  price_class         = "PriceClass_100" # NA + EU edges; cheapest tier.
  aliases             = [var.domain, "www.${var.domain}"]

  origin {
    origin_id                = "spa"
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  origin {
    origin_id   = "api"
    domain_name = local.origin_fqdn

    custom_origin_config {
      http_port              = var.app_port
      https_port             = 443
      origin_protocol_policy = "http-only" # EC2 origin speaks HTTP behind CloudFront, locked by SG (ADR 0014).
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = "spa"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress               = true
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = "api"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    compress                 = true
  }

  # SPA fallback — load-bearing for path-segment overlay URLs (ADR 0008/0014).
  custom_error_response {
    error_code         = 404
    response_code      = 200
    response_page_path = "/index.html"
  }
  custom_error_response {
    error_code         = 403
    response_code      = 200
    response_page_path = "/index.html"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cdn.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}
