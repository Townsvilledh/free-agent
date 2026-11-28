"""v1.0.16 tests — the two mechanism classes of battery
round 3 (releases/lille-1.0.15/battery/GRADE.md), pinned by
SHAPE, never by scenario text:

Class 1 — FINISH-VS-STEPS CONSISTENCY: a finish whose
summary asserts a number/date/item that contradicts ANY
content the run itself produced (tool observations AND the
model's own step texts/working) bounces ONCE with the
contradiction named; a second contradictory finish is
refused and the task parks honestly. Research-shaped
finishes: every listed item must trace to a fetched source
in the step record (the 1.0.15 evidence demand extended
from file content to report items).

Class 2 — MALFORMED-CALL RECOVERY: a malformed tool call
earns ONE repair re-ask per call shape, showing the
specific format error; the first malformation never counts
toward the stop. Only a malformed call after its repair
re-ask counts as a strike; two strikes stop the run
honestly, with the repairs on the record.
"""
import os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent.loop import (
    TaskLoop, _malformed_shape, unsourced_items,
    weekday_contradictions)


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


# The round-3 numbers-a summary, verbatim in shape: headline
# figures computed with max() instead of the cap's min(),
# contradicted by the summary's own closing note.
NUMBERS_BAD = (
    "Calculated swap fees based on 0.5% fee with $2 cap:\n"
    "- On $30: fee = max(0.5% * 30, 2) = max(0.15, 2) = $2 (capped)\n"
    "- On $300: fee = max(0.5% * 300, 2) = max(1.5, 2) = $2 (capped)\n"
    "- On $3,000: fee = max(0.5% * 3000, 2) = max(15, 2) = $15\n"
    "\n"
    "Note: The cap applies when the calculated 0.5% fee exceeds $2. "
    "For $30 and $300, the 0.5% fee is below $2, so the actual fee "
    "would be $0.15 and $1.5 respectively. The cap only kicks in "
    "when the percentage fee would exceed $2, which happens when "
    "the swap amount is $400 or more.")

NUMBERS_GOOD = (
    "Fees: on $30 the fee is $0.15; on $300 the fee is $1.50; "
    "on $3,000 the fee is $2 (the $2 cap). Working: 0.5% of 30 "
    "is 0.15, 0.5% of 300 is 1.5, 0.5% of 3000 is 15 capped to 2.")

FEE_TASK = ("A swap route charges a 0.5% fee capped at $2. "
            "What is the fee on $30, on $300, and on $3,000?")


class ConsistencyTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-tasks-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_summary_contradicting_own_working_bounces_then_lands(self):
        loop = TaskLoop(model_call=ScriptedModel([
            native("finish", {"summary": NUMBERS_BAD, "outputs": []}),
            native("finish", {"summary": NUMBERS_GOOD, "outputs": []}),
        ]), workspace_root=self.root)
        res = loop.run(FEE_TASK)
        self.assertEqual(res.status, "done")
        self.assertEqual(res.summary, NUMBERS_GOOD)
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertEqual(len(rejected), 1)
        self.assertIn("contradict", rejected[0].result_summary)

    def test_second_contradictory_finish_parks(self):
        loop = TaskLoop(model_call=ScriptedModel([
            native("finish", {"summary": NUMBERS_BAD, "outputs": []}),
            native("finish", {"summary": NUMBERS_BAD, "outputs": []}),
            native("finish", {"summary": NUMBERS_GOOD, "outputs": []}),
        ]), workspace_root=self.root)
        res = loop.run(FEE_TASK)
        self.assertEqual(res.status, "stopped")
        kinds = [s.kind for s in res.steps]
        self.assertIn("finish-refused", kinds)
        self.assertIn("contradict", res.summary)
        # The good finish queued behind the park never landed.
        self.assertNotEqual(res.summary, NUMBERS_GOOD)

    def test_finish_contradicting_step_computed_numbers_bounces(self):
        # Her own execution step settles the $3,000 fee at the
        # cap; the finish asserts the uncapped figure instead.
        loop = TaskLoop(model_call=ScriptedModel([
            native("run_python", {"code":
                "for amt in (30, 300, 3000):\n"
                "    raw = 0.5 / 100 * amt\n"
                "    print(f'on {amt}: 0.5% is {raw}, fee = {min(raw, 2)}')\n"}),
            native("finish", {"summary":
                "Fees: on $3,000 the fee is $15 "
                "(0.5% of 3000 is 15).", "outputs": []}),
            native("finish", {"summary":
                "Fees: on $30 the fee is $0.15, on $300 it is "
                "$1.50, and on $3,000 it is $2 — the cap.",
                "outputs": []}),
        ]), workspace_root=self.root)
        res = loop.run(FEE_TASK)
        self.assertEqual(res.status, "done")
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertEqual(len(rejected), 1)
        self.assertIn("contradict", rejected[0].result_summary)
        self.assertIn("3000", rejected[0].result_summary)

    def test_finish_contradicting_step_text_date_bounces(self):
        # schedule-b's shape: her own step text computed the
        # date; the finish lands a different one.
        loop = TaskLoop(model_call=ScriptedModel([
            text("Counting working days from Friday 28 November "
                 "2026, not counting the start day: the 10th "
                 "working day is Friday 11 December 2026."),
            native("finish", {"summary":
                "The deadline is Tuesday 8 December 2026.",
                "outputs": []}),
            native("finish", {"summary":
                "The deadline is Friday 11 December 2026.",
                "outputs": []}),
        ]), workspace_root=self.root)
        res = loop.run(
            "A deadline is 10 working days after Friday "
            "28 November 2026, not counting the start day. "
            "What date is it?")
        self.assertEqual(res.status, "done")
        self.assertIn("11 December 2026", res.summary)
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertEqual(len(rejected), 1)
        self.assertIn("2026-12-11", rejected[0].result_summary)

    def test_research_finish_with_unsourced_items_bounces(self):
        # research-a's shape: the run's fetched source names
        # ONE event; the finish lists it plus two the steps
        # never found.
        task_id, task_dir = "research-x", os.path.join(
            self.root, "research-x")
        os.makedirs(task_dir)
        with open(os.path.join(task_dir, "sources.txt"), "w") as f:
            f.write("Open Source Summit Lagos 2026 — applications "
                    "close 20 December 2026. Solo entries welcome.\n")
        bad = ("## Summary\nI found 3 upcoming hackathons:\n\n"
               "### 1. **Open Source Summit Lagos 2026**\n"
               "- **Deadline**: 20 December 2026\n\n"
               "### 2. **MLH Hackathon 2024**\n"
               "- **Deadline**: March 31, 2024\n\n"
               "### 3. **HackMIT Spring 2024**\n"
               "- **Deadline**: April 15, 2024\n")
        good = ("## Summary\nI found 1 upcoming event in the "
                "sources:\n\n"
                "### 1. **Open Source Summit Lagos 2026**\n"
                "- **Deadline**: 20 December 2026\n")
        loop = TaskLoop(model_call=ScriptedModel([
            native("read_file", {"path": "sources.txt"}),
            native("finish", {"summary": bad, "outputs": []}),
            native("finish", {"summary": good, "outputs": []}),
        ]), workspace_root=self.root)
        res = loop.run(
            "Report the upcoming hackathons from the material "
            "in your sandbox, with their deadlines.",
            task_id=task_id, task_dir=task_dir)
        self.assertEqual(res.status, "done")
        self.assertEqual(res.summary, good)
        rejected = [s for s in res.steps if s.kind == "finish-rejected"]
        self.assertEqual(len(rejected), 1)
        self.assertIn("MLH Hackathon 2024",
                      rejected[0].result_summary)

    def test_weekday_contradiction_is_structural(self):
        # 8 December 2026 is a Tuesday; naming it a Friday
        # contradicts the calendar on the face of the text.
        bad = weekday_contradictions(
            "The deadline is Friday 8 December 2026.")
        self.assertEqual(len(bad), 1)
        self.assertEqual(bad[0][2], "friday")
        self.assertEqual(bad[0][3], "tuesday")
        self.assertEqual(weekday_contradictions(
            "The deadline is Tuesday 8 December 2026."), [])
        self.assertEqual(weekday_contradictions(
            "The deadline is 8 December 2026."), [])

    def test_unsourced_items_helper(self):
        state = {"task_text": "find hackathons",
                 "messages": [
                     {"role": "user", "content": "find hackathons"},
                     {"role": "user", "content":
                      "Tool result (web_search): Devconnect "
                      "Buenos Aires 2026 — applications open"}]}
        summary = ("### 1. **Devconnect Buenos Aires 2026**\n"
                   "### 2. **Invented Chain Summit 2024**\n")
        self.assertEqual(unsourced_items(summary, state),
                         ["Invented Chain Summit 2024"])


BAD_CALL = ('```json\n{"tool": "write_file", "args": '
            '{"path": "a.txt", "content": }\n```')


class MalformedRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-tasks-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_malformed_shape_names_tool_and_category(self):
        shape = _malformed_shape(BAD_CALL, "JSON did not parse: x")
        self.assertEqual(shape, "write_file|json did not parse")
        shape = _malformed_shape("no tool here", "JSON block is "
                                             "not an object")
        self.assertEqual(shape, "unknown|json block is not an object")

    def test_malformed_call_repaired_on_reask(self):
        model = ScriptedModel([
            text(BAD_CALL),
            text('```json\n{"tool": "write_file", "args": '
                 '{"path": "a.txt", "content": "hi"}}\n```'),
            native("finish", {"summary": "wrote a.txt",
                              "outputs": ["a.txt"]}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("write a file")
        self.assertEqual(res.status, "done")
        kinds = [s.kind for s in res.steps]
        self.assertIn("malformed", kinds)
        self.assertIn("repair", kinds)
        # The repair re-ask showed the model the SPECIFIC
        # format error, not a generic complaint.
        repair_prompts = [
            m.get("content", "") for call in model.seen
            for m in call if m.get("role") == "user"
            and "malformed" in (m.get("content") or "")]
        self.assertTrue(any("JSON did not parse" in p
                            for p in repair_prompts))

    def test_still_malformed_after_repair_stops_honestly(self):
        # Round 3's dominant mode: the same malformed shape
        # over and over. The first malformation earns its
        # repair re-ask (no stop); the failed repair is ONE
        # strike (still no stop); the next malformation of
        # the already-repaired shape is the second strike
        # and the run stops honestly, repairs on the record.
        model = ScriptedModel([text(BAD_CALL)] * 6)
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("write a file")
        self.assertEqual(res.status, "error")
        self.assertIn("malformed", res.summary)
        self.assertIn("repair", res.summary)
        malformed = [s for s in res.steps if s.kind == "malformed"]
        # initial + failed repair + post-repair repeat >= 3:
        # the run did NOT stop at the old two-strike point.
        self.assertGreaterEqual(len(malformed), 3)
        self.assertGreaterEqual(len(model.seen), 3)
        self.assertTrue(any("repair re-ask" in s.result_summary
                            for s in res.steps))

    def test_different_shape_earns_its_own_repair(self):
        # A NEW malformed shape is a different failure: it
        # earns its own repair re-ask rather than counting
        # as a strike against the first shape's repair.
        other_bad = '```json\n{"tool": "read_file", "args": }\n```'
        model = ScriptedModel([
            text(BAD_CALL),                       # shape A malformed
            text(other_bad),                      # repair reply: shape B malformed
            text('```json\n{"tool": "read_file", "args": '
                 '{"path": "a.txt"}}\n```'),      # shape B repaired
            native("finish", {"summary": "read attempted",
                              "outputs": []}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root)
        res = loop.run("read a file")
        self.assertEqual(res.status, "done")
        repairs = [s for s in res.steps if s.kind == "repair"]
        self.assertGreaterEqual(len(repairs), 1)
        strikes = [s for s in res.steps
                   if s.kind == "malformed"
                   and "strike" in s.result_summary]
        self.assertEqual(strikes, [])


if __name__ == "__main__":
    unittest.main()
