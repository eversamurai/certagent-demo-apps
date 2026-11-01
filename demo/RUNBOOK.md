# Recording runbook

Two takes. Take 1 shows cases 1 and 2 in one CertAgent run, and take 2 shows case 3 on top of it. Run every
command from `D:\dev\demo_apps` in Git Bash. CertAgent itself runs from `D:\dev\cert-verif-app`.

> **Always reset with `demo/reset.sh`, never with `cert-verif-app/lab/init.sh` alone.** `init.sh` recreates
> nginx without this repo's overlay, so the sites go back to the plain `demo.lab OK` text.

## 1. Prerequisites

- Docker Desktop is running: `docker version` shows a Server section.
- Ollama is running with the Glimmer model pulled. Check with `ollama list`, then set the variable to the exact tag:
  `export OLLAMA_MODEL=<tag>`.
- In the terminal that will run CertAgent: `export JAVA_HOME="/c/Program Files/Java/jdk-23.0.1"`.
- `cert-verif-app` is next to this repo. If it isn't, `export CERTAGENT_HOME=/d/path/to/cert-verif-app`.
- Ports 8080, 8443, 8444, 9000, 8088 and 8090 are free.

## 2. Screen layout

| Left half (960×1080) | Right half (960×1080) |
|---|---|
| CertAgent timeline: `http://localhost:8088` | Traffic probe: `http://localhost:8090` |

- Put a small terminal across the bottom (about 8 lines), using a large font.
- Browser zoom at 100%. If the probe tiles look small on camera, use 110%.
- Use dark mode in Windows and in the browser. The timeline follows the system theme, and the probe is always dark.
- Hide bookmarks bars and close other tabs.

## 3. Take 1: Fixed, then Needs approval

```bash
demo/reset.sh                     # ~1 min; ends with "Ready". Probe: demo.lab OK, shop.prod WARN
```

In the `cert-verif-app` terminal:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--certagent.schedule.run-on-startup=false
```

Wait until `http://localhost:8088` loads with an empty timeline. **Start recording**, then:

```bash
demo/scenario.sh fixed
```

Point at these, in order:
1. **Probe: demo.lab goes red.** The tile reads "certificate not trusted — unknown issuer", the issuer is
   *Rogue Corp Root*, and red squares start in the strip.
2. **Timeline: the demo.lab card appears.** Observed → Diagnosed (untrusted issuer).
3. **The reasoning headline.** This is the model's own explanation of why it is renewing.
4. **The `AUTO · lab` badge**, then Applied → Verified.
5. **The Before/After panels.** Red rogue issuer on the left, green lab CA on the right.
6. **Probe: demo.lab goes green.** Its fingerprint matches the timeline's "After" fingerprint. The script
   also prints `match: yes`.
7. **The shop.prod card:** `APPROVAL NEEDED · prod`, outcome **Needs approval**. The probe stays amber
   ("expires in 3h…") with no red, because nobody touched it.

## 4. Take 2: Rolled back (no reset, so take 1's cards stay underneath)

```bash
demo/scenario.sh rollback
```

The script installs a 1024-bit key and leaves an nginx config that fails `nginx -t`. The probe turns
demo.lab **amber** ("weak key: RSA 1024 bits") but never red. Point at:
1. **The new demo.lab card:** renew → Applied, then the **ROLLBACK** row. The new fingerprint is struck through,
   and the backup fingerprint is restored.
2. **The reason line** with nginx's `[emerg] invalid parameter "max_fails=three"`.
3. **The probe strip for demo.lab:** amber all the way through, with no red squares. No client saw an outage
   while the agent tried the fix and backed it out. The script prints `worst probe state during the run: WARN
   (no outage)`.

## 5. Clean-up

```bash
demo/reset.sh        # stop CertAgent first (Ctrl+C); removes the broken conf.d snippet, fresh certs, empty timeline
```

## 6. If it goes wrong

| Symptom | Check |
|---|---|
| Model takes more than a minute, or the card stays "reasoning…" | Run `ollama ps` to see whether the model is loaded. Warm it up with one throwaway run before recording, then run `demo/reset.sh`. |
| Card shows **Failed safely** with an ACME error | http-01 couldn't reach the challenge. Run `curl -s localhost:8080/.well-known/acme-challenge/x` (it should return 404 from nginx, not a connection error), and check that the `demo.lab` alias exists: `docker network inspect certagent-lab_default`. |
| Card shows **Unresolved** or no renew in take 1 | The run may have used up `max-actions-per-run` (10 tool calls, verifies included). Reset and record again. If it happens again, record cases 1 and 2 as separate takes using `scenario.sh approval`. |
| nginx won't start / `reset.sh` says "nginx -t fails" | Look at `cert-verif-app/lab/conf.d/` (`reset.sh` empties it) and at `docker compose -p certagent-lab logs nginx`. Never restart nginx between `break.sh reload` and a reset. |
| Probe shows FAIL "service behind nginx is not answering" (HTTP 502) | An app container is down: `docker compose -f compose.yml ps`, then `docker compose -f compose.yml up -d`. |
| Probe page doesn't load | `docker compose -f compose.yml logs traffic-probe`. Port 8090 may be busy. |
| `reset.sh` refuses: "CertAgent is running" | Stop it with Ctrl+C first, so the timeline is really cleared. |
| Ports busy | `netstat -ano \| grep -E ':(8080\|8443\|8444\|9000\|8088\|8090) '`, then stop whatever holds the port. |
| `reset.sh` prints a WARNING about nginx.conf | The lab's `nginx.conf` changed. Copy the change into `lab-overlay/nginx.conf`. |
