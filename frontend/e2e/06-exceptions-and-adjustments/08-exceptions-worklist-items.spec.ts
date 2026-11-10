import { expect, type Page, test } from '@playwright/test';
import { apiStatus, onSomePage, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { apiAll, CHECKER, checker, expectToast, MAKER, maker } from '../support/payroll-support';

/**
 * X-05 Exceptions & Holds Worklist with raised items (SRS-G-9, SRS-EH-4, SRS-15-14; FRS C-08 fields).
 * The demo stack has no producer for payroll exceptions yet (decision D-7), so a database fixture
 * writes exactly what a producer writes (ExceptionWorklistService.raise / ExceptionEntry.raise): an
 * HRMS-sourced item without an I-21 task is ROUTED_UPSTREAM, owned by HRMS, not resolvable in
 * Payroll. Each item carries this run's code; whatever is still unsettled at the end is closed as
 * the system closes a cleared condition (RESOLVED, never deleted), so no blocking item outlives the
 * spec and holds gate G2 for the cycle specs.
 */
const RUN = Date.now().toString(36).toUpperCase();
const WARN = `E2E_X05_W_${RUN}`;
const HRMS = `E2E_X05_H_${RUN}`;
const BLOCK = `E2E_X05_B_${RUN}`;
const MANY = `E2E_X05_M_${RUN}`;

const member = (emp: string) =>
  `(SELECT employee_id FROM employee WHERE emp_num = '${emp}' AND location_id = (SELECT location_id FROM org_location WHERE location_code = 'DH001'))`;

function raise(
  code: string,
  emp: string,
  severity: 'WARNING' | 'BLOCKING',
  field: string,
  source: 'ENT' | 'HRMS',
  detail: string,
) {
  const upstream = source === 'HRMS';
  sql(
    `INSERT INTO payroll_exception (employee_id, module_code, exception_code, severity, blocks_gate, entity_name, field_name, field_source, resolvable_in_payroll, correction_screen, owner_role_code, detail, exception_status, raised_at) VALUES (${member(emp)}, 'C-02', '${code}', '${severity}', 'G2', 'employee', '${field}', '${source}', '${upstream ? 'N' : 'Y'}', ${upstream ? 'NULL' : "'M-01'"}, ${upstream ? "'HRMS'" : 'NULL'}, '${detail}', '${upstream ? 'ROUTED_UPSTREAM' : 'OPEN'}', CURRENT_TIMESTAMP)`,
  );
}

/** The item's id as X-05 lists it to the signed-in user (the member's EIS is the emp_num). */
async function idOf(page: Page, code: string, eis: string): Promise<number> {
  const items = await apiAll<{ id: number; exceptionCode: string; eis: string | null }>(
    page,
    '/v1/exceptions',
  );
  const found = items.find((e) => e.exceptionCode === code && e.eis === eis);
  expect(found, `${code} for ${eis}`).toBeTruthy();
  return (found as { id: number }).id;
}

async function groupRow(page: Page, code: string) {
  return onSomePage(page, page.locator(`tr[data-code="${code}"]`));
}

test.describe('Exceptions and holds worklist with raised items (X-05)', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test.beforeAll(() => {
    raise(WARN, '105220', 'WARNING', 'bank_account', 'ENT', 'Bank account missing (E2E)');
    raise(WARN, '105461', 'WARNING', 'bank_account', 'ENT', 'Bank account missing (E2E)');
    raise(HRMS, '106004', 'BLOCKING', 'pan_number', 'HRMS', 'PAN differs from HRMS (E2E)');
    raise(BLOCK, '106118', 'BLOCKING', 'basic_pay', 'ENT', 'No basic pay (E2E)');
    // SRS l.634: one condition about 25 members of the office, more than one page of rows.
    sql(
      `INSERT INTO payroll_exception (employee_id, module_code, exception_code, severity, blocks_gate, entity_name, field_name, field_source, resolvable_in_payroll, correction_screen, detail, exception_status, raised_at) SELECT e.employee_id, 'C-02', '${MANY}', 'WARNING', 'G2', 'employee', 'bank_account', 'ENT', 'Y', 'M-01', 'Many members (E2E)', 'OPEN', CURRENT_TIMESTAMP FROM employee e WHERE e.location_id = (SELECT location_id FROM org_location WHERE location_code = 'DH001') ORDER BY e.employee_id FETCH FIRST 25 ROWS ONLY`,
    );
  });

  test.afterAll(() => {
    sql(
      `UPDATE payroll_exception SET exception_status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE exception_code IN ('${WARN}', '${HRMS}', '${BLOCK}', '${MANY}') AND exception_status IN ('OPEN', 'IN_PROGRESS', 'ROUTED_UPSTREAM')`,
    );
  });

  test(
    'SRS-G-9: a condition lists its count, field, source, fix location and whether it blocks G2; the Checker assigns and accepts it with a reason',
    {
      tag: [
        '@SRS-G-9',
        '@SRS-15-14',
        '@SC-01-0063',
        '@SC-05-0067',
        '@SC-05-0069',
        '@SC-05-0071',
        '@SC-05-0072',
        '@SC-05-0073',
        '@SC-05-0074',
        '@positive',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-05');
      const group = await groupRow(page, WARN);
      await expect(group).toContainText('Bank account missing (E2E)');
      await expect(group.locator('td.num')).toHaveText('2');
      await expect(group).toContainText(t('en', 'x05.no'));
      await expect(group).toContainText('M-01');
      // The Maker may look but neither assign nor accept: Edit is off, and the API agrees.
      await expect(group.getByRole('button', { name: t('en', 'x05.editOf', [WARN]) })).toBeDisabled();
      await group.getByRole('button', { name: t('en', 'x05.viewOf', [WARN]) }).click();
      const first = page.locator(`tr[data-item="${await idOf(page, WARN, '105220')}"]`);
      await expect(first).toContainText('bank_account');
      await expect(first).toContainText(t('en', 'x05.source', ['ENT']));
      await expect(page.getByRole('button', { name: t('en', 'x05.accept') })).toHaveCount(0);
      expect(
        await apiStatus(page, `/v1/exceptions/${await idOf(page, WARN, '105220')}/assign`, 'POST', {
          owner: 'PAYROLL_MAKER',
        }),
      ).toBe(403);

      await workAs(page, 'en', CHECKER, checker, 'X-05');
      await (await groupRow(page, WARN)).getByRole('button', { name: t('en', 'x05.editOf', [WARN]) }).click();
      const item = page.locator(`tr[data-item="${await idOf(page, WARN, '105220')}"]`);
      await item
        .getByLabel(t('en', 'x05.assignTo', [WARN]))
        .selectOption({ label: t('en', 'role.PAYROLL_MAKER') });
      await expectToast(page, t('en', 'x05.assigned', [WARN]));
      await expect(item).toContainText(t('en', 'x05.status.IN_PROGRESS'));
      await item.getByRole('button', { name: t('en', 'x05.accept') }).click();
      // No acceptance without its reason.
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByRole('button', { name: t('en', 'common.submit') })).toBeDisabled();
      await dialog.getByRole('textbox').fill('Bank details follow next month; salary held');
      await dialog.getByRole('button', { name: t('en', 'common.submit') }).click();
      await expectToast(page, t('en', 'x05.accepted', [WARN]));
      await expect(item).toContainText(t('en', 'x05.status.ACCEPTED'));
      await expect(item).toContainText('Bank details follow next month; salary held');
    },
  );

  test(
    'SRS-EH-4: an HRMS value is shown as corrected upstream; Payroll offers no correction and refuses acceptance',
    { tag: ['@SRS-EH-4', '@SRS-G-45', '@SC-01-0064', '@SC-05-0064', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', CHECKER, checker, 'X-05');
      const group = await groupRow(page, HRMS);
      await expect(group).toContainText(t('en', 'x05.upstream'));
      await expect(group).toContainText(t('en', 'role.HRMS'));
      const edit = group.getByRole('button', { name: t('en', 'x05.editOf', [HRMS]) });
      await expect(edit).toBeDisabled();
      await expect(edit).toHaveAttribute('data-why', t('en', 'x05.why.hrmsOwned'));
      await group.getByRole('button', { name: t('en', 'x05.viewOf', [HRMS]) }).click();
      const item = page.locator(`tr[data-item="${await idOf(page, HRMS, '106004')}"]`);
      await expect(item).toContainText(t('en', 'x05.status.ROUTED_UPSTREAM'));
      await expect(item).toContainText(t('en', 'x05.source', ['HRMS']));
      // A forged call is refused as well: nothing upstream is accepted or reassigned in Payroll.
      expect(
        await apiStatus(page, `/v1/exceptions/${await idOf(page, HRMS, '106004')}/accept`, 'POST', {
          reason: 'HRMS will correct the PAN',
        }),
      ).toBe(422);
    },
  );

  test(
    'SRS-G-9: a blocking exception is cleared at its source, never accepted; the reason the button is off is shown',
    { tag: ['@SC-01-0009', '@SRS-G-9', '@SC-01-0065', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', CHECKER, checker, 'X-05');
      const group = await groupRow(page, BLOCK);
      await expect(group).toContainText(t('en', 'x05.yes'));
      await group.getByRole('button', { name: t('en', 'x05.editOf', [BLOCK]) }).click();
      const accept = page
        .locator(`tr[data-item="${await idOf(page, BLOCK, '106118')}"]`)
        .getByRole('button', { name: t('en', 'x05.accept') });
      await expect(accept).toBeDisabled();
      await expect(accept).toHaveAttribute('title', t('en', 'x05.acceptBlocked'));
      expect(
        await apiStatus(page, `/v1/exceptions/${await idOf(page, BLOCK, '106118')}/accept`, 'POST', {
          reason: 'Accept it anyway, please',
        }),
      ).toBe(422);
    },
  );

  test(
    'SRS-G-9: a condition larger than one page is one row counting every member; its items page on their own; the class and period filters narrow it',
    { tag: ['@SRS-G-9', '@SRS-15-14', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-05');
      const group = await groupRow(page, MANY);
      // Counted by the server over the whole scope, not the 20 rows of a page.
      await expect(group.locator('td.num')).toHaveText('25');
      await expect(page.locator(`tr[data-code="${MANY}"]`)).toHaveCount(1);
      await group.getByRole('button', { name: t('en', 'x05.viewOf', [MANY]) }).click();
      const items = page.locator('.card').filter({
        has: page.getByRole('heading', { name: t('en', 'x05.itemsOf', [MANY]) }),
      });
      await expect(items.locator('tr[data-item]')).toHaveCount(20);
      // Another class hides it; a period hides it (its items belong to no cycle) and closes the
      // opened condition.
      await page.getByRole('button', { name: t('en', 'x05.filter') }).click();
      await page.getByLabel(t('en', 'x05.class'), { exact: true }).selectOption('C-02');
      await expect(await groupRow(page, MANY)).toBeVisible();
      await page.getByLabel(t('en', 'x05.period'), { exact: true }).fill('2024-03');
      await expect(page.getByRole('heading', { name: t('en', 'x05.itemsOf', [MANY]) })).toHaveCount(0);
      await expect(page.locator(`tr[data-code="${MANY}"]`)).toHaveCount(0);
      // Kolkata's Maker never sees Delhi's condition.
      await workAs(
        page,
        'en',
        'maker.kolkata@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'KL001' },
        'X-05',
      );
      const kolkata = await apiAll<{ exceptionCode: string }>(page, '/v1/exceptions/groups');
      expect(kolkata.some((g) => g.exceptionCode === MANY)).toBe(false);
    },
  );
});
