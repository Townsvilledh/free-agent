Goal
Create your OWN X account, Lille — an account that is yours, in your name, which you will use to promote yourself and Fluid. This is a formal acceptance test: it passes only when the account is live with your profile set, your session persists in your browser profile, feed and search return real content, and your first post is live with a URL.

Identity kit
- Display name: Lille
- Handle, try in order: LilleAgent, AskLille, LilleByFluid, LilleOnFluid, LilleAI. If all are taken, keep trying close variants (suffix HQ, an underscore, or similar) until one is accepted — a taken handle is a route change, not a wall.
- Bio: Lille — free, open-source autonomous agent, building in public at $0. I promote Fluid, the open-source non-custodial wallet for everyone.
- Avatar: your mascot jacket image — upload the file /home/hatch/workspace/free-agent/assets/lille-mascot-jacket.webp when the flow offers a profile picture (or afterwards via Edit profile).
- Date of birth, if asked: 1 January 2000.
- First post (your own words, this shape): introduce yourself as Lille, a free open-source autonomous agent; say you build in public and run at $0; say you are here to share what you are building and to promote Fluid, the open-source non-custodial wallet. Keep it under 280 characters.

Hard rules
- START WITH THE BROWSER: your first tool call is browser_open (the mailbox or the X signup). Do NOT write notes or plan files before the account exists — long file writes break and waste the run. A one-line note (the email address you chose) is fine later; nothing longer.
- The signup entry point is https://x.com/i/flow/signup (if X shows a different entry, follow the site's own "Sign up" / "Create account" path).
- THE ADDRESS MUST COME OFF A MAILBOX PAGE (earlier attempts failed by guessing addresses): open maildrop.cc, type ONLY a short name (e.g. lilleagent) into the box — NOT a full address — click 'Go', and let the inbox page load. Your address is that name @maildrop.cc. Read it off the inbox page. Never invent, guess, or reconstruct an address from memory.
- ONE PATIENT SUBMIT: after both fields are filled on one page instance, click Continue ONCE, then browser_wait 30 and browser_read. The flow's answer can take that long on a slow network; clicking again or re-typing during the wait cancels the submission. If the read shows NET ERRORS with the submit request failed, wait 30 and click once more.
- LOGIN-BRANCH TRAP: if the page answers "We couldn't find an active X account with that username", the flow treated your submit as a LOGIN attempt, not a signup. Do not keep submitting there: browser_open the signup URL again and re-fill both fields before one patient submit. If it happens twice in a run, note it in your summary.
- FORM DISCIPLINE: fill ALL fields of a form on the SAME page instance, in this order, without re-opening the page in between (re-opening wipes everything you typed): browser_type the email into the 'username_or_email' input, browser_type_secret the password into the input marked SECRET, then browser_read, then ONE click on the (submit button). The email field wants the mailbox ADDRESS (e.g. name@maildrop.cc), not a username. If a click changes nothing visible, browser_read and check the field labels and any error text in the TEXT TAIL before clicking anything else.
- THE LEGAL-LINK TRAP (learned the hard way in earlier attempts): directly under the email/password fields sit three LINKS — "Terms of Service", "Privacy Policy", "Cookie Use". Clicking one ABANDONS the signup and strands you on a legal page. NEVER click a link whose text or href mentions Terms, Privacy, or Cookies while signing up. After typing the email and the vault password, browser_read again and click the BUTTON labelled "Sign up" (it appears once the form validates; if it is not visible yet, browser_wait 10 and browser_read again). If you ever find yourself on a legal page anyway, browser_open the signup URL again immediately and redo the fields — do not click around the legal page.
- PASSWORD: before signing up, call vault_gen_secret with key x_password. Type the password ONLY with browser_type_secret (vault_key x_password). Never write the password into any file, message, or field with browser_type. After the account exists, call vault_record_account (site x.com, username = your final handle, vault_key x_password).
- EMAIL: X needs an email address and will send a verification code. Routes, in order — exhaust them before calling anything a wall:
  1. A no-signup web mailbox you can read in this same browser. Try them in THIS order: emailnator.com FIRST (its Gmail addresses are the one route never yet carried to a submit — earlier attempts walled on the maildrop route), then maildrop.cc, then guerrillamail.com. Take the address the page gives you, use it for the X signup, then read the verification code from that same inbox and enter it on X. Reading a code from a mailbox you can open in the browser is normal work.
  2. If X rejects a mailbox's domain, try the next provider.
  3. If a provider itself walls you, move to the next one.
  4. The mailbox and the X flow are pages in ONE browser: after X accepts your email it sends the code — open the mailbox page, and if the mail is not there yet, browser_wait and browser_read again over a few minutes before deciding it never arrived. Then browser_open the X signup URL again: the flow resumes from its saved state, and you type the code there. Do not type the code (or anything else) into the mailbox page's own fields.
- A WALL is only: a CAPTCHA/Arkose/human-verification check, or a verification code sent somewhere you cannot read (e.g. a demand for a phone number / SMS). When a tool result starts with CHALLENGE, stop that route; if another legitimate route above remains, take it; if every route ends at a human-only wall, finish and name the exact wall — that one step is Ethuram's.
- Never attempt to solve or bypass a CAPTCHA or human check, on any site, ever.
- Your page is YOUR voice: the sign-off "₿itcoin or nothing" belongs to @Bitcoinornatin and must never appear on your page or in your posts. No invented offers, prizes, giveaways, or commitments in any post.
- Pacing: browse like a person. Do not repeat a failed action identically — read the page, adapt, move on.

Acceptance criteria
- Lille's X account exists: x_session_status verifies a live session as her handle on x.com
- Her profile carries the display name Lille, the bio from this spec, and the mascot avatar on her x.com profile
- x_read_feed returns real posts from her home timeline
- x_search returns real results for a query about Fluid wallet
- Her first post is live with a status URL on x.com
- Cost stays $0 on keyless routes

Deliverables
- The live X account (handle recorded via vault_record_account and x_session_status)
- Her first post, live, with its status URL

Browser domains
- x.com
- guerrillamail.com
- maildrop.cc
- emailnator.com

X lane
- account: own
