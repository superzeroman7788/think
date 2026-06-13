#!/usr/bin/env bash
# acceptance tests for evening review leg (v1.0)
set -euo pipefail

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
PARSE_URL="${SUPABASE_URL}/functions/v1/review-parse-voice"
APPLY_URL="${SUPABASE_URL}/functions/v1/review-apply"
SUMMARY_URL="${SUPABASE_URL}/functions/v1/day-summary"
PROBE_URL="${SUPABASE_URL}/functions/v1/reflection-probe"
MEMORY_URL="${SUPABASE_URL}/functions/v1/memory-add"
REST_URL="${SUPABASE_URL}/rest/v1"
AUTH_URL="${SUPABASE_URL}/auth/v1"
EVIDENCE_DIR="${EVIDENCE_DIR:-$(dirname "$0")/../../docs/test-evidence}"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="${EVIDENCE_DIR}/evening-review-${TS}.log"
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
    -d "{\"email\":\"evening_review_${TS}@think-act.test\",\"password\":\"Review_${TS}!\",\"email_confirm\":true}"
}

sign_in() {
  curl -sS -X POST "${AUTH_URL}/token?grant_type=password" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"evening_review_${TS}@think-act.test\",\"password\":\"Review_${TS}!\"}"
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

curl_patch() {
  local url="$1" token="$2" body="$3"
  curl -sS -w "\nHTTP_STATUS:%{http_code}" -X PATCH "${url}" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -H "Prefer: return=representation" \
    -d "${body}"
}

curl_get() {
  local url="$1" token="$2"
  curl -sS -w "\nHTTP_STATUS:%{http_code}" -X GET "${url}" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${token}"
}

log "setup"
CREATE="$(create_user)"
USER_ID="$(echo "${CREATE}" | jq -r '.id')"
TOKEN="$(sign_in | jq -r '.access_token')"
echo "USER_ID=${USER_ID}" | tee -a "${LOG_FILE}"

TASKS_JSON="$(insert_tasks "[
  {\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"健身\",\"planned_start\":\"${TODAY}T18:00:00+08:00\",\"planned_duration\":45,\"important\":false,\"status\":\"planned\",\"actual_start\":null,\"actual_end\":null,\"source\":\"user_voice\"},
  {\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"回邮件\",\"planned_start\":\"${TODAY}T16:00:00+08:00\",\"planned_duration\":20,\"important\":false,\"status\":\"planned\",\"actual_start\":null,\"actual_end\":null,\"source\":\"user_voice\"},
  {\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"写报告\",\"planned_start\":\"${TODAY}T09:00:00+08:00\",\"planned_duration\":60,\"important\":true,\"status\":\"done\",\"actual_start\":\"${TODAY}T09:05:00+08:00\",\"actual_end\":\"${TODAY}T10:00:00+08:00\",\"source\":\"user_voice\"}
]")"
GYM_ID="$(echo "${TASKS_JSON}" | jq -r '.[0].id')"
MAIL_ID="$(echo "${TASKS_JSON}" | jq -r '.[1].id')"
REPORT_ID="$(echo "${TASKS_JSON}" | jq -r '.[2].id')"
echo "GYM_ID=${GYM_ID} MAIL_ID=${MAIL_ID} REPORT_ID=${REPORT_ID}" | tee -a "${LOG_FILE}"

task_row() {
  jq -n --arg id "$1" --arg title "$2" --arg status "$3" --arg ps "$4" --argjson dur "$5" --argjson imp "$6" \
    '{id:$id,title:$title,status:$status,planned_start:$ps,planned_duration:$dur,important:$imp,actual_start:null,actual_end:null}'
}

log "TEST 1 — review-parse-voice 无 JWT → 401"
RAW1="$(curl_post "${PARSE_URL}" "" "{\"date\":\"${TODAY}\",\"timezone\":\"Asia/Shanghai\",\"transcript\":\"健身做了\",\"tasks\":[]}")"
HTTP1="$(echo "${RAW1}" | sed -n 's/.*HTTP_STATUS://p')"
[[ "${HTTP1}" == "401" ]]
echo "HTTP=${HTTP1}" | tee -a "${LOG_FILE}"

log "TEST 2 — review-parse-voice 健身做了邮件没做"
PARSE_BODY="$(jq -n \
  --arg date "${TODAY}" \
  --arg gym "${GYM_ID}" \
  --arg mail "${MAIL_ID}" \
  --arg report "${REPORT_ID}" \
  '{
    date: $date,
    timezone: "Asia/Shanghai",
    transcript: "健身做了，邮件没做",
    tasks: [
      {id: $gym, title: "健身", status: "planned", planned_start: ($date + "T18:00:00+08:00"), planned_duration: 45, important: false, actual_start: null},
      {id: $mail, title: "回邮件", status: "planned", planned_start: ($date + "T16:00:00+08:00"), planned_duration: 20, important: false, actual_start: null},
      {id: $report, title: "写报告", status: "done", planned_start: ($date + "T09:00:00+08:00"), planned_duration: 60, important: true, actual_start: ($date + "T09:05:00+08:00"), actual_end: ($date + "T10:00:00+08:00")}
    ]
  }')"
RAW2="$(curl_post "${PARSE_URL}" "${TOKEN}" "${PARSE_BODY}")"
HTTP2="$(echo "${RAW2}" | sed -n 's/.*HTTP_STATUS://p')"
RESP2="$(echo "${RAW2}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP2}" | tee -a "${LOG_FILE}"
echo "${RESP2}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP2}" == "200" ]]
REVIEW_ID="$(echo "${RESP2}" | jq -r '.review_id')"
echo "${RESP2}" | jq -e --arg gym "${GYM_ID}" --arg mail "${MAIL_ID}" '
  (.proposed | map(select(.task_id == $gym))[0].to_status) as $g |
  (.proposed | map(select(.task_id == $mail))[0].to_status) as $m |
  ($g == "done") and ($m == "skipped") and (.review_id | length > 0)
' >/dev/null

log "TEST 3 — review-apply 原子落库"
APPLY_BODY="$(echo "${RESP2}" | jq -c --arg date "${TODAY}" --arg rid "${REVIEW_ID}" '{
  date: $date,
  review_id: $rid,
  proposed: [.proposed[] | {task_id: .task_id, to_status: .to_status}],
  added: [.added[]? | {client_key: .client_key, title: .title, planned_start: .planned_start, planned_duration: .planned_duration}]
}')"
RAW3="$(curl_post "${APPLY_URL}" "${TOKEN}" "${APPLY_BODY}")"
HTTP3="$(echo "${RAW3}" | sed -n 's/.*HTTP_STATUS://p')"
RESP3="$(echo "${RAW3}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP3}" | tee -a "${LOG_FILE}"
echo "${RESP3}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP3}" == "200" ]]
echo "${RESP3}" | jq -e --arg gym "${GYM_ID}" --arg mail "${MAIL_ID}" '
  (.tasks | map(select(.id == $gym))[0].status) == "done" and
  (.tasks | map(select(.id == $mail))[0].status) == "skipped" and
  (.tasks | map(select(.id == $gym))[0].actual_start == null) and
  (.tasks | map(select(.id == $gym))[0].actual_end != null)
' >/dev/null

log "TEST 4 — 重复 apply → 非 200"
RAW4="$(curl_post "${APPLY_URL}" "${TOKEN}" "${APPLY_BODY}")"
HTTP4="$(echo "${RAW4}" | sed -n 's/.*HTTP_STATUS://p')"
echo "HTTP=${HTTP4}" | tee -a "${LOG_FILE}"
[[ "${HTTP4}" != "200" ]]

log "TEST 5 — REVIEW_STALE 409（基线变后 apply）"
RAW5P="$(curl_post "${PARSE_URL}" "${TOKEN}" "${PARSE_BODY}")"
RESP5P="$(echo "${RAW5P}" | sed '/HTTP_STATUS:/d')"
REVIEW_ID2="$(echo "${RESP5P}" | jq -r '.review_id')"
# 篡改基线：点选把邮件标 done
PATCH_URL="${REST_URL}/tasks?id=eq.${MAIL_ID}"
RAW_PATCH="$(curl_patch "${PATCH_URL}" "${TOKEN}" "{\"status\":\"done\",\"actual_end\":\"${TODAY}T20:00:00+08:00\"}")"
HTTP_PATCH="$(echo "${RAW_PATCH}" | sed -n 's/.*HTTP_STATUS://p')"
[[ "${HTTP_PATCH}" == "200" ]]
APPLY_STALE="$(echo "${RESP5P}" | jq -c --arg date "${TODAY}" --arg rid "${REVIEW_ID2}" '{
  date: $date, review_id: $rid,
  proposed: [.proposed[] | {task_id: .task_id, to_status: .to_status}],
  added: [.added[]? | {client_key: .client_key, title: .title, planned_start: .planned_start, planned_duration: .planned_duration}]
}')"
RAW5="$(curl_post "${APPLY_URL}" "${TOKEN}" "${APPLY_STALE}")"
HTTP5="$(echo "${RAW5}" | sed -n 's/.*HTTP_STATUS://p')"
BODY5="$(echo "${RAW5}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP5} body=${BODY5}" | tee -a "${LOG_FILE}"
[[ "${HTTP5}" == "409" ]]
echo "${BODY5}" | jq -e '.error.code == "REVIEW_STALE" and .error.retryable == true' >/dev/null

log "TEST 6 — day-summary v2 持久化 ai_summary + ai_praise + ai_advice"
# 重插邮件为 skipped 以便 summary 统计一致
curl -sS -X PATCH "${PATCH_URL}" \
  -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
  -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"status\":\"skipped\"}" >/dev/null

SUMMARY_BODY="$(echo "${RESP3}" | jq -c --arg date "${TODAY}" '{date: $date, tasks: .tasks}')"
RAW6="$(curl_post "${SUMMARY_URL}" "${TOKEN}" "${SUMMARY_BODY}")"
HTTP6="$(echo "${RAW6}" | sed -n 's/.*HTTP_STATUS://p')"
RESP6="$(echo "${RAW6}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP6}" | tee -a "${LOG_FILE}"
echo "${RESP6}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP6}" == "200" ]]
echo "${RESP6}" | jq -e '.summary | length > 0 and .praise | length > 0' >/dev/null
echo "${RESP6}" | jq -e '.praise | test("没搞定|无深度|都没搞定") | not' >/dev/null
REFL_URL="${REST_URL}/daily_reflections?user_id=eq.${USER_ID}&date=eq.${TODAY}&select=ai_summary,ai_praise,ai_advice"
RAW_REFL="$(curl_get "${REFL_URL}" "${TOKEN}")"
REFL_BODY="$(echo "${RAW_REFL}" | sed '/HTTP_STATUS:/d')"
echo "${REFL_BODY}" | jq -e '.[0].ai_praise | length > 0' | tee -a "${LOG_FILE}"

log "TEST 7 — reflection-probe 持久化 ai_probe"
PROBE_BODY="$(jq -n --arg date "${TODAY}" --argjson tasks "$(echo "${RESP3}" | jq '.tasks')" '{
  date: $date, today_tasks: $tasks, recent_tasks: []
}')"
RAW7="$(curl_post "${PROBE_URL}" "${TOKEN}" "${PROBE_BODY}")"
HTTP7="$(echo "${RAW7}" | sed -n 's/.*HTTP_STATUS://p')"
RESP7="$(echo "${RAW7}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP7}" | tee -a "${LOG_FILE}"
echo "${RESP7}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP7}" == "200" ]]
echo "${RESP7}" | jq -e '.probe | length > 0' >/dev/null
PROBE_REFL_URL="${REST_URL}/daily_reflections?user_id=eq.${USER_ID}&date=eq.${TODAY}&select=ai_probe"
RAW_PROBE_REFL="$(curl_get "${PROBE_REFL_URL}" "${TOKEN}")"
PROBE_REFL="$(echo "${RAW_PROBE_REFL}" | sed '/HTTP_STATUS:/d')"
echo "${PROBE_REFL}" | jq -e '.[0].ai_probe | length > 0' | tee -a "${LOG_FILE}"

log "TEST 8 — memory-add confirmed_by_user=false → 400"
RAW8="$(curl_post "${MEMORY_URL}" "${TOKEN}" "$(jq -n --arg date "${TODAY}" '{
  date: $date, text: "测试记忆", user_response: "原话", source: "reflection", confirmed_by_user: false
}')")"
HTTP8="$(echo "${RAW8}" | sed -n 's/.*HTTP_STATUS://p')"
BODY8="$(echo "${RAW8}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP8} body=${BODY8}" | tee -a "${LOG_FILE}"
[[ "${HTTP8}" == "400" ]]

log "TEST 9 — memory-add 确认落库 memories + daily_reflections"
RAW9="$(curl_post "${MEMORY_URL}" "${TOKEN}" "$(jq -n --arg date "${TODAY}" '{
  date: $date,
  text: "下午深度任务容易被会议挤掉",
  user_response: "对，下午一有会我就往后拖深度活",
  source: "reflection",
  confirmed_by_user: true
}')")"
HTTP9="$(echo "${RAW9}" | sed -n 's/.*HTTP_STATUS://p')"
RESP9="$(echo "${RAW9}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP9}" | tee -a "${LOG_FILE}"
echo "${RESP9}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP9}" == "200" ]]
MEM_ID="$(echo "${RESP9}" | jq -r '.id')"
echo "${RESP9}" | jq -e --arg mid "${MEM_ID}" '
  .confirmed_by_user == true and
  .reflection.promoted_memory == $mid and
  (.reflection.user_response | length > 0)
' >/dev/null
FINAL_REFL_URL="${REST_URL}/daily_reflections?user_id=eq.${USER_ID}&date=eq.${TODAY}&select=user_response,promoted_memory,completed_at"
RAW_FINAL="$(curl_get "${FINAL_REFL_URL}" "${TOKEN}")"
FINAL_REFL="$(echo "${RAW_FINAL}" | sed '/HTTP_STATUS:/d')"
echo "${FINAL_REFL}" | jq -e --arg mid "${MEM_ID}" '
  .[0].promoted_memory == $mid and
  (.[0].user_response | length > 0) and
  (.[0].completed_at != null)
' | tee -a "${LOG_FILE}"

log "TEST 10 — 计划外补记 added（全天已 done/planned 空）"
ADD_BODY="$(jq -n \
  --arg date "${TODAY}" \
  --arg gym "${GYM_ID}" \
  --arg mail "${MAIL_ID}" \
  --arg report "${REPORT_ID}" \
  '{
    date: $date,
    timezone: "Asia/Shanghai",
    transcript: "另外还参加了同学生日聚会",
    tasks: [
      {id: $gym, title: "健身", status: "done", planned_start: ($date + "T18:00:00+08:00"), planned_duration: 45, important: false, actual_start: null, actual_end: ($date + "T19:00:00+08:00")},
      {id: $mail, title: "回邮件", status: "skipped", planned_start: ($date + "T16:00:00+08:00"), planned_duration: 20, important: false, actual_start: null},
      {id: $report, title: "写报告", status: "done", planned_start: ($date + "T09:00:00+08:00"), planned_duration: 60, important: true, actual_start: ($date + "T09:05:00+08:00"), actual_end: ($date + "T10:00:00+08:00")}
    ]
  }')"
RAW10="$(curl_post "${PARSE_URL}" "${TOKEN}" "${ADD_BODY}")"
HTTP10="$(echo "${RAW10}" | sed -n 's/.*HTTP_STATUS://p')"
RESP10="$(echo "${RAW10}" | sed '/HTTP_STATUS:/d')"
echo "HTTP=${HTTP10}" | tee -a "${LOG_FILE}"
echo "${RESP10}" | jq '.' | tee -a "${LOG_FILE}"
[[ "${HTTP10}" == "200" ]]
echo "${RESP10}" | jq -e '(.added | length) >= 1 and (.unclear == false)' >/dev/null
REVIEW_ADD_ID="$(echo "${RESP10}" | jq -r '.review_id')"
ADD_APPLY="$(echo "${RESP10}" | jq -c --arg date "${TODAY}" --arg rid "${REVIEW_ADD_ID}" '{
  date: $date, review_id: $rid,
  proposed: [.proposed[]? | {task_id: .task_id, to_status: .to_status}],
  added: [.added[] | {client_key: .client_key, title: .title, planned_start: .planned_start, planned_duration: .planned_duration}]
}')"
RAW10A="$(curl_post "${APPLY_URL}" "${TOKEN}" "${ADD_APPLY}")"
HTTP10A="$(echo "${RAW10A}" | sed -n 's/.*HTTP_STATUS://p')"
RESP10A="$(echo "${RAW10A}" | sed '/HTTP_STATUS:/d')"
echo "HTTP apply=${HTTP10A}" | tee -a "${LOG_FILE}"
[[ "${HTTP10A}" == "200" ]]
echo "${RESP10A}" | jq -e '[.tasks[] | select(.source=="review_voice")] | length >= 1' >/dev/null

log "PASS — evidence: ${LOG_FILE}"
