#!/usr/bin/env bash
# verify anonymous sign-in + plan-generate for new users
set -euo pipefail

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
FN_URL="${SUPABASE_URL}/functions/v1/plan-generate"
REST_URL="${SUPABASE_URL}/rest/v1"
AUTH_URL="${SUPABASE_URL}/auth/v1"

if [[ -z "${SUPABASE_ANON_KEY:-}" ]]; then
  echo "export SUPABASE_ANON_KEY"
  exit 1
fi

TODAY="$(date -u +%F)"

log() { printf '\n==> %s\n' "$*"; }

log "1) anonymous sign-in"
SIGNUP=$(curl -sS -X POST "${AUTH_URL}/signup" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${SUPABASE_ANON_KEY}" \
  -H "Content-Type: application/json" \
  -d '{}')
echo "${SIGNUP}" | jq '{user_id: .user.id, is_anonymous: .user.is_anonymous, has_token: (.access_token != null)}'

TOKEN=$(echo "${SIGNUP}" | jq -r '.access_token // empty')
USER_ID=$(echo "${SIGNUP}" | jq -r '.user.id // empty')
if [[ -z "${TOKEN}" || -z "${USER_ID}" ]]; then
  echo "FAIL: anonymous sign-in did not return token"
  echo "${SIGNUP}" | jq .
  exit 1
fi

log "2) check profiles row"
PROFILE=$(curl -sS "${REST_URL}/profiles?id=eq.${USER_ID}&select=id,is_anonymous,preview_started_at,timezone,memory_enabled,subscription_status,daily_ai_calls" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN}")
echo "${PROFILE}" | jq '.'

log "3) call plan-generate"
BODY=$(cat <<EOF
{
  "date": "${TODAY}",
  "raw_input": "今天上午要写产品文档,下午 3 点开会,晚上想跑步",
  "hard_constraints": [{ "start": "15:00", "end": "16:00", "title": "产品评审会" }],
  "tone": "friendly"
}
EOF
)
PLAN=$(curl -sS -X POST "${FN_URL}" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${BODY}")
echo "${PLAN}" | jq 'if .proposal_id then {proposal_id, task_count: (.tasks|length), suggestion_count: (.suggestion_tasks|length), ai_comment, first_task: .tasks[0].title} else . end'

if echo "${PLAN}" | jq -e '.proposal_id' >/dev/null 2>&1; then
  echo ""
  echo "RESULT: PASS — anonymous user can call plan-generate"
  exit 0
fi

echo ""
echo "RESULT: FAIL"
exit 1
