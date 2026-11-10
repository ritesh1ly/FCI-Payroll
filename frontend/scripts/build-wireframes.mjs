#!/usr/bin/env node
/**
 * Build-time sanitisation of the live wireframe bodies (wireframes/screens.json, taken from the
 * live FCILekha site). The browser never receives HTML: each body becomes a JSON element tree that
 * src/components/workspace/Wireframe.tsx renders with React, through an allow-list of tags and
 * attributes. On the way:
 *   - style="…" attributes become generated classes (src/generated/wireframe.css), so the CSP keeps
 *     style-src free of 'unsafe-inline';
 *   - onclick/onchange handlers become data-act / data-change descriptors (no inline script);
 *   - every other attribute, tag or handler is dropped.
 * Body text stays English: the design pack's content is not yet translated (>> OPEN << Rajbhasha).
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const screens = JSON.parse(readFileSync(join(root, 'wireframes', 'screens.json'), 'utf8'));
const outDir = join(root, 'src', 'generated');

const TAGS = new Set([
  'div',
  'span',
  'b',
  'i',
  'em',
  'strong',
  'small',
  'p',
  'h3',
  'h4',
  'code',
  'br',
  'table',
  'thead',
  'tbody',
  'tfoot',
  'tr',
  'th',
  'td',
  'button',
  'select',
  'option',
  'input',
  'label',
  'textarea',
  'ul',
  'li',
]);
const VOID = new Set(['br', 'input']);
/** Void elements outside the allow-list: dropped, and never treated as containers. */
const FOREIGN_VOID = new Set([
  'img',
  'hr',
  'meta',
  'link',
  'wbr',
  'area',
  'source',
  'base',
  'embed',
  'col',
  'track',
]);
const ATTRS = new Set([
  'class',
  'id',
  'title',
  'colspan',
  'rowspan',
  'placeholder',
  'type',
  'value',
  'selected',
  'disabled',
  'checked',
  'readonly',
  'name',
  'for',
  'maxlength',
  'min',
  'max',
  'step',
]);
const HANDLERS = new Set(['doAct', 'openForm', 'toast', 'saveDARate', 'scrollToForm', 'setM1Filter']);

const styleClasses = new Map();
const dropped = new Map();

function decode(text) {
  return text.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos|nbsp|#39);/gi, (_, e) => {
    const lower = e.toLowerCase();
    if (lower.startsWith('#x')) return String.fromCodePoint(Number.parseInt(lower.slice(2), 16));
    if (lower.startsWith('#')) return String.fromCodePoint(Number.parseInt(lower.slice(1), 10));
    return { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' }[lower];
  });
}

function styleClass(style) {
  const css = style.trim().replace(/;\s*$/, '');
  if (!styleClasses.has(css)) styleClasses.set(css, `wf-s${styleClasses.size + 1}`);
  return styleClasses.get(css);
}

/** onclick="doAct('Export')" -> { act: 'doAct', args: ['Export'] } */
function handler(code) {
  const match = /^\s*([A-Za-z0-9]+)\((.*)\)\s*;?\s*$/s.exec(code);
  if (!match || !HANDLERS.has(match[1])) return null;
  const args = [...match[2].matchAll(/'((?:\\'|[^'])*)'|"((?:\\"|[^"])*)"|(this(?:\.value)?)/g)].map(
    (m) => m[1] ?? m[2] ?? m[3],
  );
  return { act: match[1], args: args.map((a) => a.replace(/\\'/g, "'")) };
}

function parseAttributes(text) {
  const attrs = {};
  for (const m of text.matchAll(
    /([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/g,
  )) {
    const name = m[1].toLowerCase();
    const value = decode(m[2] ?? m[3] ?? m[4] ?? '');
    if (name === 'style') {
      attrs.class = [attrs.class, styleClass(value)].filter(Boolean).join(' ');
    } else if (name === 'onclick' || name === 'onchange') {
      const h = handler(value);
      if (h) attrs[name === 'onclick' ? 'data-act' : 'data-change'] = JSON.stringify(h);
      else
        dropped.set(`${name}=${value.slice(0, 40)}`, (dropped.get(`${name}=${value.slice(0, 40)}`) ?? 0) + 1);
    } else if (ATTRS.has(name)) {
      attrs[name] = name === 'class' && attrs.class ? `${attrs.class} ${value}` : value;
    } else {
      dropped.set(name, (dropped.get(name) ?? 0) + 1);
    }
  }
  return attrs;
}

/**
 * A forgiving HTML parser for the design-pack fragments: returns [tag, attrs, children] | string.
 * <script> and <style> are dropped with their content; foreign void elements (img, hr …) are
 * dropped; any other unknown element keeps only its text, inside a neutral span.
 */
export function parse(html) {
  const rootNode = ['#root', {}, []];
  // Each stack entry: [node, tagAsWritten] so that an unknown element's closing tag closes it.
  const stack = [[rootNode, '#root']];
  const re =
    /<!--[\s\S]*?-->|<(script|style)\b[\s\S]*?<\/\1\s*>|<\/([a-zA-Z0-9]+)\s*>|<([a-zA-Z0-9]+)((?:\s+[^>]*?)?)\s*(\/?)>|([^<]+)|(<)/gi;
  for (const m of html.matchAll(re)) {
    const top = stack[stack.length - 1][0];
    if (m[0].startsWith('<!--') || m[1]) continue;
    if (m[2]) {
      const tag = m[2].toLowerCase();
      const at = stack.map((entry) => entry[1]).lastIndexOf(tag);
      if (at > 0) stack.length = at;
      continue;
    }
    if (m[3]) {
      const tag = m[3].toLowerCase();
      const known = TAGS.has(tag);
      if (!known) dropped.set(`<${tag}>`, (dropped.get(`<${tag}>`) ?? 0) + 1);
      if (FOREIGN_VOID.has(tag)) continue;
      const node = [known ? tag : 'span', known ? parseAttributes(m[4] ?? '') : {}, []];
      top[2].push(node);
      if (!VOID.has(tag) && m[5] !== '/') stack.push([node, tag]);
      continue;
    }
    const text = decode(m[6] ?? m[7] ?? '');
    if (text) top[2].push(text);
  }
  return rootNode[2];
}

/**
 * The design pack's reviewer aids are not product content: annotations (.anno), open-question and
 * how-to notes (.oq, .howto), FRS reference tags (.frs, .ref, .devrefs), field-source markers and
 * legend (.src, .legend) and the RBAC demonstration strip (.rbac, .rchip). They are removed here, so
 * they never reach the browser.
 */
const DESIGN_AIDS = new Set([
  'anno',
  'oq',
  'howto',
  'frs',
  'ref',
  'devrefs',
  'src',
  'legend',
  'rbac',
  'rchip',
]);
let prunedAids = 0;

function pruneAids(nodes) {
  return nodes
    .filter((node) => {
      if (typeof node === 'string') return true;
      const classes = String(node[1].class ?? '').split(/\s+/);
      const aid = classes.some((c) => DESIGN_AIDS.has(c));
      if (aid) prunedAids++;
      return !aid;
    })
    .map((node) => (typeof node === 'string' ? node : [node[0], node[1], pruneAids(node[2])]));
}

function textOf(html) {
  return decode(html.replace(/<[^>]+>/g, '')).trim();
}

const out = {};
for (const [id, screen] of Object.entries(screens)) {
  const bodies = {};
  for (const [role, body] of Object.entries(screen.bodiesByRole)) {
    bodies[role] = pruneAids(parse(body));
  }
  out[id] = {
    id,
    code: screen.code,
    name: textOf(screen.name),
    crumb: textOf(screen.crumb),
    bodies,
  };
}

mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, 'wireframes.json'), JSON.stringify(out), 'utf8');
const css = [...styleClasses].map(([rule, cls]) => `.wf-root .${cls}{${rule}}`).join('\n');
writeFileSync(
  join(outDir, 'wireframe.css'),
  `/* GENERATED by scripts/build-wireframes.mjs. Do not edit. */\n${css}\n`,
  'utf8',
);
console.log(
  `build-wireframes: ${Object.keys(out).length} screens, ${styleClasses.size} style classes` +
    `, ${prunedAids} design-pack reviewer aids removed` +
    (dropped.size ? `, dropped: ${[...dropped].map(([k, n]) => `${k}×${n}`).join(', ')}` : ''),
);
