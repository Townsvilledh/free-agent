"""Host-side Groq bridge (free_agent/hostkeys.py) — unit tests.

No live calls: the connector exchange and the child process are
fakes. What is pinned:
- the scrubber removes the surrogate bare, as `Bearer <value>`, and
  split across chunk boundaries; unrelated text passes through;
- the launcher REFUSES (exit 3, child never spawned) when the
  exchange yields nothing;
- the child receives GROQ_API_KEY=<surrogate> in ITS environment
  only, and its output is relayed scrubbed.
"""
import io
import unittest

from free_agent import hostkeys

SECRET = "surrogate-token-abc123"


class FakeProc:
    def __init__(self, lines, code=0):
        self.stdout = iter(lines)
        self._code = code

    def wait(self):
        return self._code


class FakePopen:
    def __init__(self, lines, code=0):
        self.lines = lines
        self.code = code
        self.calls = []

    def __call__(self, cmd, **kwargs):
        self.calls.append((cmd, kwargs))
        return FakeProc(self.lines, self.code)


class TestScrubber(unittest.TestCase):
    def test_bare_and_bearer_forms_removed(self):
        out = hostkeys.scrub_text(f"key is {SECRET} and Bearer {SECRET} ok", SECRET)
        self.assertNotIn(SECRET, out)
        self.assertIn(hostkeys.REDACTED, out)
        self.assertTrue(out.endswith("ok"))

    def test_split_across_chunks_removed(self):
        s = hostkeys.Scrubber(SECRET)
        pieces = [SECRET[i:i + 3] for i in range(0, len(SECRET), 3)]
        out = "".join(s.feed(">>") ) + "".join(s.feed(p) for p in pieces)
        out += s.feed("<<") + s.flush()
        self.assertNotIn(SECRET, out)
        self.assertIn(">>", out)
        self.assertIn("<<", out)

    def test_unrelated_text_untouched(self):
        text = "an ordinary log line with no secrets\nsecond line\n"
        self.assertEqual(hostkeys.scrub_text(text, SECRET), text)

    def test_empty_secret_is_passthrough(self):
        self.assertEqual(hostkeys.scrub_text("anything", ""), "anything")


class TestLauncher(unittest.TestCase):
    def test_refusal_when_exchange_yields_nothing(self):
        popen = FakePopen(["should never be seen"])
        err = io.StringIO()
        code = hostkeys.main(["--task", "x"], surrogate_getter=lambda: None,
                             popen=popen, out=io.StringIO(), err=err)
        self.assertEqual(code, 3)
        self.assertEqual(popen.calls, [])          # child never spawned
        self.assertIn("REFUSED", err.getvalue())

    def test_refusal_on_empty_command_after_dashes(self):
        code = hostkeys.main(["--"], surrogate_getter=lambda: SECRET,
                             popen=FakePopen([]), out=io.StringIO(), err=io.StringIO())
        self.assertEqual(code, 2)

    def test_child_env_and_scrubbed_relay(self):
        popen = FakePopen([f"child echoes Bearer {SECRET} done\n"], code=7)
        out = io.StringIO()
        code = hostkeys.main(["--task", "hello"], surrogate_getter=lambda: SECRET,
                             popen=popen, out=out, err=io.StringIO())
        self.assertEqual(code, 7)                  # child exit code passes through
        cmd, kwargs = popen.calls[0]
        self.assertEqual(cmd[1:3], ["-m", "free_agent"])
        self.assertIn("--task", cmd)
        self.assertEqual(kwargs["env"]["GROQ_API_KEY"], SECRET)
        self.assertNotIn(SECRET, out.getvalue())   # relayed output scrubbed
        self.assertIn("done", out.getvalue())

    def test_explicit_command_after_dashes(self):
        cmd = hostkeys.child_command(["--", "python3", "scripts/groq_proof.py"])
        self.assertEqual(cmd, ["python3", "scripts/groq_proof.py"])


if __name__ == "__main__":
    unittest.main()
