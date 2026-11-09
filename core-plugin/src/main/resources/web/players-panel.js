// Seite "Spieler": Spieler, Rollen, Rechte je Modul, Notizen, Verwarnungen, Sperren und Verlauf.
export default async function mount(root, ctx) {
  const { h } = ctx;
  const L = (de, en) => ctx.t({ de, en });
  let meta, players = [], tab = 'players', q = '', filter = 'all', cur = null, detail = null, dtab = 'rights', editRole = null;
  const set = (...kids) => root.replaceChildren(...kids.filter(k => k != null && k !== false));
  const rel = async () => { meta = await ctx.api('meta'); players = await ctx.api('list'); };
  async function call(path, method, body, ok) {
    try { const r = await ctx.api(path, { method, body }); if (ok) ctx.toast(ok, 'good'); return r; }
    catch (e) { if (e.message) ctx.toast(e.message, 'bad'); return null; }
  }
  const roleName = id => { const r = meta.roles.find(x => x.id === id); return r ? ctx.t({ de: r.nameDe, en: r.nameEn }) : ''; };
  const when = t => t ? new Date(t).toLocaleString(ctx.lang) : '–';
  const MOD = { peakcore: 'PeakCore', peaksound: 'PeakSound', texturepeak: 'TexturePeak', peakboard: 'PeakBoard', peakachievements: 'Achievements', peakenchants: 'Enchants', peakadmin: 'Admin', peakwarps: 'Warps' };

  if (!document.getElementById('pl-style')) {
    const st = document.createElement('style'); st.id = 'pl-style';
    st.textContent = `
.pl-wrap{display:grid;grid-template-columns:320px 1fr;gap:16px;align-items:start}
.pl-list{max-height:72vh;overflow:auto}
.pl-row{display:flex;gap:10px;align-items:center;padding:8px 10px;border:2px solid transparent;cursor:pointer;transition:background .12s,border-color .12s,transform .12s}
.pl-row:hover{background:var(--panel2);transform:translateX(3px)}.pl-row.sel{border-color:var(--accent);background:var(--panel2)}
.pl-dot{width:10px;height:10px;background:var(--dim);flex:0 0 auto}.pl-dot.on{background:var(--good);box-shadow:0 0 8px var(--good)}
.pl-name{font-weight:bold}.pl-sub{color:var(--dim);font-size:12px}
.pl-right{display:grid;grid-template-columns:1fr auto;gap:10px;align-items:center;padding:8px 0;border-bottom:1px solid var(--line)}
.pl-seg{display:flex}.pl-seg button{font:inherit;font-size:12px;color:var(--text);background:var(--panel2);border:2px solid var(--line);padding:3px 9px;cursor:pointer;margin-left:-2px}
.pl-seg button.on.allow{background:var(--good);color:#06240f;border-color:var(--good)}.pl-seg button.on.deny{background:var(--bad);color:#2a0606;border-color:var(--bad)}.pl-seg button.on.inherit{background:var(--accent);color:var(--on-accent);border-color:var(--accent)}
.pl-mod{margin-top:16px}.pl-mod h3{margin:0 0 4px;color:var(--accent)}
.pl-chk{display:inline-flex;gap:6px;align-items:center;margin:3px 12px 3px 0;font-size:13px;color:var(--text)}.pl-chk input{width:auto}
@media(max-width:900px){.pl-wrap{grid-template-columns:1fr}}`;
    document.head.appendChild(st);
  }

  // ---------- Spieler ----------
  async function open(p) {
    cur = p.uuid; detail = await call('player/' + p.uuid, 'GET'); render();
  }
  const setDetail = r => { if (r) { detail = r; render(); } };

  function listView() {
    const needle = q.trim().toLowerCase();
    const rows = players.filter(p => (!needle || p.name.toLowerCase().includes(needle)) && (filter === 'all' || (filter === 'online' && p.online) || (filter === 'role' && p.role) || (filter === 'sanction' && p.sanctioned) || (filter === 'op' && p.op)))
      .sort((a, b) => (b.online - a.online) || a.name.localeCompare(b.name)).slice(0, 400);
    const search = h('input', { placeholder: L('Spieler suchen', 'Search players'), value: q, oninput: ev => { q = ev.target.value; render(true); } });
    return h('div', { class: 'card' }, search,
      h('div', { class: 'row', style: 'margin:8px 0' }, [['all', L('Alle', 'All')], ['online', 'Online'], ['role', L('Mit Rolle', 'With role')], ['sanction', L('Gesperrt', 'Sanctioned')], ['op', 'OP']].map(([k, l]) => h('button', { class: 'px-btn small ' + (filter === k ? 'active' : ''), onclick: () => { filter = k; render(); } }, l))),
      h('div', { class: 'pl-list' }, rows.length ? rows.map(p => h('div', { class: 'pl-row' + (cur === p.uuid ? ' sel' : ''), onclick: () => open(p) },
        h('i', { class: 'pl-dot' + (p.online ? ' on' : '') }),
        h('div', { class: 'grow' }, h('div', { class: 'pl-name' }, p.name, p.op ? h('span', { class: 'tag warn', style: 'margin-left:6px' }, 'OP') : null, p.sanctioned ? h('span', { class: 'tag bad', style: 'margin-left:6px' }, '⚠') : null),
          h('div', { class: 'pl-sub' }, [p.role ? roleName(p.role) : '', p.notes ? p.notes + ' ' + L('Notizen', 'notes') : ''].filter(Boolean).join(' · ') || ' ')))) : h('div', { class: 'empty' }, L('Keine Spieler.', 'No players.'))));
  }

  function detailView() {
    if (!detail) return h('div', { class: 'empty' }, L('Wähle links einen Spieler.', 'Pick a player on the left.'));
    const d = detail, base = 'player/' + d.uuid + '/';
    const roleSel = h('select', { style: 'width:auto', onchange: async ev => setDetail(await call(base + 'role', 'POST', { role: ev.target.value }, L('Rolle gesetzt', 'Role set'))) },
      h('option', { value: '' }, L('(keine Rolle)', '(no role)')), meta.roles.map(r => h('option', { value: r.id, selected: r.id === d.role }, ctx.t({ de: r.nameDe, en: r.nameEn }))));
    const webChip = d.web.access ? h('span', { class: 'chip good' }, d.web.all ? L('Web: alles', 'Web: everything') : L('Web: ', 'Web: ') + d.web.modules.join(', ')) : h('span', { class: 'chip' }, L('Kein Web-Zugang', 'No web access'));
    const tabs = [['rights', L('Rechte', 'Rights')], ['notes', L('Notizen', 'Notes') + ' (' + d.notes.length + ')'], ['sanctions', L('Sperren', 'Sanctions') + ' (' + d.sanctions.length + ')'], ['history', L('Verlauf', 'History')]];
    return h('div', {},
      h('div', { class: 'card' },
        h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, d.name), h('span', { class: 'chip ' + (d.online ? 'good' : '') }, d.online ? 'Online' : L('Zuletzt: ', 'Last: ') + when(d.lastSeen)), d.op ? h('span', { class: 'chip warn' }, 'OP') : null, webChip),
        h('div', { class: 'pl-sub' }, d.uuid),
        h('div', { class: 'row', style: 'margin-top:12px' }, h('span', { class: 'sub' }, L('Rolle', 'Role')), roleSel)),
      h('div', { class: 'row', style: 'margin:10px 0' }, tabs.map(([k, l]) => h('button', { class: 'px-btn small ' + (dtab === k ? 'active' : ''), onclick: () => { dtab = k; render(); } }, l))),
      dtab === 'rights' ? rightsView(d, base) : dtab === 'notes' ? notesView(d, base) : dtab === 'sanctions' ? sanctionsView(d, base) : historyView(d));
  }

  function rightsView(d, base) {
    const groups = {};
    d.rights.forEach(r => (groups[r.module] ??= []).push(r));
    return h('div', { class: 'card' }, h('p', { class: 'sub' }, L('Vererben = die Rolle und die Standardeinstellung des Servers gelten. Verbieten gewinnt immer.', 'Inherit = the role and the server default apply. Deny always wins.')),
      Object.entries(groups).map(([mod, rights]) => h('div', { class: 'pl-mod' }, h('h3', {}, MOD[mod] || mod),
        rights.map(r => h('div', { class: 'pl-right' },
          h('div', {}, h('b', {}, r.node), r.fromRole ? h('span', { class: 'tag', style: 'margin-left:6px' }, L('von Rolle', 'from role')) : null, r.sanctioned ? h('span', { class: 'tag bad', style: 'margin-left:6px' }, L('gesperrt', 'blocked')) : null,
            h('div', { class: 'pl-sub' }, r.desc + ' · ' + (r.effective ? L('gilt', 'effective') : L('gilt nicht', 'not effective')))),
          h('div', { class: 'pl-seg' }, [['allow', L('Erlauben', 'Allow')], ['inherit', L('Vererben', 'Inherit')], ['deny', L('Verbieten', 'Deny')]].map(([m, l]) => h('button', { class: m + (r.mode === m ? ' on' : ''), onclick: async () => { if (r.mode !== m) setDetail(await call(base + 'perm', 'POST', { node: r.node, mode: m })); } }, l))))))));
  }

  function notesView(d, base) {
    const t = h('textarea', { rows: 3, maxlength: 500, placeholder: L('Notiz für andere Admins', 'Note for other admins') });
    return h('div', { class: 'card' }, t, h('div', { class: 'row', style: 'margin-top:8px' }, h('button', { class: 'px-btn primary', onclick: async () => { if (t.value.trim()) setDetail(await call(base + 'note', 'POST', { text: t.value }, L('Notiz gespeichert', 'Note saved'))); } }, L('Notiz hinzufügen', 'Add note'))),
      d.notes.length ? h('table', { style: 'margin-top:12px' }, d.notes.slice().reverse().map(n => h('tr', {}, h('td', { class: 'sub' }, when(n.time)), h('td', {}, n.admin), h('td', {}, n.text),
        h('td', {}, h('button', { class: 'px-btn small danger', onclick: async () => { if (await ctx.confirm(L('Notiz löschen?', 'Delete note?'))) setDetail(await call(base + 'note/' + n.index, 'DELETE')); } }, '×'))))) : h('div', { class: 'empty', style: 'margin-top:12px' }, L('Keine Notizen.', 'No notes.')));
  }

  function sanctionsView(d, base) {
    const mods = [...new Set(meta.catalog.map(c => c.module))];
    const scope = h('select', {}, h('option', { value: 'all' }, L('Alles (alle Module und Web)', 'Everything (all modules and web)')), h('option', { value: 'web' }, L('Nur Web-Oberfläche', 'Web interface only')), mods.map(m => h('option', { value: m }, MOD[m] || m)));
    const reason = h('input', { placeholder: L('Grund (Pflicht)', 'Reason (required)'), maxlength: 500 });
    const hours = h('input', { type: 'number', min: 0, value: 24, style: 'width:110px' });
    return h('div', { class: 'card' },
      h('div', { class: 'row' }, h('span', { class: 'sub' }, L('Bereich', 'Scope')), scope, h('span', { class: 'sub' }, L('Stunden (0 = dauerhaft)', 'Hours (0 = permanent)')), hours), reason,
      h('div', { class: 'row', style: 'margin-top:8px' },
        h('button', { class: 'px-btn danger', onclick: async () => { if (!reason.value.trim()) { ctx.toast(L('Bitte einen Grund angeben.', 'Please give a reason.'), 'bad'); return; } if (await ctx.confirm(L('Wirklich sperren?', 'Really block?'))) { setDetail(await call(base + 'sanction', 'POST', { scope: scope.value, reason: reason.value, hours: +hours.value }, L('Gesperrt', 'Blocked'))); } } }, L('Sperren', 'Block')),
        h('button', { class: 'px-btn', onclick: async () => { if (reason.value.trim()) setDetail(await call(base + 'warn', 'POST', { reason: reason.value }, L('Verwarnt', 'Warned'))); else ctx.toast(L('Bitte einen Grund angeben.', 'Please give a reason.'), 'bad'); } }, L('Nur verwarnen', 'Warn only'))),
      d.sanctions.length ? h('table', { style: 'margin-top:12px' }, d.sanctions.slice().reverse().map(s => h('tr', {}, h('td', {}, h('span', { class: 'tag ' + (s.warning ? 'warn' : s.active ? 'bad' : '') }, s.warning ? L('Verwarnung', 'Warning') : s.active ? L('Gesperrt', 'Blocked') : L('Abgelaufen', 'Expired'))),
        h('td', {}, s.scope), h('td', {}, s.reason), h('td', { class: 'sub' }, s.admin + ' · ' + when(s.time) + (s.until ? ' → ' + when(s.until) : '')),
        h('td', {}, h('button', { class: 'px-btn small', onclick: async () => setDetail(await call(base + 'sanction/' + s.id, 'DELETE', undefined, L('Aufgehoben', 'Lifted'))) }, L('Aufheben', 'Lift')))))) : h('div', { class: 'empty', style: 'margin-top:12px' }, L('Keine Sperren oder Verwarnungen.', 'No sanctions or warnings.')));
  }

  function historyView(d) {
    return h('div', { class: 'card' }, d.history.length ? h('table', {}, d.history.slice().reverse().map(x => h('tr', {}, h('td', { class: 'sub' }, when(x.time)), h('td', {}, x.admin), h('td', {}, x.module), h('td', {}, x.action)))) : h('div', { class: 'empty' }, L('Keine Einträge im Web-Protokoll.', 'No entries in the web log.')),
      h('p', { class: 'sub' }, L('Hier stehen Web-Änderungen, die den Namen enthalten. Uploads und Aktionen im Spiel stehen im Server-Log.', 'Shows web changes that mention the name. Uploads and in-game actions are in the server log.')));
  }

  // ---------- Rollen ----------
  function rolesView() {
    const r = editRole;
    const nodesByMod = {};
    meta.catalog.forEach(c => (nodesByMod[c.module] ??= []).push(c));
    const form = r ? (() => {
      const id = h('input', { value: r.id, disabled: !r.isNew, placeholder: 'kennung' }), de = h('input', { value: r.nameDe }), en = h('input', { value: r.nameEn });
      const web = h('input', { type: 'checkbox', checked: r.web, onchange: ev => { r.web = ev.target.checked; render(); } });
      const nodes = new Set(r.nodes), mods = new Set(r.modules);
      const chk = (set, key, label, title) => h('label', { class: 'pl-chk', title: title || '' }, h('input', { type: 'checkbox', checked: set.has(key), onchange: ev => { ev.target.checked ? set.add(key) : set.delete(key); } }), label);
      return h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, r.isNew ? L('Neue Rolle', 'New role') : r.id),
        h('div', { class: 'row' }, h('div', {}, h('label', {}, 'ID'), id), h('div', {}, h('label', {}, L('Name (Deutsch)', 'Name (German)')), de), h('div', {}, h('label', {}, L('Name (Englisch)', 'Name (English)')), en)),
        h('label', { class: 'pl-chk', style: 'margin-top:12px' }, web, L('Darf die Web-Oberfläche benutzen (Link per /peakweb)', 'May use the web interface (link via /peakweb)')),
        r.web ? h('div', {}, h('label', {}, L('Sichtbare Seiten', 'Visible pages')), chk(mods, '*', L('Alle (Voll-Zugang)', 'All (full access)')), meta.modules.filter(m => m !== 'players').map(m => chk(mods, m, m)),
          h('div', { class: 'pl-sub' }, L('Die Spieler-Seite gibt es nur mit Voll-Zugang.', 'The players page needs full access.'))) : null,
        h('label', {}, L('Rechte (gelten beim Spieler mit dieser Rolle)', 'Rights (apply to players with this role)')),
        Object.entries(nodesByMod).map(([mod, list]) => h('div', { class: 'pl-mod' }, h('b', {}, MOD[mod] || mod), h('div', {}, chk(nodes, mod + '.*', L('alle ', 'all ') + mod + '.*'), list.map(c => chk(nodes, c.node, c.node, c.desc))))),
        h('div', { class: 'row', style: 'margin-top:14px' },
          h('button', { class: 'px-btn primary', onclick: async () => { const res = await call('roles', 'POST', { id: id.value.trim().toLowerCase(), nameDe: de.value, nameEn: en.value, web: r.web, nodes: [...nodes], modules: [...mods] }, L('Rolle gespeichert', 'Role saved')); if (res) { meta = res; editRole = null; render(); } } }, L('Speichern', 'Save')),
          h('button', { class: 'px-btn ghost', onclick: () => { editRole = null; render(); } }, L('Abbrechen', 'Cancel'))));
    })() : null;
    return h('div', {},
      h('div', { class: 'row' }, h('button', { class: 'px-btn primary', onclick: () => { editRole = { isNew: true, id: '', nameDe: '', nameEn: '', web: false, nodes: [], modules: [] }; render(); } }, '+ ' + L('Neue Rolle', 'New role'))),
      form,
      h('div', { class: 'grid', style: 'margin-top:14px' }, meta.roles.map(ro => h('div', { class: 'tile', onclick: () => { editRole = { ...ro, isNew: false }; render(); window.scrollTo({ top: 0, behavior: 'smooth' }); } },
        h('div', { class: 'nm' }, ctx.t({ de: ro.nameDe, en: ro.nameEn })), h('div', { class: 'meta' }, ro.id + (ro.builtin ? ' · ' + L('fertig', 'built-in') : '')),
        h('div', { class: 'meta' }, ro.web ? (ro.modules.includes('*') ? L('Web: alles', 'Web: everything') : 'Web: ' + (ro.modules.join(', ') || '–')) : L('kein Web-Zugang', 'no web access')),
        h('div', { class: 'meta' }, ro.nodes.length + ' ' + L('Rechte', 'rights')),
        ro.builtin ? null : h('div', { class: 'row', style: 'margin-top:8px' }, h('button', { class: 'px-btn small danger', onclick: async ev => { ev.stopPropagation(); if (await ctx.confirm(L('Rolle löschen? Spieler mit dieser Rolle verlieren sie.', 'Delete role? Players with it lose it.'))) { const res = await call('roles/' + ro.id, 'DELETE', undefined, L('Gelöscht', 'Deleted')); if (res) { meta = res; players = await ctx.api('list'); render(); } } } }, L('Löschen', 'Delete')))))));
  }

  // ---------- Plugins (Rechte fremder Plugins mitverwalten) ----------
  let pluginList = null;
  function pluginsView() {
    if (!pluginList) { ctx.api('plugins').then(r => { pluginList = r; render(); }).catch(() => {}); return h('p', { class: 'sub' }, '...'); }
    return h('div', {},
      h('p', { class: 'sub' }, L('Hier waehlst du Plugins (z. B. AssassinTales), deren Rechte du in Rollen und bei einzelnen Spielern setzen willst. Die Rechte erscheinen danach im Reiter Rechte.', 'Pick plugins (e.g. AssassinTales) whose permissions you want to set in roles and per player. They then show up in the Rights tab.')),
      pluginList.map(p => h('div', { class: 'pl-right' }, h('div', {}, h('b', {}, p.name), h('span', { class: 'sub' }, ' · ' + p.count + ' ' + L('Rechte', 'permissions'))),
        h('div', { class: 'pl-seg' }, h('button', { class: p.managed ? 'on allow' : '', onclick: async () => { const r = await call('plugins', 'POST', { name: p.name, on: !p.managed }, 'OK'); if (r) { pluginList = r; await rel(); render(); } } }, p.managed ? L('verwaltet', 'managed') : L('nicht verwaltet', 'not managed'))))));
  }
  function render(keepFocus) {
    const main = tab === 'players' ? h('div', { class: 'pl-wrap' }, listView(), detailView()) : tab === 'plugins' ? pluginsView() : rolesView();
    set(h('h1', {}, L('Spieler', 'Players')), h('p', { class: 'sub' }, L('Rechte, Rollen, Notizen und Sperren – getrennt je Modul. Spieler ohne Eintrag behalten genau die Rechte, die der Server sonst vergibt.', 'Rights, roles, notes and sanctions – separate per module. Players without an entry keep exactly the rights the server gives them otherwise.')),
      h('div', { class: 'row' }, [['players', L('Spieler', 'Players')], ['roles', L('Rollen', 'Roles') + ' (' + meta.roles.length + ')'], ['plugins', L('Plugins', 'Plugins')]].map(([k, l]) => h('button', { class: 'px-btn ' + (tab === k ? 'active' : ''), onclick: () => { tab = k; render(); } }, l))),
      h('div', { style: 'margin-top:14px' }, main));
    if (keepFocus) { const i = root.querySelector('.pl-list')?.parentElement?.querySelector('input'); if (i) { i.focus(); i.setSelectionRange(i.value.length, i.value.length); } }
  }

  ctx.on(async d => { if (d.module === 'players') { try { await rel(); if (cur) detail = await ctx.api('player/' + cur); render(); } catch { /* egal */ } } });
  await rel();
  render();
}
