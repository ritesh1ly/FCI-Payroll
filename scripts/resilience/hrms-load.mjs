#!/usr/bin/env node
/**
 * Load, profile and chaos run of Payroll's HRMS gateway (owner 2026-11-06: "concurrent run of the API, profiling,
 * CPU and memory, turnaround time, response time", "use chaos monkey"). It drives the Administrator's API console
 * (POST /v1/integration/hrms-apis/{code}/call, X-03) so the whole path is measured: nginx -> Tomcat -> gateway
 * (contract check, token, pooled WebClient, bulkhead, retry) -> the HRMS test double -> reply classification.
 *
 *   # stack: deploy-local.ps1 ... -ExtraProfiles hrms-mock   (+ -Chaos on a chaos build for --mode chaos)
 *   node scripts/resilience/hrms-load.mjs --mode load  --levels 1,5,10,25,50 --seconds 20 [--api get_ota_status]
 *   node scripts/resilience/hrms-load.mjs --mode chaos --users 10 --phase-seconds 30
 *
 * load : for each concurrency level, N virtual users call in a closed loop for --seconds. Per level it reports
 *        requests, throughput, client turnaround (avg, p50, p90, p95, p99, max), the gateway's own time to the HRMS
 *        (elapsedMillis from the reply), outcomes and errors, and from the management port: process and system CPU,
 *        heap, GC pause time, live threads and the HTTP client pool (active / idle / pending connections).
 * chaos: baseline -> latency (Chaos Monkey) -> exceptions (Chaos Monkey) -> HRMS faults (mock triggers: 500, 502, 503,
 *        429, empty, truncated, error-in-200) -> recovery; checks that every answer is a clean reply or problem, no
 *        answer waits past the client timeout, no secret is in any answer, the bulkhead is never exceeded and every
 *        request succeeds again once the assaults stop.
 * Report: docs/perf/hrms-gateway-<mode>-<date>.json; exit code 1 when a check fails.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, a, i, all) => (a.startsWith('--') ? [...pairs, [a.slice(2), all[i + 1] ?? 'true']] : pairs), []),
);
const MODE = args.mode ?? 'load';
const BASE = args.base ?? 'https://localhost:8743';
const RUNTIME = resolve(ROOT, args.runtime ?? 'scripts/local/runtime-b');
const OFFSET = Number(args['port-offset'] ?? 300);
const SECONDS = Number(args.seconds ?? 20);
const PHASE_MS = Number(args['phase-seconds'] ?? 30) * 1000;
const LEVELS = String(args.levels ?? '1,5,10,25,50').split(',').map(Number);
const USERS = Number(args.users ?? 10);
const API = args.api ?? 'get_ota_status';
const QUERY = JSON.parse(args.query ?? '{"request_id":"4517","emp_ota_id":"1230"}');
const CLIENT_TIMEOUT_MS = 20_000;
const PASSWORD = 'Fci@Demo2026';
const API_LOG = resolve(RUNTIME, 'logs/online/api.json');
const MGMT = `http://127.0.0.1:${8081 + OFFSET}/api/actuator`;
if (new URL(BASE).hostname === 'localhost') process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));
const checks = [];
function check(phase, name, ok, detail) {
  checks.push({ phase, name, ok, detail });
  console.log(`  ${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` - ${detail}` : ''}`);
}

// ------------------------------------------------------------------ management port (metrics, Chaos Monkey)
function metricsAuth() {
  const handover = readFileSync(resolve(RUNTIME, 'config/HANDOVER.txt'), 'utf8');
  const password = /metrics \(Prometheus\) password : (\S+)/.exec(handover)?.[1];
  if (!password) throw new Error('metrics password not found in HANDOVER.txt');
  return `Basic ${Buffer.from(`metrics:${password}`).toString('base64')}`;
}
const AUTH = metricsAuth();

async function metric(name, tag) {
  try {
    const q = tag ? `?tag=${encodeURIComponent(tag)}` : '';
    const r = await fetch(`${MGMT}/metrics/${name}${q}`, { headers: { Authorization: AUTH }, signal: AbortSignal.timeout(20000) });
    // Each management call costs about 1 s (the Basic password is hashed per request) and nine run at once
    // while the load runs, so a short timeout returned null for every meter from 5 users on.
    if (!r.ok) return null;
    const m = {};
    for (const x of (await r.json()).measurements ?? []) m[x.statistic] = x.value;
    return m;
  } catch {
    return null;
  }
}

async function sample() {
  const pool = (n) => metric(`reactor.netty.connection.provider.${n}`, 'name:hrms-client');
  const [proc, sys, heap, threads, gc, active, idle, pending, total] = await Promise.all([
    metric('process.cpu.usage'),
    metric('system.cpu.usage'),
    metric('jvm.memory.used', 'area:heap'),
    metric('jvm.threads.live'),
    metric('jvm.gc.pause'),
    pool('active.connections'),
    pool('idle.connections'),
    pool('pending.connections'),
    pool('total.connections'),
  ]);
  return {
    procCpu: proc?.VALUE ?? null,
    sysCpu: sys?.VALUE ?? null,
    heapMb: heap ? heap.VALUE / 1048576 : null,
    threads: threads?.VALUE ?? null,
    gcCount: gc?.COUNT ?? null,
    gcSeconds: gc?.TOTAL_TIME ?? null,
    active: active?.VALUE ?? null,
    idle: idle?.VALUE ?? null,
    pending: pending?.VALUE ?? null,
    total: total?.VALUE ?? null,
  };
}

async function monkey(assaults) {
  const headers = { Authorization: AUTH, 'Content-Type': 'application/json' };
  if (assaults) {
    const set = await fetch(`${MGMT}/chaosmonkey/assaults`, { method: 'POST', headers, body: JSON.stringify(assaults) });
    if (!set.ok) throw new Error(`assaults ${set.status} ${await set.text()}`);
  }
  const r = await fetch(`${MGMT}/chaosmonkey/${assaults ? 'enable' : 'disable'}`, { method: 'POST', headers });
  if (!r.ok) throw new Error(`chaosmonkey ${r.status} ${await r.text()}`);
}
const LATENCY = { level: 5, latencyActive: true, latencyRangeStart: 500, latencyRangeEnd: 2000, exceptionsActive: false };
const EXCEPTIONS = { level: 10, latencyActive: false, exceptionsActive: true };

// ------------------------------------------------------------------ browser-protocol client (as chaos-run.mjs)
class Session {
  constructor() {
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
    const started = performance.now();
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
      if (r.status === 403 && !this.steppedUp && path !== '/auth/step-up' && text.includes('AUTH-403-STEP-UP')) {
        this.steppedUp = true;
        await this.stepUp();
        return this.call(method, path, body);
      }
      return { status: r.status, ms: performance.now() - started, text, type: r.headers.get('content-type') ?? '' };
    } catch (e) {
      return { status: 0, ms: performance.now() - started, text: '', type: '', error: e.name };
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

async function signIn() {
  for (let attempt = 0; attempt < 5; attempt++) {
    const s = new Session();
    await s.call('GET', '/auth/csrf');
    const r = await s.call('POST', '/auth/login', {
      loginId: 'sysadmin@fci-demo.test',
      password: PASSWORD,
      role: 'SYSTEM_ADMINISTRATOR',
      location: 'HQ001',
    });
    let signed = r.status === 200;
    if (r.status === 202) {
      // A sensitive role confirms the sign-in with the 6-digit code that the demo stack writes to its log.
      const since = Date.now();
      for (let i = 0; i < 30 && !signed; i++) {
        await sleep(300);
        const code = newestDevCode(since);
        if (code) signed = (await s.call('POST', '/auth/login/otp', { code })).status === 200;
        if (code) break;
      }
    }
    if (signed) {
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
  throw new Error('administrator sign-in failed');
}

/** The newest development OTP in the API log since {@code since}, whoever it is for (one sign-in at a time). */
function newestDevCode(since) {
  if (!existsSync(API_LOG)) return null;
  const lines = readFileSync(API_LOG, 'utf8').split(String.fromCharCode(10)).filter((l) => l.includes('devCode='));
  for (const line of lines.reverse()) {
    const at = /"@timestamp":"([^"]+)"/.exec(line)?.[1];
    const code = /devCode=(\d{6})/.exec(line)?.[1];
    if (code && at && Date.parse(at) >= since - 1000) return code;
  }
  return null;
}

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

// ------------------------------------------------------------------ measurement helpers
const percentile = (sorted, p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)] : null);
const round = (n, d = 1) => (n == null ? null : Number(n.toFixed(d)));

function callBody(api = API, query = QUERY) {
  return { query, body: null, reason: 'load and resilience test' };
}

/** N virtual users in a closed loop for {@code ms}; every answer is recorded. */
async function drive(session, users, ms, make = () => ({ api: API, body: callBody() })) {
  const stop = Date.now() + ms;
  const results = [];
  const loop = async () => {
    while (Date.now() < stop) {
      const { api, body } = make();
      const r = await session.call('POST', `/v1/integration/hrms-apis/${api}/call`, body);
      const reply = session.json(r);
      results.push({ status: r.status, ms: r.ms, outcome: reply.outcome ?? null, errorCode: reply.errorCode ?? reply.errorCode ?? null, gateway: reply.elapsedMillis ?? null, text: r.text, type: r.type, error: r.error ?? null });
    }
  };
  const sampler = (async () => {
    const samples = [];
    while (Date.now() < stop) {
      samples.push(await sample());
      await sleep(1000);
    }
    return samples;
  })();
  await Promise.all(Array.from({ length: users }, loop));
  return { results, samples: await sampler };
}

function summarise(results, samples, seconds) {
  const times = results.map((r) => r.ms).sort((a, b) => a - b);
  const gate = results.map((r) => r.gateway).filter((x) => x != null).sort((a, b) => a - b);
  const count = (key) => results.reduce((m, r) => ((m[r[key] ?? 'none'] = (m[r[key] ?? 'none'] ?? 0) + 1), m), {});
  const col = (k) => samples.map((s) => s[k]).filter((x) => x != null);
  const max = (a) => (a.length ? Math.max(...a) : null);
  const avg = (a) => (a.length ? a.reduce((x, y) => x + y, 0) / a.length : null);
  const gcSeconds = col('gcSeconds');
  return {
    requests: results.length,
    perSecond: round(results.length / seconds),
    turnaroundMs: { avg: round(avg(times)), p50: round(percentile(times, 50)), p90: round(percentile(times, 90)), p95: round(percentile(times, 95)), p99: round(percentile(times, 99)), max: round(max(times)) },
    gatewayToHrmsMs: { avg: round(avg(gate)), p95: round(percentile(gate, 95)), max: round(max(gate)) },
    http: count('status'),
    outcomes: count('outcome'),
    errorCodes: count('errorCode'),
    cpu: { processAvg: round(avg(col('procCpu')), 3), processMax: round(max(col('procCpu')), 3), systemMax: round(max(col('sysCpu')), 3) },
    heapMbMax: round(max(col('heapMb'))),
    threadsMax: max(col('threads')),
    gcPauseSecondsDuring: gcSeconds.length > 1 ? round(gcSeconds.at(-1) - gcSeconds[0], 3) : null,
    pool: { activeMax: max(col('active')), pendingMax: max(col('pending')), totalMax: max(col('total')) },
  };
}

const report = { mode: MODE, at: new Date().toISOString(), api: API, base: BASE, levels: {}, phases: {} };
function save() {
  report.checks = checks;
  const dir = resolve(ROOT, 'docs/perf');
  mkdirSync(dir, { recursive: true });
  const file = resolve(dir, `hrms-gateway-${MODE}-${new Date().toISOString().slice(0, 10)}.json`);
  writeFileSync(file, JSON.stringify(report, null, 2));
  console.log(`report: ${file}`);
}

// ------------------------------------------------------------------ modes
async function load(session) {
  console.log(`load: ${API}, levels ${LEVELS.join(',')}, ${SECONDS} s each`);
  await drive(session, 2, 3000); // warm-up: JIT, the token, the pool
  for (const users of LEVELS) {
    const { results, samples } = await drive(session, users, SECONDS * 1000);
    const s = summarise(results, samples, SECONDS);
    report.levels[users] = s;
    console.log(`  ${String(users).padStart(3)} users: ${s.perSecond}/s  turnaround p50 ${s.turnaroundMs.p50} p95 ${s.turnaroundMs.p95} p99 ${s.turnaroundMs.p99} max ${s.turnaroundMs.max} ms | gateway->HRMS p95 ${s.gatewayToHrmsMs.p95} ms | CPU ${s.cpu.processAvg} (max ${s.cpu.processMax}) | heap ${s.heapMbMax} MB | threads ${s.threadsMax} | pool active ${s.pool.activeMax} pending ${s.pool.pendingMax} | http ${JSON.stringify(s.http)}`);
    check(`load-${users}`, `${users} users: no request hangs past the client timeout`, results.every((r) => r.status !== 0 || r.error !== 'TimeoutError'));
    check(`load-${users}`, `${users} users: every answer is a reply or a clean problem`, results.every((r) => r.status === 200 || (r.type.includes('json') && /errorCode|"type"/.test(r.text))), JSON.stringify(s.http));
  }
}

const SECRET = /api-key|password|bearer\s+[a-z0-9]{8,}/i;
async function chaos(session) {
  const make = () => ({ api: API, body: callBody() });
  const phase = async (name, setup, teardown, extra = make) => {
    console.log(`phase ${name}`);
    if (setup) await setup();
    const { results, samples } = await drive(session, USERS, PHASE_MS, extra);
    if (teardown) await teardown();
    const s = summarise(results, samples, PHASE_MS / 1000);
    report.phases[name] = s;
    check(name, 'no answer waits past the client timeout', !results.some((r) => r.error === 'TimeoutError'), `max ${s.turnaroundMs.max} ms`);
    const clean = (r) => r.type.includes('json') && /correlationId/.test(r.text) && !/at [a-z.]+\.[A-Z]\w+\.\w+\(|SQLException|ORA-\d|\.java:\d/.test(r.text);
    // A one-line summary of the failure ("... failed after 1 attempt(s): RuntimeException: ...") is the documented reason of a 502 (SRS-INT-31); stack frames, SQL and file names are not.
    const dirty = results.filter((r) => r.status !== 200 && !clean(r));
    check(name, 'every non-200 answer is a clean problem with a correlation id and no internals', dirty.length === 0, dirty.length ? `${dirty.length} unclean, e.g. ${dirty[0].status} ${dirty[0].type} ${String(dirty[0].text).slice(0, 200)}` : JSON.stringify(s.http));
    check(name, 'no answer carries a key, password or token', !results.some((r) => SECRET.test(r.text)));
    check(name, 'the HTTP client pool never exceeds its bound', (s.pool.totalMax ?? 0) <= 50, `max ${s.pool.totalMax}`);
    console.log(`  ${s.requests} requests, p95 ${s.turnaroundMs.p95} ms, http ${JSON.stringify(s.http)}, outcomes ${JSON.stringify(s.outcomes)}`);
    return { results, s };
  };
  const base = await phase('baseline');
  check('baseline', 'every request answers 200 (or 429 when the HRMS rate limit is reached)', base.results.every((r) => r.status === 200 || r.status === 429), JSON.stringify(base.s.http));
  await phase('latency', () => monkey(LATENCY), () => monkey(null));
  await phase('exceptions', () => monkey(EXCEPTIONS), () => monkey(null));
  // The HRMS side: the test double's fault triggers, medical-claim status keyed by emp_num (999500 server error, 999502 HTML
  // bad gateway, 999503 with Retry-After, 999429 rate limit, 999204 empty body, 999299 truncated, 999200 error in a 200).
  const faults = ['999500', '999502', '999503', '999429', '999204', '999299', '999200'];
  let n = 0;
  await phase('hrms-faults', null, null, () => ({
    api: 'get_medical_claim_status',
    body: callBody('get_medical_claim_status', { emp_num: faults[n++ % faults.length], payment_id: '1' }),
  }));
  const rec = await phase('recovery');
  check('recovery', 'every request answers 200 again (or 429 at the HRMS rate limit)', rec.results.every((r) => r.status === 200 || r.status === 429), JSON.stringify(rec.s.http));
}

// ------------------------------------------------------------------ main
const session = await signIn();
try {
  if (MODE === 'load') await load(session);
  else if (MODE === 'chaos') await chaos(session);
  else throw new Error(`unknown --mode ${MODE}`);
} finally {
  try {
    await monkey(null);
  } catch {
    // the monkey is not there on a normal build
  }
  save();
}
const failed = checks.filter((c) => !c.ok);
console.log(`${checks.length - failed.length} / ${checks.length} checks passed`);
process.exit(failed.length ? 1 : 0);
