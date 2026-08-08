# Gromozeka on AWS

The first production shape is deliberately small:

- one `t3a.medium` On-Demand EC2 instance for Server and PostgreSQL;
- a separate encrypted EBS data volume;
- immutable Server and Worker images in ECR;
- private S3 runtime bundles and nightly PostgreSQL/home backups;
- no inbound security-group rules, SSH keys or long-lived GitHub AWS keys;
- SSM for operations and Tailscale Serve for application access.

GitHub Actions authenticates through OIDC. The release build role can only push
the two ECR repositories. The deployment role can inspect those release images,
publish a runtime bundle, and run the deployment SSM document against the
single managed instance.

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

## Release and deploy

```bash
gh workflow run release.yml \
  --ref main \
  --field version=1.7.0 \
  --field publish_release=true \
  --field deploy_aws=true
```

The release workflow tests and assembles every published artifact, pushes the
same versioned Server and Worker images to GHCR and ECR, publishes the GitHub
Release, then installs that exact release through SSM. Pushing a `v*` tag runs
the same release-and-deploy path.

Redeploy an already published release without rebuilding it:

```bash
deploy/aws/bin/deploy 1.7.0
```

## Operate

```bash
deploy/aws/bin/status
deploy/aws/bin/logs
deploy/aws/bin/shell
deploy/aws/bin/tunnel
deploy/aws/bin/backup
```

## Optional AWS Computer

AWS Computer adds a persistent graphical Linux session to the same EC2
instance. It uses a minimal Xdcv and Metacity desktop, the official Google
Chrome build, and a normal outbound Gromozeka Worker named `aws-computer`. Its
home and browser profile live on the encrypted data EBS volume. DCV listens
only on loopback; Tailscale Serve publishes its web client on private HTTPS
port `8443`.

An authenticated Gromozeka user with `USE` access to the Worker can ask an
agent for its interactive desktop link. The read-only
`grz_worker_interactive_access_get` tool returns a stable Server URL. Opening
that URL creates a 60-second, one-time DCV handoff and redirects the browser to
the `aws-computer` session without a second login. The Linux password remains
available as a recovery path through the direct DCV URL.

Amazon DCV and Google Chrome are installed directly from their vendors and are
not redistributed in Gromozeka artifacts. The signed Browser Bridge is
installed through Chrome's external extension mechanism. The Gromozeka Worker
and Browser Bridge are downloaded from the currently deployed GitHub Release
and verified against that release's `SHA256SUMS`.

Apply Terraform once to grant the EC2 role access to the regional DCV license,
then install and enroll the optional computer:

```bash
deploy/aws/bin/terraform-plan
deploy/aws/bin/terraform-apply
deploy/aws/bin/computer install
deploy/aws/bin/computer password
deploy/aws/bin/computer enroll ONE_TIME_ENROLLMENT_TOKEN
deploy/aws/bin/computer url
```

Use `deploy/aws/bin/computer status`, `start`, and `stop` for ordinary
operations. Published Server deployments update an already-installed AWS
Computer to the same Gromozeka version, but never install or start one
implicitly.

The first Tailscale login is interactive. Open `deploy/aws/bin/shell`, then run:

```bash
sudo tailscale up --accept-dns=false
sudo tailscale serve --bg --yes http://127.0.0.1:8765
tailscale serve status
```

Redeploy the current release once more after the first Tailscale login. The
deployment discovers the instance MagicDNS name and adds it to the MCP Host and
Origin allowlists without hardcoding a tailnet name.

Until Tailscale is connected, `deploy/aws/bin/tunnel` exposes the Server only
through an authenticated SSM port-forwarding session.
