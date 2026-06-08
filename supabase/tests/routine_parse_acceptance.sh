#!/usr/bin/env bash
# acceptance tests for POST /functions/v1/routine-parse
set -euo pipefail

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
FN_URL="${SUPABASE_URL}/functions/v1/routine-parse"
AUTH_URL="${SUPABASE_URL}/auth/v1"
EVIDENCE_DIR="${EVIDENCE_DIR:-$(dirname "$0")/../../docs/test-evidence}"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="${EVIDENCE_DIR}/routine-parse-${TS}.log"

mkdir -p "${EVIDENCE_DIR}"

if [[ -z "${SUPABASE_ANON_KEY:-}" || -z "${SUPABASE_SERVICE_ROLE_KEY:-}" ]]; then
  echo "export SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY"
  exit 1
fi

log() { printf '\n========== %s ==========\n' "$*" | tee -a "${LOG_FILE}"; }

create_user() {
  curl -sS -X POST "${AUTH_URL}/admin/users" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"routine_parse_${TS}@think-act.test\",\"password\":\"Routine_${TS}!\",\"email_confirm\":true}"
}

sign_in() {
  local email="$1"
  local pass="$2"
  curl -sS -X POST "${AUTH_URL}/token?grant_type=password" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${pass}\"}"
}

routine_parse() {
  local token="${1:-}"
  local text="$2"
  local auth_header=()
  if [[ -n "${token}" ]]; then
    auth_header=(-H "Authorization: Bearer ${token}")
  fi
  curl -sS -w "\nHTTP_STATUS:%{http_code}" -X POST "${FN_URL}" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    "${auth_header[@]}" \
    -H "Content-Type: application/json" \
    -d "{\"text\":\"${text}\",\"timezone\":\"Asia/Shanghai\"}"
}

EMAIL="routine_parse_${TS}@think-act.test"
PASS="Routine_${TS}!"

log "setup user"
CREATE="$(create_user)"
TOKEN="$(sign_in "${EMAIL}" "${PASS}" | jq -r '.access_token')"
echo "email=${EMAIL}" | tee -a "${LOG_FILE}"

log "TEST 1 — no JWT → 401"
RAW1="$(routine_parse "" "每天早上八点健身")"
HTTP1="$(echo "${RAW1}" | sed -n 's/.*HTTP_STATUS://p')"
BODY1="$(echo "${RAW1}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP1}" | tee -a "${LOG_FILE}"
echo "${BODY1}" | tee -a "${LOG_FILE}"
[[ "${HTTP1}" == "401" ]]

log "TEST 2 — 工作日晚上九点学英语"
RAW2="$(routine_parse "${TOKEN}" "工作日晚上九点学英语")"
HTTP2="$(echo "${RAW2}" | sed -n 's/.*HTTP_STATUS://p')"
BODY2="$(echo "${RAW2}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP2}" | tee -a "${LOG_FILE}"
echo "${BODY2}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP2}" == "200" ]]
echo "${BODY2}" | jq -e '.routines[0].title == "学英语"' >/dev/null
echo "${BODY2}" | jq -e '.routines[0].default_time == "21:00"' >/dev/null
echo "${BODY2}" | jq -e '.routines[0].repeat_days == [1,2,3,4,5]' >/dev/null
echo "${BODY2}" | jq -e '([.. | strings] | any(test("sk-|apikey|secret"; "i"))) | not' >/dev/null

log "TEST 3 — 早上八点健身，晚上十点吃药 → 2 routines"
RAW3="$(routine_parse "${TOKEN}" "早上八点健身，晚上十点吃药")"
HTTP3="$(echo "${RAW3}" | sed -n 's/.*HTTP_STATUS://p')"
BODY3="$(echo "${RAW3}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP3}" | tee -a "${LOG_FILE}"
echo "${BODY3}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP3}" == "200" ]]
echo "${BODY3}" | jq -e '(.routines | length) == 2' >/dev/null
echo "${BODY3}" | jq -e '.routines[0].title == "健身" and .routines[1].title == "吃药"' >/dev/null

log "PASS — evidence: ${LOG_FILE}"
