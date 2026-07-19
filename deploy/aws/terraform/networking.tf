resource "aws_vpc" "gromozeka" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.project_name}-${var.environment}"
  }
}

resource "aws_internet_gateway" "gromozeka" {
  vpc_id = aws_vpc.gromozeka.id

  tags = {
    Name = "${var.project_name}-${var.environment}"
  }
}

resource "aws_subnet" "gromozeka" {
  vpc_id                  = aws_vpc.gromozeka.id
  cidr_block              = var.subnet_cidr
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.project_name}-${var.environment}"
  }
}

resource "aws_route_table" "gromozeka" {
  vpc_id = aws_vpc.gromozeka.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.gromozeka.id
  }

  tags = {
    Name = "${var.project_name}-${var.environment}"
  }
}

resource "aws_route_table_association" "gromozeka" {
  subnet_id      = aws_subnet.gromozeka.id
  route_table_id = aws_route_table.gromozeka.id
}

resource "aws_security_group" "gromozeka" {
  name_prefix = "${var.project_name}-${var.environment}-"
  description = "No public ingress. Management and application access use outbound-initiated SSM and Tailscale."
  vpc_id      = aws_vpc.gromozeka.id

  egress {
    description = "Outbound internet for ECR, SSM, Tailscale and LLM providers."
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}"
  }

  lifecycle {
    create_before_destroy = true
  }
}
