"""Video production tools (v0.6) — code-driven video, NO generative
video models (they are unavailable on this account's region and are
never called; see VIDEO.md).

The pipeline is deterministic wrappers around real local machinery —
the model never gets a raw shell:

- capture_walkthrough: scripted headless-Chromium capture of a live
  web app (tools/capture/*.mjs — CDP over Node's built-in WebSocket,
  a local relay proxy when the host needs one). Emits PNG frames +
  a manifest.json with per-frame hold durations and page-text
  excerpts (the excerpts are EVIDENCE: spec mode checks the brief's
  own phrases against text the captured pages actually showed).
- render_card: title/section cards rendered with PIL.
- narrate: text-to-speech via a Fallback-Law chain of free routes
  (edge-tts -> Google Translate TTS -> local flite/espeak). The
  result names the route that served; if every route fails the tool
  says so plainly and the video ships captions-only, labelled as
  such. Audio is never faked.
- assemble_video: frames + captions + optional narration -> MP4
  (H.264, yuv420p) via ffmpeg, then a verify step (ffprobe) whose
  numbers are returned as a parseable VIDEO_OK line — spec mode's
  finish guard requires that evidence before SHIPPED.
- verify_video: standalone ffprobe evidence for an existing file.

Everything is confined to the task sandbox (sandbox.py rules).
"""
import json, os, re, shutil, subprocess, sys, urllib.parse, urllib.request

from . import sandbox

CAPTURE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "tools", "capture")
MAX_SCENES = 24
MAX_NARRATION_CHARS = 1200
DEFAULT_W, DEFAULT_H = 720, 1280  # portrait 720p — phone-first demos

# ---------------------------------------------------------------- ffmpeg

def find_ffmpeg():
    return os.environ.get("LILLE_FFMPEG") or shutil.which("ffmpeg")

def find_ffprobe():
    return os.environ.get("LILLE_FFPROBE") or shutil.which("ffprobe")

def _run(cmd, timeout=300):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

def probe_media(path):
    """ffprobe a media file. Returns a dict or raises SandboxError."""
    ffprobe = find_ffprobe()
    if not ffprobe:
        raise sandbox.SandboxError("ffprobe not found on this host")
    r = _run([ffprobe, "-v", "error", "-print_format", "json",
              "-show_format", "-show_streams", path], timeout=60)
    if r.returncode != 0:
        raise sandbox.SandboxError(f"ffprobe failed: {r.stderr.strip()[:200]}")
    return json.loads(r.stdout or "{}")

def media_duration(path):
    info = probe_media(path)
    try:
        return float(info.get("format", {}).get("duration", 0) or 0)
    except (TypeError, ValueError):
        return 0.0

def video_evidence_line(path):
    """The parseable evidence line spec mode grades against."""
    info = probe_media(path)
    streams = info.get("streams", [])
    v = next((s for s in streams if s.get("codec_type") == "video"), None)
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)
    dur = 0.0
    try:
        dur = float(info.get("format", {}).get("duration", 0) or 0)
    except (TypeError, ValueError):
        pass
    size = os.path.getsize(path)
    if v is None:
        return f"VIDEO_FAIL path={path} reason=no-video-stream bytes={size}"
    return (f"VIDEO_OK path={path} duration={dur:.1f}s "
            f"size={v.get('width')}x{v.get('height')} "
            f"video={v.get('codec_name')} "
            f"audio={a.get('codec_name') if a else 'none'} bytes={size}")

def verify_video(task_dir, args):
    target = sandbox.resolve_inside(task_dir, args.get("path", ""))
    if not os.path.isfile(target):
        return f"error: no such file: {args.get('path')}"
    try:
        return video_evidence_line(target)
    except sandbox.SandboxError as e:
        return f"VIDEO_FAIL path={target} reason={e}"

# ---------------------------------------------------------------- PIL cards

def _font(size, bold=True):
    from PIL import ImageFont
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold
        else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf" if bold
        else "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return ImageFont.truetype(c, size)
    return ImageFont.load_default()

def _hex(color, default):
    color = str(color or default).strip()
    if not re.fullmatch(r"#[0-9a-fA-F]{6}", color):
        color = default
    return tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))

def _wrap(draw, text, font, max_width):
    words, lines, cur = str(text).split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if draw.textlength(trial, font=font) <= max_width or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines

def render_card(task_dir, args):
    """Render a title/section card PNG inside the sandbox."""
    from PIL import Image, ImageDraw
    out = sandbox.resolve_inside(task_dir, args.get("out", "card.png"))
    w = int(args.get("width") or DEFAULT_W)
    h = int(args.get("height") or DEFAULT_H)
    w = max(160, min(w, 1920))
    h = max(160, min(h, 1920))
    bg = _hex(args.get("bg"), "#0b0e13")
    fg = _hex(args.get("fg"), "#ffffff")
    accent = _hex(args.get("accent"), "#f7931a")
    img = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(img)
    # accent rule near the top third
    d.rectangle([w * 0.08, h * 0.30, w * 0.30, h * 0.30 + max(6, h // 120)],
                fill=accent)
    title = str(args.get("title", "")).strip()
    subtitle = str(args.get("subtitle", "")).strip()
    y = h * 0.34
    if title:
        f = _font(max(28, w // 11))
        for line in _wrap(d, title, f, w * 0.84):
            d.text((w * 0.08, y), line, font=f, fill=fg)
            y += f.size * 1.25
    if subtitle:
        f = _font(max(20, w // 22))
        y += h * 0.03
        for line in _wrap(d, subtitle, f, w * 0.84):
            d.text((w * 0.08, y), line, font=f, fill=accent)
            y += f.size * 1.3
    mascot = args.get("mascot")
    if mascot:
        try:
            mpath = sandbox.resolve_inside(task_dir, mascot)
            if os.path.isfile(mpath):
                from PIL import Image as PILImage
                m = PILImage.open(mpath).convert("RGBA")
                side = int(w * 0.52)
                m.thumbnail((side, side))
                img.paste(m, (w - m.width - int(w * 0.06),
                              h - m.height - int(h * 0.05)), m)
        except Exception:
            pass  # a card without the mascot is still a card
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, "PNG")
    return f"CARD_OK path={os.path.relpath(out, task_dir)} size={w}x{h}"

# ------------------------------------------------------- caption compositing

def _captioned_frame(src_path, dest_path, caption, width, height,
                     blur_regions=None, bg=(11, 14, 19)):
    """Scale src to fit a width x height canvas (dark pad), apply any
    blur regions (fractions of the SOURCE image), burn the caption
    into a bottom bar. Returns dest_path."""
    from PIL import Image, ImageDraw, ImageFilter
    img = Image.open(src_path).convert("RGB")
    if blur_regions:
        for reg in blur_regions:
            try:
                x, y, bw, bh = (float(v) for v in reg)
            except (TypeError, ValueError):
                continue
            box = (int(img.width * x), int(img.height * y),
                   int(img.width * (x + bw)), int(img.height * (y + bh)))
            if box[2] > box[0] and box[3] > box[1]:
                region = img.crop(box).filter(
                    ImageFilter.GaussianBlur(radius=18))
                img.paste(region, box[:2])
    canvas = Image.new("RGB", (width, height), bg)
    scale = min(width / img.width, height / img.height)
    nw, nh = max(1, int(img.width * scale)), max(1, int(img.height * scale))
    img = img.resize((nw, nh), Image.LANCZOS)
    canvas.paste(img, ((width - nw) // 2, (height - nh) // 2))
    if caption:
        d = ImageDraw.Draw(canvas, "RGBA")
        f = _font(max(20, width // 21))
        lines = _wrap(d, caption, f, width * 0.92)
        line_h = f.size * 1.3
        bar_h = int(line_h * len(lines) + height * 0.03)
        d.rectangle([0, height - bar_h, width, height],
                    fill=(0, 0, 0, 178))
        y = height - bar_h + height * 0.012
        for line in lines:
            tw = d.textlength(line, font=f)
            d.text(((width - tw) / 2, y), line, font=f,
                   fill=(255, 255, 255))
            y += line_h
    canvas.save(dest_path, "PNG")
    return dest_path

# ---------------------------------------------------------------- TTS chain

def _edge_tts_cmd():
    """Locate an edge-tts executable: PATH, the documented optional
    venv (~/.freeagent/tools/edge-tts-venv), or the current python."""
    exe = shutil.which("edge-tts")
    if exe:
        return [exe]
    venv_exe = os.path.expanduser(
        "~/.freeagent/tools/edge-tts-venv/bin/edge-tts")
    if os.path.isfile(venv_exe):
        return [venv_exe]
    if subprocess.run([sys.executable, "-c", "import edge_tts"],
                      capture_output=True).returncode == 0:
        return [sys.executable, "-m", "edge_tts"]
    return None


def _tts_edge_tts(text, out_path):
    """Route 1: Microsoft Edge TTS via the free `edge-tts` package
    (keyless). Raises on any failure — the chain decides. (On this
    build host it currently fails TLS verification on the synthesis
    websocket through the egress proxy; the chain falls through —
    see VIDEO.md.)"""
    cmd = _edge_tts_cmd()
    if cmd is None:
        raise RuntimeError("edge-tts package not installed")
    proxy = (os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
             or os.environ.get("HTTP_PROXY") or os.environ.get("http_proxy"))
    full = cmd + ["--text", text,
                  "--voice", os.environ.get("LILLE_TTS_VOICE",
                                            "en-US-AriaNeural"),
                  "--write-media", out_path]
    if proxy:
        full += ["--proxy", proxy]
    r = _run(full, timeout=120)
    if r.returncode != 0 or not os.path.isfile(out_path) \
            or os.path.getsize(out_path) < 1000:
        tail = (r.stderr or "").strip().splitlines()
        raise RuntimeError("edge-tts failed: "
                           + (tail[-1][:160] if tail else "no output"))

def _tts_google(text, out_path):
    """Route 2: Google Translate TTS (keyless HTTP endpoint, the same
    one browsers use). Text is chunked to the endpoint's ~200-char
    per-request limit; chunks are concatenated with ffmpeg."""
    chunks, cur = [], ""
    for word in str(text).split():
        if len(cur) + len(word) + 1 > 180 and cur:
            chunks.append(cur)
            cur = word
        else:
            cur = (cur + " " + word).strip()
    if cur:
        chunks.append(cur)
    parts = []
    tmpdir = out_path + ".parts"
    os.makedirs(tmpdir, exist_ok=True)
    try:
        for i, chunk in enumerate(chunks):
            url = ("https://translate.google.com/translate_tts?ie=UTF-8"
                   "&client=tw-ob&tl=en&q=" +
                   urllib.parse.quote(chunk))
            req = urllib.request.Request(
                url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read()
            if len(data) < 800:
                raise RuntimeError("google tts returned a suspiciously "
                                   "small response")
            part = os.path.join(tmpdir, f"{i:02d}.mp3")
            with open(part, "wb") as f:
                f.write(data)
            parts.append(part)
        if len(parts) == 1:
            shutil.copyfile(parts[0], out_path)
        else:
            ffmpeg = find_ffmpeg()
            if not ffmpeg:
                raise RuntimeError("ffmpeg needed to join TTS chunks")
            lst = out_path + ".concat.txt"
            with open(lst, "w") as f:
                for p in parts:
                    f.write(f"file '{p}'\n")
            r = _run([ffmpeg, "-y", "-v", "error", "-f", "concat",
                      "-safe", "0", "-i", lst, "-c", "copy", out_path],
                     timeout=120)
            os.unlink(lst)
            if r.returncode != 0:
                raise RuntimeError("ffmpeg chunk join failed")
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

def _tts_local(text, out_path):
    """Route 3: a local speech binary (flite / espeak-ng / espeak) —
    robotic but fully offline. Raises when none is installed."""
    flite = shutil.which("flite")
    espeak = shutil.which("espeak-ng") or shutil.which("espeak")
    wav = out_path + ".wav"
    if flite:
        r = _run([flite, "-t", text, "-o", wav], timeout=120)
    elif espeak:
        r = _run([espeak, "-w", wav, text], timeout=120)
    else:
        raise RuntimeError("no local TTS binary (flite/espeak) installed")
    if r.returncode != 0 or not os.path.isfile(wav):
        raise RuntimeError("local TTS failed")
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise RuntimeError("ffmpeg needed to encode local TTS output")
    r = _run([ffmpeg, "-y", "-v", "error", "-i", wav, out_path],
             timeout=120)
    os.unlink(wav)
    if r.returncode != 0:
        raise RuntimeError("ffmpeg encode of local TTS failed")

TTS_ROUTES = [("edge-tts", _tts_edge_tts),
              ("google-translate-tts", _tts_google),
              ("local-flite-espeak", _tts_local)]

def narrate(task_dir, args):
    """Synthesize narration for a list of scenes [{id, text}].
    Fallback-Law chain; the serving route is named in the result.
    Total failure is reported honestly — never faked audio."""
    scenes = args.get("scenes") or []
    if not scenes or not isinstance(scenes, list):
        return "error: narrate needs a non-empty 'scenes' list"
    if len(scenes) > MAX_SCENES:
        return f"error: too many scenes (max {MAX_SCENES})"
    out_dir = sandbox.resolve_inside(task_dir, args.get("out_dir", "audio"))
    os.makedirs(out_dir, exist_ok=True)
    results, failures = [], []
    route_used = None
    for scene in scenes:
        sid = re.sub(r"[^A-Za-z0-9_-]", "_", str(scene.get("id", "scene")))
        text = str(scene.get("text", "")).strip()
        if not text:
            results.append(f"{sid}: skipped (no text)")
            continue
        if len(text) > MAX_NARRATION_CHARS:
            return (f"error: narration for {sid} is {len(text)} chars "
                    f"(max {MAX_NARRATION_CHARS}) — shorten the script")
        out_path = os.path.join(out_dir, sid + ".mp3")
        served = None
        for name, fn in TTS_ROUTES:
            try:
                fn(text, out_path)
                served = name
                break
            except Exception as e:
                failures.append(f"{sid}/{name}: {e}")
        if served is None:
            return ("NARRATION_FAILED no TTS route could synthesize "
                    f"scene {sid}. Routes tried: "
                    + "; ".join(failures[-3:])[:400])
        route_used = route_used or served
        try:
            dur = media_duration(out_path)
        except Exception:
            dur = 0.0
        results.append(f"{sid}: {os.path.relpath(out_path, task_dir)} "
                       f"dur={dur:.1f}s via {served}")
    return (f"NARRATION_OK route={route_used} scenes={len(scenes)} | "
            + " | ".join(results))

# ---------------------------------------------------------------- capture

def resolve_steps(task_dir, args):
    """Return (steps, error). Steps come inline (args['steps']) or
    from args['steps_file'] — a JSON array the model writes flat with
    write_file, because small keyless models reliably fail to emit a
    long step list as nested JSON inside one tool call. The file is
    looked up task-relative first, then under repo/ (spec mode tells
    the model to build in repo/, and models mix the two up). Errors
    are precise enough for a small model to self-correct."""
    steps = args.get("steps")
    if isinstance(steps, list) and steps:
        return steps, None
    sf = args.get("steps_file")
    if isinstance(sf, str) and sf.strip():
        for cand in (sf, os.path.join("repo", sf)):
            try:
                spath = sandbox.resolve_inside(task_dir, cand)
            except sandbox.SandboxError:
                continue
            if os.path.isfile(spath):
                try:
                    with open(spath, encoding="utf-8") as f:
                        data = json.load(f)
                except (OSError, ValueError) as e:
                    return None, f"steps_file {sf!r} is not valid JSON: {e}"
                if isinstance(data, list) and data:
                    return data, None
                return None, f"steps_file {sf!r} must contain a non-empty " \
                             "JSON array of step objects"
        return None, f"steps_file not found: {sf!r} — write it first " \
                     "with write_file (a flat JSON array of steps), " \
                     "then call capture_walkthrough again"
    return None, "capture_walkthrough needs a non-empty 'steps' list " \
                 "(or steps_file naming a written steps JSON file)"


def capture_walkthrough(task_dir, args):
    """Run the scripted Chromium capture. args: steps (list), name
    (frames subdir), width/height, expect_text (list of phrases the
    brief says must be shown — checked against captured page text)."""
    node = shutil.which("node")
    if not node:
        return "error: node not found on this host (capture engine needs node)"
    chrome = os.environ.get("LILLE_CHROME", "/opt/meta-chromium/chrome")
    if not os.path.isfile(chrome):
        chrome = shutil.which("chromium") or shutil.which("chromium-browser") \
            or shutil.which("google-chrome")
    if not chrome:
        return "error: no Chromium binary found for capture"
    steps, serr = resolve_steps(task_dir, args)
    if serr:
        return f"error: {serr}"
    if len(steps) > 60:
        return "error: too many capture steps (max 60)"
    for s in steps:
        if s.get("action") == "goto":
            url = str(s.get("url", ""))
            if not re.match(r"^https?://", url):
                return f"error: capture goto URL must be http(s): {url[:80]!r}"
    name = re.sub(r"[^A-Za-z0-9_-]", "_", str(args.get("name", "capture")))
    frames_dir = sandbox.resolve_inside(task_dir, name)
    os.makedirs(frames_dir, exist_ok=True)
    steps_path = os.path.join(frames_dir, "steps.json")
    with open(steps_path, "w") as f:
        json.dump(steps, f)
    cmd = [node, os.path.join(CAPTURE_DIR, "capture.mjs"),
           "--steps", steps_path, "--out", frames_dir,
           "--width", str(int(args.get("width") or 450)),
           "--height", str(int(args.get("height") or 800)),
           "--chrome", chrome]
    try:
        r = _run(cmd, timeout=int(args.get("timeout") or 900))
    except subprocess.TimeoutExpired:
        return "error: capture timed out"
    out_lines = (r.stdout or "").strip().splitlines()
    summary = out_lines[-1] if out_lines else ""
    if r.returncode != 0 or "CAPTURE_OK" not in summary:
        err_tail = (r.stderr or "").strip()[-350:]
        out_tail = (r.stdout or "").strip()[-150:]
        return f"error: capture failed: {err_tail} | log tail: {out_tail}"
    manifest_path = os.path.join(frames_dir, "manifest.json")
    expect = [str(x) for x in (args.get("expect_text") or [])]
    found, missing = [], []
    if expect and os.path.isfile(manifest_path):
        with open(manifest_path) as f:
            manifest = json.load(f)
        all_text = "\n".join(fr.get("text_excerpt", "")
                             for fr in manifest.get("frames", []))
        for phrase in expect:
            (found if phrase.lower() in all_text.lower()
             else missing).append(phrase)
    frames_n = summary.split("frames=")[-1].split()[0] if "frames=" in summary else "?"
    msg = f"CAPTURE_OK frames={frames_n} dir={name} manifest={name}/manifest.json"
    if expect:
        msg += (f" expect_found={len(found)}/{len(expect)}"
                f" found={found} missing={missing}")
    return msg

# ---------------------------------------------------------------- assembly

def build_segment_command(ffmpeg, frame_png, audio_path, duration, seg_path,
                          fps=30):
    """Pure construction (unit-tested): one scene -> one MP4 segment
    with a uniform audio track (real narration, or generated silence
    so segments can be concatenated losslessly)."""
    cmd = [ffmpeg, "-y", "-v", "error",
           "-loop", "1", "-framerate", str(fps), "-i", frame_png]
    if audio_path:
        cmd += ["-i", audio_path]
    else:
        cmd += ["-f", "lavfi", "-i",
                "anullsrc=channel_layout=stereo:sample_rate=44100"]
    # NB: no -shortest here. It used to truncate every narrated
    # segment to the raw audio length, silently discarding the
    # scene's hold (and the +0.4s tail assemble adds) — a 49s plan
    # came out at exactly the sum of the narration files. apad pads
    # the audio with silence up to the -t cap instead.
    cmd += ["-map", "0:v:0", "-map", "1:a:0",
            "-t", f"{duration:.2f}", "-af", "apad",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-r", str(fps),
            "-c:a", "aac", "-b:a", "128k", seg_path]
    return cmd

def _resolve_media(task_dir, rel):
    """Resolve a media path task-relative; if missing there, try
    under repo/ (spec mode tells the model to build in repo/, and
    models mix the two roots up). Returns the task-relative path if
    neither exists, so the caller's error names what was asked for."""
    p = sandbox.resolve_inside(task_dir, rel)
    if os.path.isfile(p):
        return p
    if not str(rel).startswith("repo/"):
        p2 = sandbox.resolve_inside(task_dir, os.path.join("repo", str(rel)))
        if os.path.isfile(p2):
            return p2
    return p


def assemble_video(task_dir, args):
    """Assemble scenes [{image, duration, caption, audio, blur}] into
    an MP4 and return ffprobe evidence. Segment durations extend to
    fit narration audio (+0.4s tail) when audio is present."""
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        return "error: ffmpeg not found on this host"
    scenes = args.get("scenes") or []
    if not scenes or not isinstance(scenes, list):
        return "error: assemble_video needs a non-empty 'scenes' list"
    if len(scenes) > MAX_SCENES:
        return f"error: too many scenes (max {MAX_SCENES})"
    # Friendly top-level shapes → per-scene fields. Models describe
    # the same assembly both ways (per-scene audio/blur, or a
    # narration_dir + ordered narration_ids + blur_regions map, and
    # 'hold' for 'duration' — the capture manifest's word); accept
    # both deterministically instead of failing the run on dialect.
    ndir = args.get("narration_dir")
    nids = args.get("narration_ids") or []
    breg = args.get("blur_regions") or {}
    for i, scene in enumerate(scenes):
        if not isinstance(scene, dict):
            continue
        if scene.get("duration") is None and scene.get("hold") is not None:
            scene["duration"] = scene["hold"]
        if ndir and i < len(nids) and not scene.get("audio"):
            scene["audio"] = os.path.join(str(ndir), str(nids[i]) + ".mp3")
        if not scene.get("blur") and isinstance(breg, dict):
            regs = breg.get(str(i), breg.get(i))
            if regs:
                scene["blur"] = regs
    out = sandbox.resolve_inside(task_dir, args.get("out", "video.mp4"))
    width = int(args.get("width") or DEFAULT_W)
    height = int(args.get("height") or DEFAULT_H)
    work = sandbox.resolve_inside(task_dir, ".assemble-work")
    if os.path.isdir(work):
        shutil.rmtree(work)
    os.makedirs(work)
    seg_paths, captions_used, total = [], 0, 0.0
    narrated = 0
    try:
        for i, scene in enumerate(scenes):
            img = _resolve_media(task_dir, scene.get("image", ""))
            if not os.path.isfile(img):
                return f"error: scene {i} image missing: {scene.get('image')}"
            dur = float(scene.get("duration") or 4)
            dur = max(1.0, min(dur, 60.0))
            audio_path = None
            if scene.get("audio"):
                narrated += 1
                audio_path = _resolve_media(task_dir, scene["audio"])
                if not os.path.isfile(audio_path):
                    return f"error: scene {i} audio missing: {scene['audio']}"
                try:
                    adur = media_duration(audio_path)
                except Exception:
                    adur = 0.0
                if adur > 0:
                    dur = max(dur, adur + 0.4)
            caption = str(scene.get("caption", "")).strip()
            if caption:
                captions_used += 1
            frame_png = os.path.join(work, f"frame-{i:02d}.png")
            _captioned_frame(img, frame_png, caption, width, height,
                             blur_regions=scene.get("blur"))
            seg = os.path.join(work, f"seg-{i:02d}.mp4")
            r = _run(build_segment_command(ffmpeg, frame_png, audio_path,
                                           dur, seg), timeout=300)
            if r.returncode != 0:
                return f"error: ffmpeg segment {i} failed: {r.stderr[:300]}"
            seg_paths.append(seg)
            total += dur
        lst = os.path.join(work, "concat.txt")
        with open(lst, "w") as f:
            for p in seg_paths:
                f.write(f"file '{p}'\n")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        r = _run([ffmpeg, "-y", "-v", "error", "-f", "concat", "-safe", "0",
                  "-i", lst, "-c", "copy", out], timeout=300)
        if r.returncode != 0:  # fall back to a re-encode concat
            r = _run([ffmpeg, "-y", "-v", "error", "-f", "concat",
                      "-safe", "0", "-i", lst, "-c:v", "libx264",
                      "-pix_fmt", "yuv420p", "-c:a", "aac", out],
                     timeout=600)
            if r.returncode != 0:
                return f"error: ffmpeg concat failed: {r.stderr[:300]}"
    finally:
        shutil.rmtree(work, ignore_errors=True)
    try:
        evidence = video_evidence_line(out)
    except sandbox.SandboxError as e:
        return f"error: assembled file failed verification: {e}"
    return (f"{evidence} scenes={len(scenes)} captions={captions_used} "
            f"narrated_scenes={narrated} planned_duration={total:.1f}s")
