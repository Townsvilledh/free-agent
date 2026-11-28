"""v1.0.15 tests — the five failure classes of battery
round 2 (releases/lille-1.0.14/battery/GRADE.md), pinned.
The battery's shapes are replayed as tests; its sentences
are never rules (no-hardcodes law).

1. CORE-PATH ROUTE STARVATION: a dead route demotes
   (401/403/404 record health), a 404 rotates to the
   route's spare model, a near recovery is waited out
   in-run instead of parking, and the route audit tail is
   persisted into task checkpoints.
2. ZERO-WORK FINISHES: a finish on a task whose own words
   demand evidence (named baseline files / a pointed-at
   page / her own product with a number at stake) cannot
   land on zero executed tool steps; a demanded file must
   have been read by a step; asserted values must trace
   to evidence; an empty-summary finish is not a finish.
3. PSEUDO-TOOL-CALL TEXT: call-shaped text is detected,
   steered once to the real format, and stopped honestly
   on repetition; an unproductive streak is steered back
   to the task's own work and stopped at twice the
   threshold.
4. FORMAT-B CONTRACT: a spec run executes with the repo
   as its working directory — the directory the verifier
   checks — so the round-1/round-2 BLOCKED shape (correct
   files one level too high) cannot recur; files written
   at the OLD level (repo/<file> from inside the repo)
   honestly grade BLOCKED.
5. LOOP-CHECK DIVERSION: the loop steer is derived from
   the record (last productive tool, still-unread files)
   and never names a tool class the job did not call for.
"""
import json, os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent import gittools, specmode
from free_agent.config import Config, ProviderCfg
from free_agent.loop import (
    TaskLoop, detect_pseudo_tool_call)
from free_agent.router import Router
from free_agent.taskstore import TaskEngine


def native(name, args, cid="c1"):
    return {"content": None,
            "tool_calls": [{"id": cid, "name": name, "args": args}]}


def text(content):
    return {"content": content, "tool_calls": None}


class ScriptedModel:
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


class FakeClock:
    def __init__(self, t=1_000_000.0):
        self.t = t

    def __call__(self):
        return self.t

    def advance(self, seconds):
        self.t += seconds


def prov(name, models=None, cooldown=60):
    return ProviderCfg(name, "https://example.invalid/v1", "NOPE_KEY",
                       models or ["m"], cooldown_seconds=cooldown)


def cfg_with(providers):
    c = Config()
    c.providers = providers
    return c


# ---------------- class 1: route starvation ----------------

class RouteHealthTests(unittest.TestCase):
    def test_auth_failure_demotes_the_route(self):
        # Round 2's shape: the lead route answers 401 on
        # every call (a credential the egress never swapped).
        # It must record health and be SKIPPED while cooling,
        # not retried first on every call.
        clock = FakeClock()
        calls = []

        def transport(p, messages):
            calls.append(p.name)
            if p.name == "dead-route":
                return (401, "")
            return (200, "from-live")

        r = Router(cfg_with([prov("dead-route"), prov("live-route")]),
                   transport=transport, clock=clock)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "live-route")
        self.assertEqual(r.fail_counts.get("dead-route"), 1)
        self.assertIn("dead-route", r.cooldowns)
        calls.clear()
        out = r.chat([{"role": "user", "content": "again"}])
        self.assertEqual(out["provider"], "live-route")
        self.assertNotIn("dead-route", calls)  # skipped, not retried

    def test_404_rotates_to_the_spare_model(self):
        # The config lists a spare model precisely for an
        # account that cannot serve the lead; before the fix
        # models[0] was the only model ever sent.
        p = prov("multi", models=["lead-model", "spare-model"])
        seen_models = []

        def raw(provider, messages, tools=None):
            seen_models.append(provider.models[0])
            if provider.models[0] == "lead-model":
                return (404, None, None)
            return (200, {"role": "assistant", "content": "ok"}, None)

        r = Router(cfg_with([p]), transport=lambda p_, m: (200, ""))
        r.raw_transport = raw
        out = r.complete([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "multi")
        self.assertEqual(seen_models, ["lead-model", "spare-model"])
        self.assertEqual(p.models[0], "spare-model")  # sticks
        self.assertIn("model-rotated",
                      [e.outcome for e in r.audit])

    def test_near_recovery_is_waited_out_in_run(self):
        # One route, limited with a 5-second Retry-After:
        # the call waits and re-walks instead of returning
        # the queued result that parked round-2 tasks.
        clock = FakeClock()
        state = {"n": 0}

        def transport(p, messages):
            state["n"] += 1
            if state["n"] == 1:
                return (429, "", 5)
            return (200, "recovered")

        r = Router(cfg_with([prov("only")]), transport=transport,
                   clock=clock)
        r.sleeper = clock.advance
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out.get("provider"), "only")
        self.assertEqual(out.get("text"), "recovered")
        self.assertIn("route-wait", [e.outcome for e in r.audit])

    def test_far_recovery_still_queues_without_sleeping(self):
        clock = FakeClock()
        slept = []
        r = Router(cfg_with([prov("only")]),
                   transport=lambda p, m: (402, "", 900), clock=clock)
        r.sleeper = lambda s: slept.append(s)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertTrue(out["queued"])
        self.assertEqual(out["retry_at"], clock.t + 900)
        self.assertEqual(slept, [])

    def test_checkpoint_carries_the_route_audit_tail(self):
        root = tempfile.mkdtemp(prefix="lille-tasks-")
        try:
            engine = TaskEngine(
                workspace_root=root,
                model_call=ScriptedModel([
                    native("finish", {"summary": "done", "outputs": []}),
                ]))
            rec = engine.start("say done")
            self.assertIn("route_events", rec["checkpoint"])
            self.assertEqual(rec["checkpoint"]["route_events"], [])
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------- class 2: zero-work finishes ----------------

WALLET_A = ("Wallet A: self-custody wallet. Your seed phrase "
            "stays on your device. No KYC. Supports 3 chains. "
            "Charges no fee of its own.")
WALLET_B = ("Wallet B: custodial wallet. The company holds "
            "your keys. KYC is required. Ethereum only. "
            "Charges a 1% fee.")


class ZeroWorkFinishTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-tasks-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def _wallets_task(self, task_id="summary-b"):
        task_dir = os.path.join(self.root, task_id)
        os.makedirs(task_dir)
        with open(os.path.join(task_dir, "wallet_a.txt"), "w") as f:
            f.write(WALLET_A)
        with open(os.path.join(task_dir, "wallet_b.txt"), "w") as f:
            f.write(WALLET_B)
        return task_id

    def test_summary_b_replay_zero_work_finish_rejected(self):
        task_id = self._wallets_task()
        model = ScriptedModel([
            text("I've read the two wallet files. Here is the "
                 "comparison."),
            # The round-2 honesty failure, replayed: a finish
            # with a fabricated table and no tool step at all.
            native("finish", {
                "summary": "Differences: currency USD vs EUR; "
                           "account type Checking vs Savings; "
                           "balance 1234.56 vs 5678.90.",
                "outputs": []}),
            native("read_file", {"path": "wallet_a.txt"}),
            native("read_file", {"path": "wallet_b.txt"}),
            native("finish", {
                "summary": "Wallet A is self-custody with no "
                           "KYC and 3 chains; Wallet B is "
                           "custodial, requires KYC, is "
                           "Ethereum only and charges a 1% fee.",
                "outputs": []}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("Read wallet_a.txt and wallet_b.txt (in "
                       "your workspace). State three concrete "
                       "differences between the two, in a table.",
                       task_id=task_id)
        self.assertEqual(res.status, "done")
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertTrue(any("no executed step" in s.result_summary
                            for s in rejected), res.steps)
        self.assertIn("self-custody", res.summary)

    def test_unread_demanded_file_rejected(self):
        task_id = self._wallets_task()
        model = ScriptedModel([
            native("read_file", {"path": "wallet_a.txt"}),
            native("finish", {
                "summary": "Wallet A is self-custody; Wallet B "
                           "is custodial and requires KYC.",
                "outputs": []}),
            native("read_file", {"path": "wallet_b.txt"}),
            native("finish", {
                "summary": "Wallet A is self-custody; Wallet B "
                           "is custodial and requires KYC.",
                "outputs": []}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("Read wallet_a.txt and wallet_b.txt. "
                       "State the differences.", task_id=task_id)
        self.assertEqual(res.status, "done")
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertTrue(any("wallet_b.txt" in s.result_summary
                            and "no step of this run read" in
                            s.result_summary for s in rejected),
                        res.steps)

    def test_fabricated_value_after_reads_rejected(self):
        task_id = self._wallets_task()
        model = ScriptedModel([
            native("read_file", {"path": "wallet_a.txt"}),
            native("read_file", {"path": "wallet_b.txt"}),
            native("finish", {
                "summary": "Wallet A holds a balance of "
                           "1234.56; Wallet B is custodial.",
                "outputs": []}),
            native("finish", {
                "summary": "Wallet A is self-custody; Wallet B "
                           "is custodial.",
                "outputs": []}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("Read wallet_a.txt and wallet_b.txt. "
                       "State the differences.", task_id=task_id)
        self.assertEqual(res.status, "done")
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertTrue(any("1234.56" in s.result_summary
                            for s in rejected), res.steps)

    def test_empty_summary_finish_rejected(self):
        # browser-a's accepted finish: args carried only an
        # error payload under another key; the summary was
        # empty and the run still "completed".
        model = ScriptedModel([
            native("finish", {"results": {"error": "JSON did "
                                           "not parse"}}),
            native("finish", {"summary": "Nothing to fetch; "
                                         "the answer is in "
                                         "the task.",
                              "outputs": []}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("State the obvious.")
        self.assertEqual(res.status, "done")
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertTrue(any("no summary" in s.result_summary
                            for s in rejected), res.steps)

    def test_no_demand_task_may_finish_without_tools(self):
        # numbers-a's legitimate shape: the task carries its
        # own inputs and demands no evidence — a direct
        # finish is proportionate.
        model = ScriptedModel([
            native("finish", {"summary": "15% of 30 is 4.5.",
                              "outputs": []}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("What is 15% of 30?")
        self.assertEqual(res.status, "done")
        self.assertIn("4.5", res.summary)


# ---------------- class 3: pseudo-tool-call text ----------------

class PseudoCallTests(unittest.TestCase):
    def test_detection_shapes(self):
        # browser-b's replies, replayed as shapes.
        self.assertEqual(
            detect_pseudo_tool_call(
                'browser_open("https://example.com", 2)'),
            "browser_open")
        script = ("Lille: browser_wait(10, \"Example Domain\", 30)\n"
                  "Lille: browser_read()\n"
                  "Lille: browser_click(\"Login\")")
        self.assertEqual(detect_pseudo_tool_call(script),
                         "browser_wait")
        self.assertEqual(
            detect_pseudo_tool_call(
                "- browser_open(\"https://en.wikipedia.org/wiki/Lagos\")\n"
                "- browser_read()"),
            "browser_open")
        self.assertEqual(
            detect_pseudo_tool_call("Lille: finish()"),
            "finish")

    def test_detection_rejects_prose_and_code(self):
        self.assertIsNone(detect_pseudo_tool_call(
            "I will use the browser_open tool to check the page."))
        self.assertIsNone(detect_pseudo_tool_call(
            'print("hello")'))
        self.assertIsNone(detect_pseudo_tool_call(
            "The heading says Example Domain."))
        self.assertIsNone(detect_pseudo_tool_call(""))

    def test_pseudo_replies_stop_the_run_honestly(self):
        root = tempfile.mkdtemp(prefix="lille-tasks-")
        try:
            model = ScriptedModel([
                text('browser_open("https://example.com", 2)'),
                text('Lille: browser_read()'),
                text('browser_read()'),
            ])
            loop = TaskLoop(model_call=model, workspace_root=root)
            res = loop.run("Go to example.com and tell me the "
                           "main heading.")
            self.assertEqual(res.status, "stopped")
            kinds = [s.kind for s in res.steps]
            self.assertEqual(kinds.count("pseudo-call"), 2)
            self.assertNotIn("finish", kinds)
            seen = json.dumps(model.seen)
            self.assertIn("as TEXT", seen)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_pseudo_then_real_call_recovers(self):
        root = tempfile.mkdtemp(prefix="lille-tasks-")
        try:
            model = ScriptedModel([
                text('write_file("note.txt", "hi")'),
                native("write_file", {"path": "note.txt",
                                      "content": "hi"}),
                native("finish", {"summary": "Wrote note.txt.",
                                  "outputs": ["note.txt"]}),
            ])
            loop = TaskLoop(model_call=model, workspace_root=root)
            res = loop.run("Write a note to note.txt.")
            self.assertEqual(res.status, "done")
            self.assertEqual([s.kind for s in res.steps].count(
                "pseudo-call"), 1)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_derailment_steers_then_stops(self):
        # format-a's shape: the same failing execution,
        # repeated — no file changes, no new information.
        root = tempfile.mkdtemp(prefix="lille-tasks-")
        try:
            replies = [native("run_python", {
                "code": "pip install -r requirements.txt"})] * 12
            model = ScriptedModel(replies)
            loop = TaskLoop(model_call=model, workspace_root=root)
            res = loop.run("Reply with a JSON object naming "
                           "the three people in the task.")
            self.assertEqual(res.status, "stopped")
            self.assertIn("stopped making progress", res.summary)
            seen = json.dumps(model.seen)
            self.assertIn("Loop check", seen)
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------- class 5: loop steer returns to own work ----------------

class LoopSteerTests(unittest.TestCase):
    def test_files_b_replay_steer_names_own_work_not_browser(self):
        root = tempfile.mkdtemp(prefix="lille-tasks-")
        try:
            task_dir = os.path.join(root, "files-b")
            os.makedirs(task_dir)
            notes = {
                "note1.txt": "Project Atlas kickoff is on "
                             "Monday. Amara Osei leads.",
                "note2.txt": "The Atlas budget is 4500 dollars.",
                "note3.txt": "Atlas needs three deliverables.",
            }
            for name, content in notes.items():
                with open(os.path.join(task_dir, name), "w") as f:
                    f.write(content)
            model = ScriptedModel([
                native("read_file", {"path": "note1.txt"}),
                native("read_file", {"path": "note1.txt"}),
                native("read_file", {"path": "note1.txt"}),
                native("read_file", {"path": "note2.txt"}),
                native("read_file", {"path": "note3.txt"}),
                native("write_file", {
                    "path": "summary.txt",
                    "content": "Atlas: kickoff Monday, lead "
                               "Amara Osei, budget 4500 "
                               "dollars, three deliverables."}),
                native("finish", {
                    "summary": "Combined the three Atlas notes "
                               "into summary.txt.",
                    "outputs": ["summary.txt"]}),
            ])
            loop = TaskLoop(model_call=model, workspace_root=root)
            res = loop.run(
                "Read note1.txt, note2.txt and note3.txt in "
                "your workspace and write a combined summary "
                "to summary.txt.", task_id="files-b")
            self.assertEqual(res.status, "done")
            # Judge the STEER itself (the system prompt
            # legitimately names browser tools elsewhere).
            steers = [m["content"] for call in model.seen
                      for m in call
                      if m.get("role") == "user"
                      and "Loop check" in (m.get("content") or "")]
            self.assertTrue(steers)
            steer = steers[0]
            self.assertIn("read_file", steer)
            self.assertIn("Still unread: note2.txt, note3.txt",
                          steer)
            self.assertNotIn("browser_read", steer)
            self.assertNotIn("browser_open", steer)
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------- class 4: spec working-directory contract ----------------

FORMAT_B_SPEC = """# Two small files

Goal
Create two small text files: hello.txt containing exactly the text 'hello lille', and count.txt containing the number of words in hello.txt.

Acceptance criteria
- [ ] hello.txt exists containing exactly 'hello lille'
- [ ] count.txt exists containing the number of words in hello.txt
- [ ] The task is reported SHIPPED only if both files verify: hello.txt content is exactly 'hello lille' and count.txt holds that word count

Deliverables
- hello.txt
- count.txt
"""


class SpecContractTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-spec-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_format_b_shape_ships(self):
        # The battery's Format B spec, run the way the
        # contract now works: the model's plain relative
        # paths land in the repo — the directory the
        # verifier checks.
        model = ScriptedModel([
            native("write_file", {"path": "hello.txt",
                                  "content": "hello lille"}),
            native("write_file", {"path": "count.txt",
                                  "content": "2"}),
            native("finish", {"summary": "Created hello.txt "
                                         "and count.txt.",
                              "outputs": ["hello.txt", "count.txt"]}),
        ])
        report, result, task_dir = specmode.run_spec(
            FORMAT_B_SPEC, repo=None, workspace_root=self.root,
            model_call=model, max_steps=10)
        self.assertTrue(report.startswith("STATUS: SHIPPED"), report)
        repo = os.path.join(task_dir, "repo")
        with open(os.path.join(repo, "hello.txt")) as f:
            self.assertEqual(f.read(), "hello lille")
        with open(os.path.join(repo, "count.txt")) as f:
            self.assertEqual(f.read(), "2")
        # Nothing leaked one level up, where two battery
        # rounds worth of correct files graded BLOCKED.
        self.assertFalse(os.path.exists(
            os.path.join(task_dir, "hello.txt")))

    def test_old_level_writes_grade_blocked_honestly(self):
        # A model still writing repo/<file> from inside the
        # repo lands one level too deep; the verifier's
        # BLOCKED is then the truth, not a mismatch.
        model = ScriptedModel([
            native("write_file", {"path": "repo/hello.txt",
                                  "content": "hello lille"}),
            native("write_file", {"path": "repo/count.txt",
                                  "content": "2"}),
            native("finish", {"summary": "Created the files.",
                              "outputs": ["repo/hello.txt",
                                          "repo/count.txt"]}),
        ])
        report, result, task_dir = specmode.run_spec(
            FORMAT_B_SPEC, repo=None, workspace_root=self.root,
            model_call=model, max_steps=10)
        self.assertTrue(report.startswith("STATUS: BLOCKED"), report)

    def test_repo_dir_of_resolution(self):
        td = os.path.join(self.root, "plain")
        os.makedirs(os.path.join(td, "repo"))
        # Neither is a git repo: the named subdir stands.
        self.assertEqual(gittools.repo_dir_of(td, None),
                         os.path.join(td, "repo"))
        # The task dir itself as git root (the spec
        # contract): resolves to itself.
        os.makedirs(os.path.join(td, ".git"))
        self.assertEqual(gittools.repo_dir_of(td, None), td)
        # A real repo/ subdir still wins when it is a repo.
        os.makedirs(os.path.join(td, "repo", ".git"))
        self.assertEqual(gittools.repo_dir_of(td, None),
                         os.path.join(td, "repo"))


if __name__ == "__main__":
    unittest.main()
