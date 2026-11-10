/**
 * Shared client of a local FCILekha stack for the campaign scripts (resilience, load, profiling): browser-protocol
 * sessions (CSRF, cookies, idempotency key, the OTP step-up of sensitive screens), the Checker's decision with the
 * development OTP, the H2 database oracle, Chaos Monkey control and the processes of the stack.
 *
 * Needs a stack deployed by scripts/local/deploy-local.ps1 with the demo data (development OTPs are written to the
 * API log, log-codes on). The same conventions as scripts/resilience/chaos-run.mjs and scripts/perf/load-module.mjs.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';

export const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

export function stack({ root, base = 'https://localhost:8743', runtime = 'scripts/local/runtime-b', offset = 300 } = {}) {
  const RUNTIME = resolve(root, runtime);
  const API_LOG = resolve(RUNTIME, 'logs/online/api.json');
  const PASSWORD = 'Fci@Demo2026';
  const CLIENT_TIMEOUT_MS = 30_000;
  const MGMT = {
    online: `http://127.0.0.1:${8081 + offset}/api/actuator`,
    batch: `http://127.0.0.1:${8091 + offset}/api/actuator`,
  };
  const PORT = { online: 8080 + offset, batch: 8090 + offset };
  if (new URL(base).hostname === 'localhost') process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

  /** The newest development OTP of {@code userId} in the API log since {@code since}. */
  function devCode(userId, since) {
    if (!existsSync(API_LOG)) return null;
    const lines = readFileSync(API_LOG, 'utf8')
      .split('\n')
      .filter((l) => l.includes('devCode=') && l.includes(`"userId":"${userId}"`));
    for (const line of lines.reverse()) {
      const at = /"@timestamp":"([^"]+)"/.exec(line)?.[1];
      const code = /devCode=(\d{6})/.exec(line)?.[1];
      if (code && at && Date.parse(at) >= since - 1000) return code;
    }
    return null;
  }

  class Session {
    constructor(login) {
      this.login = login;
      this.cookies = new Map();
      this.headers = {};
    }
    header() {
      return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
    }
    async call(method, path, body) {
      const headers = { Accept: 'application/json', Cookie: this.header(), ...this.headers };
      if (method !== 'GET') {
        headers['Content-Type'] = 'application/json';
        headers['X-XSRF-TOKEN'] = decodeURIComponent(this.cookies.get('XSRF-TOKEN') ?? '');
        headers['Idempotency-Key'] = crypto.randomUUID();
      }
      const started = Date.now();
      try {
        const r = await fetch(`${base}/api${path}`, {
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
        return { status: r.status, ms: Date.now() - started, text };
      } catch (e) {
        return { status: 0, ms: Date.now() - started, text: '', error: e.name };
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

  /**
   * The sign-in OTP (ADR-0026) of one login request: the development sender logs the code with the
   * request's correlation id, which the client chose (X-Correlation-Id), so it never takes another
   * sign-in's code.
   */
  async function loginCode(correlationId, timeoutMs = 15_000) {
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
      if (existsSync(API_LOG)) {
        const text = readFileSync(API_LOG, 'utf8');
        const tail = text.slice(Math.max(0, text.length - 2_000_000)).split(/\r?\n/);
        for (const line of tail.reverse()) {
          if (line.includes('purpose=AUTH_LOGIN') && line.includes(`"correlationId":"${correlationId}"`)) {
            const code = /devCode=(\d{6})/.exec(line)?.[1];
            if (code) return code;
          }
        }
      }
      await sleep(300);
    }
    return null;
  }

  /**
   * Signs in with the shared demo password: the password, then the sign-in OTP when the server asks
   * for it (202, OD-36), then the step-up of sensitive screens, all answered from the API log.
   */
  async function signIn(login, office, role) {
    let last = '';
    for (let attempt = 0; attempt < 3; attempt++) {
      const s = new Session(login);
      await s.call('GET', '/auth/csrf');
      const correlationId = crypto.randomUUID();
      s.headers = { 'X-Correlation-Id': correlationId };
      let r = await s.call('POST', '/auth/login', { loginId: login, password: PASSWORD, role, location: office });
      s.headers = {};
      if (r.status === 202) {
        const code = await loginCode(correlationId);
        if (code) r = await s.call('POST', '/auth/login/otp', { code });
      }
      if (r.status === 200) {
        const me = s.json(await s.call('GET', '/auth/me'));
        s.userId = me.userId;
        if (me.stepUpRequired) {
          s.steppedUp = true;
          await s.stepUp();
        }
        return s;
      }
      last = `${r.status} ${r.text.slice(0, 200)}`;
      await sleep(2000);
    }
    throw new Error(`${login}: sign-in failed (${last})`);
  }

  /** A Checker's decision, answering an OTP challenge (428) with the development code. */
  async function decide(checker, approvalId, decision = 'APPROVE', reason = undefined) {
    const since = Date.now();
    const body = { decision, ...(reason ? { reason } : {}) };
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

  /** Rows of a read-only query on the stack's H2 database (the test oracle; never used to change data). */
  function sqlRows(query) {
    const jar = readdirSync(resolve(RUNTIME, 'h2')).find((f) => /^h2-.*\.jar$/.test(f));
    const out = execFileSync(
      'java',
      [
        '-cp',
        resolve(RUNTIME, 'h2', jar),
        'org.h2.tools.Shell',
        '-url',
        `jdbc:h2:tcp://127.0.0.1:${9092 + offset}/mem:fcilekha;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH`,
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

  function metricsAuth() {
    const handover = readFileSync(resolve(RUNTIME, 'config/HANDOVER.txt'), 'utf8');
    const password = /metrics \(Prometheus\) password : (\S+)/.exec(handover)?.[1];
    if (!password) throw new Error('metrics password not found in HANDOVER.txt');
    return `Basic ${Buffer.from(`metrics:${password}`).toString('base64')}`;
  }

  /** Chaos Monkey on an instance: the assaults to set and enable, or null to disable. */
  async function monkey(mode, assaults) {
    const headers = { Authorization: metricsAuth(), 'Content-Type': 'application/json' };
    if (assaults) {
      const set = await fetch(`${MGMT[mode]}/chaosmonkey/assaults`, { method: 'POST', headers, body: JSON.stringify(assaults) });
      if (!set.ok) throw new Error(`${mode}: assaults ${set.status} ${await set.text()}`);
    }
    const r = await fetch(`${MGMT[mode]}/chaosmonkey/${assaults ? 'enable' : 'disable'}`, { method: 'POST', headers });
    if (!r.ok) throw new Error(`${mode}: chaosmonkey ${r.status} ${await r.text()}`);
  }

  async function health(mode) {
    try {
      const r = await fetch(`${MGMT[mode]}/health`, { signal: AbortSignal.timeout(5000) });
      return r.ok ? (await r.json()).status : `HTTP ${r.status}`;
    } catch (e) {
      return `DOWN (${e.cause?.code ?? e.name})`;
    }
  }

  function listeningPid(mode) {
    const out = execFileSync(
      'powershell',
      [
        '-NoProfile',
        '-Command',
        `(Get-NetTCPConnection -LocalPort ${PORT[mode]} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess`,
      ],
      { encoding: 'utf8' },
    ).trim();
    return out ? Number(out) : null;
  }

  /** Kills an instance of this stack only (by its own port), as a crash would. */
  function kill(mode) {
    const pid = listeningPid(mode);
    if (!pid) throw new Error(`${mode}: nothing listens on ${PORT[mode]}`);
    execFileSync('powershell', ['-NoProfile', '-Command', `Stop-Process -Id ${pid} -Force`]);
    return pid;
  }

  /** Restarts the online and batch Tomcats of this stack (data kept). */
  function restartBackends(extra = []) {
    execFileSync(
      'powershell',
      [
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        resolve(root, 'scripts/local/deploy-local.ps1'),
        '-Action',
        'backend',
        '-PortOffset',
        String(offset),
        '-Runtime',
        RUNTIME,
        '-SkipBuild',
        '-OtpRequired',
        'Y',
        ...extra,
      ],
      { encoding: 'utf8', stdio: ['ignore', 'ignore', 'inherit'], timeout: 15 * 60_000 },
    );
  }

  return { Session, signIn, decide, devCode, sqlRows, one, monkey, health, listeningPid, kill, restartBackends, MGMT, PORT, RUNTIME };
}
