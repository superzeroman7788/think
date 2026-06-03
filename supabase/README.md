# think & act · supabase backend

## prerequisites

- [supabase cli](https://supabase.com/docs/guides/cli) (`brew install supabase/tap/supabase`)
- docker engine — this repo uses **colima** (`brew install colima docker && colima start`)

> analytics is disabled in `config.toml` because the vector container fails to mount docker.sock under colima on macOS.

## first-time setup

```bash
# from repo root
cp .env.example .env
# fill DEEPSEEK_API_KEY in .env

supabase start
supabase db reset
```

## edge functions

`llm-ping` is a temporary smoke-test endpoint for the llm adapter.

```bash
# with local stack running (llm-ping has verify_jwt=false for smoke tests)
curl -sS -X POST "http://127.0.0.1:54321/functions/v1/llm-ping" \
  -H "Content-Type: application/json" \
  -d '{"q":"用一句话说你好"}'
```

one-liner dev bootstrap from repo root:

```bash
./scripts/supabase-dev.sh
```

serve a single function with hot reload (optional):

```bash
supabase functions serve llm-ping --env-file .env
```

## secrets

- local: keys in repo-root `.env`, referenced by `supabase/config.toml` → `[edge_runtime.secrets]`
- remote project: **think and act** (`mpxdworxojotiwjxdeds`, seoul)
- remote: already linked; update secrets with `supabase secrets set --env-file .env`
- deploy functions:
  ```bash
  ./scripts/sync-prompts.sh   # before plan-generate deploy
  supabase functions deploy llm-ping
  supabase functions deploy plan-generate
  ```
- push schema: `supabase db push`

## remote smoke test

```bash
curl -sS -X POST "https://mpxdworxojotiwjxdeds.supabase.co/functions/v1/llm-ping" \
  -H "Content-Type: application/json" \
  -d '{"q":"用一句话说你好"}'
```

dashboard: https://supabase.com/dashboard/project/mpxdworxojotiwjxdeds

## useful urls (after `supabase start`)

- studio: http://127.0.0.1:54323
- api: http://127.0.0.1:54321
- db: `postgresql://postgres:postgres@127.0.0.1:54322/postgres`
