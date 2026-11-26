// browse.mjs — Lille's persistent interactive browser driver (v0.8).
//
// A long-lived headless Chromium (SAME relay/CDP machinery as the
// v0.6 capture engine) driven over JSON-lines on stdin/stdout by
// free_agent/browsertools.py. Unlike capture.mjs (a scripted,
// throwaway profile), this driver uses a PERSISTENT user-data-dir:
// sessions (e.g. a logged-in X account) survive between runs. The
// profile dir is host-only state — never committed, never exported.
//
// Protocol: one JSON object per line in, one per line out.
//   in : {id, action, ...args}
//   out: {id, ok: true, result} | {id, ok: false, error}
// Actions: open, read, click, type, upload, screenshot, wait, eval
// (eval is used ONLY by browsertools' own fixed X-lane expressions —
// it is never exposed to the model as a tool), close.
//
// Perception is structural (DESIGN #2): visible text + an element
// list with stable refs (data-lille-ref attributes assigned per
// read). Password/secret field VALUES are never read back; typing a
// secret happens only via the `secret: true` type action whose value
// travels this local pipe and is never logged here.
//
// Challenge honesty: after every action the page is checked for
// human-only walls (Arkose/funcaptcha/hCaptcha/reCAPTCHA iframes,
// "verify you are human" text, emailed-code prompts, phone demands).
// A hit is reported as result.challenge — Lille's loop must STOP and
// report it. This driver never attempts to solve or bypass one.
import { spawn, execSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';
import { CDP, sleep } from './cdp.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

function arg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
}

const PROFILE = arg('profile');
const WIDTH = Number(arg('width', '1000'));
const HEIGHT = Number(arg('height', '820'));
const CHROME = arg('chrome', '/opt/meta-chromium/chrome');
const DEBUG_PORT = Number(arg('port', '9341'));

let relay = null;
let chrome = null;
let cdp = null;

// Network-error perception (v0.8): an SPA submit that dies on a
// failed request looks identical to a slow one in the visible
// page — inert button, no error text (the X signup proof hit
// exactly this across attempts 18-19). The driver keeps the
// last few failed/4xx-5xx requests and every collect() carries
// them, so the model can SEE that the page's own calls failed.
const netErrors = [];
const netReqs = new Map();
function netNote(entry) {
  netErrors.push(entry);
  while (netErrors.length > 8) netErrors.shift();
}

const log = (...a) => console.error('[browse]', ...a);
const pace = () => sleep(400 + Math.floor(Math.random() * 500));

async function freePort() {
  for (const p of [18080, 18081, 18082, 18083, 18084]) {
    const busy = await new Promise((resolve) => {
      const s = new net.Socket();
      s.once('connect', () => { s.destroy(); resolve(true); });
      s.once('error', () => resolve(false));
      s.connect(p, '127.0.0.1');
    });
    if (!busy) return p;
  }
  return 18089;
}

async function startBrowser() {
  fs.mkdirSync(PROFILE, { recursive: true, mode: 0o700 });
  const chromeArgs = [
    '--headless=new', '--disable-gpu', '--no-sandbox',
    '--disable-dev-shm-usage',
    // Loopback targets (local dev servers, test fixtures) are
    // legitimate Lille work; Chrome's Local Network Access checks
    // otherwise block them with ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS.
    '--disable-features=LocalNetworkAccessChecks,LocalNetworkAccessPermissionPrompt',
    '--remote-debugging-port=' + DEBUG_PORT,
    '--remote-debugging-address=127.0.0.1',
    // Standard reduced desktop UA — the string ordinary Chrome
    // ships to every site. HeadlessChrome's self-advertising UA
    // is refused at the edge by x.com (HTTP 403 "Access denied"
    // before any page or challenge is served, verified live in
    // the v0.8 proof); a normal UA is browser configuration, not
    // challenge bypass — any Arkose/CAPTCHA X then serves is
    // still detected and still stops the run.
    '--user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36',
    '--user-data-dir=' + PROFILE,
    '--ignore-certificate-errors',
    '--window-size=' + WIDTH + ',' + HEIGHT,
    '--lang=en-GB', 'about:blank',
  ];
  const hasProxy = !!(process.env.HTTPS_PROXY || process.env.https_proxy ||
                      process.env.HTTP_PROXY || process.env.http_proxy);
  if (hasProxy) {
    const port = await freePort();
    relay = spawn(process.execPath, [path.join(HERE, 'relay.mjs'), String(port)],
                  { stdio: ['ignore', 'pipe', 'pipe'] });
    await sleep(800);
    chromeArgs.push('--proxy-server=http://127.0.0.1:' + port);
    // Loopback must never go through the egress relay: the proxy
    // cannot reach this host's own 127.0.0.1 (local dev servers and
    // fixtures would land on chrome-error://).
    chromeArgs.push('--proxy-bypass-list=127.0.0.1;localhost;[::1]');
  }
  const webglFile = '/var/lib/hatch-browser/rootfs-base/usr/share/hatch/chromium/webgl-allowed-domains.txt';
  if (fs.existsSync(webglFile) && CHROME.includes('meta-chromium')) {
    try {
      const sha = execSync(`sha256sum ${webglFile}`).toString().split(/\s+/)[0];
      chromeArgs.push('--hatch-webgl-allowed-domains-file=' + webglFile,
                      '--hatch-webgl-allowed-domains-sha256=' + sha);
    } catch {}
  }
  chrome = spawn(CHROME, chromeArgs, { stdio: ['ignore', 'pipe', 'pipe'] });
  let targets = null;
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`);
      targets = await r.json();
      if (targets && targets.length) break;
    } catch {}
    await sleep(500);
  }
  if (!targets || !targets.length) throw new Error('chrome DevTools endpoint never came up');
  const page = targets.find((t) => t.type === 'page') || targets[0];
  cdp = new CDP(page.webSocketDebuggerUrl);
  await cdp.connect();
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('DOM.enable');
  await cdp.send('Network.enable');
  cdp.on('Network.requestWillBeSent', (p) => {
    try {
      netReqs.set(p.requestId, {
        method: p.request && p.request.method,
        url: String((p.request && p.request.url) || '').split('?')[0].slice(0, 90),
      });
    } catch {}
  });
  cdp.on('Network.responseReceived', (p) => {
    try {
      const st = p.response && p.response.status;
      if (st && st >= 400) {
        const r = netReqs.get(p.requestId) || {};
        netNote(`${r.method || ''} ${String((p.response && p.response.url) || '').split('?')[0].slice(0, 90)} -> ${st}`);
      }
    } catch {}
  });
  cdp.on('Network.loadingFailed', (p) => {
    try {
      const r = netReqs.get(p.requestId) || {};
      netNote(`${r.method || ''} ${r.url || ''} -> FAILED ${String(p.errorText || '').slice(0, 40)}`);
    } catch {}
  });
}

const COLLECT = `(() => {
  const vis = (e) => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  document.querySelectorAll('[data-lille-ref]').forEach(e => e.removeAttribute('data-lille-ref'));
  const sel = 'a[href], button, input, select, textarea, [role="button"], [contenteditable="true"], [data-testid]';
  const seen = new Set();
  const els = [];
  let n = 0;
  for (const e of document.querySelectorAll(sel)) {
    if (seen.has(e)) continue;
    // File inputs are routinely display:none behind a styled button;
    // they still need refs so browser_upload can target them.
    if (!vis(e) && (e.getAttribute('type') || '').toLowerCase() !== 'file') continue;
    seen.add(e);
    if (e.tagName === 'A' && !e.innerText.trim() && !e.getAttribute('aria-label')) {
      // keep anchors only if they say something or carry an image alt
      if (!e.querySelector('img[alt]:not([alt=""])')) continue;
    }
    n += 1;
    e.setAttribute('data-lille-ref', String(n));
    const type = (e.getAttribute('type') || '').toLowerCase();
    const secret = type === 'password' ||
      /pass(word)?|pwd|secret|one-time|otp/i.test(e.getAttribute('autocomplete') || '') ||
      /pass(word)?|pwd/i.test(e.getAttribute('name') || '');
    els.push({
      ref: n,
      tag: e.tagName.toLowerCase(),
      type: type || undefined,
      role: e.getAttribute('role') || undefined,
      testid: e.getAttribute('data-testid') || undefined,
      text: (e.innerText || e.getAttribute('aria-label') || '').replace(/\\s+/g, ' ').trim().slice(0, 90),
      name: e.getAttribute('name') || undefined,
      placeholder: e.getAttribute('placeholder') || undefined,
      href: e.tagName === 'A' ? (e.getAttribute('href') || undefined) : undefined,
      secret: secret || undefined,
    });
    if (els.length >= 40) break;
  }
  const text = document.body ? (document.body.innerText || '') : '';
  const frames = [...document.querySelectorAll('iframe')].map(f => {
    const r = f.getBoundingClientRect();
    const cs = getComputedStyle(f);
    return { src: f.getAttribute('src') || '', w: Math.round(r.width),
             h: Math.round(r.height),
             visible: cs.display !== 'none' && cs.visibility !== 'hidden' };
  });
  return { url: location.href, title: document.title || '', text, elements: els, frames };
})()`;

const CHALLENGE_TEXT = [
  ['captcha', 'verify you are human'],
  ['captcha', 'are you a robot'],
  ['captcha', 'confirm you’re human'],
  ['captcha', "confirm you're human"],
  ['captcha', 'i am human'],
  ['code', 'enter the code we sent'],
  ['code', 'we sent a code'],
  ['code', 'enter the verification code'],
  ['code', 'check your email for a code'],
  ['code', 'we emailed you a code'],
  ['phone', 'confirm your phone number'],
  ['phone', 'enter your phone number'],
  ['phone', 'add a phone number'],
  ['login-challenge', 'unusual login activity'],
  ['login-challenge', 'help us keep your account secure'],
];

function detectChallenge(state) {
  const url = (state.url || '').toLowerCase();
  if (/arkose|funcaptcha/.test(url)) return { kind: 'captcha', detail: 'challenge in URL: ' + state.url };
  for (const fr of state.frames || []) {
    const src = typeof fr === 'string' ? fr : (fr.src || '');
    const s = src.toLowerCase();
    if (!/arkose|funcaptcha|hcaptcha|recaptcha/.test(s)) continue;
    // A challenge counts only when PRESENTED — visible at a
    // meaningful size. Passive scoring/badge iframes (reCAPTCHA
    // v3, invisible v2 anchors) sit at 0x0 or hidden on countless
    // ordinary pages whose content and controls work fine; in
    // the v0.8 X proof they false-walled two usable mailbox
    // homepages. Using the page never interacts with the
    // captcha; if the site later gates an action behind it, it
    // renders visibly and this fires. Missing geometry fails
    // safe (counts as presented).
    const presented = typeof fr === 'string' || fr.visible === undefined
      || (fr.visible && (fr.w || 0) >= 40 && (fr.h || 0) >= 40);
    if (presented) {
      return { kind: 'captcha', detail: 'human-verification iframe: ' + src.slice(0, 120) };
    }
  }
  const text = (state.text || '').toLowerCase();
  for (const [kind, needle] of CHALLENGE_TEXT) {
    if (text.includes(needle)) return { kind, detail: 'page text: "' + needle + '"' };
  }
  if (/\/account\/access|\/i\/flow\/login\?.*challenge/.test(url)) {
    return { kind: 'login-challenge', detail: 'account access wall: ' + state.url };
  }
  return null;
}

async function collect() {
  const state = await cdp.eval(COLLECT);
  state.challenge = detectChallenge(state);
  if (netErrors.length) state.net_errors = netErrors.slice(-5);
  return state;
}

function stateLite(state) {
  return { url: state.url, title: state.title, challenge: state.challenge,
           net_errors: state.net_errors,
           text_excerpt: (state.text || '').slice(0, 700) };
}

const CLICK_TEXT = (s) => `(() => {
  const norm = (t) => (t || '').replace(/\\s+/g, ' ').trim().toLowerCase();
  const want = norm(${JSON.stringify(s)});
  const vis = (e) => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  const els = [...document.querySelectorAll('button, a, [role="button"], div, span, li')];
  const pick = (list) => list.filter(vis).find(e => e.tagName === 'BUTTON' || e.tagName === 'A' || e.getAttribute('role') === 'button')
    || list.filter(vis)[0] || list[0];
  const exact = els.filter(e => norm(e.innerText) === want);
  if (exact.length) { pick(exact).click(); return 'clicked'; }
  const part = els.filter(e => vis(e) && norm(e.innerText).includes(want) && norm(e.innerText).length < 200);
  if (part.length) { pick(part).click(); return 'clicked'; }
  return 'NOT FOUND';
})()`;

const REF_EL = (ref) => `document.querySelector('[data-lille-ref="${ref}"]')`;

async function doType(a) {
  const probe = await cdp.eval(`(() => {
    const el = ${REF_EL(a.ref)};
    if (!el) return { missing: true };
    const type = (el.getAttribute('type') || '').toLowerCase();
    const secret = type === 'password' ||
      /pass(word)?|pwd|secret|one-time|otp/i.test(el.getAttribute('autocomplete') || '') ||
      /pass(word)?|pwd/i.test(el.getAttribute('name') || '');
    return { secret, tag: el.tagName };
  })()`);
  if (!probe || probe.missing) throw new Error('type target missing: ref ' + a.ref + ' (call browser_read first — refs are assigned per read)');
  if (probe.secret && !a.secret) {
    const err = new Error('SECRET_FIELD: ref ' + a.ref + ' is a password/secret field — browser_type refuses it; use browser_type_secret with a vault key name');
    err.code = 'SECRET_FIELD';
    throw err;
  }
  await pace();
  await cdp.eval(`(() => { const el = ${REF_EL(a.ref)}; el.focus(); if (el.select) el.select(); return true; })()`);
  // Select-all so insertText replaces any existing content.
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: 2 });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', modifiers: 2 });
  await cdp.send('Input.insertText', { text: String(a.text ?? a.value ?? '') });
  await sleep(350);
  return collect();
}

async function handle(a) {
  switch (a.action) {
    case 'open': {
      netErrors.length = 0;
      await cdp.send('Page.navigate', { url: String(a.url) });
      await sleep(Number(a.settle || 4) * 1000);
      let st = await collect();
      // SPA shells (X's onboarding among them) can sit empty for
      // ~a minute behind a slow proxy before the app bootstraps;
      // returning the blank shell made the loop act on nothing
      // (v0.8 X proof, attempts 4-12). Wait out the shell:
      // re-collect until the page shows elements or real text.
      const deadline = Date.now() + 70000;
      while (Date.now() < deadline
             && (st.elements || []).length === 0
             && (st.text || '').trim().length < 100
             && !st.challenge) {
        await sleep(5000);
        st = await collect();
      }
      return st;
    }
    case 'read':
      return collect();
    case 'click': {
      await pace();
      let r;
      if (a.ref != null) {
        r = await cdp.eval(`(() => { const el = ${REF_EL(a.ref)}; if (!el) return 'missing'; el.scrollIntoView({block:'center'}); el.click(); return 'clicked'; })()`);
        if (r === 'missing') throw new Error('click target missing: ref ' + a.ref + ' (call browser_read first)');
      } else if (a.text) {
        r = await cdp.eval(CLICK_TEXT(String(a.text)));
        if (r === 'NOT FOUND') throw new Error('click text not found: ' + a.text);
      } else {
        throw new Error('click needs ref or text');
      }
      await sleep(Number(a.settle || 2) * 1000);
      return collect();
    }
    case 'type':
      return doType(a);
    case 'insert_text': {
      // Internal only: raw text insertion at the current focus
      // (used by the X composer's contenteditable box).
      await cdp.send('Input.insertText', { text: String(a.text || '') });
      await sleep(350);
      return collect();
    }
    case 'upload': {
      const doc = await cdp.send('DOM.getDocument', { depth: 1 });
      const q = await cdp.send('DOM.querySelector', {
        nodeId: doc.root.nodeId,
        selector: `[data-lille-ref="${a.ref}"]`,
      });
      if (!q.nodeId) throw new Error('upload target missing: ref ' + a.ref);
      await cdp.send('DOM.setFileInputFiles', { files: [String(a.path)], nodeId: q.nodeId });
      await sleep(800);
      return collect();
    }
    case 'screenshot': {
      // captureScreenshot stalls when the compositor has not
      // produced a frame yet (seen live on a heavy article page:
      // CDP timeout while a warm page captured in 0.2s). Bring the
      // page to front, nudge a repaint, and retry once.
      let shot = null;
      let lastErr = null;
      for (let attempt = 0; attempt < 2 && !shot; attempt++) {
        try {
          await cdp.send('Page.bringToFront').catch(() => {});
          await cdp.eval('window.scrollBy(0, 1); window.scrollBy(0, -1); true').catch(() => {});
          await sleep(250);
          shot = await cdp.send('Page.captureScreenshot',
                                { format: 'png', fromSurface: true }, 60000);
        } catch (e) { lastErr = e; await sleep(800); }
      }
      if (!shot) throw new Error('SCREENSHOT_TIMEOUT: captureScreenshot did not answer after a repaint nudge + retry (' + (lastErr ? lastErr.message : 'unknown') + ')');
      fs.writeFileSync(String(a.path), Buffer.from(shot.data, 'base64'));
      const st = await collect();
      return { ...stateLite(st), screenshot: String(a.path),
               bytes: fs.statSync(String(a.path)).size };
    }
    case 'wait': {
      if (a.text) {
        const deadline = Date.now() + Number(a.wait_timeout ?? a.timeout ?? 30) * 1000;
        let st = await collect();
        while (Date.now() < deadline) {
          if ((st.text || '').toLowerCase().includes(String(a.text).toLowerCase())) return st;
          await sleep(1200);
          st = await collect();
        }
        throw new Error('wait_text timed out: ' + String(a.text).slice(0, 60));
      }
      await sleep(Number(a.seconds || 2) * 1000);
      return collect();
    }
    case 'eval': {
      // Internal only: fixed expressions from browsertools.py.
      const value = await cdp.eval(String(a.expression));
      const st = await collect().catch(() => null);
      return { value, url: st ? st.url : '', challenge: st ? st.challenge : null };
    }
    default:
      throw new Error('unknown browse action: ' + a.action);
  }
}

async function main() {
  await startBrowser();
  process.stdout.write(JSON.stringify({ ready: true }) + '\n');
  const rl = readline.createInterface({ input: process.stdin });
  for await (const line of rl) {
    if (!line.trim()) continue;
    let req;
    try { req = JSON.parse(line); } catch { continue; }
    if (req.action === 'close') {
      process.stdout.write(JSON.stringify({ id: req.id, ok: true, result: { closed: true } }) + '\n');
      cleanup(0);
    }
    try {
      const result = await handle(req);
      process.stdout.write(JSON.stringify({ id: req.id, ok: true, result }) + '\n');
    } catch (e) {
      process.stdout.write(JSON.stringify({ id: req.id, ok: false, error: e.message, code: e.code || undefined }) + '\n');
    }
  }
  cleanup(0);
}

function cleanup(code) {
  try { cdp && cdp.close(); } catch {}
  try { chrome && chrome.kill('SIGKILL'); } catch {}
  try { relay && relay.kill('SIGKILL'); } catch {}
  process.exit(code);
}

main().catch((e) => { log('fatal:', e.message); cleanup(1); });
