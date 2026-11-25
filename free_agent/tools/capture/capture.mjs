// capture.mjs — Lille's scripted walkthrough capture engine (v0.6).
//
// Drives headless Chromium over CDP through a step list and records
// PNG frames + manifest.json (per-frame hold seconds, caption, and a
// page-text excerpt kept as evidence). This is a SCRIPTED CAPTURE
// instrument for making demo videos — it is not general browser
// control (see VIDEO.md / BENCHMARK.md gap notes).
//
// Usage:
//   node capture.mjs --steps steps.json --out FRAMES_DIR
//                    [--width 450] [--height 800] [--chrome PATH]
//
// Step actions:
//   goto            {url}
//   wait            {seconds}
//   wait_text       {text, timeout?, absent?}   poll page text
//   wait_selector   {selector, timeout?}        poll visible element
//   click_text      {text}                      exact-then-partial match
//   click_selector  {selector}
//   click_if_text   {text}                      no-op when absent
//   type            {selector, text}            focus + insertText
//   check           {selector}                  tick a checkbox
//   parse_seed      {start, end, save_as}       numbered word list from
//                                               page text -> vars[save_as]
//   solve_word_quiz {seed_var}                  Fluid-style backup quiz:
//                                               per "Word #N" block click
//                                               the seed word (app adapter;
//                                               markers come from the page)
//   scroll_into_view {text}                     scroll element into view
//   screenshot      {name, hold?, caption?}     record a frame
//
// Any step may carry "shot": {"name":..., "hold":..., "caption":...}
// to screenshot right after it. Secrets (passwords) travel only in
// the steps file inside the task sandbox; the manifest never stores
// typed values — page-text excerpts are truncated and password-field
// values are never read.
import { spawn, execSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CDP, sleep, waitFor } from './cdp.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

function arg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
}

const STEPS_FILE = arg('steps');
const OUT_DIR = arg('out');
const WIDTH = Number(arg('width', '450'));
const HEIGHT = Number(arg('height', '800'));
const CHROME = arg('chrome', '/opt/meta-chromium/chrome');

const steps = JSON.parse(fs.readFileSync(STEPS_FILE, 'utf8'));
fs.mkdirSync(OUT_DIR, { recursive: true });
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'lille-cap-profile-'));

const frames = [];
const vars = {};
let shotCount = 0;
let relay = null;
let chrome = null;
let cdp = null;

const log = (...a) => console.log('[capture]', ...a);

async function freePort() {
  for (const p of [18080, 18081, 18082, 18083]) {
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
  const chromeArgs = [
    '--headless=new', '--disable-gpu', '--no-sandbox',
    '--disable-dev-shm-usage',
    '--remote-debugging-port=9339', '--remote-debugging-address=127.0.0.1',
    '--user-data-dir=' + profile,
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
  }
  // Hatch Chromium builds gate WebGL behind an allowlist file; include
  // the flags when the file exists (harmless elsewhere).
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
      const r = await fetch('http://127.0.0.1:9339/json/list');
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
}

const CLICK_TEXT = (s) => `(() => {
  const norm = (t) => (t || '').replace(/\\s+/g, ' ').trim().toLowerCase();
  const want = norm(${JSON.stringify(s)});
  const vis = (e) => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  const els = [...document.querySelectorAll('button, a, [role="button"], [data-act], li, div, span')];
  const pick = (list) => list.filter(vis).find(e => e.tagName === 'BUTTON' || e.tagName === 'A' || e.getAttribute('role') === 'button')
    || list.filter(vis)[0] || list.find(e => e.tagName === 'BUTTON') || list[0];
  const exact = els.filter(e => norm(e.innerText) === want);
  if (exact.length) { pick(exact).click(); return 'clicked'; }
  const starts = els.filter(e => norm(e.innerText).startsWith(want));
  if (starts.length) { pick(starts).click(); return 'clicked'; }
  const part = els.filter(e => vis(e) && norm(e.innerText).includes(want) && norm(e.innerText).length < 220);
  if (part.length) { pick(part).click(); return 'clicked'; }
  return 'NOT FOUND';
})()`;

async function pageText() {
  return (await cdp.eval(`document.body ? document.body.innerText : ''`).catch(() => '')) || '';
}

async function screenshot(step) {
  shotCount += 1;
  const name = String(step.name || ('shot-' + shotCount)).replace(/[^\w-]+/g, '_');
  const file = String(shotCount).padStart(3, '0') + '-' + name + '.png';
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(OUT_DIR, file), Buffer.from(shot.data, 'base64'));
  const text = await pageText();
  frames.push({
    file,
    hold_seconds: Number(step.hold || 4),
    caption: String(step.caption || ''),
    text_excerpt: text.slice(0, 1200),
  });
  log('frame', file, 'hold', step.hold || 4);
}

async function runStep(step, idx) {
  const a = step.action;
  log('step', idx + 1, a);
  if (a === 'goto') {
    await cdp.send('Page.navigate', { url: step.url });
    await sleep(Number(step.settle || 5) * 1000);
  } else if (a === 'wait') {
    await sleep(Number(step.seconds || 1) * 1000);
  } else if (a === 'wait_text') {
    const timeout = Number(step.timeout || 60) * 1000;
    const needle = String(step.text || '');
    const deadline = Date.now() + timeout;
    let ok = false;
    while (Date.now() < deadline) {
      const t = await pageText();
      const present = t.toLowerCase().includes(needle.toLowerCase());
      if (step.absent ? !present : present) { ok = true; break; }
      await sleep(1500);
    }
    if (!ok) throw new Error(`wait_text timed out (${step.absent ? 'absent' : 'present'}): ${needle.slice(0, 60)}`);
  } else if (a === 'wait_selector') {
    await waitFor(cdp, `(() => { const el = document.querySelector(${JSON.stringify(step.selector)}); return !!el && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length); })()`,
                  Number(step.timeout || 60) * 1000);
  } else if (a === 'click_text') {
    const r = await cdp.eval(CLICK_TEXT(String(step.text || '')));
    if (r === 'NOT FOUND') throw new Error('click_text not found: ' + step.text);
    await sleep(Number(step.settle || 1.2) * 1000);
  } else if (a === 'click_if_text') {
    await cdp.eval(CLICK_TEXT(String(step.text || ''))).catch(() => {});
    await sleep(Number(step.settle || 1.2) * 1000);
  } else if (a === 'click_selector') {
    const r = await cdp.eval(`(() => { const el = document.querySelector(${JSON.stringify(step.selector)}); if (!el) return 'missing'; el.click(); return 'clicked'; })()`);
    if (r === 'missing') throw new Error('click_selector missing: ' + step.selector);
    await sleep(Number(step.settle || 1.2) * 1000);
  } else if (a === 'type') {
    const ok = await cdp.eval(`(() => { const el = document.querySelector(${JSON.stringify(step.selector)}); if (!el) return false; el.focus(); if (el.select) el.select(); return true; })()`);
    if (!ok) throw new Error('type target missing: ' + step.selector);
    await cdp.send('Input.insertText', { text: String(step.text || '') });
    await sleep(300);
  } else if (a === 'check') {
    await cdp.eval(`(() => { const el = document.querySelector(${JSON.stringify(step.selector)}); if (el && !el.checked) el.click(); return true; })()`);
    await sleep(300);
  } else if (a === 'parse_seed') {
    const t = await pageText();
    const seg = (t.split(String(step.start || ''))[1] || '').split(String(step.end || ''))[0];
    const seed = {};
    for (const m of seg.matchAll(/(\d{1,2})\s*([a-z]+)/g)) seed[Number(m[1])] = m[2];
    if (Object.keys(seed).length < 12) throw new Error('parse_seed found only ' + Object.keys(seed).length + ' words');
    vars[String(step.save_as || 'seed')] = seed;
    log('parsed seed words:', Object.keys(seed).length, '(values never logged)');
  } else if (a === 'solve_word_quiz') {
    // App adapter (Fluid-style backup quiz): each "Word #N" question
    // block holds option buttons carrying data-word. Queries are
    // re-run fresh per question (the app may re-render), the block
    // is the ancestor holding >=2 options and exactly ONE question
    // label, and selection is verified via the app's own marked
    // style before the step succeeds.
    const seed = vars[String(step.seed_var || 'seed')];
    if (!seed) throw new Error('solve_word_quiz: no parsed seed in vars');
    // The question header can render before the option buttons do;
    // wait for the options to exist before solving.
    await waitFor(cdp, `(() => document.querySelectorAll('#seedQuizBox button[data-word]').length >= 4)()`,
                  Number(step.timeout || 30) * 1000).catch(() => {
      throw new Error('solve_word_quiz: option buttons never rendered');
    });
    const asked = await cdp.eval(`(() => {
      const qb = document.getElementById('seedQuizBox') || document.body;
      const els = [...qb.querySelectorAll('*')].filter(e => e.children.length === 0 && /^Word #(\\d+)$/.test((e.innerText||'').trim()));
      return els.map(e => Number(e.innerText.trim().match(/#(\\d+)/)[1]));
    })()`);
    if (!asked || !asked.length) throw new Error('solve_word_quiz: no quiz questions found');
    const clickOne = (n, word) => `(() => {
      const qb = document.getElementById('seedQuizBox') || document.body;
      const lab = [...qb.querySelectorAll('*')].find(e => e.children.length === 0 && (e.innerText||'').trim() === 'Word #${n}');
      if (!lab) return 'no label';
      let c = lab;
      for (let i = 0; i < 6 && c.parentElement; i++) {
        c = c.parentElement;
        const opts = [...c.querySelectorAll('button[data-word]')];
        const labs = [...c.querySelectorAll('*')].filter(e => e.children.length === 0 && /^Word #\\d+$/.test((e.innerText||'').trim()));
        if (opts.length >= 2 && labs.length === 1) break;
      }
      const hit = [...c.querySelectorAll('button[data-word]')].find(b => b.dataset.word === ${JSON.stringify(word)});
      if (!hit) return 'no option';
      hit.click();
      return 'clicked';
    })()`;
    const markedCount = () => cdp.eval(`(() => {
      const qb = document.getElementById('seedQuizBox') || document.body;
      return [...qb.querySelectorAll('button[data-word]')].filter(b => (b.getAttribute('style') || '').includes('--brand-1')).length;
    })()`);
    for (const n of asked) {
      const r = await cdp.eval(clickOne(n, seed[n]));
      if (r !== 'clicked') throw new Error(`solve_word_quiz: question ${n}: ${r}`);
      await sleep(500);
    }
    if (await markedCount() < asked.length) {
      // One retry pass for any question whose click did not stick.
      for (const n of asked) { await cdp.eval(clickOne(n, seed[n])); await sleep(400); }
      if (await markedCount() < asked.length) {
        throw new Error('solve_word_quiz: selections did not stick after retry');
      }
    }
    log('quiz solved:', asked.join(','));
    await sleep(600);
  } else if (a === 'unlock_if_present') {
    const state = await cdp.eval(`(() => {
      const box = document.getElementById('unlockBox');
      const vis = box && getComputedStyle(box).display !== 'none' && !!(box.offsetWidth || box.offsetHeight);
      return vis ? 'visible' : 'absent';
    })()`);
    if (state === 'visible') {
      await cdp.eval(`(() => { const el = document.getElementById('unlockPw'); if (el) { el.focus(); el.select && el.select(); } return true; })()`);
      await cdp.send('Input.insertText', { text: String(step.password || '') });
      await sleep(300);
      const r = await cdp.eval(CLICK_TEXT('Unlock Fluid'));
      if (r === 'NOT FOUND') throw new Error('unlock_if_present: unlock button not found');
      await sleep(Number(step.settle || 4) * 1000);
      log('unlocked via unlock box');
    } else {
      log('no unlock box — session already unlocked');
    }
  } else if (a === 'scroll_into_view') {
    await cdp.eval(`(() => { const els = [...document.querySelectorAll('*')].filter(e => e.children.length === 0 && (e.innerText||'').includes(${JSON.stringify(String(step.text || ''))})); if (els[0]) els[0].scrollIntoView({block: 'center'}); return els.length; })()`);
    await sleep(600);
  } else if (a === 'screenshot') {
    await screenshot(step);
  } else {
    throw new Error('unknown capture action: ' + a);
  }
  if (step.shot) await screenshot(step.shot);
}

async function main() {
  await startBrowser();
  let sourceUrl = '';
  for (let i = 0; i < steps.length; i++) {
    if (steps[i].action === 'goto' && !sourceUrl) sourceUrl = steps[i].url;
    await runStep(steps[i], i);
  }
  const manifest = {
    tool: 'lille capture_walkthrough v0.6',
    source_url: sourceUrl,
    viewport: { width: WIDTH, height: HEIGHT },
    frames,
  };
  fs.writeFileSync(path.join(OUT_DIR, 'manifest.json'), JSON.stringify(manifest, null, 2));
  console.log(`CAPTURE_OK frames=${frames.length} dir=${OUT_DIR}`);
}

main()
  .then(() => cleanup(0))
  .catch((e) => { console.error('CAPTURE_FAILED: ' + e.message); cleanup(1); });

function cleanup(code) {
  try { cdp && cdp.close(); } catch {}
  try { chrome && chrome.kill('SIGKILL'); } catch {}
  try { relay && relay.kill('SIGKILL'); } catch {}
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch {}
  process.exit(code);
}
