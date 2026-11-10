// Stryker per module (shard), within one CPU budget, skipping the modules that did not change
// (docs/quality/QUALITY_GATES.md "Mutation runs, sharded"; developer playbook, learnings log 2026-11-08).
//
// Why: the full run (`npm run mutation`) starts a whole `vitest run` of EVERY business-rule test for
// each mutant, with Stryker's default concurrency (cores - 1) on top of Vitest's own worker pool: about
// 5 hours at 90 % CPU. Here each module (src/modules/<m>/business) is one Stryker run whose mutants run
// only the rule tests that import that module's business files (static import graph of
// tests/unit/*rules*.test.ts), in one Vitest worker each; the shards share one budget of mutant
// workers, run below normal priority, and a shard whose inputs are unchanged since its last passing run
// is not run again.
//
//   npm run mutation:shards                       all shards (the changed ones), then the merged report
//   npm run mutation:shards -- --dry-run          list shards, budget and what would be skipped; runs nothing
//   npm run mutation:shards -- --force            run every shard, each with Stryker --force
//   npm run mutation:shards -- --only cpf,tax     only these shards (the others keep their recorded result)
//   npm run mutation:shards -- --budget 6         mutant workers in total (default 60 % of the logical cores;
//                                                 also --budget 40%, or FCI_MUTATION_BUDGET)
//   npm run mutation:shards -- --per-shard 2      at most this many workers per shard (default 4)
//
// What is mutated and the thresholds are those of stryker.config.json (break 95, per shard and overall);
// the shards' mutate globs partition its `mutate` glob exactly. Output in reports/mutation/shards
// (git-ignored): <module>/mutation.{json,html}, <module>/stryker.log, state.json (input hashes and
// scores), merged-mutation.{json,html}.
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const rel = (abs) => path.relative(root, abs).split(path.sep).join('/');
const SHARD_DIR = path.join(root, 'reports', 'mutation', 'shards');
const STATE_FILE = path.join(SHARD_DIR, 'state.json');
const TEST_DIR = path.join(root, 'tests', 'unit');
// The rule tests of vitest.mutation.config.ts (its default `include`, tests/unit/*rules*.test.ts).
const RULE_TEST = /^[^/\\]*rules[^/\\]*\.test\.ts$/;
// A change to any of these can change every result: every shard runs again, with --force.
const TOOLING = [
  'stryker.config.json',
  'vitest.mutation.config.ts',
  'package-lock.json',
  'tsconfig.json',
  'scripts/mutation-shards.mjs',
];
const STATE_VERSION = 1;

// ---------------------------------------------------------------- options

function parseArgs(argv) {
  const opts = { dryRun: false, force: false, only: null, budget: null, perShard: 4 };
  for (let i = 0; i < argv.length; i += 1) {
    const [flag, inline] = argv[i].split('=', 2);
    const value = () => inline ?? argv[(i += 1)];
    if (flag === '--dry-run') opts.dryRun = true;
    else if (flag === '--force') opts.force = true;
    else if (flag === '--only')
      opts.only = value()
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
    else if (flag === '--budget') opts.budget = value();
    else if (flag === '--per-shard') opts.perShard = Number(value());
    else {
      console.error(`Unknown option ${argv[i]} (see the header of scripts/mutation-shards.mjs)`);
      process.exit(2);
    }
  }
  if (!Number.isInteger(opts.perShard) || opts.perShard < 1) {
    console.error('--per-shard must be a whole number of at least 1');
    process.exit(2);
  }
  return opts;
}

/** Mutant workers in total: N, or P % of the logical cores; default 60 %. Always at least 1. */
function resolveBudget(raw) {
  const cores = os.availableParallelism();
  const spec = String(raw ?? process.env.FCI_MUTATION_BUDGET ?? '60%').trim();
  const pct = /^(\d+(?:\.\d+)?)%$/.exec(spec);
  const n = pct ? Math.floor((cores * Number(pct[1])) / 100) : Number(spec);
  if (!Number.isFinite(n)) {
    console.error(`Budget "${spec}" is neither a number nor a percentage`);
    process.exit(2);
  }
  return { cores, spec, budget: Math.max(1, Math.min(cores, Math.floor(n))) };
}

// ---------------------------------------------------------------- static import graph

const IMPORT_RE =
  /(?:^|[\n;])\s*(import|export)\s+(type\s+)?(?:[\w*{}\s,$]+?\s+from\s+)?['"]([^'"]+)['"]|import\(\s*['"]([^'"]+)['"]\s*\)/g;
const EXTENSIONS = ['', '.ts', '.tsx', '.mts', '.js', '.mjs', '.json', '/index.ts', '/index.tsx'];

function resolveSpecifier(spec, fromFile) {
  let base;
  if (spec.startsWith('@/')) base = path.join(root, 'src', spec.slice(2));
  else if (spec.startsWith('.')) base = path.resolve(path.dirname(fromFile), spec);
  else return null; // a package: covered by package-lock.json in TOOLING
  for (const ext of EXTENSIONS) {
    const candidate = base + ext;
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
  }
  return null;
}

/** Imports of one file: { runtime: Set<abs>, all: Set<abs> } (all includes `import type`). */
const importCache = new Map();
function importsOf(file) {
  if (importCache.has(file)) return importCache.get(file);
  const result = { runtime: new Set(), all: new Set() };
  importCache.set(file, result);
  if (!/\.(ts|tsx|mts|js|mjs)$/.test(file)) return result;
  const text = fs.readFileSync(file, 'utf8');
  for (const m of text.matchAll(IMPORT_RE)) {
    const spec = m[3] ?? m[4];
    const target = resolveSpecifier(spec, file);
    if (!target) continue;
    result.all.add(target);
    if (!m[2]) result.runtime.add(target);
  }
  return result;
}

/** Every file reachable from `start`, following runtime imports only or all imports. */
function reach(start, kind) {
  const seen = new Set();
  const stack = [start];
  while (stack.length) {
    const file = stack.pop();
    if (seen.has(file)) continue;
    seen.add(file);
    for (const next of importsOf(file)[kind]) stack.push(next);
  }
  seen.delete(start);
  return seen;
}

// ---------------------------------------------------------------- shards

function listFiles(dir, accept) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((d) => d.isFile() && accept(d.name))
    .map((d) => path.join(d.parentPath, d.name))
    .sort();
}

/** True when a file declares types only: Stryker finds no mutant in it, so it needs no test. */
function typesOnly(file) {
  const code = fs
    .readFileSync(file, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '');
  return !/\b(function|const|let|var|class|enum)\b|=>|export\s+default\b/.test(code);
}

function hashFiles(files) {
  const h = createHash('sha256');
  for (const f of [...files].sort()) {
    h.update(rel(f)).update('\0').update(fs.readFileSync(f)).update('\0');
  }
  return h.digest('hex').slice(0, 24);
}

function buildShards() {
  const modulesDir = path.join(root, 'src', 'modules');
  const tests = fs
    .readdirSync(TEST_DIR)
    .filter((n) => RULE_TEST.test(n))
    .sort()
    .map((n) => path.join(TEST_DIR, n));
  const testReach = new Map(tests.map((t) => [t, { runtime: reach(t, 'runtime'), all: reach(t, 'all') }]));
  const tooling = TOOLING.map((f) => path.join(root, f)).filter((f) => fs.existsSync(f));
  const shards = [];
  const untested = [];
  for (const name of fs.readdirSync(modulesDir).sort()) {
    const businessDir = path.join(modulesDir, name, 'business');
    // Same files as stryker.config.json "mutate": src/modules/*/business/**/*.ts
    const business = listFiles(businessDir, (n) => n.endsWith('.ts'));
    if (business.length === 0) continue;
    const mine = new Set(business);
    const shardTests = tests.filter((t) => [...testReach.get(t).runtime].some((f) => mine.has(f)));
    for (const b of business) {
      if (!typesOnly(b) && !shardTests.some((t) => testReach.get(t).runtime.has(b))) untested.push(rel(b));
    }
    const support = new Set(tooling);
    for (const t of shardTests) {
      support.add(t);
      for (const f of testReach.get(t).all) if (!mine.has(f)) support.add(f);
    }
    for (const b of business) for (const f of reach(b, 'all')) if (!mine.has(f)) support.add(f);
    const bytes = business.reduce((sum, f) => sum + fs.statSync(f).size, 0);
    shards.push({
      module: name,
      business,
      tests: shardTests,
      mutatedHash: hashFiles(business),
      supportHash: hashFiles(support),
      // Run-time estimate for the queue: mutants grow with the code, each costs a run of the tests.
      weight: bytes * Math.max(1, shardTests.length),
      bytes,
    });
  }
  return { shards, untested };
}

// ---------------------------------------------------------------- state and decisions

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
}

function shardPaths(module) {
  const dir = path.join(SHARD_DIR, module);
  return {
    dir,
    config: path.join(dir, 'stryker.config.json'),
    json: path.join(dir, 'mutation.json'),
    html: path.join(dir, 'mutation.html'),
    incremental: path.join(dir, 'stryker-incremental.json'),
    log: path.join(dir, 'stryker.log'),
  };
}

/** skip | incremental | force, with the reason shown in the plan. */
function decide(shard, previous, opts) {
  const p = shardPaths(shard.module);
  if (opts.only && !opts.only.includes(shard.module)) {
    return previous ? { action: 'skip', reason: 'not in --only (recorded result kept)' } : null;
  }
  if (opts.force) return { action: 'force', reason: '--force' };
  if (!previous || !fs.existsSync(p.json)) return { action: 'force', reason: 'no recorded run' };
  if (previous.supportHash !== shard.supportHash) {
    // Stryker's incremental mode cannot see test changes with the command runner (stryker.config.json
    // incremental_comment): a changed test or helper reruns the whole shard.
    return { action: 'force', reason: 'tests, imported code or tooling changed' };
  }
  if (previous.mutatedHash !== shard.mutatedHash) {
    return { action: 'incremental', reason: 'business files changed (tests unchanged)' };
  }
  if (previous.status !== 'passed') return { action: 'incremental', reason: 'last run did not pass' };
  return { action: 'skip', reason: `unchanged since ${previous.finishedAt}` };
}

// ---------------------------------------------------------------- scores

function countReport(report) {
  const c = { killed: 0, timeout: 0, survived: 0, noCoverage: 0, other: 0 };
  for (const file of Object.values(report?.files ?? {})) {
    for (const m of file.mutants ?? []) {
      if (m.status === 'Killed') c.killed += 1;
      else if (m.status === 'Timeout') c.timeout += 1;
      else if (m.status === 'Survived') c.survived += 1;
      else if (m.status === 'NoCoverage') c.noCoverage += 1;
      else c.other += 1; // CompileError, RuntimeError, Ignored: not in the score, as in Stryker
    }
  }
  return c;
}

/** Stryker's mutation score: detected / (detected + undetected), in %; 100 when nothing is valid. */
function score(c) {
  const detected = c.killed + c.timeout;
  const valid = detected + c.survived + c.noCoverage;
  return valid === 0 ? 100 : (100 * detected) / valid;
}

function sumCounts(list) {
  return list.reduce(
    (a, c) => {
      for (const k of Object.keys(a)) a[k] += c[k] ?? 0;
      return a;
    },
    { killed: 0, timeout: 0, survived: 0, noCoverage: 0, other: 0 },
  );
}

// ---------------------------------------------------------------- running

function lowerPriority(pid) {
  try {
    // Below normal on Windows, nice 10 elsewhere; children inherit it.
    os.setPriority(pid, os.constants.priority.PRIORITY_BELOW_NORMAL);
  } catch {
    // Not allowed here: the run continues at the current priority.
  }
}

function writeShardConfig(base, shard, concurrency, p) {
  const config = {};
  for (const [k, v] of Object.entries(base))
    if (!k.startsWith('_') && !k.endsWith('_comment') && k !== '$schema') config[k] = v;
  Object.assign(config, {
    mutate: [`src/modules/${shard.module}/business/**/*.ts`],
    concurrency,
    commandRunner: { command: 'node node_modules/vitest/vitest.mjs run --config vitest.mutation.config.ts' },
    reporters: ['clear-text', 'json', 'html'],
    htmlReporter: { fileName: rel(p.html) },
    jsonReporter: { fileName: rel(p.json) },
    incrementalFile: rel(p.incremental),
    tempDirName: `.stryker-tmp/${shard.module}`,
    // Other shards' sandboxes and the reports must not be copied into this shard's sandbox.
    ignorePatterns: [...(base.ignorePatterns ?? []), '/.stryker-tmp', '/reports'],
  });
  fs.mkdirSync(p.dir, { recursive: true });
  fs.writeFileSync(p.config, `${JSON.stringify(config, null, 2)}\n`);
}

const running = new Set();

function killTree(child) {
  if (child.exitCode !== null) return;
  if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F']);
  else child.kill('SIGTERM');
}

function runShard(base, shard, plan, concurrency) {
  const p = shardPaths(shard.module);
  writeShardConfig(base, shard, concurrency, p);
  const args = [
    path.join(root, 'node_modules', '@stryker-mutator', 'core', 'bin', 'stryker.js'),
    'run',
    rel(p.config),
  ];
  if (plan.action === 'force') args.push('--force');
  const out = fs.openSync(p.log, 'w');
  const started = Date.now();
  console.log(`[${shard.module}] start, ${concurrency} worker(s), ${plan.action} (${plan.reason})`);
  const child = spawn(process.execPath, args, {
    cwd: root,
    stdio: ['ignore', out, out],
    env: {
      ...process.env,
      // Read by vitest.mutation.config.ts: run only this shard's rule tests.
      FCI_MUTATION_TESTS: shard.tests.map(rel).join(','),
    },
  });
  lowerPriority(child.pid);
  running.add(child);
  return new Promise((resolve) => {
    child.on('exit', (code) => {
      running.delete(child);
      fs.closeSync(out);
      const minutes = ((Date.now() - started) / 60000).toFixed(1);
      const report = readJson(p.json, null);
      const counts = report ? countReport(report) : null;
      resolve({ code, minutes, counts, fresh: report !== null && fs.statSync(p.json).mtimeMs >= started });
    });
  });
}

/** Runs the queue, largest first, never more than `budget` mutant workers at once. */
async function runQueue(base, queue, budget, perShard, onDone) {
  let free = budget;
  const pending = [...queue].sort((a, b) => b.shard.weight - a.shard.weight);
  const active = new Set();
  await new Promise((resolveAll) => {
    const pump = () => {
      // A shard starts only with its full share of workers (or alone): handing the largest shard the one worker left
      // made tax run its 1,148 mutants one after another (82 min; 26 min with three workers, 2026-11-08).
      const share = Math.min(perShard, budget);
      while (pending.length && free > 0 && (free >= share || active.size === 0)) {
        const item = pending.shift();
        const workers = Math.min(share, free);
        free -= workers;
        const job = runShard(base, item.shard, item.plan, workers).then(async (result) => {
          free += workers;
          active.delete(job);
          await onDone(item, result);
          pump();
        });
        active.add(job);
      }
      if (!pending.length && !active.size) resolveAll();
    };
    pump();
  });
}

// ---------------------------------------------------------------- merged report

function escapeHtmlTags(json) {
  return json.replace(/</g, '<"+"');
}

function writeMerged(base, shards) {
  const merged = {
    schemaVersion: '2',
    thresholds: { high: base.thresholds.high, low: base.thresholds.low },
    files: {},
  };
  for (const s of shards) {
    const report = readJson(shardPaths(s.module).json, null);
    if (report?.schemaVersion) merged.schemaVersion = report.schemaVersion;
    Object.assign(merged.files, report?.files ?? {});
  }
  merged.projectRoot = root;
  fs.writeFileSync(path.join(SHARD_DIR, 'merged-mutation.json'), JSON.stringify(merged));
  const elements = path.join(
    root,
    'node_modules',
    'mutation-testing-elements',
    'dist',
    'mutation-test-elements.js',
  );
  if (fs.existsSync(elements)) {
    const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><title>Mutation (shards)</title><script>${fs.readFileSync(elements, 'utf8')}</script></head><body><mutation-test-report-app titlePostfix="Stryker shards"></mutation-test-report-app><script>document.querySelector('mutation-test-report-app').report = ${escapeHtmlTags(JSON.stringify(merged))};</script></body></html>`;
    fs.writeFileSync(path.join(SHARD_DIR, 'merged-mutation.html'), html);
  }
  return countReport(merged);
}

// ---------------------------------------------------------------- main

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const { cores, spec, budget } = resolveBudget(opts.budget);
  const perShard = Math.min(opts.perShard, budget);
  const base = readJson(path.join(root, 'stryker.config.json'), null);
  const breakAt = base.thresholds.break;
  const { shards, untested } = buildShards();

  if (opts.only) {
    const unknown = opts.only.filter((m) => !shards.some((s) => s.module === m));
    if (unknown.length) {
      console.error(`--only: no shard named ${unknown.join(', ')}`);
      process.exit(2);
    }
  }
  // Guard: a business file no rule test imports scores 0 % in every run, sharded or full (playbook
  // 2026-10-26). Its mutants are still mutated (same set as the full run); the gate fails at the end.
  if (untested.length) {
    console.error('GUARD: business files with runtime code that no tests/unit/*rules*.test.ts imports:');
    for (const f of untested) console.error(`  ${f}`);
    console.error(
      'Give each its own <name>-rules.test.ts (developer playbook §4); the gate fails until then.',
    );
  }

  const state = readJson(STATE_FILE, { version: STATE_VERSION, shards: {} });
  if (state.version !== STATE_VERSION) state.shards = {};
  const plans = shards.map((s) => ({ shard: s, plan: decide(s, state.shards[s.module], opts) }));

  console.log(
    `CPU budget: ${budget} mutant worker(s) of ${cores} logical cores (${spec}); at most ${perShard} per shard; ` +
      `one Vitest worker per mutant run; below-normal priority.`,
  );
  console.log('shard'.padEnd(15) + 'files  tests  action       reason');
  for (const { shard, plan } of plans) {
    const action = plan ? plan.action : 'not run';
    const reason = plan ? plan.reason : 'not in --only, no recorded result';
    console.log(
      `${shard.module.padEnd(15)}${String(shard.business.length).padStart(5)}  ${String(shard.tests.length).padStart(5)}  ${action.padEnd(12)} ${reason}`,
    );
  }
  const queue = plans.filter((x) => x.plan && x.plan.action !== 'skip');
  console.log(`${queue.length} shard(s) to run, ${plans.length - queue.length} skipped or not run.`);
  if (opts.dryRun) {
    let free = budget;
    const wave = [...queue]
      .sort((a, b) => b.shard.weight - a.shard.weight)
      .flatMap((x) => {
        // The same rule as runQueue: a shard starts with its full share of workers or waits.
        const share = Math.min(perShard, budget);
        if (free < share) return [];
        const w = share;
        free -= w;
        return [`${x.shard.module} x${w}`];
      });
    console.log(`First wave (largest first): ${wave.join(', ') || 'none'}`);
    for (const { shard } of queue) {
      console.log(`  ${shard.module}: ${shard.tests.map((t) => path.basename(t)).join(', ')}`);
    }
    return;
  }

  lowerPriority(process.pid);
  const stop = () => {
    for (const child of running) killTree(child);
    process.exit(130);
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  fs.mkdirSync(SHARD_DIR, { recursive: true });

  const failures = untested.length ? ['untested business files (guard)'] : [];
  await runQueue(base, queue, budget, perShard, async ({ shard }, result) => {
    const valid = result.fresh && result.counts;
    const s = valid ? score(result.counts) : 0;
    const passed = Boolean(valid) && s >= breakAt;
    state.shards[shard.module] = {
      mutatedHash: shard.mutatedHash,
      supportHash: shard.supportHash,
      status: passed ? 'passed' : 'failed',
      score: Number(s.toFixed(2)),
      counts: result.counts,
      exitCode: result.code,
      finishedAt: new Date().toISOString(),
    };
    fs.writeFileSync(STATE_FILE, `${JSON.stringify(state, null, 2)}\n`);
    if (!passed) failures.push(shard.module);
    console.log(
      `[${shard.module}] ${valid ? `${s.toFixed(2)} %` : `no report (exit ${result.code}), see ${rel(shardPaths(shard.module).log)}`} ` +
        `${passed ? 'pass' : 'FAIL'} in ${result.minutes} min`,
    );
  });

  // Per shard (run now or recorded) and overall, against the same break threshold.
  console.log('\nshard'.padEnd(16) + 'score    killed  timeout  survived  noCov  status');
  for (const { shard } of plans) {
    const rec = state.shards[shard.module];
    if (!rec) {
      console.log(`${shard.module.padEnd(15)}  -  not run yet`);
      failures.push(shard.module);
      continue;
    }
    const c = rec.counts ?? {};
    console.log(
      `${shard.module.padEnd(15)}${String(rec.score).padStart(6)} %  ${String(c.killed ?? '-').padStart(6)}  ${String(c.timeout ?? '-').padStart(7)}  ${String(c.survived ?? '-').padStart(8)}  ${String(c.noCoverage ?? '-').padStart(5)}  ${rec.status}`,
    );
    if (rec.status !== 'passed' && !failures.includes(shard.module)) failures.push(shard.module);
  }
  // Overall from the shards' reports on disk (the merged report), cross-checked with the recorded counts.
  const overallCounts = writeMerged(base, shards);
  const recorded = sumCounts(shards.map((s) => state.shards[s.module]?.counts ?? {}));
  if (recorded.killed + recorded.timeout !== overallCounts.killed + overallCounts.timeout) {
    console.error('A shard report on disk differs from its recorded counts: rerun that shard.');
    failures.push('report mismatch');
  }
  const overall = score(overallCounts);
  console.log(
    `Overall ${overall.toFixed(2)} % (break ${breakAt}); report ${rel(path.join(SHARD_DIR, 'merged-mutation.html'))}`,
  );
  if (failures.length || overall < breakAt) {
    console.error(`Mutation gate FAILED: ${failures.length ? failures.join(', ') : 'overall score'}`);
    process.exit(1);
  }
}

main().catch((error) => {
  console.error(error);
  for (const child of running) killTree(child);
  process.exit(1);
});
