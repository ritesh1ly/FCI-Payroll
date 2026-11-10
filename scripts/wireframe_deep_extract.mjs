// Deep object inventory of a screen, from the v6.1 wireframe pack (binding for screen structure, OD-121) or from the
// built app (same shape, so the two compare object by object).
//
//   node ../scripts/wireframe_deep_extract.mjs pack <pack.html> <out.json> [CODE ...]      (run from frontend/)
//   live side: WF_CRAWL=<out.json> sh run_e2e.sh <log> e2e/support/wireframe-live-crawl.spec.ts (same inventory function)
//
// Per screen (and per role that can view it, in the pack): ordered `objects` - every heading, tile, filter / form field
// (label, control type, options, required, read-only, source badge), table (columns with source badge, row actions,
// first row), button (disabled and its reason), tab, banner, pill and note - plus the dialog forms the pack defines
// (FORMS). Text only; no styling. Compare with scripts/wireframe_parity.py.
import { createRequire } from 'node:module';
import { writeFileSync } from 'node:fs';
const require = createRequire(`${process.cwd()}/package.json`);
const { chromium } = require('@playwright/test');
const [mode, src, out, ...rest] = process.argv.slice(2);

/**
 * Runs inside the page: returns the ordered inventory of the visible main area. With `opts.sub` (a sub-view: action
 * dialog, row View / Edit dialog, document, entry form) it also reads what only dialogs and documents draw: a key /
 * value row (`.kv` > `.k`) as a read-only field with the key's source badge (the pack's View dialog draws the same
 * field as a disabled input), a form section or document band (`.fsec`, `.band`) as a heading, and the header cells of
 * a table without a `thead` (a document's printed labels) as its columns. Off for the first render of a screen.
 */
function inventory(rootSelector, opts) {
  const subView = !!(opts && opts.sub);
  const root = document.querySelector(rootSelector) || document.body;
  const txt = (el) => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
  const badge = (el) => {
    const b = el.querySelector('.src');
    return b ? (b.className.match(/s-([A-Z]+)/) || [])[1] || txt(b) : null;
  };
  const strip = (el) => {
    const c = el.cloneNode(true);
    c.querySelectorAll('.src, .frs, script, style').forEach((x) => x.remove());
    return txt(c);
  };
  const objects = [];
  const seen = new Set();
  const push = (o) => {
    const k = JSON.stringify(o);
    if (!seen.has(k)) {
      seen.add(k);
      objects.push(o);
    }
  };
  const visible = (el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const walk = (el) => {
    if (!visible(el)) return;
    if (el.closest('details:not([open])')) return;
    const tag = el.tagName;
    const cls = typeof el.className === 'string' ? el.className : '';
    if (/^H[1-6]$/.test(tag)) return push({ k: 'heading', t: strip(el), src: badge(el) });
    const classes = cls.split(/\s+/);
    if (subView && (classes.includes('fsec') || classes.includes('band'))) return push({ k: 'heading', t: strip(el), src: badge(el) });
    if (subView && classes.includes('kv')) {
      // Key / value pairs: the key is the field (label, badge), the value is the record of the day; a bold key
      // with no value is a section heading. Objects inside a value (a button, a nested table) are read too.
      for (const k of el.children) {
        if (!(typeof k.className === 'string' && k.className.split(/\s+/).includes('k'))) continue;
        const v = k.nextElementSibling;
        const value = v ? strip(v) : '';
        if (!value && k.querySelector('b') && strip(k) === txt(k.querySelector('b'))) push({ k: 'heading', t: strip(k), src: badge(k) });
        else push({ k: 'field', label: strip(k), type: 'text', required: /\*/.test(txt(k)), readOnly: true, src: badge(k), value: value.slice(0, 60) });
        if (v) for (const c of v.children) walk(c);
      }
      return;
    }
    if (cls.split(/\s+/).includes('tile') || cls.includes('kpi')) {
      // A tile is its label (structure); the big value and the sub-text are data of the day.
      const lab = el.querySelector('.lab');
      const sub = el.querySelector('.sub');
      const ico = el.querySelector('.tico');
      // The label without its glyph; the glyph on its own (icons match the wireframe too, OD-121).
      const label = lab ? (() => { const c = lab.cloneNode(true); c.querySelectorAll('.tico, .src').forEach((x) => x.remove()); return txt(c); })() : strip(el);
      return push({ k: 'tile', t: label, icon: ico ? txt(ico) : '', value: strip(el.querySelector('.big') || el).slice(0, 40), sub: sub ? strip(sub) : '' });
    }
    if (tag === 'TABLE') {
      let cols = [...el.querySelectorAll('thead th')].map((th) => ({ t: strip(th), src: badge(th) }));
      // A document's printed labels; a figure in a header cell (a total row) is the record of the day.
      if (subView && !cols.length) cols = [...el.querySelectorAll('th')].map((th) => ({ t: strip(th), src: badge(th) })).filter((c) => !/^[\d,.₹\s()%-]*$/.test(c.t));
      const first = el.querySelector('tbody tr');
      // Row actions depend on each row's state: the union over the rows of the page, not the first row alone.
      const seen = new Set();
      const acts = [...el.querySelectorAll('tbody tr button, tbody tr a')].filter((b) => !seen.has(strip(b)) && seen.add(strip(b))).map((b) => ({ t: strip(b), disabled: b.disabled || b.getAttribute('aria-disabled') === 'true', why: b.dataset.why || b.title || null }));
      return push({ k: 'table', cols, rows: el.querySelectorAll('tbody tr').length, rowActions: acts, firstRow: first ? [...first.children].map((c) => strip(c)).slice(0, 14) : [] });
    }
    if (['INPUT', 'SELECT', 'TEXTAREA'].includes(tag)) {
      if (el.closest('details:not([open])')) return;
      const id = el.id;
      let label = id ? document.querySelector(`label[for="${id}"]`) : null;
      if (!label) label = el.closest('label') || el.closest('.f, .fld, .field, .fg, .rf-field, .row > div')?.querySelector('label, .lbl, .fl');
      const options = tag === 'SELECT' ? [...el.options].map((o) => o.text.trim()).slice(0, 40) : undefined;
      return push({ k: 'field', label: label ? strip(label) : (tag === 'SELECT' && options[0]) || el.placeholder || el.getAttribute('aria-label') || '', type: tag === 'INPUT' ? el.type : tag.toLowerCase(), required: el.required || /\*/.test(label ? txt(label) : ''), readOnly: el.readOnly || el.disabled, options, src: label ? badge(label) : null, value: el.type === 'password' ? '' : (el.value || '').slice(0, 60), placeholder: el.placeholder || undefined });
    }
    if (tag === 'BUTTON' || cls.split(/\s+/).includes('btn')) {
      if (el.closest('table')) return;
      return push({ k: 'button', t: strip(el), disabled: el.disabled || /lock/.test(cls), why: el.dataset.why || el.title || null });
    }
    if (/\btab\b/.test(cls) || el.getAttribute('role') === 'tab') return push({ k: 'tab', t: strip(el), on: /\bon\b|active/.test(cls) || el.getAttribute('aria-selected') === 'true' });
    if (/pill|badge|chip/.test(cls) && !el.closest('table') && !el.closest('.src')) push({ k: 'pill', t: strip(el) });
    if (/note|banner|alert|callout|hint|legend/.test(cls) && el.children.length < 6) push({ k: 'note', t: strip(el).slice(0, 400) });
    for (const c of el.children) walk(c);
  };
  walk(root);
  return objects;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, ignoreHTTPSErrors: true });
const result = { mode, at: new Date().toISOString(), screens: {} };

if (mode === 'pack') {
  await page.goto(`file:///${src.replace(/\\/g, '/')}`);
  const index = await page.evaluate(() => Object.entries(SCREENS).map(([id, s]) => ({ id, code: s.code, name: String(s.name).replace(/<[^>]+>/g, ''), view: s.rbac?.v ?? [], edit: s.rbac?.c ?? [], approve: s.rbac?.a ?? [] })));
  const want = rest.length ? index.filter((s) => rest.includes(s.code)) : index;
  result.forms = await page.evaluate(() => JSON.parse(JSON.stringify(typeof FORMS === 'undefined' ? {} : FORMS, (k, v) => (typeof v === 'function' ? undefined : v))));
  result.roles = await page.evaluate(() => Object.fromEntries(Object.entries(ROLES).map(([k, r]) => [k, r.name])));
  const fn = inventory.toString();
  for (const s of want) {
    const roles = [...new Set([...(s.edit.slice(0, 1)), ...s.view.slice(0, 1), ...s.approve.slice(0, 1)])].filter(Boolean);
    const entry = { id: s.id, code: s.code, name: s.name, view: s.view, edit: s.edit, approve: s.approve, byRole: {} };
    for (const role of roles.length ? roles : ['maker']) {
      try {
        entry.byRole[role] = await page.evaluate(([id, role, fn]) => {
          setRole(role);
          go(id);
          const f = eval(`(${fn})`);
          return f('#main');
        }, [s.id, role, fn]);
      } catch (e) {
        entry.byRole[role] = { error: String(e).slice(0, 200) };
      }
    }
    result.screens[`${s.code}#${s.id}`] = entry;
  }
} else {
  throw new Error('the live side is frontend/e2e/support/wireframe-live-crawl.spec.ts');
}
writeFileSync(out, JSON.stringify(result, null, 1));
if (mode === 'pack') {
  // The small index (id, code, name, roles) the spec generator reads: docs/quality/wireframe-parity/pack-index.json.
  const index = Object.values(result.screens).map((s) => ({ id: s.id, code: s.code, name: s.name, view: s.view, edit: s.edit, approve: s.approve }));
  writeFileSync(out.replace(/\.json$/, '') + '.index.json', JSON.stringify({ pack: src.replace(/.*[\\/]/, ''), screens: index }, null, 1));
}
console.log(`${Object.keys(result.screens).length} screen entries -> ${out}`);
await browser.close();
