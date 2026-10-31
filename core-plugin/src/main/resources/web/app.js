// PeakVerse Web-Oberflaeche: Rahmen. Module liefern ihre Seite als JS-Modul (/m/<id>/panel.js) mit "export default function mount(root, ctx)".
const STR = {
  de: { home: 'Start', log: 'Protokoll', logout: 'Abmelden', hello: 'Angemeldet als', welcome: 'Willkommen', welcomeSub: 'Hier verwaltest du den Server. Alle Aenderungen werden protokolliert.',
    online: 'Gerade hier', nobody: 'nur du', modules: 'Bereiche', noModules: 'Keine Bereiche aktiv.', time: 'Zeit', admin: 'Admin', area: 'Bereich', action: 'Aktion',
    noLog: 'Noch nichts protokolliert.', conflict: 'Konflikt', conflictText: 'Ein anderer Administrator hat inzwischen etwas geaendert', overwrite: 'Trotzdem speichern', cancel: 'Abbrechen',
    reload: 'Neu laden', needLogin: 'Nicht angemeldet', needLoginText: 'Gib im Spiel /peakcore web ein und oeffne den Link.', changedBy: 'hat etwas geaendert in', saved: 'Gespeichert',
    error: 'Fehler', live: 'Live verbunden', offline: 'Verbindung verloren, versuche neu...', ok: 'OK', yes: 'Ja', confirmT: 'Sicher?' },
  en: { home: 'Home', log: 'Log', logout: 'Log out', hello: 'Signed in as', welcome: 'Welcome', welcomeSub: 'Manage your server here. Every change is logged.',
    online: 'Here now', nobody: 'only you', modules: 'Areas', noModules: 'No areas active.', time: 'Time', admin: 'Admin', area: 'Area', action: 'Action',
    noLog: 'Nothing logged yet.', conflict: 'Conflict', conflictText: 'Another administrator has changed something in the meantime', overwrite: 'Save anyway', cancel: 'Cancel',
    reload: 'Reload', needLogin: 'Not signed in', needLoginText: 'Type /peakcore web in the game and open the link.', changedBy: 'changed something in', saved: 'Saved',
    error: 'Error', live: 'Live connected', offline: 'Connection lost, retrying...', ok: 'OK', yes: 'Yes', confirmT: 'Are you sure?' }
};

const $ = (s, r = document) => r.querySelector(s);
const store = {
  get(k, d) { try { return localStorage.getItem('pv.' + k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem('pv.' + k, v); } catch { /* privater Modus */ } }
};
const S = { lang: store.get('lang', navigator.language?.startsWith('de') ? 'de' : 'en'), theme: store.get('theme', 'dark'), fx: store.get('fx', '1') === '1', snd: store.get('snd', '1') === '1',
  me: '', modules: [], revs: {}, current: null, onChanged: [], session: null };
const t = k => STR[S.lang][k] ?? k;
const tr = o => o?.[S.lang] ?? o?.de ?? '';

// ---- Hilfen ----
export function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === false || v == null) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'value') el.value = v;
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  return el;
}

let audio;
function beep(freq = 520, ms = 60, vol = .04) {
  if (!S.snd) return;
  try {
    audio ??= new (window.AudioContext || window.webkitAudioContext)();
    const o = audio.createOscillator(), g = audio.createGain();
    o.type = 'square'; o.frequency.value = freq; g.gain.value = vol;
    o.connect(g); g.connect(audio.destination); o.start(); o.stop(audio.currentTime + ms / 1000);
  } catch { /* kein Audio */ }
}
document.addEventListener('click', e => { if (e.target.closest('.px-btn, nav a, .tile')) beep(e.target.closest('.primary') ? 740 : 520); });

function toast(text, kind = '') {
  const el = h('div', { class: 'toast ' + kind }, text);
  $('#toasts').append(el);
  if (kind === 'good') beep(880, 90); else if (kind === 'bad') beep(180, 140, .06);
  setTimeout(() => el.remove(), 4500);
}

function modal(title, text, buttons) {
  return new Promise(resolve => {
    const m = $('#modal');
    const done = v => { m.hidden = true; m.replaceChildren(); resolve(v); };
    m.replaceChildren(h('div', { class: 'box' }, h('h3', {}, title), h('p', {}, text),
      h('div', { class: 'row' }, buttons.map(b => h('button', { class: 'px-btn ' + (b.cls || ''), onclick: () => done(b.value) }, b.label)))));
    m.hidden = false;
  });
}
const confirmBox = text => modal(t('confirmT'), text, [{ label: t('cancel'), value: false }, { label: t('yes'), value: true, cls: 'danger' }]);

// ---- Schnittstelle ----
async function api(path, opts = {}, moduleId = null, force = false) {
  const headers = { 'X-Peak': '1', ...(opts.headers || {}) };
  if (moduleId && opts.method && opts.method !== 'GET') {
    if (S.revs[moduleId] != null) headers['X-Rev'] = String(S.revs[moduleId]);
    if (force) headers['X-Force'] = '1';
  }
  let body = opts.body;
  if (body && !(body instanceof Blob) && !(body instanceof ArrayBuffer) && typeof body !== 'string') { body = JSON.stringify(body); headers['Content-Type'] = 'application/json'; }
  const res = await fetch(path, { method: opts.method || 'GET', headers, body, credentials: 'same-origin' });
  const rev = res.headers.get('X-Rev');
  if (rev && moduleId) S.revs[moduleId] = Number(rev);
  if (res.status === 401) { needLogin(); throw new Error(t('needLogin')); }
  const type = res.headers.get('Content-Type') || '';
  const data = type.includes('json') ? await res.json() : opts.raw ? await res.blob() : await res.text();
  if (res.status === 409 && data?.conflict && !force && opts.method) {
    const choice = await modal(t('conflict'), t('conflictText') + (data.by ? ' (' + data.by + ')' : '') + '.',
      [{ label: t('cancel'), value: 'cancel' }, { label: t('overwrite'), value: 'force', cls: 'danger' }]);
    if (choice === 'force') return api(path, opts, moduleId, true);
    throw new Error(t('conflict'));
  }
  if (!res.ok) throw new Error(data?.error || ('HTTP ' + res.status));
  return data;
}

function needLogin() {
  $('#app').replaceChildren(h('main', {}, h('div', { class: 'card' }, h('h1', {}, t('needLogin')), h('p', { class: 'sub' }, t('needLoginText')))));
}

// ---- Rahmen ----
function applyPrefs() {
  document.documentElement.dataset.theme = S.theme;
  document.documentElement.lang = S.lang;
  document.body.classList.toggle('fx-off', !S.fx);
  $('#b-lang').textContent = S.lang.toUpperCase();
  $('#b-theme').textContent = S.theme === 'dark' ? '☾' : '☀';
  $('#b-fx').classList.toggle('active', S.fx);
  $('#b-snd').classList.toggle('active', S.snd);
  $('#b-out').textContent = t('logout');
  $('#who').replaceChildren(t('hello') + ' ', h('b', {}, S.me));
}

function buildNav() {
  const nav = $('#nav');
  const items = [{ id: 'home', label: t('home') }, ...S.modules.map(m => ({ id: m.id, label: S.lang === 'de' ? m.titleDe : m.titleEn })), { id: 'log', label: t('log') }];
  nav.replaceChildren(...items.map(i => h('a', { 'data-id': i.id, class: S.current === i.id ? 'on' : '', onclick: () => go(i.id) }, i.label)));
}

async function go(id) {
  S.current = id; S.onChanged = [];
  location.hash = id;
  buildNav();
  const view = $('#view');
  view.replaceChildren();
  try {
    if (id === 'home') home(view);
    else if (id === 'log') await logView(view);
    else {
      const mod = await import('/m/' + id + '/panel.js?v=' + Date.now());
      await mod.default(view, ctxFor(id));
    }
  } catch (e) {
    view.replaceChildren(h('div', { class: 'card' }, h('h1', {}, t('error')), h('p', {}, e.message)));
  }
}

function ctxFor(id) {
  return {
    id, api: (p, o) => api('/api/' + id + '/' + p, o, id), t: tr, get lang() { return S.lang; }, me: S.me,
    toast, confirm: confirmBox, h, beep, on: fn => S.onChanged.push(fn), saved: () => toast(t('saved'), 'good'), rev: () => S.revs[id]
  };
}

function home(view) {
  const online = S.session.online.filter(n => n !== S.me);
  view.append(h('h1', {}, t('welcome') + ', ' + S.me), h('p', { class: 'sub' }, t('welcomeSub')),
    h('div', { class: 'card' }, h('h2', {}, t('online')), online.length ? online.map(n => h('span', { class: 'pill' }, n)) : h('span', { class: 'sub' }, t('nobody'))),
    h('h2', {}, t('modules')),
    S.modules.length ? h('div', { class: 'grid' }, S.modules.map(m => h('div', { class: 'tile', onclick: () => go(m.id) }, h('div', { class: 'nm' }, S.lang === 'de' ? m.titleDe : m.titleEn)))) : h('div', { class: 'empty' }, t('noModules')),
    h('p', { class: 'sub' }, 'PeakCore ' + S.session.version));
}

async function logView(view) {
  const rows = await api('/api/log?n=200');
  view.append(h('h1', {}, t('log')), rows.length ? h('div', { class: 'card' }, h('table', {}, h('tr', {}, h('th', {}, t('time')), h('th', {}, t('admin')), h('th', {}, t('area')), h('th', {}, t('action'))),
    rows.map(r => h('tr', {}, h('td', {}, new Date(r.time).toLocaleString(S.lang)), h('td', {}, r.admin), h('td', {}, r.module), h('td', {}, r.action))))) : h('div', { class: 'empty' }, t('noLog')));
}

function connectLive() {
  let es;
  const open = () => {
    es = new EventSource('/api/events');
    es.addEventListener('changed', ev => {
      const d = JSON.parse(ev.data);
      if (d.by && d.by !== S.me) {
        const m = S.modules.find(x => x.id === d.module);
        toast(d.by + ' ' + t('changedBy') + ' ' + (m ? (S.lang === 'de' ? m.titleDe : m.titleEn) : d.module));
      }
      S.onChanged.forEach(fn => { try { fn(d); } catch (e) { console.error(e); } });
    });
    es.onerror = () => { es.close(); setTimeout(open, 4000); };
  };
  open();
}

function background() {
  const c = $('#bg'), g = c.getContext('2d');
  let pts = [];
  const resize = () => { c.width = innerWidth; c.height = innerHeight; pts = Array.from({ length: Math.round(innerWidth * innerHeight / 22000) }, () => ({ x: Math.random() * c.width, y: Math.random() * c.height, s: 3 + Math.floor(Math.random() * 3) * 3, v: .1 + Math.random() * .35, hue: Math.random() < .5 ? 0 : 1 })); };
  resize(); addEventListener('resize', resize);
  const loop = () => {
    if (S.fx) {
      g.clearRect(0, 0, c.width, c.height);
      const col = getComputedStyle(document.documentElement);
      const a = col.getPropertyValue('--accent').trim(), b = col.getPropertyValue('--accent2').trim();
      for (const p of pts) { p.y -= p.v; if (p.y < -10) { p.y = c.height + 10; p.x = Math.random() * c.width; } g.fillStyle = p.hue ? a : b; g.globalAlpha = .18; g.fillRect(Math.round(p.x), Math.round(p.y), p.s, p.s); }
    }
    requestAnimationFrame(loop);
  };
  loop();
}

async function boot() {
  applyPrefs();
  background();
  $('#b-lang').onclick = () => { S.lang = S.lang === 'de' ? 'en' : 'de'; store.set('lang', S.lang); applyPrefs(); buildNav(); go(S.current); };
  $('#b-theme').onclick = () => { S.theme = S.theme === 'dark' ? 'light' : 'dark'; store.set('theme', S.theme); applyPrefs(); };
  $('#b-fx').onclick = () => { S.fx = !S.fx; store.set('fx', S.fx ? '1' : '0'); applyPrefs(); };
  $('#b-snd').onclick = () => { S.snd = !S.snd; store.set('snd', S.snd ? '1' : '0'); applyPrefs(); if (S.snd) beep(660); };
  $('#b-out').onclick = async () => { try { await api('/api/logout', { method: 'POST' }); } catch { /* egal */ } needLogin(); };
  try {
    S.session = await api('/api/session');
  } catch { return; }
  S.me = S.session.name; S.modules = S.session.modules;
  for (const m of S.modules) S.revs[m.id] = m.rev;
  applyPrefs();
  connectLive();
  const start = location.hash.slice(1);
  await go(start === 'log' || S.modules.some(m => m.id === start) ? start : 'home');
}
boot();
