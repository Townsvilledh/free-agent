"""v0.4 durable task engine tests — mocked model, real store/loop.

The kill/resume test simulates process death with a BaseException
raised inside the model call after 2 completed steps: the engine
process "dies", a FRESH engine instance resumes from the on-disk
checkpoint, and the task must finish with continuous step numbering
and no repeated tool executions.
"""
import json, os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent.taskstore import TaskEngine, TaskStore
from free_agent.loop import WaitingNetworkError


def native(name, args, cid="c1"):
    return {"content": None,
            "tool_calls": [{"id": cid, "name": name, "args": args}]}


class SimulatedCrash(BaseException):
    pass


class CrashAfterTwoModel:
    """Completes 2 steps, then 'kills the process' on the 3rd call."""
    def __init__(self):
        self.calls = 0
        self.executed = []

    def __call__(self, messages, tools):
        self.calls += 1
        if self.calls == 1:
            return native("write_file", {"path": "a.txt", "content": "one"})
        if self.calls == 2:
            return native("write_file", {"path": "b.txt", "content": "two"})
        raise SimulatedCrash("process killed")


class FinisherModel:
    def __init__(self):
        self.calls = 0

    def __call__(self, messages, tools):
        self.calls += 1
        # The resumed process must SEE the earlier steps' effects in
        # the message state (tool observations for a.txt and b.txt).
        blob = json.dumps(messages)
        assert "wrote a.txt" in blob and "wrote b.txt" in blob, \
            "resumed model did not receive checkpointed message state"
        return native("finish", {"summary": "both files written",
                                 "outputs": ["a.txt", "b.txt"]})


class NetworkDownModel:
    def __call__(self, messages, tools):
        raise WaitingNetworkError(1234567890.0, "network unreachable")


class TaskStoreTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-store-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_kill_and_resume_in_fresh_engine(self):
        eng1 = TaskEngine(workspace_root=self.root,
                          model_call=CrashAfterTwoModel())
        with self.assertRaises(SimulatedCrash):
            eng1.start("write two files then finish")
        # The record survived the "death", checkpointed at 2 steps.
        store = TaskStore(self.root)
        recs = store.list()
        self.assertEqual(len(recs), 1)
        rec = recs[0]
        self.assertEqual(rec["state"], "running")  # died mid-flight
        self.assertIsNotNone(rec["checkpoint"])
        self.assertEqual(rec["checkpoint"]["iterations_done"], 2)
        self.assertEqual(len(rec["checkpoint"]["steps"]), 2)

        # Fresh engine (new "process") resumes and finishes.
        eng2 = TaskEngine(workspace_root=self.root,
                          model_call=FinisherModel())
        done = eng2.resume(rec["id"])
        self.assertEqual(done["state"], "completed")
        steps = done["checkpoint"]["steps"]
        # Continuous numbering, no repeats: 1,2 from before the kill,
        # then the finish step — write_file appears exactly twice.
        self.assertEqual([s["n"] for s in steps],
                         list(range(1, len(steps) + 1)))
        writes = [s for s in steps
                  if s["kind"] == "tool" and s["tool"] == "write_file"]
        self.assertEqual(len(writes), 2)
        self.assertEqual(steps[-1]["kind"], "finish")
        task_dir = done["checkpoint"]["task_dir"]
        for name in ("a.txt", "b.txt"):
            self.assertTrue(os.path.isfile(os.path.join(task_dir, name)))

    def test_network_failure_is_waiting_not_failed(self):
        eng = TaskEngine(workspace_root=self.root,
                         model_call=NetworkDownModel())
        rec = eng.start("do something online")
        self.assertEqual(rec["state"], "waiting_network")
        self.assertEqual(rec["next_retry_at"], 1234567890.0)
        # And it is resumable once the "network" (model) recovers.
        class PlainFinisher:
            def __call__(self, messages, tools):
                return native("finish", {"summary": "ok", "outputs": []})
        eng2 = TaskEngine(workspace_root=self.root, model_call=PlainFinisher())
        done = eng2.resume(rec["id"])
        self.assertEqual(done["state"], "completed")

    def test_cancel_before_start_stays_cancelled(self):
        eng = TaskEngine(workspace_root=self.root,
                         model_call=PlainFinisherSentinel())
        rec = eng.store.create("never run this")
        eng.cancel(rec["id"])
        out = eng.resume(rec["id"])
        self.assertEqual(out["state"], "cancelled")

    def test_checkpoint_file_integrity(self):
        eng = TaskEngine(workspace_root=self.root,
                         model_call=PlainFinisherSentinel())
        rec = eng.start("finish immediately")
        self.assertEqual(rec["state"], "completed")
        path = os.path.join(self.root, "task_store", rec["id"] + ".json")
        with open(path) as f:
            on_disk = json.load(f)  # must be valid JSON, uncorrupted
        self.assertEqual(on_disk["id"], rec["id"])
        self.assertEqual(on_disk["state"], "completed")
        ck = on_disk["checkpoint"]
        for key in ("task_id", "task_text", "messages", "steps",
                    "iterations_done", "task_dir"):
            self.assertIn(key, ck)


class PlainFinisherSentinel:
    def __call__(self, messages, tools):
        return native("finish", {"summary": "done", "outputs": []})


if __name__ == "__main__":
    unittest.main()
