# SETUP.md — phone-first, $0 all the way

**You need NO API key. Nothing to collect, nothing to buy.**
Lille runs on free public **keyless** endpoints out of the box
(see KEYLESS.md). Install the APK (step 1), connect your Fluid
name (step 2), and chat.

**One thing you DO need: a Fluid username.** Lille's login is
your Fluid name (LOGIN.md) — if you don't have one yet, the
app's login screen links you to Fluid to create a wallet and
claim a name first. That is the only "account" Lille will ever
ask for, and it isn't Lille's account — it's yours, in Fluid.

**Golden rule for the optional keys below: never paste any key into a
chat — not this one, not any AI.** A personal key, if you ever add
one, goes into the app's encrypted vault on your phone — nowhere else.

Hosting costs $0 by design (see HOSTING.md): your phone is the host,
GitHub Actions builds the APK and runs the clockwork, Cloudflare
Workers can relay. Oracle is an *optional* extra at the end.

## 1. Get the APK (built free by GitHub Actions)
Open this link on your phone and install what downloads:

**https://raw.githubusercontent.com/Townsvilledh/free-agent/apk/app-debug.apk**

Allow "install unknown apps" for your browser/Files when Android
asks. No Play Store, no fee, no SDK. Every build since v0.9.2
carries the same signing certificate (SIGNING.md), so updates
install over the top — your tasks, sessions and vault survive
them; you never uninstall to update again.

## 2. Connect your Fluid name — then chat
First open shows the login (LOGIN.md): type your Fluid
username → copy the WalletConnect code Lille prints → in
**Fluid → More → WalletConnect** paste it and approve → Lille
opens when the connected wallet proves it owns the name. She
remembers the connection; you only redo this if the session
expires. Then just talk to her. The keyless brain needs no
account — expect free-tier manners: about one request at a time
and up to ~45s between answers on the anonymous tier; the app
paces itself and retries automatically when rate-limited, so a
busy route means she thinks a little longer, not an error.

## 3. OPTIONAL: your own free keys (skip this — the app works without it)
Only if YOU want higher limits or stronger models, with YOUR OWN free
accounts (nobody will ever ask you for these — see KEYLESS.md):
- Groq: groq.com → sign up (no card) → Console → API Keys → create.
- AgentRouter: agentrouter.org → sign in → Console → Tokens → create.
  (Premium models come in daily batches; the daily login check-in
  refills free credit.)
Honest status: the in-app key vault UI is **planned, not yet
shipped** — today personal keys activate only in the Python core
(env vars, below). When the app UI lands, keys will be stored
with Android Keystore encryption (EncryptedSharedPreferences),
never leave the phone except to their own provider, and be
excluded from backups. Until then, the app is keyless-only —
which is the point: it works fully without any key.
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
