#!/usr/bin/env bash
# stability benchmark: N consecutive plan-generate calls
set -euo pipefail

RUNS="${1:-15}"
LABEL="${2:-benchmark}"

PROJECT_REF="${SUPABASE_PROJECT_REF:-mpxdworxojotiwjxdeds}"
SUPABASE_URL="${SUPABASE_URL:-https://${PROJECT_REF}.supabase.co}"
FN_URL="${SUPABASE_URL}/functions/v1/plan-generate"
AUTH_URL="${SUPABASE_URL}/auth/v1"

if [[ -z "${SUPABASE_ANON_KEY:-}" ]]; then
  echo "export SUPABASE_ANON_KEY"
  exit 1
fi

TODAY="$(date -u +%F)"
BODY=$(cat <<EOF
{"date":"${TODAY}","raw_input":"今天上午要写产品文档,下午 3 点开会,晚上想跑步","hard_constraints":[{"start":"15:00","end":"16:00","title":"产品评审会"}],"tone":"friendly"}
EOF
)

echo "========== ${LABEL} (${RUNS} runs) =========="
echo "input: 今天上午要写产品文档,下午 3 点开会,晚上想跑步"
echo ""

# one anonymous user for all runs (fair use allows <=20/day)
SIGNUP=$(curl -sS -X POST "${AUTH_URL}/signup" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "Authorization: Bearer ${SUPABASE_ANON_KEY}" \
  -H "Content-Type: application/json" \
  -d '{}')
TOKEN=$(echo "${SIGNUP}" | jq -r '.access_token // empty')
if [[ -z "${TOKEN}" ]]; then
  echo "anonymous sign-in failed"; echo "${SIGNUP}" | jq .; exit 1
fi

python3 - "$RUNS" "$FN_URL" "$SUPABASE_ANON_KEY" "$TOKEN" "$BODY" <<'PY'
import json, sys, time, urllib.request, urllib.error

runs = int(sys.argv[1])
fn_url = sys.argv[2]
anon = sys.argv[3]
token = sys.argv[4]
body = sys.argv[5].encode()

def classify(err_code: str, http_status: int, raw: str) -> str:
    if err_code == "AI_TIMEOUT":
        return "deepseek_timeout"
    if err_code == "AI_INVALID_JSON":
        return "json_validation_failed"
    if err_code == "ALL_PROVIDERS_DOWN":
        low = raw.lower()
        if "timed out" in low or "timeout" in low:
            return "deepseek_timeout"
        if "http 5" in low or "502" in low or "503" in low or "504" in low or "500" in low:
            return "deepseek_5xx"
        return "all_providers_down"
    if err_code:
        return f"other_{err_code}"
    if http_status >= 500:
        return "edge_5xx"
    return "other"

results = []
for i in range(1, runs + 1):
    req = urllib.request.Request(
        fn_url,
        data=body,
        headers={
            "apikey": anon,
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    t0 = time.perf_counter()
    status = "success"
    category = "success"
    err_code = ""
    http_status = 0
    detail = ""
    provider = ""
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            http_status = resp.status
            raw = resp.read().decode()
            data = json.loads(raw)
            provider = data.get("provider", "")
            if data.get("proposal_id"):
                status = "success"
                category = "success" if provider != "kimi" else "success_kimi_fallback"
            else:
                status = "fail"
                err = data.get("error") or {}
                err_code = err.get("code", "")
                detail = raw[:500]
                category = classify(err_code, http_status, detail)
    except urllib.error.HTTPError as e:
        http_status = e.code
        raw = e.read().decode(errors="replace")
        status = "fail"
        try:
            data = json.loads(raw)
            err_code = (data.get("error") or {}).get("code", "")
            detail = raw[:500]
        except Exception:
            detail = raw[:500]
        category = classify(err_code, http_status, detail)
    except Exception as e:
        status = "fail"
        category = "other"
        detail = str(e)
    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    results.append({
        "run": i,
        "status": status,
        "category": category,
        "ms": elapsed_ms,
        "err_code": err_code,
        "provider": provider,
    })
    prov_suffix = f" | provider={provider}" if provider else ""
    print(f"run {i:2d}: {status:6s} | {category:22s} | {elapsed_ms:5d}ms{prov_suffix}")

from collections import Counter
cats = Counter(r["category"] for r in results)
success = sum(1 for r in results if r["status"] == "success")
times = [r["ms"] for r in results if r["status"] == "success"]
avg = int(sum(times) / len(times)) if times else 0

print()
print("--- summary ---")
print(f"success_rate: {success}/{runs} ({100*success/runs:.1f}%)")
print(f"avg_latency_success_ms: {avg}")
print("failure_distribution:")
for k, v in sorted(cats.items(), key=lambda x: -x[1]):
    if k != "success":
        print(f"  {k}: {v}")
if success:
    print(f"  success: {success}")
kimi_runs = sum(1 for r in results if r.get("provider") == "kimi")
if kimi_runs:
    print(f"kimi_fallback_triggered: {kimi_runs}/{runs}")
else:
    print("kimi_fallback_triggered: 0")
PY
