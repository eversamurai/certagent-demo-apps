# CertAgent demo environment

Props for the [CertAgent](https://github.com/eversamurai/meta-certificate-solver-hc) demo video. These make the
lab look like a small company running Java services, and add a **traffic probe**: real HTTPS clients that fail
when a certificate breaks and recover when CertAgent fixes it.

**Recording guide: [demo/RUNBOOK.md](demo/RUNBOOK.md)**

## What's here

| Piece | Path | What it is |
|---|---|---|
| storefront | `storefront/` | Java 21 (JDK only) HTTP service: product catalogue. `/`, `/api/health`, `/api/products` |
| checkout | `checkout/` | The same, for payments: `/`, `/api/health`, `/api/orders` |
| accounts | `accounts/` | The same, for sign-in and profiles: `/`, `/api/health`, `/api/users`. Behind `accounts.lab` (:8445), used by `scenario.sh hostname` |
| traffic-probe | `traffic-probe/` | Java 21 HTTPS client checking all three sites every 2s, plus a status page on **:8090** |
| reasoning-relay | `reasoning-relay/` | Java 21 proxy between CertAgent and Ollama on **:11435**. Asks Ollama for a stream, publishes the thinking tokens on `/live` (server-sent events), and returns the same single answer CertAgent expects |
| nginx overlay | `lab-overlay/` | The lab's `nginx.conf`, with `proxy_pass` to the apps in place of placeholder text, plus a compose override that swaps it in |
| compose | `compose.yml` | Project `certagent-demo`: the apps and the probe on the lab's network (`certagent-lab_default`) |
| scripts | `demo/` | `reset.sh`, `scenario.sh fixed\|approval\|rollback`, `RUNBOOK.md` |

```
                        ┌──────────── docker network certagent-lab_default ────────────┐
traffic-probe ─HTTPS──▶ │ nginx :443  demo.lab  ──http──▶ storefront :8080              │
 (trusts the lab        │ nginx :8444 shop.prod ──http──▶ checkout   :8080              │
  root only, :8090)     │ nginx :80   /.well-known/acme-challenge/  (ACME http-01)      │
                        │ ca    :9000 step-ca (ACME)                                    │
                        └──────────────────────────────────────────────────────────────┘
CertAgent (host, :8088) scans https://localhost:8443 / :8444 and renews certs in cert-verif-app/lab/certs/
```

## How it fits with the lab without changing it

The lab (step-ca, nginx, `init.sh`, `break.sh`) lives in `cert-verif-app/lab/`, and this repo doesn't edit it.

- **nginx config.** `demo/reset.sh` runs the lab's `init.sh`, then re-creates nginx with
  `-f cert-verif-app/lab/docker-compose.yml -f lab-overlay/nginx.override.yml`. The override replaces only the
  `nginx.conf` mount. Ports, the `demo.lab`/`shop.prod` aliases, the cert and webroot mounts and `conf.d` all
  stay as the lab defines them. CertAgent's reload (`docker compose -f lab/docker-compose.yml exec nginx …`)
  runs inside that container, so it keeps the overlay.
- **Upstreams are looked up per request** (`resolver 127.0.0.11` plus a variable in `proxy_pass`). So
  `nginx -t` never fails because an app is down, and a renewal can't be rolled back for the wrong reason.
- **TLS stays in nginx.** CertAgent installs PEM files and reloads nginx. The Java apps speak plain HTTP behind
  it, like a typical Java service behind a reverse proxy.
- **The probe mounts `cert-verif-app/lab/certs` read-only** and reads only `root_ca.crt`, again on every check.
  It mounts the folder rather than the single file because Docker turns a missing single-file mount into a
  directory.

## Traffic probe

- **Every 2 seconds**, for each site, it sends a GET to `https://demo.lab/api/products` and
  `https://shop.prod:8444/api/orders`. These go through the docker aliases, so SNI and the hostname check are real.
- **Trust:** it uses `java.net.http.HttpClient` with an `SSLContext` that trusts **only** the lab root. Hostname
  verification is on.
- **A fresh client and SSLContext on every check.** A pooled connection or a resumed TLS session would keep
  showing the old certificate after nginx reloads.
- **Hard deadline** of 3s per request, so a stuck server can't freeze the page.
- **Certificate details** come from the response's `SSLSession`. If the handshake failed, a separate socket that
  accepts any certificate captures what the server sent.
- **Fingerprint** uses CertAgent's format: lowercase hex SHA-256 of the DER, and the page shows the first 12 characters.
- **States:**
  - **OK**: the request worked.
  - **WARN**: the request worked but the cert is risky (RSA under 2048 bits, or under 6h until it expires).
    Java accepts RSA 1024, so without this rule the weak-key case would look healthy.
  - **FAIL**: the request failed, or nginx returned 5xx.
- **Endpoints:**
  - `GET /`: the status page. A single file, system fonts, no external requests.
  - `GET /api/status`: everything as JSON. The page polls it every second.
  - `GET /api/state/{demo.lab|shop.prod}`: `OK`, `WARN`, `FAIL` or `NONE` as plain text, used by the scripts.

## Ports

| Port | What |
|---|---|
| 8090 | Traffic probe page (new), with the live reasoning panel |
| 11435 | Reasoning relay (new): start CertAgent with `OLLAMA_URL=http://localhost:11435` |
| 8080, 8443, 8444, 8445, 9000 | The lab (nginx http, demo.lab, shop.prod, accounts.lab, step-ca) |
| 8088 | CertAgent |

The apps have no host ports. nginx is the only way in.

## Run

```bash
demo/reset.sh                       # lab + overlay + apps + probe, empty CertAgent timeline
# in cert-verif-app: ./mvnw spring-boot:run -Dspring-boot.run.arguments=--certagent.schedule.run-on-startup=false
demo/scenario.sh fixed              # demo.lab Fixed, shop.prod Needs approval
demo/scenario.sh rollback           # demo.lab Rolled back, probe never red
```

- **Requirements:** Docker Desktop, Git Bash on Windows (or any bash), `curl`, Python 3 for the scenario report,
  and `cert-verif-app` next to this repo (or set `CERTAGENT_HOME`).
- **Images** are pinned: `eclipse-temurin:21.0.13_9-jdk-alpine` to build, `21.0.13_9-jre-alpine` to run.
- **No Maven and no dependencies.** Each image compiles one `.java` file with `javac`.
