# LESSONS.md — from Ethuram's first agent

Before Lille, Ethuram built his own universal autonomous agent: a single
Groq-powered Python script (~1,400 lines) with a tool registry, Solana
trading, strategy generation and simulation. The aim was always a
*universal* agent — trading was simply the hardest task class, so it got
the deepest dive. It "didn't turn out well". This file is the autopsy,
kept so that anyone building on Lille — human or agent — inherits the
lessons and not just the code.

## What the first agent got right (keep these instincts)

- **A tool registry with schemas.** The model chose from declared tools
  with typed parameters. That is the same skeleton Lille's loop runs on.
- **Fallbacks for data.** Prices were fetched CoinGecko → Jupiter → Pyth,
  with a cache. One source is no source — an embryo of the Fallback Law.
- **An approval gate for live trading**, per token, not global. A baby
  Sentinel.
- **Simulation before any live trade**, and transaction simulation before
  broadcasting. Verify before commit.
- **Clamping the model's numbers.** Strategy parameters proposed by the
  LLM were forced into safe ranges. The model's output is a *proposal*,
  never an instruction — this instinct now runs through all of Lille,
  down to wallet caps enforced in the signing path.

## The failure modes (each one is now a Lille law)

1. **One brain, one key, total death.** Everything hung on a single
   `GROQ_API_KEY`; if the client failed to initialise, the agent exited —
   and the failure mode Ethuram actually met was a *silent* exit (no
   entry point, errors buried in a log file).
   → Lille: keyless multi-route brain with fallbacks and cooldowns;
   honest surfaces — she reports, she never vanishes.
2. **Secrets through the chat.** The script accepted a raw Solana private
   key typed as a prompt and swapped wallets with it.
   → Lille: an encrypted vault; secrets are typed *by key name only* and
   never enter model context, traces, or logs.
3. **A blocklist sandbox.** `execute_code` banned imports and a few
   function names around `exec` — hand-rolled restrictions that are
   escapable through the object graph.
   → Lille: OS-level isolation for code execution. Safety is structural,
   not a list of forbidden words.
4. **A simulator grading itself on invented data.** Prices were generated
   by a random walk; "sentiment" was a random number; a stub function
   returned a hardcoded sentence as if it were social data. The strategy
   optimiser then "learned" from those results — the agent could
   manufacture evidence of an edge that did not exist.
   → Lille: verify by state, grade from evidence. Simulation is a
   rehearsal, never a result. A stub must say it is a stub. (See
   TRADING.md.)
5. **Nothing survived a restart.** Context, performance history and
   approvals lived in RAM.
   → Lille: the durable task engine — checkpoints after every step,
   resume in a new process, waiting states instead of fake failures.
6. **Success decided by string-matching** (`"executed" in result`).
   Wording changes; state doesn't.
   → Lille: outcomes are graded from state and receipts, never from a
   tool's phrasing.
7. **A monolith with no tests.** One file, ~1,400 lines; a syntax error
   at line 1449 took a whole debugging saga, and every fix risked
   breaking something else.
   → Lille: small modules behind a full test suite, and no release is
   claimed shipped until it has been exercised live, end to end.

## The summary

The first agent was not a failure of aim — it was a first draft of the
right agent. Lille is what happens when each of its failure modes gets a
law instead of a patch.
