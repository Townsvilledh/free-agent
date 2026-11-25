// Minimal CDP client over Node's built-in WebSocket.
// Part of Lille's capture_walkthrough engine (v0.6) — adapted from the
// walk harness used to verify Fluid live; no dependencies beyond Node.
export class CDP {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.nextId = 1;
    this.pending = new Map();
    this.handlers = new Map();
    this.ws = null;
  }
  async connect() {
    this.ws = new WebSocket(this.wsUrl);
    await new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, { once: true });
      this.ws.addEventListener('error', reject, { once: true });
    });
    this.ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message || 'CDP error'));
        else resolve(msg.result || {});
      } else if (msg.method) {
        const list = this.handlers.get(msg.method) || [];
        for (const fn of list) { try { fn(msg.params || {}); } catch {} }
      }
    });
  }
  on(method, fn) {
    if (!this.handlers.has(method)) this.handlers.set(method, []);
    this.handlers.get(method).push(fn);
  }
  send(method, params = {}, timeoutMs = 30000) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('CDP timeout: ' + method)); }, timeoutMs);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(t); resolve(v); },
        reject: (e) => { clearTimeout(t); reject(e); },
      });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  async eval(expression, { awaitPromise = true, timeoutMs = 30000 } = {}) {
    const r = await this.send('Runtime.evaluate', {
      expression, awaitPromise, returnByValue: true, timeout: timeoutMs,
    }, timeoutMs + 5000);
    if (r.exceptionDetails) {
      const d = r.exceptionDetails;
      throw new Error('eval exception: ' + ((d.exception && d.exception.description) || d.text || 'unknown'));
    }
    return r.result ? r.result.value : undefined;
  }
  close() { try { this.ws.close(); } catch {} }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Poll an in-page expression until truthy or the deadline passes.
export async function waitFor(cdp, expression, timeoutMs = 30000, pollMs = 300) {
  const deadline = Date.now() + timeoutMs;
  let lastErr = null;
  while (Date.now() < deadline) {
    try {
      const v = await cdp.eval(expression, { timeoutMs: 8000 });
      if (v) return v;
    } catch (e) { lastErr = e; }
    await sleep(pollMs);
  }
  throw new Error('waitFor timed out: ' + expression.slice(0, 120) + (lastErr ? ' (last err: ' + lastErr.message + ')' : ''));
}
