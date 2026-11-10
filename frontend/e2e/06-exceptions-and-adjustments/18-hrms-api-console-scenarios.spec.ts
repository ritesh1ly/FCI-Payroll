import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, type Page, test } from '@playwright/test';
import { t, workAs } from '../support/app';

/**
 * Module 23 X-03 API console, every HRMS API and every scenario through the UI (owner 2026-11-06: "test all
 * the api ... from playwright UI, test all the possible edge case"; SRS-INT-1, SRS-INT-6, SRS-INT-12,
 * SRS-INT-31, SRS-INT-35). The Administrator picks an API, types its query and body, gives a reason and runs
 * it; Payroll's gateway calls the FCI HRMS mock server (stack with `-ExtraProfiles hrms-mock`) and the screen
 * shows the classified reply.
 *
 * Data-driven: the cases are those of the mock's own catalogue (E2E_MOCK_CASES = its api-test-cases.json),
 * run in catalogue order after a reset of the mock (E2E_MOCK_ADMIN_KEY). A case runs when Payroll can express
 * it: one of the configured APIs, JSON body, no custom header. The others are listed with the reason in the
 * "coverage" test (custom headers and malformed bodies are the mock's own authentication / transport cases,
 * covered by HrmsClientTokenTest and HrmsMockScenarioLiveTest). A case Payroll's contract check refuses before
 * sending (a wrong kind of value the mock would answer 400) counts as a pass of the contract.
 *
 * Runs only with E2E_HRMS_MOCK=1 and E2E_MOCK_CASES set.
 */
const ADMIN = 'sysadmin@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const REASON = 'E2E scenario run';
const MOCK_URL = process.env.E2E_HRMS_MOCK_URL ?? 'http://localhost:8080';
const CASES_FILE = process.env.E2E_MOCK_CASES;
const enabled = process.env.E2E_HRMS_MOCK === '1' && !!CASES_FILE;

interface MockCase {
  group: string;
  name: string;
  method: string;
  path: string;
  body?: unknown;
  rawBody?: string;
  headers?: Record<string, string | null>;
  liveOnly?: boolean;
  expect: { status: number; error_code?: string; bodyEquals?: string; json?: Record<string, unknown> };
}

const SKIPPED_GROUPS = [
  /^Authentication$/,
  /^Integration edge cases$/,
  /^Postman samples - auth$/,
  /X-Mock-Scenario/,
];

/** The APIs Payroll calls in the hrms-mock profile (direction OUTBOUND), by setting code. */
function configuredCodes(): Set<string> {
  const yml = readFileSync(resolve(process.cwd(), '../backend/src/main/resources/application.yaml'), 'utf8');
  return new Set(
    [...yml.matchAll(/^\s{8}([a-z0-9_]+): \{path: [^,]+, direction: OUTBOUND\}/gm)].map(
      (m) => m[1] as string,
    ),
  );
}

/** "METHOD /path" -> setting code, from the contract document, for the configured APIs only. */
function codesByPath(): Map<string, string> {
  const doc = JSON.parse(
    readFileSync(
      resolve(process.cwd(), '../backend/src/main/resources/integration/hrms-openapi.json'),
      'utf8',
    ),
  ) as { paths: Record<string, Record<string, { operationId: string }>> };
  const configured = configuredCodes();
  const map = new Map<string, string>();
  for (const [path, ops] of Object.entries(doc.paths)) {
    for (const [method, op] of Object.entries(ops)) {
      if (configured.has(op.operationId)) map.set(`${method.toUpperCase()} ${path}`, op.operationId);
    }
  }
  return map;
}

function loadCases() {
  if (!enabled) return { run: [] as Prepared[], skipped: [] as { name: string; why: string }[] };
  const catalogue = JSON.parse(readFileSync(CASES_FILE as string, 'utf8')) as { cases: MockCase[] };
  const byPath = codesByPath();
  const run: Prepared[] = [];
  const skipped: { name: string; why: string }[] = [];
  for (const c of catalogue.cases) {
    const remapped = remapFault(c, byPath);
    if (remapped) {
      run.push(remapped);
      continue;
    }
    const why = skipReason(c, byPath);
    if (why) {
      skipped.push({ name: `${c.group}: ${c.name}`, why });
      continue;
    }
    const [pathPart = '', queryPart = ''] = c.path.split('?');
    const code = byPath.get(`${c.method} /${pathPart.replace(/^\/+/, '')}`) as string;
    const query: Record<string, string> = {};
    for (const [k, v] of new URLSearchParams(queryPart)) query[k] = v;
    run.push({ c, code, query });
  }
  return { run, skipped };
}

/**
 * The mock's fault triggers (emp_num 9995xx / 9994xx / 9992xx / 9990xx) work on any API. The cases written for an
 * API Payroll does not call (get-payroll-statust is held) are run on a configured status API instead, which tests
 * the same transport behaviour: 503 with Retry-After, 429, a 30 s wait, an empty or truncated body.
 */
function remapFault(c: MockCase, byPath: Map<string, string>): Prepared | null {
  const [pathPart = '', queryPart = ''] = c.path.split('?');
  if (byPath.has(`${c.method} /${pathPart.replace(/^\/+/, '')}`)) return null;
  if (c.headers && Object.keys(c.headers).length > 0) return null;
  const trigger = /emp_num=(999\d{3})/.exec(queryPart)?.[1];
  if (!trigger) return null;
  return {
    c: { ...c, name: `${c.name} (run on get_medical_claim_status)` },
    code: 'get_medical_claim_status',
    query: { emp_num: trigger, payment_id: '1' },
  };
}

interface Prepared {
  c: MockCase;
  code: string;
  query: Record<string, string>;
}

function skipReason(c: MockCase, byPath: Map<string, string>): string | null {
  if (c.liveOnly) return 'live-only (a URL only Tomcat rejects)';
  if (/in process/i.test(c.name)) return "bound to the mock's pinned business date 2026-10-28";
  if (SKIPPED_GROUPS.some((g) => g.test(c.group)))
    return 'authentication / transport case of the mock (token layer tests)';
  if (c.headers && Object.keys(c.headers).length > 0) return 'needs a custom request header';
  if (c.rawBody !== undefined) return 'malformed or raw body (Payroll sends typed JSON only)';
  const [pathPart = '', queryPart = ''] = c.path.split('?');
  if (!byPath.has(`${c.method} /${pathPart.replace(/^\/+/, '')}`))
    return 'API not configured in Payroll (not required or held)';
  if (queryPart.includes('%')) return 'undecodable query string';
  return null;
}

const { run: CASES, skipped: SKIPPED } = loadCases();

/** The outcomes Payroll can legitimately show for a case. */
function expected(c: MockCase): {
  outcomes: string[];
  status?: number;
  errorCode?: string;
  failed?: boolean;
} {
  const s = c.expect.status;
  const text = JSON.stringify(c.body ?? {}) + c.path;
  if (text.includes('999204') || text.includes('999299')) return { outcomes: ['UNREADABLE'] };
  if (text.includes('999200')) return { outcomes: ['FAILED_IN_BODY'] };
  if (s >= 500) return { outcomes: [], failed: true };
  if (s >= 400) {
    // The contract check may refuse a wrong kind of value before the mock sees it.
    return {
      outcomes: s === 400 ? ['REJECTED', 'INVALID_REQUEST'] : ['REJECTED'],
      status: s,
      errorCode: c.expect.error_code,
    };
  }
  return { outcomes: ['ACCEPTED'], status: s };
}

async function run(page: Page, p: Prepared) {
  await page.getByLabel(t('en', 'x03.console.api'), { exact: true }).selectOption(p.code);
  await page.getByLabel(t('en', 'x03.console.query'), { exact: true }).fill(JSON.stringify(p.query));
  await page
    .getByLabel(t('en', 'x03.console.body'), { exact: true })
    .fill(p.c.body === undefined ? '' : JSON.stringify(p.c.body));
  const [response] = await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/hrms-apis\/[a-z0-9_]+\/call$/.test(r.url()),
      { timeout: 60_000 },
    ),
    page.getByRole('button', { name: t('en', 'x03.console.run'), exact: true }).click(),
  ]);
  return response;
}

test.describe.serial('Module 23 X-03 API console: every scenario of the HRMS mock', () => {
  test.skip(
    !enabled,
    'needs E2E_HRMS_MOCK=1 and E2E_MOCK_CASES (the mock catalogue), a stack with -ExtraProfiles hrms-mock',
  );
  test.setTimeout(120_000);

  let page: Page;

  test.beforeAll(async ({ browser }) => {
    // The mock keeps state (a request can be read back and cancelled once): start from its seed data.
    const adminKey = process.env.E2E_MOCK_ADMIN_KEY ?? 'fci-mock-admin';
    const reset = await fetch(`${MOCK_URL}/mock-admin/reset`, {
      method: 'POST',
      headers: { 'X-Admin-Key': adminKey },
    });
    expect(reset.ok, 'reset of the mock').toBe(true);
    page = await browser.newPage({ ignoreHTTPSErrors: true });
    await workAs(page, 'en', ADMIN, admin, 'X-03');
    // The console opens from the drawn Test connection (WF-079).
    await page.getByRole('button', { name: t('en', 'x03.testAct') }).click();
    await page.getByLabel(t('en', 'x03.console.reason'), { exact: true }).fill(REASON);
  });

  test.afterAll(async () => {
    await page?.close();
  });

  // One test, one step per case: a failed test makes Playwright restart its worker and rerun the setup (the mock
  // reset), which would break the order the cases depend on. Soft assertions report every mismatch at the end.
  test(
    'SRS-INT-6: every scenario of the mock catalogue runs through the console and is classified as expected',
    { tag: ['@SRS-INT-6', '@SRS-INT-12', '@SRS-INT-31', '@SRS-INT-35', '@positive'] },
    async () => {
      test.setTimeout(30 * 60_000);
      const mismatches: string[] = [];
      let compared = 0;
      for (const p of CASES) {
        const e = expected(p.c);
        const label = `${p.c.group}: ${p.c.name} [${p.code}]`;
        await test.step(label, async () => {
          const response = await run(page, p);
          const note = (why: string) => mismatches.push(`${label} -> ${why}`);
          compared++;
          if (e.failed) {
            // HRMS failed (5xx / network): the call fails after its retries; a clean problem, never a reply.
            const problem = (await response.json()) as {
              errorCode?: string;
              correlationId?: string;
              outcome?: string;
            };
            // A call that outlasts the gateway's own limit (nginx) is cut there as a clean 504 problem.
            const cut = response.status() === 504 && !!problem.correlationId;
            if (
              !cut &&
              (response.status() !== 502 ||
                problem.errorCode !== 'INT-502-CALL-FAILED' ||
                !problem.correlationId)
            ) {
              note(
                `expected a failed call (502 INT-502-CALL-FAILED), got HTTP ${response.status()} ${problem.errorCode ?? problem.outcome}`,
              );
            }
            return;
          }
          if (response.status() !== 200) {
            note(`the console answered HTTP ${response.status()}`);
            return;
          }
          const reply = (await response.json()) as {
            outcome: string;
            httpStatus: number;
            errorCode: string | null;
            problems: string[];
          };
          const got = `${reply.outcome} HTTP ${reply.httpStatus} ${reply.errorCode ?? ''}`.trim();
          if (!e.outcomes.includes(reply.outcome)) {
            note(
              `expected ${e.outcomes.join(' or ')}${e.status ? ` HTTP ${e.status} ${e.errorCode ?? ''}` : ''}, got ${got}`,
            );
            return;
          }
          if (reply.outcome === 'INVALID_REQUEST') {
            if (reply.problems.length === 0 || reply.httpStatus !== 0)
              note('the contract check names nothing');
          } else {
            if (e.status !== undefined && reply.httpStatus !== e.status)
              note(`expected HTTP ${e.status}, got ${got}`);
            if (e.errorCode && reply.errorCode !== e.errorCode) note(`expected ${e.errorCode}, got ${got}`);
          }
          // The screen shows the same answer.
          const panel = page.getByTestId('x03-hrms-console-result');
          try {
            await expect(panel).toContainText(t('en', `x03.console.outcome.${reply.outcome}`), {
              timeout: 5000,
            });
            if (reply.errorCode) await expect(panel).toContainText(reply.errorCode, { timeout: 5000 });
          } catch {
            note(`the screen does not show the answer (${got})`);
          }
        });
      }
      console.log(`API console scenarios compared: ${compared}, mismatches: ${mismatches.length}`);
      for (const m of mismatches) console.log(`  MISMATCH ${m}`);
      expect(mismatches, 'every case is classified as the mock expects').toEqual([]);
    },
  );

  test(
    'SRS-INT-1: coverage: every case of the catalogue ran or is listed with the reason it cannot',
    { tag: ['@SRS-INT-1', '@positive'] },
    async () => {
      const byReason = new Map<string, number>();
      for (const s of SKIPPED) byReason.set(s.why, (byReason.get(s.why) ?? 0) + 1);
      test.info().annotations.push({
        type: 'coverage',
        description: `${CASES.length} cases run; skipped: ${[...byReason].map(([w, n]) => `${n} ${w}`).join('; ')}`,
      });
      console.log(`API console scenarios: ${CASES.length} run, ${SKIPPED.length} skipped`);
      for (const [why, n] of byReason) console.log(`  skipped ${n}: ${why}`);
      expect(CASES.length).toBeGreaterThan(100);
    },
  );
});
