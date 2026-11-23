# HOSTING.md — the $0 hosting stack

Ethuram has **no funds for hosting**. FreeAgent therefore runs on a layered
stack where every layer is free. v1 ships Layers 0 + 1 + 2 and is fully
working at $0. Layer 3 is an optional later unlock, never a requirement.

## Layer 0 — The phone itself is the host (primary)
- The APK runs the agent directly on Ethuram's **Samsung Galaxy A12**
  (Helio P35, typically 3–4 GB RAM, Android 10/11).
- The "brains" are remote **free APIs** (AgentRouter primary, Groq
  fallback), so the phone's CPU load is light: it sends prompts, receives
  text, runs tools. **No local LLM runs on the phone** — a local model is
  not realistic on this hardware, and none is promised.
- **Honest autonomy limits:** Android battery optimisation / Doze can
  delay background work. Mitigations built into the app design:
  **WorkManager** for periodic jobs, an optional **foreground-service
  mode** for long tasks (visible notification, user-controlled), and
  battery-optimisation exemption requested (with the user's consent).
- Jobs pause when the phone is off, dead, or without data. This layer is
  **"autonomous while the phone lives"** — it is not a 24/7 server, and
  the docs/app must never claim it is.

## Layer 1 — GitHub Actions (scheduled jobs + APK builds, public repo)
- **Free and unlimited** standard Linux runners for **public** repos.
  Private repos get 2,000 minutes/month free instead — so FreeAgent's
  repo should stay public (it is open source by design).
- Scheduled workflows: minimum interval 5 minutes; runs **can be delayed
  or dropped under load**, and schedules **auto-disable after 60 days of
  repository inactivity** — the agent's own result commits count as
  activity and reset that clock.
- Job cap **6 hours**; runners are ephemeral — **no persistence**. State
  must be committed back (encrypted) or kept on the phone.
- Secrets only via **GitHub Secrets**, never in code, YAML, or logs.
- ⚠️ **Workflow logs on a public repo are public.** Therefore **only
  non-private jobs run here**: the daily AgentRouter check-in, provider
  health checks, public research. **Never Gmail, never private content,
  never account sessions on Actions.** Privacy routing (SECURITY.md)
  applies to infrastructure too, not just providers.

## Layer 2 — Cloudflare Workers (free relay)
- Free tier: **100,000 requests/day**, **5 cron triggers per account**,
  **10 ms CPU per invocation**.
- Role: an always-on, lightweight **webhook / relay / queue** between
  the phone and GitHub Actions (e.g. receive a trigger, wake a workflow,
  hold a small queue, serve the latest APK download link).
- It **cannot** run a browser or heavy agent work — 10 ms CPU is a
  relay, not a computer. It never sees private content either; it moves
  metadata/envelopes only.

## Layer 3 — OPTIONAL later upgrade: Oracle Cloud Always Free
- Always Free allowance (reduced June 2026): **2 OCPU / 12 GB RAM** ARM
  (VM.Standard.A1.Flex), **200 GB** block storage, **no expiry**, no
  charge while inside Always Free limits.
- **Possible blocker, stated plainly:** signup requires a credit card —
  or a credit-like debit card — for identity verification, with a
  ~$1 temporary hold; **prepaid and virtual cards are often refused**.
  If Ethuram cannot pass that check, this layer simply stays off; v1
  does not depend on it.
- **Idle reclaim:** Oracle may reclaim Always Free instances whose
  95th-percentile CPU, network **and** memory use are all under 20%
  over 7 days. (Upgrading the tenancy to Pay-As-You-Go keeps Always
  Free resources free and removes reclaim exposure, per current
  research — but that also requires a card.)
- What it would add: **true 24/7 browser autonomy** (headless Chromium
  on a real server) while the phone is off.

## Conclusion
**v1 = Layers 0 + 1 + 2 = $0, fully working.** The phone hosts the
agent and its secrets; Actions runs the clockwork and builds the APK;
Workers relays. Layer 3 (Oracle) is an optional unlock for 24/7
browser autonomy if/when the card check can be passed — not a
requirement, and nothing in v1 waits on it.
