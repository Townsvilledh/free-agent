# WALLET.md — Lille's agentic wallet (v0.12; chains widened v0.12.4)

*What her wallet is, how money moves through it, and what she can
never do — in plain words. Design decisions trace to the
Lille-in-Fluid spec v0.2 (companion wallet) and Ethuram's
refinements of 2026-11-26.*

## One agentic wallet, not two

There is **one agentic wallet per user**. The wallet in this app
IS the companion wallet the Lille-in-Fluid spec describes — the
same wallet concept and record, connected to the user's verified
Fluid identity — not a Lille-only invention that would conflict
with the in-Fluid one later. It lives in Lille today because
Lille is the agentic wallet's phone home: Fluid is a web wallet
with no native app, and Lille is the app on the phone. The main
Fluid wallet stays separate — it is the user's identity and the
embedded-sheet bridge (below), never merged into her wallet, and
she can never touch it. Her wallet's portability path is her
vault: the key rides inside the v0.11 encrypted backup, so the
same wallet restores on a new phone with the user's Fluid
identity.

## Where the key lives

Her EVM keypair is generated on the phone (SecureRandom) the
moment the user taps **Create Lille's wallet** — an explicit
user action, never automatic. The private key exists in exactly
one place: an entry in her Keystore-wrapped encrypted vault
(`DeviceVault`), the same vault that holds her other secrets.
It is never written to chat, task records, logs, or the backup
plaintext; the backup carries it only inside the sealed,
encrypted payload (the v0.11 backup seals `vault.exportSecrets()`
— verified in code, `BackupManager` — and restores through
`importSecrets`). The only code that ever reads the key is the
signing path in `WalletEngine`, at the moment of signing, and
the key bytes are zeroed immediately after. Creating a wallet
twice never replaces the key — the existing address comes back.

## No app-switching anywhere in her wallet's life

Create, view balances, prepare, approve, sign, sweep-back — the
whole life of her wallet happens **inside Lille's own UI**. No
step sends the user to Fluid or to a browser. The one exception
is funding, and it is inbound only: the user sends funds TO her
address from wherever their money already is; her screen simply
shows the address with a copy button. Everything after that is
hers, in-app.

## How a send works (prepare-and-ask)

Money moves only through a deterministic path — never through
the model's free text:

1. **Parse.** A chat prompt is matched by `WalletPrompt`, a
   strict deterministic parser. The supported phrasings are
   exactly: `send|pay <amount> <ASSET> to <target> [on <chain>]`,
   `send|pay $<amount> of <ASSET> to <target> [on <chain>]`, and
   `send|pay <amount> dollars of <ASSET> to <target> [on
   <chain>]`, with ASSET ∈ {ETH, USDC, USDT, WETH, BNB, POL,
   AVAX, WAVAX} (checked against the chosen chain — an asset
   she holds on one chain is not spendable on another), target
   a 0x address or a Fluid name (`@name` or bare), chain ∈
   {ethereum, mainnet, base, arbitrum, polygon, bsc, bnb,
   avalanche, avax, optimism} (default Ethereum). A send-shaped
   message that doesn't match exactly gets a question back —
   never a guess. The Wallet screen's send form feeds the same
   pipeline, and its asset list follows the selected chain.
2. **Prepare.** `WalletEngine` resolves the recipient (Fluid
   names through the published names registry, primary +
   fallback fetch), reads live balances, nonce and fees from
   public RPCs (≥2 routes per chain, serving source named), and
   prices the send in USD through the price chain (CoinGecko →
   Coinbase → Kraken). If the value can't be priced, the send is
   labelled UNPRICED and can only proceed with the user's
   explicit raw-amount confirmation on the approval sheet.
3. **Gate.** Before any key is touched, `WalletGate.decide()`
   runs — deterministic code, not the model. It checks, in
   order: valid recipient and amount; sufficient balance for
   amount AND fee; the user's caps (below); and the approval
   rule. Its verdict is Allow, NeedsApproval, or Refused with a
   plain-language reason.
4. **Approve.** Unless the gate allows outright (autonomous
   mode, below), an in-app approval sheet shows the exact send
   in plain language — amount, asset, recipient, chain, USD
   value, network fee, and which wallet it leaves. The Approve
   tap is the only source of approval in the app; there is no
   "approve later by replying yes" path.
5. **Sign + broadcast + verify.** The signer assembles the
   transaction in the chain's own shape — EIP-1559 everywhere
   except BNB Smart Chain, which still runs legacy gas
   pricing and signs a type-0 EIP-155 transaction — signs it
   with her key (in memory, zeroed after), broadcasts through
   the RPC fallback chain, and polls for the receipt. The
   report carries the transaction hash; a broadcast whose
   receipt doesn't land says so honestly and still gives the
   hash to check.

Every step lands in her wallet activity list — created,
prepared, refused (with the reason), sent, confirmed, failed,
swept — marked as hers, separate from any Fluid activity.

## Chains (v0.12.4)

One keyset, every EVM chain — her address is the same on all
of them. Seven chains, each with at least two public RPC
routes walked in order (the serving source is named in her
balance list; a failed read shows "couldn't load", never a
fake zero):

| Chain | Native | Tokens | Gas |
|---|---|---|---|
| Ethereum (1) | ETH | ETH, USDC, USDT, WETH | EIP-1559 |
| Base (8453) | ETH | ETH, USDC, USDT, WETH | EIP-1559 |
| Arbitrum One (42161) | ETH | ETH, USDC, USDT, WETH | EIP-1559 |
| Polygon (137) | POL | POL, USDC, USDT, WETH | EIP-1559 |
| BNB Smart Chain (56) | BNB | BNB, USDC, USDT | **legacy** |
| Avalanche C-Chain (43114) | AVAX | AVAX, USDC, USDT, WAVAX | EIP-1559 |
| Optimism (10) | ETH | ETH, USDC, USDT, WETH | EIP-1559 |

Every token contract and its decimals were verified **on
chain** on 2026-11-26 — `decimals()` and `symbol()` read from
each chain's own public RPC before the address was allowed
into the table, and the table is pinned by JVM tests. The
verification caught real traps: the stables on BNB Smart
Chain are **18-decimal** (a 6-decimal assumption would
misprice every BSC stable send by 10¹²), and Polygon's native
asset is **POL** (the gas token since the 2024 upgrade —
MATIC is the retired name). Prices come through the same
price chain (CoinGecko → Coinbase → Kraken), extended to the
new natives.

## Caps are the user's dial, not a cage

First-run defaults are **$5 per transaction / $20 per day**
(rolling 24 hours) — a starting point, nothing more. In the
wallet settings the user can raise either cap to any amount or
switch it off entirely ("no limit", with one clear warning line
shown next to the choice). Whatever the user sets is enforced
deterministically **in the signing path** (`WalletGate`), on
every send, whatever any prompt says — the model cannot talk
its way past a cap, and neither can a web page she read. Daily
totals persist across restarts. Unpriced sends never count
against the caps (there is no honest number to count) and are
never autonomous. Sweep-back skips the caps entirely — capping
a sweep could trap the user's own money in her wallet — but
always requires approval.

## The autonomous switch

Settings carry one switch, **default OFF**: *"Let Lille spend
within these caps without asking each time."* With it ON, a
prepared send that passes the gate (priced, within the user's
caps, balance sufficient) signs and broadcasts without the
approval sheet — and is still cap-checked, still written to the
activity list, and still reported in the chat/task record after
the fact. The switch is the user's explicit act in settings;
it can never be enabled from a prompt, a task, or a web page.

## Sweep-back

**Return funds to my Fluid wallet** prepares a send-all — every
token with a balance, then the native remainder after fees — to
the user's verified Fluid address from the identity record
(the address their login proved they own). It is always
prepare-and-ask, even with the autonomous switch on.

## Threat model, in plain words

Her chat surface is **untrusted input**: users paste text from
anywhere, web pages she reads can contain instructions, and the
model itself can be talked into things. That is exactly why:

- **the key is separate.** Her wallet is not the user's Fluid
  wallet and shares no fate with it. The worst case is bounded
  by what the user chose to fund her with.
- **caps live in the signer, not the model.** The gate is
  deterministic code that runs after the model has finished
  talking. A prompt — hers, the user's, or one injected into a
  page she read — cannot raise a cap, skip the sheet, or flip
  the autonomous switch.
- **money only moves through the tested path.** The strict
  parser, the preparer, and the gate are pinned by JVM tests,
  including a stub-signer test proving no signature is produced
  without the gate's Allow.

What she can **never** do:

- Touch the main Fluid wallet — no key, no session method, no
  code path. Her WalletConnect session is identity-only
  (`personal_sign`); it cannot sign transactions.
- Exceed the caps the user set, or spend unapproved while the
  autonomous switch is off.
- Spend from an empty or unpriced position silently — unpriced
  sends need the raw-amount confirmation, and insufficient
  balance for amount or fee refuses at prepare time.
- Hide a spend: every outcome is in the activity list.

## The embedded Fluid sheet (login approvals)

"Sign where you stand" (Ethuram, 2026-11-26): approving
Lille's login pairing used to mean copy the code → switch to
Fluid → paste → approve → switch back. The login screen now
also offers **Open Fluid inside Lille**: the pairing code is
placed on the clipboard automatically, and Fluid's own web app
loads in an in-app sheet (`FluidSheetActivity`) — the user
unlocks Fluid and approves without leaving Lille. Fluid has
**no `wc:` deep link** today (checked against its source
2026-11-26: its WalletConnect panel is paste-only), so the
sheet carries the clipboard flow rather than a deep link;
PARITY.md records the Fluid-side follow-up. One honest caveat:
the embedded browser's storage is separate from Chrome/the
installed Fluid, so a user whose wallet lives elsewhere may
need to create or import it inside the sheet once — it then
persists there.

**Trust boundary:** the sheet is a plain container. Lille
installs no JavaScript bridge, injects no script, and never
reads keystrokes, form values, or page content inside Fluid.
Fluid's keys stay Fluid's.

## Not built (honest)

- Task-engine wallet tools: her background tasks cannot spend
  yet — wallet sends run from chat prompts and the Wallet
  screen only.
- Non-EVM chains: her wallet is EVM-only — the seven chains
  above. Bitcoin, Solana and the other Fluid families are NOT
  BUILT in her wallet; PARITY.md carries them as named rows,
  not as fine print.
- Main-wallet transaction approvals from inside Lille: NOT
  BUILT — see the first-class row in PARITY.md.
