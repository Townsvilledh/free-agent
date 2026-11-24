# SETUP.md — for Ethuram (phone-first, $0 all the way)

**You need NO API key. Nothing to sign up for, nothing to collect.**
FreeAgent v0.2 runs on free public **keyless** endpoints out of the box
(see KEYLESS.md). Just install the APK (step 1) and chat.

**Golden rule for the optional keys below: never paste any key into a
chat — not this one, not any AI.** A personal key, if you ever add
one, goes into the app's encrypted vault on your phone — nowhere else.

Hosting costs $0 by design (see HOSTING.md): your phone is the host,
GitHub Actions builds the APK and runs the clockwork, Cloudflare
Workers can relay. Oracle is an *optional* extra at the end.

## 1. Get the APK (built free by GitHub Actions)
1. This repo lives in a **public** GitHub repo (public = free,
   unlimited Actions minutes; it is open source anyway). Nothing
   secret is ever committed — that is what makes public safe.
2. Push the repo → open the repo on your phone → **Actions** tab →
   the **build-apk** run → download the `freeagent-debug-apk`
   artifact. (Later, signed builds will be attached to **Releases**.)
3. Install the APK on your A12 (allow "install unknown apps" for your
   browser/Files when Android asks). No Play Store, no fee, no SDK.

## 2. Chat — that's it, you're set up
Open the app and talk to it. The keyless brain answers with no
account. Expect free-tier manners: about one request at a time and
up to ~45s between answers on the anonymous tier — the app queues
and retries automatically when rate-limited.

## 3. OPTIONAL: your own free keys (skip this — the app works without it)
Only if YOU want higher limits or stronger models, with YOUR OWN free
accounts (nobody will ever ask you for these — see KEYLESS.md):
- Groq: groq.com → sign up (no card) → Console → API Keys → create.
- AgentRouter: agentrouter.org → sign in → Console → Tokens → create.
  (Premium models come in daily batches; the daily login check-in
  refills free credit.)
Then, in the app: **Settings → Vault** → unlock → add the key. Stored
with Android Keystore encryption (EncryptedSharedPreferences), never
leaves the phone except to its own provider, excluded from backups.
**Never** put Gmail tokens, passwords, or session cookies in GitHub
Secrets for this project — the Actions jobs need no secrets at all in
the keyless design.

(The Python core's vault flow works the same on any machine:
`free_agent/vault.py`, Fernet-encrypted, 0600 — keys are typed on the
host itself, never sent through a chat. On the Python core, keyed
providers activate from `AGENTROUTER_API_KEY` / `GROQ_API_KEY` env
vars; with neither set, the keyless provider carries everything.)

## 4. Gmail (later, read-only first)
In the app: **Settings → Connect Gmail** → Google consent via AppAuth
showing the **readonly** scope only. The token lands in the Keystore
vault on the phone. Send access stays off unless you explicitly turn
it on, and every send still needs your approval tap.

## 5. Verify (Python core, any machine)
```bash
python -m free_agent --demo
python -m unittest discover tests -v
```

## 6. OPTIONAL later: Oracle Cloud Always Free (Layer 3)
Only if you want 24/7 browser autonomy while your phone is off.
1. cloud.oracle.com → sign up for Always Free (2 OCPU / 12 GB ARM,
   200 GB storage, no expiry, $0 inside Always Free limits).
2. **Blocker, plainly:** Oracle requires a **credit card — or a
   credit-like debit card — for identity**, with a ~$1 temporary hold;
   prepaid/virtual cards are often refused. If no card passes, this
   layer stays off and nothing is lost — v1 runs fully without it.
3. Also know: **idle free instances can be reclaimed** (low CPU +
   network + memory over 7 days). Keep backups of `memory/` (and an
   encrypted copy of the vault) on your phone regardless.
