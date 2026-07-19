locals {
  github_oidc_host = "token.actions.githubusercontent.com"
  github_oidc_arn  = aws_iam_openid_connect_provider.github.arn
}

resource "aws_iam_openid_connect_provider" "github" {
  url = "https://${local.github_oidc_host}"

  client_id_list = [
    "sts.amazonaws.com",
  ]
}

data "aws_iam_policy_document" "github_build_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/${var.github_branch}"]
    }
  }
}

resource "aws_iam_role" "github_build" {
  name               = "${var.project_name}-${var.environment}-github-build"
  assume_role_policy = data.aws_iam_policy_document.github_build_assume_role.json
}

data "aws_iam_policy_document" "github_build" {
  statement {
    sid       = "AuthenticateToEcr"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PushImmutableImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [
      aws_ecr_repository.server.arn,
      aws_ecr_repository.worker.arn,
    ]
  }
}

resource "aws_iam_role_policy" "github_build" {
  name   = "push-cloud-images"
  role   = aws_iam_role.github_build.id
  policy = data.aws_iam_policy_document.github_build.json
}

data "aws_iam_policy_document" "github_deploy_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:sub"
      values   = ["repo:${var.github_repository}:environment:${var.github_deploy_environment}"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${var.project_name}-${var.environment}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_deploy_assume_role.json
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid = "PublishRuntimeBundle"
    actions = [
      "s3:PutObject",
    ]
    resources = [
      "${aws_s3_bucket.artifacts.arn}/runtime/*",
    ]
  }

  statement {
    sid = "DeployToGromozekaInstance"
    actions = [
      "ssm:SendCommand",
    ]
    resources = [
      aws_instance.gromozeka.arn,
      aws_ssm_document.deploy.arn,
    ]
  }

  statement {
    sid = "ObserveDeployment"
    actions = [
      "ssm:GetCommandInvocation",
      "ssm:ListCommandInvocations",
      "ssm:ListCommands",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "deploy-cloud-runtime"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}

data "aws_iam_policy_document" "instance_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    effect  = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name               = "${var.project_name}-${var.environment}-instance"
  assume_role_policy = data.aws_iam_policy_document.instance_assume_role.json
}

resource "aws_iam_role_policy_attachment" "instance_ssm" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "instance" {
  statement {
    sid       = "AuthenticateToEcr"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PullCloudImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [
      aws_ecr_repository.server.arn,
      aws_ecr_repository.worker.arn,
    ]
  }

  statement {
    sid = "ReadRuntimeBundles"
    actions = [
      "s3:GetObject",
    ]
    resources = [
      "${aws_s3_bucket.artifacts.arn}/runtime/*",
    ]
  }

  statement {
    sid = "WriteBackups"
    actions = [
      "s3:AbortMultipartUpload",
      "s3:PutObject",
    ]
    resources = [
      "${aws_s3_bucket.artifacts.arn}/backups/*",
    ]
  }
}

resource "aws_iam_role_policy" "instance" {
  name   = "runtime-artifacts-and-images"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.instance.json
}

resource "aws_iam_instance_profile" "gromozeka" {
  name = "${var.project_name}-${var.environment}"
  role = aws_iam_role.instance.name
}
