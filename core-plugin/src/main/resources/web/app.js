// PeakVerse Web-Oberflaeche: Rahmen. Module liefern ihre Seite als JS-Modul (/m/<id>/panel.js) mit "export default function mount(root, ctx)".
// Der Rahmen (Menue, Startseite, Server, Module) ist in Deutsch, Englisch, Franzoesisch und Spanisch; die Seiten der Module bisher nur Deutsch/Englisch.
const LANGS = [['de', 'Deutsch'], ['en', 'English'], ['fr', 'Français'], ['es', 'Español']];
const STR = {
  de: { home: 'Start', server: 'Server', modules: 'Module', log: 'Protokoll', logout: 'Abmelden', hello: 'Angemeldet als', welcome: 'Willkommen',
    th_day: 'Tag', th_night: 'Nacht', pickLang: 'Sprache wählen', ctaServer: 'Server verwalten', ctaModules: 'Module ansehen',
    tag1: 'Texturen zeichnen, die jeder sofort sieht.', tag2: 'Sounds, Packs und Fähigkeiten an einem Ort.', tag3: 'Dein Server, deine Regeln, live aktualisiert.',
    aboutT: 'Was ist PeakVerse?', aboutP: 'PeakVerse ist die Verwaltung für deinen Minecraft-Server. Du gestaltest Texturen, Sounds und das Server-Pack bequem im Browser. Spieler mit der Mod bekommen jede Änderung sofort, ohne neu beizutreten.',
    f1T: 'Texturen', f1P: 'Zeichne Gegenstände im Pixel-Editor oder nimm ein Standard-Bild und mach es zu deinem.',
    f2T: 'Pack-Verwaltung', f2P: 'Testmodus, Freigabe und Zurückrollen: das Server-Pack bleibt immer unter deiner Kontrolle.',
    f3T: 'Live und sicher', f3P: 'Mehrere Admins gleichzeitig, Konflikt-Warnung und ein Protokoll über jede Änderung.',
    howT: 'So funktioniert es', s1T: 'Anmelden', s1P: 'Im Spiel /peakcore web eingeben und den Link anklicken.', s2T: 'Gestalten', s2P: 'Texturen zeichnen, Dateien hochladen, Fähigkeiten einstellen.', s3T: 'Freigeben', s3P: 'Das Pack wird gebaut und kommt bei den Spielern sofort an.',
    statModules: 'Module', statAdmins: 'Admins online', statLangs: 'Sprachen',
    srvT: 'Dein Server', srvSub: 'Status und Werkzeuge auf einen Blick.', srvPack: 'Pack-Server', running: 'läuft', stopped: 'aus', sending: 'Pack wird gesendet', testMode: 'Testmodus', problems: 'Hinweise',
    adminsOnline: 'Admins online', tools: 'Werkzeuge', recent: 'Zuletzt geändert', open: 'Öffnen',
    modT: 'Module', modSub: 'Jedes Modul bringt eigene Funktionen mit. Du schaltest sie in der Konfiguration ein und aus.', planned: 'Geplant', soon: 'Kommt später',
    online: 'Gerade hier', nobody: 'nur du', noModules: 'Keine Bereiche aktiv.', time: 'Zeit', admin: 'Admin', area: 'Bereich', action: 'Aktion',
    noLog: 'Noch nichts protokolliert.', conflict: 'Konflikt', conflictText: 'Ein anderer Administrator hat inzwischen etwas geaendert', overwrite: 'Trotzdem speichern', cancel: 'Abbrechen',
    reload: 'Neu laden', needLogin: 'Nicht angemeldet', needLoginText: 'Gib im Spiel /peakcore web ein und oeffne den Link.', changedBy: 'hat etwas geaendert in', saved: 'Gespeichert',
    error: 'Fehler', live: 'Live verbunden', offline: 'Verbindung verloren, versuche neu...', ok: 'OK', yes: 'Ja', confirmT: 'Sicher?' },
  en: { home: 'Home', server: 'Server', modules: 'Modules', log: 'Log', logout: 'Log out', hello: 'Signed in as', welcome: 'Welcome',
    th_day: 'Day', th_night: 'Night', pickLang: 'Choose your language', ctaServer: 'Manage server', ctaModules: 'Browse modules',
    tag1: 'Draw textures everyone sees instantly.', tag2: 'Sounds, packs and abilities in one place.', tag3: 'Your server, your rules, updated live.',
    aboutT: 'What is PeakVerse?', aboutP: 'PeakVerse is the control center for your Minecraft server. Design textures, sounds and the server pack right in your browser. Players with the mod get every change instantly, without rejoining.',
    f1T: 'Textures', f1P: 'Draw items in the pixel editor or start from a default image and make it yours.',
    f2T: 'Pack management', f2P: 'Test mode, release and rollback: the server pack always stays under your control.',
    f3T: 'Live and safe', f3P: 'Several admins at once, conflict warnings and a log of every change.',
    howT: 'How it works', s1T: 'Sign in', s1P: 'Type /peakcore web in the game and click the link.', s2T: 'Create', s2P: 'Draw textures, upload files, set abilities.', s3T: 'Release', s3P: 'The pack is built and reaches players right away.',
    statModules: 'Modules', statAdmins: 'Admins online', statLangs: 'Languages',
    srvT: 'Your server', srvSub: 'Status and tools at a glance.', srvPack: 'Pack server', running: 'running', stopped: 'off', sending: 'Sending pack', testMode: 'Test mode', problems: 'Notices',
    adminsOnline: 'Admins online', tools: 'Tools', recent: 'Recent changes', open: 'Open',
    modT: 'Modules', modSub: 'Every module brings its own features. Switch them on and off in the config.', planned: 'Planned', soon: 'Coming later',
    online: 'Here now', nobody: 'only you', noModules: 'No areas active.', time: 'Time', admin: 'Admin', area: 'Area', action: 'Action',
    noLog: 'Nothing logged yet.', conflict: 'Conflict', conflictText: 'Another administrator has changed something in the meantime', overwrite: 'Save anyway', cancel: 'Cancel',
    reload: 'Reload', needLogin: 'Not signed in', needLoginText: 'Type /peakcore web in the game and open the link.', changedBy: 'changed something in', saved: 'Saved',
    error: 'Error', live: 'Live connected', offline: 'Connection lost, retrying...', ok: 'OK', yes: 'Yes', confirmT: 'Are you sure?' },
  fr: { home: 'Accueil', server: 'Serveur', modules: 'Modules', log: 'Journal', logout: 'Déconnexion', hello: 'Connecté en tant que', welcome: 'Bienvenue',
    th_day: 'Jour', th_night: 'Nuit', pickLang: 'Choisis ta langue', ctaServer: 'Gérer le serveur', ctaModules: 'Voir les modules',
    tag1: 'Dessine des textures que tout le monde voit tout de suite.', tag2: 'Sons, packs et capacités au même endroit.', tag3: 'Ton serveur, tes règles, mis à jour en direct.',
    aboutT: "Qu'est-ce que PeakVerse ?", aboutP: "PeakVerse est le centre de contrôle de ton serveur Minecraft. Crée textures, sons et pack serveur dans ton navigateur. Les joueurs avec le mod reçoivent chaque changement tout de suite, sans se reconnecter.",
    howT: 'Comment ça marche', s1T: 'Connexion', s1P: 'Tape /peakcore web en jeu et clique sur le lien.', s2T: 'Créer', s2P: 'Dessine des textures, envoie des fichiers, règle les capacités.', s3T: 'Publier', s3P: 'Le pack est construit et arrive tout de suite chez les joueurs.',
    srvT: 'Ton serveur', srvSub: "L'état et les outils en un coup d'œil.", modT: 'Modules', open: 'Ouvrir', planned: 'Prévu', soon: 'Bientôt', saved: 'Enregistré', cancel: 'Annuler', yes: 'Oui', error: 'Erreur' },
  es: { home: 'Inicio', server: 'Servidor', modules: 'Módulos', log: 'Registro', logout: 'Cerrar sesión', hello: 'Sesión de', welcome: 'Bienvenido',
    th_day: 'Día', th_night: 'Noche', pickLang: 'Elige tu idioma', ctaServer: 'Gestionar servidor', ctaModules: 'Ver módulos',
    tag1: 'Dibuja texturas que todos ven al instante.', tag2: 'Sonidos, packs y habilidades en un solo lugar.', tag3: 'Tu servidor, tus reglas, actualizado en vivo.',
    aboutT: '¿Qué es PeakVerse?', aboutP: 'PeakVerse es el centro de control de tu servidor de Minecraft. Crea texturas, sonidos y el pack del servidor desde el navegador. Los jugadores con el mod reciben cada cambio al instante, sin volver a entrar.',
    howT: 'Cómo funciona', s1T: 'Iniciar sesión', s1P: 'Escribe /peakcore web en el juego y pulsa el enlace.', s2T: 'Crear', s2P: 'Dibuja texturas, sube archivos, ajusta habilidades.', s3T: 'Publicar', s3P: 'El pack se construye y llega a los jugadores de inmediato.',
    srvT: 'Tu servidor', srvSub: 'Estado y herramientas de un vistazo.', modT: 'Módulos', open: 'Abrir', planned: 'Previsto', soon: 'Próximamente', saved: 'Guardado', cancel: 'Cancelar', yes: 'Sí', error: 'Error' }
};
const THEMES = ['day', 'night'];
const $ = (s, r = document) => r.querySelector(s);
const store = {
  get(k, d) { try { return localStorage.getItem('pv.' + k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem('pv.' + k, v); } catch { /* privater Modus */ } }
};
const guessLang = () => { const l = (navigator.language || 'en').slice(0, 2); return LANGS.some(x => x[0] === l) ? l : 'en'; };
const S = { lang: LANGS.some(x => x[0] === store.get('lang', '')) ? store.get('lang', '') : guessLang(), theme: THEMES.includes(store.get('theme', '')) ? store.get('theme', '') : 'day',
  fx: store.get('fx', '1') === '1', snd: store.get('snd', '1') === '1', me: '', modules: [], revs: {}, current: null, onChanged: [], session: null };
const t = k => STR[S.lang]?.[k] ?? STR.en[k] ?? STR.de[k] ?? k;
const tr = o => o?.[S.lang] ?? o?.en ?? o?.de ?? '';

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
document.addEventListener('click', e => { if (e.target.closest('.px-btn, nav a, .tile, .mcard, .lang-chip')) beep(e.target.closest('.primary') ? 740 : 520); });

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

// ---- Module: Beschreibung und animiertes Logo ----
const CORE_TOOLS = ['pack', 'areas'];
const DESC = {
  texturepeak: { de: 'Eigene Texturen für Gegenstände: Pixel-Editor, Standard-Bilder zum Kopieren und Ändern, Fähigkeiten und ein Archiv.', en: 'Custom item textures: pixel editor, default images to copy or modify, abilities and an archive.' },
  pack: { de: 'Das Server-Pack: Status, Testmodus mit Testern, Freigabe für alle und Zurückrollen.', en: 'The server pack: status, test mode with testers, release to everyone and rollback.' },
  areas: { de: 'Eigene Dateien: Sounds, Schriften, Partikel, HUD, Himmel, Titelbild und 3D-Modelle.', en: 'Your own files: sounds, fonts, particles, HUD, sky, title image and 3D models.' },
  peaksound: { de: 'Musik und Sounds im Spiel.', en: 'Music and sounds in the game.' }
};
const PLANNED = [{ name: 'PeakStats', de: 'Statistiken und Ranglisten.', en: 'Statistics and leaderboards.' }, { name: 'ServerPackKeeper', de: 'Sichert und verwaltet ganze Server-Packs.', en: 'Backs up and manages whole server packs.' }];

const SVGNS = 'http://www.w3.org/2000/svg';
function svg(tag, attrs = {}, ...kids) {
  const el = document.createElementNS(SVGNS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  kids.flat().forEach(k => k && el.append(k));
  return el;
}

// TexturePeak: ein Berggipfel aus Pixeln, ueber den eine Farbwelle laeuft; Funken blinken, der Gipfel schwebt und die Farben wandern langsam
function logoTexturePeak(size = 96) {
  const map = ['...ww...', '..wwww..', '..wbbw..', '.bbbdbb.', '.bbbbbbd', 'bbdbbbbb', 'bbbbbdbb', 'bbbbbbbb'];
  const col = { w: '#f4f7ff', b: '#6f8cff', d: '#4a5fd0' };
  const s = svg('svg', { viewBox: '0 0 64 64', width: size, height: size, class: 'logo-tp', 'aria-label': 'TexturePeak' });
  const g = svg('g', { class: 'tp-peak' });
  map.forEach((row, y) => [...row].forEach((c, x) => {
    if (c === '.') return;
    const r = svg('rect', { x: 8 + x * 6 - 0, y: 12 + y * 5.5, width: 6.2, height: 5.7, fill: col[c], class: 'tp-px' });
    r.style.setProperty('--d', ((x + y) * 0.16).toFixed(2) + 's');
    g.append(r);
  }));
  s.append(g);
  [[10, 8], [54, 18], [50, 6]].forEach(([x, y], i) => {
    const sp = svg('g', { class: 'tp-spark', transform: `translate(${x} ${y})` }, svg('rect', { x: -1, y: -4, width: 2, height: 8, fill: '#ffd86b' }), svg('rect', { x: -4, y: -1, width: 8, height: 2, fill: '#ffd86b' }));
    sp.style.animationDelay = (i * 0.7) + 's';
    s.append(sp);
  });
  // ein Pinselstrich, der ueber den Berg wandert
  s.append(svg('rect', { class: 'tp-brush', x: 6, y: 56, width: 52, height: 3, fill: '#ffd86b', opacity: '.0' }));
  return s;
}
function logoGeneric(id, size = 96) {
  const s = svg('svg', { viewBox: '0 0 64 64', width: size, height: size, class: 'logo-gen logo-' + id });
  if (id === 'peaksound') [0, 1, 2, 3, 4].forEach(i => { const r = svg('rect', { x: 8 + i * 11, y: 12, width: 8, height: 40, fill: i % 2 ? '#b58cff' : '#7aa2ff', class: 'bar' }); r.style.animationDelay = (i * .18) + 's'; s.append(r); });
  else if (id === 'pack') { [0, 1, 2].forEach(i => { const r = svg('rect', { x: 10 + i * 4, y: 40 - i * 12, width: 40, height: 12, fill: ['#5fbf3c', '#8b5a2b', '#7aa2ff'][i], class: 'layer' }); r.style.animationDelay = (i * .25) + 's'; s.append(r); }); }
  else if (id === 'areas') { const g = svg('g', { class: 'spin', transform: 'translate(32 32)' }, [0, 1, 2, 3].map(i => svg('rect', { x: -3, y: -26, width: 6, height: 20, fill: ['#ffd86b', '#7aa2ff', '#b58cff', '#6fe0b0'][i], transform: `rotate(${i * 90})` }))); s.append(g, svg('rect', { x: 26, y: 26, width: 12, height: 12, fill: '#fff' })); }
  else { s.append(svg('rect', { x: 12, y: 12, width: 40, height: 40, fill: '#7aa2ff', class: 'layer' })); }
  return s;
}
const logoFor = (id, size) => id === 'texturepeak' ? logoTexturePeak(size) : logoGeneric(id, size);
const modTitle = m => S.lang === 'de' ? m.titleDe : m.titleEn;

// ---- Rahmen ----
function applyPrefs() {
  document.documentElement.dataset.theme = S.theme;
  document.documentElement.lang = S.lang;
  document.body.classList.toggle('fx-off', !S.fx);
  $('#b-lang').textContent = S.lang.toUpperCase();
  $('#b-theme').textContent = t('th_' + S.theme);
  $('#b-fx').classList.toggle('active', S.fx);
  $('#b-snd').classList.toggle('active', S.snd);
  $('#b-out').textContent = t('logout');
  $('#who').replaceChildren(t('hello') + ' ', h('b', {}, S.me));
}

// Reiter oben: Start, Server, Module, Protokoll. Die Seiten der Module haengen unter "Server" (Pack, Bereiche) oder "Module".
const parentOf = id => CORE_TOOLS.includes(id) ? 'server' : ['home', 'server', 'modules', 'log'].includes(id) ? id : 'modules';
function buildNav() {
  const items = [['home', t('home')], ['server', t('server')], ['modules', t('modules')], ['log', t('log')]];
  $('#nav').replaceChildren(...items.map(([id, label]) => h('a', { 'data-id': id, class: parentOf(S.current) === id ? 'on' : '', onclick: () => go(id) }, label)));
  const crumb = $('#crumb');
  const m = S.modules.find(x => x.id === S.current);
  if (m) crumb.replaceChildren(h('a', { class: 'crumb-a', onclick: () => go(parentOf(m.id)) }, '← ' + t(parentOf(m.id))), h('span', { class: 'crumb-logo' }, logoFor(m.id, 26)), h('b', {}, modTitle(m)));
  else crumb.replaceChildren();
}

async function go(id) {
  S.current = id; S.onChanged = []; parallax = [];
  location.hash = id;
  buildNav();
  const view = $('#view');
  view.replaceChildren();
  window.scrollTo(0, 0);
  try {
    if (id === 'home') home(view);
    else if (id === 'server') await serverPage(view);
    else if (id === 'modules') modulesPage(view);
    else if (id === 'log') await logView(view);
    else {
      const mod = await import('/m/' + id + '/panel.js?v=' + Date.now());
      await mod.default(view, ctxFor(id));
    }
  } catch (e) {
    view.replaceChildren(h('div', { class: 'card' }, h('h1', {}, t('error')), h('p', {}, e.message)));
  }
  reveal(view);
}

function ctxFor(id) {
  return {
    id, api: (p, o) => api('/api/' + id + '/' + p, o, id), t: tr, get lang() { return S.lang; }, me: S.me,
    toast, confirm: confirmBox, h, beep, on: fn => S.onChanged.push(fn), saved: () => toast(t('saved'), 'good'), rev: () => S.revs[id]
  };
}

// Elemente mit der Klasse "reveal" gleiten beim Erscheinen ein
function reveal(root) {
  const items = [...root.querySelectorAll('.reveal')];
  if (!S.fx || !('IntersectionObserver' in window)) { items.forEach(i => i.classList.add('in')); return; }
  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } }), { threshold: .12 });
  items.forEach((i, n) => { i.style.transitionDelay = Math.min(n % 6, 5) * 60 + 'ms'; io.observe(i); });
}

function countUp(el, to) {
  if (!S.fx) { el.textContent = to; return; }
  const t0 = performance.now();
  const step = now => { const p = Math.min(1, (now - t0) / 900); el.textContent = Math.round(to * (1 - Math.pow(1 - p, 3))); if (p < 1 && el.isConnected) requestAnimationFrame(step); };
  requestAnimationFrame(step);
}

function typeLines(el, lines) {
  if (!S.fx) { el.textContent = lines[0]; return; }
  let li = 0, ci = 0, del = false;
  const tick = () => {
    if (!el.isConnected) return;
    const line = lines[li];
    ci += del ? -1 : 1;
    el.textContent = line.slice(0, ci);
    let wait = del ? 18 : 42;
    if (!del && ci >= line.length) { del = true; wait = 2200; }
    else if (del && ci <= 0) { del = false; li = (li + 1) % lines.length; wait = 350; }
    setTimeout(tick, wait);
  };
  tick();
}

// ---- Startseite ----
function home(view) {
  const langs = h('div', { class: 'lang-row' }, h('span', { class: 'lang-lab' }, t('pickLang')),
    LANGS.map(([code, name]) => h('button', { class: 'lang-chip' + (S.lang === code ? ' on' : ''), onclick: () => setLang(code) }, h('b', {}, code.toUpperCase()), ' ' + name)));
  const tagline = h('span', { class: 'typed' });
  const title = h('h1', { class: 'big' }, [...'PeakVerse'].map((c, i) => { const s = h('span', { class: 'ch' }, c); s.style.animationDelay = (i * 55) + 'ms'; return s; }));
  view.append(
    heroScene(h('div', { class: 'hero-in' },
      h('div', { class: 'kicker' }, t('welcome') + ', ' + S.me),
      title,
      h('p', { class: 'tagline' }, tagline, h('span', { class: 'caret' })),
      h('div', { class: 'row' }, h('button', { class: 'px-btn primary big-btn', onclick: () => go('server') }, t('ctaServer')), h('button', { class: 'px-btn big-btn', onclick: () => go('modules') }, t('ctaModules'))),
      langs)),
    h('section', { class: 'about reveal' }, h('h2', {}, t('aboutT')), h('p', { class: 'lead' }, t('aboutP'))),
    h('div', { class: 'feat' }, [['f1', 'texturepeak'], ['f2', 'pack'], ['f3', 'areas']].map(([k, logo]) => h('div', { class: 'fcard reveal' }, h('div', { class: 'ficon' }, logoFor(logo, 64)), h('h3', {}, t(k + 'T')), h('p', {}, t(k + 'P'))))),
    h('section', { class: 'how reveal' }, h('h2', {}, t('howT')),
      h('div', { class: 'steps' }, ['s1', 's2', 's3'].map((k, i) => h('div', { class: 'step reveal' }, h('div', { class: 'num' }, i + 1), h('h3', {}, t(k + 'T')), h('p', {}, t(k + 'P')))))),
    h('div', { class: 'stats reveal' }, [[S.modules.length, t('statModules')], [Math.max(1, S.session.online.length), t('statAdmins')], [LANGS.length, t('statLangs')]].map(([n, l]) => { const v = h('b', {}, '0'); queueMicrotask(() => countUp(v, n)); return h('div', { class: 'stat' }, v, h('span', {}, l)); })));
  typeLines(tagline, [t('tag1'), t('tag2'), t('tag3')]);
}

function setLang(code) {
  S.lang = code; store.set('lang', code); applyPrefs(); go(S.current);
}

// ---- Server ----
async function serverPage(view) {
  let st = null, log = [];
  try { st = await api('/api/pack/status', {}, 'pack'); } catch { /* Pack-Modul aus */ }
  try { log = await api('/api/log?n=6'); } catch { /* egal */ }
  const chip = (txt, kind = '') => h('span', { class: 'chip ' + kind }, txt);
  const tools = S.modules.filter(m => CORE_TOOLS.includes(m.id));
  view.append(
    h('section', { class: 'srv-head reveal' },
      h('div', { class: 'rack' }, [0, 1, 2].map(i => h('div', { class: 'unit' }, h('i', { style: 'animation-delay:' + (i * .4) + 's' }), h('i', { style: 'animation-delay:' + (i * .4 + .2) + 's' }), h('em', {})))),
      h('div', { class: 'grow' }, h('h1', {}, t('srvT')), h('p', { class: 'sub' }, t('srvSub')),
        h('div', { class: 'chips' }, chip('PeakCore ' + S.session.version), chip(Math.max(1, S.session.online.length) + ' ' + t('statAdmins')),
          st ? [chip(t('srvPack') + ': ' + (st.serverRunning ? t('running') : t('stopped')), st.serverRunning ? 'good' : 'warn'), st.sending ? chip(t('sending'), 'good') : null, st.testMode ? chip(t('testMode'), 'warn') : null,
            (st.problems?.length) ? chip(st.problems.length + ' ' + t('problems'), 'bad') : null] : null))),
    h('h2', { class: 'reveal' }, t('tools')),
    h('div', { class: 'mgrid' }, tools.map(m => modCard(m))),
    h('h2', { class: 'reveal' }, t('recent')),
    log.length ? h('div', { class: 'card reveal' }, h('table', {}, log.slice(-6).reverse().map(r => h('tr', {}, h('td', { class: 'dim' }, new Date(r.time).toLocaleTimeString(S.lang)), h('td', {}, r.admin), h('td', {}, r.action))))) : h('div', { class: 'empty reveal' }, t('noLog')));
}

// ---- Module ----
function modCard(m) {
  return h('div', { class: 'mcard reveal', onclick: () => go(m.id) },
    h('div', { class: 'mlogo' }, logoFor(m.id, 84)), h('div', { class: 'mtxt' }, h('h3', {}, modTitle(m)), h('p', {}, tr(DESC[m.id]) || ''), h('span', { class: 'px-btn small' }, t('open') + ' →')));
}
function modulesPage(view) {
  const mods = S.modules.filter(m => !CORE_TOOLS.includes(m.id));
  view.append(h('section', { class: 'srv-head reveal' }, h('div', { class: 'grow' }, h('h1', {}, t('modT')), h('p', { class: 'sub' }, t('modSub')))),
    mods.length ? h('div', { class: 'mgrid' }, mods.map(modCard)) : h('div', { class: 'empty' }, t('noModules')),
    h('h2', { class: 'reveal' }, t('planned')),
    h('div', { class: 'mgrid' }, PLANNED.map(p => h('div', { class: 'mcard ghost reveal' }, h('div', { class: 'mlogo' }, logoGeneric('planned', 84)), h('div', { class: 'mtxt' }, h('h3', {}, p.name), h('p', {}, tr(p)), h('span', { class: 'chip' }, t('soon')))))));
}

// Pixel-Landschaft mit Parallaxe (eigene Bilder, zur Laufzeit gezeichnet): Himmel, Wolken, drei Huegelreihen mit Baeumen, Sonne oder Mond
let parallax = [];
function heroScene(content) {
  const night = S.theme === 'night', W = 320, H = 100;
  let seed = 11;
  const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  const mk = (w, ht, fn) => { const c = document.createElement('canvas'); c.width = w; c.height = ht; fn(c.getContext('2d')); return 'url(' + c.toDataURL('image/png') + ')'; };
  const sky = night ? ['#0a1030', '#0d1a42', '#112456', '#173068', '#1f3d7a'] : ['#4a90e2', '#5fa3ec', '#79b6f2', '#98c9f6', '#bddcf8'];
  const hill = (g, base, a1, k1, a2, k2, p, paint) => { for (let x = 0; x < W; x++) { const top = Math.round(base + a1 * Math.sin(2 * Math.PI * k1 * x / W + p) + a2 * Math.sin(2 * Math.PI * k2 * x / W + p * 2)); paint(g, x, top); } return g; };
  const layers = [
    { f: .6, css: '', img: mk(W, H, g => { sky.forEach((col, i) => { g.fillStyle = col; g.fillRect(0, i * 20, W, 20); }); if (night) for (let i = 0; i < 90; i++) { g.fillStyle = `rgba(255,255,255,${.4 + rnd() * .6})`; g.fillRect(Math.floor(rnd() * W), Math.floor(rnd() * 70), 1, 1); } }) },
    { f: .5, css: 'clouds', img: mk(W, H, g => {
      g.fillStyle = night ? 'rgba(150,170,230,.30)' : 'rgba(255,255,255,.92)';
      for (let i = 0; i < 7; i++) { const x = Math.floor(rnd() * W), y = 8 + Math.floor(rnd() * 36), w = 18 + Math.floor(rnd() * 18);
        for (const dx of [-W, 0, W]) { g.fillRect(x + dx, y + 3, w, 4); g.fillRect(x + dx + 3, y, w - 9, 3); g.fillRect(x + dx + 6, y - 2, Math.floor(w / 3), 2); } } }) },
    { f: .38, css: '', img: mk(W, H, g => hill(g, 60, 9, 2, 4, 5, 1, (g2, x, top) => { g2.fillStyle = night ? '#1a2c5a' : '#8fb7d6'; g2.fillRect(x, top, 1, H - top); })) },
    { f: .26, css: '', img: mk(W, H, g => hill(g, 68, 8, 3, 3, 7, 2, (g2, x, top) => { g2.fillStyle = night ? '#12304a' : '#4f9a4a'; g2.fillRect(x, top, 1, H - top); })) },
    { f: .12, css: '', img: mk(W, H, g => {
      const tops = [];
      hill(g, 80, 5, 2, 3, 6, 3, (g2, x, top) => { tops[x] = top; g2.fillStyle = night ? '#1c5a2e' : '#5fbf3c'; g2.fillRect(x, top, 1, 2); g2.fillStyle = night ? '#0f3a22' : '#3d8a2f'; g2.fillRect(x, top + 2, 1, 4); g2.fillStyle = night ? '#2c2218' : '#7b5a36'; g2.fillRect(x, top + 6, 1, H - top - 6); });
      for (let i = 0; i < 9; i++) { const x = 6 + Math.floor((i + rnd() * .6) * W / 9), top = tops[Math.min(x, W - 1)];
        g.fillStyle = night ? '#3a2a1a' : '#6b4a26'; g.fillRect(x + 2, top - 7, 2, 8);
        g.fillStyle = night ? '#0f4a26' : '#2e7a2a'; g.fillRect(x, top - 13, 6, 7); g.fillRect(x + 1, top - 15, 4, 2); } }) }
  ];
  const orb = mk(16, 16, g => { const c = 7.5; for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) { const d = Math.hypot(x - c, y - c); if (d > 7) continue;
    g.fillStyle = night ? (d < 3 && (x + y) % 5 === 0 ? '#c8cde8' : '#e8ecff') : (d < 4 ? '#fff6b0' : '#ffe066'); g.fillRect(x, y, 1, 1); } });
  parallax = [];
  const sec = h('section', { class: 'hero' });
  layers.forEach((l, i) => {
    const el = h('div', { class: 'ly ' + l.css });
    el.style.backgroundImage = l.img;
    sec.append(el); parallax.push([el, l.f]);
    if (i === 1) { const o = h('div', { class: 'orb' }); o.style.backgroundImage = orb; o.style.backgroundSize = '100% 100%'; sec.append(o); parallax.push([o, .55]); }
  });
  // Gluehwuermchen in der Nacht, Bluetenstaub am Tag
  for (let i = 0; i < 16; i++) { const f = h('i', { class: night ? 'fly' : 'pol' }); f.style.left = Math.round(rnd() * 100) + '%'; f.style.top = (35 + Math.round(rnd() * 55)) + '%'; f.style.animationDelay = (rnd() * 6).toFixed(1) + 's'; f.style.animationDuration = (5 + rnd() * 6).toFixed(1) + 's'; sec.append(f); }
  sec.append(content);
  // Maus bewegt die Ebenen leicht (Tiefe)
  sec.addEventListener('pointermove', e => { if (!S.fx) return; const r = sec.getBoundingClientRect(); sec.style.setProperty('--mx', ((e.clientX - r.left) / r.width - .5).toFixed(3)); });
  const fit = () => { const hh = sec.clientHeight || 380; sec.style.setProperty('--tw', Math.round(hh * 1.4 / H * W) + 'px'); };
  requestAnimationFrame(fit);
  return sec;
}
function onScroll() {
  const y = Math.min(scrollY, 600);
  for (const [el, f] of parallax) el.style.transform = S.fx ? 'translateY(' + Math.round(y * f) + 'px)' : '';
}
addEventListener('scroll', onScroll, { passive: true });

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
        toast(d.by + ' ' + t('changedBy') + ' ' + (m ? modTitle(m) : d.module));
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
  const resize = () => { c.width = innerWidth; c.height = innerHeight; pts = Array.from({ length: Math.round(innerWidth * innerHeight / 26000) }, () => ({ x: Math.random() * c.width, y: Math.random() * c.height, s: 3 + Math.floor(Math.random() * 3) * 3, v: .1 + Math.random() * .35, hue: Math.random() < .5 ? 0 : 1 })); };
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
  $('#b-lang').onclick = () => setLang(LANGS[(LANGS.findIndex(l => l[0] === S.lang) + 1) % LANGS.length][0]);
  $('#b-theme').onclick = () => { S.theme = THEMES[(THEMES.indexOf(S.theme) + 1) % THEMES.length]; store.set('theme', S.theme); applyPrefs(); if (S.current === 'home') go('home'); };
  $('#b-fx').onclick = () => { S.fx = !S.fx; store.set('fx', S.fx ? '1' : '0'); applyPrefs(); };
  $('#b-snd').onclick = () => { S.snd = !S.snd; store.set('snd', S.snd ? '1' : '0'); applyPrefs(); if (S.snd) beep(660); };
  $('#b-out').onclick = async () => { try { await api('/api/logout', { method: 'POST' }); } catch { /* egal */ } needLogin(); };
  try {
    S.session = await api('/api/session');
  } catch { return; }
  S.me = S.session.name; S.modules = S.session.modules;
  $('#ver').textContent = 'PeakCore ' + S.session.version;
  for (const m of S.modules) S.revs[m.id] = m.rev;
  applyPrefs();
  connectLive();
  const start = location.hash.slice(1);
  await go(['home', 'server', 'modules', 'log'].includes(start) || S.modules.some(m => m.id === start) ? start : 'home');
}
boot();
