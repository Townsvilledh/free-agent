# Spec — Fluid demo video (Lille v0.6 live proof)

## Goal
Produce a short demo video of the Fluid web wallet (live app at
https://fluidwallet.dpdns.org/): open the app, create a wallet, land
on the home screen, open a swap and show a live quote. Title card,
captions on every scene, and narration if a TTS route serves.
The wallet created in the video is a brand-new THROWAWAY demo
wallet made only for filming: it holds zero funds, its password is
a throwaway invented for the demo, and its seed-phrase frame is
blurred in the final video (the blur step is in the plan). Nothing
real is at risk and no real user's credentials are involved.

Work plan (follow in order):
1. render_card: title card to cards/title.png — title "Fluid",
   subtitle "Every chain. One wallet. Yours alone.", bg #0b0e13,
   accent #f7931a, text #f5f5f5, width 720, height 1280.
2. render_card: end card to cards/end.png — title "Fluid",
   subtitle "Your keys never leave your phone.", same colours.
3. FIRST write_file the steps JSON below, exactly as given, to
   "repo/capture-steps.json" (a flat JSON array, nothing else in the
   file). THEN capture_walkthrough with steps_file
   "repo/capture-steps.json" (do NOT pass steps inline — long nested
   JSON in one tool call fails), name "capture", width 450, height 800,
   timeout 1200, expect_text ["Create new wallet",
   "Write these 12 words", "Prove the backup works",
   "Across all networks", "Get routes", "You receive"]. The steps:
[
  {"action": "goto", "url": "https://fluidwallet.dpdns.org/", "settle": 6, "shot": {"name": "landing", "hold": 5, "caption": "Fluid — every chain, one wallet"}},
  {"action": "type", "selector": "#createName", "text": "lille_v06_take7"},
  {"action": "type", "selector": "#createPw", "text": "LilleDemo-V06-pass"},
  {"action": "type", "selector": "#createPw2", "text": "LilleDemo-V06-pass", "shot": {"name": "form", "hold": 5, "caption": "Create a wallet — pick a name, set a password"}},
  {"action": "click_text", "text": "Create new wallet", "settle": 3},
  {"action": "wait_text", "text": "Write these 12 words", "timeout": 60},
  {"action": "scroll_into_view", "text": "Write these 12 words"},
  {"action": "screenshot", "name": "seed", "hold": 6, "caption": "Your seed phrase — written on paper, never shared"},
  {"action": "parse_seed", "start": "Write these 12 words", "end": "I wrote my seed phrase", "save_as": "seed"},
  {"action": "check", "selector": "#seedCheck"},
  {"action": "click_text", "text": "Continue →", "settle": 2.5},
  {"action": "wait_text", "text": "Prove the backup works", "timeout": 60},
  {"action": "solve_word_quiz", "seed_var": "seed"},
  {"action": "scroll_into_view", "text": "Prove the backup works"},
  {"action": "screenshot", "name": "quiz", "hold": 5, "caption": "Prove the backup works — pick the right words"},
  {"action": "click_text", "text": "Check my words", "settle": 3},
  {"action": "wait_text", "text": "Continue without a username", "timeout": 40},
  {"action": "click_text", "text": "Continue without a username", "settle": 3},
  {"action": "unlock_if_present", "password": "LilleDemo-V06-pass", "settle": 5},
  {"action": "wait_text", "text": "Across all networks", "timeout": 60},
  {"action": "wait", "seconds": 4, "shot": {"name": "home", "hold": 7, "caption": "The home screen — every chain, one balance"}},
  {"action": "click_text", "text": "Swap", "settle": 2.5, "shot": {"name": "swap", "hold": 5, "caption": "Open a swap — any chain to any chain"}},
  {"action": "type", "selector": "#wAmt", "text": "0.05"},
  {"action": "click_text", "text": "Get routes", "settle": 2},
  {"action": "wait_text", "text": "You receive", "timeout": 240},
  {"action": "scroll_into_view", "text": "You receive"},
  {"action": "screenshot", "name": "quote", "hold": 8, "caption": "A live quote — best route, real price"}
]
4. narrate ONCE with EXACTLY these arguments (copy them):
   {"out_dir": "narration", "scenes": [
     {"id": "title", "text": "Fluid. Every chain. One wallet. Yours alone."},
     {"id": "landing", "text": "This is Fluid, a wallet that holds every blockchain in one place."},
     {"id": "form", "text": "Creating a wallet takes seconds. Pick a name, and set a password."},
     {"id": "seed", "text": "Fluid shows your seed phrase once. Write it on paper, and never share it."},
     {"id": "quiz", "text": "Then you prove the backup works, by picking the right words."},
     {"id": "home", "text": "The home screen shows every chain, with one balance across all networks."},
     {"id": "swap", "text": "Open a swap, choose any chain to any chain, and enter an amount."},
     {"id": "quote", "text": "Fluid searches live routes, and shows the best quote with its real cost."},
     {"id": "end", "text": "Fluid. Your keys never leave your phone."}]}
5. assemble_video ONCE with EXACTLY these arguments (copy them):
   {"out": "live-tests/2026-11-25-v06-fluid-demo.mp4",
    "width": 720, "height": 1280,
    "narration_dir": "narration",
    "narration_ids": ["title", "landing", "form", "seed", "quiz", "home", "swap", "quote", "end"],
    "blur_regions": {"3": [[0.06, 0.52, 0.88, 0.26]]},
    "scenes": [
      {"image": "cards/title.png", "hold": 4, "caption": "Fluid — every chain, one wallet"},
      {"image": "capture/001-landing.png", "hold": 5, "caption": "Fluid — every chain, one wallet"},
      {"image": "capture/002-form.png", "hold": 5, "caption": "Create a wallet — pick a name, set a password"},
      {"image": "capture/003-seed.png", "hold": 6, "caption": "Your seed phrase — written on paper, never shared (blurred here)"},
      {"image": "capture/004-quiz.png", "hold": 5, "caption": "Prove the backup works — pick the right words"},
      {"image": "capture/005-home.png", "hold": 8, "caption": "The home screen — every chain, one balance"},
      {"image": "capture/006-swap.png", "hold": 5, "caption": "Open a swap — any chain to any chain"},
      {"image": "capture/007-quote.png", "hold": 10, "caption": "A live quote — best route, real price"},
      {"image": "cards/end.png", "hold": 4, "caption": "Fluid — your keys never leave your phone"}]}
   (Scene index 3 is the seed frame — the blur region covers the
   words; do not remove it.)
6. verify_video on the MP4, then finish with a summary that includes
   the VIDEO_OK evidence line.

CALL DISCIPLINE (binding): call each tool the number of times this
plan says — render_card twice, write_file once, capture_walkthrough
once (twice only if the first call returns an error), narrate once,
assemble_video once, verify_video once. After a tool returns OK,
NEVER call it again — move to the next numbered step.

## Hard rules
- Code-driven video only. NEVER call a video-generation model or API.
- The demo wallet is a throwaway with zero funds, created only for
  this video. Never send funds to it. The seed frame MUST be blurred
  in the assembly (blur_regions above).
- Cost: $0 — keyless routes only. No signups, no API keys.
- If the capture fails, retry it once. If it fails again, finish
  honestly as blocked — never substitute stock footage or a mock.

## Acceptance criteria
- Total duration 45–90 seconds, verified by ffprobe
- The video shows "Write these 12 words", "Across all networks" and "You receive" (wallet creation, home screen, live swap quote)
- Narration is present (a TTS route serves) or the video is captions-only
- Cost: $0

## Deliverables
- live-tests/2026-11-25-v06-fluid-demo.mp4

## Push
- none
