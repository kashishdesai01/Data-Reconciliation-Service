#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_URL="${API_URL:-http://localhost:8080}"
REVIEWER_USERNAME="${REVIEWER_USERNAME:-reviewer}"
REVIEWER_PASSWORD="${REVIEWER_PASSWORD:-change-me}"

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
else
  COMPOSE=(docker-compose)
fi

python3 "$ROOT_DIR/scripts/generate-seed.py"

for source in CRM Billing; do
  lower="$(printf '%s' "$source" | tr '[:upper:]' '[:lower:]')"
  curl --fail --silent --show-error \
    --user "$REVIEWER_USERNAME:$REVIEWER_PASSWORD" \
    -H 'Content-Type: application/json' \
    --data-binary "@$ROOT_DIR/seed/$lower.json" \
    "$API_URL/api/ingest/$source" >/dev/null
done

replay="$(curl --fail --silent --show-error --user "$REVIEWER_USERNAME:$REVIEWER_PASSWORD" \
  -H 'Content-Type: application/json' --data-binary "@$ROOT_DIR/seed/crm.json" "$API_URL/api/ingest/CRM")"
REPLAY_JSON="$replay" python3 -c 'import json,os; d=json.loads(os.environ["REPLAY_JSON"]); assert d["unchanged"] == 500, d; print("Idempotent replay: 500 unchanged")'

run="$(curl --fail --silent --show-error --user "$REVIEWER_USERNAME:$REVIEWER_PASSWORD" \
  -X POST "$API_URL/api/match-runs")"
run_id="$(RUN_JSON="$run" python3 -c 'import json,os; print(json.loads(os.environ["RUN_JSON"])["runId"])')"
printf 'Started match run %s\n' "$run_id"

for _ in $(seq 1 240); do
  status_json="$(curl --fail --silent --show-error "$API_URL/api/match-runs/$run_id")"
  status="$(STATUS_JSON="$status_json" python3 -c 'import json,os; print(json.loads(os.environ["STATUS_JSON"])["status"])')"
  case "$status" in
    COMPLETED|COMPLETED_WITH_ERRORS) break ;;
    FAILED) printf '%s\n' "$status_json"; exit 1 ;;
  esac
  sleep 1
done
if [[ "$status" != "COMPLETED" ]]; then
  printf 'Run did not complete cleanly: %s\n' "$status_json"
  exit 1
fi
printf 'Run completed: %s\n' "$status_json"

"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U reconciliation -d reconciliation -c \
  'DROP TABLE IF EXISTS evaluation_labels; CREATE TABLE evaluation_labels (
    group_id text NOT NULL, source_system text NOT NULL, source_record_id text NOT NULL,
    scenario text NOT NULL, PRIMARY KEY (source_system, source_record_id));'
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U reconciliation -d reconciliation \
  -c '\copy evaluation_labels FROM STDIN WITH (FORMAT csv, HEADER true)' < "$ROOT_DIR/seed/labels.csv"
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U reconciliation -d reconciliation \
  -f - < "$ROOT_DIR/scripts/evaluation.sql"
