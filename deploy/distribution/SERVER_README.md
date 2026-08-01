# Gromozeka Server

The Gromozeka Server is distributed as a Docker Compose stack containing the
Server, PostgreSQL with pgvector, and a Caddy HTTPS/WSS gateway. The Server port
is available only inside the Compose network; clients and Workers connect
through Caddy on port 443.

Copy `gromozeka.env.example` to `gromozeka.env`, replace every example value,
and start the stack:

```text
docker compose --env-file gromozeka.env up -d
```

Three TLS modes are available:

- `Caddyfile`: automatic public certificates. Point public DNS at the host and
  allow inbound TCP ports 80 and 443.
- `Caddyfile.internal`: Caddy's private CA. Set
  `GROMOZEKA_CADDY_CONFIG=Caddyfile.internal`, start the stack, and export its
  root with `docker compose --env-file gromozeka.env cp
  caddy:/data/caddy/pki/authorities/local/root.crt ./certs/gromozeka-root.crt`.
  Install that root on every client and pass it to Worker enrollment with
  `--ca-certificate ./certs/gromozeka-root.crt`.
- `Caddyfile.provided`: an organization-issued certificate. Put the certificate
  chain and private key in `certs/`, set their file names in `gromozeka.env`,
  and set `GROMOZEKA_CADDY_CONFIG=Caddyfile.provided`. Client and Worker
  operating systems must trust the issuing CA; Workers can additionally use
  `--ca-certificate` when their bundled Java runtime does not.

The optional containerized Worker does not include Claude Code. Claude Code is
installed, licensed, and authenticated separately on a standalone Worker host.
