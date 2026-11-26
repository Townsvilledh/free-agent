# KEYLESS.md — the no-keys doctrine

Lille works **with no API key at all**. No key is collected from
the user, from the developer, or from anybody else. No key is shipped
in the APK, in this repo, or in any config file. This is a design
doctrine (decided by Ethuram, 2026-11-24), not a missing feature.

## Why a shipped/shared key is a trap

The tempting shortcut — "just embed one API key so the app works out
of the box" — is how free apps die:

- **Extractable:** any key inside an APK can be pulled out by anyone
  who downloads it (decompile, strings, traffic sniffing). A shipped
  key is a *published* key within days.
- **Abused:** once published, strangers burn the quota and run up the
  usage on someone else's account. The owner gets the bill, the
  rate-limits, and the abuse flags.
- **Revoked:** providers detect leaked keys and kill them. The moment
  the shared key dies, **every copy of the app dies with it** — for
  every user, everywhere, at once.
- **Terms:** sharing a personal API key across users generally
  violates the provider's terms of service, risking the account.

So: the agent must be able to run on **free public (keyless)
endpoints alone**. If it can't work without a key, that's a bug in
the design, not a reason to ship one.

## Keyless endpoints used (v0.2)

All are Pollinations' public Classic API — no key, no signup, no
account:

| Use | Endpoint | Notes |
|---|---|---|
| Chat (text) | `POST https://text.pollinations.ai/openai` | OpenAI-compatible; body is `{model, messages}`. **POST, never the GET prompt-in-URL form** — a prompt in a URL gets written into server logs, proxy logs and caches along the way. |
| Model list | `GET https://text.pollinations.ai/models` | Currently GPT-OSS 20B class models; the app defaults to model `openai`. |
| Images | `https://image.pollinations.ai/prompt/{url-encoded prompt}` | Free generated images carry a **small logo**, and results are **cached by URL** — treat a generated image URL as **public**: anyone with the link can fetch it, and the same prompt returns the same cached image. Never put private content in an image prompt. |

### Real limits of the anonymous tier (design around them, honestly)

- **1 request at a time**, roughly **45 seconds between requests per
  IP**. The app queues and retries; the Python router falls through
  to the next provider on `402`/`429`.
- Beyond the limits you get **`402` with a `Retry-After`** — respect
  it; do not hammer the endpoint.
- **Best-effort only.** No uptime guarantee, models and limits can
  change or vanish without notice. Multi-provider fallback is the
  answer, not a complaint.
- **Privacy caveat:** a keyless public endpoint is a *public*
  endpoint. It is **untrusted for private content** — exactly like
  AgentRouter and Groq under the privacy routing rules (SECURITY.md).
  Gmail content, account data, anything private: never sent there.
  Private work stays on-device/local or waits for a trusted path.

## Optional personal keys (the only keys that may exist)

A user may *optionally* add **their own** free keys — e.g. their own
AgentRouter token or Groq key — for higher limits or stronger models:

- Entered by the user themselves, **in the app**, into the
  Keystore-encrypted vault (Android) / supplied as environment
  variables (Python core).
- **Never collected by us, never asked for in chat, never transmitted
  anywhere except to that key's own provider, never logged, and
  never in any backup plaintext** — in the app they ride the user's
  own encrypted backup *inside the vault* (BACKUP.md), as
  ciphertext only, under a key the user controls.
- The app must remain fully functional with the vault empty. Keys are
  an upgrade, never a requirement.

## The fallback law (why one keyless endpoint is not a single point of failure)

Keyless-first does not mean keyless-only. Under the **fallback law**
(ROUTES.md), every capability has an ordered route chain — for text:
keyless Pollinations → keyless AI Horde → your own Groq → your own
Gemini → your own Cerebras → your own OpenRouter → your own
AgentRouter → your own custom endpoint. Routes cool down on
`402/429/503` instead of being hammered, recover automatically, and
if *all* routes are cooling down the agent queues the task and says
so plainly with the earliest retry time. Keyless is simply the
default first route — the one that needs nothing from anybody.
Privacy routing still overrides the whole chain: no route in it
receives private content, however available it is.

## v0.12.1 — the own-keys door, shipped (2026-11-26)

What the section above always promised is now built in the app:
Settings → **"Your API keys (optional)"** — Groq, Google Gemini,
Cerebras, OpenRouter, AgentRouter, plus one custom
OpenAI-compatible endpoint (base URL + key, optional model).
Each has Save / Remove / **Test** (one minimal call; only the
outcome and a model count are reported — the key is never echoed).
Saved keys join the brain chain behind the keyless routes, in
chat and in the task engine, under the same per-route health
machinery; a provider with no key saved is simply absent — no
nagging, no error. The Python core walks the same chain with
keys read from environment variables only. Defaults and the
verification evidence for each model choice are in ROUTES.md.

## v0.2.1 — smart fixes (2026-11-24)

Fixes from the live tough-task battery (`live-tests/`), all solved by
making Lille smarter — none by hard-coding content:

- **Refusal recovery.** The free model refuses some phrasings
  ("autonomous profitable agent") while doing the identical work
  phrased concretely. On a refusal, Lille reframes the request ONCE
  into a concrete task form and retries, telling the user it did so.
  Refusals of genuinely harmful/illegal requests are never reframed.
  (Two live lessons baked in: refusals arrive with curly apostrophes
  — detection normalises quotes; and the reframe must *replace* the
  trigger wording, not wrap it.)
- **Streaming with auto-continue.** Chat now streams (SSE); if the
  connection drops mid-answer, the partial text is preserved and the
  task auto-continues up to 3 times, stitching one whole answer —
  the battery lost 3 of 8 first attempts to mid-body transport cuts.
- **Extraction verification.** JSON extractions are checked field by
  field against the source text; a mismatch (the battery's
  "Mr." → "M.") triggers one correction retry naming the field.
- **Grounding rule.** System prompts (Python + Android) now forbid
  inventing features/facts/steps, require unknowns to be labelled,
  and require safe, standard-practice advice.
- **No hard-coded branding.** A battery task had baked another
  project's X-profile sign-off into Lille as if it were a rule. It
  was corrected with a dated note; branding lives in per-user
  config/memory only (DESIGN.md #11).

Live re-runs of both affected cases: `live-tests/2026-11-24-v021-fixes.md`.

## The rule, in one line

> If Lille stops working when every API key is deleted, the
> build is wrong. Keyless first; personal keys optional; shared keys
> never.
