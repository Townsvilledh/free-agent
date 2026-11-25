"""v0.7 proactivity engine tests — mocked model/clock/HTTP, real
stores. The law under test: work is picked up unprompted, results
are reported when they land, and a pass never duplicates a report.
"""
import json, os, shutil, sys, tempfile, time, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent.commitments import (COST_LINE, CommitmentStore,
                                    export_for_lane, parse_check,
                                    run_check)
from free_agent.proactive import ProactiveRunner
from free_agent.taskstore import TaskEngine, TaskStore
from free_agent.loop import WaitingNetworkError
from free_agent import specmode


def native(name, args, cid="c1"):
    return {"content": None,
            "tool_calls": [{"id": cid, "name": name, "args": args}]}


class FinisherModel:
    def __init__(self):
        self.calls = 0

    def __call__(self, messages, tools):
        self.calls += 1
        return native("finish", {"summary": "done by pickup",
                                 "outputs": []})


class NetworkDownModel:
    def __call__(self, messages, tools):
        raise WaitingNetworkError(1234567890.0, "network unreachable")


class Clock:
    def __init__(self, t):
        self.t = t

    def __call__(self):
        return self.t


class CommitmentTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-cmt-")
        self.store = CommitmentStore(self.root)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_register_list_close(self):
        rec = self.store.create("results of the thing",
                                check="file:result.txt", every_seconds=600)
        self.assertEqual(rec["state"], "open")
        self.assertEqual(rec["every_seconds"], 600)
        listed = self.store.list()
        self.assertEqual([r["id"] for r in listed], [rec["id"]])
        closed = self.store.close(rec["id"])
        self.assertEqual(closed["state"], "closed")
        self.assertIsNone(self.store.close("nope"))

    def test_uncheckable_spec_refused_at_registration(self):
        with self.assertRaises(ValueError):
            self.store.create("vibes", check="whenever-it-feels-done:")
        with self.assertRaises(ValueError):
            parse_check("file:/absolute/path")
        with self.assertRaises(ValueError):
            parse_check("file:../escape")

    def test_file_check_lands_only_when_file_exists(self):
        landed, payload = run_check("file:prize.txt", self.root)
        self.assertFalse(landed)
        with open(os.path.join(self.root, "prize.txt"), "w") as f:
            f.write("won")
        landed, payload = run_check("file:prize.txt", self.root)
        self.assertTrue(landed)
        self.assertEqual(payload["size"], 3)

    def test_actions_run_check_reads_conclusion(self):
        body = json.dumps({"status": "completed", "conclusion": "success",
                           "html_url": "https://example/runs/1",
                           "run_started_at": "t0", "updated_at": "t1"})
        landed, payload = run_check(
            "actions-run:owner/repo:42", self.root,
            http_get=lambda url: (200, body))
        self.assertTrue(landed)
        self.assertEqual(payload["conclusion"], "success")
        body2 = json.dumps({"status": "in_progress", "conclusion": None})
        landed, _ = run_check("actions-run:owner/repo:42", self.root,
                              http_get=lambda url: (200, body2))
        self.assertFalse(landed)


class ProactivePassTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-pro-")
        self.clock = Clock(1_700_000_000.0)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def runner(self, **kw):
        kw.setdefault("clock", self.clock)
        return ProactiveRunner(workspace_root=self.root, **kw)

    def reports(self):
        d = os.path.join(self.root, "reports")
        return sorted(os.listdir(d)) if os.path.isdir(d) else []

    def test_due_check_lands_and_reports_once(self):
        store = CommitmentStore(self.root)
        rec = store.create("contest result", check="file:prize.txt",
                           every_seconds=300)
        rec["next_check_at"] = self.clock.t  # pin to the fake clock
        store.save(rec)
        # Not landed yet: one check executed, no report, still open,
        # next check pushed out by the interval.
        s1 = self.runner().run_pass()
        self.assertEqual(s1["checked"], 1)
        self.assertEqual(s1["reports"], [])
        after = store.load(rec["id"])
        self.assertEqual(after["state"], "open")
        self.assertEqual(after["check_count"], 1)
        self.assertEqual(after["next_check_at"], self.clock.t + 300)
        # Same instant again: not due -> nothing happens (no spam).
        s2 = self.runner().run_pass()
        self.assertEqual(s2["checked"], 0)
        self.assertEqual(self.reports(), [])
        # The result lands; advance past the interval; the pass
        # reports it unprompted.
        with open(os.path.join(self.root, "prize.txt"), "w") as f:
            f.write("15th place")
        self.clock.t += 301
        s3 = self.runner().run_pass()
        self.assertEqual(s3["landed"], [rec["id"]])
        self.assertEqual(len(s3["reports"]), 1)
        done = store.load(rec["id"])
        self.assertEqual(done["state"], "landed")
        self.assertTrue(done["reported"])
        with open(os.path.join(self.root, "reports", s3["reports"][0])) as f:
            text = f.read()
        self.assertIn("STATUS: LANDED", text)
        self.assertIn(COST_LINE, text)
        # Idempotent: further passes never re-report or re-check.
        s4 = self.runner().run_pass()
        self.assertEqual(s4["reports"], [])
        self.assertEqual(len(self.reports()), 1)
        self.assertEqual(store.load(rec["id"])["check_count"], 2)

    def test_deadline_passing_unlanded_is_reported_blocked(self):
        store = CommitmentStore(self.root)
        rec = store.create("never lands", check="file:nope.txt",
                           every_seconds=60,
                           deadline=self.clock.t + 10)
        self.clock.t += 20
        s = self.runner().run_pass()
        self.assertEqual(s["expired"], [rec["id"]])
        with open(os.path.join(self.root, "reports", s["reports"][0])) as f:
            self.assertIn("STATUS: BLOCKED", f.read())

    def test_idle_pickup_runs_queued_task_exactly_once(self):
        tasks = TaskStore(self.root)
        trec = tasks.create("write the thing")
        cmts = CommitmentStore(self.root)
        cmt = cmts.create("write the thing", kind="queued_task",
                          check=f"task-state:{trec['id']}:completed",
                          task_id=trec["id"])
        model = FinisherModel()
        s1 = self.runner(model_call=model).run_pass()
        self.assertEqual(s1["picked_up"], [trec["id"]])
        self.assertEqual(tasks.load(trec["id"])["state"], "completed")
        settled = cmts.load(cmt["id"])
        self.assertEqual(settled["state"], "landed")
        self.assertEqual(settled["result"]["summary"], "done by pickup")
        with open(os.path.join(self.root, "reports", s1["reports"][0])) as f:
            text = f.read()
        self.assertIn("STATUS: DONE", text)
        self.assertIn(COST_LINE, text)
        calls_after_first = model.calls
        s2 = self.runner(model_call=model).run_pass()
        self.assertEqual(s2["picked_up"], [])
        self.assertEqual(s2["reports"], [])
        self.assertEqual(model.calls, calls_after_first)

    def test_waiting_task_resumed_by_pass_when_retry_due(self):
        eng = TaskEngine(workspace_root=self.root,
                         model_call=NetworkDownModel())
        rec = eng.start("needs the network")
        self.assertEqual(rec["state"], "waiting_network")
        # retry_at (1234567890) is in the past relative to the clock.
        s = self.runner(model_call=FinisherModel()).run_pass()
        self.assertEqual(s["resumed"], [rec["id"]])
        self.assertEqual(TaskStore(self.root).load(rec["id"])["state"],
                         "completed")

    def test_private_commitment_refused_by_lane(self):
        store = CommitmentStore(self.root)
        pub = store.create("public watch", check="file:a.txt")
        priv = store.create("my private result", check="file:b.txt",
                            private=True)
        dest = os.path.join(self.root, "lane-export")
        exported, refused = export_for_lane(store, dest)
        self.assertEqual(exported, [pub["id"]])
        self.assertEqual(refused, [priv["id"]])
        self.assertFalse(os.path.exists(
            os.path.join(dest, "commitments", priv["id"] + ".json")))
        # And a lane-mode pass skips it even when present + due.
        with open(os.path.join(self.root, "b.txt"), "w") as f:
            f.write("secret result")
        s = self.runner(lane=True).run_pass()
        self.assertEqual(s["refused_private"], [priv["id"]])
        self.assertEqual(store.load(priv["id"])["state"], "open")
        self.assertEqual(s["reports"], [])


class SpecWatchTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-watch-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    SPEC = """Goal
Enter the draw and await the result.

Acceptance criteria
- [ ] entry.txt exists

Deliverables
- entry.txt

Watch
- file:result.txt every 5
"""

    def test_watch_section_parsed(self):
        spec = specmode.parse_spec(self.SPEC)
        self.assertEqual(spec["watch"],
                         [{"check": "file:result.txt",
                           "every_seconds": 300}])

    def test_spec_run_auto_registers_watch(self):
        model = ScriptedFinish()
        report, result, task_dir = specmode.run_spec(
            self.SPEC, workspace_root=self.root, model_call=model)
        cmts = CommitmentStore(self.root).list()
        self.assertEqual(len(cmts), 1)
        self.assertEqual(cmts[0]["check"], "file:result.txt")
        self.assertEqual(cmts[0]["every_seconds"], 300)
        self.assertIn("Watch registered: commitment " + cmts[0]["id"],
                      report)


class ScriptedFinish:
    def __call__(self, messages, tools):
        blob = json.dumps(messages)
        if "wrote entry.txt" in blob:
            return native("finish", {"summary": "entered",
                                     "outputs": ["repo/entry.txt"]})
        return native("write_file", {"path": "repo/entry.txt",
                                     "content": "in"})


if __name__ == "__main__":
    unittest.main()
