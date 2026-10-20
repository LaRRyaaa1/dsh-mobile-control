import fs from 'node:fs';
const u = fs.readFileSync(new URL('../.state/pair-url.txt', import.meta.url), 'utf8').trim().split(/\r?\n/)[0];
const r = await fetch(u, { redirect: 'manual' });
console.log('pair', r.status, r.headers.get('location'), (r.headers.get('set-cookie') || '').slice(0, 60));
const loc = new URL(r.headers.get('location'), u).href;
const cookie = (r.headers.get('set-cookie') || '').split(';')[0];
const r2 = await fetch(loc, { redirect: 'manual', headers: { cookie } });
console.log('home', r2.status, r2.headers.get('set-cookie'), (await r2.text()).length);
