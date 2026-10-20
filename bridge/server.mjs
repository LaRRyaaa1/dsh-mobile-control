#!/usr/bin/env node
/**
 * DSH Mobile Bridge
 * -----------------
 * Exposes the *live* DeepSeek Harness desktop web GUI to a phone on the LAN,
 * without moving any work off the PC.
 *
 * How it works:
 *   phone ──HTTP/WS──> bridge (LAN) ──HTTP/WS──> 127.0.0.1:<dsh-port>
 *
 * DSH binds its web GUI to loopback and authenticates browsers with a signed
 * HttpOnly cookie whose signing key lives in $DSH_HOME/.credentials.yaml.
 * The bridge reads that local key, mints the same cookie server-side, and
 * injects it on every proxied request. The phone never sees the key or the
 * DSH cookie; it only holds a long random pairing cookie for the bridge.
 *
 * Safety:
 *   - binds to one private LAN address by default (not 0.0.0.0)
 *   - requires a 32-byte pairing token delivered out-of-band (QR/URL)
 *   - rejects unknown Host / cross-site Origin (DNS-rebinding + CSRF)
 *   - strips DSH auth cookies from responses and never forwards its own
 *   - HTTP only; use Tailscale/WireGuard or a TLS reverse proxy off-LAN
 *
 * Usage:
 *   node server.mjs [--port 8787] [--bind 192.168.1.10] [--reset-pair]
 *                   [--dsh http://127.0.0.1:62734]
 *
 * Optional QR rendering uses qrcode-terminal; everything else is Node built-ins.
 */
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
let qrcode = null;
try { qrcode = (await import('qrcode-terminal')).default; } catch { /* QR is optional */ }

const IS_WIN = process.platform === 'win32';
const HOME = process.env.USERPROFILE || os.homedir();
const DSH_HOME = process.env.DSH_HOME || path.join(HOME, '.dsh');
const STATE_DIR = process.env.DSH_MOBILE_HOME || path.join(HOME, '.dsh-mobile');
const CONFIG_FILE = path.join(STATE_DIR, 'config.json');
const PAIR_FILE = path.join(STATE_DIR, 'pair-url.txt');
const APK_FILE = new URL('../dsh-mobile.apk', import.meta.url);
const BRIDGE_COOKIE = 'dsh_mobile';
const PAIR_COOKIE_DAYS = 180;

const args = process.argv.slice(2);
function arg(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 && i + 1 < args.length && !args[i + 1].startsWith('--') ? args[i + 1] : fallback;
}
const WANT_PORT = Number(arg('--port', process.env.DSH_MOBILE_PORT || 8787));
const WANT_BIND = arg('--bind', process.env.DSH_MOBILE_BIND || '');
const FORCE_DSH = arg('--dsh', process.env.DSH_MOBILE_DSH || '');
const RESET_PAIR = args.includes('--reset-pair');
const NO_QR = args.includes('--no-qr');

function log(...a) {
  const t = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  console.log(`[${t}]`, ...a);
}
function die(msg) {
  console.error(`\n[错误] ${msg}\n`);
  process.exit(1);
}

/* ------------------------------------------------------------------ state */

fs.mkdirSync(STATE_DIR, { recursive: true });

function loadConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
  } catch {
    return {};
  }
}
function saveConfig(c) {
  fs.writeFileSync(CONFIG_FILE, JSON.stringify(c, null, 2));
  try { fs.chmodSync(CONFIG_FILE, 0o600); } catch {}
}
const config = loadConfig();
if (!config.secret) config.secret = crypto.randomBytes(32).toString('base64url');
if (!config.pairToken || RESET_PAIR) config.pairToken = crypto.randomBytes(24).toString('base64url');
config.createdAt ||= new Date().toISOString();
saveConfig(config);

/* -------------------------------------------------------------------- net */

function isPrivateIPv4(ip) {
  const p = ip.split('.').map(Number);
  if (p.length !== 4 || p.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return false;
  if (p[0] === 10) return true;
  if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return true;
  if (p[0] === 192 && p[1] === 168) return true;
  return false;
}
function lanAddresses() {
  const out = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      if (!isPrivateIPv4(a.address)) continue;
      if (a.address.startsWith('169.254.')) continue;
      out.push({ name, address: a.address });
    }
  }
  // Prefer Wi-Fi / Ethernet and avoid virtual adapters when possible.
  const score = (x) => {
    const n = x.name.toLowerCase();
    if (n.includes('wi-fi') || n.includes('wifi') || n.includes('wlan')) return 0;
    if (n.includes('ethernet') || n.includes('以太网')) return 1;
    if (n.includes('veth') || n.includes('vmware') || n.includes('virtualbox') || n.includes('hyper-v')) return 9;
    return 5;
  };
  out.sort((a, b) => score(a) - score(b));
  return out;
}
const LAN = lanAddresses();
if (!LAN.length) {
  log('未发现私有局域网 IPv4 地址；将只监听 127.0.0.1。');
}
const BIND = WANT_BIND || (LAN[0] ? LAN[0].address : '127.0.0.1');
const PORT = Number.isFinite(WANT_PORT) && WANT_PORT > 0 ? WANT_PORT : 8787;
if (BIND === '0.0.0.0') {
  log('警告：--bind 0.0.0.0 会监听所有网卡（包括可能暴露到公网的网卡）。建议绑定具体局域网 IP。');
}

function stripPort(hostHeader) {
  if (!hostHeader) return '';
  const s = String(hostHeader).trim().toLowerCase();
  if (s.startsWith('[')) return s.slice(1, s.indexOf(']'));
  return s.split(':')[0];
}
const ALLOWED_HOSTS = new Set([
  'localhost', '127.0.0.1', '::1',
  ...LAN.map((x) => x.address.toLowerCase()),
  ...(WANT_BIND && WANT_BIND !== '0.0.0.0' ? [WANT_BIND.toLowerCase()] : []),
  ...(process.env.DSH_MOBILE_HOSTNAME ? [process.env.DSH_MOBILE_HOSTNAME.toLowerCase()] : []),
]);
function isAllowedHost(hostHeader) {
  const h = stripPort(hostHeader);
  return h === '0.0.0.0' || ALLOWED_HOSTS.has(h);
}

/* ---------------------------------------------------------------- dsh auth */

function readDshSecret() {
  const file = path.join(DSH_HOME, '.credentials.yaml');
  if (!fs.existsSync(file)) throw new Error(`找不到 ${file}，请先启动一次 DSH 桌面端并完成浏览器初始化。`);
  const text = fs.readFileSync(file, 'utf8');
  const m = text.match(/browser-session:[\s\S]*?secret:\s*['"]?([A-Za-z0-9_\-+/=]+)['"]?/);
  if (!m) throw new Error(`无法从 ${file} 读取 browser-session 签名密钥。`);
  return Buffer.from(m[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64');
}

let dsh = { origin: null, port: null, authority: null, cookie: null, cookieExp: 0 };

function mintDshCookie(authority) {
  const secret = readDshSecret();
  const now = Date.now();
  const expiresAt = now + 29 * 864e5; // DSH's own default is 30 days; renew a day early
  const payload = Buffer.from(JSON.stringify({
    version: 1, authority, issuedAt: now, expiresAt,
  })).toString('base64url');
  const name = 'dsh-auth-' + crypto.createHash('sha256').update(authority).digest('base64url');
  const sig = crypto.createHmac('sha256', secret).update(payload).digest('base64url');
  return { cookie: `${name}=v1.${payload}.${sig}`, exp: expiresAt };
}

function probe(origin, timeout = 700) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (v) => { if (!settled) { settled = true; resolve(v); } };
    let u;
    try { u = new URL(origin); } catch { return done(null); }
    const req = http.get({
      host: u.hostname, port: u.port || 80, path: '/', timeout,
      headers: { 'user-agent': 'dsh-mobile-bridge/1.0' },
    }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (d) => { if (body.length < 4000) body += d; });
      res.on('end', () => done({ status: res.statusCode, headers: res.headers, body }));
      res.on('error', () => done(null));
    });
    req.on('timeout', () => { req.destroy(); done(null); });
    req.on('error', () => done(null));
  });
}

function parseListeningPorts() {
  const ports = new Set();
  try {
    if (IS_WIN) {
      const out = execFileSync('netstat', ['-ano'], { encoding: 'utf8', windowsHide: true, timeout: 4000 });
      for (const line of out.split(/\r?\n/)) {
        const m = line.match(/^\s*TCP\s+(?:127\.0\.0\.1|\[::1\]):(\d+)\s+\S+\s+LISTENING\s+\d+\s*$/i);
        if (m) ports.add(Number(m[1]));
      }
    } else {
      const out = execFileSync('ss', ['-ltn'], { encoding: 'utf8', timeout: 4000 });
      for (const line of out.split(/\r?\n/)) {
        const m = line.match(/(?:127\.0\.0\.1|\[::1\]):(\d+)\b/);
        if (m) ports.add(Number(m[1]));
      }
    }
  } catch {}
  return [...ports].filter((p) => p >= 1024 && p <= 65535 && p !== PORT);
}

async function discoverDsh() {
  const candidates = [];
  const add = (o) => {
    if (!o) return;
    const s = String(o).replace(/\/+$/, '');
    if (/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(s) && !candidates.includes(s)) candidates.push(s);
  };
  add(FORCE_DSH);
  add(process.env.DSH_WEB_URL);
  add(config.dshOrigin);
  add('http://127.0.0.1:62734');
  for (const p of parseListeningPorts()) add(`http://127.0.0.1:${p}`);

  log(`正在查找本机 DSH Web GUI（候选 ${candidates.length} 个端口）…`);
  const queue = [...candidates];
  const found = [];
  const worker = async () => {
    while (queue.length && !found.length) {
      const origin = queue.shift();
      const r = await probe(origin);
      if (!r) continue;
      const ct = String(r.headers['content-type'] || '');
      const looksDsh = r.status === 401 || (r.status === 200 && ct.includes('text/html') && r.body.includes('__DSH_BOOT__'));
      if (looksDsh) found.push(origin);
    }
  };
  await Promise.all(Array.from({ length: Math.min(8, queue.length) }, worker));
  if (!found.length) return null;
  const origin = found[0];
  const u = new URL(origin);
  const port = Number(u.port || 80);
  const authority = `127.0.0.1:${port}`;
  const minted = mintDshCookie(authority);
  const check = await new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/', timeout: 1500, headers: { cookie: minted.cookie } }, (res) => {
      res.resume();
      resolve(res.statusCode);
    });
    req.on('timeout', () => { req.destroy(); resolve(0); });
    req.on('error', () => resolve(0));
  });
  if (check !== 200) {
    throw new Error(`找到了 DSH（${origin}），但用本机密钥生成的会话 Cookie 被拒绝（HTTP ${check}）。请重启 DSH 后再试。`);
  }
  dsh = { origin, port, authority, cookie: minted.cookie, cookieExp: minted.exp };
  config.dshOrigin = origin;
  saveConfig(config);
  log(`已连接 DSH：${origin}（Cookie 校验通过）`);
  return dsh;
}

/* --------------------------------------------------------------- security */

function parseCookies(header) {
  const out = {};
  for (const part of String(header || '').split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    const k = part.slice(0, i).trim();
    if (!k || k in out) continue;
    try { out[k] = decodeURIComponent(part.slice(i + 1).trim()); } catch { out[k] = part.slice(i + 1).trim(); }
  }
  return out;
}
function hmac(value) {
  return crypto.createHmac('sha256', config.secret).update(value).digest('base64url');
}
function issueBridgeCookie() {
  const exp = Date.now() + PAIR_COOKIE_DAYS * 864e5;
  const base = `v1.${exp}`;
  return `${base}.${hmac(base)}`;
}
function verifyBridgeCookie(raw) {
  if (!raw) return false;
  const parts = String(raw).split('.');
  if (parts.length !== 3 || parts[0] !== 'v1') return false;
  const exp = Number(parts[1]);
  if (!Number.isFinite(exp) || exp < Date.now()) return false;
  const expected = hmac(`v1.${exp}`);
  const a = Buffer.from(parts[2]);
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function isAuthed(req) {
  return verifyBridgeCookie(parseCookies(req.headers.cookie)[BRIDGE_COOKIE]);
}

const attempts = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const a = attempts.get(ip) || { n: 0, reset: now + 60_000 };
  if (now > a.reset) { a.n = 0; a.reset = now + 60_000; }
  a.n += 1;
  attempts.set(ip, a);
  if (attempts.size > 1000) {
    for (const [k, v] of attempts) if (v.reset < now) attempts.delete(k);
  }
  return a.n > 10;
}
function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/* -------------------------------------------------------------- responses */

const COMMON_HEADERS = {
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'referrer-policy': 'no-referrer',
};
function sendText(res, status, text, extra = {}) {
  const body = Buffer.from(text, 'utf8');
  res.writeHead(status, { ...COMMON_HEADERS, 'content-type': 'text/plain; charset=utf-8', 'content-length': body.length, ...extra });
  res.end(body);
}
function sendHtml(res, status, html, extra = {}) {
  const body = Buffer.from(html, 'utf8');
  res.writeHead(status, { ...COMMON_HEADERS, 'content-type': 'text/html; charset=utf-8', 'content-length': body.length, ...extra });
  res.end(body);
}
function serveApk(res) {
  let st;
  try { st = fs.statSync(APK_FILE); } catch {
    return sendText(res, 404, 'APK 还没有构建。请先在电脑上运行 android\\build.ps1。');
  }
  res.writeHead(200, {
    ...COMMON_HEADERS,
    'content-type': 'application/vnd.android.package-archive',
    'content-length': st.size,
    'content-disposition': 'attachment; filename="dsh-mobile.apk"',
  });
  fs.createReadStream(APK_FILE).pipe(res);
}

function unauthorized(res) {
  sendHtml(res, 401, `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH 手机控制 · 未配对</title>
<style>body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#0f1115;color:#e8eaed;margin:0;padding:32px;line-height:1.7}h1{font-size:22px}code{background:#1c2027;padding:3px 7px;border-radius:6px;word-break:break-all}.muted{color:#9aa0a6}</style>
<h1>需要配对</h1><p>这个地址只接受已配对的手机访问。</p>
<p>请在电脑上运行 <code>dsh-mobile</code> 启动器，用手机扫描它显示的二维码，或直接打开它输出的配对链接。</p>
<p class="muted">配对链接形如 <code>http://电脑局域网IP:8787/pair/…</code>，每个链接只能由你自己的设备使用。</p>
<p><a href="/app.apk" style="color:#8ab4f8">下载安卓 App（约 0.3 MB）</a></p>`);
}

/* ---------------------------------------------------------------- proxying */

function upstreamCookie(clientCookie, d) {
  const kept = [];
  for (const part of String(clientCookie || '').split(';')) {
    const s = part.trim();
    if (!s) continue;
    const name = s.slice(0, Math.max(0, s.indexOf('='))).trim();
    if (!name || name === BRIDGE_COOKIE || /^dsh-auth-/i.test(name)) continue;
    kept.push(s);
  }
  kept.push(d.cookie);
  return kept.join('; ');
}

function buildRequestHeaders(req, d) {
  const h = { ...req.headers };
  for (const k of ['host', 'origin', 'referer', 'cookie', 'proxy-connection', 'x-forwarded-for', 'x-forwarded-host', 'x-forwarded-proto', 'forwarded']) delete h[k];
  h.host = d.authority;
  if (req.headers.origin) h.origin = `http://${d.authority}`;
  if (req.headers.referer) {
    try {
      const r = new URL(req.headers.referer);
      h.referer = `http://${d.authority}${r.pathname}${r.search}`;
    } catch { delete h.referer; }
  }
  h.cookie = upstreamCookie(req.headers.cookie, d);
  return h;
}

function rewriteLocation(loc, d, req) {
  if (!loc) return loc;
  try {
    const u = new URL(loc, `http://${d.authority}`);
    if (u.hostname === '127.0.0.1' && Number(u.port || 80) === d.port) {
      return `${u.pathname}${u.search}${u.hash}`;
    }
  } catch {}
  return loc;
}

function responseHeaders(up, d, req) {
  const h = { ...up.headers };
  for (const k of ['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade']) delete h[k];
  if (h['set-cookie']) {
    const list = (Array.isArray(h['set-cookie']) ? h['set-cookie'] : [h['set-cookie']])
      .filter((c) => !/^dsh-auth-/i.test(c));
    if (list.length) h['set-cookie'] = list;
    else delete h['set-cookie'];
  }
  if (h.location) h.location = rewriteLocation(h.location, d, req);
  if (h['access-control-allow-origin'] && String(h['access-control-allow-origin']).includes('127.0.0.1')) {
    h['access-control-allow-origin'] = req.headers.origin || '*';
  }
  return h;
}

async function ensureDsh() {
  if (dsh.origin) {
    if (dsh.cookieExp - Date.now() < 864e5) {
      try {
        const minted = mintDshCookie(dsh.authority);
        dsh.cookie = minted.cookie;
        dsh.cookieExp = minted.exp;
      } catch (e) { log('续期 DSH Cookie 失败：' + e.message); }
    }
    return dsh;
  }
  try {
    await discoverDsh();
  } catch (e) {
    log('查找 DSH 失败：' + e.message);
  }
  return dsh.origin ? dsh : null;
}

async function proxyHttp(req, res) {
  const d = await ensureDsh();
  if (!d) return sendText(res, 502, 'DSH 未运行，或桥接无法连接本机 DSH。请先打开 DeepSeek Harness 桌面端。');
  const headers = buildRequestHeaders(req, d);
  const up = http.request({
    host: '127.0.0.1', port: d.port, method: req.method, path: req.url, headers, timeout: 0,
  }, (ur) => {
    res.writeHead(ur.statusCode || 502, responseHeaders(ur, d, req));
    ur.pipe(res);
    ur.on('error', () => res.destroy());
  });
  up.on('error', (e) => {
    log(`代理错误 ${req.method} ${req.url}: ${e.message}`);
    if (!res.headersSent) sendText(res, 502, `无法连接 DSH：${e.message}`);
    else res.destroy();
  });
  req.on('aborted', () => up.destroy());
  res.on('close', () => { if (!res.writableEnded) up.destroy(); });
  req.pipe(up);
}

function handleUpgrade(req, socket, head) {
  socket.on('error', () => {});
  if (!isAllowedHost(req.headers.host)) {
    socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
    return;
  }
  const origin = req.headers.origin;
  if (origin && origin !== `http://${req.headers.host}`) {
    socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
    return;
  }
  if (!isAuthed(req)) {
    socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    return;
  }
  if (!dsh.origin) {
    socket.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n');
    return;
  }
  const d = dsh;
  const h = { ...req.headers };
  for (const k of ['host', 'origin', 'cookie', 'proxy-connection', 'x-forwarded-for', 'x-forwarded-host', 'x-forwarded-proto']) delete h[k];
  h.host = d.authority;
  if (origin) h.origin = `http://${d.authority}`;
  h.cookie = upstreamCookie(req.headers.cookie, d);

  const up = net.connect(d.port, '127.0.0.1', () => {
    up.setNoDelay(true);
    socket.setNoDelay(true);
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    for (const [k, v] of Object.entries(h)) {
      if (Array.isArray(v)) for (const x of v) lines.push(`${k}: ${x}`);
      else if (v !== undefined) lines.push(`${k}: ${v}`);
    }
    up.write(lines.join('\r\n') + '\r\n\r\n');
    if (head && head.length) up.write(head);
    socket.pipe(up);
    up.pipe(socket);
  });
  up.on('error', (e) => { log('WebSocket 上游错误：' + e.message); socket.destroy(); });
  socket.on('error', () => up.destroy());
  socket.on('close', () => up.destroy());
  up.on('close', () => socket.destroy());
}

/* ------------------------------------------------------------------- pair */

function bridgeBase(req) {
  return `http://${req.headers.host}`;
}

function handlePair(req, res, token) {
  const ip = req.socket.remoteAddress || 'unknown';
  if (rateLimited(ip)) return sendText(res, 429, '尝试次数过多，请稍后再试。');
  if (!safeEqual(token, config.pairToken)) {
    log(`配对失败：来自 ${ip} 的令牌不匹配`);
    return sendText(res, 404, '配对链接无效。请在电脑上重新生成配对链接。');
  }
  const cookie = issueBridgeCookie();
  const maxAge = PAIR_COOKIE_DAYS * 86400;
  log(`配对成功：${ip}（${req.headers['user-agent'] || 'unknown'}）`);
  res.writeHead(303, {
    ...COMMON_HEADERS,
    location: '/',
    'set-cookie': `${BRIDGE_COOKIE}=${cookie}; Path=/; Max-Age=${maxAge}; HttpOnly; SameSite=Strict`,
  });
  res.end();
}

/* ----------------------------------------------------------------- server */

const server = http.createServer((req, res) => {
  let url;
  try { url = new URL(req.url, 'http://bridge.local'); } catch { return sendText(res, 400, 'Bad request'); }

  if (!isAllowedHost(req.headers.host)) {
    return sendText(res, 403, 'Forbidden host.');
  }
  if (url.pathname === '/app.apk') return serveApk(res);
  if (url.pathname === '/healthz') {
    return sendText(res, 200, JSON.stringify({
      ok: true, service: 'dsh-mobile-bridge', dsh: dsh.origin, version: 1,
    }), { 'content-type': 'application/json; charset=utf-8' });
  }
  const origin = req.headers.origin;
  if (origin && origin !== bridgeBase(req)) {
    return sendText(res, 403, 'Forbidden origin.');
  }
  if (String(req.headers['sec-fetch-site'] || '') === 'cross-site') {
    return sendText(res, 403, 'Cross-site request blocked.');
  }

  const m = url.pathname.match(/^\/pair\/([A-Za-z0-9_-]{16,128})$/);
  if (m) return handlePair(req, res, m[1]);
  if (url.pathname === '/pair') return unauthorized(res);

  if (!isAuthed(req)) return unauthorized(res);
  proxyHttp(req, res).catch((e) => {
    log('请求处理失败：' + e.stack);
    if (!res.headersSent) sendText(res, 500, 'Bridge error: ' + e.message);
  });
});

server.requestTimeout = 0;
server.headersTimeout = 60_000;
server.keepAliveTimeout = 65_000;
server.on('upgrade', handleUpgrade);
server.on('clientError', (err, socket) => {
  if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
});

/* ----------------------------------------------------------------- startup */

function displayWidth(s) {
  let w = 0;
  for (const ch of s) {
    const c = ch.codePointAt(0);
    if (c === 0x200d || (c >= 0x0300 && c <= 0x036f)) continue;
    if ((c >= 0x1100 && c <= 0x115f) || (c >= 0x2e80 && c <= 0xa4cf)
        || (c >= 0xac00 && c <= 0xd7a3) || (c >= 0xf900 && c <= 0xfaff)
        || (c >= 0xfe30 && c <= 0xfe4f) || (c >= 0xff00 && c <= 0xff60)
        || (c >= 0xffe0 && c <= 0xffe6) || (c >= 0x1f300 && c <= 0x1faff)) {
      w += 2;
    } else {
      w += 1;
    }
  }
  return w;
}
function pad(s, n) {
  const text = String(s);
  return text + ' '.repeat(Math.max(0, n - displayWidth(text)));
}

function appPairLink(httpUrl) {
  return `dshmobile://pair?u=${encodeURIComponent(httpUrl)}`;
}

function printQr(httpUrl) {
  if (!qrcode) {
    console.log('  （未安装 qrcode-terminal，跳过二维码；请粘贴上面的配对链接。）\n');
    return;
  }
  qrcode.generate(appPairLink(httpUrl), { small: true });
}

function existingBridge(timeout = 900) {
  return new Promise((resolve) => {
    const host = BIND === '0.0.0.0' ? '127.0.0.1' : BIND;
    const req = http.get({ host, port: PORT, path: '/healthz', timeout }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (d) => { if (body.length < 2000) body += d; });
      res.on('end', () => {
        try {
          const j = JSON.parse(body);
          resolve(j && j.service === 'dsh-mobile-bridge' ? j : null);
        } catch { resolve(null); }
      });
    });
    req.on('timeout', () => { req.destroy(); resolve(null); });
    req.on('error', () => resolve(null));
  });
}

(async () => {
  try {
    await discoverDsh();
  } catch (e) {
    log('启动时未连接 DSH：' + e.message);
  }

  server.on('error', async (e) => {
    if (e.code === 'EADDRINUSE') {
      const running = await existingBridge();
      if (running) {
        let url = '';
        try { url = fs.readFileSync(PAIR_FILE, 'utf8').trim().split(/\r?\n/)[0]; } catch {}
        console.log(`\n桥接已经在运行（${BIND}:${PORT}）。`);
        if (url) {
          console.log(`手机配对链接：${url}\n`);
          if (!NO_QR) printQr(url);
        } else {
          console.log(`找不到 ${PAIR_FILE}，请关闭旧桥接后重新启动。`);
        }
        process.exit(0);
      }
      die(`端口 ${PORT} 已被其他程序占用；请用 --port 换一个端口。`);
    }
    die(e.message);
  });
  server.listen(PORT, BIND, () => {
    const shownPort = PORT === 80 ? '' : `:${PORT}`;
    const ips = BIND === '0.0.0.0' ? LAN.map((x) => x.address) : [BIND];
    const urls = ips.map((ip) => `http://${ip}${shownPort}/pair/${config.pairToken}`);
    fs.writeFileSync(PAIR_FILE, urls.join('\r\n') + '\r\n');
    try { fs.chmodSync(PAIR_FILE, 0o600); } catch {}

    const W = 68;
    const row = (text, indent = 2) => `  │${' '.repeat(indent)}${pad(text, W - indent)}│`;
    const sep = `  ├${'─'.repeat(W)}┤`;
    const lines = [
      '',
      `  ┌${'─'.repeat(W)}┐`,
      row('DSH 手机控制桥接已启动'),
      sep,
      row(`本机 DSH : ${dsh.origin || '未找到（请先打开 DSH 桌面端）'}`),
      row(`监听地址 : ${BIND}:${PORT}`),
      sep,
      row('手机配对链接（任一，需与手机同一局域网）'),
    ];
    for (const u of urls) lines.push(row(u, 4));
    lines.push(
      sep,
      row('安装包：手机浏览器打开下面的地址直接下载'),
      row(`http://${BIND === '0.0.0.0' ? LAN[0]?.address || '127.0.0.1' : BIND}${shownPort}/app.apk`, 4),
      sep,
      row('手机 App：点“扫码”扫描下方二维码，或粘贴上面的链接。'),
      row('浏览器：直接打开上面的 http 链接（不适合日常操作）。'),
      row('链接只给你自己的设备；泄露后请重启并加 --reset-pair。'),
      row('按 Ctrl+C 停止桥接。'),
      `  └${'─'.repeat(W)}┘`,
      '',
    );
    console.log(lines.join('\n'));
    if (!NO_QR) {
      console.log('  DSH 手机 App 配对二维码：\n');
      printQr(urls[0]);
      console.log('');
    }
    log(`配对链接已写入 ${PAIR_FILE}`);
  });
})();

process.on('SIGINT', () => { log('正在关闭…'); server.close(() => process.exit(0)); setTimeout(() => process.exit(0), 1500); });
process.on('SIGTERM', () => { server.close(() => process.exit(0)); setTimeout(() => process.exit(0), 1500); });
