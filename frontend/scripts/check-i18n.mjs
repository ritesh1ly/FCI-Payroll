#!/usr/bin/env node
/**
 * CI gate of the FCILekha i18n rule (npm run check:i18n). Fails when
 *   1. messages_en and messages_hi do not have identical keys (also checked by build-i18n);
 *   2. a literal t('key') in src/ has no entry in the bundle, or a template t(`prefix.${…}`) has no
 *      key with that prefix;
 *   3. a component hard-codes a label: JSX text, or a string placeholder / title / aria-label /
 *      alt attribute containing letters (Latin or Devanagari). Data values come from the API and
 *      punctuation or symbols (—, →, ✔, 🔎) are fine. Mark a deliberate exception with a comment
 *      containing "i18n-ignore" on the line before.
 * Uses the TypeScript compiler API, so it sees the real syntax tree, not a regex guess.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { parseProperties } from './build-i18n-lib.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const bundles = resolve(root, '..', 'backend', 'src', 'main', 'resources', 'i18n');
const en = parseProperties(readFileSync(join(bundles, 'messages_en.properties'), 'utf8'));
const hi = parseProperties(readFileSync(join(bundles, 'messages_hi.properties'), 'utf8'));
const keys = new Set(Object.keys(en));
const problems = [];

for (const key of keys) if (!(key in hi)) problems.push(`messages_hi.properties lacks ${key}`);
for (const key of Object.keys(hi)) if (!keys.has(key)) problems.push(`messages_en.properties lacks ${key}`);
for (const [key, value] of Object.entries(hi))
  if (value.trim() === '') problems.push(`messages_hi: empty value for ${key}`);

const LETTERS = /[A-Za-z]{2,}|[ऀ-ॿ]/;
const LABEL_ATTRIBUTES = new Set(['placeholder', 'title', 'aria-label', 'alt']);

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'generated' ? [] : files(path);
    return /\.(tsx?|mts)$/.test(name) ? [path] : [];
  });
}

function ignored(source, node) {
  const { line } = source.getLineAndCharacterOfPosition(node.getStart(source));
  const lines = source.text.split('\n');
  return [lines[line - 1] ?? '', lines[line] ?? ''].some((l) => l.includes('i18n-ignore'));
}

for (const file of files(join(root, 'src'))) {
  const text = readFileSync(file, 'utf8');
  const source = ts.createSourceFile(
    file,
    text,
    ts.ScriptTarget.Latest,
    true,
    file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
  const where = (node) =>
    `${relative(root, file)}:${source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1}`;

  const visit = (node) => {
    if (
      ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) &&
      node.expression.text === 't' &&
      node.arguments.length > 0
    ) {
      const arg = node.arguments[0];
      if (ts.isStringLiteral(arg) || ts.isNoSubstitutionTemplateLiteral(arg)) {
        if (!keys.has(arg.text)) problems.push(`${where(arg)}: unknown key '${arg.text}'`);
      } else if (ts.isTemplateExpression(arg)) {
        const prefix = arg.head.text;
        if (prefix && ![...keys].some((k) => k.startsWith(prefix)))
          problems.push(`${where(arg)}: no key starts with '${prefix}'`);
      }
    }
    if (ts.isJsxText(node) && LETTERS.test(node.text) && !ignored(source, node)) {
      problems.push(`${where(node)}: hard-coded label "${node.text.trim().slice(0, 60)}"`);
    }
    if (ts.isJsxAttribute(node) && node.initializer && ts.isStringLiteral(node.initializer)) {
      const name = node.name.getText(source);
      if (LABEL_ATTRIBUTES.has(name) && LETTERS.test(node.initializer.text) && !ignored(source, node)) {
        problems.push(`${where(node)}: hard-coded ${name}="${node.initializer.text.slice(0, 60)}"`);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
}

if (problems.length > 0) {
  console.error(`check-i18n: ${problems.length} problem(s)\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
console.log(`check-i18n: ${keys.size} keys, en/hi identical, no hard-coded labels`);
