import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

/**
 * The target DDL v4.0, the schema of record (CLAUDE.md §1): the frontend's fixed vocabularies are its
 * CHECK lists, so tests compare them with the file itself instead of a copy. Found by walking up from
 * the working directory, so the tests also run inside the Stryker sandbox
 * (frontend/.stryker-tmp/sandbox-*).
 */
const DDL = join('docs', 'requirements', 'src', 'FCI_Payroll_Target_DDL_v4.0.sql');

let cached: string | undefined;

function ddlText(): string {
  if (cached !== undefined) return cached;
  let dir = process.cwd();
  for (;;) {
    const candidate = join(dir, DDL);
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- the repository's own DDL
    if (existsSync(candidate)) {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- the repository's own DDL
      cached = readFileSync(candidate, 'utf8');
      return cached;
    }
    const parent = dirname(dir);
    if (parent === dir) throw new Error(`${DDL} not found above ${process.cwd()}`);
    dir = parent;
  }
}

/** The values of `CHECK (<column> IN (...))` in `CREATE TABLE <table>`, in DDL order. */
export function ddlCheckValues(table: string, column: string): string[] {
  const text = ddlText();
  const start = text.indexOf(`CREATE TABLE ${table} (`);
  if (start < 0) throw new Error(`no table ${table} in the DDL`);
  const block = text.slice(start, text.indexOf('\n);', start));
  const open = `CHECK (${column} IN (`;
  const at = block.indexOf(open);
  if (at < 0) throw new Error(`no CHECK list for ${table}.${column}`);
  const list = block.slice(at + open.length, block.indexOf(')', at + open.length));
  return [...list.matchAll(/'([^']*)'/g)].map((m) => m[1] ?? '');
}
