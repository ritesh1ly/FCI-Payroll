// Extracts the structure of wireframe screens from the v6.1 developer pack (a layout reference, see the
// playbook): per screen its card titles, table columns with their field-source badge, labels, buttons, tiles.
// Usage (from frontend/, which provides Playwright):
//   node ../scripts/wireframe_extract.mjs <pack.html> <out.json> [CODE ...]
// then: python -I ../scripts/wireframe_compare.py <out.json> [<live.json>]
import { createRequire } from 'node:module';
import { writeFileSync } from 'node:fs';
const require = createRequire(`${process.cwd()}/package.json`);
const { chromium } = require('@playwright/test');
const PACK = `file:///${process.argv[2].replace(/\\/g, '/')}`;
const DEFAULT = ['D-01','C-09','C-10','C-11','C-12','R-02','X-01','X-02','X-03','X-04','X-05','X-06','X-07','X-08','A-01','A-02','A-03','A-04','A-05','A-06','A-07','A-08',
  'W-01','W-02','I-17','I-20','I-23','M-01','M-04','M-10','M-14','M-15','M-16','M-22','M-23','M-24','M-25','M-26','M-27',
  'C-01','C-02','C-13','E-01','R-01'];
const WANT = process.argv.length > 4 ? process.argv.slice(4) : DEFAULT;
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
await page.goto(PACK);
const index = await page.evaluate(() =>
  Object.entries(SCREENS).map(([id, s]) => ({ id, code: s.code, name: String(s.name).replace(/<[^>]+>/g, ''), roles: s.rbac?.v ?? [] })));
const roles = await page.evaluate(() => Object.fromEntries(Object.entries(ROLES).map(([k, r]) => [k, r.name])));
const out = { roles, screens: {} };
for (const code of WANT) {
  const s = index.find((x) => x.code === code);
  if (!s) { out.screens[code] = { missing: true }; continue; }
  const role = s.roles[0];
  const shape = await page.evaluate(([id, role]) => {
    setRole(role); go(id);
    const main = document.getElementById('main') || document.querySelector('main');
    const txt = (el) => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    const uniq = (a) => [...new Set(a.filter(Boolean))];
    return {
      cards: uniq([...main.querySelectorAll('.card .ch, .card h3, .card-h, .card > .hd, h2, h3')].map(txt)).slice(0, 40),
      columns: [...main.querySelectorAll('table')].map((t) => [...t.querySelectorAll('thead th')].map((th) => {
        const c = th.cloneNode(true); const b = c.querySelector('.src'); const src = b ? (b.className.match(/s-([A-Z]+)/) || [])[1] : null;
        if (b) b.remove(); return src ? `${txt(c)} [${src}]` : txt(c); })).filter((c) => c.length),
      labels: uniq([...main.querySelectorAll('label, .lbl, .fl')].map(txt)).slice(0, 80),
      buttons: uniq([...main.querySelectorAll('button, .btn, a.btn')].map(txt)).slice(0, 60),
      tiles: uniq([...main.querySelectorAll('.tile, .kpi, .stat')].map(txt)).slice(0, 20),
      badges: uniq([...main.querySelectorAll('[class*="s-ENT"],[class*="s-SYS"],[class*="s-HRMS"],[class*="s-CALC"],[class*="s-RULE"],[class*="s-INTF"],[class*="s-ESS"]')].map((e) => e.className.match(/s-[A-Z]+/)?.[0])),
    };
  }, [s.id, role]);
  out.screens[code] = { id: s.id, name: s.name, role, roleName: roles[role], ...shape };
}
writeFileSync(process.argv[3], JSON.stringify(out, null, 1));
console.log('screens:', Object.keys(out.screens).length, 'missing:', Object.entries(out.screens).filter(([, v]) => v.missing).map(([k]) => k).join(' '));
await browser.close();
