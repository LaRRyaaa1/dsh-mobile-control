import fs from 'node:fs';
import net from 'node:net';
import crypto from 'node:crypto';

const pair = fs.readFileSync(new URL('../.state/pair-url.txt', import.meta.url), 'utf8').trim();
const base = new URL(pair);
let r = await fetch(pair, { redirect: 'manual' });
const cookie = (r.headers.get('set-cookie') || '').split(';')[0];

const key = crypto.randomBytes(16).toString('base64');
const req = [
  'GET /api/remote.mux HTTP/1.1',
  `Host: ${base.host}`,
  'Upgrade: websocket',
  'Connection: Upgrade',
  `Sec-WebSocket-Key: ${key}`,
  'Sec-WebSocket-Version: 13',
  `Cookie: ${cookie}`,
  `Origin: http://${base.host}`,
  '',
  '',
].join('\r\n');

const socket = net.connect(Number(base.port), base.hostname, () => socket.write(req));
socket.setTimeout(4000);
socket.on('data', (d) => {
  console.log(d.toString('utf8').split('\r\n').slice(0, 6).join('\n'));
  socket.destroy();
});
socket.on('timeout', () => { console.log('TIMEOUT'); socket.destroy(); });
socket.on('error', (e) => console.error('ERR', e.message));
