// Runs Playwright by suite from e2e/suites.json (e2e/README.md "Suites"): up to 8 workers, no two suites that
// share state at once, the serial lane last and one suite at a time. Every step
// runs whatever the one before it did. The stack needs -E2EParallelUsers (the clones).
//
//   node scripts/e2e-run.mjs all                        every suite (e2e/0[1-8]*, menus 1-20)
//   node scripts/e2e-run.mjs functional                 the same as all (the wireframe parity specs are gone, OD-157)
//   node scripts/e2e-run.mjs suite <name> ...           those suites and what they depend on
//   node scripts/e2e-run.mjs domain <name> ...          every suite with a spec of that domain
//   node scripts/e2e-run.mjs affected [--base <ref>] [--files a,b]    what a change touches, with related suites
//   node scripts/e2e-run.mjs failed                     the last run's failures, with related suites
//   --dry-run prints the plan and starts nothing. E2E_WORKERS=4: fewer workers than 8.
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { impactOf, plan, withRelated } from './e2e-plan.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(readFileSync(path.join(root, 'e2e/suites.json'), 'utf8'));
const stateDir = path.join(root, 'test-results/lanes');
const FAILED_FILE = path.join(stateDir, 'failed-specs.json');

function take(args, flag) {
  const at = args.indexOf(flag);
  if (at < 0) return undefined;
  const [, value] = args.splice(at, 2);
  return value;
}

function changedFiles(args) {
  const list = take(args, '--files');
  if (list) return list.split(',').filter(Boolean);
  const base = take(args, '--base') ?? 'HEAD';
  const git = (...more) => execFileSync('git', more, { cwd: root, encoding: 'utf8' });
  const top = git('rev-parse', '--show-toplevel').trim();
  const out =
    git('-C', top, 'diff', '--name-only', base) +
    git('-C', top, 'ls-files', '--others', '--exclude-standard');
  return out.split(/\r?\n/).filter(Boolean);
}

function select(command, args) {
  const byName = (names) => {
    const unknown = names.filter((n) => !manifest.suites.some((s) => s.name === n));
    if (unknown.length) throw new Error(`unknown suite: ${unknown.join(', ')}`);
    return names;
  };
  if (command === 'all' || command === 'functional') return manifest.suites;
  if (command === 'suite') {
    const names = args.splice(0, args.length).filter((a) => {
      if (a.startsWith('-')) {
        args.push(a);
        return false;
      }
      return true;
    });
    const chosen = new Set(byName(names));
    return withRelated(
      { ...manifest, suites: manifest.suites.map((s) => ({ ...s, dependents: [] })) },
      chosen,
    );
  }
  if (command === 'domain') {
    const names = args.splice(0, args.length).filter((a) => {
      if (a.startsWith('-')) {
        args.push(a);
        return false;
      }
      return true;
    });
    const unknown = names.filter((n) => !manifest.domains[n]);
    if (unknown.length)
      throw new Error(
        `unknown domain: ${unknown.join(', ')}; known: ${Object.keys(manifest.domains).join(', ')}`,
      );
    const chosen = new Set(names.flatMap((n) => manifest.domains[n]));
    return withRelated(
      { ...manifest, suites: manifest.suites.map((s) => ({ ...s, dependents: [] })) },
      chosen,
    );
  }
  if (command === 'affected') {
    const files = changedFiles(args);
    const hits = files.map((file) => ({ file, ...impactOf(manifest, file) }));
    for (const hit of hits) console.log(`[e2e] ${hit.file}: ${hit.suites.length} suites (${hit.why})`);
    return withRelated(manifest, new Set(hits.flatMap((h) => h.suites)));
  }
  if (command === 'failed') {
    if (!existsSync(FAILED_FILE)) throw new Error('no failed-specs.json: run a suite first');
    const specs = JSON.parse(readFileSync(FAILED_FILE, 'utf8'));
    const names = new Set(
      manifest.suites.filter((s) => s.specs.some((f) => specs.includes(f))).map((s) => s.name),
    );
    return withRelated(manifest, names);
  }
  throw new Error(`unknown command ${command}: all, functional, suite, domain, affected, failed`);
}

/** The projects and file filters of a step. */
function projects(step) {
  return { names: step.suites.map((s) => `suite-${s.name}`), files: [] };
}

function run(step, index, extra, failed) {
  const { names, files } = projects(step);
  const reportFile = path.join(stateDir, `step-${index}.json`);
  const started = Date.now();
  const { status, error } = spawnSync(
    process.execPath,
    [
      path.join(root, 'node_modules/@playwright/test/cli.js'),
      'test',
      ...names.map((n) => `--project=${n}`),
      '--reporter=list,json',
      ...files,
      ...extra,
    ],
    {
      cwd: root,
      stdio: 'inherit',
      env: { ...process.env, E2E_LANES: step.kind, PLAYWRIGHT_JSON_OUTPUT_NAME: reportFile },
    },
  );
  if (error) console.error(error.message);
  if (existsSync(reportFile)) {
    const visit = (suite, file) => {
      suite.suites?.forEach((s) => visit(s, s.file ?? file));
      for (const spec of suite.specs ?? []) {
        if (spec.ok === false) failed.add(`e2e/${(spec.file ?? file).split('\\').join('/')}`);
      }
    };
    JSON.parse(readFileSync(reportFile, 'utf8')).suites?.forEach((s) => visit(s, s.file));
  }
  const seconds = Math.round((Date.now() - started) / 1000);
  console.log(
    `[e2e] ${step.kind} ${step.suites.map((s) => s.name).join(' ')}: exit ${status} in ${seconds} s`,
  );
  return status ?? 1;
}

const args = process.argv.slice(2);
const command = args.shift() ?? 'all';
const dryRun = args.includes('--dry-run');
if (dryRun) args.splice(args.indexOf('--dry-run'), 1);
let chosen;
try {
  chosen = select(command, args);
} catch (problem) {
  console.error(problem.message);
  process.exit(2);
}
const steps = plan(manifest, chosen);
const seconds = (step) => Math.max(...step.suites.map((s) => s.estimatedSeconds));
const total = Math.round(steps.reduce((n, s) => n + seconds(s), 0));
console.log(
  `[e2e] ${chosen.length} suites, ${chosen.reduce((n, s) => n + s.tests, 0)} tests, ${steps.length} steps, about ${Math.round(total / 60)} min`,
);
steps.forEach((step, i) =>
  console.log(`[e2e] step ${i + 1} ${step.kind}: ${step.suites.map((s) => s.name).join(' ')}`),
);
if (dryRun || steps.length === 0) process.exit(0);

mkdirSync(stateDir, { recursive: true });
const failed = new Set();
let exit = 0;
steps.forEach((step, i) => {
  const code = run(step, i + 1, args, failed);
  if (code !== 0) exit = code;
});
writeFileSync(FAILED_FILE, `${JSON.stringify([...failed].sort(), null, 1)}\n`);
console.log(`[e2e] ${failed.size} specs failed; npm run e2e:failed runs them with their related suites`);
process.exit(exit);
