#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  echo "missing .env — run: cp .env.example .env"
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

if ! docker info >/dev/null 2>&1; then
  if command -v colima >/dev/null 2>&1; then
    echo "starting colima..."
    colima start
  else
    echo "docker is not running. install colima or start docker desktop / orbstack first."
    exit 1
  fi
fi

supabase start
supabase db reset

echo ""
echo "supabase is ready."
echo "studio: http://127.0.0.1:54323"
echo "test llm:"
echo "  curl -sS -X POST http://127.0.0.1:54321/functions/v1/llm-ping -H 'Content-Type: application/json' -d '{\"q\":\"用一句话说你好\"}'"
