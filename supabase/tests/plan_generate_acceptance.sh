#!/usr/bin/env bash
# acceptance tests for POST /plan/generate (task 03)
set -euo pipefail

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
FN_URL="${SUPABASE_URL}/functions/v1/plan-generate"
REST_URL="${SUPABASE_URL}/rest/v1"
AUTH_URL="${SUPABASE_URL}/auth/v1"

if [[ -z "${SUPABASE_ANON_KEY:-}" || -z "${SUPABASE_SERVICE_ROLE_KEY:-}" ]]; then
  echo "export SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY"
  exit 1
fi

TS="$(date +%s)"
EMAIL="plan_acc_${TS}@think-act.test"
PASS="PlanAcc_${TS}!"
TODAY="$(date -u +%F)"
YESTERDAY="$(date -u -v-1d +%F 2>/dev/null || date -u -d 'yesterday' +%F)"

log() { printf '\n========== %s ==========\n' "$*"; }

create_user() {
  curl -sS -X POST "${AUTH_URL}/admin/users" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASS}\",\"email_confirm\":true}"
}

sign_in() {
  curl -sS -X POST "${AUTH_URL}/token?grant_type=password" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASS}\"}"
}

plan_generate() {
  local token="$1"
  local extra_query="${2:-}"
  curl -sS -X POST "${FN_URL}${extra_query}" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -d '{
      "date": "'"${TODAY}"'",
      "raw_input": "今天上午要写产品文档,下午 3 点开会,晚上想跑步",
      "hard_constraints": [
        { "start": "15:00", "end": "16:00", "title": "产品评审会" }
      ],
      "tone": "friendly"
    }'
}

patch_profile() {
  local body="$1"
  curl -sS -X PATCH "${REST_URL}/profiles?id=eq.${USER_ID}" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -H "Prefer: return=representation" \
    -d "${body}"
}

log "setup user"
CREATE="$(create_user)"
USER_ID="$(echo "${CREATE}" | jq -r '.id')"
SIGNIN="$(sign_in)"
TOKEN="$(echo "${SIGNIN}" | jq -r '.access_token')"
echo "USER_ID=${USER_ID}"

log "TEST 1 — happy path"
echo "REQUEST:"
echo "POST ${FN_URL}"
echo 'body: { date, raw_input, hard_constraints, tone }'
RESP1="$(plan_generate "${TOKEN}")"
echo "RESPONSE:"
echo "${RESP1}" | jq '.'

log "TEST 2 — fair use at 19 then 20"
patch_profile '{"daily_ai_calls":19,"ai_calls_date":"'"${TODAY}"'"}' >/dev/null
RESP2A="$(plan_generate "${TOKEN}")"
echo "at daily_ai_calls=19:"
echo "${RESP2A}" | jq 'if .proposal_id then {ok:true, proposal_id} else . end'
patch_profile '{"daily_ai_calls":20,"ai_calls_date":"'"${TODAY}"'"}' >/dev/null
RESP2B="$(plan_generate "${TOKEN}")"
echo "at daily_ai_calls=20:"
echo "${RESP2B}" | jq '.'

log "TEST 3 — invalid json retry (debug_force_invalid_json=1)"
# reset counter for this user so test can run
patch_profile '{"daily_ai_calls":0,"ai_calls_date":"'"${TODAY}"'"}' >/dev/null
RESP3="$(plan_generate "${TOKEN}" "?debug_force_invalid_json=1")"
echo "RESPONSE:"
echo "${RESP3}" | jq '.'

log "TEST 4 — memory_enabled=false skips memories"
patch_profile '{"memory_enabled":false,"daily_ai_calls":0,"ai_calls_date":"'"${TODAY}"'"}' >/dev/null
echo "(check function logs for: memories section skipped memory_enabled=false)"
RESP4="$(plan_generate "${TOKEN}")"
echo "RESPONSE (truncated):"
echo "${RESP4}" | jq '{proposal_id, task_count:(.tasks|length), ai_comment}'

log "TEST 5 — cross-day reset"
patch_profile '{"daily_ai_calls":15,"ai_calls_date":"'"${YESTERDAY}"'"}' >/dev/null
RESP5="$(plan_generate "${TOKEN}")"
PROFILE_AFTER="$(curl -sS "${REST_URL}/profiles?id=eq.${USER_ID}&select=daily_ai_calls,ai_calls_date" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN}")"
echo "RESPONSE (truncated):"
echo "${RESP5}" | jq '{proposal_id, ai_comment}'
echo "PROFILE AFTER:"
echo "${PROFILE_AFTER}" | jq '.'

log "cleanup"
curl -sS -X DELETE "${AUTH_URL}/admin/users/${USER_ID}" \
  -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
  -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" >/dev/null
echo "deleted test user"
