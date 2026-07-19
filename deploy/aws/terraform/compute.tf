locals {
  docker_compose_version      = "v5.3.1"
  docker_compose_amd64_sha256 = "f9ebc6ebdb19d769b793c245a736caaeb198c62587f13b25c660c13b4987f959"
  instance_name               = "${var.project_name}-${var.environment}"
}

resource "aws_instance" "gromozeka" {
  ami                         = data.aws_ssm_parameter.amazon_linux_2023.value
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.gromozeka.id
  vpc_security_group_ids      = [aws_security_group.gromozeka.id]
  iam_instance_profile        = aws_iam_instance_profile.gromozeka.name
  associate_public_ip_address = true

  disable_api_termination              = true
  instance_initiated_shutdown_behavior = "stop"

  user_data = templatefile("${path.module}/templates/user-data.sh.tftpl", {
    data_volume_id              = aws_ebs_volume.data.id
    docker_compose_version      = local.docker_compose_version
    docker_compose_amd64_sha256 = local.docker_compose_amd64_sha256
  })

  user_data_replace_on_change = false

  metadata_options {
    http_endpoint               = "enabled"
    http_protocol_ipv6          = "disabled"
    http_put_response_hop_limit = 1
    http_tokens                 = "required"
    instance_metadata_tags      = "disabled"
  }

  credit_specification {
    cpu_credits = "standard"
  }

  root_block_device {
    delete_on_termination = true
    encrypted             = true
    volume_size           = var.root_volume_size_gib
    volume_type           = "gp3"
  }

  tags = {
    Name = local.instance_name
  }

  depends_on = [
    aws_iam_role_policy_attachment.instance_ssm,
    aws_iam_role_policy.instance,
  ]
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  instance_id = aws_instance.gromozeka.id
  volume_id   = aws_ebs_volume.data.id

  stop_instance_before_detaching = true
}
