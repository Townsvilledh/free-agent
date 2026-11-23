# SETUP.md — for Ethuram (phone-first, $0 all the way)

**Golden rule: never paste any key into a chat — not this one, not any AI.**
Keys go into the app's encrypted vault on your phone, or into GitHub
Secrets for the non-private Actions jobs — nowhere else.

Hosting costs $0 by design (see HOSTING.md): your phone is the host,
GitHub Actions builds the APK and runs the clockwork, Cloudflare
Workers can relay. Oracle is an *optional* extra at the end.

## 1. Groq free key (fallback brain)
1. On your phone, open groq.com → sign up (no card for the free tier).
2. Console → API Keys → create one. Keep it ready for step 4.

## 2. AgentRouter key (primary brain)
1. Open agentrouter.org → register / sign in (GitHub sign-in works).
2. Note your free credit balance in the console.
3. Console → Tokens → create a token (`sk-...`). Keep it for step 4.
4. Remember: premium models are released in daily batches, and the
   daily login check-in is what refills free credit — the app/Actions
   jobs are designed around that rhythm (the real login session lives
   on your phone, never on GitHub).

## 3. Get the APK (built free by GitHub Actions)
1. This repo lives in a **public** GitHub repo (public = free,
   unlimited Actions minutes; it is open source anyway). Nothing
   secret is ever committed — that is what makes public safe.
2. Push the repo → open the repo on your phone → **Actions** tab →
   the **build-apk** run → download the `freeagent-debug-apk`
   artifact. (Later, signed builds will be attached to **Releases**.)
3. Install the APK on your A12 (allow "install unknown apps" for your
   browser/Files when Android asks). No Play Store, no fee, no SDK.

## 4. Put keys in the vault (on your phone, never in chat)
In the app: **Settings → Vault** → unlock → add `AGENTROUTER_API_KEY`
and `GROQ_API_KEY`. They are stored with Android Keystore encryption
(EncryptedSharedPreferences), never leave the phone except to their
own provider, and are excluded from backups.

For the scheduled Actions jobs only: repo **Settings → Secrets and
variables → Actions** → add the secrets a *non-private* job needs
(currently the check-in heartbeat only checks that the AgentRouter
secret exists — it never prints it). **Never** put Gmail tokens,
passwords, or session cookies in GitHub Secrets for this project.

(The Python core's vault flow from v0.1 still works on any machine:
`free_agent/vault.py`, Fernet-encrypted, 0600 — same rule: keys are
typed on the host itself, never sent through a chat.)

## 5. Gmail (later, read-only first)
In the app: **Settings → Connect Gmail** → Google consent via AppAuth
showing the **readonly** scope only. The token lands in the Keystore
vault on the phone. Send access stays off unless you explicitly turn
it on, and every send still needs your approval tap.

## 6. Verify (Python core, any machine)
```bash
python -m free_agent --demo
python -m unittest discover tests -v
```

## 7. OPTIONAL later: Oracle Cloud Always Free (Layer 3)
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
