#!/usr/bin/env node
/**
 * Resilience run of the local stack (owner 2026-10-27: "implement chaos monkey and do the resilience testing";
 * PENDING PT-5). Needs a stack deployed with the chaos profile from a WAR built with -Pfci.chaos=true:
 *
 *   ./gradlew :backend:bootWar :backend:h2Triggers -Pfci.chaos=true
 *   powershell -File scripts/local/deploy-local.ps1 -PortOffset 300 -Runtime scripts/local/runtime-b -SkipBuild -OtpRequired Y -Chaos
 *   node scripts/resilience/chaos-run.mjs [--phase-seconds 60] [--users 4] [--skip-kill]
 *
 * Phases and the checks each must pass (docs/engineering/RESILIENCE_TESTING.md):
 *   1 baseline        no fault: every request answers 2xx.
 *   2 latency         Chaos Monkey adds 1-3 s to one call in five (services, repositories, REST controllers):
 *                     every answer arrives (no hang past the client timeout) and every 5xx is a clean problem.
 *   3 exceptions      a RuntimeException in one call in five: every 5xx is a ProblemDetail with a correlationId and
 *                     never a stack trace, class or SQL; the I-24 leave encashment arrear run is repeated meanwhile and
 *                     the database must hold exactly the successful runs, each complete (all or nothing).
 *   4 recovery        assaults off: every request answers 2xx again, health UP.
 *   5 online killed   the online instance is killed: the gateway answers fast (no hang) while it is down; after the
 *                     restart the session from before the kill is still valid (Spring Session JDBC) and health is UP.
 *   6 batch killed    a dry run is slowed by latency on the batch instance and killed while RUNNING: after the
 *                     restart crash recovery ends it FAILED (never left RUNNING) and the stack serves again.
 * Report: docs/perf/resilience-<date>.json; exit code 1 when a check fails.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, a, i, all) => (a.startsWith('--') ? [...pairs, [a.slice(2), all[i + 1] ?? 'true']] : pairs), []),
);
const BASE = args.base ?? 'https://localhost:8743';
const RUNTIME = resolve(ROOT, args.runtime ?? 'scripts/local/runtime-b');
const OFFSET = Number(args['port-offset'] ?? 300);
const PHASE_MS = Number(args['phase-seconds'] ?? 60) * 1000;
const USERS = Number(args.users ?? 4);
const CLIENT_TIMEOUT_MS = 20_000;
const PASSWORD = 'Fci@Demo2026';
const API_LOG = resolve(RUNTIME, 'logs/online/api.json');
const MGMT = {
  online: `http://127.0.0.1:${8081 + OFFSET}/api/actuator`,
  batch: `http://127.0.0.1:${8091 + OFFSET}/api/actuator`,
};
const PORT = { online: 8080 + OFFSET, batch: 8090 + OFFSET };
if (new URL(BASE).hostname === 'localhost') process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));
const checks = [];
function check(phase, name, ok, detail) {
  checks.push({ phase, name, ok, detail });
  console.log(`  ${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` - ${detail}` : ''}`);
}

// ------------------------------------------------------------------ management (Chaos Monkey actuator)
function metricsAuth() {
  const handover = readFileSync(resolve(RUNTIME, 'config/HANDOVER.txt'), 'utf8');
  const password = /metrics \(Prometheus\) password : (\S+)/.exec(handover)?.[1];
  if (!password) throw new Error('metrics password not found in HANDOVER.txt');
  return `Basic ${Buffer.from(`metrics:${password}`).toString('base64')}`;
}
const AUTH = metricsAuth();

async function monkey(mode, assaults) {
  const headers = { Authorization: AUTH, 'Content-Type': 'application/json' };
  if (assaults) {
    const set = await fetch(`${MGMT[mode]}/chaosmonkey/assaults`, { method: 'POST', headers, body: JSON.stringify(assaults) });
    if (!set.ok) throw new Error(`${mode}: assaults ${set.status} ${await set.text()}`);
  }
  const r = await fetch(`${MGMT[mode]}/chaosmonkey/${assaults ? 'enable' : 'disable'}`, { method: 'POST', headers });
  if (!r.ok) throw new Error(`${mode}: chaosmonkey ${r.status} ${await r.text()}`);
}
const LATENCY = { level: 5, latencyActive: true, latencyRangeStart: 1000, latencyRangeEnd: 3000, exceptionsActive: false };
// The default exception of Chaos Monkey is a RuntimeException. A request passes some ten watched calls, so one in
// five fails most requests; one in twenty-five lets part of the I-24 runs through, which the all-or-nothing check needs.
const EXCEPTIONS = { level: 25, latencyActive: false, exceptionsActive: true };

async function health(mode) {
  try {
    const r = await fetch(`${MGMT[mode]}/health`, { signal: AbortSignal.timeout(5000) });
    return r.ok ? (await r.json()).status : `HTTP ${r.status}`;
  } catch (e) {
    return `DOWN (${e.cause?.code ?? e.name})`;
  }
}

// ------------------------------------------------------------------ browser-protocol client (as load-module.mjs)
class Session {
  constructor(login) {
    this.login = login;
    this.cookies = new Map();
  }
  header() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  }
  async call(method, path, body) {
    const headers = { Accept: 'application/json', Cookie: this.header() };
    if (method !== 'GET') {
      headers['Content-Type'] = 'application/json';
      headers['X-XSRF-TOKEN'] = decodeURIComponent(this.cookies.get('XSRF-TOKEN') ?? '');
      headers['Idempotency-Key'] = crypto.randomUUID();
    }
    const started = Date.now();
    try {
      const r = await fetch(`${BASE}/api${path}`, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
        redirect: 'manual',
        signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
      });
      for (const c of r.headers.getSetCookie?.() ?? []) {
        const [pair] = c.split(';');
        const at = pair.indexOf('=');
        this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
      }
      const text = await r.text();
      // A sensitive screen (SRS-G-48) asks once per session for an OTP step-up, as the browser does.
      if (r.status === 403 && !this.steppedUp && path !== '/auth/step-up' && text.includes('AUTH-403-STEP-UP')) {
        this.steppedUp = true;
        await this.stepUp();
        return this.call(method, path, body);
      }
      return { status: r.status, ms: Date.now() - started, text, type: r.headers.get('content-type') ?? '', retryAfter: r.headers.get('retry-after') };
    } catch (e) {
      return { status: 0, ms: Date.now() - started, text: '', type: '', error: e.name };
    }
  }
  async stepUp() {
    const since = Date.now();
    const challenge = await this.call('POST', '/auth/step-up', {});
    const id = Number(this.json(challenge).args?.[0]);
    for (let i = 0; i < 20 && id; i++) {
      await sleep(300);
      const code = devCode(this.userId, since);
      if (code) {
        await this.call('POST', '/auth/step-up', { otp: { challengeId: id, code } });
        return;
      }
    }
  }
  json(res) {
    try {
      return JSON.parse(res.text);
    } catch {
      return {};
    }
  }
}

async function signIn(login, office, role) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const s = new Session(login);
    await s.call('GET', '/auth/csrf');
    const r = await s.call('POST', '/auth/login', { loginId: login, password: PASSWORD, role, location: office });
    if (r.status === 200) {
      const me = s.json(await s.call('GET', '/auth/me'));
      s.userId = me.userId;
      if (me.stepUpRequired) {
        s.steppedUp = true;
        await s.stepUp();
      }
      return s;
    }
    await sleep(3000);
  }
  throw new Error(`${login}: sign-in failed`);
}

/** The newest development OTP of {@code userId} in the API log since {@code since} (demo stack, log-codes on). */
function devCode(userId, since) {
  if (!existsSync(API_LOG)) return null;
  const lines = readFileSync(API_LOG, 'utf8').split('\n').filter((l) => l.includes('devCode=') && l.includes(`"userId":"${userId}"`));
  for (const line of lines.reverse()) {
    const at = /"@timestamp":"([^"]+)"/.exec(line)?.[1];
    const code = /devCode=(\d{6})/.exec(line)?.[1];
    if (code && at && Date.parse(at) >= since - 1000) return code;
  }
  return null;
}

/** A Checker's decision, answering the OTP challenge (428) with the development code. */
async function decide(checker, approvalId) {
  const since = Date.now();
  const body = { decision: 'APPROVE' };
  let r = await checker.call('POST', `/v1/approvals/${approvalId}/decision`, body);
  if (r.status !== 428) return r.status;
  const challenge = Number(checker.json(r).args?.[0]);
  for (let i = 0; i < 20; i++) {
    await sleep(300);
    const code = devCode(checker.userId, since);
    if (code) {
      r = await checker.call('POST', `/v1/approvals/${approvalId}/decision`, { ...body, otp: { challengeId: challenge, code } });
      return r.status;
    }
  }
  return 428;
}

// ------------------------------------------------------------------ database oracle (H2 shell, as frontend/e2e/support/db.ts)
function sqlRows(query) {
  const jar = readdirSync(resolve(RUNTIME, 'h2')).find((f) => /^h2-.*\.jar$/.test(f));
  const out = execFileSync(
    'java',
    [
      '-cp',
      resolve(RUNTIME, 'h2', jar),
      'org.h2.tools.Shell',
      '-url',
      `jdbc:h2:tcp://127.0.0.1:${9092 + OFFSET}/mem:fcilekha;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH`,
      '-user',
      'fci_payroll',
      '-password',
      readFileSync(resolve(RUNTIME, 'config/secrets/backend/spring.datasource.password'), 'utf8').trim(),
      '-sql',
      query,
    ],
    { encoding: 'utf8' },
  );
  if (/^Error: /m.test(out)) throw new Error(out);
  const lines = out.split(/\r?\n/).filter((l) => l.includes('|') || (l.trim() !== '' && !l.startsWith('(')));
  return lines.slice(1).map((l) => l.split('|').map((c) => c.trim()));
}
const one = (query) => sqlRows(query)[0]?.[0];

// ------------------------------------------------------------------ workload
let READS = [
  ['/menu', 'maker'],
  ['/auth/me', 'maker'],
  ['/v1/leave-encashment-arrears?page=0&size=20', 'maker'],
  ['/v1/arrear-events?page=0&size=20', 'maker'],
  ['/v1/jobs?page=0&size=20', 'maker'],
  ['/v1/exceptions?page=0&size=20', 'maker'],
  ['/v1/inbox?page=0&size=20', 'checker'],
  ['/v1/pay-components?page=0&size=20', 'maker'],
];
// Internals that must never reach the browser: a Java class or exception name, a stack frame, SQL state, an Oracle code.
const LEAKS = /\b[a-z]+\.[a-z]+\.[A-Za-z.]*[A-Z]\w*(Exception|Error)\b|\bat (in\.gov|org\.|java\.|jdk\.)|\.java:\d+|SQLState|SQL state|ORA-\d{5}|\bselect\b.*\bfrom\b/i;

/** Closed-loop readers for {@code ms}: every answer is recorded; a 5xx must be a clean problem. */
async function readLoad(sessions, ms, label) {
  const samples = [];
  const until = Date.now() + ms;
  await Promise.all(
    Array.from({ length: USERS }, async (_, u) => {
      while (Date.now() < until) {
        const [path, who] = READS[(u + samples.length) % READS.length];
        const r = await sessions[who].call('GET', path);
        samples.push({ path, ...r, text: r.status >= 500 ? r.text : '' });
        await sleep(150);
      }
    }),
  );
  const by = {};
  for (const s of samples) by[s.status] = (by[s.status] ?? 0) + 1;
  const sorted = samples.map((s) => s.ms).sort((a, b) => a - b);
  const p95 = sorted[Math.floor(sorted.length * 0.95)] ?? 0;
  console.log(`  ${label}: ${samples.length} requests, statuses ${JSON.stringify(by)}, p95 ${p95} ms, max ${sorted.at(-1)} ms`);
  return { samples, statuses: by, p95, max: sorted.at(-1) ?? 0 };
}

function cleanProblems(samples) {
  const bad = samples.filter((s) => s.status >= 500 && s.status !== 502 && s.status !== 503 && s.status !== 504).filter((s) => {
    let p = {};
    try {
      p = JSON.parse(s.text);
    } catch {
      return true;
    }
    const { instance: _path, ...rest } = p;
    return !p.correlationId || LEAKS.test(JSON.stringify(rest));
  });
  return bad;
}

async function leaveArrearRuns(maker, ms, context) {
  const outcome = { ok: 0, failed: 0, statuses: {} };
  const until = Date.now() + ms;
  while (Date.now() < until) {
    const r = await maker.call('POST', '/v1/leave-encashment-arrears/runs', {
      eis: context.eis,
      circularId: context.circularId,
      revisionEffective: context.effective,
      reason: 'Chaos run (resilience)',
    });
    outcome.statuses[r.status] = (outcome.statuses[r.status] ?? 0) + 1;
    if (r.status === 200) outcome.ok++;
    else outcome.failed++;
    await sleep(500);
  }
  return outcome;
}

// ------------------------------------------------------------------ processes
function listeningPid(port) {
  const out = execFileSync('powershell', [
    '-NoProfile',
    '-Command',
    `(Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess`,
  ], { encoding: 'utf8' }).trim();
  return out ? Number(out) : null;
}
function kill(mode) {
  const pid = listeningPid(PORT[mode]);
  if (!pid) throw new Error(`${mode}: nothing listens on ${PORT[mode]}`);
  execFileSync('powershell', ['-NoProfile', '-Command', `Stop-Process -Id ${pid} -Force`]);
  return pid;
}
function restartBackends() {
  execFileSync(
    'powershell',
    [
      '-NoProfile',
      '-ExecutionPolicy',
      'Bypass',
      '-File',
      resolve(ROOT, 'scripts/local/deploy-local.ps1'),
      '-Action',
      'backend',
      '-PortOffset',
      String(OFFSET),
      '-Runtime',
      RUNTIME,
      '-SkipBuild',
      '-OtpRequired',
      'Y',
      '-Chaos',
    ],
    { encoding: 'utf8', stdio: ['ignore', 'ignore', 'inherit'], timeout: 15 * 60_000 },
  );
}

// ------------------------------------------------------------------ run
async function main() {
  const report = { started: new Date().toISOString(), base: BASE, phaseSeconds: PHASE_MS / 1000, users: USERS, phases: {} };
  console.log(`Resilience run against ${BASE} (online ${await health('online')}, batch ${await health('batch')})`);
  await monkey('online', null);
  await monkey('batch', null);
  const maker = await signIn('maker.delhi@fci-demo.test', 'DH001', 'PAYROLL_MAKER');
  const checker = await signIn('ddo.delhi@fci-demo.test', 'DH001', 'PAYROLL_CHECKER');
  const sessions = { maker, checker };
  // Only the reads these desks may open: a 403 is the access model working, not a fault.
  const open = [];
  for (const [path, who] of READS) {
    const r = await sessions[who].call('GET', path);
    if (r.status === 200) open.push([path, who]);
    else console.log(`  (not in the workload: ${who} ${path} answers ${r.status})`);
  }
  READS = open;
  report.workload = READS.map(([path, who]) => `${who} ${path}`);

  console.log('1 baseline');
  const base = await readLoad(sessions, PHASE_MS / 2, 'baseline');
  const baseErrors = base.samples.filter((s) => s.status < 200 || s.status >= 300);
  check('baseline', 'every request answers 2xx without a fault', baseErrors.length === 0, baseErrors.slice(0, 3).map((s) => `${s.status} ${s.path}`).join(', '));
  report.phases.baseline = { statuses: base.statuses, p95: base.p95, max: base.max };

  console.log('2 latency assault (1-3 s on one call in five)');
  await monkey('online', LATENCY);
  const slow = await readLoad(sessions, PHASE_MS, 'latency');
  await monkey('online', null);
  check('latency', 'no request hangs past the client timeout', slow.samples.every((s) => s.status !== 0), `${slow.samples.filter((s) => s.status === 0).length} timed out`);
  const slowBad = cleanProblems(slow.samples);
  check('latency', 'every 5xx is a clean ProblemDetail', slowBad.length === 0, slowBad.slice(0, 2).map((s) => `${s.status} ${s.path}`).join(', '));
  report.phases.latency = { statuses: slow.statuses, p95: slow.p95, max: slow.max };

  console.log('3 exception assault (RuntimeException in one call in twenty-five) with I-24 runs');
  const context = {
    eis: '104882',
    circularId: Number(one("SELECT circular_order_id FROM circular_order WHERE circular_number = 'DEMO/DA-REV/LEA-01'")),
    effective: (() => {
      const d = new Date(Date.now() + 5.5 * 3600_000);
      const m = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - 3, 1));
      return `${m.getUTCFullYear()}-${String(m.getUTCMonth() + 1).padStart(2, '0')}-01`;
    })(),
  };
  const employee = `(SELECT employee_id FROM employee WHERE emp_num = '104882' AND parent_zone_id = (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'))`;
  const lastEvent = Number(one('SELECT COALESCE(MAX(arrear_event_id), 0) FROM arrear_event'));
  await monkey('online', EXCEPTIONS);
  const [faulty, runs] = await Promise.all([readLoad(sessions, PHASE_MS, 'exceptions'), leaveArrearRuns(maker, PHASE_MS, context)]);
  await monkey('online', null);
  console.log(`  I-24 runs under assault: ${JSON.stringify(runs.statuses)}`);
  const faultyBad = cleanProblems(faulty.samples);
  check('exceptions', 'every 5xx is a ProblemDetail with a correlationId and no internals', faultyBad.length === 0, faultyBad.slice(0, 2).map((s) => `${s.status} ${s.path} ${s.text.slice(0, 120)}`).join(' | '));
  check('exceptions', 'some requests did fail (the assault was active)', Object.keys(faulty.statuses).some((s) => Number(s) >= 500), JSON.stringify(faulty.statuses));
  const events = Number(one(`SELECT COUNT(*) FROM arrear_event WHERE arrear_event_id > ${lastEvent} AND source_module = 'LEAVE'`));
  const empty = Number(
    one(
      `SELECT COUNT(*) FROM arrear_event e WHERE e.arrear_event_id > ${lastEvent} AND e.source_module = 'LEAVE' AND NOT EXISTS (SELECT 1 FROM leave_encashment_arrear a WHERE a.arrear_event_id = e.arrear_event_id)`,
    ),
  );
  const calculated = Number(one(`SELECT COUNT(*) FROM leave_encashment_arrear WHERE employee_id = ${employee} AND le_status = 'CALCULATED'`));
  check('exceptions', 'all or nothing: one event per successful run, none for a failed one', events === runs.ok, `${events} events, ${runs.ok} successful runs, ${runs.failed} failed`);
  check('exceptions', 'all or nothing: no event without its record', empty === 0, `${empty} empty events`);
  check('exceptions', 'one current (CALCULATED) record for the member', calculated <= 1, `${calculated}`);
  report.phases.exceptions = { statuses: faulty.statuses, runs, events, emptyEvents: empty, calculated };

  console.log('4 recovery (assaults off)');
  const after = await readLoad(sessions, PHASE_MS / 2, 'recovery');
  const afterErrors = after.samples.filter((s) => s.status < 200 || s.status >= 300);
  check('recovery', 'every request answers 2xx again', afterErrors.length === 0, afterErrors.slice(0, 3).map((s) => `${s.status} ${s.path}`).join(', '));
  check('recovery', 'online health UP', (await health('online')) === 'UP');
  report.phases.recovery = { statuses: after.statuses, p95: after.p95 };

  if (args['skip-kill'] !== 'true') {
    console.log('5 online instance killed');
    const pid = kill('online');
    const down = [];
    for (let i = 0; i < 5; i++) {
      down.push(await maker.call('GET', '/auth/me'));
      await sleep(1000);
    }
    console.log(`  killed pid ${pid}; while down: ${down.map((r) => `${r.status}/${r.ms}ms`).join(' ')}`);
    check('online-kill', 'the gateway answers fast while the API is down (no hang)', down.every((r) => r.status !== 0 && r.ms < 10_000));
    check('online-kill', 'no 2xx while the API is down', down.every((r) => r.status >= 500));
    restartBackends();
    const me = await maker.call('GET', '/auth/me');
    check('online-kill', 'the session from before the kill is still valid after the restart', me.status === 200, `${me.status}`);
    check('online-kill', 'online and batch health UP after the restart', (await health('online')) === 'UP' && (await health('batch')) === 'UP');
    report.phases.onlineKill = { pid, down: down.map((r) => ({ status: r.status, ms: r.ms })), meAfter: me.status };

    console.log('6 batch instance killed mid-job');
    const types = maker.json(await maker.call('GET', '/v1/jobs/types'));
    const locationId = types.locations?.find((l) => l.code === 'DH001')?.id;
    await monkey('batch', LATENCY);
    let jobId = null;
    for (const period of ['2026-05', '2026-04', '2026-03', '2026-02', '2026-01']) {
      const r = await maker.call('POST', '/v1/jobs', { jobType: 'PAYROLL_DRY_RUN', params: { locationId: String(locationId), payPeriod: period }, reason: 'Resilience run: batch node loss' });
      if (r.status < 300) {
        jobId = maker.json(r).id ?? maker.json(r).jobId ?? maker.json(r).job?.id;
        break;
      }
    }
    if (!jobId) {
      check('batch-kill', 'a dry run could be submitted', false);
    } else {
      const job = async () => maker.json(await maker.call('GET', `/v1/jobs/${jobId}`)).job ?? {};
      const decided = await decide(checker, (await job()).approvalId);
      let status = (await job()).status;
      for (let i = 0; i < 120 && !['RUNNING', 'COMPLETED', 'FAILED'].includes(status); i++) {
        await sleep(500);
        status = (await job()).status;
      }
      console.log(`  job ${jobId}: approval ${decided}, status ${status}`);
      if (status !== 'RUNNING') {
        check('batch-kill', 'the job was RUNNING when the node was killed', false, `status ${status}`);
      } else {
        const pid = kill('batch');
        restartBackends();
        let final = (await job()).status;
        for (let i = 0; i < 60 && ['RUNNING', 'PICKED'].includes(final); i++) {
          await sleep(1000);
          final = (await job()).status;
        }
        console.log(`  killed batch pid ${pid}; after the restart the job is ${final}`);
        check('batch-kill', 'crash recovery ends the killed job (never left RUNNING)', !['RUNNING', 'PICKED'].includes(final), final);
        report.phases.batchKill = { jobId, killedWhile: status, after: final };
      }
    }
    await monkey('batch', null).catch(() => {});
  }

  report.finished = new Date().toISOString();
  report.checks = checks;
  const out = resolve(ROOT, `docs/perf/resilience-${report.started.slice(0, 10)}.json`);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, JSON.stringify(report, null, 2));
  const failed = checks.filter((c) => !c.ok);
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed; report ${out}`);
  process.exit(failed.length ? 1 : 0);
}

main().catch(async (e) => {
  console.error(e);
  await monkey('online', null).catch(() => {});
  await monkey('batch', null).catch(() => {});
  process.exit(2);
});
