// The pack's sidebar as it RENDERS it (labels and icons are rewritten at run time): the union over every role of
// entry id -> { code, label, icon, groups }, for the nav.<id> / navicon.<id> labels (OD-121).
//   node ../scripts/wireframe_nav_extract.mjs <pack.html> <out.json>     (from frontend/)
import { createRequire } from 'node:module';
import { writeFileSync } from 'node:fs';
const require = createRequire(`${process.cwd()}/package.json`);
const { chromium } = require('@playwright/test');
const [src, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
await page.goto(`file:///${src.replace(/\\/g, '/')}`);
const roles = ['maker', 'checker', 'ldh', 'hr', 'fin', 'admin', 'emp'];
const entries = {};
const groups = {};
for (const role of roles) {
  const got = await page.evaluate((r) => {
    setRole(r);
    renderSide();
    const txt = (el) => (el?.textContent || '').replace(/\s+/g, ' ').trim();
    const side = document.getElementById('side') || document.querySelector('.side');
    const res = [];
    let group = { section: '', title: 'Home' };
    for (const el of Array.from(side.children)) {
      if (el.classList.contains('grp')) {
        // The group button carries its chevron (and a count when collapsed): the title is the text before them.
        const title = txt(el).replace(/[⌄›]\s*$/, '').replace(/\d+\s*$/, '').trim();
        const g = NAVGROUPS.find((x) => x[1].replace(/&amp;/g, '&').replace(/<[^>]+>/g, '').trim() === title);
        group = { section: g ? g[0] : '?', title };
      } else if (el.classList.contains('nav')) {
        const id = el.dataset.id;
        const c = el.cloneNode(true);
        const icon = txt(c.querySelector('.ic'));
        c.querySelector('.ic')?.remove();
        c.querySelector('.tag')?.remove();
        res.push({ id, code: SCREENS[id]?.code, label: txt(c), icon, section: group.section, group: group.title, order: res.length });
      }
    }
    return res;
  }, role);
  for (const e of got) {
    if (e.section === 'R' || e.section === 'N') continue;
    entries[e.id] ??= { code: e.code, label: e.label, icon: e.icon, section: e.section, group: e.group, order: e.order, roles: [] };
    entries[e.id].roles.push(role);
    groups[e.section] ??= { title: e.group, order: Object.keys(groups).length };
  }
}
writeFileSync(out, JSON.stringify({ groups, entries }, null, 1));
console.log(`${Object.keys(entries).length} entries, ${Object.keys(groups).length} groups -> ${out}`);
await browser.close();
