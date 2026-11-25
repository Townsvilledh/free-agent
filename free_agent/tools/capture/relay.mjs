// Local relay proxy for Lille's capture engine (v0.6).
// Listens on 127.0.0.1:<port argv[2], default 18080> and forwards to the
// egress proxy whose URL (incl. credentials) is read from the
// environment at run time. Credentials are never printed or logged.
// On hosts with direct internet (no proxy env), capture.mjs does not
// start this relay at all.
import http from 'node:http';
import net from 'node:net';

const PORT = Number(process.argv[2] || 18080);
const upstream = new URL(process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY || process.env.http_proxy);
const auth = upstream.username
  ? 'Basic ' + Buffer.from(decodeURIComponent(upstream.username) + ':' + decodeURIComponent(upstream.password)).toString('base64')
  : null;
const upHost = upstream.hostname;
const upPort = Number(upstream.port || 3128);

function connectUpstream(cb) {
  const s = net.connect(upPort, upHost, cb);
  s.on('error', () => { try { s.destroy(); } catch {} });
  return s;
}

const server = http.createServer((req, res) => {
  let target;
  try { target = new URL(req.url); } catch { res.writeHead(400); res.end(); return; }
  const s = connectUpstream(() => {
    const lines = [`${req.method} ${req.url} HTTP/1.1`, `Host: ${target.host}`];
    for (const [k, v] of Object.entries(req.headers)) {
      if (k.toLowerCase() === 'proxy-authorization' || k.toLowerCase() === 'proxy-connection') continue;
      lines.push(`${k}: ${v}`);
    }
    if (auth) lines.push(`Proxy-Authorization: ${auth}`);
    lines.push('Proxy-Connection: close', '', '');
    s.write(lines.join('\r\n'));
    s.pipe(res.socket);
    req.socket.pipe(s);
  });
  s.on('error', () => { try { res.writeHead(502); res.end(); } catch {} });
});

server.on('connect', (req, clientSocket, head) => {
  const s = connectUpstream(() => {
    const lines = [`CONNECT ${req.url} HTTP/1.1`, `Host: ${req.url}`];
    if (auth) lines.push(`Proxy-Authorization: ${auth}`);
    lines.push('Proxy-Connection: keep-alive', '', '');
    s.write(lines.join('\r\n'));
  });
  let buf = Buffer.alloc(0);
  const onData = (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    const idx = buf.indexOf('\r\n\r\n');
    if (idx === -1) return;
    s.off('data', onData);
    const header = buf.subarray(0, idx).toString('latin1');
    const rest = buf.subarray(idx + 4);
    const status = Number((header.split(' ')[1] || '0'));
    if (status === 200) {
      clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (rest.length) clientSocket.write(rest);
      if (head && head.length) s.write(head);
      s.pipe(clientSocket);
      clientSocket.pipe(s);
    } else {
      clientSocket.write('HTTP/1.1 502 Bad Gateway\r\n\r\n');
      clientSocket.destroy();
      s.destroy();
    }
  };
  s.on('data', onData);
  s.on('error', () => { try { clientSocket.destroy(); } catch {} });
  clientSocket.on('error', () => { try { s.destroy(); } catch {} });
});

server.listen(PORT, '127.0.0.1', () => console.log('relay listening on 127.0.0.1:' + PORT));
