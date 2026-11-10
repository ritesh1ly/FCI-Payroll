import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '@/shared/i18n';
import { Src, Th } from '@/shared/ui';

/**
 * R-SRC (KB field-source.md, SRS §6.8.3): every field on every screen shows its source. Found 2026-10-23
 * (wireframe review WF-1): only the Module 01 screens badged their columns. This guard keeps every
 * module's column headers badged, the new modules' included.
 */

const MODULES = join(__dirname, '..', '..', 'src', 'modules');

function componentFiles(): string[] {
  return readdirSync(MODULES).flatMap((module) => {
    const dir = join(MODULES, module, 'components');
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- the repository's own source folders
      return readdirSync(dir)
        .filter((f) => f.endsWith('.tsx'))
        .map((f) => join(dir, f));
    } catch {
      return [];
    }
  });
}

describe('field-source badges (R-SRC)', () => {
  it('every column header of every screen carries its field-source badge', () => {
    const unbadged: string[] = [];
    for (const file of componentFiles()) {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- the repository's own source files
      const source = readFileSync(file, 'utf8');
      // `<th\b`: never `<thead>` (h and e are both word characters, so no boundary between them).
      // A self-closing `<th … />` is an empty header; matching it first keeps the lazy body from
      // running on to the next table's `</th>`.
      for (const match of source.matchAll(/<th\b[^>]*\/>|<th\b[^>]*>([\s\S]*?)<\/th>/g)) {
        if (match[0].endsWith('/>')) continue;
        // A row header (scope="row") labels a value in a printed document such as the payslip: it is no
        // column of a screen's list, so it carries no column badge; the document's own fields are tested there.
        if (/^<th\b[^>]*\bscope="row"/.test(match[0])) continue;
        const body = match[1] ?? '';
        // Exempt: an empty header (an action column named by aria-label) and a header that is only a
        // control (a select-all checkbox): neither is a data field.
        const trimmed = body.trim();
        const controlOnly = trimmed.startsWith('<input') && trimmed.endsWith('/>');
        if (trimmed === '' || controlOnly || /<Src\b/.test(body)) continue;
        unbadged.push(`${file.slice(MODULES.length + 1)}: ${body.replace(/\s+/g, ' ').trim().slice(0, 60)}`);
      }
    }
    expect(unbadged).toEqual([]);
  });

  it('the badge letter is hidden from assistive technology: a header is read by its name alone', () => {
    render(
      <I18nProvider>
        <table>
          <thead>
            <tr>
              <Th label="Owner" s="ENT" />
              <th>
                Status
                <Src s="SYS" />
              </th>
            </tr>
          </thead>
        </table>
      </I18nProvider>,
    );
    expect(screen.getByRole('columnheader', { name: 'Owner' })).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'Status' })).toBeTruthy();
    // The meaning stays available as the tooltip.
    expect(document.querySelector('.s-ENT')?.getAttribute('title')).toBeTruthy();
  });
});
