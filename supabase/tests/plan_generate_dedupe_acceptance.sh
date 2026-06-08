#!/usr/bin/env bash
# plan-generate dedupe: unit tests + production smoke (LLM non-deterministic on smoke)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EVIDENCE_DIR="${EVIDENCE_DIR:-${ROOT}/docs/test-evidence}"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="${EVIDENCE_DIR}/plan-generate-dedupe-${TS}.log"
PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
FN_URL="${SUPABASE_URL}/functions/v1/plan-generate"
AUTH_URL="${SUPABASE_URL}/auth/v1"

mkdir -p "${EVIDENCE_DIR}"
log() { printf '\n========== %s ==========\n' "$*" | tee -a "${LOG_FILE}"; }

log "TEST 1 — dedupe unit tests (deterministic)"
deno test "${ROOT}/supabase/functions/_shared/plan/dedupe_test.ts" 2>&1 | tee -a "${LOG_FILE}"

if [[ -z "${SUPABASE_ANON_KEY:-}" ]]; then
  log "SKIP smoke — set SUPABASE_ANON_KEY for LLM integration checks"
  log "PASS (unit only) — ${LOG_FILE}"
  exit 0
fi

log "TEST 2 — smoke: repeated 做文档 in one sentence"
TOKEN="$(curl -sS -X POST "${AUTH_URL}/signup" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${SUPABASE_ANON_KEY}" \
  -H "Content-Type: application/json" \
  -d "{}" | jq -r '.access_token')"
TODAY="$(date +%F)"

smoke() {
  local label="$1"
  local raw="$2"
  local jq_filter="$3"
  log "smoke: ${label}"
  RESP="$(curl -sS -X POST "${FN_URL}" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"date\":\"${TODAY}\",\"raw_input\":\"${raw}\",\"hard_constraints\":[],\"tone\":\"friendly\"}")"
  echo "${RESP}" | jq '{tasks: [.tasks[] | {title, planned_start}], count: (.tasks | length)}' | tee -a "${LOG_FILE}"
  echo "${RESP}" | jq -e "${jq_filter}" >/dev/null
}

smoke "口述重复" "今天上午做文档，做文档，做文档" \
  '[.tasks | map(.title | test("做文档")) | add] >= 1 and ([.tasks[] | select(.title | test("^做文档"))] | length) <= 1'

smoke "上午下午分块" "上午做文档，下午做文档" \
  '(.tasks | length) >= 2 and ([.tasks[].title] | any(test("^做文档$"))) and ([.tasks[].title] | any(test("续")))'

smoke "不同任务不丢" "上午做文档，下午开会，晚上跑步" \
  '(.tasks | length) >= 3'

log "PASS — evidence: ${LOG_FILE}"
