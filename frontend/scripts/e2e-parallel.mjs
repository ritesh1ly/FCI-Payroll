// Runs the Playwright suite as parallel groups, one group per stack (developer playbook §6).
//
// Specs share the demo users (single-session rule, sign-in rate limits) and the payroll specs depend
// on each other in order (the locked cycle feeds Module 01), so one stack runs one worker. Speed comes
// from several stacks: each group of business modules gets its own stack (own database, sessions and
// rate-limit buckets) and its own Playwright process; their blob reports are merged at the end.
//
//   E2E_STACKS="https://localhost:8643|C:/…/runtime-a/logs/online/api.json,https://localhost:8743|C:/…/runtime-b/logs/online/api.json"
//   E2E_IGNORE_TLS_ERRORS=1 node scripts/e2e-parallel.mjs
//
// Stacks: scripts/local/deploy-local.ps1 -PortOffset 200 -Runtime <dir-a>, then
//         scripts/local/deploy-local.ps1 -PortOffset 300 -Runtime <dir-b> -SkipBuild
// With fewer stacks than groups, groups take turns on the stacks (same order as the serial suite).
// Output: playwright-report/ (HTML) and playwright-results.json (read by scripts/quality_report.py).
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
// Balanced by run time (sign-in and access ~195 s, the rest ~200 s). Folders keep their serial
// order inside a group: the cycle spec locks the period the Module 01 specs use.
const GROUPS = [
  { name: 'sign-in-and-access', paths: ['e2e/01-sign-in-and-access'] },
  {
    name: 'platform-masters-approvals-payroll',
    paths: [
      'e2e/02-platform',
      'e2e/03-masters',
      'e2e/04-approvals',
      'e2e/05-payroll-processing',
      'e2e/06-exceptions-and-adjustments',
    ],
  },
];

const stacks = (process.env.E2E_STACKS ?? '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)
  .map((s) => {
    const [baseUrl, apiLog] = s.split('|');
    return { baseUrl, apiLog };
  });
if (stacks.length === 0) {
  console.error('E2E_STACKS is empty: "<base url>|<online api.json log>,…" (one per stack)');
  process.exit(2);
}

const blobDir = path.join(root, 'blob-report');
fs.rmSync(blobDir, { recursive: true, force: true });
const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';

/** Runs the groups assigned to one stack, one after the other. */
function runOnStack(stack, groups) {
  return groups.reduce(
    (previous, group) =>
      previous.then(
        (failures) =>
          new Promise((resolve) => {
            const started = Date.now();
            console.log(`[${group.name}] → ${stack.baseUrl}`);
            const child = spawn(
              npx,
              [
                'playwright',
                'test',
                ...group.paths,
                '--reporter=blob,line',
                // Own artifacts folder: each run empties its folder at start.
                `--output=test-results/${group.name}`,
              ],
              {
                cwd: root,
                shell: process.platform === 'win32',
                stdio: ['ignore', 'pipe', 'pipe'],
                env: {
                  ...process.env,
                  E2E_BASE_URL: stack.baseUrl,
                  E2E_API_LOG: stack.apiLog,
                  // Own directory per process: the blob reporter empties its directory at start.
                  PLAYWRIGHT_BLOB_OUTPUT_DIR: path.join(blobDir, 'groups', group.name),
                  PLAYWRIGHT_BLOB_OUTPUT_NAME: `${group.name}.zip`,
                },
              },
            );
            const prefix = (chunk) =>
              chunk
                .toString()
                .split('\n')
                .filter(Boolean)
                .forEach((line) => console.log(`[${group.name}] ${line}`));
            child.stdout.on('data', prefix);
            child.stderr.on('data', prefix);
            child.on('close', (code) => {
              console.log(
                `[${group.name}] exit ${code} after ${Math.round((Date.now() - started) / 1000)} s`,
              );
              resolve(failures + (code === 0 ? 0 : 1));
            });
          }),
      ),
    Promise.resolve(0),
  );
}

const assignment = stacks.map(() => []);
GROUPS.forEach((group, i) => assignment[i % stacks.length].push(group));
const started = Date.now();
const failures = (await Promise.all(stacks.map((stack, i) => runOnStack(stack, assignment[i])))).reduce(
  (a, b) => a + b,
  0,
);

// One HTML report and one JSON result for all groups.
const mergeDir = path.join(blobDir, 'all');
fs.mkdirSync(mergeDir, { recursive: true });
let missing = 0;
for (const group of GROUPS) {
  const zip = path.join(blobDir, 'groups', group.name, `${group.name}.zip`);
  if (fs.existsSync(zip)) fs.copyFileSync(zip, path.join(mergeDir, `${group.name}.zip`));
  else {
    missing += 1;
    console.error(`[${group.name}] no blob report: its results are missing from the merge`);
  }
}
spawnSync(npx, ['playwright', 'merge-reports', '--reporter=html', mergeDir], {
  cwd: root,
  shell: process.platform === 'win32',
  stdio: 'inherit',
  env: { ...process.env, PLAYWRIGHT_HTML_OPEN: 'never' },
});
const json = spawnSync(npx, ['playwright', 'merge-reports', '--reporter=json', mergeDir], {
  cwd: root,
  shell: process.platform === 'win32',
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
});
fs.writeFileSync(path.join(root, 'playwright-results.json'), json.stdout ?? '');
console.log(
  `e2e-parallel: ${GROUPS.length} groups on ${stacks.length} stack(s), ${failures} failed group(s), ` +
    `${Math.round((Date.now() - started) / 1000)} s`,
);
process.exit(failures + missing === 0 ? 0 : 1);
