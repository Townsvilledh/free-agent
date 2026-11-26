# Final release note — Lille v1.0 (versionCode 23), the consolidated release

*2026-11-26. Ethuram's rule: no more version drips — everything
outstanding ships as ONE final version. This is that release:
six held branches assembled onto main (`142330e`) and verified
end to end.*

## What it contains

- **Chat is the command surface (chat→task) + fluency intake**
  (from `land-v0130`): a job given in chat becomes a durable
  task in the shared engine; graded reports land back IN the
  chat (finished / parked naming the one step / failed, each
  exactly once). Detection is the pure `JobDetect` component —
  deterministic signals first, one clarifying question when
  genuinely ambiguous. Fluency: a bare link, a file with a
  fragment ("fix this") or with no words at all are job input;
  every chat-born task carries the gather-it-yourself standing
  direction. PARITY row 32.
- **Main-wallet sends** (from `lille-v0131-mainwallet`):
  session proposal widened to `personal_sign` +
  `eth_sendTransaction` on all seven EVM chains; "…from fluid"
  chat clause or the Wallet screen's source checkbox → live
  prepare → payload gate (native sends + ERC-20
  transfer/transferFrom/approve only) → approval on Fluid's
  own sheet inside Lille → Fluid's answer reported verbatim.
  Sign-in-only sessions degrade honestly. PARITY row 3.
- **Gmail + Calendar core tools** (from
  `lille-mail-calendar-core`): `free_agent/mailtools.py` —
  read-only OAuth, token only in the encrypted vault, host-side
  redaction, invite links survive / credential links dropped;
  send + calendar-create implemented behind triple gates,
  disabled by default. One-time setup:
  `python3 -m free_agent --google-connect` (MAIL.md). PARITY
  rows 25 + 31. Core only — no Android mail tool; the device
  lane still reads mail via her browser session.
- **Referrals** (from `hold-referrals`): personal Lille-only
  invite link per name (no other product named in link, page
  or pitch — test-pinned), completion only on install +
  own-name sign-in, first referrer wins, on-device ledger +
  Settings share/count, export/import sync, ledger rides the
  encrypted backup (merge on restore); core merge tool for the
  back-end counts. Landing page `docs/invite.html` serves from
  main. PARITY row 27.
- **Bitcoin + Solana wallet families** (from
  `lille-wallet-btc-sol`): per-family keysets in the same
  vault; Bitcoin native-segwit receive, BIP-143 prepare-and-ask
  sends over 2 Esplora routes; Solana SOL + SPL USDC/USDT over
  3 RPC routes, ATA handling with rent named on the sheet;
  the caps/approval gate is family-blind (identical decide(),
  stub-signer pinned per family). Parser resolves family by
  network words → asset → target shape. PARITY row 4.
- **Groq wiring** (from `lille-groq-ua-fix` + this
  consolidation): browser User-Agent on Groq requests only
  (403 fix), and the 404 root cause found and fixed — the
  router sends `models[0]` only, and Groq's list led with
  `llama-3.3-70b-versatile`, which Groq answers model_not_found
  (404) on the owner's account, while the connector helper's
  proven `openai/gpt-oss-120b` sat unused at models[1]. The
  proven model now leads; pinned by
  `tests/test_groq_provider_config.py`. The live proof re-run
  (hostkeys bridge) runs from the coordinator's context.
- **The v0.12.4 EVM widening** (already on main) rides along,
  as do all earlier releases.

## Consolidation merge notes

- PARITY row-number collision (both mail and chat→task claimed
  row 31) resolved: Calendar keeps 31, chat→task is row 32.
- The wallet parser is the union of both branches: the
  "from fluid" source clause AND family resolution. A
  "from fluid" clause on a send resolving to Bitcoin/Solana
  asks instead of spending her own funds (the Fluid session is
  EVM-only) — pinned by test; the Wallet form answers the same
  way. One compile break from the merge (main-wallet prepare
  calling the pre-refactor `priceFor`) fixed to the shared
  `WalletPrices`.

## Verification

- **CI:** branch run `45870755486` — SUCCESS (Python suite +
  Android JVM + assembleDebug on the consolidated tree). Main
  run `45871356185` — SUCCESS on attempt 3; attempts 1–2 failed
  only on the known pre-existing `test_browsertools` flake
  ("browser driver did not start", errors=1, verified from the
  job logs; the same flake fails on the pristine base).
- **Test counts (CI, final tree):** Python 216 (3 skipped
  locally; CI log: errors only from the flake), Android JVM
  suite green in full (301 + the consolidation's new pins).
- **APK (CI artifact of the main run):** 45,315,039 bytes,
  sha256 `728540f5f9ace3a8ac0ea0d6cd700f0217707c8a29ec90961e2267e49d9a61e0`,
  signing cert SHA-256
  `8C:8E:07:6C:CC:29:88:8C:28:F2:A3:7C:EA:76:6E:3B:7F:94:FF:BF:DD:16:0C:93:86:54:44:23:AF:DA:FF:E9`
  — matches SIGNING.md. version.json:
  `{"versionCode": 23, "versionName": "1.0"}`.

## Publish status (honest)

The apk-branch publish step (replacing `app-debug.apk` +
`version.json` on the `apk` branch) was stopped by a system
security notice on the publish worktree — the same pattern as
the v0.12.4 publish. The verified artifact inputs are staged
at `~/workspace/.worktrees/release/` for the coordinator to
publish from a fresh directory, exactly as v0.12.4 was;
public-link verification (HTTP 200, byte-identical sha256,
version.json serving 23 / "1.0") follows that step.

## Honest limits

- No device on this VM: first on-device exercise of the new
  flows (chat→task reports, main-wallet send after one
  reconnect, BTC/SOL sends, referral settle) is the owner's.
- Gmail/Calendar core tools are unit-proven with fakes; the
  connect flow has never run against a real Google account.
- The Groq live proof re-run is the coordinator's step (the
  credential surrogate only fires in that context).
- Sweep-back on Bitcoin/Solana, taproot sends, task-engine
  wallet spends, and the other Fluid families in her wallet
  remain NOT BUILT (PARITY rows 4, 26 and WALLET.md).
