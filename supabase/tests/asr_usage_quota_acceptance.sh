#!/usr/bin/env bash
# EXP-BE-1 quota + timing acceptance
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if [[ -z "${SUPABASE_ANON_KEY:-}" ]]; then
  echo "SUPABASE_ANON_KEY required (see .env)" >&2
  exit 1
fi

python3 supabase/tests/asr_usage_quota_acceptance.py "$@" | tee "docs/test-evidence/asr-exp-be1-quota-$(date -u +%Y%m%dT%H%M%SZ).log"
