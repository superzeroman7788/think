#!/usr/bin/env bash
# 块二 /plan-revise 契约对账探针（v1.1）。Cursor 部署后运行：
#   bash scripts/reconcile_plan_revise.sh
# 校验 BE 返回的字段名/形状/错误码 == FE 写死的 v1.1，避免「上线才发现对不上」。
set -euo pipefail
cd "$(dirname "$0")/.."

URL=$(grep '^SUPABASE_URL=' .env | cut -d= -f2- | tr -d '"')
ANON=$(grep '^SUPABASE_ANON_KEY=' .env | cut -d= -f2- | tr -d '"')
RESP=$(curl -s -X POST "$URL/auth/v1/signup" -H "apikey: $ANON" -H "Authorization: Bearer $ANON" -H "Content-Type: application/json" -d '{}')
TOKEN=$(echo "$RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")

echo "=== 1) plan-revise propose（已开始任务=锁定，未来任务=可挪/可弃）==="
PROP=$(curl -s -X POST "$URL/functions/v1/plan-revise" \
  -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
    "date":"2026-06-05","timezone":"Asia/Shanghai","now":"2026-06-05T14:30:00+08:00",
    "instruction":"跑步推一小时，刷邮件不做了",
    "tasks":[
      {"id":"11111111-1111-1111-1111-111111111111","title":"写报告","planned_start":"2026-06-05T14:00:00+08:00","planned_duration":60,"important":true,"status":"planned","actual_start":"2026-06-05T14:05:00+08:00"},
      {"id":"22222222-2222-2222-2222-222222222222","title":"跑步","planned_start":"2026-06-05T18:00:00+08:00","planned_duration":30,"important":false,"status":"planned","actual_start":null},
      {"id":"33333333-3333-3333-3333-333333333333","title":"刷邮件","planned_start":"2026-06-05T20:00:00+08:00","planned_duration":20,"important":false,"status":"planned","actual_start":null}
    ]}')
echo "$PROP" | python3 - <<'PY'
import sys,json
d=json.load(sys.stdin)
ok=True
def chk(c,m):
    global ok
    print(("  OK  " if c else "  FAIL")+" "+m); ok=ok and c
chk("revision_id" in d, "顶层有 revision_id")
chk("summary" in d, "顶层有 summary")
chk(isinstance(d.get("revisions"),list), "revisions 是数组")
fields_seen=set(); changes={}
for r in d.get("revisions",[]):
    for k in ("task_id","title","change","before","after"):
        if k in r: fields_seen.add(k)
    changes[r.get("task_id")]=r.get("change")
    for st in ("before","after"):
        s=r.get(st) or {}
        # 字段名校验（出现即检查拼写）
chk({"task_id","title","change","before","after"} <= fields_seen, "revisions[] 字段名: task_id/title/change/before/after")
chk(all(c in ("moved","dropped","unchanged") for c in changes.values() if c), "change 三态 ∈ {moved,dropped,unchanged}")
# 业务校验
chk(changes.get("11111111-1111-1111-1111-111111111111")!="moved", "已开始任务(写报告)不得 moved（BE 端锁）")
chk(changes.get("33333333-3333-3333-3333-333333333333")=="dropped", "刷邮件 → dropped")
# before/after 字段名
for r in d.get("revisions",[]):
    a=r.get("after") or {}
    if r.get("change")=="moved":
        chk("planned_start" in a, f"moved.after 有 planned_start ({r.get('title')})")
print("\nRESULT:", "PASS ✅" if ok else "FAIL ❌")
print("revision_id =", d.get("revision_id"))
PY

echo
echo "=== 2) plan-revise-apply 防陈旧：用假 revision_id 期望 409/APPLY_STALE 或 4xx ==="
CODE=$(curl -s -o /tmp/_apply.json -w '%{http_code}' -X POST "$URL/functions/v1/plan-revise-apply" \
  -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"date":"2026-06-05","revision_id":"00000000-0000-0000-0000-000000000000","revisions":[{"task_id":"22222222-2222-2222-2222-222222222222","change":"moved","after":{"planned_start":"2026-06-05T19:00:00+08:00","planned_duration":30,"status":"planned"}}]}')
echo "HTTP $CODE  body: $(cat /tmp/_apply.json)"
echo "  期望: 409 且 error.code=APPLY_STALE（或非法 revision_id → APPLY_CONFLICT）"
