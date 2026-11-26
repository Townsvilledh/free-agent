"""v0.3 tool-call loop tests — mocked model, real sandbox/tools."""
import json, os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent.loop import TaskLoop, parse_text_tool_call
from free_agent import sandbox


def native(name, args, cid="c1"):
    return {"content": None,
            "tool_calls": [{"id": cid, "name": name, "args": args}]}


def text(content):
    return {"content": content, "tool_calls": None}


class ScriptedModel:
    def __init__(self, replies):
        self.replies = list(replies)

    def __call__(self, messages, tools):
        if self.replies:
            return self.replies.pop(0)
        return text("script exhausted")


class LoopTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-tasks-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def make_loop(self, replies, **kw):
        return TaskLoop(model_call=ScriptedModel(replies),
                        workspace_root=self.root, **kw)

    def test_multistep_write_run_error_fix_run_finish(self):
        buggy = "raise ValueError('boom')\n"
        fixed = "print('answer', 42)\n"
        loop = self.make_loop([
            native("write_file", {"path": "fib.py", "content": buggy}),
            native("run_python", {"code": buggy}),
            native("write_file", {"path": "fib.py", "content": fixed}),
            native("run_python", {"code": fixed}),
            native("finish", {"summary": "fixed and verified",
                              "outputs": ["fib.py"]}),
        ])
        res = loop.run("write and fix a script")
        self.assertEqual(res.status, "done")
        self.assertTrue(os.path.isfile(os.path.join(res.task_dir, "fib.py")))
        kinds = [s.kind for s in res.steps]
        self.assertEqual(kinds.count("tool"), 4)
        self.assertEqual(kinds[-1], "finish")
        run_steps = [s for s in res.steps if s.tool == "run_python"]
        self.assertIn("exit_code=1", run_steps[0].result_summary)
        self.assertIn("exit_code=0", run_steps[1].result_summary)
        # trace persisted
        self.assertTrue(os.path.isfile(
            os.path.join(self.root, "task_logs", res.task_id + ".json")))

    def test_path_traversal_rejected(self):
        loop = self.make_loop([
            native("write_file", {"path": "../evil.txt", "content": "x"}),
            native("finish", {"summary": "done", "outputs": []}),
        ])
        res = loop.run("try to escape")
        self.assertEqual(res.status, "done")
        self.assertFalse(os.path.exists(os.path.join(self.root, "evil.txt")))
        self.assertIn("sentinel-denied", [s.kind for s in res.steps])

    def test_step_budget_stops_looping_model(self):
        class AlwaysList:
            def __call__(self, messages, tools):
                return native("list_files", {"path": "."})
        loop = TaskLoop(model_call=AlwaysList(), workspace_root=self.root,
                        max_steps=3)
        res = loop.run("loop forever")
        self.assertEqual(res.status, "stopped")
        self.assertIn("3 steps", res.summary)
        self.assertEqual([s.kind for s in res.steps].count("tool"), 3)

    def test_malformed_tool_call_repaired_once(self):
        loop = self.make_loop([
            text("```json\n{tool: finish,}\n```"),
            native("finish", {"summary": "repaired", "outputs": []}),
        ])
        res = loop.run("malformed then fixed")
        self.assertEqual(res.status, "done")
        kinds = [s.kind for s in res.steps]
        self.assertIn("malformed", kinds)
        self.assertIn("repair", kinds)

    def test_malformed_twice_is_honest_error(self):
        class AlwaysBad:
            def __call__(self, messages, tools):
                return text("```json\n{nope\n```")
        loop = TaskLoop(model_call=AlwaysBad(), workspace_root=self.root)
        res = loop.run("always malformed")
        self.assertEqual(res.status, "error")
        self.assertIn("malformed", res.summary)

    def test_finish_without_evidence_rejected(self):
        loop = self.make_loop([
            native("finish", {"summary": "trust me",
                              "outputs": ["ghost.txt"]}),
            native("finish", {"summary": "ok, really done", "outputs": []}),
        ])
        res = loop.run("claim a file that does not exist")
        self.assertEqual(res.status, "done")
        self.assertIn("finish-rejected", [s.kind for s in res.steps])
        self.assertEqual(res.summary, "ok, really done")

    def test_text_fallback_tool_calls(self):
        loop = self.make_loop([
            text('```json\n{"tool": "write_file", "args": {"path": "a.txt", "content": "hi"}}\n```'),
            text('```json\n{"tool": "finish", "args": {"summary": "wrote a.txt", "outputs": ["a.txt"]}}\n```'),
        ])
        res = loop.run("use the text protocol")
        self.assertEqual(res.status, "done")
        with open(os.path.join(res.task_dir, "a.txt")) as f:
            self.assertEqual(f.read(), "hi")

    def test_xml_dialect_tool_calls(self):
        # Dialect seen live 2026-11-26 (AI Horde community model):
        # <tool_call>name<arg_key>k</arg_key><arg_value>v</arg_value>.
        # Before the parser learned it, a spec run died BLOCKED
        # with three well-formed calls read as plain text.
        loop = self.make_loop([
            text('<tool_call>write_file<arg_key>path</arg_key>'
                 '<arg_value>a.txt</arg_value><arg_key>content'
                 '</arg_key><arg_value>hi</arg_value></tool_call>'),
            text('<tool_call>finish<arg_key>summary</arg_key>'
                 '<arg_value>wrote a.txt</arg_value><arg_key>outputs'
                 '</arg_key><arg_value>["a.txt"]</arg_value></tool_call>'),
        ])
        res = loop.run("use the xml dialect")
        self.assertEqual(res.status, "done")
        with open(os.path.join(res.task_dir, "a.txt")) as f:
            self.assertEqual(f.read(), "hi")

    def test_xml_dialect_decodes_json_values(self):
        call, err = parse_text_tool_call(
            '<tool_call>narrate<arg_key>lines</arg_key>'
            '<arg_value>["one", "two"]</arg_value>'
            '<arg_key>count</arg_key><arg_value>2</arg_value>'
            '</tool_call>')
        self.assertIsNone(err)
        self.assertEqual(call["name"], "narrate")
        self.assertEqual(call["args"]["lines"], ["one", "two"])
        self.assertEqual(call["args"]["count"], 2)

    def test_sandbox_run_python_captures_and_times_out(self):
        d = os.path.join(self.root, "t")
        os.makedirs(d)
        ok = sandbox.run_python(d, "print('hello sandbox')")
        self.assertEqual(ok["exit_code"], 0)
        self.assertIn("hello sandbox", ok["stdout"])
        slow = sandbox.run_python(d, "import time; time.sleep(10)", timeout=1)
        self.assertTrue(slow["timed_out"])

    def test_sandbox_rejects_absolute_and_escape_paths(self):
        d = os.path.join(self.root, "t")
        os.makedirs(d)
        for bad in ("/etc/passwd", "../up.txt", "a/../../up.txt"):
            with self.assertRaises(sandbox.SandboxError):
                sandbox.resolve_inside(d, bad)


if __name__ == "__main__":
    unittest.main()
