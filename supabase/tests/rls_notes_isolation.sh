#!/usr/bin/env bash
# rls isolation test for notes table (0003_notes.sql)
# user a inserts a note; user b must not select/update/delete it.
set -euo pipefail

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
REST_URL="${SUPABASE_URL}/rest/v1"
AUTH_URL="${SUPABASE_URL}/auth/v1"

if [[ -z "${SUPABASE_ANON_KEY:-}" || -z "${SUPABASE_SERVICE_ROLE_KEY:-}" ]]; then
  echo "set SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY before running."
  exit 1
fi

TS="$(date +%s)"
EMAIL_A="notes_a_${TS}@think-act.test"
EMAIL_B="notes_b_${TS}@think-act.test"
PASS="NotesRls_${TS}!"

log() { printf '\n==> %s\n' "$*"; }

create_user() {
  local email="$1"
  curl -sS -X POST "${AUTH_URL}/admin/users" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${PASS}\",\"email_confirm\":true}"
}

sign_in() {
  local email="$1"
  curl -sS -X POST "${AUTH_URL}/token?grant_type=password" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${PASS}\"}"
}

log "config"
echo "SUPABASE_URL=${SUPABASE_URL}"
echo "EMAIL_A=${EMAIL_A}"
echo "EMAIL_B=${EMAIL_B}"

log "create auth user a"
CREATE_A="$(create_user "${EMAIL_A}")"
echo "${CREATE_A}" | jq '{id, email}'

log "create auth user b"
CREATE_B="$(create_user "${EMAIL_B}")"
echo "${CREATE_B}" | jq '{id, email}'

log "sign in user a"
SIGNIN_A="$(sign_in "${EMAIL_A}")"
TOKEN_A="$(echo "${SIGNIN_A}" | jq -r '.access_token')"
UID_A="$(echo "${SIGNIN_A}" | jq -r '.user.id')"
echo "UID_A=${UID_A}"

log "sign in user b"
SIGNIN_B="$(sign_in "${EMAIL_B}")"
TOKEN_B="$(echo "${SIGNIN_B}" | jq -r '.access_token')"
UID_B="$(echo "${SIGNIN_B}" | jq -r '.user.id')"
echo "UID_B=${UID_B}"

log "user a inserts note"
INSERT_A="$(curl -sS -X POST "${REST_URL}/notes" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d "{\"user_id\":\"${UID_A}\",\"text\":\"rls test note from A\",\"source\":\"user_text\"}")"
echo "${INSERT_A}" | jq '.'
NOTE_ID="$(echo "${INSERT_A}" | jq -r '.[0].id')"
echo "NOTE_ID=${NOTE_ID}"

log "user a selects own note (expect 1 row)"
SELECT_A="$(curl -sS "${REST_URL}/notes?id=eq.${NOTE_ID}&select=id,user_id,text" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}")"
echo "${SELECT_A}" | jq '.'

log "user b selects a's note (expect [])"
SELECT_B="$(curl -sS "${REST_URL}/notes?id=eq.${NOTE_ID}&select=id,user_id,text" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_B}")"
echo "${SELECT_B}" | jq '.'

log "user b updates a's note (expect [] / 0 rows)"
UPDATE_B="$(curl -sS -X PATCH "${REST_URL}/notes?id=eq.${NOTE_ID}" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_B}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d '{"text":"hacked by B"}')"
echo "${UPDATE_B}" | jq '.'

log "user b deletes a's note (expect [] / 0 rows)"
DELETE_B="$(curl -sS -X DELETE "${REST_URL}/notes?id=eq.${NOTE_ID}" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_B}" \
  -H "Prefer: return=representation")"
echo "${DELETE_B}" | jq '.'

log "user a confirms note still exists"
SELECT_A_AFTER="$(curl -sS "${REST_URL}/notes?id=eq.${NOTE_ID}&select=id,text" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${TOKEN_A}")"
echo "${SELECT_A_AFTER}" | jq '.'

log "cleanup test users (service role)"
curl -sS -X DELETE "${AUTH_URL}/admin/users/$(echo "${CREATE_A}" | jq -r '.id')" \
  -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
  -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" >/dev/null
curl -sS -X DELETE "${AUTH_URL}/admin/users/$(echo "${CREATE_B}" | jq -r '.id')" \
  -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
  -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" >/dev/null
echo "deleted test users"

log "assertions"
SELECT_B_COUNT="$(echo "${SELECT_B}" | jq 'length')"
UPDATE_B_COUNT="$(echo "${UPDATE_B}" | jq 'length')"
DELETE_B_COUNT="$(echo "${DELETE_B}" | jq 'length')"
SELECT_A_COUNT="$(echo "${SELECT_A}" | jq 'length')"
SELECT_A_AFTER_COUNT="$(echo "${SELECT_A_AFTER}" | jq 'length')"

PASS=true
[[ "${SELECT_B_COUNT}" == "0" ]] || PASS=false
[[ "${UPDATE_B_COUNT}" == "0" ]] || PASS=false
[[ "${DELETE_B_COUNT}" == "0" ]] || PASS=false
[[ "${SELECT_A_COUNT}" == "1" ]] || PASS=false
[[ "${SELECT_A_AFTER_COUNT}" == "1" ]] || PASS=false

echo ""
echo "summary:"
echo "  B select rows: ${SELECT_B_COUNT} (expected 0)"
echo "  B update rows: ${UPDATE_B_COUNT} (expected 0)"
echo "  B delete rows: ${DELETE_B_COUNT} (expected 0)"
echo "  A select rows: ${SELECT_A_COUNT} (expected 1)"
echo "  A still has note after B delete: ${SELECT_A_AFTER_COUNT} (expected 1)"

if [[ "${PASS}" == "true" ]]; then
  echo "RESULT: PASS — notes rls isolation works"
  exit 0
else
  echo "RESULT: FAIL — notes rls isolation broken"
  exit 1
fi
