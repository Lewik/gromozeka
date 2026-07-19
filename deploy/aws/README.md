# Gromozeka on AWS

The first production shape is deliberately small:

- one `t3a.medium` On-Demand EC2 instance for Server, Cloud Worker, PostgreSQL
  and RabbitMQ;
- a separate encrypted EBS data volume;
- immutable Server and Worker images in ECR;
- private S3 runtime bundles and nightly PostgreSQL/home backups;
- no inbound security-group rules, SSH keys or long-lived GitHub AWS keys;
- SSM for operations and Tailscale Serve for application access.

GitHub Actions authenticates through OIDC. The build role can only push the two
ECR repositories. The deployment role can only publish a runtime bundle and run
the deployment SSM document against the single managed instance.

## Local prerequisites

Set the AWS profile once in the ignored local environment file:

```bash
printf 'AWS_PROFILE=codex\nAWS_REGION=il-central-1\n' > deploy/aws/local.env
```

The Tel Aviv region must be enabled explicitly before Terraform can use it:

```bash
deploy/aws/bin/region-status
aws account enable-region --region-name il-central-1
```

Do not continue until `region-status` reports `ENABLED`.

## Provision

```bash
deploy/aws/bin/bootstrap-state
cp deploy/aws/terraform/terraform.tfvars.example deploy/aws/terraform/terraform.tfvars
deploy/aws/bin/terraform-plan
deploy/aws/bin/terraform-apply
deploy/aws/bin/configure-github
```

`terraform-plan` creates a saved plan. Review its resources and cost before
running `terraform-apply`.

## Deploy

```bash
deploy/aws/bin/deploy
```

This dispatches one GitHub Actions workflow that tests and assembles the
application, pushes both images under the full Git commit SHA, publishes the
runtime bundle and deploys it through SSM.

## Operate

```bash
deploy/aws/bin/status
deploy/aws/bin/logs
deploy/aws/bin/shell
deploy/aws/bin/tunnel
deploy/aws/bin/backup
```

The first Tailscale login is interactive. Open `deploy/aws/bin/shell`, then run:

```bash
sudo tailscale up --accept-dns=false
sudo tailscale serve --bg --yes http://127.0.0.1:8765
tailscale serve status
```

Run `deploy/aws/bin/deploy` once more after the first Tailscale login. The
deployment discovers the instance MagicDNS name and adds it to the MCP Host and
Origin allowlists without hardcoding a tailnet name.

Until Tailscale is connected, `deploy/aws/bin/tunnel` exposes the Server only
through an authenticated SSM port-forwarding session.
