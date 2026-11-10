import { expect, type Page, test } from '@playwright/test';
import { t, workAs } from '../support/app';

/**
 * M-15 Allowance & Reimbursement Ceiling Norms - the field rules (SRS-16-14; SRS M-15 field table;
 * CeilingNormService): a ceiling is a positive amount of at most 10 digits and 2 decimals, the
 * periodicity one of the DDL list, the claim type and eligibility category M-16 values, and no
 * version goes without its reason. Every attempt here is refused, so nothing is left waiting and
 * the spec re-runs on any stack.
 */
const RULE_ADMIN = 'su.hq@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const FAR = '2399-01-01';

/** A forged proposal as the screen would send it; returns the status and the error code. */
async function propose(page: Page, claimType: string, over: Record<string, unknown>) {
  return page.evaluate(
    async ([type, body]) => {
      const token =
        document.cookie
          .split('; ')
          .find((c) => c.startsWith('XSRF-TOKEN='))
          ?.split('=')[1] ?? '';
      const response = await fetch(`/nginx/fci-payroll/v1/masters/ceiling-norms/${type}/versions`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': token,
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify(body),
      });
      const problem = (await response.json().catch(() => ({}))) as { errorCode?: string };
      return { status: response.status, errorCode: problem.errorCode ?? null };
    },
    [
      claimType,
      {
        effectiveFrom: FAR,
        ceilingValue: 500,
        periodicity: 'MONTHLY',
        eligibilityCategory: 'DEMO_ALL',
        confirmed: true,
        reason: 'E2E: a refused ceiling',
        ...over,
      },
    ] as const,
  );
}

test.describe('M-15 ceiling norms: the field rules', () => {
  test(
    'a ceiling is a positive amount of at most ten digits and two decimals',
    { tag: ['@SRS-16-14', '@SC-04-0059', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-15');
      for (const ceilingValue of [-1, 0, 10.123, 12345678901]) {
        expect(await propose(page, 'LABOUR_TA', { ceilingValue }), String(ceilingValue)).toEqual({
          status: 422,
          errorCode: 'MST-422-CEILING',
        });
      }
    },
  );

  test(
    'the periodicity is one of the M-16 values the screen offers; any other is refused',
    { tag: ['@SRS-16-14', '@SC-04-0060', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-15');
      await page
        .getByRole('row', { name: /LABOUR_TA/ })
        .getByRole('button', { name: t('en', 'mst.newVersion'), exact: true })
        .click();
      const offered = await page
        .locator('#cn-period option')
        .evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value));
      // SRS l.1421 sources the list from M-16; the demo list is the four drawn, all within the DDL CHECK (Q-04-9).
      expect(offered).toEqual(['MONTHLY', 'ANNUAL', 'BLOCK_YEAR_4', 'ONE_TIME_PER_EVENT']);
      expect(await propose(page, 'LABOUR_TA', { periodicity: 'WEEKLY' })).toEqual({
        status: 422,
        errorCode: 'MST-422-CEILING',
      });
    },
  );

  test(
    'SRS-G-38 (US-GAP-211): a norm version without its Active M-25 circular is refused, even forged',
    { tag: ['@SRS-G-38', '@US-GAP-211', '@SRS-16-14', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-15');
      // Wave D review 6: no circular is a required field (422), not a circular "null".
      expect(await propose(page, 'LABOUR_TA', { circularOrderId: null })).toEqual({
        status: 422,
        errorCode: 'MST-422-CIRCULAR-REQUIRED',
      });
      // An id that is no Active circular is still refused as such.
      expect(await propose(page, 'LABOUR_TA', { circularOrderId: 987654321 })).toEqual({
        status: 409,
        errorCode: 'MST-409-CIRCULAR-STATE',
      });
    },
  );

  test(
    'the claim type and the eligibility category must be values of their M-16 lists',
    { tag: ['@SRS-16-14', '@SRS-16-15', '@SC-04-0057', '@SC-04-0061', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-15');
      expect(await propose(page, 'LABOUR_TA', { eligibilityCategory: 'NO_SUCH_CATEGORY' })).toEqual({
        status: 422,
        errorCode: 'MST-422-VALUE',
      });
      expect(await propose(page, 'NO_SUCH_CLAIM', {})).toEqual({ status: 422, errorCode: 'MST-422-VALUE' });
    },
  );

  test(
    'no version goes without its reason and its eligibility category: the form will not send',
    { tag: ['@SRS-16-14', '@SRS-G-37', '@SC-04-0061', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-15');
      await page
        .getByRole('row', { name: /LABOUR_TA/ })
        .getByRole('button', { name: t('en', 'mst.newVersion'), exact: true })
        .click();
      // The version form lives in the M-15 register under the drawn screen, whose own Save & Submit is a
      // separate button (OD-121): the guard is asserted on the form this test fills.
      const submit = page
        .getByTestId('m15-register')
        .getByRole('button', { name: t('en', 'common.submitForApproval') });
      await page.locator('#cn-from').fill(FAR);
      await page.locator('#cn-value').fill('500');
      await page.locator('#cn-elig').selectOption('DEMO_ALL');
      await page.locator('#cn-reason').fill('');
      await expect(submit).toBeDisabled();
      await page.locator('#cn-reason').fill('E2E: guard check');
      // SRS-G-38 (US-GAP-211): nor without its Active circular.
      await expect(submit).toBeDisabled();
      await page.locator('#cn-circular').selectOption({ index: 1 });
      await expect(submit).toBeEnabled();
      await page.locator('#cn-elig').selectOption('');
      await expect(submit).toBeDisabled();
      // Nothing was sent: cancel leaves no proposal behind.
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
    },
  );
});
