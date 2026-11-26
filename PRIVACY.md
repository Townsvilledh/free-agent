# Privacy Policy — Lille

**Last updated: 26 November 2026**

Lille is a free, open-source (MIT) Android app. This policy is
short because there is very little to say: **Lille has no
servers, no accounts of its own, no telemetry, no analytics,
and no ads. The developers receive no data about you or your
use of the app — there is nowhere for it to go.** The full
source code is public in this repository, so you don't have
to take our word for any of this.

## Your identity: your Fluid name

Lille's only sign-in is your **Fluid username**, connected
through WalletConnect. Lille asks your Fluid wallet to
approve the connection and to sign messages (`personal_sign`
on Ethereum/eip155) — that is the only thing the connection
can ever do. **It cannot move funds or make transactions.**
Lille never sees your wallet's seed phrase or private keys;
those never leave Fluid.

## Your backups stay yours

If you set up backup, Lille creates it **on your phone** and
encrypts it on your phone (AES-256-GCM) with a key derived
from your own Fluid wallet's signature — or, in fallback
mode, from a passphrase you choose. The encrypted backup
contains your chats, tasks, app settings, your Lille
identity record (your Fluid username and address), and the
app's local vault.

It is stored only in places you control:

- a folder **you choose** on your device, and
- optionally, your **own Google Drive**, in the app's
  private app-data folder.

The backup is encrypted before it leaves the app. Google
stores only the locked file — it cannot read the contents,
and neither can we: the developers never receive, hold, or
can decrypt your backup. Lose the Fluid wallet (or the
fallback passphrase) and the backup cannot be opened by
anyone, including us.

## The AI brain is a public service

Lille's brain runs on free public AI endpoints (see
KEYLESS.md). When you chat or give Lille a task, the content
you type is sent to those public services so they can answer
— that's how the app works, and no account or API key is
involved. Because those services are public, **don't put
secrets — passwords, keys, private documents — into chats or
tasks.** Any credentials Lille itself holds (for example, a
password she generated for a task) live only in the encrypted
vault on your device, and leave it only inside your own
encrypted backup.

If you optionally add your own free API key (for example
Groq or AgentRouter), it is stored only in that same
encrypted on-device vault and is sent only to its own
provider.

## Browsing and connected accounts

Lille's in-app browser keeps its sessions **on your device
only**. When a task needs you to sign in to a site (for
example, connecting your X account), **you type your own
login yourself** in Lille's browser — Lille never sees,
asks for, or stores your passwords for third-party sites.

## Changes and contact

If this policy changes, the change will appear here, in the
open, in this repository's history. Questions or concerns:
open an issue at
<https://github.com/Townsvilledh/free-agent/issues>.
