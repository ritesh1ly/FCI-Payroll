#!/usr/bin/env node
/**
 * Black-box attack probe of a deployed FCILekha stack (OWASP WSTG / ASVS 4.0 L2 style): what an anonymous internet
 * attacker and a signed-in user trying to escalate can reach. Every check states the attack and the expected refusal;
 * the run prints PASS / FAIL per check and exits 1 on any FAIL. Read-only towards business data except sign-in /
 * sign-out of demo users (never their lockout: the brute-force checks use unknown login ids).
 *
 *   node scripts/security/attack-probe.mjs [--base https://localhost:8743]
 *
 * Needs the local demo stack (scripts/local/deploy-local.ps1, -OtpRequired Y): the sign-in OTPs are read from the
 * API log and object ids from the H2 database (scripts/support/fci-client.mjs), as the other campaign scripts do.
 */
import { readFileSync } from 'node:fs';
import { request } from 'node:https';
import { stack } from '../support/fci-client.mjs';

const args = process.argv.slice(2);
const base = args.includes('--base') ? args[args.indexOf('--base') + 1] : 'https://localhost:8743';
const root = new URL('../..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const fci = stack({ root, base });
const results = [];

function check(id, title, ok, detail = '') {
  results.push({ id, title, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${title}${detail ? `  [${detail}]` : ''}`);
}

async function raw(path, init = {}) {
  try {
    const r = await fetch(`${base}${path}`, { redirect: 'manual', signal: AbortSignal.timeout(15_000), ...init });
    return { status: r.status, headers: r.headers, text: await r.text() };
  } catch (e) {
    return { status: 0, headers: new Headers(), text: String(e.cause?.code ?? e.name) };
  }
}

/** A request with any method (fetch refuses TRACE / TRACK itself). */
function anyMethod(method, path) {
  const local = ['localhost', '127.0.0.1'].includes(new URL(base).hostname);
  return new Promise((done) => {
    const req = request(`${base}${path}`, { method, rejectUnauthorized: !local }, (res) => {
      res.resume();
      res.on('end', () => done(res.statusCode));
    });
    req.on('error', () => done(0));
    req.end();
  });
}

const refused = (s) => s === 401 || s === 403 || s === 404 || s === 405;
const leaks = (text) => /at [a-z]+\.[a-z.]+\(|Exception|org\.springframework|org\.hibernate|SQL|ORA-\d|jdbc:/i.test(text);

// ------------------------------------------------------------------ anonymous attacker
async function anonymous() {
  for (const path of [
    '/api/auth/me',
    '/api/menu',
    '/api/v1/employees?page=0&size=20',
    '/api/v1/admin/users?page=0&size=20',
    '/api/v1/admin/org',
    '/api/v1/inbox?page=0&size=20',
    '/api/v1/audit/history?entity=rule_version&key=OTP_VALIDITY',
    '/api/v1/payslips/mine/2026-10',
    '/api/v1/reference/lists',
  ]) {
    const r = await raw(path);
    check('AN-1', `anonymous GET ${path.split('?')[0]} refused`, r.status === 401, `HTTP ${r.status}`);
  }

  // Management, documentation, consoles and source files are never published.
  for (const path of [
    '/api/actuator',
    '/api/actuator/health',
    '/api/actuator/env',
    '/api/actuator/heapdump',
    '/api/actuator/prometheus',
    '/actuator/env',
    '/api/v3/api-docs',
    '/api/swagger-ui/index.html',
    '/api/h2-console',
    '/h2-console',
    '/api/WEB-INF/web.xml',
    '/api/META-INF/MANIFEST.MF',
    '/.env',
    '/nginx/.env',
    '/.git/config',
    '/nginx/.git/config',
    '/nginx/_next/../../../../etc/passwd',
    '/nginx/_next/static/..%2f..%2f..%2fpackage.json',
    '/nginx/package.json',
    '/nginx/server.js',
    '/nginx/.next/BUILD_ID',
  ]) {
    const r = await raw(path);
    const leaked = r.status === 200 && !/<!DOCTYPE html>/i.test(r.text);
    check('AN-2', `no exposure of ${path}`, !leaked && r.status !== 500, `HTTP ${r.status}`);
  }

  // Methods: only what each location needs.
  for (const [method, path] of [
    ['TRACE', '/nginx'],
    ['TRACK', '/nginx'],
    ['PUT', '/nginx'],
    ['DELETE', '/nginx/payroll'],
    ['TRACE', '/api/auth/login-options'],
    ['TRACE', '/api/v1/employees'],
  ]) {
    const status = await anyMethod(method, path);
    check('AN-3', `${method} ${path} refused`, status === 403 || status === 405 || status === 404, `HTTP ${status}`);
  }

  // CORS: no other origin may read the API with the user's cookies.
  for (const method of ['GET', 'OPTIONS']) {
    const r = await raw('/api/auth/login-options', {
      method,
      headers: { Origin: 'https://evil.example', 'Access-Control-Request-Method': 'POST' },
    });
    const acao = r.headers.get('access-control-allow-origin');
    const acac = r.headers.get('access-control-allow-credentials');
    check('AN-4', `CORS ${method} from another origin not allowed`, !acao && acac !== 'true', `ACAO=${acao} ACAC=${acac}`);
  }

  // Host header and redirects never point elsewhere.
  for (const [path, headers] of [
    ['/', { Host: 'evil.example' }],
    ['/', { 'X-Forwarded-Host': 'evil.example' }],
    ['/payroll', { 'X-Forwarded-Host': 'evil.example' }],
    ['//evil.example/', {}],
    ['/\\evil.example/', {}],
    ['/nginx//evil.example', {}],
    ['/%2F%2Fevil.example', {}],
    ['/nginx?next=https://evil.example', {}],
  ]) {
    const r = await raw(path, { headers });
    const location = r.headers.get('location') ?? '';
    // Off-site = the browser would leave our origin (an absolute or protocol-relative URL to another host).
    const offsite = location !== '' && new URL(location, base).host !== new URL(base).host;
    check('AN-5', `no open redirect: ${path} ${JSON.stringify(headers)}`, !offsite, `HTTP ${r.status} Location=${location}`);
  }

  // Hardening headers on the page and on the API.
  const page = await raw('/nginx');
  const h = (name) => page.headers.get(name) ?? '';
  check('AN-6', 'page HSTS', /max-age=\d{7,}/.test(h('strict-transport-security')), h('strict-transport-security'));
  check('AN-6', 'page CSP with frame-ancestors none and no unsafe-inline script', /frame-ancestors 'none'/.test(h('content-security-policy')) && !/script-src[^;]*'unsafe-inline'/.test(h('content-security-policy')), h('content-security-policy').slice(0, 120));
  check('AN-6', 'page nosniff', h('x-content-type-options') === 'nosniff');
  check('AN-6', 'page not cached (credentials page)', /no-store/.test(h('cache-control')), h('cache-control'));
  check('AN-6', 'no X-Powered-By, no server version', !page.headers.get('x-powered-by') && !/\d/.test(h('server')), `server=${h('server')}`);
  const api = await raw('/api/auth/login-options');
  check('AN-6', 'API nosniff + no-store', api.headers.get('x-content-type-options') === 'nosniff' && /no-store/.test(api.headers.get('cache-control') ?? ''));

  // CSRF: a state change without the token is refused.
  const csrf = await raw('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ loginId: 'nobody@fci-demo.test', password: 'x' }),
  });
  check('AN-7', 'POST without CSRF token refused', csrf.status === 403, `HTTP ${csrf.status}`);

  // Error answers never leak internals.
  const s = new fci.Session('anon');
  await s.call('GET', '/auth/csrf');
  for (const body of ['{"loginId":', '[]', '{"loginId":"\' OR 1=1 --","password":"x"}', `{"loginId":"${'a'.repeat(5000)}","password":"x"}`]) {
    const r = await fetch(`${base}/api/auth/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: s.header(),
        'X-XSRF-TOKEN': decodeURIComponent(s.cookies.get('XSRF-TOKEN') ?? ''),
      },
      body,
    });
    const text = await r.text();
    check('AN-8', `malformed sign-in body answered without internals (${body.slice(0, 20)}...)`, r.status < 500 && !leaks(text), `HTTP ${r.status}`);
  }
  for (const path of ["/api/v1/employees/1'%20OR%20'1'='1", '/api/v1/employees/%00', '/api/v1/employees/..%2f..%2fadmin']) {
    const r = await raw(path);
    check('AN-8', `odd path ${path} refused without internals`, refused(r.status) || r.status === 400, `HTTP ${r.status}`);
  }

  // Account enumeration: an unknown login and a wrong password look the same.
  async function attempt(loginId) {
    const t = new fci.Session(loginId);
    await t.call('GET', '/auth/csrf');
    const started = Date.now();
    const r = await t.call('POST', '/auth/login', { loginId, password: 'Wrong@Pass123' });
    return { status: r.status, code: t.json(r).code ?? t.json(r).errorCode ?? r.text.slice(0, 80), ms: Date.now() - started };
  }
  const unknown = await attempt(`ghost.${Date.now()}@fci-demo.test`);
  const wrong = await attempt(`ghost2.${Date.now()}@fci-demo.test`);
  check('AN-9', 'unknown login and wrong password give the same answer', unknown.status === wrong.status && unknown.code === wrong.code, `${unknown.status}/${unknown.code} vs ${wrong.status}/${wrong.code}`);
  check('AN-9', 'a refused sign-in waits the failure floor (slows guessing)', unknown.ms >= 500, `${unknown.ms} ms`);

  // Session cookie: Secure, HttpOnly, SameSite, host-only.
  const login = await raw('/api/auth/csrf');
  const cookies = login.headers.getSetCookie?.() ?? [];
  const xsrf = cookies.find((c) => c.startsWith('XSRF-TOKEN')) ?? '';
  check('AN-10', 'XSRF cookie Secure + SameSite', /;\s*Secure/i.test(xsrf) && /SameSite=(Strict|Lax)/i.test(xsrf), xsrf.replace(/=[^;]+/, '=…'));
}

// ------------------------------------------------------------------ recovery flows and the OTP
async function recoveryAndOtp() {
  async function post(path, body, extra = {}) {
    const t = new fci.Session('probe');
    await t.call('GET', '/auth/csrf');
    t.headers = extra;
    const r = await t.call('POST', path, body);
    return { r, t, code: t.json(r).code ?? t.json(r).errorCode ?? '', status: r.status };
  }
  // Forgotten password: a known and an unknown login id are answered alike (no account discovery).
  const known = await post('/auth/password/forgot', { loginId: 'pm.dh001@fci-demo.test' });
  const unknown = await post('/auth/password/forgot', { loginId: `nobody.${Date.now()}@fci-demo.test` });
  check('AN-11', 'forgot password: known and unknown login answered alike', known.status === unknown.status && known.r.text === unknown.r.text, `${known.status} vs ${unknown.status}`);
  // Mobile sign-in: a registered and an unregistered number are answered alike.
  const mobile = fci.one("SELECT registered_mobile FROM app_user WHERE login_id = 'pm.dh001@fci-demo.test'");
  if (mobile) {
    const reg = await post('/auth/login/mobile', { mobile });
    const unreg = await post('/auth/login/mobile', { mobile: '9000000001' });
    check('AN-12', 'mobile sign-in: registered and unregistered number answered alike', reg.status === unreg.status && reg.code === unreg.code, `${reg.status}/${reg.code} vs ${unreg.status}/${unreg.code}`);
  }
  // A reset with a guessed code is refused, never a password change.
  const reset = await post('/auth/password/reset', { loginId: 'pm.dh001@fci-demo.test', code: '000000', newPassword: 'Probe@Attack2026x' });
  check('AN-11', 'password reset with a guessed code refused', reset.status >= 400 && reset.status < 500, `HTTP ${reset.status}`);
  // No password oracle (S-1): the CURRENT password with a wrong code is "wrong code", never "reused".
  const oracle = await post('/auth/password/reset', { loginId: 'pm.dh001@fci-demo.test', code: '000000', newPassword: 'Fci@Demo2026' });
  check('AN-11', 'reset with the current password and a wrong code tells nothing (no password oracle)', !/REUSED/i.test(oracle.r.text), `HTTP ${oracle.status} ${oracle.code}`);
  // The account-discovery answers wait the failure floor for a known and an unknown identifier alike.
  const t0 = Date.now();
  await post('/auth/password/forgot', { loginId: `nobody.${Date.now()}@fci-demo.test` });
  const unknownMs = Date.now() - t0;
  check('AN-11', 'forgot password for an unknown login waits the failure floor', unknownMs >= 500, `${unknownMs} ms`);

  // Sign-in OTP guessing: five wrong codes burn the challenge; the right code is then refused too.
  const correlationId = crypto.randomUUID();
  const started = await post(
    '/auth/login',
    { loginId: 'pm.dh001@fci-demo.test', password: 'Fci@Demo2026', role: 'PAYROLL_MAKER', location: 'DH001' },
    { 'X-Correlation-Id': correlationId },
  );
  if (started.status === 202) {
    const s = started.t;
    s.headers = {};
    const wrong = [];
    for (let i = 0; i < 5; i++) wrong.push((await s.call('POST', '/auth/login/otp', { code: String(100000 + i) })).status);
    // The real code, read from the development log; it must be dead now.
    let realCode = null;
    for (let i = 0; i < 30 && !realCode; i++) {
      const hit = readFileSync(`${fci.RUNTIME}/logs/online/api.json`, 'utf8')
        .split(/\r?\n/)
        .reverse()
        .find((l) => l.includes('purpose=AUTH_LOGIN') && l.includes(`"correlationId":"${correlationId}"`));
      realCode = /devCode=(\d{6})/.exec(hit ?? '')?.[1] ?? null;
      if (!realCode) await new Promise((ok) => setTimeout(ok, 300));
    }
    const after = realCode ? (await s.call('POST', '/auth/login/otp', { code: realCode })).status : 'no code';
    check('AN-13', 'five wrong sign-in OTPs refused', wrong.every((w) => w === 401 || w === 400 || w === 429), wrong.join(','));
    check('AN-13', 'the right code after the lockout is refused (challenge burned)', after !== 200, `HTTP ${after}`);
    const fresh = await fci.signIn('pm.dh001@fci-demo.test', 'DH001', 'PAYROLL_MAKER');
    check('AN-13', 'the account itself is not locked by OTP guessing (no denial of service)', Boolean(fresh));
    await fresh.call('POST', '/auth/logout', {});
  } else {
    check('AN-13', 'sign-in OTP guessing: the stack asks no sign-in OTP', true, `HTTP ${started.status} (skipped)`);
  }
}

// ------------------------------------------------------------------ brute force (unknown ids only)
async function bruteForce() {
  const s = new fci.Session('bf');
  await s.call('GET', '/auth/csrf');
  const statuses = [];
  const id = `bf.${Date.now()}@fci-demo.test`;
  for (let i = 0; i < 40; i++) {
    statuses.push((await s.call('POST', '/auth/login', { loginId: id, password: `Guess${i}@x1` })).status);
  }
  check('BF-1', 'password guessing on one login id is throttled (429 within 40 tries)', statuses.includes(429), statuses.join(','));
}

// ------------------------------------------------------------------ signed-in users trying to escalate
async function insiders() {
  const one = fci.one;
  const employee = await fci.signIn('emp.rsharma@fci-demo.test', undefined, 'EMPLOYEE_ESEVA');
  const maker = await fci.signIn('maker.delhi@fci-demo.test', 'DH001', 'PAYROLL_MAKER');

  // Function level: the employee self-service role reaches no desk or admin API.
  for (const path of [
    '/v1/employees?page=0&size=20',
    '/v1/admin/users?page=0&size=20',
    '/v1/admin/org',
    `/v1/admin/audit?from=${new Date(Date.now() - 86_400_000).toISOString()}&to=${new Date(Date.now() + 60_000).toISOString()}&field=LOGIN`,
    '/v1/inbox?page=0&size=20',
    '/v1/audit/history?entity=rule_version&key=OTP_VALIDITY',
    '/v1/cycles/2026-10/results?page=0&size=20',
  ]) {
    const r = await employee.call('GET', path);
    check('IN-1', `eSeva employee GET ${path.split('?')[0]} refused`, r.status === 403 || r.status === 404, `HTTP ${r.status}`);
  }

  // Object level: another member's record by its number.
  const ownEis = one("SELECT e.emp_num FROM employee e JOIN app_user u ON u.employee_id = e.employee_id WHERE u.login_id = 'emp.rsharma@fci-demo.test'");
  const otherEis = one(`SELECT emp_num FROM employee WHERE emp_num <> '${ownEis}' AND employee_type = 'STAFF' FETCH FIRST 1 ROWS ONLY`);
  for (const path of [`/v1/employees/${otherEis}`, `/v1/employees/${otherEis}/allowance-flags`, `/v1/employees/${otherEis}/lifecycle-events`]) {
    const r = await employee.call('GET', path);
    check('IN-2', `eSeva employee reads another member (${path.replace(otherEis, '{eis}')}) refused`, r.status === 403 || r.status === 404, `HTTP ${r.status}`);
  }

  // Office scope: a Delhi maker and a member of another zone.
  const outside = one(
    "SELECT e.emp_num FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code NOT LIKE 'DH%' AND e.employee_type = 'STAFF' AND e.location_id NOT IN (SELECT location_id FROM org_location WHERE location_code = 'DH001') FETCH FIRST 1 ROWS ONLY",
  );
  for (const path of [`/v1/employees/${outside}`, `/v1/employees/${outside}/transfers`, `/v1/employees/${outside}/allowance-flags/history`]) {
    const r = await maker.call('GET', path);
    check('IN-3', `DH001 maker reads a member outside its office (${path.replace(outside, '{eis}')}) refused`, r.status === 403 || r.status === 404, `HTTP ${r.status}`);
  }

  // Scope switch to a role or office the user does not hold.
  for (const body of [
    { role: 'SYSTEM_ADMINISTRATOR', location: 'HQ001' },
    { role: 'PAYROLL_MAKER', location: 'HQ001' },
    { role: 'SUPER_USER', location: null },
  ]) {
    const r = await maker.call('POST', '/auth/switch-scope', body);
    check('IN-4', `switch to an unheld scope ${JSON.stringify(body)} refused`, r.status >= 400 && r.status < 500 && r.status !== 428, `HTTP ${r.status}`);
  }
  const me = maker.json(await maker.call('GET', '/auth/me'));
  check('IN-4', 'the maker is still the Delhi maker', me.role === 'PAYROLL_MAKER' && me.location?.code === 'DH001', `${me.role} ${me.location?.code}`);

  // Nested file ids: a file of one record asked through another record's route.
  const foreign = one("SELECT attachment_id FROM document_attachment WHERE entity_name = 'external_income_declaration' FETCH FIRST 1 ROWS ONLY");
  const superuser = await fci.signIn('su.hq@fci-demo.test', 'HQ001', 'SUPER_USER').catch(() => null);
  const asker = superuser ?? maker;
  for (const [entity, route] of [
    ['supplementary_payment', (id) => `/v1/supplementary/${id}/files/${foreign}`],
    ['off_cycle_payment', (id) => `/v1/off-cycle/${id}/files/${foreign}`],
    ['reimbursement_claim', (id) => `/v1/claims/${id}/documents/${foreign}`],
    ['hrms_fallback_upload', (id) => `/v1/hrms-fallback/uploads/${id}/files/${foreign}`],
    ['pf_sanction_document', (id) => `/v1/cpf/sanctions/${id}/documents/${foreign}`],
  ]) {
    const key = one(`SELECT entity_key FROM document_attachment WHERE entity_name = '${entity}' FETCH FIRST 1 ROWS ONLY`);
    if (!key || !foreign) {
      check('IN-5', `${entity}: no demo record to probe`, true, 'skipped');
      continue;
    }
    const label = `${route('{id}').slice(0, -String(foreign).length)}{file}`;
    // Control: the record's own file through its own route is served to the same user, so a refusal below is
    // the file check, not a missing permission on the record.
    const ownFile = one(`SELECT attachment_id FROM document_attachment WHERE entity_name = '${entity}' AND entity_key = '${key}' FETCH FIRST 1 ROWS ONLY`);
    const control = await asker.call('GET', route(key).slice(0, -String(foreign).length) + ownFile);
    const r = await asker.call('GET', route(key));
    check(
      'IN-5',
      `file of another record through ${label} refused (own file: HTTP ${control.status})`,
      (r.status === 403 || r.status === 404) && control.status !== r.status,
      `foreign HTTP ${r.status}`,
    );
  }

  // Maker-checker: the maker cannot decide (or approve) an item, least of all its own.
  const own = one("SELECT approval_id FROM approval_queue WHERE status = 'PENDING_APPROVAL' AND submitted_by = 'maker.delhi@fci-demo.test' FETCH FIRST 1 ROWS ONLY");
  if (own) {
    const r = await maker.call('POST', `/v1/approvals/${own}/decision`, { decision: 'APPROVE' });
    check('IN-6', 'maker approving its own item refused', r.status === 403 || r.status === 409 || r.status === 422, `HTTP ${r.status}`);
  } else {
    check('IN-6', 'maker approving its own item: no pending item of the maker', true, 'skipped');
  }
  const anyItem = one("SELECT approval_id FROM approval_queue WHERE status = 'PENDING_APPROVAL' FETCH FIRST 1 ROWS ONLY");
  if (anyItem) {
    const r = await employee.call('POST', `/v1/approvals/${anyItem}/decision`, { decision: 'APPROVE' });
    check('IN-6', 'eSeva employee deciding an approval refused', r.status === 403 || r.status === 404, `HTTP ${r.status}`);
  }

  // Session: stolen cookie from another browser, and reuse after sign-out.
  const thief = new fci.Session('thief');
  thief.cookies = new Map(maker.cookies);
  thief.headers = { 'User-Agent': 'curl/8.0 (attacker)' };
  const stolen = await thief.call('GET', '/auth/me');
  check('IN-7', 'a session cookie replayed from another browser is refused', stolen.status === 401, `HTTP ${stolen.status}`);
  const again = await maker.call('GET', '/auth/me');
  check('IN-7', 'and the hijacked session is ended for its owner too', again.status === 401, `HTTP ${again.status}`);

  const victim = await fci.signIn('maker.delhi@fci-demo.test', 'DH001', 'PAYROLL_MAKER');
  const before = victim.header();
  await victim.call('POST', '/auth/logout', {});
  const replay = new fci.Session('replay');
  replay.cookies = new Map(before.split('; ').map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
  const after = await replay.call('GET', '/auth/me');
  check('IN-8', 'the session cookie is dead after sign-out', after.status === 401, `HTTP ${after.status}`);

  // Session fixation: the session id changes at sign-in.
  const fix = new fci.Session('fixation');
  await fix.call('GET', '/auth/csrf');
  const pre = [...fix.cookies].find(([k]) => /FCISESSION/.test(k))?.[1];
  const signedIn = await fci.signIn('ddo.delhi@fci-demo.test', 'DH001', 'PAYROLL_CHECKER');
  const post = [...signedIn.cookies].find(([k]) => /FCISESSION/.test(k))?.[1];
  check('IN-9', 'a new session id is issued at sign-in', !pre || pre !== post, pre ? 'pre-login id present' : 'no pre-login session');
  await signedIn.call('POST', '/auth/logout', {});
  await employee.call('POST', '/auth/logout', {});
  if (superuser) await superuser.call('POST', '/auth/logout', {});
}

await anonymous();
await recoveryAndOtp();
await bruteForce();
await insiders();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length} checks, ${failed.length} failed`);
for (const f of failed) console.log(`  FAIL ${f.id} ${f.title} [${f.detail}]`);
process.exit(failed.length ? 1 : 0);
