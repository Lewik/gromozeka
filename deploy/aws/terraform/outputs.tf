output "aws_account_id" {
  value = data.aws_caller_identity.current.account_id
}

output "aws_region" {
  value = var.aws_region
}

output "artifact_bucket" {
  value = aws_s3_bucket.artifacts.id
}

output "server_repository_url" {
  value = aws_ecr_repository.server.repository_url
}

output "worker_repository_url" {
  value = aws_ecr_repository.worker.repository_url
}

output "instance_id" {
  value = aws_instance.gromozeka.id
}

output "github_build_role_arn" {
  value = aws_iam_role.github_build.arn
}

output "github_deploy_role_arn" {
  value = aws_iam_role.github_deploy.arn
}

output "deploy_document_name" {
  value = aws_ssm_document.deploy.name
}

output "tailscale_setup_command" {
  value = "sudo tailscale up --accept-dns=false && sudo tailscale serve --bg --yes http://127.0.0.1:8765"
}
