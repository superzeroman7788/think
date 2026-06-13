#!/usr/bin/env bash
# day-summary v2 复测：清掉当天旧持久化 → 重新 POST → 对照 tasks 数量
#
# 用法 A（推荐，用 App 同账号 JWT）:
#   export SUPABASE_URL=https://xxx.supabase.co
#   export SUPABASE_ANON_KEY=...
#   export ACCESS_TOKEN="<从 App / Supabase 会话复制的 access_token>"
#   bash supabase/tests/day_summary_retest.sh
#
# 用法 B（仅验 API，匿名 signup 新用户无今日 tasks，数字会是 0）:
#   同上但不设 ACCESS_TOKEN → 脚本会 signup 临时用户（tasks 可能为空）
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TODAY="$(date +%Y-%m-%d)"
SUPABASE_URL="${SUPABASE_URL:?}"
SUPABASE_ANON_KEY="${SUPABASE_ANON_KEY:?}"

if [[ -z "${ACCESS_TOKEN:-}" ]]; then
  echo "==> 无 ACCESS_TOKEN，signup 临时用户（通常无今日 tasks，仅 smoke）"
  ACCESS_TOKEN="$(curl -s -X POST "$SUPABASE_URL/auth/v1/signup" \
    -H "apikey: $SUPABASE_ANON_KEY" \
    -H "Authorization: Bearer $SUPABASE_ANON_KEY" \
    -H "Content-Type: application/json" \
    -d '{}' | jq -r '.access_token')"
fi

USER_ID="$(curl -s "$SUPABASE_URL/auth/v1/user" \
  -H "apikey: $SUPABASE_ANON_KEY" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq -r '.id')"
echo "user_id=$USER_ID date=$TODAY"

auth_hdr=(-H "apikey: $SUPABASE_ANON_KEY" -H "Authorization: Bearer $ACCESS_TOKEN")

echo ""
echo "==> 1) 当前 daily_reflections（App 进屏读的就是这个）"
curl -s "$SUPABASE_URL/rest/v1/daily_reflections?user_id=eq.$USER_ID&date=eq.$TODAY&select=ai_summary,ai_praise,ai_advice" \
  "${auth_hdr[@]}" -H "Accept: application/json" | jq .

echo ""
echo "==> 2) 当前 tasks（BE day-summary 统计源 = DB，与审核列表同源）"
TASKS_JSON="$(curl -s "$SUPABASE_URL/rest/v1/tasks?user_id=eq.$USER_ID&date=eq.$TODAY&deleted_at=is.null&select=id,title,status" \
  "${auth_hdr[@]}" -H "Accept: application/json")"
echo "$TASKS_JSON" | jq .
TOTAL="$(echo "$TASKS_JSON" | jq '[.[] | select(.status != "dropped")] | length')"
DONE="$(echo "$TASKS_JSON" | jq '[.[] | select(.status == "done")] | length')"
echo "期望 praise 数字: ${DONE}/${TOTAL}"

echo ""
echo "==> 3) 清空当天 ai_*（模拟「从未生成过」，App 才会出现按钮）"
curl -s -X PATCH "$SUPABASE_URL/rest/v1/daily_reflections?user_id=eq.$USER_ID&date=eq.$TODAY" \
  "${auth_hdr[@]}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=minimal" \
  -d '{"ai_summary":null,"ai_praise":null,"ai_advice":null}' \
  -w " PATCH HTTP=%{http_code}\n" -o /dev/null

echo ""
echo "==> 4) POST /day-summary（body 可只带 date；tasks 以 DB 为准）"
RESP="$(curl -s -X POST "$SUPABASE_URL/functions/v1/day-summary" \
  "${auth_hdr[@]}" \
  -H "Content-Type: application/json" \
  -d "{\"date\":\"$TODAY\"}")"
echo "$RESP" | jq .

PRAISE="$(echo "$RESP" | jq -r '.praise // ""')"
if echo "$PRAISE" | grep -q "${TOTAL}件里做成了${DONE}件"; then
  echo "✅ praise 数字与 DB tasks 一致"
else
  echo "⚠️  praise 未含期望片段「${TOTAL}件里做成了${DONE}件」— 检查 tasks 或 BE"
fi
if echo "$PRAISE" | grep -Eq '没搞定|无深度|2件里做成了1件'; then
  echo "❌ praise 仍含禁忌/旧口径"
  exit 1
fi
if echo "$RESP" | jq -r '.advice // ""' | grep -q '深度活儿'; then
  if ! echo "$TASKS_JSON" | jq -e '.[] | select(.task_type == "deep_work" or (.title | test("深度")))' >/dev/null 2>&1; then
    echo "❌ advice 编造深度活儿"
    exit 1
  fi
fi

echo ""
echo "==> 5) 持久化回读（App 下次 load 会显示）"
curl -s "$SUPABASE_URL/rest/v1/daily_reflections?user_id=eq.$USER_ID&date=eq.$TODAY&select=ai_praise,ai_advice" \
  "${auth_hdr[@]}" -H "Accept: application/json" | jq .

echo ""
echo "App 侧: 完全杀进程 → 再进复盘页（应直接显示上一步 ai_praise/advice，无需再点按钮）"
