// Seite "Fehler": Warnungen und Fehler von PeakVerse und Pack-Probleme der Spieler, gleiche Meldungen zusammengefasst.
export default async function mount(root, ctx) {
  const { h, t } = ctx;
  const L = (de, en) => t({ de, en });
  let list = [], source = '', level = '', open = new Set();

  const ago = ms => {
    const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return L('vor ' + s + ' s', s + ' s ago');
    if (s < 3600) return L('vor ' + Math.round(s / 60) + ' Min', Math.round(s / 60) + ' min ago');
    if (s < 86400) return L('vor ' + Math.round(s / 3600) + ' Std', Math.round(s / 3600) + ' h ago');
    return L('vor ' + Math.round(s / 86400) + ' Tagen', Math.round(s / 86400) + ' d ago');
  };

  async function call(path, ok) {
    try { list = await ctx.api(path, { method: 'POST', body: '' }); if (ok) ctx.toast(ok, 'good'); render(); }
    catch (e) { if (e.message) ctx.toast(e.message, 'bad'); }
  }

  function row(e) {
    const isOpen = open.has(e.id);
    return h('div', { class: 'card', style: 'margin:0 0 10px' },
      h('div', { class: 'row' },
        h('span', { class: 'tag ' + (e.level === 'ERROR' ? 'bad' : 'warn') }, e.level === 'ERROR' ? L('Fehler', 'Error') : L('Warnung', 'Warning')),
        h('span', { class: 'tag' }, e.source),
        h('b', {}, e.count + '×'),
        h('span', { class: 'sub' }, L('zuletzt ', 'last ') + ago(e.last) + ' · ' + L('erstmals ', 'first ') + ago(e.first)),
        h('span', { class: 'grow' }),
        h('button', { class: 'px-btn small', onclick: () => resolve(e.id) }, L('Erledigt', 'Done'))),
      h('div', { style: 'margin:8px 0;word-break:break-word' }, e.message),
      e.players.length ? h('div', { class: 'sub' }, L('Betroffene Spieler: ', 'Affected players: ') + e.players.join(', ')) : null,
      e.detail ? h('div', {},
        h('a', { style: 'cursor:pointer', onclick: () => { isOpen ? open.delete(e.id) : open.add(e.id); render(); } }, isOpen ? L('Details ausblenden', 'Hide details') : L('Details anzeigen', 'Show details')),
        isOpen ? h('pre', { style: 'white-space:pre-wrap;word-break:break-all;max-height:300px;overflow:auto;font-size:12px' }, e.detail) : null) : null);
  }

  async function resolve(id) {
    try { list = await ctx.api('resolve', { method: 'POST', body: { id } }); ctx.toast(L('Erledigt', 'Done'), 'good'); render(); }
    catch (e) { if (e.message) ctx.toast(e.message, 'bad'); }
  }

  function render() {
    const sources = [...new Set(list.map(e => e.source))].sort();
    const shown = list.filter(e => (!source || e.source === source) && (!level || e.level === level));
    root.replaceChildren(
      h('h1', {}, L('Fehler', 'Errors')),
      h('p', { class: 'sub' }, L('Warnungen und Fehler von PeakVerse und Probleme, die Spieler mit dem Server-Pack haben. Gleiche Meldungen sind zusammengefasst.', 'Warnings and errors from PeakVerse and problems players have with the server pack. Identical messages are grouped.')),
      h('div', { class: 'row', style: 'margin-bottom:12px' },
        h('button', { class: 'px-btn small' + (!source ? ' active' : ''), onclick: () => { source = ''; render(); } }, L('Alle', 'All')),
        sources.map(s => h('button', { class: 'px-btn small' + (source === s ? ' active' : ''), onclick: () => { source = s; render(); } }, s)),
        h('span', { class: 'grow' }),
        ['', 'ERROR', 'WARN'].map(l => h('button', { class: 'px-btn small' + (level === l ? ' active' : ''), onclick: () => { level = l; render(); } }, l === '' ? L('Alle Stufen', 'All levels') : l === 'ERROR' ? L('Fehler', 'Errors') : L('Warnungen', 'Warnings'))),
        h('button', { class: 'px-btn small danger', disabled: !list.length, onclick: async () => { if (await ctx.confirm(L('Alle Meldungen löschen?', 'Delete all messages?'))) call('clear', L('Liste geleert', 'List cleared')); } }, L('Alles leeren', 'Clear all'))),
      ...(shown.length ? shown.map(row)
        : [h('div', { class: 'empty' }, list.length ? L('Keine Meldungen mit diesem Filter.', 'No messages with this filter.') : L('Keine Fehler. Alles ruhig.', 'No errors. All quiet.'))]));
  }

  ctx.on(async d => { if (d.module === 'errors') { try { list = await ctx.api('list'); } catch { return; } render(); } });
  list = await ctx.api('list');
  render();
}
