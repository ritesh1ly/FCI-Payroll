import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { messages } from '@/generated/messages';

/**
 * SRS-G-40 (English and Hindi) on C-13: the job history and detail print each job parameter by its label
 * (`c13.param.<name>`). A parameter without one showed its raw key on C-13 (role-screen matrix, 2026-11-08:
 * the simulation job's simulationId). The parameter names are read from the backend source: the job
 * catalog's ParamDef entries and each batch job's required parameters, so a new parameter without a label
 * fails here, not on a screen.
 */
const JAVA = join(__dirname, '..', '..', '..', 'backend', 'src', 'main', 'java');

function javaFiles(dir: string): string[] {
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- the repository's own source folders
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- the repository's own source folders
    return statSync(path).isDirectory() ? javaFiles(path) : name.endsWith('.java') ? [path] : [];
  });
}

function parameterNames(): Set<string> {
  const names = new Set<string>();
  for (const file of javaFiles(JAVA)) {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- the repository's own source files
    const source = readFileSync(file, 'utf8');
    for (const m of source.matchAll(/ParamDef\(\s*"([A-Za-z]+)"/g)) names.add(m[1] as string);
    // A batch job's required parameters: new RequestParametersValidator(List.of("a", "b", ...)).
    for (const list of source.matchAll(/RequestParametersValidator\(\s*List\.of\(([^)]*)\)/g)) {
      for (const p of (list[1] as string).matchAll(/"([A-Za-z]+)"/g)) names.add(p[1] as string);
    }
  }
  return names;
}

describe('C-13 job parameter labels (SRS-G-40)', () => {
  it('every job parameter the backend declares has a label in English and Hindi', () => {
    const names = parameterNames();
    expect(names.has('simulationId'), 'the simulation job is read').toBe(true);
    const missing = [...names].flatMap((name) =>
      (['en', 'hi'] as const)
        .filter((locale) => !(`c13.param.${name}` in messages[locale]))
        .map((locale) => `${locale}: c13.param.${name}`),
    );
    expect(missing).toEqual([]);
  });
});
