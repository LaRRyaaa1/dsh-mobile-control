import fs from 'node:fs';

const pair = fs.readFileSync(new URL('../.state/pair-url.txt', import.meta.url), 'utf8').trim();
const base = new URL(pair).origin;
let r = await fetch(base + '/', { redirect: 'manual' });
console.log('no-cookie', r.status);

r = await fetch(pair, { redirect: 'manual' });
console.log('pair', r.status, 'set-cookie?', Boolean(r.headers.get('set-cookie')));
const ck = (r.headers.get('set-cookie') || '').split(';')[0];

r = await fetch(base + '/', { headers: { cookie: ck } });
const body = await r.text();
console.log('home', r.status, 'len', body.length, 'boot', body.includes('__DSH_BOOT__'), 'title', /<title>(.*?)<\/title>/.exec(body)?.[1]);
console.log('has 62734?', body.includes('62734'));

const m = body.match(/src="([^"]+\.js)"/);
console.log('js', m?.[1]);
if (m) {
  const a = await fetch(new URL(m[1], base + '/'), { headers: { cookie: ck } });
  const text = await a.text();
  console.log('asset', a.status, a.headers.get('content-type'), text.length);
}
