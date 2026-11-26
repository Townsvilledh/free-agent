"""Referral export merge tool (free_agent/referrals.py):
the back-end half of Lille invites. Its semantics must
mirror the Android pure component (Referrals.kt) exactly —
names normalised, self-referrals dropped, a referred name
counted once with the earliest completion standing (first
referrer wins) — so a merge here and a merge on a phone
always agree."""

import json
import unittest

from free_agent import referrals


def export(records):
    return json.dumps({"version": 1, "records": records})


class TestParseRecords(unittest.TestCase):
    def test_normalises_and_drops_junk(self):
        text = export([
            {"referrer": "@Alice", "referred": "Carol", "at": 300, "source": "link"},
            {"referrer": "carol", "referred": "carol", "at": 100, "source": "name"},
            {"referrer": "", "referred": "dan", "at": 100, "source": "name"},
            {"referrer": "bob", "referred": "dan", "at": "soon", "source": "name"},
        ])
        records = referrals.parse_records(text)
        self.assertEqual(2, len(records))
        self.assertEqual("alice", records[1]["referrer"])
        self.assertEqual("carol", records[1]["referred"])
        self.assertEqual(0, records[0]["at"])  # bad timestamp -> 0, never a crash

    def test_unreadable_documents_yield_nothing(self):
        self.assertEqual([], referrals.parse_records("not json"))
        self.assertEqual([], referrals.parse_records('["a", "list"]'))
        self.assertEqual([], referrals.parse_records('{"records": 42}'))

    def test_duplicates_collapse_to_earliest(self):
        text = export([
            {"referrer": "alice", "referred": "carol", "at": 900, "source": "name"},
            {"referrer": "bob", "referred": "carol", "at": 300, "source": "link"},
        ])
        records = referrals.parse_records(text)
        self.assertEqual(1, len(records))
        self.assertEqual("bob", records[0]["referrer"])


class TestMergeExports(unittest.TestCase):
    def test_first_referrer_wins_across_files(self):
        a = export([{"referrer": "alice", "referred": "carol", "at": 500, "source": "name"}])
        b = export([
            {"referrer": "bob", "referred": "carol", "at": 300, "source": "link"},
            {"referrer": "alice", "referred": "dan", "at": 700, "source": "link"},
        ])
        ledger = referrals.merge_exports([a, b])
        self.assertEqual(2, len(ledger["records"]))
        carol = [r for r in ledger["records"] if r["referred"] == "carol"][0]
        self.assertEqual("bob", carol["referrer"])

    def test_counts_are_the_grading_input(self):
        a = export([
            {"referrer": "alice", "referred": "carol", "at": 1, "source": "name"},
            {"referrer": "alice", "referred": "dan", "at": 2, "source": "name"},
            {"referrer": "bob", "referred": "erin", "at": 3, "source": "link"},
        ])
        ledger = referrals.merge_exports([a])
        self.assertEqual({"alice": 2, "bob": 1}, referrals.counts(ledger))


if __name__ == "__main__":
    unittest.main()
