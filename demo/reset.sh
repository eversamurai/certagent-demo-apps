#!/usr/bin/env bash
# Reset the whole demo: lab (CA, fresh certs, nginx with the app overlay), Java apps, probe, and an empty
# CertAgent timeline. Run with CertAgent stopped. Usage: demo/reset.sh
source "$(dirname "$0")/common.sh"

command -v docker >/dev/null || fail "docker not found — start Docker Desktop"
[[ -f "$CERTAGENT_HOME/lab/init.sh" ]] || fail "no lab at $CERTAGENT_HOME/lab — set CERTAGENT_HOME to the cert-verif-app checkout"

if agent_up && [[ ${FORCE:-0} != 1 ]]; then
  fail "CertAgent is running on :8088 — stop it first (Ctrl+C) so its timeline is cleared too (or FORCE=1)"
fi

# Our nginx.conf is a copy of the lab's with proxy_pass instead of placeholder text. If the lab's file gained
# anything else (a new server, a changed ACME location), say so instead of silently serving an old copy.
drift=$(grep -vxFf <(sed 's/\r$//' "$DEMO_APPS/lab-overlay/nginx.conf") <(sed 's/\r$//' "$CERTAGENT_HOME/lab/nginx/nginx.conf") \
        | grep -vE 'return 200 "(demo\.lab|shop\.prod) OK|^\s*(#.*)?$' || true)
if [[ -n $drift ]]; then
  log "WARNING: lab/nginx/nginx.conf has lines the overlay copy lacks — update lab-overlay/nginx.conf:"
  printf '    %s\n' "$drift"
fi

log "lab: init.sh (CA, root export, 24h demo.lab cert, 4h shop.prod cert)"
(cd "$CERTAGENT_HOME" && ./lab/init.sh)

log "apps + probe: build and start"
"${APPS[@]}" up -d --build

log "nginx: recreate with the overlay config (demo.lab -> storefront, shop.prod -> checkout)"
"${LAB[@]}" up -d --force-recreate nginx
"${LAB[@]}" exec -T nginx grep -q 'proxy_pass' /etc/nginx/nginx.conf || fail "nginx is not running the overlay config"
"${LAB[@]}" exec -T nginx nginx -t -q || fail "nginx -t fails after reset"

log "CertAgent: clearing data/ (timeline, backups, ACME account)"
rm -rf "$CERTAGENT_HOME/data"

wait_state demo.lab  'OK'      60
wait_state shop.prod 'OK|WARN' 30

cat <<EOF

  Ready.  Probe:    $PROBE
          Timeline: $AGENT (start it now)

  Start CertAgent in $CERTAGENT_HOME:
    export JAVA_HOME="/c/Program Files/Java/jdk-23.0.1"
    ./mvnw spring-boot:run -Dspring-boot.run.arguments=--certagent.schedule.run-on-startup=false

  Then: demo/scenario.sh fixed

EOF
