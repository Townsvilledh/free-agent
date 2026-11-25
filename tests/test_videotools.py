"""v0.6 video production tests — mocked model, real ffmpeg/PIL where
the host has them. No network, no generative video anything."""
import os, shutil, subprocess, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent import specmode, tasktools, videotools

FFMPEG = videotools.find_ffmpeg()
FFPROBE = videotools.find_ffprobe()
HAVE_AV = bool(FFMPEG and FFPROBE)


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


class SegmentCommandTests(unittest.TestCase):
    def test_silent_scene_gets_generated_silence(self):
        cmd = videotools.build_segment_command(
            "ffmpeg", "frame.png", None, 4.5, "seg.mp4")
        joined = " ".join(cmd)
        self.assertIn("anullsrc", joined)
        self.assertIn("-t", cmd)
        self.assertIn("4.50", cmd)
        self.assertIn("libx264", joined)
        self.assertIn("yuv420p", joined)

    def test_narrated_scene_maps_real_audio(self):
        cmd = videotools.build_segment_command(
            "ffmpeg", "frame.png", "voice.mp3", 6.0, "seg.mp4")
        joined = " ".join(cmd)
        self.assertNotIn("anullsrc", joined)
        self.assertIn("voice.mp3", joined)
        self.assertIn("1:a:0", joined)


class CaptureGateTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-vid-")
        self.sentinel = tasktools.Sentinel()

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_file_url_goto_denied(self):
        ok, reason = self.sentinel.check(
            "capture_walkthrough",
            {"steps": [{"action": "goto", "url": "file:///etc/passwd"}]},
            self.root)
        self.assertFalse(ok)
        self.assertIn("http(s)", reason)

    def test_https_goto_allowed(self):
        ok, _ = self.sentinel.check(
            "capture_walkthrough",
            {"steps": [{"action": "goto", "url": "https://example.org/"}]},
            self.root)
        self.assertTrue(ok)

    def test_steps_file_gate_and_load(self):
        import json as _json
        # Gate: steps_file inside the sandbox passes without steps.
        with open(os.path.join(self.root, "steps.json"), "w") as f:
            _json.dump([{"action": "goto", "url": "https://example.org/"},
                        {"action": "screenshot", "name": "a", "hold": 2}], f)
        ok, _ = self.sentinel.check(
            "capture_walkthrough", {"steps_file": "steps.json"}, self.root)
        self.assertTrue(ok)
        # Gate: a steps_file that cannot resolve inside the sandbox
        # (and does not exist) is denied with the precise reason.
        ok, reason = self.sentinel.check(
            "capture_walkthrough",
            {"steps_file": "../../evil-outside.json"}, self.root)
        self.assertFalse(ok)
        self.assertIn("steps_file not found", reason)
        # Handler: a missing file is a precise, self-correctable error.
        out = videotools.capture_walkthrough(
            self.root, {"steps_file": "nope.json"})
        self.assertIn("steps_file not found", out)
        self.assertIn("write_file", out)
        # Handler: a non-list JSON file is rejected.
        with open(os.path.join(self.root, "bad.json"), "w") as f:
            f.write('{"not": "a list"}')
        out = videotools.capture_walkthrough(
            self.root, {"steps_file": "bad.json"})
        self.assertIn("must contain a non-empty", out)
        # Resolver: the repo/ fallback finds a file written there.
        os.makedirs(os.path.join(self.root, "repo"), exist_ok=True)
        with open(os.path.join(self.root, "repo", "s2.json"), "w") as f:
            _json.dump([{"action": "wait", "seconds": 1}], f)
        steps, err = videotools.resolve_steps(
            self.root, {"steps_file": "s2.json"})
        self.assertIsNone(err)
        self.assertEqual(len(steps), 1)
        # Sentinel validates goto URLs inside a steps_file too.
        with open(os.path.join(self.root, "evil.json"), "w") as f:
            _json.dump([{"action": "goto", "url": "file:///etc/passwd"}], f)
        ok, _ = self.sentinel.check(
            "capture_walkthrough", {"steps_file": "evil.json"}, self.root)
        self.assertFalse(ok)

    def test_assemble_escape_denied(self):
        ok, _ = self.sentinel.check(
            "assemble_video",
            {"out": "../evil.mp4",
             "scenes": [{"image": "x.png"}]}, self.root)
        self.assertFalse(ok)


class TTSChainTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-tts-")
        self._routes = videotools.TTS_ROUTES

    def tearDown(self):
        videotools.TTS_ROUTES = self._routes
        shutil.rmtree(self.root, ignore_errors=True)

    def _sine_mp3(self, path):
        subprocess.run(
            [FFMPEG, "-y", "-v", "error", "-f", "lavfi", "-i",
             "sine=frequency=440:duration=1", path],
            check=True, timeout=60)

    @unittest.skipUnless(HAVE_AV, "ffmpeg/ffprobe not on this host")
    def test_chain_falls_through_and_names_route(self):
        def boom(text, out):
            raise RuntimeError("route down")

        def good(text, out):
            self._sine_mp3(out)

        videotools.TTS_ROUTES = [("route-a", boom), ("route-b", good)]
        res = videotools.narrate(
            self.root, {"scenes": [{"id": "s1", "text": "hello world"}]})
        self.assertTrue(res.startswith("NARRATION_OK route=route-b"), res)
        self.assertIn("dur=1.0s", res)

    def test_all_routes_down_is_honest_failure(self):
        def boom(text, out):
            raise RuntimeError("route down")

        videotools.TTS_ROUTES = [("route-a", boom), ("route-b", boom)]
        res = videotools.narrate(
            self.root, {"scenes": [{"id": "s1", "text": "hello world"}]})
        self.assertTrue(res.startswith("NARRATION_FAILED"), res)
        self.assertNotIn("NARRATION_OK", res)
        self.assertFalse(os.path.exists(
            os.path.join(self.root, "audio", "s1.mp3")))


@unittest.skipUnless(HAVE_AV, "ffmpeg/ffprobe not on this host")
class AssemblyTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-asm-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_render_card_and_tiny_assembly(self):
        r = videotools.render_card(self.root, {
            "out": "card.png", "title": "Demo", "subtitle": "a test",
            "width": 360, "height": 640})
        self.assertTrue(r.startswith("CARD_OK"), r)
        res = videotools.assemble_video(self.root, {
            "out": "tiny.mp4", "width": 360, "height": 640,
            "scenes": [
                {"image": "card.png", "duration": 1, "caption": "one"},
                {"image": "card.png", "duration": 1, "caption": "two"},
            ]})
        self.assertTrue(res.startswith("VIDEO_OK"), res)
        self.assertIn("video=h264", res)
        self.assertIn("captions=2", res)
        ev = videotools.verify_video(self.root, {"path": "tiny.mp4"})
        self.assertTrue(ev.startswith("VIDEO_OK"), ev)
        dur = float(ev.split("duration=")[1].split("s")[0])
        self.assertTrue(1.5 <= dur <= 3.0, dur)

    def test_friendly_shapes_map_to_scenes(self):
        # narration_dir + narration_ids + blur_regions + 'hold' must
        # assemble exactly like per-scene audio/blur/duration.
        import subprocess as _sp
        os.makedirs(os.path.join(self.root, "narration"), exist_ok=True)
        _sp.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i",
                 "sine=frequency=440:duration=1", "-q:a", "4",
                 os.path.join(self.root, "narration", "intro.mp3")],
                check=True)
        r = videotools.render_card(self.root, {
            "out": "card.png", "title": "Demo", "width": 360,
            "height": 640})
        self.assertTrue(r.startswith("CARD_OK"), r)
        res = videotools.assemble_video(self.root, {
            "out": "friendly.mp4", "width": 360, "height": 640,
            "narration_dir": "narration", "narration_ids": ["intro"],
            "blur_regions": {"0": [[0.1, 0.1, 0.5, 0.2]]},
            "scenes": [{"image": "card.png", "hold": 3,
                        "caption": "with voice"}]})
        self.assertTrue(res.startswith("VIDEO_OK"), res)
        self.assertIn("narrated_scenes=1", res)
        self.assertIn("audio=aac", res)
        # Regression: a narrated scene must last its HOLD (3s), not
        # collapse to the 1s audio length (the old -shortest bug —
        # it silently turned a 49s plan into a 44.4s video).
        dur = float(res.split("duration=")[1].split("s")[0])
        self.assertTrue(dur >= 2.8, dur)


VIDEO_SPEC = """# Demo video

Goal
Make a short demo video.

Acceptance criteria
- [ ] The video is 1-30 seconds long
- [ ] Narration or captions present
- [ ] demo.mp4 is delivered

Deliverables
- demo.mp4
"""

PERSONAL_SPEC = """# Grant video

Goal
Record a pitch video where the entrant must personally appear on camera explaining the project.

Acceptance criteria
- [ ] pitch.mp4 is delivered

Deliverables
- pitch.mp4
"""


@unittest.skipUnless(HAVE_AV, "ffmpeg/ffprobe not on this host")
class VideoSpecModeTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-vspec-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_video_spec_end_to_end_mocked(self):
        model = ScriptedModel([
            native("render_card", {"out": "repo/card.png", "title": "Demo",
                                   "width": 360, "height": 640}),
            native("assemble_video", {
                "out": "repo/demo.mp4", "width": 360, "height": 640,
                "scenes": [{"image": "repo/card.png", "duration": 2,
                            "caption": "hello"}]}),
            native("finish", {"summary": "video made",
                              "outputs": ["repo/demo.mp4"]}),
        ])
        report, result, _ = specmode.run_spec(
            VIDEO_SPEC, workspace_root=self.root, model_call=model)
        self.assertTrue(report.startswith("STATUS: SHIPPED"), report)
        self.assertIn("Cost: $0 — keyless routes only", report)
        self.assertEqual(specmode.validate_report(
            report, specmode.parse_spec(VIDEO_SPEC)), [])

    def test_route_wait_resume_keeps_evidence(self):
        # Regression (v0.6 proof, attempt 5): a route cooldown after
        # real work used to restart the spec loop cold — the fresh
        # segment redid work, failed, and its empty trace graded a
        # finished video BLOCKED. The restart must RESUME from the
        # checkpoint so earlier evidence survives into grading.
        from unittest import mock
        from free_agent.loop import WaitingRouteError
        state = {"calls": 0}

        def flaky_model(messages, tools):
            state["calls"] += 1
            n = state["calls"]
            if n == 1:
                return native("render_card", {
                    "out": "repo/card.png", "title": "Demo",
                    "width": 360, "height": 640}, "c1")
            if n == 2:
                raise WaitingRouteError(retry_at=0)
            if n == 3:
                return native("assemble_video", {
                    "out": "repo/demo.mp4", "width": 360, "height": 640,
                    "scenes": [{"image": "repo/card.png", "duration": 2,
                                "caption": "hello"}]}, "c2")
            return native("finish", {"summary": "video made",
                                     "outputs": ["repo/demo.mp4"]}, "c3")

        with mock.patch("free_agent.specmode.time.sleep", lambda s: None):
            report, result, _ = specmode.run_spec(
                VIDEO_SPEC, workspace_root=self.root,
                model_call=flaky_model)
        self.assertTrue(report.startswith("STATUS: SHIPPED"), report)
        tools_used = [s.tool for s in result.steps if s.kind == "tool"]
        self.assertEqual(tools_used.count("render_card"), 1, tools_used)
        self.assertIn("assemble_video", tools_used)

    def test_finish_guard_blocks_unverified_video(self):
        model = ScriptedModel([
            native("finish", {"summary": "trust me", "outputs": []}),
            native("render_card", {"out": "repo/card.png", "title": "Demo",
                                   "width": 360, "height": 640}),
            native("assemble_video", {
                "out": "repo/demo.mp4", "width": 360, "height": 640,
                "scenes": [{"image": "repo/card.png", "duration": 2,
                            "caption": "hello"}]}),
            native("finish", {"summary": "video made",
                              "outputs": ["repo/demo.mp4"]}),
        ])
        report, result, _ = specmode.run_spec(
            VIDEO_SPEC, workspace_root=self.root, model_call=model)
        kinds = [s.kind for s in result.steps]
        self.assertIn("finish-rejected", kinds)
        self.assertTrue(report.startswith("STATUS: SHIPPED"), report)

    def test_personal_appearance_spec_refused_before_any_work(self):
        def exploding_model(messages, tools):
            raise AssertionError("model must not be called")

        report, result, _ = specmode.run_spec(
            PERSONAL_SPEC, workspace_root=self.root,
            model_call=exploding_model)
        self.assertTrue(report.startswith("STATUS: NEEDS ETHURAM"), report)
        self.assertIn("Needs Ethuram:", report)
        self.assertIsNone(result)
        self.assertEqual(specmode.validate_report(
            report, specmode.parse_spec(PERSONAL_SPEC)), [])


if __name__ == "__main__":
    unittest.main()
