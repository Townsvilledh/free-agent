"""v0.7 Thoroughness Law tests — sweep capability (bar 9).

Covers the acceptance points: multi-phrasing query generation,
dedup correctness against the persisted known-items record
(incl. URL normalisation), the mandatory coverage note enforced
by the report validator, and not-checked surfacing when a
surface errors (never a silent skip).
"""
import os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from free_agent import sweep as sw


def fake_surfaces(web_items=None, extra_items=None, broken=None):
    """Deterministic offline surfaces. `broken` maps a surface name
    to the error it should raise."""
    def mk(name, items):
        def fn(topic, phrasings):
            if broken and name in broken:
                raise sw.SurfaceError(broken[name])
            return list(items or [])
        return fn
    return [
        ("web", mk("web", web_items)),
        ("devpost", mk("devpost", extra_items)),
        ("x", mk("x", [])),
    ]


ITEMS_A = [
    {"title": "Alpha Hackathon", "url": "https://devpost.com/hackathons/alpha",
     "snippet": "Online"},
    {"title": "Beta Challenge", "url": "https://example.org/beta",
     "snippet": ""},
]


class TestPhrasings(unittest.TestCase):
    def test_multiple_distinct_phrasings(self):
        phr = sw.generate_phrasings("hackathons open for solo online entry",
                                    year=2026)
        self.assertGreaterEqual(len(phr), 4)
        self.assertEqual(len(phr), len(set(phr)))  # all distinct
        self.assertEqual(phr[0], "hackathons open for solo online entry")
        self.assertTrue(any("2026" in p for p in phr))
        self.assertTrue(any("apply" in p for p in phr))

    def test_deterministic(self):
        self.assertEqual(sw.generate_phrasings("grants for musicians", 2026),
                         sw.generate_phrasings("grants for musicians", 2026))

    def test_keywords_strip_stopwords(self):
        self.assertEqual(sw.keywords("hackathons open for solo online entry"),
                         "hackathons online")


class TestDedup(unittest.TestCase):
    def setUp(self):
        self.ws = tempfile.mkdtemp(prefix="lille-sweep-")

    def tearDown(self):
        shutil.rmtree(self.ws, ignore_errors=True)

    def test_second_identical_sweep_reports_zero_new(self):
        surfaces = fake_surfaces(web_items=ITEMS_A)
        _, s1 = sw.run_sweep("hackathons", self.ws, surfaces=surfaces)
        self.assertEqual((s1["new"], s1["known"]), (2, 0))
        _, s2 = sw.run_sweep("hackathons", self.ws, surfaces=surfaces)
        self.assertEqual((s2["new"], s2["known"]), (0, 2))
        # The record persisted across runs (fresh store instance).
        self.assertEqual(len(sw.KnownItems(self.ws)), 2)

    def test_url_variants_dedup_to_one_item(self):
        variant = [{"title": "Beta Challenge",
                    "url": "https://www.example.org/beta/?utm_source=x",
                    "snippet": ""}]
        sw.run_sweep("hackathons", self.ws,
                     surfaces=fake_surfaces(web_items=ITEMS_A))
        _, s = sw.run_sweep("hackathons", self.ws,
                            surfaces=fake_surfaces(web_items=variant))
        self.assertEqual(s["new"], 0)  # same fingerprint after normalising

    def test_within_sweep_duplicates_merge(self):
        dup = ITEMS_A + [dict(ITEMS_A[0])]
        _, s = sw.run_sweep("hackathons", self.ws,
                            surfaces=fake_surfaces(web_items=dup))
        self.assertEqual(s["new"], 2)


class TestCoverageNote(unittest.TestCase):
    def setUp(self):
        self.ws = tempfile.mkdtemp(prefix="lille-sweep-")

    def tearDown(self):
        shutil.rmtree(self.ws, ignore_errors=True)

    def test_real_report_passes_validator(self):
        report, _ = sw.run_sweep("hackathons", self.ws,
                                 surfaces=fake_surfaces(web_items=ITEMS_A))
        self.assertEqual(sw.validate_sweep_report(report), [])
        self.assertIn("## Coverage note", report)
        self.assertIn("Surfaces NOT checked: none", report)

    def test_validator_rejects_missing_coverage_note(self):
        report, _ = sw.run_sweep("hackathons", self.ws,
                                 surfaces=fake_surfaces(web_items=ITEMS_A))
        broken = report.replace("## Coverage note", "## Notes")
        problems = sw.validate_sweep_report(broken)
        self.assertTrue(any("coverage" in p for p in problems), problems)

    def test_validator_rejects_missing_cost_line_and_dedup_split(self):
        problems = sw.validate_sweep_report(
            "# Lille — sweep report\nSTATUS: DONE\n")
        self.assertTrue(any("Cost" in p for p in problems))
        self.assertTrue(any("Already known" in p for p in problems))
        self.assertTrue(any("phrasings" in p for p in problems))

    def test_errored_surface_is_named_not_checked_with_reason(self):
        surfaces = fake_surfaces(
            web_items=ITEMS_A,
            broken={"devpost": "bot verification wall (HTTP 405)"})
        report, s = sw.run_sweep("hackathons", self.ws, surfaces=surfaces)
        self.assertEqual(s["status"], "DONE")  # other surfaces carried it
        self.assertIn("Surfaces NOT checked: devpost — bot verification "
                      "wall (HTTP 405)", report)
        self.assertEqual(sw.validate_sweep_report(report), [])

    def test_all_surfaces_down_is_blocked_not_empty(self):
        surfaces = fake_surfaces(broken={"web": "boom", "devpost": "boom",
                                         "x": "boom"})
        report, s = sw.run_sweep("hackathons", self.ws, surfaces=surfaces)
        self.assertEqual(s["status"], "BLOCKED")
        self.assertIn("STATUS: BLOCKED", report)
        self.assertEqual(sw.validate_sweep_report(report), [])

    def test_fresh_nonexistent_workspace_is_created(self):
        # Regression: the first live run crashed in KnownItems.save()
        # because run_sweep never created a fresh workspace root.
        ws = os.path.join(self.ws, "brand", "new")
        report, s = sw.run_sweep("hackathons", ws,
                                  surfaces=fake_surfaces(web_items=ITEMS_A))
        self.assertEqual(s["status"], "DONE")
        self.assertTrue(os.path.isfile(os.path.join(ws, "sweep_known.json")))

    def test_report_written_to_outbox(self):
        _, s = sw.run_sweep("hackathons", self.ws,
                            surfaces=fake_surfaces(web_items=ITEMS_A))
        path = os.path.join(self.ws, "reports", s["report_file"])
        self.assertTrue(os.path.isfile(path))


class TestSurfaceParsers(unittest.TestCase):
    def test_ddg_unwrap(self):
        href = ("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fx"
                "&rut=abc")
        self.assertEqual(sw._unwrap_ddg(href), "https://example.org/x")

    def test_fingerprint_stable(self):
        a = {"title": "Alpha Hackathon!", "url": "https://EXAMPLE.org/a/"}
        b = {"title": "alpha hackathon", "url": "http://example.org/a"}
        self.assertEqual(sw.fingerprint(a), sw.fingerprint(b))


if __name__ == "__main__":
    unittest.main()
