# Shared helpers for reset.sh and scenario.sh. Source, don't run.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite container paths

# Windows-style absolute paths (D:/dev/...) on Git Bash, plain ones elsewhere: docker needs these for mounts.
winpath() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }

DEMO_APPS="$(winpath "$(dirname "${BASH_SOURCE[0]}")/..")"
CERTAGENT_HOME="$(winpath "${CERTAGENT_HOME:-$DEMO_APPS/../cert-verif-app}")"
export DEMO_APPS CERTAGENT_HOME

# The lab with our nginx overlay, and this repo's own project (apps + probe).
LAB=(docker compose -f "$CERTAGENT_HOME/lab/docker-compose.yml" -f "$DEMO_APPS/lab-overlay/nginx.override.yml")
APPS=(docker compose -f "$DEMO_APPS/compose.yml")

PROBE=http://localhost:8090
AGENT=http://localhost:8088
PY=${PYTHON:-python}
export PYTHONIOENCODING=utf-8   # Windows Python would print cp1252 into Git Bash

log()  { printf '\033[35m[demo]\033[0m %s\n' "$*"; }
fail() { printf '\033[31m[demo]\033[0m %s\n' "$*" >&2; exit 1; }

# probe_state NAME -> OK | WARN | FAIL | NONE | DOWN (probe not answering)
probe_state() { curl -sf "$PROBE/api/state/$1" 2>/dev/null || echo DOWN; }

# wait_state NAME REGEX SECONDS — wait until the probe's state for NAME matches REGEX (e.g. 'FAIL', 'OK|WARN').
wait_state() {
  local name=$1 want=$2 secs=$3 s
  for ((i = 0; i < secs; i++)); do
    s=$(probe_state "$name")
    if [[ $s =~ ^($want)$ ]]; then log "probe: $name is $s"; return 0; fi
    sleep 1
  done
  fail "probe: $name is still $s after ${secs}s (wanted $want)"
}

agent_up() { curl -sf "$AGENT/api/status" >/dev/null 2>&1; }
