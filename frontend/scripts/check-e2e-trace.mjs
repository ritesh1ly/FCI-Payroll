// Quality gate: every Playwright test traces to the SRS / FRS and every in-scope KB scenario of the
// built modules has an E2E test or a recorded reason (scripts/e2e_trace.py; developer playbook §6).
// Runs the Python gate with whichever interpreter the machine has.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const script = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'scripts',
  'e2e_trace.py',
);
// Windows ships a python3 Store stub, so try python first there.
const interpreters = process.platform === 'win32' ? ['python', 'python3'] : ['python3', 'python'];
for (const python of interpreters) {
  const run = spawnSync(python, [script, '--check'], {
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (run.error && run.error.code === 'ENOENT') continue;
  if (run.status === 9009) continue; // Windows: interpreter not found
  process.exit(run.status ?? 1);
}
console.error('check:e2e-trace needs Python 3 (python3 or python on the PATH)');
process.exit(1);
