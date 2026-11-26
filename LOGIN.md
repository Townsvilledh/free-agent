# LOGIN.md — the Fluid login gate (v0.10)

**Lille is free. She runs on your Fluid name.**

From v0.10, Lille opens with a login — not a password, not an
account with Lille, and never an API key. Your **Fluid
username is your login**: every Lille user is a Fluid user
with a Fluid name. (Same pattern as Ethuram's Global Free
Internet rule: the Fluid username is the access identity.)

## The flow

1. Lille's first screen asks for your **Fluid username**.
2. Lille checks the name against the published **Fluid names
   registry** (`https://fluidwallet.dpdns.org/names.json`,
   Fluid's own fallback mirror if that's unreachable). An
   unknown name — or a name that publishes no EVM address —
   stops here, with a message saying exactly which and what
   to do about it.
3. Lille prints a **WalletConnect pairing code** (a `wc:` URI).
4. You copy it, open **Fluid → More → WalletConnect**, paste
   the code, and approve.
5. The approval reaches Lille over the WalletConnect relay.
   Lille then **verifies**: the wallet that connected must be
   the wallet that owns the typed name — its EVM account must
   equal the name's published `evm` address in the registry
   (compared case-insensitively). WalletConnect alone only
   ever yields an address, never a name; the registry check
   is what ties the two together. Connect a different wallet
   and Lille says *"That wallet doesn't own @name"* and ends
   that session — typing someone else's name does not open
   their Lille.
6. On a match, Lille stores `{username, address}` and opens.

**Remembered:** the WalletConnect SDK persists the session
and Lille persists the verified identity, so later opens pass
the gate from local state alone — **no network round-trip**,
offline included. The login returns only when the session is
gone or expired, with your username prefilled. An approval
that lands while Android has killed the login screen still
completes: the pending attempt is recorded before pairing,
and any later screen reconciles it against the live session.

**Disconnect:** Tasks screen → **Disconnect Fluid name** ends
the session and forgets the identity, for re-testing the gate
from scratch.

## The fine print, plainly

- **EVM only.** Fluid's WalletConnect speaks eip155 only in
  v1, so the session requests exactly one namespace (eip155),
  one chain (`eip155:1`), and one method: **`personal_sign`**.
  No transaction method is requested, ever — the session
  exists to prove which wallet owns a name, nothing more.
  Lille never asks the connected wallet to sign or send
  anything as part of login.
- **An identity + promotion gate, not a security wall.**
  Lille is open source; the gate decides what the shipped
  app does, it cannot make any copy tamper-proof. Its job is
  honest: every honest Lille user arrives with a Fluid name,
  and Lille knows which name she's serving. If the
  WalletConnect stack itself fails to start on a device, a
  previously verified user is still let in (the gate degrades
  to the stored identity rather than locking a person out of
  a free app); a fresh install always needs the full flow.
- **The gate guards the UI only.** Background workers —
  durable tasks, the proactive pass — never consult login
  state. An in-place update landing on a phone with a live
  task does not stall the task; the task engine has no idea
  the gate exists.
- **New to Fluid?** The login screen links to
  https://fluidwallet.dpdns.org — create a wallet, claim your
  name in Fluid (More → Your Fluid name), then come back and
  connect. That ordering is deliberate: Fluid first.
- **Project.** The WalletConnect relay is reached with
  Ethuram's existing Reown projectId (the "Fluid Wallet"
  project created for Fluid's WalletGuide listing) — a public
  client identifier, not a credential. The SDK is the
  official WalletConnect Kotlin SDK (`com.walletconnect:sign`
  2.21.0 + `android-core` 1.23.0).

## Status

Shipped in v0.10.0. The gate logic (registry lookup, address
verification, startup decision) is a pure component
(`FluidGate`) pinned by JVM tests, including a recorded
excerpt of the live registry. The end-to-end approval inside
Fluid is exercised for the first time on Ethuram's phone —
until that run, the phone-side login is labelled **pending
first live proof**, not proven.
