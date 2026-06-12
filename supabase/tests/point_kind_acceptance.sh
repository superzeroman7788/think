#!/usr/bin/env bash
# 时刻点(钉子) + 时间语义 · 验收（单元 + 可选 apply RPC）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EVIDENCE_DIR="${EVIDENCE_DIR:-${ROOT}/docs/test-evidence}"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="${EVIDENCE_DIR}/point-kind-${TS}.log"
COMMIT="$(git -C "${ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"

mkdir -p "${EVIDENCE_DIR}"
exec > >(tee -a "${LOG_FILE}") 2>&1

echo "=== 时刻点 BE 验收 ==="
echo "commit=${COMMIT}"
echo "log=${LOG_FILE}"

echo ""
echo "=== 1) 单元测试 time_semantics + revise_semantics ==="
cd "${ROOT}"
deno test \
  supabase/functions/_shared/plan/time_semantics_test.ts \
  supabase/functions/_shared/plan/plan_revise_v13_test.ts

echo ""
echo "=== 2) 语义样例（输入 → 期望字段）==="

deno eval --quiet "
import {
  collectTimeSemanticErrors,
  finalizePlanTaskSemantics,
  parseExplicitRanges,
} from './supabase/functions/_shared/plan/time_semantics.ts';

const cases = [
  {
    name: '下午2点到6点工作',
    input: '下午2点到6点工作',
    tasks: [{ title: '工作', task_type: 'deep_work', time_of_day: 'afternoon', planned_start: '14:00', planned_duration: 240 }],
    expectErrors: 0,
  },
  {
    name: '3点提醒我给老张打电话',
    input: '3点提醒我给老张打电话',
    tasks: [{ title: '给老张打电话', task_type: 'social', time_of_day: 'afternoon', planned_start: '15:00', planned_duration: 0, kind: 'point' }],
    expectErrors: 0,
  },
  {
    name: '一块+一钉',
    input: '下午2点到6点工作,3点提醒我给老张打电话',
    tasks: [
      { title: '工作', task_type: 'deep_work', time_of_day: 'afternoon', planned_start: '14:00', planned_duration: 240 },
      { title: '给老张打电话', task_type: 'social', time_of_day: 'afternoon', planned_start: '15:00', planned_duration: 0, kind: 'point' },
    ],
    expectErrors: 0,
  },
];

for (const c of cases) {
  const finalized = finalizePlanTaskSemantics(c.tasks, c.input);
  const errors = collectTimeSemanticErrors(finalized, c.input);
  const ranges = parseExplicitRanges(c.input);
  console.log(JSON.stringify({
    case: c.name,
    ranges,
    tasks: finalized.map((t) => ({
      title: t.title,
      kind: t.kind,
      planned_start: t.planned_start,
      planned_duration: t.planned_duration,
      anchor_block_start: t.anchor_block_start ?? null,
    })),
    errors,
    pass: errors.length === c.expectErrors,
  }));
  if (errors.length !== c.expectErrors) Deno.exit(1);
}
console.log('semantic_cases=PASS');
"

if [[ -n "${SUPABASE_ANON_KEY:-}" && -n "${SUPABASE_SERVICE_ROLE_KEY:-}" ]]; then
  echo ""
  echo "=== 3) apply_plan_revise: point moved 保持 duration=0 ==="
  PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
  SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
  REST_URL="${SUPABASE_URL}/rest/v1"
  AUTH_URL="${SUPABASE_URL}/auth/v1"
  RPC_URL="${SUPABASE_URL}/rest/v1/rpc"
  TODAY="$(date +%F)"

  CREATE="$(curl -sS -X POST "${AUTH_URL}/admin/users" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"point_kind_${TS}@think-act.test\",\"password\":\"Point_${TS}!\",\"email_confirm\":true}")"
  USER_ID="$(echo "${CREATE}" | jq -r '.id')"
  TOKEN="$(curl -sS -X POST "${AUTH_URL}/token?grant_type=password" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"point_kind_${TS}@think-act.test\",\"password\":\"Point_${TS}!\"}" | jq -r '.access_token')"

  BLOCK_JSON="$(curl -sS -X POST "${REST_URL}/tasks" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -H "Prefer: return=representation" \
    -d "[{\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"工作\",\"planned_start\":\"${TODAY}T14:00:00+08:00\",\"planned_duration\":240,\"status\":\"planned\",\"source\":\"user_voice\",\"kind\":\"block\"}]")"
  BLOCK_ID="$(echo "${BLOCK_JSON}" | jq -r '.[0].id')"

  POINT_JSON="$(curl -sS -X POST "${REST_URL}/tasks" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -H "Prefer: return=representation" \
    -d "[{\"user_id\":\"${USER_ID}\",\"date\":\"${TODAY}\",\"title\":\"打电话\",\"planned_start\":\"${TODAY}T15:00:00+08:00\",\"planned_duration\":0,\"status\":\"planned\",\"source\":\"user_voice\",\"kind\":\"point\",\"anchor_task_id\":\"${BLOCK_ID}\"}]")"
  POINT_ID="$(echo "${POINT_JSON}" | jq -r '.[0].id')"

  BASELINE="$(jq -n \
    --arg bid "${BLOCK_ID}" \
    --arg pid "${POINT_ID}" \
    --arg date "${TODAY}" \
    '[
      {task_id: $bid, status: "planned", planned_start: ($date + "T14:00:00+08:00"), planned_duration: 240, actual_start: null},
      {task_id: $pid, status: "planned", planned_start: ($date + "T15:00:00+08:00"), planned_duration: 0, actual_start: null}
    ]')"

  REV_ID="$(curl -sS -X POST "${RPC_URL}/create_plan_revise_proposal" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg date "${TODAY}" --argjson baseline "${BASELINE}" '{p_date: $date, p_baseline: $baseline, p_ttl_minutes: 30, p_added_proposed: []}')")"

  APPLY="$(curl -sS -X POST "${RPC_URL}/apply_plan_revise" \
    -H "apikey: ${SUPABASE_ANON_KEY}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$(jq -n \
      --arg rid "${REV_ID}" \
      --arg date "${TODAY}" \
      --arg pid "${POINT_ID}" \
      '{
        p_revision_id: $rid,
        p_date: $date,
        p_revisions: [{
          task_id: $pid,
          change: "moved",
          after: { planned_start: ($date + "T16:00:00+08:00"), planned_duration: 30, status: "planned" }
        }],
        p_added: []
      }')")"

  echo "apply=${APPLY}"
  AFTER="$(curl -sS "${REST_URL}/tasks?id=eq.${BLOCK_ID}&select=planned_start,planned_duration,kind" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}")"
  POINT_AFTER="$(curl -sS "${REST_URL}/tasks?id=eq.${POINT_ID}&select=planned_start,planned_duration,kind" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}")"
  echo "block_after=${AFTER}"
  echo "point_after=${POINT_AFTER}"

  BLOCK_DUR="$(echo "${AFTER}" | jq -r '.[0].planned_duration')"
  POINT_DUR="$(echo "${POINT_AFTER}" | jq -r '.[0].planned_duration')"
  POINT_START="$(echo "${POINT_AFTER}" | jq -r '.[0].planned_start')"

  [[ "${BLOCK_DUR}" == "240" ]] || { echo "FAIL: block duration changed"; exit 1; }
  [[ "${POINT_DUR}" == "0" ]] || { echo "FAIL: point duration must stay 0"; exit 1; }
  [[ "${POINT_START}" == *"T16:00:00"* ]] || { echo "FAIL: point not moved"; exit 1; }
  echo "apply_rpc=PASS"
else
  echo ""
  echo "=== 3) apply RPC 跳过（无 SUPABASE_* keys）==="
fi

echo ""
echo "=== RESULT: PASS ==="
