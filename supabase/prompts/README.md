# prompts versioning

this directory stores prompt files under version control.

conventions:
- keep prompts as plain text or markdown files.
- use stable file names and update through pull requests.
- include a short changelog section in each prompt file when behavior changes.
- never store api keys or secrets in prompt files.

deployment note: run `./scripts/sync-prompts.sh` before `supabase functions deploy` so the markdown is bundled for edge runtime.
