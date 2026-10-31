// Seite "Bereiche": eigene Pack-Dateien fuer Sounds, Texte, Schriften, Partikel, HUD, animierte Texturen, Himmel und Titelbild.
// Die Dateien liegen in plugins/PeakCore/pack-files/ und gewinnen im Pack gegen das Basis-Pack.
export default async function mount(root, ctx) {
  const { h } = ctx;
  const L = (de, en) => ctx.t({ de, en });
  const AREAS = [
    { id: 'sounds', de: 'Sounds', en: 'Sounds', dir: 'assets/minecraft/sounds/', hint: L('OGG-Dateien (Vorbis, mono). Damit sie im Spiel erscheinen, braucht der Namensraum eine sounds.json (Vorlage unten).', 'OGG files (Vorbis, mono). To appear in game the namespace needs a sounds.json (template below).'),
      templates: [{ path: 'assets/minecraft/sounds.json', text: '{\n  "mein_sound": {\n    "sounds": ["minecraft:mein_sound"]\n  }\n}\n' }] },
    { id: 'text', de: 'Texte', en: 'Texts', dir: 'assets/minecraft/lang/', hint: L('Sprachdateien (JSON), z. B. de_de.json: "schluessel": "Text". Ueberschreibt Namen und Menue-Texte.', 'Language files (JSON), e.g. de_de.json: "key": "text". Overrides names and menu texts.'),
      templates: [{ path: 'assets/minecraft/lang/de_de.json', text: '{\n  "menu.singleplayer": "Einzelspieler"\n}\n' }] },
    { id: 'fonts', de: 'Schriften', en: 'Fonts', dir: 'assets/minecraft/font/', hint: L('Schrift-Definitionen (JSON) in font/ und Zeichen-Bilder in textures/font/.', 'Font definitions (JSON) in font/ and glyph images in textures/font/.'),
      templates: [{ path: 'assets/minecraft/font/default.json', text: '{\n  "providers": []\n}\n' }] },
    { id: 'particles', de: 'Partikel', en: 'Particles', dir: 'assets/minecraft/particles/', hint: L('Partikel-Definitionen (JSON) in particles/, Bilder in textures/particle/.', 'Particle definitions (JSON) in particles/, images in textures/particle/.'), templates: [] },
    { id: 'hud', de: 'HUD', en: 'HUD', dir: 'assets/minecraft/textures/gui/sprites/hud/', hint: L('Herzen, Hunger, Hotbar und andere Oberflaechen-Bilder (PNG) unter textures/gui/sprites/.', 'Hearts, hunger, hotbar and other interface images (PNG) under textures/gui/sprites/.'), templates: [] },
    { id: 'anim', de: 'Animierte Texturen', en: 'Animated textures', dir: 'assets/minecraft/textures/block/', hint: L('Ein hohes PNG (Bilder untereinander) plus gleichnamige .png.mcmeta mit "animation". Vorlage unten.', 'A tall PNG (frames stacked) plus a same-named .png.mcmeta with "animation". Template below.'),
      templates: [{ path: 'assets/minecraft/textures/block/beispiel.png.mcmeta', text: '{\n  "animation": {\n    "frametime": 4\n  }\n}\n' }] },
    { id: 'models', de: 'Modelle (3D)', en: 'Models (3D)', dir: 'assets/minecraft/models/item/', hint: L('Block- und Item-Modelle (JSON), z. B. aus Blockbench: Datei > Exportieren > Java Block/Item Model. Die Texturen dazu als PNG unter textures/ hochladen. Vorschau mit Drehen per Maus.', 'Block and item models (JSON), e.g. from Blockbench: File > Export > Java Block/Item Model. Upload the textures as PNG under textures/. Preview rotates with the mouse.'), templates: [] },
    { id: 'sky', de: 'Himmel', en: 'Sky', dir: 'assets/minecraft/textures/environment/', hint: L('Sonne, Mond, Wolken (PNG) in textures/environment/.', 'Sun, moon, clouds (PNG) in textures/environment/.'), templates: [] },
    { id: 'title', de: 'Titelbild', en: 'Title screen', dir: 'assets/minecraft/textures/gui/title/', hint: L('Logo und Hintergrund-Panorama (panorama_0.png bis panorama_5.png) unter textures/gui/title/.', 'Logo and background panorama (panorama_0.png to panorama_5.png) under textures/gui/title/.'), templates: [] }
  ];
  let area = AREAS[0], files = [], editing = null, preview = null;
  const kb = n => n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : n > 1024 ? (n / 1024).toFixed(1) + ' KB' : n + ' B';
  const isText = p => /\.(json|mcmeta|txt|lang)$/.test(p);
  const reload = async () => { files = await ctx.api('custom'); };
  const url = p => '/api/areas/custom/' + p;

  async function put(path, body, ok) {
    try { await ctx.api('custom/' + path, { method: 'PUT', body }); ctx.toast(ok || L('Gespeichert', 'Saved'), 'good'); await reload(); render(); return true; }
    catch (e) { if (e.message) ctx.toast(e.message, 'bad'); return false; }
  }

  // ---------- 3D-Vorschau (Block-/Item-Modelle) ----------
  // Zeichnet die Quader des Modells mit ihren Texturen (orthogonal, per Maus drehbar). Nicht dargestellt: Drehung einzelner Quader ("rotation"),
  // Licht- und Anzeige-Einstellungen ("display"), Overrides. Das Modell im Spiel sieht darum an diesen Stellen anders aus.
  const FACES = {
    north: { n: [0, 0, -1], v: (a, b) => [[b[0], b[1], a[2]], [a[0], b[1], a[2]], [a[0], a[1], a[2]], [b[0], a[1], a[2]]] },
    south: { n: [0, 0, 1], v: (a, b) => [[a[0], b[1], b[2]], [b[0], b[1], b[2]], [b[0], a[1], b[2]], [a[0], a[1], b[2]]] },
    east: { n: [1, 0, 0], v: (a, b) => [[b[0], b[1], b[2]], [b[0], b[1], a[2]], [b[0], a[1], a[2]], [b[0], a[1], b[2]]] },
    west: { n: [-1, 0, 0], v: (a, b) => [[a[0], b[1], a[2]], [a[0], b[1], b[2]], [a[0], a[1], b[2]], [a[0], a[1], a[2]]] },
    up: { n: [0, 1, 0], v: (a, b) => [[a[0], b[1], a[2]], [b[0], b[1], a[2]], [b[0], b[1], b[2]], [a[0], b[1], b[2]]] },
    down: { n: [0, -1, 0], v: (a, b) => [[a[0], a[1], b[2]], [b[0], a[1], b[2]], [b[0], a[1], a[2]], [a[0], a[1], a[2]]] }
  };
  const SHADE = { up: 1, down: .5, north: .8, south: .8, east: .65, west: .65 };

  function textureSource(ref) {
    const r = ref.includes(':') ? ref : 'minecraft:' + ref;
    const [ns, p] = r.split(':');
    const path = 'assets/' + ns + '/textures/' + p + '.png';
    return files.some(f => f.path === path) ? '/api/areas/custom/' + path : '/api/pack/file?variant=standard&path=' + encodeURIComponent(path);
  }

  function tri(g, img, s, d) {
    const [[sx0, sy0], [sx1, sy1], [sx2, sy2]] = s, [[dx0, dy0], [dx1, dy1], [dx2, dy2]] = d;
    const den = sx0 * (sy2 - sy1) - sx1 * sy2 + sx2 * sy1 + (sx1 - sx2) * sy0;
    if (!den) return;
    const a = -(sy0 * (dx2 - dx1) - sy1 * dx2 + sy2 * dx1 + (sy1 - sy2) * dx0) / den, b = (sy1 * dy2 + sy0 * (dy1 - dy2) - sy2 * dy1 + (sy2 - sy1) * dy0) / den;
    const c = (sx0 * (dx2 - dx1) - sx1 * dx2 + sx2 * dx1 + (sx1 - sx2) * dx0) / den, dd = -(sx1 * dy2 + sx0 * (dy1 - dy2) - sx2 * dy1 + (sx2 - sx1) * dy0) / den;
    const e = (sx0 * (sy2 * dx1 - sy1 * dx2) + sy0 * (sx1 * dx2 - sx2 * dx1) + (sx2 * sy1 - sx1 * sy2) * dx0) / den, f = (sx0 * (sy2 * dy1 - sy1 * dy2) + sy0 * (sx1 * dy2 - sx2 * dy1) + (sx2 * sy1 - sx1 * sy2) * dy0) / den;
    g.save(); g.beginPath(); g.moveTo(dx0, dy0); g.lineTo(dx1, dy1); g.lineTo(dx2, dy2); g.closePath(); g.clip();
    g.transform(a, b, c, dd, e, f); g.drawImage(img, 0, 0); g.restore();
  }

  function modelView(canvas, model) {
    const g = canvas.getContext('2d'), W = canvas.width, S = W / 26;
    const images = {};
    for (const [k, v] of Object.entries(model.textures || {})) { if (typeof v !== 'string' || v.startsWith('#')) continue; const im = new Image(); im.src = textureSource(v); images[k] = im; }
    let yaw = .7, pitch = -.5, drag = null, alive = true;
    const imgFor = t => { let k = t; for (let i = 0; i < 6 && typeof k === 'string' && k.startsWith('#'); i++) { const next = (model.textures || {})[k.slice(1)]; if (typeof next === 'string' && next.startsWith('#')) { k = next; continue; } return images[k.slice(1)] || null; } return null; };
    const rot = p => { const x = p[0] - 8, y = p[1] - 8, z = p[2] - 8; const cx = Math.cos(yaw), sx = Math.sin(yaw), cy = Math.cos(pitch), sy = Math.sin(pitch); const x1 = x * cx + z * sx, z1 = -x * sx + z * cx; return [x1, y * cy - z1 * sy, y * sy + z1 * cy]; };
    const draw = () => {
      if (!alive || !canvas.isConnected) return;
      g.clearRect(0, 0, W, W);
      const quads = [];
      for (const el of model.elements || []) {
        if (!Array.isArray(el.from) || !Array.isArray(el.to)) continue;
        for (const [name, face] of Object.entries(el.faces || {})) {
          const def = FACES[name]; if (!def) continue;
          const n = rot([def.n[0] + 8, def.n[1] + 8, def.n[2] + 8]).map((v, i) => v - rot([8, 8, 8])[i]);
          if (n[2] > 0) continue;                      // Rueckseite (vom Betrachter weg)
          const vs = def.v(el.from, el.to).map(rot);
          quads.push({ vs, z: vs.reduce((s, v) => s + v[2], 0) / 4, name, face });
        }
      }
      quads.sort((p, q) => q.z - p.z);
      for (const q of quads) {
        const pts = q.vs.map(v => [W / 2 + v[0] * S, W / 2 - v[1] * S]);
        const img = imgFor(q.face.texture);
        const uv = q.face.uv || [0, 0, 16, 16];
        if (img && img.complete && img.naturalWidth) {
          const sc = img.naturalWidth / 16, tc = [[uv[0], uv[1]], [uv[2], uv[1]], [uv[2], uv[3]], [uv[0], uv[3]]].map(p => [p[0] * sc, p[1] * sc]);
          tri(g, img, [tc[0], tc[1], tc[2]], [pts[0], pts[1], pts[2]]); tri(g, img, [tc[0], tc[2], tc[3]], [pts[0], pts[2], pts[3]]);
          g.fillStyle = 'rgba(0,0,0,' + (1 - SHADE[q.name]) * .7 + ')'; g.beginPath(); pts.forEach((p, i) => i ? g.lineTo(p[0], p[1]) : g.moveTo(p[0], p[1])); g.closePath(); g.fill();
        } else { g.fillStyle = 'hsl(280 60% ' + 35 * SHADE[q.name] + '%)'; g.beginPath(); pts.forEach((p, i) => i ? g.lineTo(p[0], p[1]) : g.moveTo(p[0], p[1])); g.closePath(); g.fill(); }
        g.strokeStyle = 'rgba(255,255,255,.18)'; g.stroke();
      }
      if (!drag) yaw += .008;
      requestAnimationFrame(draw);
    };
    canvas.onpointerdown = e => { drag = [e.clientX, e.clientY]; canvas.setPointerCapture(e.pointerId); };
    canvas.onpointermove = e => { if (!drag) return; yaw += (e.clientX - drag[0]) * .01; pitch = Math.max(-1.5, Math.min(1.5, pitch + (e.clientY - drag[1]) * .01)); drag = [e.clientX, e.clientY]; };
    canvas.onpointerup = () => { drag = null; };
    Object.values(images).forEach(im => im.addEventListener('load', () => { }));
    requestAnimationFrame(draw);
    return () => { alive = false; };
  }

  let stopView = null;
  function previewCard() {
    stopView?.();
    const canvas = h('canvas', { width: 420, height: 420, style: 'background:var(--bg);border:3px solid var(--line);max-width:100%;cursor:grab;touch-action:none' });
    const warn = [];
    const m = preview.model;
    if (!Array.isArray(m.elements) || !m.elements.length) warn.push(L('Dieses Modell hat keine eigenen Quader (elements); es erbt vielleicht von einem anderen Modell (parent).', 'This model has no cuboids (elements); it may inherit from another model (parent).'));
    if ((m.elements || []).some(e => e.rotation)) warn.push(L('Gedrehte Quader werden in der Vorschau ungedreht gezeichnet.', 'Rotated cuboids are drawn unrotated in the preview.'));
    setTimeout(() => { stopView = modelView(canvas, m); }, 0);
    return h('div', { class: 'card' }, h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, '3D: ' + preview.path), h('button', { class: 'px-btn small ghost', onclick: () => { stopView?.(); preview = null; render(); } }, L('Schliessen', 'Close'))),
      h('div', { style: 'margin-top:12px' }, canvas), warn.map(w => h('div', { class: 'tag warn', style: 'display:block;margin-top:8px' }, w)));
  }

  function editor() {
    const ta = h('textarea', { rows: 16, style: 'font-family:inherit' });
    ta.value = editing.text;
    const path = h('input', { value: editing.path });
    return h('div', { class: 'card' }, h('h2', {}, L('Datei bearbeiten', 'Edit file')), h('label', {}, L('Pfad im Pack', 'Path in pack')), path, h('label', {}, L('Inhalt', 'Content')), ta,
      h('div', { class: 'row', style: 'margin-top:12px' },
        h('button', { class: 'px-btn primary', onclick: async () => { if (await put(path.value.trim(), new TextEncoder().encode(ta.value).buffer)) { editing = null; render(); } } }, L('Speichern und Pack bauen', 'Save and rebuild pack')),
        h('button', { class: 'px-btn', onclick: () => { editing = null; render(); } }, L('Abbrechen', 'Cancel'))));
  }

  function render() {
    const mine = files.filter(f => f.path.startsWith(area.dir) || (area.id === 'anim' && /\.png(\.mcmeta)?$/.test(f.path) && f.path.includes('/textures/') && !f.path.includes('/gui/') && !f.path.includes('/environment/')));
    const set = (...k) => root.replaceChildren(...k.filter(x => x != null && x !== false));
    const pathInput = h('input', { value: area.dir, placeholder: 'assets/minecraft/...' });
    const picker = h('input', { type: 'file', hidden: true, onchange: async e => {
      const f = e.target.files[0]; if (!f) return;
      let p = pathInput.value.trim(); if (p.endsWith('/')) p += f.name.toLowerCase().replace(/[^a-z0-9._-]/g, '_');
      await put(p, await f.arrayBuffer(), L('Hochgeladen, Pack neu gebaut', 'Uploaded, pack rebuilt')); picker.value = '';
    } });
    set(h('h1', {}, L('Bereiche', 'Areas')),
      h('p', { class: 'sub' }, L('Eigene Dateien fuer das Resource-Pack. Jede Aenderung baut das Pack neu (bei eingeschaltetem Testmodus sehen es erst die Tester).', 'Custom files for the resource pack. Every change rebuilds the pack (with test mode on, only testers see it first).')),
      h('div', { class: 'row' }, AREAS.map(a => h('button', { class: 'px-btn small ' + (a === area ? 'active' : ''), onclick: () => { area = a; editing = null; render(); } }, L(a.de, a.en)))),
      preview ? previewCard() : null,
      editing ? editor() : h('div', { class: 'card', style: 'margin-top:14px' }, h('h2', {}, L(area.de, area.en)), h('p', { class: 'sub' }, area.hint),
        h('label', {}, L('Zielpfad (endet er auf /, wird der Dateiname angehaengt)', 'Target path (if it ends with /, the file name is appended)')), pathInput,
        h('div', { class: 'row', style: 'margin-top:10px' }, h('button', { class: 'px-btn primary', onclick: () => picker.click() }, L('Datei hochladen...', 'Upload file...')), picker,
          area.templates.map(t => h('button', { class: 'px-btn', onclick: () => { editing = { path: t.path, text: t.text }; render(); } }, L('Vorlage: ', 'Template: ') + t.path.split('/').pop())))),
      h('div', { class: 'card' }, h('h2', {}, L('Eigene Dateien', 'Custom files') + ' (' + mine.length + ')'),
        mine.length ? h('table', {}, mine.map(f => h('tr', {},
          h('td', {}, f.path.endsWith('.png') ? h('img', { src: url(f.path) + '?v=' + f.size, style: 'width:32px;height:32px;image-rendering:pixelated;vertical-align:middle;margin-right:8px' }) : null, h('span', { style: 'word-break:break-all' }, f.path)),
          h('td', {}, kb(f.size)),
          h('td', {}, /\/models\/.*\.json$/.test(f.path) ? h('button', { class: 'px-btn small', onclick: async () => { preview = { path: f.path, model: JSON.parse(await (await fetch(url(f.path), { credentials: 'same-origin' })).text()) }; render(); } }, '3D') : null, ' ', isText(f.path) ? h('button', { class: 'px-btn small', onclick: async () => { const r = await fetch(url(f.path), { credentials: 'same-origin' }); editing = { path: f.path, text: await r.text() }; render(); } }, L('Bearbeiten', 'Edit')) : null,
            ' ', h('a', { class: 'px-btn small', style: 'text-decoration:none', href: url(f.path), download: f.path.split('/').pop() }, L('Laden', 'Get')),
            ' ', h('button', { class: 'px-btn small danger', onclick: async () => { if (await ctx.confirm(L('Datei loeschen?', 'Delete file?') + ' ' + f.path)) { try { await ctx.api('custom/' + f.path, { method: 'DELETE' }); await reload(); render(); ctx.toast(L('Geloescht', 'Deleted'), 'good'); } catch (e) { ctx.toast(e.message, 'bad'); } } } }, '×'))))) : h('div', { class: 'empty' }, L('Noch keine eigenen Dateien in diesem Bereich.', 'No custom files in this area yet.'))));
  }

  ctx.on(async d => { if (d.module === 'areas' && !editing) { await reload(); render(); } });
  await reload();
  render();
}
