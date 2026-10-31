// Seite "Pack": Status, Testmodus, Basis-Pack, Versionen, Dateiliste mit Vorschau.
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
      h('div', { class: 'card' }, h('h2', {}, L('Basis-Pack', 'Base pack')),
        h('p', { class: 'sub' }, L('Ein fertiges Resource-Pack (ZIP) als Grundlage. Die Texturen der Module werden dazugemischt; bei Konflikten gewinnt das Basis-Pack.', 'A finished resource pack (ZIP) as the foundation. Module textures are merged in; on conflicts the base pack wins.')),
        h('div', { class: 'row' }, status.hasBase ? h('span', { class: 'tag good' }, L('Basis-Pack aktiv', 'Base pack active')) : h('span', { class: 'tag' }, L('keins', 'none')),
          h('button', { class: 'px-btn', onclick: () => picker.click() }, L('ZIP hochladen...', 'Upload ZIP...')),
          status.hasBase ? h('button', { class: 'px-btn danger', onclick: async () => { if (await ctx.confirm(L('Basis-Pack entfernen?', 'Remove base pack?'))) act2('base', 'DELETE'); } }, L('Entfernen', 'Remove')) : null)),
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

  const picker = h('input', { type: 'file', accept: '.zip', hidden: true, onchange: async e => {
    const f = e.target.files[0];
    if (!f) return;
    ctx.toast(L('Wird hochgeladen...', 'Uploading...'));
    try { status = await ctx.api('base', { method: 'POST', body: await f.arrayBuffer() }); ctx.toast(L('Basis-Pack aktiv', 'Base pack active'), 'good'); render(); loadFiles(); }
    catch (err) { ctx.toast(err.message, 'bad'); }
    picker.value = '';
  } });
  root.append(picker);

  ctx.on(async d => { if (d.module === 'pack') { try { status = await ctx.api('status'); } catch { return; } render(); loadFiles(); } });
  status = await ctx.api('status');
  render();
  loadFiles();
}
