import { expect, type Page, test } from '@playwright/test';
import {
  apiAll,
  apiJson,
  auditTrail,
  CHECKER,
  checker,
  currentCycle,
  expectToast,
  MAKER,
  maker,
  row,
} from '../support/payroll-support';
import { apiProblem, shiftPeriod, sidebarCodes, t, workAs } from '../support/app';

/**
 * X-06 Payroll Query Management (SRS-G-11; DDL payroll_query, chk_query_checker) through the UI:
 * Open -> In Progress -> Resolved -> Closed against a Payroll ID of the run of record: the newest locked month of
 * DO Delhi HQ (DH001), the month before the current one (demo seed V082_21, OD-141; the eSeva member 104882 is paid
 * in it). A computed-amount query is confirmed by the Payroll Checker,
 * never by the Maker who answered; any other query the Maker closes. A member raises queries on the
 * own record only, through eSeva.
 */
const MEMBER_ESEVA = 'emp.rsharma@fci-demo.test';

async function payrollIds(page: Page): Promise<Map<string, number>> {
  const period = shiftPeriod((await currentCycle(page)).period, -1);
  const { status } = await apiJson<{ status: string | null }>(page, `/v1/cycles/${period}`);
  if (status !== 'LOCKED') return new Map();
  const rows = await apiAll<{ resultId: number; eis: string }>(page, `/v1/cycles/${period}/results`);
  return new Map(rows.map((r) => [r.eis, r.resultId]));
}

async function raise(page: Page, payrollId: number | string, text: string, computed: boolean) {
  await page.getByRole('button', { name: t('en', 'x06.raiseAct') }).click();
  await page.getByLabel(t('en', 'x06.payrollId')).fill(String(payrollId));
  if (computed) await page.getByLabel(t('en', 'x06.computed')).check();
  await page.getByLabel(t('en', 'x06.text'), { exact: true }).fill(text);
  await page
    .getByRole('button', { name: t('en', 'x06.raise') })
    .last()
    .click();
}

/** Opens the next step of a query (the row's Edit action). */
async function open(page: Page, reference: string) {
  await row(page, reference)
    .getByRole('button', { name: t('en', 'x06.editOf', [reference]) })
    .click();
}

/** Opens a query read-only (the row's View action). */
async function view(page: Page, reference: string) {
  await row(page, reference)
    .getByRole('button', { name: t('en', 'x06.viewOf', [reference]) })
    .click();
}

test.describe.serial('Payroll queries (X-06)', () => {
  const stamp = Date.now();
  let ids = new Map<string, number>();
  let computed = '';
  let plain = '';

  test.beforeEach(async ({ page }) => {
    await workAs(page, 'en', MAKER, maker, 'X-06');
    if (ids.size === 0) ids = await payrollIds(page);
    test.skip(
      ids.size === 0,
      'Needs the locked month before the current one at DH001 (demo seed V082_21, OD-141).',
    );
  });

  test(
    'SRS-G-11: the Maker raises a query on a computed amount and answers it',
    {
      tag: [
        '@SRS-G-11',
        '@SC-01-0011',
        '@SC-01-0112',
        '@SC-01-0115',
        '@SC-01-0116',
        '@SC-01-0117',
        '@SC-01-0118',
        '@positive',
      ],
    },
    async ({ page }) => {
      await raise(page, ids.get('106958') ?? 0, `E2E ${stamp}: why is HRA lower this month?`, true);
      computed = /QRY-\d{8}/.exec(await expectToast(page, /QRY-\d{8}/))?.[0] ?? '';
      await expect(row(page, computed)).toContainText(t('en', 'x06.status.OPEN'));
      await expect(row(page, computed)).toContainText(t('en', 'x06.computedShort'));
      await expect(row(page, computed)).toContainText(t('en', 'x06.byStaff', ['']).trim());
      // The Payroll ID is shown as PR-period-staff number.
      await expect(row(page, computed)).toContainText(/PR-\d{6}-106958/);
      await open(page, computed);
      await page.getByLabel(t('en', 'x06.response')).fill('The city class of the posting changed.');
      await page.getByRole('button', { name: t('en', 'x06.respond') }).click();
      await expectToast(page, t('en', 'x06.updated', [computed, t('en', 'x06.status.IN_PROGRESS')]));
    },
  );

  test(
    'chk_query_checker: the answering Maker cannot confirm it; the Payroll Checker does, then it closes',
    { tag: ['@SRS-G-11', '@SRS-G-1', '@SC-01-0112', '@SC-01-0120', '@SC-01-0121', '@negative'] },
    async ({ page }) => {
      // The Maker who answered has no next step: Edit is disabled with the reason; View is read-only.
      const edit = row(page, computed).getByRole('button', { name: t('en', 'x06.editOf', [computed]) });
      await expect(edit).toBeDisabled();
      await expect(edit).toHaveAttribute('data-why', t('en', 'x06.noStep'));
      await view(page, computed);
      await expect(page.getByRole('button', { name: t('en', 'x06.confirm') })).toHaveCount(0);
      await expect(page.getByText(t('en', 'x06.checkerConfirms'))).toBeVisible();
      await workAs(page, 'en', CHECKER, checker, 'X-06');
      await open(page, computed);
      await page.getByLabel(t('en', 'x06.resolution')).fill('Correct as computed; explained to the member.');
      await page.getByRole('button', { name: t('en', 'x06.confirm') }).click();
      await expectToast(page, t('en', 'x06.updated', [computed, t('en', 'x06.status.CLOSED')]));
      await expect(row(page, computed)).toContainText(t('en', 'x06.status.CLOSED'));
    },
  );

  test(
    'a query not about a computed amount is closed by the Maker who answered it',
    { tag: ['@SRS-G-11', '@SC-01-0119', '@SC-01-0121', '@positive'] },
    async ({ page }) => {
      await raise(page, ids.get('106958') ?? 0, `E2E ${stamp}: payslip not received by e-mail`, false);
      plain = /QRY-\d{8}/.exec(await expectToast(page, /QRY-\d{8}/))?.[0] ?? '';
      await open(page, plain);
      await page.getByLabel(t('en', 'x06.response')).fill('Re-sent to the registered address.');
      await page.getByRole('button', { name: t('en', 'x06.respond') }).click();
      await expectToast(page, t('en', 'x06.updated', [plain, t('en', 'x06.status.IN_PROGRESS')]));
      await open(page, plain);
      await page.getByLabel(t('en', 'x06.resolution')).fill('Received.');
      await page.getByRole('button', { name: t('en', 'x06.resolve') }).click();
      await expectToast(page, t('en', 'x06.updated', [plain, t('en', 'x06.status.CLOSED')]));
    },
  );

  test(
    'the status filter shows only the chosen status',
    { tag: ['@SRS-G-11', '@SRS-NF-3', '@SC-01-0121', '@positive'] },
    async ({ page }) => {
      await page.getByRole('button', { name: t('en', 'x06.filterAct') }).click();
      await page.getByLabel(t('en', 'gen.status')).selectOption('CLOSED');
      await expect(row(page, plain)).toBeVisible();
      await expect(page.locator('tbody tr .pill')).not.toContainText([t('en', 'x06.status.OPEN')]);
      await page.getByLabel(t('en', 'gen.status')).selectOption('OPEN');
      await expect(row(page, plain)).toHaveCount(0);
    },
  );

  test(
    'an unknown Payroll ID and Hindi text are refused',
    { tag: ['@SRS-G-11', '@SRS-EH-3', '@SC-01-0116', '@edge'] },
    async ({ page }) => {
      await raise(page, 987654321, 'Where is my arrear?', false);
      await expect(page.locator('.fl-error[role="alert"]')).toContainText(
        t('en', 'error.QRY-422-PAYROLL-ID'),
      );
      await page.getByLabel(t('en', 'x06.text'), { exact: true }).fill('वेतन कम क्यों है');
      await expect(page.getByRole('button', { name: t('en', 'x06.raise') }).last()).toBeDisabled();
    },
  );

  test(
    'SRS-G-11: a member raises queries on the own record only, through eSeva',
    { tag: ['@SRS-G-11', '@SRS-G-29', '@SC-01-0011', '@SC-01-0117', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MEMBER_ESEVA, { role: 'EMPLOYEE_ESEVA' }, 'X-06');
      // X-08 joined the member's menu with the drawn screen (V078_14, WF-206).
      expect((await sidebarCodes(page)).sort()).toEqual(['E-01', 'X-06', 'X-08']);
      const own = ids.get('104882');
      test.skip(own === undefined, 'The member was not paid in the run of record.');
      await raise(page, own ?? 0, `E2E ${stamp}: my DA arrear`, false);
      const mine = /QRY-\d{8}/.exec(await expectToast(page, /QRY-\d{8}/))?.[0] ?? '';
      await expect(row(page, mine)).toContainText(t('en', 'x06.byMember', ['']).trim());
      // Another member's Payroll ID is refused; the list holds the member's own queries only.
      await raise(page, ids.get('106958') ?? 0, 'Not my record', false);
      await expect(page.locator('.fl-error[role="alert"]')).toBeVisible();
      await expect(row(page, computed)).toHaveCount(0);
      // A member never answers or resolves.
      await expect(
        row(page, mine).getByRole('button', { name: t('en', 'x06.editOf', [mine]) }),
      ).toBeDisabled();
      await view(page, mine);
      await expect(page.getByRole('button', { name: t('en', 'x06.respond') })).toHaveCount(0);
    },
  );

  test(
    'SRS-G-11: a query never computes; a correction goes through its own maker-checker transaction',
    { tag: ['@SRS-G-11', '@SRS-G-1', '@SC-01-0113', '@positive'] },
    async ({ page }) => {
      await expect(page.getByText(t('en', 'x06.bound.noCompute'))).toBeVisible();
      await expect(page.getByText(t('en', 'x06.bound.correction'))).toBeVisible();
      // No amount can be changed from the query: its detail has no amount field.
      await view(page, computed);
      await expect(page.getByRole('spinbutton')).toHaveCount(0);
      await expect(page.getByLabel(/amount/i)).toHaveCount(0);
    },
  );

  test(
    'SRS-G-11: every status change and the resolution are in the audit trail',
    { tag: ['@SRS-G-11', '@SRS-G-2', '@SC-01-0114', '@SC-01-0002', '@positive'] },
    async ({ page, browser }) => {
      const queries = await apiAll<{ id: number; reference: string }>(page, '/v1/queries');
      const id = queries.find((q) => q.reference === computed)?.id;
      expect(id, computed).toBeDefined();
      const rows = await auditTrail(browser, 'payroll_query', String(id));
      // Raised, responded (by the Maker) and resolved (by the Checker): each a row with its actor.
      expect(rows.length).toBeGreaterThanOrEqual(3);
      expect(rows.every((r) => r.moduleCode === 'X-06')).toBe(true);
      expect(new Set(rows.map((r) => r.changedBy))).toEqual(new Set([MAKER, CHECKER]));
      expect(rows.some((r) => r.newValue === 'CLOSED' || r.newValue === 'RESOLVED')).toBe(true);
    },
  );
  test(
    'stored markup is shown as text, never run, and a formula is neutralised in the CSV export',
    { tag: ['@SRS-G-11', '@SRS-G-13', '@negative'] },
    async ({ page }) => {
      // OWASP A03: a query is free text that other users read on screen and in exports.
      const payload = `=HYPERLINK("http://example.invalid","x") <img src=x onerror="document.title='pwned'"> E2E ${stamp}`;
      const title = await page.title();
      await raise(page, ids.get('106958') ?? 0, payload, false);
      const reference = /QRY-\d{8}/.exec(await expectToast(page, /QRY-\d{8}/))?.[0] ?? '';
      await view(page, reference);
      await expect(page.getByText(payload, { exact: true }).first()).toBeVisible();
      // Rendered as text: no element was created from it and nothing ran.
      await expect(page.locator('img[src="x"]')).toHaveCount(0);
      expect(await page.title()).toBe(title);
      // In the CSV the text starts with a quote, so a spreadsheet shows it instead of computing it.
      await page.keyboard.press('Escape');
      // WF-077: no export is drawn on X-06; the list still downloads from the API.
      const csv = await (await page.request.get('/nginx/fci-payroll/v1/queries/export?format=CSV')).text();
      const line = csv.split('\n').find((l) => l.includes(`E2E ${stamp}`)) ?? '';
      expect(line).toContain(`'=HYPERLINK`);
      expect(line).not.toMatch(/(^|,)"?=HYPERLINK/);
    },
  );
  /** The first payroll record of the run of record (any member of the office). */
  const anyId = () => [...ids.values()][0] as number;

  test(
    'X-06 field table: query text is 1 to 4000 characters of plain text; blank or longer is refused',
    { tag: ['@SRS-G-11', '@SC-01-0118', '@edge'] },
    async ({ page }) => {
      const raise = (queryText: string) =>
        apiProblem(page, '/v1/queries', 'POST', {
          payrollId: anyId(),
          queryText,
          concernsComputedAmount: false,
        });
      expect((await raise('   ')).status).toBe(400);
      expect((await raise(`E2E ${stamp} `.padEnd(4001, 'x'))).status).toBe(400);
      // Exactly the limit is accepted.
      expect((await raise(`E2E ${stamp} `.padEnd(4000, 'x'))).status).toBe(201);
    },
  );

  test(
    'X-06 statuses: no resolution before a response, and a closed query takes no further step',
    { tag: ['@SRS-G-11', '@SC-01-0119', '@SC-01-0121', '@negative'] },
    async ({ page }) => {
      const { id } = await apiJson<{ id: number }>(page, '/v1/queries', 'POST', {
        payrollId: anyId(),
        queryText: `E2E order of steps ${stamp}`,
        concernsComputedAmount: false,
      });
      // Open: a resolution needs the Maker's response first (Open -> In Progress -> Resolved).
      expect(
        await apiProblem(page, `/v1/queries/${id}/resolve`, 'POST', { resolution: 'Too early' }),
      ).toEqual({
        status: 409,
        errorCode: 'QRY-409-STATUS',
      });
      expect(
        (
          await apiProblem(page, `/v1/queries/${id}/respond`, 'POST', {
            response: 'Checked: the slip is right',
            concernsComputedAmount: false,
          })
        ).status,
      ).toBe(200);
      expect(
        (
          await apiProblem(page, `/v1/queries/${id}/resolve`, 'POST', {
            resolution: 'Explained to the member',
          })
        ).status,
      ).toBe(200);
      // Closed: nothing moves it again.
      for (const [step, body] of [
        ['respond', { response: 'Once more', concernsComputedAmount: false }],
        ['resolve', { resolution: 'Once more' }],
      ] as const) {
        expect(await apiProblem(page, `/v1/queries/${id}/${step}`, 'POST', body), step).toEqual({
          status: 409,
          errorCode: 'QRY-409-STATUS',
        });
      }
    },
  );

  test(
    'X-06: a query raised on a computed amount keeps that flag; the response cannot drop it',
    { tag: ['@SRS-G-11', '@SC-01-0120', '@negative'] },
    async ({ page }) => {
      const { id } = await apiJson<{ id: number }>(page, '/v1/queries', 'POST', {
        payrollId: anyId(),
        queryText: `E2E computed flag ${stamp}`,
        concernsComputedAmount: true,
      });
      // Dropping it would let the Maker close a computed-amount query without the Checker.
      expect(
        await apiProblem(page, `/v1/queries/${id}/respond`, 'POST', {
          response: 'Not a computation after all',
          concernsComputedAmount: false,
        }),
      ).toEqual({ status: 422, errorCode: 'QRY-422-COMPUTED' });
    },
  );
});
