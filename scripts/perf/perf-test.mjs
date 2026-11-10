#!/usr/bin/env node
/**
 * Mixed performance test of the deployed stack (owner 2026-10-27: "after profiling do the performance testing and
 * identify issue if any and fix it"; SRS-NF-6: every interaction within 3 s). Profiling (load-module.mjs,
 * deep-profile.py) looks at one module at a time; this drives the modules together, as the desks of a working
 * day would, on the VOLUME set (deploy-local.ps1 -Volume):
 *   - 25 region Payroll Makers: X-05, the cycle desk, I-04 loans, I-06 penalties (mixes 01, 05, 09, 12) and the office
 *     modules built since: O-05, I-08, O-04, I-05, I-11, M-02 / M-06, M-05 (mixes 11, 13, 15, 17, 19, 20, 22),
 *   - 25 region Payroll Checkers: W-01 and the cycle status (mix 02),
 *   - the Headquarters desks: masters (04), rules (06), audit (03), CPF (07), administration (02a).
 * Closed-loop stages of rising concurrency (each virtual user: request, think, request ...), then a stress stage
 * without think time to find where the stack saturates. Per stage: throughput, p50 / p95 / p99 / max, errors by
 * status, SLA breaches; per endpoint at the busiest stage. GET only, nothing is written.
 *
 * SLA (owner 2026-10-27): 3 s for every interactive request; 1 minute for a report or an Excel / CSV download (a path
 * with /export or /report). Each desk downloads once per round of its screens (XLSX, the format a user picks most).
 *
 * Through NGINX (default base) every virtual user shares one client address, so NGINX's per-address API limit
 * (200 r/s) caps the stress stage: pass --base http://127.0.0.1:8380 to load the online instance directly for the
 * capacity numbers (the API is served under /api there too).
 *
 * Scenarios (--scenario): working-day (S1 stages + S6 stress), report-rush (S5), month-end (S4), salary-day (S7: --members 950 --desks 50 --arrivals-per-second 10 --steady-seconds 300),
 * payslip-download (S8, OD-144: --members 1000 --arrivals-per-second 20 --pdf-rounds 3: every member downloads the
 * own payslip PDF at the same moment, honouring Retry-After on a 503; counts 200 / 304 / 503 and the latency), eseva (S3 sign-in surge + S2
 * salary-day members; needs --base http://127.0.0.1:8380), all.
 * Usage: node scripts/perf/perf-test.mjs [--scenario working-day] [--base https://localhost:8743] [--stages 10,25,50]
 *        [--stage-seconds 60] [--think 1000] [--stress-seconds 60] [--eseva-users 350] [--storm-waves 50,100,200]
 * Report: docs/perf/perf-test-<date>.json (add the run to docs/perf/profiling-tracker.json "performance").
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { MODULES, percentile, signIn } from './load-module.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, a, i, all) => (a.startsWith('--') ? [...pairs, [a.slice(2), all[i + 1]]] : pairs), []),
);
const STAGES = (args.stages ?? '10,25,50').split(',').map(Number);
const STAGE_SECONDS = Number(args['stage-seconds'] ?? 60);
const THINK_MS = Number(args.think ?? 1000);
const STRESS_SECONDS = Number(args['stress-seconds'] ?? 60);
const SLA_MS = 3000;
const DOWNLOAD_SLA_MS = 60_000;
// A file: an export, a report, or a register (the I-08 Arrears Register is a file, not a page).
const isDownload = (path) => /\/(export|report|register|pdf)(\?|$)/.test(path);
const slaOf = (path) => (isDownload(path) ? DOWNLOAD_SLA_MS : SLA_MS);

/** Report months: the last three, as YYYYMM. */
function months() {
  const now = new Date(Date.now() + 5.5 * 3600_000);
  const ym = (back) => {
    const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - back, 1));
    return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
  };
  return { from: ym(3), to: ym(0) };
}

/** The downloads of each kind of desk (owner's 1-minute SLA). */
function downloads(desk) {
  const { from, to } = months();
  const since = new Date(Date.now() - 30 * 86400_000).toISOString();
  const until = new Date().toISOString();
  if (desk.role === 'PAYROLL_MAKER') {
    return [
      ['download X-05 worklist', '/v1/exceptions/export?format=XLSX'],
      ['download I-04 loan report', `/v1/loans/report?from=${from}&to=${to}&format=XLSX`],
      ['download I-06 penalty report', `/v1/penalties/report?from=${from}&to=${to}&format=XLSX`],
      ['download I-08 arrears register', '/v1/arrear-events/register'],
    ];
  }
  if (desk.role === 'SUPER_USER') return [['download X-04 audit 30 days', `/v1/admin/audit/export?from=${since}&to=${until}&format=XLSX`]];
  if (desk.role === 'SYSTEM_ADMINISTRATOR') {
    return [
      ['download A-04 active users', '/v1/admin/active-users/export?format=XLSX'],
      ['download M-26 labels', '/v1/masters/labels/export?format=XLSX'],
    ];
  }
  return [];
}

/** The desks: login, office, role, the mixes whose paths it reads. */
function desks() {
  const regions = MODULES['01'].users.map(([login, , office]) => office.slice(3));
  const makers = regions.map((c) => ({
    login: `vol.maker.${c.toLowerCase()}@fci-demo.test`, office: `RO-${c}`, role: 'PAYROLL_MAKER',
    // The office modules of the first deep pass and those built since (11, 13, 15, 17, 19, 20, 22).
    mixes: ['01', '05', '09', '12', '11', '13', '15', '17', '19', '20', '22'],
  }));
  const checkers = regions.map((c) => ({
    login: `vol.checker.${c.toLowerCase()}@fci-demo.test`, office: `RO-${c}`, role: 'PAYROLL_CHECKER', mixes: ['02'],
  }));
  const hq = [
    { login: 'su.hq@fci-demo.test', office: 'HQ001', role: 'RULE_ADMINISTRATOR', mixes: ['04', '06'] },
    { login: 'tax.ruleadmin@fci-demo.test', office: 'HQ001', role: 'RULE_ADMINISTRATOR', mixes: ['04', '06'] },
    { login: 'superuser.hq@fci-demo.test', office: 'HQ001', role: 'SUPER_USER', mixes: ['03'] },
    { login: 'cpf.manager.north@fci-demo.test', office: 'NA11', role: 'CPF_MANAGER', mixes: ['07'] },
    { login: 'sysadmin@fci-demo.test', office: 'HQ001', role: 'SYSTEM_ADMINISTRATOR', mixes: ['02a'] },
  ];
  // Interleave so that every stage has makers, checkers and Headquarters desks.
  const out = [];
  for (let i = 0; i < Math.max(makers.length, checkers.length, hq.length); i++) {
    for (const group of [makers, checkers, hq]) if (group[i]) out.push(group[i]);
  }
  return out;
}

async function prepare(desk, headers = {}) {
  const session = await signIn(desk.login, desk.office, desk.role, headers);
  const ctx = {};
  for (const m of desk.mixes) Object.assign(ctx, MODULES[m].prepare ? await MODULES[m].prepare(session) : {});
  const paths = () => [
    ...desk.mixes.flatMap((m) => MODULES[m].paths(ctx.eis ?? null, ctx).map(([n, p]) => [`${m} ${n}`, p])),
    ...downloads(desk),
  ];
  return { desk, session, paths };
}

function summarise(samples, seconds) {
  const interactive = samples.filter((s) => !s.download);
  const dl = samples.filter((s) => s.download).map((s) => s.ms).sort((a, b) => a - b);
  const all = interactive.map((s) => s.ms).sort((a, b) => a - b);
  const statuses = {};
  for (const s of samples) statuses[s.status] = (statuses[s.status] ?? 0) + 1;
  const errors = samples.filter((s) => s.status >= 400).length;
  return {
    requests: samples.length,
    throughput: Math.round((samples.length / seconds) * 10) / 10,
    p50: Math.round(percentile(all, 50)),
    p95: Math.round(percentile(all, 95)),
    p99: Math.round(percentile(all, 99)),
    max: Math.round(all.at(-1) ?? 0),
    errors,
    errorRate: samples.length ? Math.round((errors / samples.length) * 10000) / 100 : 0,
    statuses,
    slaBreaches: samples.filter((s) => s.ms > s.sla).length,
    downloads: { requests: dl.length, p50: Math.round(percentile(dl, 50)), p99: Math.round(percentile(dl, 99)),
      max: Math.round(dl.at(-1) ?? 0), slaMs: DOWNLOAD_SLA_MS },
  };
}

function byEndpoint(samples) {
  const groups = new Map();
  for (const s of samples) groups.set(s.name, [...(groups.get(s.name) ?? []), s]);
  return [...groups].map(([name, list]) => {
    const ms = list.map((s) => s.ms).sort((a, b) => a - b);
    return {
      endpoint: name, requests: list.length, errors: list.filter((s) => s.status >= 400).length, slaMs: list[0].sla,
      breaches: list.filter((s) => s.ms > s.sla).length,
      p50: Math.round(percentile(ms, 50)), p95: Math.round(percentile(ms, 95)), p99: Math.round(percentile(ms, 99)),
      max: Math.round(ms.at(-1) ?? 0),
    };
  }).sort((a, b) => b.p99 / b.slaMs - a.p99 / a.slaMs);
}

/** The slowest {@code n} endpoints, plus every endpoint that answered an error (kept for the report). */
function topAndFailing(samples, n) {
  const all = byEndpoint(samples);
  return [...all.slice(0, n), ...all.slice(n).filter((e) => e.errors > 0)];
}

async function stage(users, seconds, think) {
  const samples = [];
  const end = Date.now() + seconds * 1000;
  await Promise.all(users.map(async (u, i) => {
    // Stagger the start so the virtual users do not move in lock step.
    await new Promise((ok) => setTimeout(ok, (i * 97) % Math.max(1, think || 50)));
    let k = i;
    while (Date.now() < end) {
      const list = u.paths();
      const [name, path] = list[k++ % list.length];
      const t0 = performance.now();
      let status = 0;
      try {
        const r = await u.session.call('GET', path);
        await r.arrayBuffer();
        status = r.status;
      } catch {
        status = 599;
      }
      samples.push({ name, ms: performance.now() - t0, status, download: isDownload(path), sla: slaOf(path) });
      if (think > 0) await new Promise((ok) => setTimeout(ok, think));
    }
  }));
  return samples;
}

const BASE_URL = args.base ?? 'https://localhost:8743';
// Direct to the online instance: each virtual user carries its own office address in X-Forwarded-For (trusted only
// from the local proxy, fci.proxies), as the load balancer and NGINX pass real client addresses in production.
const DIRECT = !BASE_URL.startsWith('https://localhost:8743');
/** A distinct office address per virtual user (10.x.y.z). */
const officeIp = (i) => `10.${20 + Math.floor(i / 65025)}.${Math.floor(i / 255) % 255}.${(i % 255) + 1}`;

function log(label, s) {
  console.log(
    `${label}: ${s.requests} req, ${s.throughput}/s, p50 ${s.p50} p95 ${s.p95} p99 ${s.p99} max ${s.max} ms, ` +
      `errors ${s.errorRate} % ${JSON.stringify(s.statuses)}, SLA breaches ${s.slaBreaches}; ` +
      `downloads ${s.downloads.requests} p99 ${s.downloads.p99} ms`,
  );
}

function slowest(report, n) {
  for (const e of report.stages.at(-1).endpoints.slice(0, n)) {
    console.log(`  ${e.endpoint}: p50 ${e.p50} p99 ${e.p99} max ${e.max} (${e.requests} req, ${e.errors} err, SLA ${e.slaMs} ms)`);
  }
}

async function signInDesks(count) {
  const users = [];
  for (const [i, desk] of desks().slice(0, count).entries()) {
    users.push(await prepare(desk, DIRECT ? { 'X-Forwarded-For': officeIp(i) } : {}));
    // Through NGINX the sign-in limiter allows 10 a minute per client address: pace the sign-ins.
    if (!DIRECT) await new Promise((ok) => setTimeout(ok, 6_500));
  }
  return users;
}

/** S1 working day (stages with think time) and S6 stress (no think time). */
async function workingDay(report) {
  const users = await signInDesks(Math.max(...STAGES));
  for (const n of STAGES) {
    const samples = await stage(users.slice(0, n), STAGE_SECONDS, THINK_MS);
    const s = { scenario: 'S1 working day', users: Math.min(n, users.length), thinkMs: THINK_MS, seconds: STAGE_SECONDS, ...summarise(samples, STAGE_SECONDS) };
    report.stages.push({ ...s, endpoints: topAndFailing(samples, 12) });
    log(`S1 ${s.users} users, think ${THINK_MS} ms`, s);
  }
  if (STRESS_SECONDS > 0) {
    const samples = await stage(users, STRESS_SECONDS, 0);
    const s = { scenario: 'S6 stress', users: users.length, thinkMs: 0, seconds: STRESS_SECONDS, ...summarise(samples, STRESS_SECONDS) };
    report.stages.push({ ...s, endpoints: topAndFailing(samples, 15) });
    log(`S6 stress ${s.users} users, no think`, s);
    slowest(report, 10);
  }
  return users;
}

/** S5 report rush: every maker downloads its reports at the same time, back to back. */
async function reportRush(report, signedIn) {
  const users = signedIn ?? (await signInDesks(Number(args['rush-users'] ?? 50)));
  const makers = users.filter((u) => u.desk.role === 'PAYROLL_MAKER').map((u) => ({ ...u, paths: () => downloads(u.desk) }));
  const seconds = Number(args['rush-seconds'] ?? 120);
  const samples = await stage(makers, seconds, 0);
  const s = { scenario: 'S5 report rush', users: makers.length, thinkMs: 0, seconds, ...summarise(samples, seconds) };
  report.stages.push({ ...s, endpoints: byEndpoint(samples) });
  log(`S5 report rush, ${makers.length} makers`, s);
  slowest(report, 6);
}

/** The eSeva accounts of the volume set (V900_3), read through A-01 by the System Administrator. */
async function esevaLogins(count) {
  const admin = await signIn('sysadmin@fci-demo.test', 'HQ001', 'SYSTEM_ADMINISTRATOR', DIRECT ? { 'X-Forwarded-For': officeIp(60000) } : {});
  const out = [];
  for (let page = 0; out.length < count; page++) {
    const r = await admin.call('GET', `/v1/admin/users?q=vol.ess.&page=${page}&size=100`);
    const body = await r.json();
    const rows = body.items ?? [];
    if (rows.length === 0) break;
    out.push(...rows.map((u) => u.loginId ?? u.login).filter(Boolean));
  }
  return out.slice(0, count);
}

const ESS_PATHS = [
  ['E-01 my record', '/v1/eseva/me'],
  ['E-01 payslips', '/v1/payslips/mine?page=0&size=10'],
  ['E-01 last payslip', null],
  ['E-01 declarations', '/v1/tax/declarations/mine'],
  ['E-01 Form 16', '/v1/tax/form16-certificates/mine'],
  ['E-01 CPF sanctions', '/v1/cpf/sanctions/mine'],
  ['E-01 update requests', '/v1/hrms-update-requests/mine'],
  ['menu', '/menu'],
];

function lastMonth() {
  const now = new Date(Date.now() + 5.5 * 3600_000);
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

/**
 * S3 sign-in surge: waves of members signing in at the same moment from distinct office addresses; then S2 salary
 * day: the signed-in members read their payslips, declarations and Form 16 with think time.
 */
async function eseva(report) {
  if (!DIRECT) throw new Error('the eSeva scenarios need --base http://127.0.0.1:8380 (one address per member)');
  const logins = await esevaLogins(Number(args['eseva-users'] ?? 350));
  const waves = (args['storm-waves'] ?? '50,100,200').split(',').map(Number);
  const sessions = [];
  let next = 0;
  for (const wave of waves) {
    const batch = logins.slice(next, next + wave);
    if (batch.length === 0) break;
    const offset = next;
    next += batch.length;
    const t0 = performance.now();
    const timings = [];
    const failures = {};
    await Promise.all(
      batch.map(async (login, j) => {
        const t = performance.now();
        try {
          sessions.push(await signIn(login, null, null, { 'X-Forwarded-For': officeIp(1000 + offset + j) }));
          timings.push(performance.now() - t);
        } catch (e) {
          const code = /sign-in (\d+)/.exec(e.message)?.[1] ?? 'error';
          failures[code] = (failures[code] ?? 0) + 1;
        }
      }),
    );
    const sorted = timings.sort((a, b) => a - b);
    const s = {
      scenario: 'S3 sign-in surge', users: batch.length, seconds: Math.round((performance.now() - t0) / 100) / 10,
      signedIn: timings.length, failures, p50: Math.round(percentile(sorted, 50)), p95: Math.round(percentile(sorted, 95)),
      p99: Math.round(percentile(sorted, 99)), max: Math.round(sorted.at(-1) ?? 0),
      slaBreaches: sorted.filter((ms) => ms > SLA_MS).length,
    };
    report.stages.push(s);
    console.log(`S3 sign-in surge, ${batch.length} at once: ${s.signedIn} signed in within ${s.seconds} s, p50 ${s.p50} p95 ${s.p95} p99 ${s.p99} max ${s.max} ms, failures ${JSON.stringify(failures)}, over 3 s ${s.slaBreaches}`);
  }
  const think = Number(args['eseva-think'] ?? 2000);
  const users = sessions.map((session) => ({
    session,
    paths: () => ESS_PATHS.map(([n, p]) => [n, p ?? `/v1/payslips/mine/${lastMonth()}`]),
  }));
  const seconds = Number(args['eseva-seconds'] ?? 90);
  const samples = await stage(users, seconds, think);
  const s = { scenario: 'S2 salary-day eSeva', users: users.length, thinkMs: think, seconds, ...summarise(samples, seconds) };
  report.stages.push({ ...s, endpoints: byEndpoint(samples) });
  log(`S2 salary day, ${users.length} members, think ${think} ms`, s);
  slowest(report, 8);
}

/**
 * S4 month-end: every region maker queues a C-09 simulation of its whole office (a C-13 SIMULATION_RUN job on the
 * batch instance, up to SIMULATION_MAX_SELECTION members each) and the desks keep working meanwhile; the online
 * latency is compared with S1 at the same concurrency, and the jobs' completion times are recorded.
 */
async function monthEnd(report, signedIn) {
  const users = signedIn ?? (await signInDesks(Math.max(...STAGES)));
  const makers = users.filter((u) => u.desk.role === 'PAYROLL_MAKER');
  const queued = [];
  for (const m of makers) {
    const r = await m.session.call('POST', '/v1/simulations', { population: 'OFFICE', members: [], whatIf: 'AS_IS' });
    const body = await r.json().catch(() => ({}));
    if (r.status < 300 && body.run?.id) queued.push({ maker: m, id: body.run.id, status: body.run.status, at: Date.now() });
    else console.log(`  ${m.desk.login}: simulation not queued (${r.status} ${body.errorCode ?? ''})`);
  }
  console.log(`S4: ${queued.length} office simulations queued on the batch instance`);
  const seconds = Number(args['month-end-seconds'] ?? 120);
  const watch = (async () => {
    const end = Date.now() + seconds * 1000;
    while (Date.now() < end && queued.some((q) => !q.done)) {
      for (const q of queued.filter((x) => !x.done)) {
        const r = await q.maker.session.call('GET', `/v1/simulations/${q.id}`);
        const body = await r.json().catch(() => ({}));
        const status = body.run?.status;
        if (status === 'COMPLETE' || status === 'FAILED') {
          q.done = status;
          q.seconds = Math.round((Date.now() - q.at) / 100) / 10;
        }
      }
      await new Promise((ok) => setTimeout(ok, 5000));
    }
  })();
  const samples = await stage(users, seconds, THINK_MS);
  await watch;
  const s = { scenario: 'S4 month-end (desks + batch simulations)', users: users.length, thinkMs: THINK_MS, seconds,
    jobs: { queued: queued.length, complete: queued.filter((q) => q.done === 'COMPLETE').length,
      failed: queued.filter((q) => q.done === 'FAILED').length,
      slowestSeconds: Math.max(0, ...queued.filter((q) => q.seconds).map((q) => q.seconds)) },
    ...summarise(samples, seconds) };
  report.stages.push({ ...s, endpoints: topAndFailing(samples, 12) });
  log(`S4 month-end, ${users.length} desks while ${queued.length} simulations run`, s);
  console.log(`  jobs: ${JSON.stringify(s.jobs)}`);
  slowest(report, 8);
}

/**
 * S7 salary day at scale (owner 2026-10-27: "test with 1000 users concurrently, salary day"): the eSeva members and the
 * desks sign in over an arrival window (a fixed rate, as people arrive, each from its own office address), then all
 * of them work at once with human think time: the members read their payslips, Form 16, declarations; the desks
 * their screens. Reports the sign-in latency (queue + hash), and per minute of the steady state the throughput and
 * latency, so the server's CPU and memory (JFR / GC log) can be read against a known load.
 */
async function salaryDay(report) {
  if (!DIRECT) throw new Error('the salary-day scenario needs --base http://127.0.0.1:8380 (one address per user)');
  const members = Number(args['members'] ?? 950);
  const deskCount = Number(args['desks'] ?? 50);
  const rate = Number(args['arrivals-per-second'] ?? 10);
  const steady = Number(args['steady-seconds'] ?? 300);
  const memberThink = Number(args['member-think'] ?? 8000);
  const deskThink = Number(args['desk-think'] ?? 3000);
  const logins = await esevaLogins(members);
  const arrivals = [
    ...desks().slice(0, deskCount).map((d) => ({ kind: 'desk', desk: d })),
    ...logins.map((login) => ({ kind: 'member', login })),
  ];
  const users = [];
  const signInMs = [];
  const failures = {};
  const t0 = performance.now();
  await Promise.all(
    arrivals.map(async (a, i) => {
      await new Promise((ok) => setTimeout(ok, Math.round((i / rate) * 1000)));
      const t = performance.now();
      try {
        if (a.kind === 'desk') {
          const u = await prepare(a.desk, { 'X-Forwarded-For': officeIp(i) });
          users.push({ ...u, think: deskThink });
        } else {
          const session = await signIn(a.login, null, null, { 'X-Forwarded-For': officeIp(i) });
          users.push({
            session,
            think: memberThink,
            paths: () => ESS_PATHS.map(([n, p]) => [n, p ?? `/v1/payslips/mine/${lastMonth()}`]),
          });
        }
        signInMs.push(performance.now() - t);
      } catch (e) {
        const code = /sign-in (\d+)/.exec(e.message)?.[1] ?? 'error';
        failures[code] = (failures[code] ?? 0) + 1;
      }
    }),
  );
  const sorted = signInMs.sort((x, y) => x - y);
  const arrival = {
    scenario: 'S7 salary day: arrivals', users: arrivals.length, ratePerSecond: rate,
    seconds: Math.round((performance.now() - t0) / 100) / 10, signedIn: signInMs.length, failures,
    p50: Math.round(percentile(sorted, 50)), p95: Math.round(percentile(sorted, 95)), p99: Math.round(percentile(sorted, 99)),
    max: Math.round(sorted.at(-1) ?? 0), slaBreaches: sorted.filter((ms) => ms > SLA_MS).length,
  };
  report.stages.push(arrival);
  console.log(`S7 arrivals: ${arrival.signedIn}/${arrival.users} signed in at ${rate}/s within ${arrival.seconds} s, sign-in p50 ${arrival.p50} p95 ${arrival.p95} p99 ${arrival.p99} max ${arrival.max} ms, failures ${JSON.stringify(failures)}`);
  // Steady state, in one-minute windows: every user works with its own think time.
  const windows = Math.max(1, Math.round(steady / 60));
  for (let w = 0; w < windows; w++) {
    const samples = [];
    const end = Date.now() + 60_000;
    await Promise.all(
      users.map(async (u, i) => {
        await new Promise((ok) => setTimeout(ok, (i * 37) % u.think));
        let k = i;
        while (Date.now() < end) {
          const list = u.paths();
          const [name, path] = list[k++ % list.length];
          const t = performance.now();
          let status = 0;
          try {
            const r = await u.session.call('GET', path);
            await r.arrayBuffer();
            status = r.status;
          } catch {
            status = 599;
          }
          samples.push({ name, ms: performance.now() - t, status, download: isDownload(path), sla: slaOf(path) });
          await new Promise((ok) => setTimeout(ok, u.think));
        }
      }),
    );
    const s = { scenario: `S7 salary day: steady minute ${w + 1}`, users: users.length, seconds: 60, ...summarise(samples, 60) };
    report.stages.push({ ...s, endpoints: topAndFailing(samples, 8) });
    log(`S7 steady minute ${w + 1}, ${users.length} users at once`, s);
  }
  slowest(report, 8);
}

/**
 * S8 payslip download (OD-144; owner's SLA: 1,000 concurrent payslip users on salary day, a download within 1 minute):
 * the members sign in over an arrival window, then every round all of them ask for the own payslip PDF of last month
 * at the same moment. A 503 (the PDF bulkhead, fci.api.pdf.max-concurrent) is retried once after its Retry-After; the
 * second and later rounds send If-None-Match with the ETag received, so a repeat is a 304 without a body. Reports per
 * round the status counts, latency percentiles, bytes and how many 503s carried a Retry-After.
 */
async function payslipDownload(report) {
  if (!DIRECT) throw new Error('the payslip-download scenario needs --base http://127.0.0.1:8380 (one address per user)');
  const members = Number(args['members'] ?? 1000);
  const rate = Number(args['arrivals-per-second'] ?? 20);
  const rounds = Number(args['pdf-rounds'] ?? 3);
  const logins = await esevaLogins(members);
  const sessions = [];
  const failures = {};
  await Promise.all(
    logins.map(async (login, i) => {
      await new Promise((ok) => setTimeout(ok, Math.round((i / rate) * 1000)));
      try {
        sessions.push({ session: await signIn(login, null, null, { 'X-Forwarded-For': officeIp(i) }), etag: null });
      } catch (e) {
        const code = /sign-in (\d+)/.exec(e.message)?.[1] ?? 'error';
        failures[code] = (failures[code] ?? 0) + 1;
      }
    }),
  );
  console.log(`S8: ${sessions.length}/${logins.length} members signed in, failures ${JSON.stringify(failures)}`);
  const path = `/v1/payslips/mine/${lastMonth()}/pdf`;
  for (let round = 1; round <= rounds; round++) {
    const ms = [];
    const statuses = {};
    let bytes = 0;
    let retryAfter = 0;
    const t0 = performance.now();
    await Promise.all(
      sessions.map(async (u) => {
        const t = performance.now();
        const headers = u.etag ? { 'If-None-Match': u.etag } : {};
        let r = await u.session.call('GET', path, undefined, headers);
        if (r.status === 503) {
          const wait = Number(r.headers.get('Retry-After') ?? 0);
          if (wait > 0) retryAfter++;
          await r.arrayBuffer();
          await new Promise((ok) => setTimeout(ok, Math.max(1, wait) * 1000));
          r = await u.session.call('GET', path, undefined, headers);
        }
        const body = await r.arrayBuffer();
        bytes += body.byteLength;
        if (r.status === 200) u.etag = r.headers.get('ETag');
        statuses[r.status] = (statuses[r.status] ?? 0) + 1;
        ms.push(performance.now() - t);
      }),
    );
    const sorted = ms.sort((a, b) => a - b);
    const s = {
      scenario: `S8 payslip download: round ${round}`, users: sessions.length,
      seconds: Math.round((performance.now() - t0) / 100) / 10, statuses, bytes, retryAfterOn503: retryAfter,
      p50: Math.round(percentile(sorted, 50)), p95: Math.round(percentile(sorted, 95)), p99: Math.round(percentile(sorted, 99)),
      max: Math.round(sorted.at(-1) ?? 0), slaBreaches: sorted.filter((v) => v > DOWNLOAD_SLA_MS).length,
    };
    report.stages.push(s);
    console.log(`S8 round ${round}: ${JSON.stringify(statuses)} in ${s.seconds} s, p50 ${s.p50} p95 ${s.p95} p99 ${s.p99} max ${s.max} ms, 503 with Retry-After ${retryAfter}`);
  }
}

async function main() {
  const scenario = args.scenario ?? 'working-day';
  const report = { at: new Date().toISOString(), base: BASE_URL, direct: DIRECT, scenario, thinkMs: THINK_MS, slaMs: SLA_MS, downloadSlaMs: DOWNLOAD_SLA_MS, stages: [] };
  let users = null;
  if (scenario === 'working-day' || scenario === 'all') users = await workingDay(report);
  if (scenario === 'report-rush' || scenario === 'all') await reportRush(report, users);
  if (scenario === 'month-end' || scenario === 'all') await monthEnd(report, users);
  if (scenario === 'eseva' || scenario === 'all') await eseva(report);
  if (scenario === 'salary-day') await salaryDay(report);
  if (scenario === 'payslip-download') await payslipDownload(report);
  const out = resolve(ROOT, 'docs/perf');
  mkdirSync(out, { recursive: true });
  const file = resolve(out, `perf-test-${report.at.slice(0, 10)}-${scenario}${args.tag ? `-${args.tag}` : ''}.json`);
  writeFileSync(file, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`report: ${file}`);
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
