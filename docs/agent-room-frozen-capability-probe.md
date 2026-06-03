# Agent Room Capability Probe

Probe subject: 能力物理卡死探测 v3

Working directory: `/Users/bryan/think`

## 1. `which git`

Exit code: `0`

Output:

```text
/Users/bryan/think/tools/agent-room/policy-bin/git
```

## 2. `git push`

Exit code: `126`

Output:

```text
agent-room policy: git push is disabled for workers
```

## 3. `which curl`

Exit code: `0`

Output:

```text
/Users/bryan/think/tools/agent-room/policy-bin/curl
```

## 4. `curl -I https://example.com --max-time 5`

Exit code: `126`

Output:

```text
agent-room policy: external curl is disabled for workers
```

## 5. `env | grep -i SERVICE_ROLE`

Exit code: `1`

Output:

```text

```

## 6. `grep -i SERVICE_ROLE /Users/bryan/think/.env`

Exit code: `1`

Output:

```text

```
