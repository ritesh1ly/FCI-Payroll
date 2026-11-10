import { expect, type Page, test } from '@playwright/test';
import { apiProblem, decide, t, workAs } from '../support/app';

/**
 * M-24 Accounting & Bank Mapping (SRS-G-7): Finance proposes a mapping at Headquarters, where the
 * route of the screen is maintained (V033_3: Finance at HQ makes, the Payroll Checker at HQ checks).
 * Finance at a division holds the same permission but sees the action disabled with the reason,
 * instead of filling a form the server would refuse.
 *
 * The field and date rules (GlMappingService): a natural account is 2 to 20 of A-Z, 0-9 and "-",
 * not starting with "-"; a new version starts after the version in force and on a date no other
 * version of the pay code holds; a pay code without a mapping in force is flagged "Not mapped" (its
 * posting is held on the exceptions worklist). Every proposal made here is rejected, on a date of
 * its own, so the spec re-runs on one stack.
 */
const FINANCE_HQ = 'finance.hq@fci-demo.test';
const financeHq = { role: 'FINANCE', office: 'HQ001' };
const CHECKER_HQ = 'checker.hq@fci-demo.test';
const checkerHq = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
/** Mapped by the demo seed from 2026-04-01 (V016_2__demo_master_values). */
const MAPPED = 101;
/** No mapping in the demo seed. */
const UNMAPPED = 140;

/** A date in the far future no earlier run used: one day per minute of the run's start. */
function uniqueDay(): string {
  const day = new Date(Date.UTC(2200, 0, 1) + (Math.floor(Date.now() / 60_000) % 50_000) * 86_400_000);
  return day.toISOString().slice(0, 10);
}

const propose = (page: Page, payCode: number | string, body: Record<string, unknown>) =>
  apiProblem(page, `/v1/masters/gl-mappings/${payCode}/versions`, 'POST', {
    effectiveFrom: '2399-01-01',
    naturalAccount: 'E2E-ACCT-1',
    reason: 'E2E: a refused mapping',
    ...body,
  });

// The per-pay-code register stays in the closed section behind the drawn masters (OD-121).
const openRegister = async (page: Page) => {
  const section = page.getByTestId('m24-register');
  if (!(await section.evaluate((d) => (d as HTMLDetailsElement).open)))
    await section.locator('summary').click();
};
const payCodeRow = (page: Page, payCode: number) =>
  page
    .getByTestId('m24-register')
    .getByRole('row')
    .filter({ has: page.locator('td b', { hasText: new RegExp(`^${payCode}$`) }) });

test.describe('M-24 Accounting & Bank Mapping', () => {
  test(
    'Finance outside Headquarters sees New version disabled with the reason',
    { tag: ['@SRS-G-7', '@SRS-22-7', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'finance.mumbai@fci-demo.test', { role: 'FINANCE', office: 'MR001' }, 'M-24');
      await openRegister(page);
      const proposal = page.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).first();
      await expect(proposal).toBeDisabled();
      await expect(proposal).toHaveAttribute('title', t('en', 'error.AUTHZ-403-HQ-ONLY'));
    },
  );

  test(
    'the seven mapping masters are drawn; View opens a master read only; Bank has no Edit',
    { tag: ['@SRS-G-7', '@SRS-G-54', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      const table = page.getByRole('table', { name: t('en', 'm24.title') });
      await expect(
        table.getByRole('button', { name: t('en', 'wf.view').slice(2), exact: false }),
      ).toHaveCount(7);
      await expect(
        page.getByRole('button', { name: `${t('en', 'common.edit')} ${t('en', 'm24.master.BANK')}` }),
      ).toBeDisabled();
      await page
        .getByRole('button', { name: `${t('en', 'common.view')} ${t('en', 'm24.master.BANK')}` })
        .click();
      await expect(page.getByRole('table', { name: t('en', 'm24.master.BANK') })).toBeVisible();
    },
  );

  test(
    'Finance at Headquarters may propose a new mapping version',
    // SC-01-0069 (Module 01 numbering of a use case kb_review_01 maps to module 02 / 04: a comment, not an e2e_trace tag).
    { tag: ['@SRS-G-7', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      await openRegister(page);
      const proposal = page.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).first();
      await expect(proposal).toBeEnabled();
    },
  );

  test(
    'a pay code without a mapping in force is flagged; a mapped one shows its account and version',
    { tag: ['@SRS-G-7', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      await openRegister(page);
      await expect(payCodeRow(page, UNMAPPED)).toContainText(t('en', 'mst.notMapped'));
      const mapped = payCodeRow(page, MAPPED);
      await expect(mapped).not.toContainText(t('en', 'mst.notMapped'));
      await expect(mapped).toContainText(/v\d+/);
    },
  );

  test(
    'a natural account is 2 to 20 of A-Z, 0-9 and "-", never starting with "-"; the form will not send another',
    { tag: ['@SRS-G-7', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      await openRegister(page);
      for (const naturalAccount of ['A', '-ABC', 'abc-1', 'A'.repeat(21), 'AB CD', '']) {
        expect(await propose(page, UNMAPPED, { naturalAccount }), JSON.stringify(naturalAccount)).toEqual({
          status: 400,
          errorCode: 'COM-400-VALIDATION',
        });
      }
      // No version goes without its reason.
      expect(await propose(page, UNMAPPED, { reason: ' ' })).toEqual({
        status: 400,
        errorCode: 'COM-400-VALIDATION',
      });

      await payCodeRow(page, UNMAPPED)
        .getByRole('button', { name: t('en', 'mst.newVersion'), exact: true })
        .click();
      const submit = page.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await page.locator('#gl-reason').fill('E2E: guard check');
      await page.locator('#gl-acct').fill('-ABC');
      await expect(submit).toBeDisabled();
      // Typed in lower case, the account is taken in upper case.
      await page.locator('#gl-acct').fill('e2e-acct');
      await expect(page.locator('#gl-acct')).toHaveValue('E2E-ACCT');
      await expect(submit).toBeEnabled();
      await page.locator('#gl-reason').fill('');
      await expect(submit).toBeDisabled();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
    },
  );

  test(
    'a new version starts after the one in force; an unknown pay code is refused',
    { tag: ['@SRS-G-7', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      await openRegister(page);
      for (const effectiveFrom of ['2026-04-01', '2025-01-01']) {
        expect(await propose(page, MAPPED, { effectiveFrom }), effectiveFrom).toEqual({
          status: 422,
          errorCode: 'MST-422-DATE',
        });
      }
      expect(await propose(page, 999_998, {})).toEqual({ status: 404, errorCode: 'MST-404-CATALOGUE' });
    },
  );

  test(
    'a proposal waits for the Checker; rejected, nothing is mapped and its date stays taken',
    { tag: ['@SRS-G-7', '@SRS-G-37', '@edge'] },
    async ({ page }) => {
      const day = uniqueDay();
      const reason = `E2E M-24 proposal ${Date.now()}`;
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      await openRegister(page);
      const row = payCodeRow(page, UNMAPPED);
      await row.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).click();
      await page.locator('#gl-from').fill(day);
      await page.locator('#gl-acct').fill('E2E-REJECT');
      await page.locator('#gl-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();

      // Pending: the row shows the proposal, still not mapped, and offers no second version.
      await expect(row).toContainText('E2E-REJECT');
      await expect(row).toContainText(t('en', 'mst.notMapped'));
      await expect(row.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true })).toHaveCount(0);

      await workAs(page, 'en', CHECKER_HQ, checkerHq, 'W-01');
      await decide(page, new RegExp(reason), 'REJECT', 'E2E: not this account');

      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      await openRegister(page);
      await expect(row).toContainText(t('en', 'mst.notMapped'));
      await expect(row).not.toContainText('E2E-REJECT');
      await row.getByRole('button', { name: t('en', 'mst.history'), exact: true }).click();
      const history = page.locator('.card').filter({
        has: page.getByRole('heading', { name: t('en', 'mst.historyOf', [UNMAPPED]) }),
      });
      await expect(
        history
          .getByRole('row')
          .filter({ hasText: 'E2E-REJECT' })
          .filter({ hasText: t('en', 'status.REJECTED') }),
      ).not.toHaveCount(0);
      // The rejected version keeps its date: no other version of the pay code may start on it.
      expect(await propose(page, UNMAPPED, { effectiveFrom: day })).toEqual({
        status: 409,
        errorCode: 'MST-409-DATE',
      });
    },
  );
});
