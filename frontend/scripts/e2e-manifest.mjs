// Generates e2e/suites.json from the sources: every spec with its menus, screens, modules, endpoints, users,
// shared state and domains; the suites; the impact map (e2e/README.md "Suites"). No browser, no stack.
//
//   node scripts/e2e-manifest.mjs           write e2e/suites.json
//   node scripts/e2e-manifest.mjs --check   fail when it is stale or a rule of the manifest is broken
import { readdirSync, readFileSync, statSync, existsSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  AREAS,
  DEPENDS_ON,
  DOMAINS,
  HUB_SCREENS,
  NO_IMPACT,
  SERIAL,
  SHARED_BACKEND,
  SHARED_FRONTEND,
} from '../e2e/suite-rules.mjs';
import { conflict, waves } from './e2e-plan.mjs';

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.resolve(frontend, '..');
const e2e = path.join(frontend, 'e2e');
const JAVA_ROOT = 'backend/src/main/java/in/gov/fci/payroll';
const posix = (p) => p.split(path.sep).join('/');
const read = (p) => readFileSync(p, 'utf8');
const unique = (list) => [...new Set(list)].sort();

function walk(dir, keep) {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return name === 'node_modules' ? [] : walk(full, keep);
    return keep(name) ? [full] : [];
  });
}

/** Screen -> menu, from the navigation migrations. */
function menus() {
  const order = {};
  const of = {};
  for (const file of walk(path.join(repo, 'db/migration/common'), (n) => n.endsWith('.sql'))) {
    const sql = read(file);
    for (const m of sql.matchAll(/VALUES \('([a-z_]+)', (\d+), /g)) order[m[1]] = Number(m[2]);
    for (const m of sql.matchAll(/'([a-z_]+)', \d+, s\.screen_code[^;]*?screen_code = '([A-Z]-\d\d)'/g)) {
      of[m[2]] ??= m[1];
    }
  }
  return { of, order };
}

/**
 * Screen -> frontend modules, from the screen registry: a screen is a module's component or a local
 * function composing several (I-04 is CPF and Loans), so one screen can have several modules.
 */
function screenModules() {
  const source = read(path.join(frontend, 'src/modules/shell/components/screenRegistry.tsx'));
  const owner = {};
  for (const m of source.matchAll(/import \{([^}]+)\} from '@\/modules\/([a-z]+)'/g)) {
    for (const name of m[1].split(',')) owner[name.replace(/\btype\b/, '').trim()] = m[2];
  }
  const wrappers = {};
  for (const m of source.matchAll(/function (\w+)\([^)]*\)[^{]*\{([\s\S]*?)\n\}/g)) {
    wrappers[m[1]] = [...m[2].matchAll(/<([A-Z]\w+)/g)].map((tag) => tag[1]);
  }
  const of = {};
  for (const m of source.matchAll(/'([A-Z]-\d\d)': \{[^}]*?component: (\w+)/g)) {
    const parts = wrappers[m[2]] ?? [m[2]];
    of[m[1]] = unique(parts.map((name) => owner[name]).filter(Boolean));
  }
  return of;
}

/** The first path segment after /v1 of an API path. */
const apiRoot = (p) => /^\/?(?:api)?\/?v1\/([a-z0-9-]+)/.exec(p)?.[1];

/** API root -> backend packages, from the controllers. */
function apiPackages() {
  const of = {};
  for (const file of walk(path.join(repo, JAVA_ROOT), (n) => n.endsWith('.java'))) {
    const source = read(file);
    if (!/@(Rest)?Controller/.test(source)) continue;
    const pkg = posix(path.relative(path.join(repo, JAVA_ROOT), file)).split('/')[0];
    for (const m of source.matchAll(/"(\/v1\/[^"]*)"/g)) {
      const root = apiRoot(m[1]);
      if (root) (of[root] ??= new Set()).add(pkg);
    }
  }
  return of;
}

/** Frontend module -> API roots it calls. */
function moduleApis() {
  const of = {};
  const base = path.join(frontend, 'src/modules');
  for (const name of readdirSync(base)) {
    const roots = new Set();
    for (const file of walk(path.join(base, name), (n) => /\.tsx?$/.test(n))) {
      for (const m of read(file).matchAll(/[`'"]((?:\$\{API_BASE\})?\/v1\/[^`'"]*)/g)) {
        const root = apiRoot(m[1].replace('${API_BASE}', ''));
        if (root) roots.add(root);
      }
    }
    of[name] = [...roots];
  }
  return of;
}

/** Backend package -> the packages that use it (package-info allowedDependencies), transitively. */
function dependents() {
  const direct = {};
  const base = path.join(repo, JAVA_ROOT);
  for (const name of readdirSync(base)) {
    const info = path.join(base, name, 'package-info.java');
    if (!existsSync(info)) continue;
    const body = /allowedDependencies\s*=\s*\{([^}]*)\}/.exec(read(info))?.[1] ?? '';
    for (const m of body.matchAll(/"([a-z]+)/g)) (direct[m[1]] ??= new Set()).add(name);
  }
  const closure = (pkg, seen = new Set([pkg])) => {
    for (const user of direct[pkg] ?? []) {
      if (!seen.has(user)) {
        seen.add(user);
        closure(user, seen);
      }
    }
    return seen;
  };
  return (pkg) => [...closure(pkg)];
}

/** Frontend module -> every module that imports it, directly or not. */
function moduleUsers() {
  const base = path.join(frontend, 'src/modules');
  const names = readdirSync(base);
  const direct = {};
  for (const user of names) {
    for (const file of walk(path.join(base, user), (n) => /\.tsx?$/.test(n))) {
      for (const m of read(file).matchAll(/@\/modules\/([a-z]+)/g)) {
        // The shell only registers screens: it is mapped to every suite on its own.
        if (m[1] !== user && user !== 'shell') (direct[m[1]] ??= new Set()).add(user);
      }
    }
  }
  const closure = (name, seen = new Set([name])) => {
    for (const user of direct[name] ?? []) {
      if (!seen.has(user)) {
        seen.add(user);
        closure(user, seen);
      }
    }
    return seen;
  };
  return (name) => [...closure(name)];
}

/** Helper -> the helpers it imports. */
function helperImports(source) {
  return [...source.matchAll(/from '(?:\.\.\/support|\.)\/([a-z-]+)'/g)].map((m) => m[1]);
}

const TEST_SECONDS = 15;
const LITERAL = /'([A-Za-z0-9][A-Za-z0-9._@/-]{3,})'/g;

function analyseSpec(file, context) {
  const rel = posix(path.relative(e2e, file));
  const id = rel.replace(/\.spec\.ts$/, '');
  const source = read(file);
  const screens = unique([
    ...[...source.matchAll(/'([A-Z]-\d\d)'/g)].map((m) => m[1]),
    ...[...source.matchAll(/\?s=([A-Z]-\d\d)/g)].map((m) => m[1]),
  ]);
  const support = new Set();
  const visit = (names) => {
    for (const name of names) {
      if (support.has(name) || !context.helpers[name]) continue;
      support.add(name);
      visit(helperImports(context.helpers[name]));
    }
  };
  visit(helperImports(source));
  const modules = unique(screens.flatMap((s) => context.screenModule[s] ?? []));
  const endpoints = unique([...source.matchAll(/['`](\/v1\/[a-z0-9/-]+)/g)].map((m) => m[1]));
  const roots = unique(
    [...endpoints.map(apiRoot), ...modules.flatMap((m) => context.moduleApi[m] ?? [])].filter(Boolean),
  );
  const packages = unique(roots.flatMap((r) => [...(context.apiPackage[r] ?? [])]));
  // The spec's own logins and those of the helpers it imports (MAKER, CHECKER, the A-05 auditor of
  // payroll-support.ts): a helper signs in through the same laneLogin, so its users need clones too.
  const LOGIN = /[a-z0-9._-]+@fci-demo\.test/g;
  const users = unique(
    [source, ...[...support].map((name) => context.helpers[name])].flatMap((text) =>
      [...text.matchAll(LOGIN)].map((m) => m[0]).filter((login) => !/\.w(\d+|n)@/.test(login)),
    ),
  );

  // What the source shows of the spec's use of shared state.
  const dbWrite = /(^|[^a-zA-Z])sql\(|lockForFixture|lockPriorForFixture|receivedTask/m.test(source);
  const dbRead = /sqlRows\(/.test(source);
  const cycle = /openCycleActions|currentCycle|\/v1\/cycles|lockForFixture|lockPriorForFixture/.test(source);
  const decides = /\bdecide\(|pressDecision\(/.test(source);
  const writes =
    decides ||
    dbWrite ||
    /'(POST|PUT|PATCH)'|\.(post|put)\(|\.click\(\)[\s\S]{0,200}expectToast|getByRole\('status'\)/.test(
      source,
    );
  const loginConstants = [...source.matchAll(/const ([A-Z][A-Z_]+) = '[^']+@fci-demo\.test'/g)].map(
    (m) => m[1],
  );
  const loginAssert =
    /(toContainText|toHaveText|hasText)[^\n]*@fci-demo/.test(source) ||
    loginConstants.some(
      (name) => source.includes(`toContainText(${name})`) || source.includes(`toHaveText(${name})`),
    );
  const exactCount = /(dataRows|rows)\([^)]*\)\)?\)?\.toHaveCount\((1\d|[2-9]\d*)\)/.test(source);
  const offices = unique([...source.matchAll(/'((?:DH|HQ|MR|KL|CH)\d{3})'/g)].map((m) => m[1]));
  const state = {
    reads: unique(screens.filter((s) => !HUB_SCREENS.includes(s)).map((s) => `screen:${s}`)),
    writes: !writes ? [] : unique(screens.filter((s) => !HUB_SCREENS.includes(s)).map((s) => `screen:${s}`)),
    inbox: decides ? (offices.length ? offices : ['DH001']) : [],
    dbFixture: dbWrite ? 'write' : dbRead ? 'read' : 'none',
    exactCount,
  };
  if (cycle) state.writes.push(...(offices.length ? offices : ['DH001']).map((o) => `cycle:${o}`));
  state.writes = unique(state.writes);

  const reasons = [];
  if (SERIAL[id]) reasons.push(SERIAL[id]);
  if (dbWrite) reasons.push('writes database fixtures');
  if (cycle) reasons.push('moves a payroll cycle');
  if (loginAssert) reasons.push('asserts the signed-in login as text');
  if (/\bsecurity-offence|security-tampering/.test(id)) reasons.push('offences end the session');
  const dependsOn = unique(DEPENDS_ON.filter((d) => d.match.test(id)).map((d) => d.suite));
  const inList = (rule) => rule.path?.test(id) || rule.screens?.some((s) => screens.includes(s));
  const domains = Object.entries(DOMAINS)
    .filter(([, rule]) => inList(rule))
    .map(([name]) => name);
  const tests = (source.match(/^\s*test\(/gm) ?? []).length;
  return {
    id,
    file: `e2e/${rel}`,
    tests,
    menus: unique(screens.map((s) => context.menu.of[s]).filter(Boolean)),
    screens,
    frontendModules: modules,
    backendPackages: packages,
    endpoints,
    users,
    helpers: [...support].sort(),
    state,
    serialReasons: reasons,
    dependsOn,
    domains,
    literals: unique([...source.matchAll(LITERAL)].map((m) => m[1])),
  };
}

function suiteOf(spec) {
  const rule = AREAS.find((a) => a.match.test(spec.id));
  const area = rule?.area ?? 'unclassified';
  const serial = !rule || rule.serial || spec.serialReasons.length > 0;
  return serial
    ? { name: `${area}-serial`, lane: 'serial', identity: 'plain' }
    : { name: area, lane: 'parallel', identity: 'clones' };
}

function timing() {
  const file = path.join(e2e, 'suite-timings.json');
  return existsSync(file) ? JSON.parse(read(file)) : { specs: {} };
}

/** Longest known prefix first: what a changed path maps to. */
function impactMap(specs, suites, context) {
  const all = suites.map((s) => s.name);
  const entries = [];
  const add = (prefix, names, why) => entries.push({ path: prefix, suites: unique(names), why });
  for (const prefix of NO_IMPACT) add(prefix, [], 'no suite depends on it');
  for (const prefix of SHARED_FRONTEND) add(prefix, all, 'shared by every screen');
  const uses = (test) => unique(specs.filter(test).map((s) => s.suite));
  for (const name of readdirSync(path.join(frontend, 'src/modules'))) {
    const prefix = `frontend/src/modules/${name}/`;
    if (SHARED_FRONTEND.includes(prefix)) continue;
    const users = context.moduleUsers(name);
    const hit = uses((s) => s.frontendModules.some((m) => users.includes(m)));
    if (hit.length) add(prefix, hit, `specs open its screens or a module using it: ${users.join(', ')}`);
    else add(prefix, all, 'no spec reaches it by a known path: safe default');
  }
  for (const name of readdirSync(path.join(repo, JAVA_ROOT))) {
    if (!statSync(path.join(repo, JAVA_ROOT, name)).isDirectory()) continue;
    const prefix = `${JAVA_ROOT}/${name}/`;
    if (SHARED_BACKEND.includes(name)) {
      add(prefix, all, 'a package every screen stands on');
      continue;
    }
    const users = context.dependents(name);
    const hit = uses((s) => s.backendPackages.some((p) => users.includes(p)));
    if (hit.length) add(prefix, hit, `specs reach it or a package using it: ${users.join(', ')}`);
    else add(prefix, all, 'no spec reaches it by a known path: safe default');
    if (existsSync(path.join(repo, 'backend/src/main/resources', name))) {
      add(`backend/src/main/resources/${name}/`, hit.length ? hit : all, `resources of ${name}`);
    }
  }
  add(`${JAVA_ROOT}/FciPayrollApplication.java`, all, 'the application');
  for (const name of Object.keys(context.helpers)) {
    add(
      `frontend/e2e/support/${name}.ts`,
      uses((s) => s.helpers.includes(name)),
      'specs import it',
    );
  }
  for (const file of walk(path.join(repo, 'db/seed'), (n) => n.endsWith('.sql'))) {
    const literals = new Set([...read(file).matchAll(LITERAL)].map((m) => m[1]));
    const hit = uses((s) => s.literals.some((l) => literals.has(l)) || s.users.some((u) => literals.has(u)));
    const known = hit.length > 0;
    add(
      `db/seed/${path.basename(file)}`,
      known ? hit : all,
      known ? 'specs name its data' : 'no spec names its data: safe default',
    );
  }
  for (const spec of specs) add(spec.file.replace(/^/, 'frontend/'), [spec.suite], 'the spec itself');
  return entries.sort((a, b) => b.path.length - a.path.length || a.path.localeCompare(b.path));
}

function build() {
  const support = path.join(e2e, 'support');
  const helpers = {};
  for (const name of readdirSync(support)) {
    if (name.endsWith('.ts') && !name.endsWith('.setup.ts'))
      helpers[name.replace(/\.ts$/, '')] = read(path.join(support, name));
  }
  const context = {
    helpers,
    menu: menus(),
    screenModule: screenModules(),
    moduleApi: moduleApis(),
    apiPackage: apiPackages(),
    dependents: dependents(),
    moduleUsers: moduleUsers(),
  };
  const times = timing();
  const files = walk(e2e, (n) => n.endsWith('.spec.ts')).sort();
  const specs = files.map((file) => analyseSpec(file, context));
  const byName = new Map();
  for (const spec of specs) {
    const suite = suiteOf(spec);
    spec.suite = suite.name;
    spec.lane = suite.lane;
    if (!byName.has(suite.name)) byName.set(suite.name, { ...suite, specs: [] });
    byName.get(suite.name).specs.push(spec);
  }
  const suites = [...byName.values()].map((suite) => {
    // Measured where a run has timed the spec; else the mean of a measured test of its kind.
    const seconds = suite.specs.map((s) => times.specs[s.id] ?? s.tests * TEST_SECONDS);
    return {
      name: suite.name,
      lane: suite.lane,
      identity: suite.identity,
      specs: suite.specs.map((s) => s.file),
      tests: suite.specs.reduce((sum, s) => sum + s.tests, 0),
      estimatedSeconds: Math.round(seconds.reduce((a, b) => a + b, 0)),
      estimatedFromRuns: suite.specs.every((s) => times.specs[s.id] !== undefined),
      menus: unique(suite.specs.flatMap((s) => s.menus)),
      domains: unique(suite.specs.flatMap((s) => s.domains)),
      dependsOn: unique(suite.specs.flatMap((s) => s.dependsOn)).filter((d) => d !== suite.name),
      writes: unique(suite.specs.flatMap((s) => s.state.writes)),
      reads: unique(suite.specs.flatMap((s) => s.state.reads)),
    };
  });
  for (const suite of suites) {
    suite.dependents = suites.filter((o) => o.dependsOn.includes(suite.name)).map((o) => o.name);
    suite.conflictsWith = suites
      .filter(
        (o) =>
          o.name !== suite.name && o.lane === 'parallel' && suite.lane === 'parallel' && conflict(suite, o),
      )
      .map((o) => o.name);
  }
  const domainsOf = new Map(suites.map((s) => [s.name, s.domains]));
  const impact = impactMap(specs, suites, context).map((entry) => ({
    ...entry,
    domains: unique(entry.suites.flatMap((name) => domainsOf.get(name))),
  }));
  for (const spec of specs) delete spec.literals;
  return {
    generatedBy: 'frontend/scripts/e2e-manifest.mjs; rules in e2e/suite-rules.mjs; do not edit by hand',
    domains: Object.fromEntries(
      Object.keys(DOMAINS).map((d) => [
        d,
        unique(specs.filter((s) => s.domains.includes(d)).map((s) => s.suite)),
      ]),
    ),
    suites,
    specs,
    impact,
  };
}

/** The rules a manifest must keep; the list of what is broken. */
function validate(manifest) {
  const problems = [];
  const onDisk = walk(e2e, (n) => n.endsWith('.spec.ts')).map((f) => `e2e/${posix(path.relative(e2e, f))}`);
  const seen = new Map();
  for (const suite of manifest.suites) {
    for (const spec of suite.specs) {
      if (seen.has(spec)) problems.push(`${spec} is in ${seen.get(spec)} and ${suite.name}`);
      seen.set(spec, suite.name);
    }
    for (const dep of suite.dependsOn) {
      if (!manifest.suites.some((s) => s.name === dep))
        problems.push(`${suite.name} depends on unknown ${dep}`);
    }
    if (suite.name === 'unclassified') problems.push(`no area for: ${suite.specs.join(', ')}`);
  }
  for (const spec of onDisk) if (!seen.has(spec)) problems.push(`${spec} is in no suite`);
  for (const spec of manifest.specs) {
    if (spec.domains.length === 0) problems.push(`${spec.file} has no domain`);
    if (spec.lane === 'parallel' && spec.state.dbFixture === 'write') {
      problems.push(`${spec.file} writes fixtures in the parallel lane`);
    }
  }
  const seed = path.join(repo, 'db/e2e-parallel/h2/V990_1__e2e_parallel_users.sql');
  const cloned = read(seed);
  const used = new Set();
  for (const spec of manifest.specs) {
    if (spec.lane !== 'parallel') continue;
    for (const user of spec.users) {
      used.add(user);
      if (!cloned.includes(`'${user}'`)) problems.push(`${spec.file}: ${user} has no clone in V990_1`);
    }
  }
  // A clone no parallel suite needs is one more account in every user list the serial specs read.
  for (const [, user] of cloned.matchAll(/'([a-z0-9._-]+@fci-demo\.test)'/g)) {
    if (!used.has(user)) problems.push(`V990_1 clones ${user}, which no parallel suite signs in as`);
  }
  for (const batch of waves(
    manifest.suites.filter((s) => s.lane === 'parallel' && s.identity === 'clones'),
  )) {
    for (const a of batch)
      for (const b of batch) if (a !== b && conflict(a, b)) problems.push(`${a.name} runs beside ${b.name}`);
  }
  return problems;
}

const minutes = (seconds) => (seconds < 90 ? `${seconds} s` : `${Math.round(seconds / 60)} min`);

/** The tables of e2e/README.md: generated, so they cannot drift from the manifest. */
function readmeTables(manifest) {
  const suiteRows = [...manifest.suites]
    .sort((a, b) => a.lane.localeCompare(b.lane) || a.name.localeCompare(b.name))
    .map(
      (s) =>
        `| \`${s.name}\` | ${s.lane} | ${s.specs.length} | ${s.tests} | ${s.estimatedFromRuns ? '' : '~'}${minutes(s.estimatedSeconds)} | ${s.dependsOn.join(', ') || '-'} |`,
    );
  const domainRows = Object.entries(manifest.domains).map(([name, suites]) => {
    const specs = manifest.specs.filter((s) => s.domains.includes(name));
    return `| \`${name}\` | ${specs.length} | ${specs.reduce((n, s) => n + s.tests, 0)} | ${suites.length} |`;
  });
  return [
    '| Suite | Lane | Specs | Tests | Estimated | Depends on |',
    '| --- | --- | --- | --- | --- | --- |',
    ...suiteRows,
    '',
    '| Domain | Specs | Tests | Suites |',
    '| --- | --- | --- | --- |',
    ...domainRows,
  ].join('\n');
}

function withTables(readme, tables) {
  const start = '<!-- suites:start -->';
  const end = '<!-- suites:end -->';
  const from = readme.indexOf(start);
  const to = readme.indexOf(end);
  return from < 0 || to < 0
    ? readme
    : `${readme.slice(0, from + start.length)}\n<!-- prettier-ignore-start -->\n\n${tables}\n\n<!-- prettier-ignore-end -->\n${readme.slice(to)}`;
}

/** The run's own reports (test-results/lanes/step-*.json) replace the timings of the specs they cover. */
function refreshTimings() {
  const dir = path.join(frontend, 'test-results/lanes');
  const seconds = {};
  const visit = (suite, file) => {
    suite.suites?.forEach((s) => visit(s, s.file ?? file));
    for (const spec of suite.specs ?? []) {
      const id = posix(spec.file ?? file).replace(/\.spec\.ts$/, '');
      for (const test of spec.tests)
        seconds[id] = (seconds[id] ?? 0) + (test.results[0]?.duration ?? 0) / 1000;
    }
  };
  for (const name of readdirSync(dir).filter((n) => /^step-\d+\.json$/.test(n))) {
    JSON.parse(read(path.join(dir, name))).suites?.forEach((s) => visit(s, s.file));
  }
  const times = timing();
  for (const [id, value] of Object.entries(seconds)) times.specs[id] = Math.round(value);
  times.source = `measured by a suite run, ${new Date().toISOString().slice(0, 10)}`;
  writeFileSync(path.join(e2e, 'suite-timings.json'), `${JSON.stringify(times, null, 1)}\n`);
}

if (process.argv.includes('--timings')) refreshTimings();
const target = path.join(e2e, 'suites.json');
const manifest = build();
const text = `${JSON.stringify(manifest, null, 1)}\n`;
const problems = validate(manifest);
const readmeFile = path.join(e2e, 'README.md');
const readme = read(readmeFile).replace(/\r\n/g, '\n');
const tabled = withTables(readme, readmeTables(manifest));
if (process.argv.includes('--check')) {
  if (!existsSync(target) || read(target).replace(/\r\n/g, '\n') !== text) {
    problems.push('e2e/suites.json is stale: npm run e2e:manifest');
  }
  if (tabled !== readme) problems.push('e2e/README.md tables are stale: npm run e2e:manifest');
} else {
  writeFileSync(target, text);
  writeFileSync(readmeFile, tabled);
}
for (const p of problems) console.error(`e2e manifest: ${p}`);
const parallel = manifest.suites.filter((s) => s.lane === 'parallel').length;
console.log(
  `e2e manifest: ${manifest.specs.length} specs, ${manifest.suites.length} suites (${parallel} parallel), ${manifest.impact.length} impact paths, ${problems.length} problems`,
);
process.exit(problems.length ? 1 : 0);
