"""Referral ledger tools (REFERRALS.md) — the back-end half
of Lille invites.

The Android app keeps the referral ledger on-device: a
referral completes when an invited person installs Lille
and signs in with their own name, and the completed records
are the ledger's content. Those records reach the operator
as EXPORTS — the JSON document the app's Settings → Export
invite list writes:

    {"version": 1, "records": [
        {"referrer": "alice", "referred": "carol",
         "at": 1732700000000, "source": "link"}, ...]}

This module is the merge/count tool for those exports. Its
semantics mirror the app's pure component
(android/.../Referrals.kt) exactly, so an export merged on
a phone and the same exports merged here agree:

  * names are normalised (trim, lowercase, drop a leading @)
  * self-referrals and blank names are dropped
  * a referred name appears at most once — on a clash the
    EARLIEST completion stands (first referrer wins)

`counts()` over the merged records is the confirmed-invite
count per name — the back-end grading input the feature
exists to produce. Nothing here talks to a network or a
wallet; it reads files the operator collected and prints
JSON.

    python3 -m free_agent.referrals export1.json [export2.json ...]
        -> {"records": [...], "counts": {"alice": 2, ...}}
"""

from __future__ import annotations

import json
import sys

LEDGER_VERSION = 1


def normalise_name(raw: object) -> str:
    """Same rule as the app's FluidGate.normaliseName."""
    if not isinstance(raw, str):
        return ""
    name = raw.strip().lower()
    if name.startswith("@"):
        name = name[1:]
    return name


def parse_records(text: str) -> list[dict]:
    """Validated records out of one export document.

    An unreadable document yields no records (never an
    exception into the operator's hands); every record is
    re-validated and the document's duplicates collapse to
    the earliest completion per referred name.
    """
    try:
        root = json.loads(text)
    except Exception:
        return []
    if not isinstance(root, dict):
        return []
    raw_records = root.get("records")
    if not isinstance(raw_records, list):
        return []
    by_referred: dict[str, dict] = {}
    for item in raw_records:
        if not isinstance(item, dict):
            continue
        referrer = normalise_name(item.get("referrer"))
        referred = normalise_name(item.get("referred"))
        if not referrer or not referred or referrer == referred:
            continue
        try:
            at = int(item.get("at") or 0)
        except (TypeError, ValueError):
            at = 0
        record = {
            "referrer": referrer,
            "referred": referred,
            "at": at,
            "source": item.get("source") if isinstance(item.get("source"), str) else "name",
        }
        existing = by_referred.get(referred)
        if existing is None or at < existing["at"]:
            by_referred[referred] = record
    return sorted(by_referred.values(), key=lambda r: r["at"])


def merge_exports(texts: list[str]) -> dict:
    """Merge export documents into one ledger document.

    Union of records; a clash for the same referred name is
    won by the earliest completion — across files, the same
    first-referrer-wins rule the app applies on-device.
    """
    by_referred: dict[str, dict] = {}
    for text in texts:
        for record in parse_records(text):
            existing = by_referred.get(record["referred"])
            if existing is None or record["at"] < existing["at"]:
                by_referred[record["referred"]] = record
    records = sorted(by_referred.values(), key=lambda r: r["at"])
    return {"version": LEDGER_VERSION, "records": records}


def counts(ledger: dict) -> dict[str, int]:
    """Confirmed invites per referrer name — the grading
    input. Sorted by count (desc), then name, for stable
    operator output."""
    tally: dict[str, int] = {}
    for record in ledger.get("records", []):
        tally[record["referrer"]] = tally.get(record["referrer"], 0) + 1
    return dict(sorted(tally.items(), key=lambda kv: (-kv[1], kv[0])))


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    texts = []
    for path in argv:
        with open(path, encoding="utf-8") as handle:
            texts.append(handle.read())
    ledger = merge_exports(texts)
    print(json.dumps({"records": ledger["records"], "counts": counts(ledger)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
