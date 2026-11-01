// Seite "Pack": Status, Testmodus, Pack-Bibliothek (mehrere Packs, eines ist das Server-Pack), Versionen, Dateiliste mit Vorschau.
export default async function mount(root, ctx) {
  const { h, t } = ctx;
  const L = (de, en) => t({ de, en });
  const kb = n => n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : n > 1024 ? (n / 1024).toFixed(1) + ' KB' : n + ' B';
  const sh = s => s ? s.slice(0, 8) : '-';
  let status, files = [], filter = '', selected = null, variant = 'standard';

  async function act(path, body, ok) {
    try { status = await ctx.api(path, { method: 'POST', body: body ?? '' }); ctx.toast(ok, 'good'); render(); if (path === 'rebuild' || path === 'base' || path === 'rollback' || path === 'publish') loadFiles(); }
    catch (e) { if (e.message) ctx.toast(e.message, 'bad'); }
  }

  async function loadFiles() {
    try { files = await ctx.api('files?variant=' + variant); } catch { files = []; }
    render();
  }

  function preview() {
    if (!selected) return h('div', { class: 'empty' }, L('Datei links auswaehlen', 'Select a file on the left'));
    const url = '/api/pack/file?variant=' + variant + '&path=' + encodeURIComponent(selected) + '&v=' + sh(status.variants.find(v => v.variant === variant)?.live);
    if (selected.endsWith('.png')) return h('div', {}, h('img', { src: url, style: 'image-rendering:pixelated;max-width:100%;min-width:128px;background:repeating-conic-gradient(var(--panel2) 0 25%,var(--line) 0 50%) 0 0/16px 16px' }));
    if (selected.endsWith('.ogg')) return h('audio', { src: url, controls: true });
    const box = h('pre', { style: 'white-space:pre-wrap;word-break:break-all;max-height:420px;overflow:auto' }, '...');
    fetch(url, { credentials: 'same-origin' }).then(r => r.text()).then(x => { box.textContent = x.slice(0, 20000); });
    return box;
  }

  function render() {
    if (!status) return;
    const v = status.variants.find(x => x.variant === variant);
    const shown = files.filter(f => f.path.toLowerCase().includes(filter.toLowerCase())).slice(0, 400);
    const set = (...kids) => root.replaceChildren(...kids.filter(k => k != null && k !== false));
    set(
      h('h1', {}, 'Pack'),
      h('p', { class: 'sub' }, L('Das Resource-Pack, das der Server an die Spieler schickt. Aenderungen koennen erst nur Testern gezeigt werden.', 'The resource pack the server sends to players. Changes can be shown to testers first.')),
      h('div', { class: 'card' },
        h('div', { class: 'row' },
          h('span', { class: 'tag ' + (status.serverRunning ? 'good' : 'warn') }, status.serverRunning ? L('Webserver laeuft', 'Web server running') : L('Webserver aus', 'Web server off')),
          h('span', { class: 'tag ' + (status.sending ? 'good' : '') }, status.sending ? L('Senden an', 'Sending on') : L('Senden aus', 'Sending off')),
          h('span', { class: 'tag ' + (status.testMode ? 'warn' : '') }, status.testMode ? L('Testmodus AN', 'Test mode ON') : L('Testmodus aus', 'Test mode off')),
          h('span', { class: 'sub' }, status.url || L('keine Adresse (pack.public-url)', 'no address (pack.public-url)'))),
        h('div', { class: 'row', style: 'margin-top:12px' },
          h('button', { class: 'px-btn primary', onclick: () => act('rebuild', '', L('Pack gebaut', 'Pack built')) }, L('Pack neu bauen', 'Rebuild pack')),
          h('button', { class: 'px-btn', disabled: !status.variants.some(x => x.test), onclick: () => act('publish', '', L('Fuer alle veroeffentlicht', 'Published to everyone')) }, L('Test-Pack fuer alle freigeben', 'Publish test pack')),
          h('button', { class: 'px-btn', onclick: () => act('testmode', { on: !status.testMode }, status.testMode ? L('Testmodus aus', 'Test mode off') : L('Testmodus an', 'Test mode on')) }, status.testMode ? L('Testmodus ausschalten', 'Disable test mode') : L('Testmodus einschalten', 'Enable test mode'))),
        status.problems.length ? h('div', { style: 'margin-top:12px' }, status.problems.map(p => h('div', { class: 'tag bad', style: 'display:block;margin:4px 0' }, p))) : null),
      h('div', { class: 'grid' }, status.variants.map(x => h('div', { class: 'card', style: 'margin:0' },
        h('b', {}, x.variant), h('div', { class: 'meta sub' }, 'Live: ' + sh(x.live) + (x.test ? '  Test: ' + sh(x.test) : '')),
        h('div', { class: 'sub' }, x.files ? x.files + ' ' + L('Dateien', 'files') + ', ' + kb(x.size) : '-')))),
      status.testMode ? h('div', { class: 'card' }, h('h2', {}, L('Tester', 'Testers')),
        status.testers.map(n => h('span', { class: 'pill' }, n + ' ', h('a', { style: 'cursor:pointer', onclick: () => act('tester', { name: n, add: false }, n + ' ' + L('entfernt', 'removed')) }, '×'))),
        status.testers.length ? null : h('span', { class: 'sub' }, L('Noch keine Tester.', 'No testers yet.')),
        h('div', { class: 'row', style: 'margin-top:10px' }, (() => {
          const input = h('input', { placeholder: L('Spielername', 'Player name'), class: 'grow' });
          return [input, h('button', { class: 'px-btn', onclick: () => input.value && act('tester', { name: input.value.trim(), add: true }, input.value + ' ' + L('ist Tester', 'is tester')) }, L('Hinzufuegen', 'Add'))];
        })())) : null,
      libEl,
      h('div', { class: 'card' }, h('h2', {}, L('Versionen', 'Versions') + ' (' + variant + ')'),
        h('div', { class: 'row' }, ['standard', 'lite'].map(x => h('button', { class: 'px-btn small ' + (x === variant ? 'active' : ''), onclick: () => { variant = x; selected = null; loadFiles(); } }, x))),
        h('table', {}, h('tr', {}, h('th', {}, '#'), h('th', {}, 'SHA1'), h('th', {}, L('Groesse', 'Size')), h('th', {}, L('Gebaut', 'Built')), h('th', {})),
          (v?.versions || []).map(e => h('tr', {}, h('td', {}, e.n), h('td', {}, sh(e.sha1) + (e.sha1 === v.live ? ' LIVE' : '') + (e.sha1 === v.test ? ' TEST' : '')), h('td', {}, kb(e.size)), h('td', {}, e.builtAt),
            h('td', {}, e.sha1 !== v.live && e.available ? h('button', { class: 'px-btn small', onclick: () => act('rollback', { variant, n: e.n }, L('Zurueckgerollt', 'Rolled back')) }, L('Zurueckrollen', 'Roll back')) : null))))),
      h('div', { class: 'card' }, h('h2', {}, L('Dateien im Live-Pack', 'Files in the live pack') + ' (' + files.length + ')'),
        h('input', { placeholder: L('Filtern...', 'Filter...'), value: filter, oninput: e => { filter = e.target.value; const pos = e.target.selectionStart; render(); const i = root.querySelector('input[placeholder]'); i.focus(); i.setSelectionRange(pos, pos); } }),
        h('div', { class: 'px-edit', style: 'margin-top:12px' },
          h('div', { style: 'max-height:420px;overflow:auto;min-width:260px' }, shown.map(f => h('div', { class: 'tile' + (f.path === selected ? ' sel' : ''), style: 'padding:4px 8px;margin-bottom:4px', onclick: () => { selected = f.path; render(); } },
            h('span', { style: 'word-break:break-all;font-size:13px' }, f.path), ' ', h('span', { class: 'meta' }, kb(f.size))))),
          h('div', {}, preview()))));
  }

  async function act2(path, method) {
    try { status = await ctx.api(path, { method }); ctx.toast(L('Erledigt', 'Done'), 'good'); render(); loadFiles(); } catch (e) { ctx.toast(e.message, 'bad'); }
  }

  // ---------- Pack-Bibliothek ----------
  // Der Bereich baut sich einmal auf und bleibt stehen (Upload-Fortschritt, geoeffnete Karten); render() haengt ihn nur wieder ein.
  let lib = null, uploading = null, editId = '', conflicts = {};
  const libEl = h('div', { class: 'card' });
  const dateOf = ms => new Date(ms).toLocaleString();
  const spanOf = (txt, cls) => h('span', { class: 'tag ' + (cls || '') }, txt);
  async function loadLib() { try { lib = await ctx.api('library'); } catch { lib = null; } drawLib(); }

  async function upload(file) {
    if (uploading) return;
    if (!/\.zip$/i.test(file.name)) { ctx.toast(L('Bitte eine ZIP-Datei waehlen.', 'Please choose a ZIP file.'), 'bad'); return; }
    uploading = { name: file.name, done: 0, total: file.size };
    drawLib();
    let id = null;
    try {
      const begin = await ctx.api('library/begin', { method: 'POST', body: { name: file.name, size: file.size } });
      id = begin.id;
      for (let off = 0, n = 0; off < file.size; off += begin.chunk, n++) {
        const end = Math.min(file.size, off + begin.chunk);
        await ctx.api('library/chunk?id=' + encodeURIComponent(id) + '&n=' + n, { method: 'POST', body: await file.slice(off, end).arrayBuffer() });
        uploading.done = end;
        drawLib();
      }
      lib = await ctx.api('library/finish', { method: 'POST', body: { id } });
      id = null;
      ctx.toast(L('Pack hochgeladen und geprueft', 'Pack uploaded and checked'), 'good');
    } catch (e) {
      ctx.toast(e.message, 'bad');
      if (id) { try { await ctx.api('library/abort', { method: 'POST', body: { id } }); } catch { /* egal */ } }
    }
    uploading = null;
    drawLib();
  }

  async function activate(p) {
    if (!(await ctx.confirm(L('„' + p.name + '“ zum Server-Pack machen? Es wird sofort an alle Spieler gesendet, die gerade online sind.', 'Make "' + p.name + '" the server pack? It is sent to everyone online right now.')))) return;
    try {
      lib = await ctx.api('library/' + p.id + '/activate', { method: 'POST', body: '' });
      status = await ctx.api('status');
      ctx.toast(L('Server-Pack gewechselt', 'Server pack switched'), 'good');
      render(); loadFiles(); drawLib();
    } catch (e) { ctx.toast(e.message, 'bad'); }
  }

  function card(p) {
    const active = lib.active === p.id;
    const formatWarn = p.format >= 0 && lib.serverFormat > 0 && p.format !== lib.serverFormat;
    const name = h('input', { value: p.name, maxlength: 60, style: 'width:100%' }), note = h('input', { value: p.note || '', maxlength: 300, placeholder: L('Notiz (optional)', 'Note (optional)'), style: 'width:100%' });
    return h('div', { class: 'pbl-card' + (active ? ' on' : '') },
      h('div', { class: 'pbl-top' },
        p.icon ? h('img', { class: 'pbl-icon', src: '/api/pack/library/' + p.id + '/icon', alt: '' }) : h('div', { class: 'pbl-icon none' }, '▦'),
        h('div', { style: 'min-width:0;flex:1' },
          editId === p.id ? [name, note, h('div', { class: 'row', style: 'margin-top:6px' },
            h('button', { class: 'px-btn small primary', onclick: async () => { try { lib = await ctx.api('library/' + p.id + '/rename', { method: 'POST', body: { name: name.value, note: note.value } }); editId = ''; drawLib(); } catch (e) { ctx.toast(e.message, 'bad'); } } }, L('Speichern', 'Save')),
            h('button', { class: 'px-btn small ghost', onclick: () => { editId = ''; drawLib(); } }, L('Abbrechen', 'Cancel')))]
            : [h('b', { class: 'pbl-name' }, p.name), p.note ? h('div', { class: 'sub' }, p.note) : null])),
      h('div', { class: 'row', style: 'margin:6px 0' }, active ? spanOf(L('Server-Pack (aktiv)', 'Server pack (active)'), 'good') : null,
        spanOf(kb(p.size)), spanOf(p.files + ' ' + L('Dateien', 'files')), p.textures ? spanOf(p.textures + ' ' + L('Texturen', 'textures')) : null, p.sounds ? spanOf(p.sounds + ' ' + L('Sounds', 'sounds')) : null,
        p.format >= 0 ? spanOf('Format ' + p.format, formatWarn ? 'warn' : '') : null, p.repaired ? spanOf(L('repariert', 'repaired'), 'warn') : null),
      p.description ? h('div', { class: 'sub', style: 'margin-bottom:4px' }, p.description) : null,
      formatWarn ? h('div', { class: 'tag warn', style: 'display:block;margin:4px 0' }, L('Gedacht fuer Format ' + p.format + ', der Server meldet ' + lib.serverFormat + '. Minecraft warnt dann evtl., die Texturen laufen meist trotzdem.', 'Made for format ' + p.format + ', the server reports ' + lib.serverFormat + '. Minecraft may warn, textures mostly work anyway.')) : null,
      p.warnings && p.warnings.length ? h('details', {}, h('summary', {}, p.warnings.length + ' ' + L('Hinweis(e) der Pruefung', 'check note(s)')), p.warnings.map(w => h('div', { class: 'sub' }, '• ' + w))) : null,
      conflicts[p.id] ? h('div', { style: 'margin-top:6px' }, conflicts[p.id].length
        ? [h('div', { class: 'tag warn', style: 'display:block' }, L(conflicts[p.id].length + ' Datei(en) kommen auch von einem Modul (z. B. TexturePeak). Die Textur des Moduls gewinnt, damit die Gegenstaende funktionieren:', conflicts[p.id].length + ' file(s) also come from a module (e.g. TexturePeak). The module texture wins so items keep working:')),
          h('div', { style: 'max-height:140px;overflow:auto;font-size:12px' }, conflicts[p.id].slice(0, 100).map(c => h('div', {}, c.path + '  ', h('span', { class: 'sub' }, '(' + c.owner + ')'))))]
        : h('div', { class: 'tag good', style: 'display:block' }, L('Keine Ueberschneidung mit den Modulen.', 'No overlap with the modules.'))) : null,
      h('div', { class: 'row', style: 'margin-top:8px' },
        h('button', { class: 'px-btn small primary', disabled: active || !!uploading, onclick: () => activate(p) }, L('Als Server-Pack', 'Use as server pack')),
        h('button', { class: 'px-btn small', onclick: () => { editId = p.id; drawLib(); } }, L('Umbenennen', 'Rename')),
        h('button', { class: 'px-btn small', onclick: async () => { try { conflicts[p.id] = await ctx.api('library/' + p.id + '/conflicts'); drawLib(); } catch (e) { ctx.toast(e.message, 'bad'); } } }, L('Konflikte pruefen', 'Check conflicts')),
        h('a', { class: 'px-btn small', href: '/api/pack/library/' + p.id + '/download', download: p.file || (p.name + '.zip') }, L('Herunterladen', 'Download')),
        h('button', { class: 'px-btn small danger', disabled: active, title: active ? L('Das aktive Pack kann nicht geloescht werden', 'The active pack cannot be deleted') : '', onclick: async () => {
          if (!(await ctx.confirm(L('„' + p.name + '“ aus der Bibliothek loeschen?', 'Delete "' + p.name + '" from the library?')))) return;
          try { lib = await ctx.api('library/' + p.id, { method: 'DELETE' }); delete conflicts[p.id]; drawLib(); } catch (e) { ctx.toast(e.message, 'bad'); }
        } }, L('Loeschen', 'Delete'))));
  }

  function drawLib() {
    if (!lib) { libEl.replaceChildren(h('h2', {}, L('Pack-Bibliothek', 'Pack library')), h('div', { class: 'sub' }, '…')); return; }
    const pct = uploading ? Math.round(uploading.done / uploading.total * 100) : 0;
    libEl.replaceChildren(
      h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, L('Pack-Bibliothek', 'Pack library')), h('span', { class: 'tag' }, lib.packs.length + '/' + lib.maxPacks), h('span', { class: 'grow' }),
        status.hasBase ? h('button', { class: 'px-btn small danger', onclick: async () => { if (await ctx.confirm(L('Das Server-Pack wieder abschalten? Die Packs bleiben in der Bibliothek.', 'Switch the server pack off? The packs stay in the library.'))) { await act2('base', 'DELETE'); loadLib(); } } }, L('Kein Basis-Pack verwenden', 'Use no base pack')) : null),
      h('p', { class: 'sub' }, L('Lade fertige Resource-Packs (ZIP, bis ' + lib.maxMb + ' MB) hoch und waehle eines als Server-Pack. Die Texturen von TexturePeak und PeakSound werden darueber gemischt; bei gleichen Dateien gewinnt das Modul. Beim Wechsel bekommen alle Spieler online das neue Pack sofort.',
        'Upload finished resource packs (ZIP, up to ' + lib.maxMb + ' MB) and pick one as the server pack. TexturePeak and PeakSound textures are merged on top; where files overlap the module wins. On a switch everyone online gets the new pack right away.')),
      uploading
        ? h('div', { class: 'pbl-up' }, h('div', {}, L('Lade hoch: ', 'Uploading: ') + uploading.name + ' (' + pct + ' %)'), h('div', { class: 'pbl-bar' }, h('i', { style: 'width:' + pct + '%' })),
          h('div', { class: 'sub' }, L('Bitte diese Seite offen lassen. Danach wird das Pack geprueft.', 'Please keep this page open. The pack is checked afterwards.')))
        : h('div', { class: 'pbl-drop', ondragover: e => { e.preventDefault(); e.currentTarget.classList.add('over'); }, ondragleave: e => e.currentTarget.classList.remove('over'),
          ondrop: e => { e.preventDefault(); e.currentTarget.classList.remove('over'); const f = e.dataTransfer.files[0]; if (f) upload(f); } },
          h('button', { class: 'px-btn primary', onclick: () => picker.click() }, '+ ' + L('Pack hochladen', 'Upload pack')), h('span', { class: 'sub' }, L('oder ZIP hierher ziehen', 'or drop a ZIP here'))),
      lib.packs.length ? h('div', { class: 'pbl-grid' }, lib.packs.slice().sort((a, b) => (lib.active === b.id) - (lib.active === a.id) || b.added - a.added).map(card)) : h('div', { class: 'empty', style: 'margin-top:10px' }, L('Noch kein Pack in der Bibliothek.', 'No pack in the library yet.')));
  }

  if (!document.getElementById('pbl-style')) {
    const s = document.createElement('style'); s.id = 'pbl-style';
    s.textContent = '.pbl-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:14px;margin-top:14px}.pbl-card{background:var(--panel2);border:2px solid var(--line);padding:12px;transition:border-color .15s,transform .15s}.pbl-card:hover{transform:translateY(-2px);border-color:var(--accent)}.pbl-card.on{border-color:var(--good);box-shadow:0 0 0 2px var(--good) inset}'
      + '.pbl-top{display:flex;gap:12px;align-items:center}.pbl-icon{width:64px;height:64px;image-rendering:pixelated;border:2px solid var(--line);background:var(--panel);flex:none;object-fit:cover}.pbl-icon.none{display:flex;align-items:center;justify-content:center;font-size:28px;color:var(--dim)}.pbl-name{font-size:16px;word-break:break-word}'
      + '.pbl-drop{display:flex;gap:12px;align-items:center;margin-top:10px;padding:16px;border:2px dashed var(--line);transition:border-color .15s,background .15s}.pbl-drop.over{border-color:var(--accent);background:var(--panel2)}.pbl-up{margin-top:10px;padding:12px;border:2px solid var(--accent)}.pbl-bar{height:14px;background:var(--panel);margin:8px 0}.pbl-bar i{display:block;height:100%;background:var(--accent);transition:width .3s}';
    document.head.appendChild(s);
  }

  const picker = h('input', { type: 'file', accept: '.zip', hidden: true, onchange: e => { const f = e.target.files[0]; picker.value = ''; if (f) upload(f); } });  root.append(picker);

  ctx.on(async d => { if (d.module === 'pack' && !uploading) { try { status = await ctx.api('status'); } catch { return; } render(); loadFiles(); loadLib(); } });
  status = await ctx.api('status');
  render();
  loadFiles();
  loadLib();
}
