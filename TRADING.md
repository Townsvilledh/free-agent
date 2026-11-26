# TRADING.md — trading as a job class

Trading is one job class among many for Lille — no more, no less. It does
not get to swallow the agent (Ethuram's first agent drowned in it), and it
does not get skipped. Like every job class, proficiency is *measured on
real tasks*, never asserted. These rules exist because Ethuram's first
agent taught each one the hard way (see LESSONS.md).

## The rules

1. **Real data or no data.** Prices, history and market data come from
   live sources, served through fallback chains (the Fallback Law), with
   the serving source named in every report. If a source is a stub,
   a placeholder, or down, the report says so. Nothing is ever presented
   as market data that did not come from a market.
2. **Simulation is a rehearsal, not a result.** Paper trading and
   backtests run on *real* historical/live data — never on generated
   price paths. Every simulation output is labelled PAPER in the report,
   and simulation numbers never feed a claim about live performance,
   never tune a live strategy silently, and never appear without their
   data source and period.
3. **Sentiment and signals must be real or absent.** A signal input is
   either computed from a named, reachable source, or the strategy runs
   without it and the report says it ran without it. No hardcoded
   "sentiment", no random scores.
4. **Dry-run before anything irreversible.** Any live order is simulated
   first (route/quote/simulation checked), and the dry-run evidence is
   part of the task record.
5. **Approval is per asset and per action.** Following the first agent's
   one good gate: live trading for a token is approved for *that token*,
   never as a global switch. Approval lives with the Sentinel, not the
   model.
6. **Caps are enforced where signing happens**, deterministically —
   position size, daily totals, slippage bounds — and model-proposed
   numbers are clamped into the approved ranges before use, exactly as
   the first agent clamped its strategy parameters.
7. **Outcomes are read from state.** Balances and transaction receipts
   decide what happened. A tool's wording never does.
8. **Keys never travel.** Trading credentials live in the vault and are
   used by name. No key is ever typed into a chat, a prompt, or a log.
9. **The honest ceiling.** No agent out-thinks a market that learns.
   Lille's trading reports state results plainly — wins, losses, and the
   sample size — and never promise profit. Risk discipline and truthful
   measurement are the product; anyone promising the profit is selling
   the fiction the first agent's simulator accidentally told itself.

## Status

Rules written 2026-11-26. Job class **not yet proven**: no live Lille
trading task has been run or graded. First proof, when scheduled, is a
paper-trading task on real data, graded from state, reported with its
sources — before any live task is even proposed.
