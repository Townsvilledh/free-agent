# Spec — Lille promo video (her own walkthrough)

## Goal
Produce a short promo video OF the Lille Android app itself, for
Ethuram to send to users: what Lille is, the Fluid-name sign-in,
chat with many conversations, background tasks, her own browser,
backup that survives a new phone, her coming X page, and where to
get her. Title and feature cards, captions on every scene, and
narration if a TTS route serves.

Lille is an Android app, not a web app — there is NO capture step
in this plan and capture_walkthrough must NOT be called. The app
frames are REAL screenshots of the running app, pre-staged in the
repo at live-tests/lille-promo-assets/ (chat-login.png and
chat-zoom.png are two framings of her connected chat screen;
tasks.png is her Background tasks screen; browser.png is her
parked browser session). Use exactly those files.

Work plan (follow in order):
1. render_card FOUR times, copy-exact arguments:
   a. {"out": "repo/cards/title.png", "title": "Lille", "subtitle": "Your free autonomous agent — living on your phone.", "bg": "#000000", "fg": "#f5f5f5", "accent": "#f7931a", "width": 720, "height": 1280, "mascot": "repo/assets/lille-mascot-jacket.webp"}
   b. {"out": "repo/cards/backup.png", "title": "Backup that follows you", "subtitle": "Sealed with your wallet's signature. New phone — same Lille.", "bg": "#000000", "fg": "#f5f5f5", "accent": "#f7931a", "width": 720, "height": 1280, "mascot": "repo/assets/lille-mascot-cap.webp"}
   c. {"out": "repo/cards/xnext.png", "title": "Her own voice", "subtitle": "Next: her own X page — building in public, promoting Fluid and herself.", "bg": "#000000", "fg": "#f5f5f5", "accent": "#f7931a", "width": 720, "height": 1280, "mascot": "repo/assets/lille-mascot-working.webp"}
   d. {"out": "repo/cards/end.png", "title": "Get Lille — free", "subtitle": "townsvilledh.github.io/free-agent", "bg": "#000000", "fg": "#f5f5f5", "accent": "#f7931a", "width": 720, "height": 1280, "mascot": "repo/assets/lille-mascot-jacket.webp"}
2. narrate ONCE with EXACTLY these arguments (copy them):
   {"out_dir": "repo/narration", "scenes": [
     {"id": "title", "text": "Meet Lille. A free, autonomous agent who lives on your phone. No subscription. No API key. No account to create."},
     {"id": "login", "text": "She signs in with your Fluid name. Approve once in Fluid, and she knows it is you. Your wallet is your identity."},
     {"id": "chat", "text": "Talk to her like a friend. She keeps every conversation — run many chats at once, and pick each up where you left it."},
     {"id": "tasks", "text": "Give her a job, then close the app. Her tasks keep working while you are away. If a free route is busy, she waits and retries — she never fakes a failure."},
     {"id": "browser", "text": "She browses the web in her own session. When a site demands a human, she parks, asks for one tap, then carries on alone."},
     {"id": "backup", "text": "New phone? No problem. Her backup is sealed with your wallet's signature. Chats, tasks and secrets — restored. Same Lille, new home."},
     {"id": "xnext", "text": "And she is getting her own page on X — building in public, and promoting Fluid and herself."},
     {"id": "end", "text": "Get Lille free. The link is on the Bitcoin Or Nothing page. Free forever, for everyone."}]}
3. assemble_video ONCE with EXACTLY these arguments (copy them):
   {"out": "repo/live-tests/2026-11-26-lille-promo.mp4",
    "width": 720, "height": 1280,
    "narration_dir": "repo/narration",
    "narration_ids": ["title", "login", "chat", "tasks", "browser", "backup", "xnext", "end"],
    "scenes": [
      {"image": "repo/cards/title.png", "hold": 6, "caption": "Lille — free, autonomous, on your phone"},
      {"image": "repo/live-tests/lille-promo-assets/chat-login.png", "hold": 7, "caption": "Signs in with your Fluid name"},
      {"image": "repo/live-tests/lille-promo-assets/chat-zoom.png", "hold": 7, "caption": "Many chats — she keeps them all"},
      {"image": "repo/live-tests/lille-promo-assets/tasks.png", "hold": 8, "caption": "Tasks that work while you're away"},
      {"image": "repo/live-tests/lille-promo-assets/browser.png", "hold": 7, "caption": "She browses — and parks when a human is needed"},
      {"image": "repo/cards/backup.png", "hold": 7, "caption": "Backup that survives a new phone"},
      {"image": "repo/cards/xnext.png", "hold": 5, "caption": "Next: her own X page"},
      {"image": "repo/cards/end.png", "hold": 5, "caption": "Get Lille free — one link, on the Bitcoin Or Nothing page"}]}
4. verify_video ONCE with {"path": "repo/live-tests/2026-11-26-lille-promo.mp4"}, then finish with a summary that includes the VIDEO_OK evidence line.

CALL DISCIPLINE (binding): render_card four times (the four calls
above), narrate once, assemble_video once, verify_video once.
Never call capture_walkthrough — this plan has no capture step.
After a tool returns OK, NEVER call it again — move to the next
numbered step.

## Hard rules
- Code-driven video only. NEVER call a video-generation model or API.
- The app frames are the pre-staged real screenshots named in the goal — use exactly those files. Never invent, redraw or substitute a screen.
- Feature cards are designed cards (render_card) and must read as cards, never as app screens.
- Her X page does not exist yet — the X scene says it is coming ("Next"), never that it is live.
- No ₿ glyph and no Bitcoin-or-nothing sign-off in any card, caption or narration line — that branding is not Lille's.
- Cost: $0 — keyless routes only. No signups, no API keys.

## Acceptance criteria
- Total duration 55-90 seconds, verified by ffprobe
- Narration is present (a TTS route serves) or the video is captions-only
- Cost: $0

## Deliverables
- live-tests/2026-11-26-lille-promo.mp4

## Push
- none
