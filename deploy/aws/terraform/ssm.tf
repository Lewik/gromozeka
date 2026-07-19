resource "aws_ssm_document" "deploy" {
  name            = "${var.project_name}-${var.environment}-deploy"
  document_type   = "Command"
  document_format = "JSON"

  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Deploy immutable Gromozeka server and worker images."
    parameters = {
      ImageTag = {
        type           = "String"
        description    = "Full Git commit SHA used as the immutable ECR image tag."
        allowedPattern = "^[0-9a-f]{40}$"
      }
      RuntimeBundleKey = {
        type           = "String"
        description    = "S3 key containing the runtime compose bundle."
        allowedPattern = "^runtime/[0-9a-f]{40}\\.tar\\.gz$"
      }
    }
    mainSteps = [
      {
        action = "aws:runShellScript"
        name   = "deploy"
        inputs = {
          timeoutSeconds = "1800"
          runCommand = [
            "#!/usr/bin/env bash",
            "set -euo pipefail",
            "test -f /var/lib/cloud/instance/gromozeka-bootstrap-complete",
            "release_dir=/opt/gromozeka/releases/{{ ImageTag }}",
            "archive_path=$(mktemp /tmp/gromozeka-runtime.XXXXXX.tar.gz)",
            "trap 'rm -f \"$archive_path\"; [[ -z \"$${release_tmp:-}\" ]] || rm -rf \"$release_tmp\"' EXIT",
            "if [[ ! -f \"$release_dir/.bundle-ready\" ]]; then release_tmp=\"$${release_dir}.new.$$\"; install -d -m 0755 \"$release_tmp\"; aws s3 cp 's3://${aws_s3_bucket.artifacts.id}/{{ RuntimeBundleKey }}' \"$archive_path\" --region '${var.aws_region}'; tar -xzf \"$archive_path\" -C \"$release_tmp\"; chmod 0755 \"$release_tmp/deploy\" \"$release_tmp/backup\"; touch \"$release_tmp/.bundle-ready\"; mv \"$release_tmp\" \"$release_dir\"; release_tmp=\"\"; fi",
            "\"$release_dir/deploy\" '${aws_ecr_repository.server.repository_url}:{{ ImageTag }}' '${aws_ecr_repository.worker.repository_url}:{{ ImageTag }}' '{{ ImageTag }}' '${var.aws_region}' '${aws_s3_bucket.artifacts.id}'",
            "ln -sfn \"$release_dir\" /opt/gromozeka/runtime.next",
            "mv -Tf /opt/gromozeka/runtime.next /opt/gromozeka/runtime",
            "touch \"$release_dir\"",
            "find /opt/gromozeka/releases -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\\n' | sort -nr | tail -n +6 | cut -d' ' -f2- | xargs -r rm -rf",
          ]
        }
      }
    ]
  })

  tags = {
    Name = "${var.project_name}-${var.environment}-deploy"
  }
}
