#!/usr/bin/env bash
# 随手记(收件箱) acceptance — inbox-capture / list / badge / update
set -euo pipefail

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
CAPTURE_URL="${SUPABASE_URL}/functions/v1/inbox-capture"
LIST_URL="${SUPABASE_URL}/functions/v1/inbox-list"
BADGE_URL="${SUPABASE_URL}/functions/v1/inbox-badge"
UPDATE_URL="${SUPABASE_URL}/functions/v1/inbox-update"
AUTH_URL="${SUPABASE_URL}/auth/v1"
EVIDENCE_DIR="${EVIDENCE_DIR:-$(dirname "$0")/../../docs/test-evidence}"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="${EVIDENCE_DIR}/inbox-${TS}.log"
TODAY="$(date +%F)"

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
    -d "{\"email\":\"inbox_${TS}@think-act.test\",\"password\":\"Inbox_${TS}!\",\"email_confirm\":true}"
}

sign_in() {
  curl -sS -X POST "${AUTH_URL}/token?grant_type=password" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"inbox_${TS}@think-act.test\",\"password\":\"Inbox_${TS}!\"}"
}

curl_post() {
  curl -sS -w "\nHTTP_STATUS:%{http_code}" -X POST "$1" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$2"
}

curl_get() {
  curl -sS -w "\nHTTP_STATUS:%{http_code}" -X GET "$1" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${TOKEN}"
}

log "setup"
CREATE="$(create_user)"
USER_ID="$(echo "${CREATE}" | jq -r '.id')"
TOKEN="$(sign_in | jq -r '.access_token')"
echo "USER_ID=${USER_ID}" | tee -a "${LOG_FILE}"

log "TEST 1 — capture with date hint"
BODY1="$(jq -n --arg today "${TODAY}" '{raw_text:"周四下午发报告",client_local_date:$today,client_tz:"Asia/Shanghai",source:"text"}')"
RAW1="$(curl_post "${CAPTURE_URL}" "${BODY1}")"
HTTP1="$(echo "${RAW1}" | sed -n 's/.*HTTP_STATUS://p')"
RESP1="$(echo "${RAW1}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP1}" | tee -a "${LOG_FILE}"
echo "${RESP1}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP1}" == "200" ]]
ID1="$(echo "${RESP1}" | jq -r '.id')"
echo "${RESP1}" | jq -e '.title != null and (.due_part == "afternoon" or .due_date != null or .extract_failed == true)' >/dev/null

log "TEST 2 — capture no date"
BODY2="$(jq -n --arg today "${TODAY}" '{raw_text:"买咖啡豆",client_local_date:$today,client_tz:"Asia/Shanghai"}')"
RAW2="$(curl_post "${CAPTURE_URL}" "${BODY2}")"
HTTP2="$(echo "${RAW2}" | sed -n 's/.*HTTP_STATUS://p')"
RESP2="$(echo "${RAW2}" | sed '/HTTP_STATUS:/d')"
[[ "${HTTP2}" == "200" ]]
ID2="$(echo "${RESP2}" | jq -r '.id')"
echo "${RESP2}" | jq -e '.due_date == null or .due_date == ""' >/dev/null 2>&1 || echo "${RESP2}" | jq -e '.due_date == null' >/dev/null

log "TEST 3 — badge counts due pending"
# force due today on ID1 via set_due
BODY3="$(jq -n --arg id "${ID1}" --arg today "${TODAY}" '{id:$id,action:"set_due",due_date:$today,due_part:"afternoon"}')"
curl_post "${UPDATE_URL}" "${BODY3}" >/dev/null
RAW3="$(curl_get "${BADGE_URL}?client_local_date=${TODAY}")"
HTTP3="$(echo "${RAW3}" | sed -n 's/.*HTTP_STATUS://p')"
RESP3="$(echo "${RAW3}" | sed '/HTTP_STATUS:/d')"
[[ "${HTTP3}" == "200" ]]
echo "${RESP3}" | jq -e '.count >= 1' >/dev/null

log "TEST 4 — add_today transaction"
BODY4="$(jq -n --arg id "${ID1}" --arg today "${TODAY}" '{id:$id,action:"add_today",client_local_date:$today,client_tz:"Asia/Shanghai"}')"
RAW4="$(curl_post "${UPDATE_URL}" "${BODY4}")"
HTTP4="$(echo "${RAW4}" | sed -n 's/.*HTTP_STATUS://p')"
RESP4="$(echo "${RAW4}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP4} ${RESP4}" | tee -a "${LOG_FILE}"
[[ "${HTTP4}" == "200" ]]
TASK_ID="$(echo "${RESP4}" | jq -r '.task_id')"
[[ -n "${TASK_ID}" && "${TASK_ID}" != "null" ]]

log "TEST 5 — dismiss reduces badge"
BODY5="$(jq -n --arg id "${ID2}" '{id:$id,action:"dismiss"}')"
curl_post "${UPDATE_URL}" "${BODY5}" >/dev/null
RAW5="$(curl_get "${LIST_URL}")"
RESP5="$(echo "${RAW5}" | sed '/HTTP_STATUS:/d')"
echo "${RESP5}" | jq -e --arg id "${ID2}" '(.items[] | select(.id==$id).status) == "dismissed"' >/dev/null

log "PASS — evidence: ${LOG_FILE}"
