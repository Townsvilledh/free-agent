"""v0.5 spec mode + repo tools tests — mocked model, real git."""
import os, shutil, subprocess, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent import gittools, specmode, tasktools
from free_agent.loop import TaskLoop


SPEC = """# Build a calculator

Goal
Add basic arithmetic to calc.py with tests.

Hard rules
- No secrets in the repo
- Tests must not be changed after creation

Acceptance criteria
- [ ] calc.py exists
- [ ] All tests pass
- [ ] Work is committed

Deliverables
- calc.py
- test_calc.py

Push
Authorised branches: `lille-proof/v05`
"""


def native(name, args, cid="c1"):
    return {"content": None,
            "tool_calls": [{"id": cid, "name": name, "args": args}]}


class ScriptedModel:
    def __init__(self, replies):
        self.replies = list(replies)

    def __call__(self, messages, tools):
        if self.replies:
            return self.replies.pop(0)
        return {"content": "script exhausted", "tool_calls": None}


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True,
                          text=True, timeout=30)


class SpecParseTests(unittest.TestCase):
    def test_headings_and_checklist(self):
        spec = specmode.parse_spec(SPEC)
        self.assertIn("arithmetic", spec["goal"])
        self.assertEqual(len(spec["hard_rules"]), 2)
        self.assertEqual(spec["criteria"],
                         ["calc.py exists", "All tests pass", "Work is committed"])
        self.assertEqual(spec["deliverables"], ["calc.py", "test_calc.py"])
        self.assertEqual(spec["push_branches"], ["lille-proof/v05"])
        self.assertFalse(spec["main_authorised"])

    def test_main_authorisation_detected(self):
        spec = specmode.parse_spec(SPEC.replace("`lille-proof/v05`", "`main`"))
        self.assertTrue(spec["main_authorised"])
        self.assertEqual(spec["push_branches"], ["main"])

    def test_case_insensitive_headings(self):
        spec = specmode.parse_spec("GOAL\nDo x\n\nACCEPTANCE CRITERIA\n- [ ] y.py exists\n")
        self.assertEqual(spec["criteria"], ["y.py exists"])


class ReportShapeTests(unittest.TestCase):
    def test_validate_report(self):
        spec = specmode.parse_spec(SPEC)
        good = ("STATUS: SHIPPED\n"
                "Cost: $0 — keyless routes only (routes served: keyless)\n"
                "Acceptance criteria:\n"
                "- [x] calc.py exists — evidence: file exists\n"
                "- [x] All tests pass — evidence: run_tests green\n"
                "- [x] Work is committed — evidence: abc123\n"
                "Commits: abc123")
        self.assertEqual(specmode.validate_report(good, spec), [])
        bad = "STATUS: SHIPPED\nAcceptance criteria:\n- [x] calc.py exists"
        problems = specmode.validate_report(bad, spec)
        self.assertTrue(any("Cost" in p for p in problems))
        self.assertTrue(any("criterion not listed" in p for p in problems))
        blocked = good.replace("STATUS: SHIPPED", "STATUS: BLOCKED")
        self.assertTrue(any("blocker" in p.lower()
                            for p in specmode.validate_report(blocked, spec)))


class PushGateTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-gate-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_push_denied_without_spec(self):
        s = tasktools.Sentinel()
        ok, reason = s.check("git_push", {"branch": "lille-proof/v05"}, self.root)
        self.assertFalse(ok)
        self.assertIn("not authorised", reason)

    def test_push_wrong_branch_denied(self):
        s = tasktools.Sentinel(spec_context={
            "push_branches": ["lille-proof/v05"], "main_authorised": False})
        ok, _ = s.check("git_push", {"branch": "dev"}, self.root)
        self.assertFalse(ok)
        ok, _ = s.check("git_push", {"branch": "lille-proof/v05"}, self.root)
        self.assertTrue(ok)

    def test_main_denied_without_explicit_authorisation(self):
        s = tasktools.Sentinel(spec_context={
            "push_branches": ["main"], "main_authorised": False})
        ok, reason = s.check("git_push", {"branch": "main"}, self.root)
        self.assertFalse(ok)
        self.assertIn("explicitly authorise", reason)
        s2 = tasktools.Sentinel(spec_context={
            "push_branches": ["main"], "main_authorised": True})
        ok, _ = s2.check("git_push", {"branch": "main"}, self.root)
        self.assertTrue(ok)

    def test_clone_confinement(self):
        s = tasktools.Sentinel()
        for bad in ("/home/user/repo", "file:///etc/passwd",
                    "https://evil.example.com/x/y", "../local"):
            ok, _ = s.check("git_clone", {"url": bad}, self.root)
            self.assertFalse(ok, bad)
        ok, _ = s.check("git_clone",
                        {"url": "https://github.com/Townsvilledh/free-agent"}, self.root)
        self.assertTrue(ok)


class SecretScanTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-secret-")
        self.repo = os.path.join(self.root, "repo")
        os.makedirs(self.repo)
        git(self.repo, "init", "--quiet")
        git(self.repo, "config", "user.email", "t@t.t")
        git(self.repo, "config", "user.name", "T")
        with open(os.path.join(self.repo, "ok.py"), "w") as f:
            f.write("x = 1\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "--quiet", "-m", "init")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_commit_blocked_by_secret_scan(self):
        token = "ghp_" + "A" * 36  # built at runtime; never a literal
        with open(os.path.join(self.repo, "leak.py"), "w") as f:
            f.write(f'TOKEN = "{token}"\n')
        out = gittools.commit(self.root, "repo", "add leak")
        self.assertIn("COMMIT BLOCKED by secret scan", out)
        self.assertIn("github-token", out)
        self.assertNotIn(token, out)  # the value is never reported
        rc = git(self.repo, "log", "--oneline")
        self.assertEqual(len(rc.stdout.strip().splitlines()), 1)

    def test_clean_commit_works(self):
        with open(os.path.join(self.repo, "more.py"), "w") as f:
            f.write("y = 2\n")
        out = gittools.commit(self.root, "repo", "clean change")
        self.assertTrue(out.startswith("committed "), out)


class SpecRunMockedTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-spec-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_spec_end_to_end_mocked(self):
        calc = "def add(a, b):\n    return a + b\n"
        test = ("import unittest\nfrom calc import add\n"
                "class T(unittest.TestCase):\n"
                "    def test_add(self):\n        self.assertEqual(add(2, 3), 5)\n")
        model = ScriptedModel([
            native("write_file", {"path": "repo/calc.py", "content": calc}),
            native("write_file", {"path": "repo/test_calc.py", "content": test}),
            native("run_tests", {"repo": "repo"}),
            native("git_commit", {"repo": "repo", "message": "add calc"}),
            native("finish", {"summary": "calc built, tests green, committed",
                              "outputs": ["repo/calc.py", "repo/test_calc.py"]}),
        ])
        report, result, task_dir = specmode.run_spec(
            SPEC, repo=None, workspace_root=self.root, model_call=model,
            max_steps=12)
        self.assertTrue(report.startswith("STATUS: SHIPPED"), report)

    def test_finish_guard_blocks_premature_finish(self):
        calc = "def add(a, b):\n    return a + b\n"
        test = ("import unittest\nfrom calc import add\n"
                "class T(unittest.TestCase):\n"
                "    def test_add(self):\n        self.assertEqual(add(2, 3), 5)\n")
        model = ScriptedModel([
            native("finish", {"summary": "done already", "outputs": []}),
            native("write_file", {"path": "repo/calc.py", "content": calc}),
            native("write_file", {"path": "repo/test_calc.py", "content": test}),
            native("run_tests", {"repo": "repo"}),
            native("git_commit", {"repo": "repo", "message": "add calc"}),
            native("finish", {"summary": "now really done",
                              "outputs": ["repo/calc.py", "repo/test_calc.py"]}),
        ])
        report, result, task_dir = specmode.run_spec(
            SPEC, repo=None, workspace_root=self.root, model_call=model,
            max_steps=12)
        self.assertTrue(report.startswith("STATUS: SHIPPED"), report)
        self.assertIn("finish-rejected", [s.kind for s in result.steps])
        self.assertIn("Cost: $0 — keyless routes only", report)
        self.assertEqual(specmode.validate_report(report, specmode.parse_spec(SPEC)), [])
        self.assertTrue(os.path.isfile(os.path.join(task_dir, "SPEC_REPORT.md")))


class PngCriterionTests(unittest.TestCase):
    """v0.8 proof attempt 3: a screenshot criterion named a .png
    file, but _FILE_TOKEN had no image extensions, so an existing
    deliverable graded as 'no checkable evidence'."""

    def test_png_file_criterion_uses_file_evidence(self):
        import tempfile
        with tempfile.TemporaryDirectory() as td:
            repo = os.path.join(td, "repo")
            os.makedirs(repo)
            with open(os.path.join(repo, "wiki.png"), "wb") as f:
                f.write(b"\x89PNG")
            ev = {"repo_dir": repo, "tests_passed": False,
                  "commit_hashes": [], "pushed": []}
            spec = {"criteria": [
                        "A screenshot of the page is saved as wiki.png"],
                    "push_branches": []}
            out = specmode.evaluate_criteria(spec, ev, td)
            self.assertTrue(out[0][1], out)
            self.assertIn("wiki.png", out[0][2])


if __name__ == "__main__":
    unittest.main()


class ResumeSpecTests(unittest.TestCase):
    """v0.8: spec runs checkpoint to disk on every step and can
    resume in a fresh process (host restarts killed three live
    proof runs before this existed)."""

    SIMPLE = """# Fact file

Goal
Write a fact to fact.txt.

Acceptance criteria
- [ ] fact.txt exists

Deliverables
- fact.txt
"""

    def test_checkpoint_written_and_resume_continues(self):
        model = ScriptedModel([
            native("write_file", {"path": "repo/fact.txt",
                                  "content": "a fact"}),
            native("finish", {"summary": "done",
                              "outputs": ["repo/fact.txt"]}, "c2"),
        ])
        with tempfile.TemporaryDirectory() as td:
            report, result, task_dir = specmode.run_spec(
                self.SIMPLE, repo=None, workspace_root=td,
                model_call=model, max_steps=10)
            self.assertTrue(report.startswith("STATUS: SHIPPED"),
                            report)
            self.assertTrue(os.path.isfile(
                os.path.join(task_dir, "spec.json")))
            self.assertTrue(os.path.isfile(
                os.path.join(task_dir, "checkpoint.json")))
            # A fresh "process" (new loop, new model) resumes
            # from the disk checkpoint and still grades from the
            # evidence on disk.
            model2 = ScriptedModel([
                native("finish", {"summary": "resumed and done",
                                  "outputs": ["repo/fact.txt"]}),
            ])
            out = specmode.resume_spec(task_dir, model_call=model2,
                                       max_steps=10)
            self.assertIsNotNone(out)
            report2, _r2, td2 = out
            self.assertEqual(td2, task_dir)
            self.assertTrue(report2.startswith("STATUS: SHIPPED"),
                            report2)

    def test_resume_without_checkpoint_is_none(self):
        with tempfile.TemporaryDirectory() as td:
            self.assertIsNone(specmode.resume_spec(td))
