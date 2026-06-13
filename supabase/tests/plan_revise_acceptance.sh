#!/usr/bin/env bash
# acceptance tests for plan-revise + plan-revise-apply (v1.1)
set -euo pipefail

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
REVISE_URL="${SUPABASE_URL}/functions/v1/plan-revise"
APPLY_URL="${SUPABASE_URL}/functions/v1/plan-revise-apply"
REST_URL="${SUPABASE_URL}/rest/v1"
AUTH_URL="${SUPABASE_URL}/auth/v1"
EVIDENCE_DIR="${EVIDENCE_DIR:-$(dirname "$0")/../../docs/test-evidence}"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="${EVIDENCE_DIR}/plan-revise-${TS}.log"
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
    -d "{\"email\":\"plan_revise_${TS}@think-act.test\",\"password\":\"Revise_${TS}!\",\"email_confirm\":true}"
}

sign_in() {
  curl -sS -X POST "${AUTH_URL}/token?grant_type=password" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"plan_revise_${TS}@think-act.test\",\"password\":\"Revise_${TS}!\"}"
}

insert_tasks() {
  curl -sS -X POST "${REST_URL}/tasks" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -H "Prefer: return=representation" \
    -d "$1"
}

curl_post() {
  local url="$1" token="$2" body="$3"
  curl -sS -w "\nHTTP_STATUS:%{http_code}" -X POST "${url}" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -d "${body}"
}

log "setup"
CREATE="$(create_user)"
USER_ID="$(echo "${CREATE}" | jq -r '.id')"
TOKEN="$(sign_in | jq -r '.access_token')"
echo "USER_ID=${USER_ID}" | tee -a "${LOG_FILE}"

TASKS_JSON="$(insert_tasks "[
  {\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"写报告\",\"planned_start\":\"${TODAY}T09:00:00+08:00\",\"planned_duration\":60,\"important\":true,\"status\":\"done\",\"source\":\"user_voice\"},
  {\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"跑步\",\"planned_start\":\"${TODAY}T18:00:00+08:00\",\"planned_duration\":30,\"important\":false,\"status\":\"planned\",\"source\":\"user_voice\"},
  {\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"刷邮件\",\"planned_start\":\"${TODAY}T16:00:00+08:00\",\"planned_duration\":20,\"important\":false,\"status\":\"planned\",\"source\":\"user_voice\"}
]")"
RUN_ID="$(echo "${TASKS_JSON}" | jq -r '.[1].id')"
MAIL_ID="$(echo "${TASKS_JSON}" | jq -r '.[2].id')"
echo "RUN_ID=${RUN_ID} MAIL_ID=${MAIL_ID}" | tee -a "${LOG_FILE}"

log "TEST 1 — propose 无 JWT → 401"
RAW1="$(curl_post "${REVISE_URL}" "" "{\"date\":\"${TODAY}\",\"timezone\":\"Asia/Shanghai\",\"now\":\"${TODAY}T14:00:00+08:00\",\"instruction\":\"测试\",\"tasks\":[]}")"
HTTP1="$(echo "${RAW1}" | sed -n 's/.*HTTP_STATUS://p')"
[[ "${HTTP1}" == "401" ]]
echo "HTTP=${HTTP1}" | tee -a "${LOG_FILE}"

log "TEST 2 — propose 推跑步、刷邮件不做了"
BODY2="$(jq -n \
  --arg date "${TODAY}" \
  --arg run_id "${RUN_ID}" \
  --arg mail_id "${MAIL_ID}" \
  '{
    date: $date,
    timezone: "Asia/Shanghai",
    now: ($date + "T14:30:00+08:00"),
    instruction: "把跑步推一小时，刷邮件不做了",
    tasks: [
      {id: "done-ctx", title: "写报告", status: "done", planned_start: ($date + "T09:00:00+08:00"), planned_duration: 60, important: true, actual_start: null},
      {id: $run_id, title: "跑步", status: "planned", planned_start: ($date + "T18:00:00+08:00"), planned_duration: 30, important: false, actual_start: null},
      {id: $mail_id, title: "刷邮件", status: "planned", planned_start: ($date + "T16:00:00+08:00"), planned_duration: 20, important: false, actual_start: null}
    ]
  }')"
RAW2="$(curl_post "${REVISE_URL}" "${TOKEN}" "${BODY2}")"
HTTP2="$(echo "${RAW2}" | sed -n 's/.*HTTP_STATUS://p')"
RESP2="$(echo "${RAW2}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP2}" | tee -a "${LOG_FILE}"
echo "${RESP2}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP2}" == "200" ]]
REVISION_ID="$(echo "${RESP2}" | jq -r '.revision_id')"
echo "${RESP2}" | jq -e --arg run "${RUN_ID}" --arg mail "${MAIL_ID}" '
  (.applicable == true) and (.reject_reason == null) and
  (.revisions | map(select(.task_id == $run))[0].change) as $rc |
  (.revisions | map(select(.task_id == $mail))[0].change) as $mc |
  ($rc == "moved" or $rc == "unchanged") and ($mc == "dropped") and
  ([.revisions[].change] | all(. == "moved" or . == "dropped" or . == "unchanged")) and
  ([.. | strings] | any(test("sk-|secret"; "i")) | not)
' >/dev/null

log "TEST 3 — apply moved+dropped"
APPLY_BODY="$(echo "${RESP2}" | jq -c --arg date "${TODAY}" --arg rid "${REVISION_ID}" '{
  date: $date,
  revision_id: $rid,
  revisions: [.revisions[] | select(.change == "moved" or .change == "dropped") | {
    task_id: .task_id,
    change: .change,
    after: (if .change == "dropped" then {status:"dropped"} else {planned_start:.after.planned_start, planned_duration:.after.planned_duration, status:"planned"} end)
  }],
  added: [.added[]? | {client_key: .client_key, title: .title, planned_start: .planned_start, planned_duration: .planned_duration, important: .important}]
}')"
RAW3="$(curl_post "${APPLY_URL}" "${TOKEN}" "${APPLY_BODY}")"
HTTP3="$(echo "${RAW3}" | sed -n 's/.*HTTP_STATUS://p')"
RESP3="$(echo "${RAW3}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP3}" | tee -a "${LOG_FILE}"
echo "${RESP3}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP3}" == "200" ]]
echo "${RESP3}" | jq -e '.ok == true and (.applied_count >= 1)' >/dev/null

log "TEST 4 — 重复 apply → APPLY_CONFLICT 409"
RAW4="$(curl_post "${APPLY_URL}" "${TOKEN}" "${APPLY_BODY}")"
HTTP4="$(echo "${RAW4}" | sed -n 's/.*HTTP_STATUS://p')"
BODY4="$(echo "${RAW4}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP4} body=${BODY4}" | tee -a "${LOG_FILE}"
[[ "${HTTP4}" == "409" ]]

log "TEST 5 — 变当前任务(actual_start 非空)仍可 moved"
STARTED_JSON="$(insert_tasks "[{\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"进行中\",\"planned_start\":\"${TODAY}T14:00:00+08:00\",\"planned_duration\":45,\"status\":\"planned\",\"actual_start\":\"${TODAY}T14:05:00+08:00\",\"source\":\"user_voice\"}]")"
STARTED_ID="$(echo "${STARTED_JSON}" | jq -r '.[0].id')"
BODY5="$(jq -n --arg date "${TODAY}" --arg id "${STARTED_ID}" '{
  date: $date, timezone: "Asia/Shanghai", now: ($date + "T14:30:00+08:00"),
  instruction: "把进行中推到下午四点",
  tasks: [{id: $id, title: "进行中", status: "planned", planned_start: ($date + "T14:00:00+08:00"), planned_duration: 45, important: false, actual_start: ($date + "T14:05:00+08:00")}]
}')"
RAW5="$(curl_post "${REVISE_URL}" "${TOKEN}" "${BODY5}")"
HTTP5="$(echo "${RAW5}" | sed -n 's/.*HTTP_STATUS://p')"
RESP5="$(echo "${RAW5}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP5}" | tee -a "${LOG_FILE}"
echo "${RESP5}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP5}" == "200" ]]
echo "${RESP5}" | jq -e --arg id "${STARTED_ID}" '
  (.revisions[] | select(.task_id==$id) | .change) == "moved" and
  (.revisions[] | select(.task_id==$id) | .after.actual_start) == null
' >/dev/null

log "TEST 6 — 新增计划外任务 added（无 planned 也可）"
ADD_BODY="$(jq -n --arg date "${TODAY}" --arg run_id "${RUN_ID}" '{
  date: $date, timezone: "Asia/Shanghai", now: ($date + "T14:30:00+08:00"),
  instruction: "下午五点加一个接孩子",
  tasks: [
    {id: $run_id, title: "跑步", status: "done", planned_start: ($date + "T18:00:00+08:00"), planned_duration: 30, important: false, actual_start: null}
  ]
}')"
RAW6="$(curl_post "${REVISE_URL}" "${TOKEN}" "${ADD_BODY}")"
HTTP6="$(echo "${RAW6}" | sed -n 's/.*HTTP_STATUS://p')"
RESP6="$(echo "${RAW6}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP6}" | tee -a "${LOG_FILE}"
[[ "${HTTP6}" == "200" ]]
echo "${RESP6}" | jq -e '(.added | length) >= 1' >/dev/null
REV6="$(echo "${RESP6}" | jq -r '.revision_id')"
ADD_APPLY6="$(echo "${RESP6}" | jq -c --arg date "${TODAY}" --arg rid "${REV6}" '{
  date: $date, revision_id: $rid, revisions: [],
  added: [.added[] | {client_key: .client_key, title: .title, planned_start: .planned_start, planned_duration: .planned_duration, important: .important}]
}')"
RAW6A="$(curl_post "${APPLY_URL}" "${TOKEN}" "${ADD_APPLY6}")"
HTTP6A="$(echo "${RAW6A}" | sed -n 's/.*HTTP_STATUS://p')"
RESP6A="$(echo "${RAW6A}" | sed '/HTTP_STATUS:/d')"
[[ "${HTTP6A}" == "200" ]]
echo "${RESP6A}" | jq -e '[.tasks[] | select(.source=="revise_voice")] | length >= 1' >/dev/null

log "TEST 7 — v1.3 reject (nonsense instruction → applicable=false + reject_reason)"
BODY7="$(jq -n \
  --arg date "${TODAY}" \
  --arg run_id "${RUN_ID}" \
  --arg mail_id "${MAIL_ID}" \
  '{
    date: $date,
    timezone: "Asia/Shanghai",
    now: ($date + "T14:30:00+08:00"),
    instruction: "blorp zzz 随便乱说完全听不懂",
    tasks: [
      {id: $run_id, title: "跑步", status: "planned", planned_start: ($date + "T18:00:00+08:00"), planned_duration: 30, important: false, actual_start: null},
      {id: $mail_id, title: "刷邮件", status: "planned", planned_start: ($date + "T16:00:00+08:00"), planned_duration: 20, important: false, actual_start: null}
    ]
  }')"
RAW7="$(curl_post "${REVISE_URL}" "${TOKEN}" "${BODY7}")"
HTTP7="$(echo "${RAW7}" | sed -n 's/.*HTTP_STATUS://p')"
RESP7="$(echo "${RAW7}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP7}" | tee -a "${LOG_FILE}"
echo "${RESP7}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP7}" == "200" ]]
echo "${RESP7}" | jq -e '
  (.applicable == false) and
  (.reject_reason != null) and
  (.reject_reason | type == "string") and
  (.reject_reason | length > 0) and
  (.warnings | length >= 1) and
  ([.. | strings] | any(test("sk-|secret"; "i")) | not)
' >/dev/null

log "PASS — evidence: ${LOG_FILE}"
