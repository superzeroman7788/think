#!/usr/bin/env bash
# CLOSE-ASR-BE acceptance wrapper
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

python3 -m pip install -q websockets 2>/dev/null || pip3 install -q websockets

python3 supabase/tests/asr_session_e2e.py "$@"
