# VIDEO.md — Lille makes videos (v0.6)

Lille produces demo and explainer videos **in code** — captured
frames, rendered cards, synthesized narration, ffmpeg assembly.
**No generative video model is used, ever**: video-generation models
are unavailable on this account in Nigeria (a lasting regional
restriction), so the whole pipeline is built from machinery that
works without one. If a brief asks for generated footage, Lille
reports that as a blocker instead of faking it.

## The pipeline (four tools, all Sentinel-gated, sandbox-confined)

1. **`capture_walkthrough`** — scripted capture of a live web app in
   headless Chromium (CDP over Node's built-in WebSocket;
   `free_agent/tools/capture/`). A step list drives the app:
   `goto`, `wait`, `wait_text`, `wait_selector`, `click_text`,
   `click_selector`, `click_if_text`, `type`, `check`,
   `scroll_into_view`, `screenshot`, plus two app adapters
   (`parse_seed` + `solve_word_quiz` for Fluid-style seed-backup
   quizzes, `unlock_if_present`). Output: PNG frames + `manifest.json`
   with per-frame hold seconds, captions, and **page-text excerpts —
   the evidence** spec mode checks the brief's own phrases against.
   This is a scripted capture instrument, *not* general browser
   control (BENCHMARK.md gap 2 stays open).
2. **`render_card`** — title/section cards rendered with PIL
   (project colours from the brief; the Lille mascot can be placed
   from the repo assets).
3. **`narrate`** — voiceover via a Fallback-Law TTS chain (below).
4. **`assemble_video`** — frames scaled/padded to the canvas,
   captions burned into a bottom bar, optional per-scene blur
   regions (e.g. over a seed phrase), narration mixed in (a scene
   stretches to fit its line), H.264/yuv420p MP4 out. **`verify_video`**
   (and the assembly itself) returns an ffprobe `VIDEO_OK` evidence
   line — duration, resolution, codecs, bytes. Spec mode's finish
   guard requires that evidence before a video spec can report
   SHIPPED, and duration criteria are graded from the ffprobe number,
   not from the model's summary.

A video brief is just a spec (see specmode): Goal = the video and
its shot list, Deliverables = the `.mp4`, Acceptance criteria =
duration range, what must be shown, narration-or-captions, cost $0.

## TTS chain (Fallback Law, free routes only)

| # | Route | Status |
|---|---|---|
| 1 | **edge-tts** (Microsoft Edge TTS, keyless; optional install at `~/.freeagent/tools/edge-tts-venv` or on PATH) | Best quality. **On this build host it currently fails**: the synthesis websocket's TLS handshake fails verification through the host's egress proxy (`SSLCertVerificationError`). The chain falls through automatically; on an unproxied host this route serves first. |
| 2 | **Google Translate TTS** (keyless HTTP endpoint, chunked at ~180 chars) | **Serving** — this is the route that narrated the v0.6 proof video. |
| 3 | **Local flite / espeak-ng** (fully offline, robotic) | Not installed on this build host; used automatically where present. |

If every route fails, `narrate` returns `NARRATION_FAILED` with the
reasons and the video ships **captions-only, labelled as such**.
Audio is never faked, and the serving route is named in the task
trace and the proof file.

## The hard boundary — read this before using Lille videos anywhere

Some competitions and grants require the **entrant personally on
camera** explaining the project (the Cognizant Agentic Web Challenge
was scrapped on exactly this ground). A Lille-made video **does not
satisfy such a rule and must never be passed off as Ethuram**.
Spec mode enforces this deterministically: a spec whose goal, rules
or criteria demand a personal appearance ("personally", "on camera",
"record yourself", …) is refused before any work runs, with a
`NEEDS ETHURAM` report naming the single step only he can take.
Lille can still prepare the script, slides and edit around his own
recording — she just cannot *be* him.

Where the rules allow an AI-made demo (the common case for product
demo videos in grant applications and hackathons), this rung reopens
lanes that a video requirement previously closed.

## Honest limits

- Capture is only as reliable as the app under it: live route
  searches, name-registry checks and other network fan-outs from a
  throttled host can take minutes or time out; the capture reports
  what actually happened, and a failed capture is a failed step the
  model may retry — never a stock-footage substitute.
- The pipeline is core + runner-lane. The Android app does not make
  videos yet; it carries the version and this roadmap.
- Portrait 720×1280 is the default canvas (phone-first demos);
  landscape is a parameter, not a different tool.
