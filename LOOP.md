# LOOP.md — the tool-call loop (v0.3)

v0.3 is the rung that turns Lille from chat into an agent. The Python
core can now run a task end-to-end: the model plans, calls tools,
observes real results, fixes its own errors, and finishes — with the
finish *verified against the filesystem*, not taken on its word.

Run it:

```bash
python -m free_agent --task "Create a Python program that ... " --pace 50
```

`--pace 50` waits ~50s between model calls (the keyless anonymous
tier's real limit). `--max-steps` sets the step budget (default 12).

## Tool protocol — two paths, one executor

1. **Native tool calls** — the provider returns OpenAI-style
   `tool_calls`; the keyless GPT-OSS endpoint advertises tool support
   on the anonymous tier.
2. **Text fallback** — if a model cannot emit native calls, it emits
   one fenced JSON block `{"tool": "...", "args": {...}}` in its
   reply; the loop parses and executes it identically.

A malformed tool call gets ONE repair retry (the parse error is sent
back to the model); a second malformed call stops the task with an
honest error — never a crash, never a silent retry loop.

## Tools (v0.3 set)

| Tool | What it does | Confined to |
|---|---|---|
| `write_file` | Write a text file | task sandbox |
| `read_file` | Read a text file | task sandbox |
| `list_files` | List sandbox files | task sandbox |
| `run_python` | Execute Python, capture stdout/stderr/exit code | task sandbox, timeout (30s default, 120 max) |
| `web_search` | Keyless search chain (see ROUTES.md) | public web only |
| `finish` | Declare done + claim outputs | outputs verified to exist |

Every task gets a fresh directory under the workspace root
(`FREEAGENT_WORKSPACE` or `~/.freeagent/tasks`). A per-task trace is
kept in the result and persisted to `<workspace>/task_logs/<id>.json`.

## The Sentinel (deterministic, non-LLM — DESIGN #4)

Permissions are decided by plain code (`free_agent/tasktools.py`),
never by asking the model to behave:

- **Allowlist** — only the six tools above exist. Anything else is
  denied by construction.
- **Confinement** — file paths are resolved inside the task sandbox;
  `..`, absolute paths, and symlink escapes are rejected before any
  I/O happens.
- **Approval hook** — a callable `(tool, args_summary) -> bool` may
  deny any call; the default allows the sandboxed tools and denies
  everything off-allowlist. Hosts can inject a stricter, user-facing
  hook.
- **Budgets** — a per-task step budget (default 12) and an output
  budget stop runaway loops honestly, with the trace so far.
- **Verify-by-state** (DESIGN #6) — a `finish` claiming output files
  that do not exist is rejected and sent back to the model as an
  observation. "Done" is a filesystem fact, not a narration.

## Sandbox limits — stated honestly

- `run_python` runs with `cwd` = task dir, a **scrubbed environment**
  (no API keys/tokens/secrets are inherited), and a hard timeout.
- **Network**: on Linux hosts where `unshare -n` actually works
  (probed at runtime, not assumed), executed code runs in a network
  namespace with no interfaces up — it genuinely cannot reach the
  network, and each run result reports `network_isolated=True`.
  Where unshare is unavailable the run still happens under cwd jail +
  env scrub + timeout, the result reports `network_isolated=False`,
  and network blocking there is **policy-level only** — the code is
  told not to use the network, but nothing physically stops it.
  Treat non-isolated hosts accordingly.
- This is a *task* sandbox, not a hostile-code jail: it is designed
  to contain an agent's ordinary work and accidents, not to withstand
  a determined escape attempt by adversarial code. Do not run
  untrusted third-party code through it.

## Inside the loop

- Model calls route through the Router: Fallback Law chains,
  cooldowns on 402/429/503, privacy routing (LOOP.md tasks are public
  content; private tasks still refuse untrusted routes).
- A phrasing refusal of the task gets the v0.2.1 treatment: ONE
  transparent reframe + retry; harmful requests are never reframed.
- Streaming/auto-continue (v0.2.1) applies to chat; loop calls use
  whole-body responses because tool-call JSON must arrive complete.

## Android status

The APK is bumped to v0.3 and its roadmap reflects this rung, but the
loop itself runs in the Python core for now. In-app task mode (the
same loop behind the chat UI) is the next step — it is not in the
v0.3 APK and is not claimed to be.
