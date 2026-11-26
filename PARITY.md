# PARITY.md — Lille vs the Muse-twin scope, honestly

*The all-round-thinker map (Ethuram, 2026-11-26): the complete
shape of what a Muse-twin agent is, against what Lille is
today — so gaps are surfaced by us, never discovered by the
user one by one. Ordered by what a user would notice missing
first. Statuses: **SHIPPED** (live-proven or in the shipped
build), **PARTIAL** (works with a named limitation), **NOT
BUILT**. Factual, no marketing. Updated per release; this map
is current as of v0.12.0.*

| # | Capability | Status | Evidence / gap |
|---|---|---|---|
| 1 | Chat with an AI brain | **SHIPPED** | Keyless brain in app + core since v0.2; live battery 32/40 (`live-tests/2026-11-24-battery.md`). v0.12: the chat never dead-ends on a busy route (below). |
| 2 | Brain resilience — a busy/free route never ends the conversation | **SHIPPED (v0.12)** | Failure classifier (offline / network / rate-limit / server / permanent) + route chain Pollinations → AI Horde + escalating auto-retry with honest status lines; "check your connection" is only said when the device is really offline. Live route verdicts in ROUTES.md (2026-11-26). |
| 3 | Main-wallet transaction approvals from inside Lille (WalletConnect session requests, approval rendered in the embedded Fluid sheet — "sign where you stand", open to any agent holding a Fluid session) | **NOT BUILT** | Fluid-side dependency: Fluid must accept dapp *transaction* requests over the WalletConnect session. Its pairing acceptance is proven (v0.10 login); transaction requests are not yet accepted. Lille's session is identity-only (`personal_sign`) by design until then. Follow-up also recorded: Fluid has no `wc:` deep link (checked 2026-11-26) — a `#wc=` link would let the embedded sheet carry the pairing itself instead of the clipboard. |
| 4 | Wallet control — her own agentic wallet | **SHIPPED (v0.12)** | WALLET.md: separate on-device keyset in her vault, balances (Ethereum + Base, RPC fallback, honest couldn't-load vs zero), strict-parser prepare-and-ask with in-app approval sheet, caps enforced in the signer (user's dial: defaults $5/$20, raiseable, removable), autonomous-within-caps switch (default off), sweep-back to the verified Fluid address, full activity list. EVM only; task-engine spends NOT BUILT (chat + Wallet screen only). |
| 5 | Multiple chats | **SHIPPED (v0.11)** | Persisted conversations, chat list, new/switch/delete, per-chat queue + reply state (ChatStore/ChatData, JVM-pinned). |
| 6 | Backup & restore (autonomous, cross-device) | **SHIPPED (v0.11–v0.11.1)** | BACKUP.md: Fluid-signature-derived key (passphrase fallback), folder layer + Google Drive appDataFolder layer enabled in the shipped build; consent screen In production (unverified-app caveats: 100-user cap, possible warning tap-through). First true device walk is the owner's install. |
| 7 | Fluid login (identity by Fluid username) | **SHIPPED (v0.10)** | WalletConnect pairing + names-registry ownership verification; remembered session, offline opens preserved. v0.12 adds the embedded Fluid sheet (no app-switching). End-to-end approval proof = owner's first run. |
| 8 | Attachments — send a picture/file | **PARTIAL** | Picker + preview shipped (v0.2); the attachment is noted by name. The model does **not** read its contents — see next row. |
| 9 | Attachment understanding (model reads the image/file) | **NOT BUILT** | No verified keyless multimodal route wired yet; never faked — the app says the attachment is noted, not read. |
| 10 | Image generation | **PARTIAL** | Keyless endpoint verified (Pollinations image, ROUTES.md); no in-app generation UI yet. |
| 11 | Voice in / voice out | **NOT BUILT** (in app) | Core TTS chain exists for video narration (v0.6); no in-app voice input or spoken replies. Android on-device speech is the planned route #1 (ROUTES.md). |
| 12 | Web search | **SHIPPED** | Keyless chain in app (DDG Instant → Wikipedia → Open Library, serving route named) and core. |
| 13 | Browser control | **PARTIAL** | Host lane proven live (v0.8 leg 1); device lane built (v0.9) with PARKED_AT_WALL human handoff. Gap: device lane's first full live run is still the owner's X task; general-purpose browsing from chat prompts is not wired (browser runs as tasks). |
| 14 | Her own social accounts (X; TikTok next) | **PARTIAL** | X creation machinery built, owner-gated, identity kit in build secrets (v0.10.1); the account itself does NOT exist yet — her on-device run stands at the human-check wall (acceptance test, not passed, not failed). TikTok: not started; it is the next acceptance task after X. |
| 15 | Users connecting their own X account | **SHIPPED (machinery, v0.10.1)** | "Connect my X account" packaged task: she opens the login, the user signs in themselves (she never sees the password), she verifies and reports the handle. First live exercise is on-device. |
| 16 | Background / offline tasks | **SHIPPED (v0.4)** | Durable checkpointed engine in app + core; waiting states instead of fake failures; Actions runner lane for code tasks. Gap: survival under Samsung battery optimisation is provable only on the owner's phone (battery exemption advised in OFFLINE.md). |
| 17 | Repo work (clone/read/edit/test/diff/commit/push) | **SHIPPED (core, v0.5)** | Deterministic git wrappers, secret-scan commit block, spec-gated pushes; live proofs in `live-tests/2026-11-24-v05-proof.md`. NOT in the app — core + runner lane only. |
| 18 | Spec-following builds (spec in → graded report out) | **SHIPPED (core, v0.5)** | Evidence-graded criteria, SHIPPED/BLOCKED/NEEDS ETHURAM reports, `Cost: $0` line. Buildathon-size proficiency NOT yet claimed (BENCHMARK.md checklist). Core only. |
| 19 | Video production | **SHIPPED (core, v0.6)** | Code-driven capture + cards + TTS chain + ffmpeg, ffprobe-verified; one real demo shipped. Core only; generative video models are unavailable here and never claimed. |
| 20 | Proactivity (works the queue, reports unprompted) | **SHIPPED (v0.7)** | Commitments register + proactive passes + scheduled lane; live proofs (reports landed ~1 min after watched events, unprompted). |
| 21 | Thoroughness sweeps | **SHIPPED (v0.7)** | Multi-surface keyless sweep with dedup + mandatory coverage note. Standing gap, named in every sweep: X has no keyless route — her own logged-in session is the intended fix (row 14). |
| 22 | Scheduling / recurring jobs | **PARTIAL** | Proactive passes + Actions cron lane exist; no user-facing "every day at 9, do X" scheduler UI in the app yet. |
| 23 | Sub-agents (delegate to child agents) | **NOT BUILT** | No delegation machinery in core or app. Muse's own sub-agents are how some of Lille was built — she cannot do this yet. |
| 24 | Long-term memory / goals system | **PARTIAL** | Core loads SOUL/USER/MEMORY files into the system prompt; the app has no user-visible memory or goals surface, and no on-device memory files yet. |
| 25 | Gmail access | **PARTIAL** | Read-only-first OAuth skeleton in core; live connection is a later milestone — no Gmail data flows today. |
| 26 | Trading (paper first, per TRADING.md) | **NOT BUILT** | Rules written (TRADING.md, 2026-11-26); zero trading tools exist. First proof, when scheduled, is a paper-trading task on real data. Live trading is a separate, unmade decision. |
| 27 | Referrals | **NOT BUILT** | Directed 2026-11-26, queued as v0.13: a personal referral link per Fluid name; a referral completes only when the invitee installs Lille AND logs in with their own Fluid name. |
| 28 | Raw model power | **PARTIAL (structural)** | Her brain is a keyless GPT-OSS 20B (Pollinations) with a community-model fallback (Horde) — frontier models (Claude/GPT class) are paywalled and no key is ever collected, by doctrine. The gap is named, not hidden: engineering (tools, verification, retries) is how she closes what she can of it. |

*Maintenance rule: any release that changes a row updates this
file in the same commit series, and any capability a user asks
about that is missing here gets added — the map being
incomplete is itself a gap.*
