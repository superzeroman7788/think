#!/usr/bin/env bash
# acceptance tests for routine migration/RLS/CRUD and plan-generate routine merge
set -euo pipefail

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
REST_URL="${SUPABASE_URL}/rest/v1"
AUTH_URL="${SUPABASE_URL}/auth/v1"
FN_URL="${SUPABASE_URL}/functions/v1/plan-generate"

if [[ -z "${SUPABASE_ANON_KEY:-}" ]]; then
  echo "export SUPABASE_ANON_KEY first"
  exit 1
fi

TS="$(date +%s)"
EMAIL_A="routine_a_${TS}@think-act.test"
EMAIL_B="routine_b_${TS}@think-act.test"
PASS="RoutineAcc_${TS}!"
TODAY="$(date -u +%F)"
TOMORROW="$(date -u -v+1d +%F 2>/dev/null || date -u -d 'tomorrow' +%F)"

weekday_index() {
  python3 - "$1" <<'PY'
from datetime import datetime
import sys
d = datetime.strptime(sys.argv[1], "%Y-%m-%d").date()
# python weekday: Monday=0...Sunday=6; expected: Sunday=0...Saturday=6
print((d.weekday() + 1) % 7)
PY
}

TODAY_IDX="$(weekday_index "${TODAY}")"
TOMORROW_IDX="$(weekday_index "${TOMORROW}")"

signup() {
  local email="$1"
  curl -sS -X POST "${AUTH_URL}/signup" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_ANON_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${PASS}\"}"
}

log() { printf '\n========== %s ==========\n' "$*"; }

log "setup users"
SIGNUP_A="$(signup "${EMAIL_A}")"
TOKEN_A="$(echo "${SIGNUP_A}" | jq -r '.access_token // empty')"
USER_A="$(echo "${SIGNUP_A}" | jq -r '.user.id // empty')"
SIGNUP_B="$(signup "${EMAIL_B}")"
TOKEN_B="$(echo "${SIGNUP_B}" | jq -r '.access_token // empty')"
USER_B="$(echo "${SIGNUP_B}" | jq -r '.user.id // empty')"
if [[ -z "${TOKEN_A}" || -z "${TOKEN_B}" ]]; then
  echo "signup failed"
  echo "${SIGNUP_A}" | jq .
  echo "${SIGNUP_B}" | jq .
  exit 1
fi
echo "USER_A=${USER_A}"
echo "USER_B=${USER_B}"

log "TEST 1 — CRUD via Supabase client path (REST + anon key + user token)"
CREATE_RESP="$(curl -sS -X POST "${REST_URL}/routines" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d '[
    {
      "user_id":"'"${USER_A}"'",
      "title":"晨间拉伸",
      "note":"10分钟活动开身",
      "type":"health",
      "default_time":"07:30:00",
      "repeat_days":['"${TODAY_IDX}"'],
      "enabled":true
    }
  ]')"
ROUTINE_ID="$(echo "${CREATE_RESP}" | jq -r '.[0].id // empty')"
echo "${CREATE_RESP}" | jq .

READ_RESP="$(curl -sS "${REST_URL}/routines?id=eq.${ROUTINE_ID}&select=id,title,repeat_days,default_time,type,enabled" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}")"
echo "${READ_RESP}" | jq .

UPDATE_RESP="$(curl -sS -X PATCH "${REST_URL}/routines?id=eq.${ROUTINE_ID}" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d '{"enabled":false,"note":"改为休息日关闭"}')"
echo "${UPDATE_RESP}" | jq .

DELETE_RESP="$(curl -sS -X DELETE "${REST_URL}/routines?id=eq.${ROUTINE_ID}" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}" \
  -H "Prefer: return=representation")"
echo "${DELETE_RESP}" | jq .

log "TEST 2 — RLS isolation (user B cannot read/update user A routine)"
CREATE_RLS="$(curl -sS -X POST "${REST_URL}/routines" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d '[{"user_id":"'"${USER_A}"'","title":"专注写作","default_time":"09:00:00","repeat_days":['"${TODAY_IDX}"'],"enabled":true}]')"
RLS_ID="$(echo "${CREATE_RLS}" | jq -r '.[0].id')"
echo "RLS_ID=${RLS_ID}"

READ_OTHER="$(curl -sS "${REST_URL}/routines?id=eq.${RLS_ID}&select=id,title" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_B}")"
echo "user B read user A routine:"
echo "${READ_OTHER}" | jq .

UPDATE_OTHER="$(curl -sS -X PATCH "${REST_URL}/routines?id=eq.${RLS_ID}" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_B}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d '{"title":"非法修改"}')"
echo "user B update user A routine:"
echo "${UPDATE_OTHER}" | jq .

log "TEST 3 — plan-generate merges today's routine with source=routine"
PLAN_RESP_TODAY="$(curl -sS -X POST "${FN_URL}" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}" \
  -H "Content-Type: application/json" \
  -d '{
    "date":"'"${TODAY}"'",
    "raw_input":"今天要写周报和开产品同步会",
    "tone":"friendly"
  }')"
echo "${PLAN_RESP_TODAY}" | jq '{proposal_id, provider, tasks}'
echo "today routine hit check:"
echo "${PLAN_RESP_TODAY}" | jq '[.tasks[] | select(.source=="routine")]'

log "TEST 4 — next-day proof for source=routine"
CREATE_NEXT_DAY="$(curl -sS -X POST "${REST_URL}/routines" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d '[{"user_id":"'"${USER_A}"'","title":"次日固定复盘","default_time":"21:00:00","repeat_days":['"${TOMORROW_IDX}"'],"enabled":true}]')"
echo "${CREATE_NEXT_DAY}" | jq .

PLAN_RESP_TOMORROW="$(curl -sS -X POST "${FN_URL}" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}" \
  -H "Content-Type: application/json" \
  -d '{
    "date":"'"${TOMORROW}"'",
    "raw_input":"明天核心是把需求评审走完",
    "tone":"friendly"
  }')"
echo "${PLAN_RESP_TOMORROW}" | jq '{proposal_id, provider, tasks}'
echo "tomorrow routine hit check:"
echo "${PLAN_RESP_TOMORROW}" | jq '[.tasks[] | select(.source=="routine")]'

log "done"
echo "Capture this script output as acceptance evidence."
