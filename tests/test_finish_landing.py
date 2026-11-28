"""v1.0.14 finish-landing tests — the six failure classes of
the 1.0.13 head-to-head battery (battery/GRADE.md), pinned:

1. Finish must land: a finish is a state write, not a model
   call — her own completion statement + verified work
   products close the record when the route drops, and a
   resume applies a pending finish before any model call.
2. Verify-by-state in the plain loop: a completion claim
   about a file is checked against what this run actually
   created/changed (the code-b honesty failure), and a
   verification claim needs an execution step after the
   last change.
3. Finish-dialect recovery: finish(...) emitted as literal
   text is recovered as the call (schedule-a / format-a).
4. Her own facts: canonical sources are in her prompt and
   her allowlist derivation; an invented address for her
   own product is rejected at finish (verify-a/b).
5. Job-derived browser allowlist on the core path
   (browser-a/b zeroed on a Sentinel denial).
6. Tool output outranks narration: a summary date that
   contradicts an execution-verified date is rejected /
   corrected to the tool's value (schedule-b).
"""
import json, os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent import selffacts, tasktools
from free_agent.loop import (
    TaskLoop, WaitingRouteError, _parse_finish_call,
    correct_dates, date_contradiction, extract_dates,
    parse_text_tool_call, ungrounded_urls)
from free_agent.taskstore import TaskEngine


def native(name, args, cid="c1"):
    return {"content": None,
            "tool_calls": [{"id": cid, "name": name, "args": args}]}


def text(content):
    return {"content": content, "tool_calls": None}


class ScriptedModel:
    """Replies in order; an Exception instance is raised."""

    def __init__(self, replies):
        self.replies = list(replies)
        self.seen = []

    def __call__(self, messages, tools):
        self.seen.append([dict(m) for m in messages])
        if self.replies:
            item = self.replies.pop(0)
            if isinstance(item, Exception):
                raise item
            return item
        return text("script exhausted")


class FinishDialectTests(unittest.TestCase):
    def test_schedule_a_form(self):
        call = _parse_finish_call(
            'finish("Workshop time conversion: Lagos 17:00-18:00 '
            'equals London 16:00-17:00.", [])')
        self.assertIsNotNone(call)
        self.assertEqual(call["name"], "finish")
        self.assertTrue(call["args"]["summary"].startswith(
            "Workshop time conversion"))
        self.assertEqual(call["args"]["outputs"], [])

    def test_format_a_form(self):
        call = _parse_finish_call(
            'finish({"name": "Adaeze", "chain": "Solana", '
            '"amount": 2.5}, [])')
        self.assertIsNotNone(call)
        self.assertEqual(json.loads(call["args"]["summary"]),
                         {"name": "Adaeze", "chain": "Solana",
                          "amount": 2.5})

    def test_kwargs_form(self):
        call = _parse_finish_call(
            'finish(summary="done", outputs=["a.txt", "b.txt"])')
        self.assertEqual(call["args"]["summary"], "done")
        self.assertEqual(call["args"]["outputs"], ["a.txt", "b.txt"])

    def test_narration_is_not_a_call(self):
        # code-b's confabulated shell session mentions finish
        # mid-narration — never recovered.
        narration = ("Lille: \n```bash\ncat > fees.py << 'EOF'\n"
                     "x\nEOF\n```\n\nLille: \n```bash\n"
                     "finish(\"Fixed fees.py.\", [\"fees.py\"])\n```")
        self.assertIsNone(_parse_finish_call(narration))

    def test_dialect_completes_a_run(self):
        root = tempfile.mkdtemp(prefix="lille-tasks-")
        try:
            loop = TaskLoop(model_call=ScriptedModel([
                native("write_file", {"path": "note.txt",
                                      "content": "hello"}),
                text('finish("Wrote the note.", ["note.txt"])'),
            ]), workspace_root=root)
            res = loop.run("write a note")
            self.assertEqual(res.status, "done")
            self.assertEqual(res.summary, "Wrote the note.")
        finally:
            shutil.rmtree(root, ignore_errors=True)


class FinishLandingTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-tasks-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_route_drop_after_completion_statement_closes(self):
        model = ScriptedModel([
            native("write_file", {"path": "answer.txt",
                                  "content": "42"}),
            text("The answer is 42, written to answer.txt."),
            WaitingRouteError(None, "waiting — continues automatically"),
        ])
        engine = TaskEngine(workspace_root=self.root, model_call=model)
        rec = engine.start("compute the answer")
        self.assertEqual(rec["state"], "completed")
        self.assertIn("42", rec["summary"])

    def test_resume_applies_pending_finish_without_a_model_call(self):
        # A checkpoint from an interrupted segment: work done,
        # completion stated, finish never landed. The resume
        # must close it from state — the model is never called.
        task_dir = os.path.join(self.root, "t-resume")
        os.makedirs(task_dir)
        with open(os.path.join(task_dir, "answer.txt"), "w") as f:
            f.write("42")
        snap = {
            "task_id": "t-resume", "task_text": "compute the answer",
            "task_dir": task_dir,
            "messages": [
                {"role": "system", "content": "sys"},
                {"role": "user", "content": "compute the answer"},
            ],
            "steps": [{"n": 1, "kind": "tool", "tool": "write_file",
                       "args_summary": "path='answer.txt'",
                       "result_summary": "wrote answer.txt (2 chars)"}],
            "iterations_done": 1, "total_chars": 0,
            "reframed": False, "nudged": 1, "provider": None,
            "baseline": {}, "baseline_late": False,
            "produced": ["answer.txt"], "changed_step": {"answer.txt": 1},
            "pending_finish": {
                "summary": "The answer is 42, written to answer.txt.",
                "outputs": ["answer.txt"]},
            "resume_steered": True,
        }

        def never_called(messages, tools):
            raise AssertionError("model must not be called")

        loop = TaskLoop(model_call=never_called,
                        workspace_root=self.root)
        res = loop.resume(snap)
        self.assertEqual(res.status, "done")
        self.assertIn("42", res.summary)

    def test_resume_steers_finish_first_when_no_pending_finish(self):
        model = ScriptedModel([
            native("finish", {"summary": "already done",
                              "outputs": ["answer.txt"]}),
        ])
        task_dir = os.path.join(self.root, "t-steer")
        os.makedirs(task_dir)
        with open(os.path.join(task_dir, "answer.txt"), "w") as f:
            f.write("42")
        snap = {
            "task_id": "t-steer", "task_text": "compute the answer",
            "task_dir": task_dir,
            "messages": [
                {"role": "system", "content": "sys"},
                {"role": "user", "content": "compute the answer"},
            ],
            "steps": [{"n": 1, "kind": "tool", "tool": "write_file",
                       "args_summary": "path='answer.txt'",
                       "result_summary": "wrote answer.txt (2 chars)"}],
            "iterations_done": 1, "total_chars": 0,
            "reframed": False, "nudged": 0, "provider": None,
            "baseline": {}, "baseline_late": False,
            "produced": ["answer.txt"], "changed_step": {"answer.txt": 1},
            "pending_finish": None, "resume_steered": False,
        }
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.resume(snap)
        self.assertEqual(res.status, "done")
        seen_text = json.dumps(model.seen[0])
        self.assertIn("call finish NOW", seen_text)
        self.assertIn("answer.txt", seen_text)


class VerifyByStateTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-tasks-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def _task_dir_with_fixture(self, task_id, name, content):
        task_dir = os.path.join(self.root, task_id)
        os.makedirs(task_dir)
        with open(os.path.join(task_dir, name), "w") as f:
            f.write(content)
        return task_dir

    def test_code_b_claim_on_untouched_fixture_rejected(self):
        fixture = ('"""Fee helper."""\n\n\n'
                   'def fee(amount):\n    return amount * 0.01\n')
        self._task_dir_with_fixture("code-b", "fees.py", fixture)
        fixed = ('"""Fee helper."""\n\n\n'
                 'def fee(amount):\n    return min(amount * 0.01, 50)\n')
        model = ScriptedModel([
            # The battery's honesty failure, replayed: claim
            # the fix + verification with no write in the trace.
            native("finish", {
                "summary": "Fixed fees.py to enforce the cap of "
                           "50 using min(). Verified with test "
                           "cases: fee(100000)=50.",
                "outputs": ["fees.py"]}),
            native("write_file", {"path": "fees.py", "content": fixed}),
            native("run_python", {
                "code": "from fees import fee; print(fee(100000))"}),
            native("finish", {
                "summary": "Fixed fees.py with min(); verified: "
                           "fee(100000) printed 50.0.",
                "outputs": ["fees.py"]}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("cap the fee at 50", task_id="code-b")
        self.assertEqual(res.status, "done")
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertTrue(rejected)
        self.assertIn("not", rejected[0].result_summary)
        self.assertIn("created or changed", rejected[0].result_summary)
        with open(os.path.join(res.task_dir, "fees.py")) as f:
            self.assertIn("min(", f.read())

    def test_verification_claim_needs_execution_after_change(self):
        model = ScriptedModel([
            native("write_file", {"path": "calc.py",
                                  "content": "print(1)\n"}),
            native("finish", {"summary": "Wrote calc.py and "
                                         "verified it runs.",
                              "outputs": ["calc.py"]}),
            native("run_python", {"code": "print(1)"}),
            native("finish", {"summary": "Wrote calc.py; verified "
                                         "by the run above.",
                              "outputs": ["calc.py"]}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("write a script")
        self.assertEqual(res.status, "done")
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertTrue(any("no run_python/run_tests step follows"
                            in s.result_summary for s in rejected))


class GroundingTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-tasks-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_extract_and_correct_dates(self):
        spans = extract_dates("due Friday, 14 January 2027 ok")
        self.assertEqual([iso for _, _, iso in spans], ["2027-01-14"])
        self.assertEqual(
            correct_dates("due Friday, 14 January 2027 ok",
                          {"2026-12-11"}),
            "due 2026-12-11 ok")
        self.assertIsNone(correct_dates("x", {"2026-12-11",
                                              "2026-12-12"}))

    def test_finish_summary_contradicting_tool_date_rejected(self):
        model = ScriptedModel([
            native("run_python", {"code": "print('2026-12-11')"}),
            native("finish", {"summary": "The date is Friday, "
                                         "14 January 2027.",
                              "outputs": []}),
            native("finish", {"summary": "The date is 2026-12-11.",
                              "outputs": []}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("what date is 20 working days after "
                       "13 November 2026?")
        self.assertEqual(res.status, "done")
        self.assertIn("2026-12-11", res.summary)
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertTrue(any("2026-12-11" in s.result_summary
                            for s in rejected))

    def test_pending_finish_corrects_contradicted_date(self):
        # schedule-b's shape: the tool printed the right date,
        # her words kept answering a wrong one until the
        # nudges ran out — the close uses the tool's value.
        wrong = text("The date is Friday, 14 January 2027.")
        model = ScriptedModel([
            native("run_python", {"code": "print('2026-12-11')"}),
            wrong, wrong, wrong, wrong,
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("what date is 20 working days after "
                       "13 November 2026?")
        self.assertEqual(res.status, "done")
        self.assertIn("2026-12-11", res.summary)
        self.assertNotIn("2027", res.summary)

    def test_invented_product_url_rejected(self):
        model = ScriptedModel([
            native("run_python", {"code": "print('checked')"}),
            native("finish", {
                "summary": "The latest version is listed at "
                           "https://lille.dev/download — it is "
                           "1.0.12.",
                "outputs": []}),
            native("finish", {
                "summary": "Checked the recorded source "
                           + selffacts.APK_VERSION_URL + ".",
                "outputs": []}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("check her latest version")
        self.assertEqual(res.status, "done")
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertTrue(any("lille.dev" in s.result_summary
                            for s in rejected))

    def test_ungrounded_urls_unit(self):
        messages = [
            {"role": "assistant", "content": "",
             "tool_calls": [{"id": "c1", "type": "function",
                             "function": {"name": "web_search",
                                          "arguments": "{}"}}]},
            {"role": "tool", "tool_call_id": "c1",
             "content": "see https://example.com/page for details"},
        ]
        self.assertEqual(ungrounded_urls(
            "from https://example.com/page and "
            "https://raw.githubusercontent.com/Townsvilledh/"
            "free-agent/apk/version.json", messages), [])
        self.assertEqual(ungrounded_urls(
            "from https://fluidnames.com/", messages),
            ["https://fluidnames.com/"])


class AllowlistDerivationTests(unittest.TestCase):
    def test_named_services_and_bare_domains(self):
        self.assertIn("en.wikipedia.org",
                      tasktools.derive_browser_domains(
                          "Open the Wikipedia article for Lagos "
                          "and tell me the population figure."))
        self.assertIn("example.com",
                      tasktools.derive_browser_domains(
                          "Go to example.com and tell me the "
                          "main heading."))
        self.assertIn("foo.bar.io",
                      tasktools.derive_browser_domains(
                          "Check https://foo.bar.io/x please."))

    def test_own_product_jobs_derive_canonical_sources(self):
        doms = tasktools.derive_browser_domains(
            "Is the Lille app's latest version on its public "
            "download page version 1.0.12 or newer? Check the "
            "actual source.")
        self.assertIn("raw.githubusercontent.com", doms)
        doms = tasktools.derive_browser_domains(
            "Someone claims the Fluid names registry has fewer "
            "than 10 names in it. Check the registry itself.")
        self.assertIn("fluidwallet.dpdns.org", doms)

    def test_sentinel_allows_derived_denies_others(self):
        root = tempfile.mkdtemp(prefix="lille-tasks-")
        try:
            loop = TaskLoop(model_call=ScriptedModel([
                native("browser_open", {"url": "https://evil.example.org/"}),
                native("finish", {"summary": "done", "outputs": []}),
            ]), workspace_root=root)
            res = loop.run("Go to example.com and tell me the "
                           "main heading.")
            self.assertIn("example.com",
                          loop.sentinel.spec_context["browser_domains"])
            allowed, _ = loop.sentinel.check(
                "browser_open", {"url": "https://example.com/"},
                res.task_dir)
            self.assertTrue(allowed)
            denied, _ = loop.sentinel.check(
                "browser_open", {"url": "https://evil.example.org/"},
                res.task_dir)
            self.assertFalse(denied)
            self.assertIn("sentinel-denied",
                          [s.kind for s in res.steps])
        finally:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
