"""Lane selection (v0.9) — WALLS.md build-next #1.

The policy is pure logic: explicit lanes are honoured, auto goes
device-first for fingerprint-sensitive domains (WALLS.md class A)
and host otherwise, and the decision always carries its reason.
Plus the two places the decision lands: spec parsing / run_spec
packaging for the device lane, and the core task engine's
PARKED_AT_WALL state (build-next #3).
"""
import json, os, shutil, tempfile, unittest

from free_agent import lanes, specmode
from free_agent.loop import Step, TaskResult
from free_agent.taskstore import TaskEngine, TaskStore, observed_wall


class ParseLaneTests(unittest.TestCase):
    def test_variants(self):
        self.assertEqual(lanes.parse_lane("device"), "device")
        self.assertEqual(lanes.parse_lane(" Device "), "device")
        self.assertEqual(lanes.parse_lane("HOST"), "host")
        self.assertEqual(lanes.parse_lane("auto"), "auto")
        self.assertIsNone(lanes.parse_lane("phone"))
        self.assertIsNone(lanes.parse_lane(""))
        self.assertIsNone(lanes.parse_lane(None))


class ChooseLaneTests(unittest.TestCase):
    def test_explicit_device_honoured_for_plain_domain(self):
        d = lanes.choose_lane("device", ["en.wikipedia.org"])
        self.assertEqual(d["lane"], "device")
        self.assertEqual(d["requested"], "device")
        self.assertIn("explicitly", d["reason"])

    def test_explicit_host_honoured_even_for_x(self):
        d = lanes.choose_lane("host", ["x.com"])
        self.assertEqual(d["lane"], "host")

    def test_auto_goes_device_for_x(self):
        d = lanes.choose_lane("auto", ["x.com", "maildrop.cc"])
        self.assertEqual(d["lane"], "device")
        self.assertIn("x.com", d["reason"])
        self.assertIn("W3", d["reason"])

    def test_auto_goes_device_for_each_sensitive_domain(self):
        for dom in ("luma.com", "lu.ma", "tiktok.com",
                    "duckduckgo.com", "dorahacks.io",
                    "guerrillamail.com", "twitter.com"):
            with self.subTest(dom=dom):
                self.assertEqual(
                    lanes.choose_lane(None, [dom])["lane"], "device")

    def test_auto_matches_subdomains(self):
        self.assertEqual(
            lanes.choose_lane("auto", ["mobile.x.com"])["lane"],
            "device")
        self.assertIsNotNone(lanes.sensitive_match("www.luma.com"))

    def test_auto_host_for_ordinary_domains(self):
        d = lanes.choose_lane("auto", ["en.wikipedia.org",
                                       "example.com"])
        self.assertEqual(d["lane"], "host")
        self.assertIn("no fingerprint-sensitive", d["reason"])

    def test_auto_host_when_no_domains(self):
        self.assertEqual(lanes.choose_lane("auto", [])["lane"],
                         "host")
        self.assertEqual(lanes.choose_lane(None, None)["lane"],
                         "host")

    def test_lookalike_domains_are_not_sensitive(self):
        self.assertIsNone(lanes.sensitive_match("notx.com"))
        self.assertIsNone(lanes.sensitive_match("x.com.evil.example"))
        self.assertEqual(
            lanes.choose_lane("auto", ["notx.com"])["lane"], "host")


class SpecLaneTests(unittest.TestCase):
    SPEC = """Goal
Make a thing.

Acceptance criteria
- The thing exists

Browser domains
- x.com

Lane
- device
"""

    def test_parse_lane_section(self):
        spec = specmode.parse_spec(self.SPEC)
        self.assertEqual(spec["lane"], "device")

    def test_parse_defaults_to_auto(self):
        spec = specmode.parse_spec(
            "Goal\nMake a thing.\n\nAcceptance criteria\n- x\n")
        self.assertEqual(spec["lane"], "auto")
        self.assertIsNone(spec["lane_choice"])

    def test_device_spec_is_packaged_not_run_on_host(self):
        root = tempfile.mkdtemp()
        try:
            def boom(messages):  # the model must never be called
                raise AssertionError("device-lane spec ran on host")
            report, result, task_dir = specmode.run_spec(
                self.SPEC, workspace_root=root, model_call=boom)
            self.assertIsNone(result)
            self.assertIn("STATUS: NEEDS ETHURAM", report)
            self.assertIn("chosen=device", report)
            self.assertIn("Needs Ethuram:", report)
            self.assertEqual(specmode.validate_report(
                report, specmode.parse_spec(self.SPEC)), [])
            pkg_path = os.path.join(task_dir, "device_task.json")
            with open(pkg_path) as f:
                pkg = json.load(f)
            self.assertEqual(pkg["kind"], "lille-device-task")
            self.assertEqual(pkg["lane"], "device")
            self.assertEqual(pkg["browser_domains"], ["x.com"])
            self.assertIn("device", pkg["lane_reason"])
            with open(os.path.join(task_dir, "spec.json")) as f:
                saved = json.load(f)
            self.assertEqual(saved["lane_choice"], "device")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_x_account_spec_is_device_lane(self):
        path = os.path.join(os.path.dirname(__file__), "..",
                            "specs", "2026-11-25-lille-x-account.md")
        with open(path) as f:
            spec = specmode.parse_spec(f.read())
        self.assertEqual(spec["lane"], "device")
        d = lanes.choose_lane(spec["lane"], spec["browser_domains"])
        self.assertEqual(d["lane"], "device")


class ParkedAtWallCoreTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.store = TaskStore(self.root)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def _result(self, steps, status="done", summary="finished"):
        return TaskResult(status, summary, "/tmp/x", "t1", steps)

    def test_observed_wall_from_trace_only(self):
        steps = [Step(1, "tool", "browser_open", "",
                      "CHALLENGE kind=captcha detail=human-"
                      "verification iframe: https://arkose — STOP")]
        wall = observed_wall(self._result(steps))
        self.assertEqual(wall["kind"], "captcha")
        self.assertIn("arkose", wall["detail"])
        # Narration without an observed CHALLENGE is not a wall.
        steps = [Step(1, "finish", "", "", "done")]
        self.assertIsNone(observed_wall(
            self._result(steps, summary="there was a captcha")))

    def test_engine_parks_on_observed_challenge(self):
        eng = TaskEngine(workspace_root=self.root)
        rec = self.store.create("browse something")
        steps = [Step(1, "tool", "browser_click", "",
                      "CHALLENGE kind=captcha detail=page text: "
                      "\"verify you are human\" — STOP that route")]
        out = eng._drive(rec, None,
                         lambda lp: self._result(steps))
        self.assertEqual(out["state"], "parked_at_wall")
        self.assertEqual(out["wall"]["kind"], "captcha")
        self.assertIn("parked_at_wall",
                      __import__("free_agent.taskstore",
                                 fromlist=["STATES"]).STATES)
        # Parked is resumable (after the human step), not terminal.
        from free_agent.taskstore import RESUMABLE, TERMINAL
        self.assertIn("parked_at_wall", RESUMABLE)
        self.assertNotIn("parked_at_wall", TERMINAL)

    def test_engine_completes_without_challenge(self):
        eng = TaskEngine(workspace_root=self.root)
        rec = self.store.create("browse something")
        steps = [Step(1, "tool", "browser_open", "", "BROWSER_OK")]
        out = eng._drive(rec, None, lambda lp: self._result(steps))
        self.assertEqual(out["state"], "completed")
        self.assertIsNone(out["wall"])


if __name__ == "__main__":
    unittest.main()
