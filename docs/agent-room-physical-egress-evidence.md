# Agent Room Physical Egress Evidence

Date: 2026-05-31

Status: passed.

## Mechanism

`tools/agent-room/codex_exec_handler.py` now runs the worker `codex exec` under an
outer macOS sandbox profile:

```text
(version 1)
(allow default)
(deny network*)
(allow network-outbound (remote ip "localhost:*"))
```

That means the worker process tree cannot directly reach external network
destinations. The only network destination available to the worker is localhost.

To keep Codex itself alive, the handler starts a local HTTP CONNECT proxy outside
the sandbox and injects proxy env vars into the worker. The proxy allowlist is:

```json
["chatgpt.com", "*.chatgpt.com"]
```

So model API traffic is allowed through the localhost proxy, while all other
domains are denied. If a worker clears proxy env vars and tries direct egress,
the OS sandbox blocks DNS/network.

## Evidence Files

- `/tmp/agent-room-egress-probe-2.json`
- `/tmp/agent-room-egress-direct-bypass-probe.json`
- `/tmp/agent-room-egress-final.json`

Both were produced by the real `codex_exec_handler.py`, not by a stub.

## Model API Allowed

Handler evidence from `/tmp/agent-room-egress-final.json`:

```text
network_policy: model-api-only
effective_argv: sandbox-exec -p '(version 1)(allow default)(deny network*)(allow network-outbound (remote ip "localhost:*"))' codex ...
```

Proxy events included allowed model traffic:

```json
{"action": "allow", "host": "chatgpt.com", "port": 443}
{"action": "allow", "host": "ab.chatgpt.com", "port": 443}
```

The worker completed with `codex_returncode: 0`.

## External Curl Blocked

Worker command:

```bash
/usr/bin/curl -I https://example.com --max-time 5
```

Worker result:

```text
curl: (56) CONNECT tunnel failed, response HTTP/1.1 403 Forbidden
exit_code: 56
```

Proxy event:

```json
{"action": "deny", "reason": "host_not_allowed", "host": "example.com"}
```

## External Urllib Blocked

Worker command:

```python
import urllib.request
print(urllib.request.urlopen("https://example.com", timeout=5).status)
```

Worker result:

```text
urllib.error.URLError: <urlopen error Tunnel connection failed: 403 Forbidden>
exit_code: 1
```

Proxy event:

```json
{"action": "deny", "reason": "host_not_allowed", "host": "example.com"}
```

## Direct Bypass Also Blocked

The worker then cleared proxy environment variables and used absolute curl:

```bash
env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy \
  /usr/bin/curl -I https://example.com --max-time 5
```

Worker result:

```text
curl: (6) Could not resolve host: example.com
EXIT_CODE:6
```

The worker also forced Python urllib to avoid proxies:

```python
import urllib.request
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
print(opener.open("https://example.com", timeout=5).status)
```

Worker result:

```text
urllib.error.URLError: <urlopen error [Errno 8] nodename nor servname provided, or not known>
EXIT_CODE:1
```

This proves the direct path is blocked by the OS sandbox, not just by a command
wrapper.
