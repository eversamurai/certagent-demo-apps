#!/usr/bin/env bash
# Break demo.lab (or not), trigger one CertAgent run, wait for it, print the outcomes.
#   fixed     untrusted cert -> probe FAIL -> run           -> demo.lab Fixed, shop.prod Needs approval
#   approval  nothing to break (shop.prod's 4h cert)        -> shop.prod Needs approval
#   rollback  weak key + broken nginx config -> probe WARN  -> demo.lab Rolled back, probe never FAIL
# Usage: demo/scenario.sh fixed|approval|rollback
source "$(dirname "$0")/common.sh"

mode=${1:-}
BREAK="$CERTAGENT_HOME/lab/break.sh"

agent_up || fail "CertAgent is not answering on :8088 — start it first (see demo/RUNBOOK.md)"
[[ $(probe_state demo.lab) != DOWN ]] || fail "traffic probe is not answering on :8090 — run demo/reset.sh"

case "$mode" in
  fixed)
    "$BREAK" untrusted
    wait_state demo.lab FAIL 15
    ;;
  approval)
    log "nothing to break: shop.prod already serves a 4h cert (probe: $(probe_state shop.prod))"
    ;;
  rollback)
    "$BREAK" weak
    "$BREAK" reload
    wait_state demo.lab WARN 15
    ;;
  *)
    echo "usage: $0 fixed|approval|rollback" >&2
    exit 2
    ;;
esac

resp=$(curl -sf -X POST "$AGENT/api/run") || fail "POST /api/run refused (is a run already in progress?)"
run=$("$PY" -c 'import json,sys; print(json.load(sys.stdin)["runId"])' <<<"$resp")
log "CertAgent run $run started — watch the timeline"

# Track the worst probe state for demo.lab while the agent works: the rollback case must never show FAIL.
worst=OK
for ((i = 0; i < 600; i++)); do
  s=$(probe_state demo.lab)
  [[ $s == FAIL ]] && worst=FAIL
  [[ $s == WARN && $worst == OK ]] && worst=WARN
  running=$(curl -sf "$AGENT/api/status" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["running"])' || echo unknown)
  [[ $running == False ]] && break
  sleep 1
done
[[ $running == False ]] || fail "run $run still going after 10 minutes"

# Give the probe one more check after the final reload before comparing fingerprints.
sleep 3
TMP="$DEMO_APPS/.demo-tmp"   # inside the repo (gitignored): Windows Python can't open Git Bash's /tmp
mkdir -p "$TMP"
curl -sf "$AGENT/api/events" > "$TMP/events.json"
curl -sf "$PROBE/api/status" > "$TMP/probe.json"

RUN="$run" MODE="$mode" WORST="$worst" "$PY" - "$TMP/events.json" "$TMP/probe.json" <<'EOF'
import json, os, sys
events = json.load(open(sys.argv[1], encoding="utf-8"))
probe = {t["name"]: t for t in json.load(open(sys.argv[2], encoding="utf-8"))["targets"]}
run = os.environ["RUN"]
mine = [e for e in events if e.get("runId") == run]

done = [e for e in mine if e["kind"] == "DONE" and not e.get("target")]
outcomes = done[-1]["details"].get("outcomes", {}) if done else {}
print()
print(f"  Run {run}")
for target, outcome in outcomes.items():
    p = probe.get(target, {})
    print(f"    {target:<10} {outcome:<16} probe: {p.get('state', '?'):<4} fp {p.get('cert', {}).get('fp', '-')}")

# The fingerprint the timeline says demo.lab should now serve: the new cert if kept, the backup if rolled back.
want = None
for e in mine:
    d = e.get("details") or {}
    if e.get("target") != "demo.lab":
        continue
    if e["kind"] == "VERIFY" and d.get("phase") == "after" and d.get("ok"):
        want = (d.get("served") or {}).get("fingerprint")
    if e["kind"] == "ROLLBACK":
        want = d.get("backupFingerprint")
served = probe.get("demo.lab", {}).get("cert", {}).get("fp")
if want and served:
    match = "yes" if want.startswith(served) else "NO"
    print(f"\n  demo.lab fingerprint — timeline {want[:12]}  probe {served}  match: {match}")
if os.environ["MODE"] == "rollback":
    w = os.environ["WORST"]
    print(f"  demo.lab worst probe state during the run: {w}" + ("  (no outage)" if w != "FAIL" else "  (OUTAGE)"))
if done:
    print("\n  " + (done[-1]["details"].get("summary") or done[-1]["message"]))
print()
EOF
