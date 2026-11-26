# Referrals — Lille invites

*Directed by Ethuram 2026-11-26, corrected the same day. Built
build-and-hold on branch `hold-referrals`; it ships inside the
consolidated final release, not as its own version.*

## The design

Every Lille user gets a personal invite link:

```
https://townsvilledh.github.io/free-agent/invite.html?ref=<name>
```

The link, its landing page (`docs/invite.html`) and the share
pitch are **purely about Lille** — no other product is named
or branded anywhere in them, and the URL contains no other
product's name. (The Android tests pin this: the link and the
pitch must never contain the login product's name.) Publicly,
a referral is simply "invite people to Lille."

A referral **completes** only when the invited person:

1. installs Lille, **and**
2. signs in with **their own** name — the sign-in is the
   proof, because the login gate verifies the connected
   wallet owns the name (LOGIN.md). A download alone, or a
   name typed anywhere else, completes nothing.

Rules, enforced in the pure component (`Referrals.kt`) and
mirrored in the core tool:

- **First referrer wins.** A name can be referred once, ever.
- **No self-referral.** Referrer ≠ referred, after the usual
  name normalisation (trim, lowercase, drop a leading `@`).
- The count a user sees is **completed referrals only** —
  never clicks, downloads or pending invites.

## How the inviter's name reaches a fresh install

A link opened on a phone without Lille goes to the landing
page in a browser; installing the APK breaks any automatic
attribution chain (there is no store referrer API for a
sideloaded app). So:

- The landing page shows the inviter's name and asks the new
  user to enter it when Lille asks who invited them — one
  optional field on the login screen ("Invited by a friend?
  Their Lille name (optional)").
- If Lille **is** already installed, the same link reaches
  the app directly: the https form is a verified App Link
  (`docs/.well-known/assetlinks.json`, the stable signing
  certificate), and the page's "open in the app" button uses
  the `lille://invite?ref=<name>` scheme as the unverified
  path. The app records the inviter as **pending** (first
  touch wins) and pre-fills the login field; the user can
  edit or clear it. Capture happens only while no verified
  identity stands on the device — an invite is for a new
  user, and a signed-in user opening a link changes nothing.
- At the first successful sign-in, the field's contents (or
  the pending inviter behind it) settle into a completed
  record. Settle is idempotent: MainActivity re-runs it on
  open, and once a record exists for a name, nothing can
  add or change it.

## The ledger (on-device)

One JSON document, `filesDir/lille_referrals.json`, written
atomically (the ChatStore discipline). All shape decisions
live in the pure component `Referrals`; `ReferralStore`
owns only the file.

```json
{
  "version": 1,
  "pending": {"referrer": "alice", "source": "link", "at": 1732700000000},
  "records": [
    {"referrer": "alice", "referred": "carol", "at": 1732700000000, "source": "name"}
  ]
}
```

`source` is `link` (arrived by invite link) or `name` (typed
on the login screen). The ledger rides the v0.11 encrypted
backup as BackupPayload's `referrals` field — merged, never
overwritten, on restore, so imports made after a backup
survive it.

## The sync path (export / import)

Completed records are recorded locally; they move by file:

- **Settings → Export invite list** writes the ledger's
  records (never the pending slot) as JSON to a file the
  user picks. **Settings → Import invite list** merges a
  file back in — union of records, earliest completion wins
  on a clash — and the count refreshes.
- The framed user story is a user with two phones keeping
  their count in step. The same export is the back-end
  count's input: the operator collects exports, and the
  core tool merges them with identical semantics —

  ```
  python3 -m free_agent.referrals export1.json [export2.json ...]
  -> {"records": [...], "counts": {"alice": 2, ...}}
  ```

  `counts` — confirmed completed invites per name — is the
  activity-grading input the feature exists to produce.
  None of this bookkeeping is mentioned in any user-facing
  copy: in the app it is only ever "invite friends to Lille."

## What is NOT built (honest edges)

- No server: counts on a device reflect the records that
  device holds (its own completion + whatever it has
  imported). There is no live cross-device sync in this
  build; the file path above is the sync.
- The landing page goes live when this branch lands on
  main (Pages serves `docs/` from main) — until then the
  link format is final but the page is not yet served.
- Attribution for a fresh install rests on the new user
  entering the inviter's name, as the landing page asks.
  That is the honest limit of a sideloaded app with no
  store referrer; nothing fakes a stronger chain.
