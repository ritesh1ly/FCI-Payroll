import { expect, test } from '@playwright/test';
import { apiProblem, apiStatus, decide, onSomePage, t, workAs } from '../support/app';

/**
 * I-25 Wage Revision steps 1 to 4 (plan 13 phase 13e) on the demo order DEMO/WR/I25-01 (seed V077_18, Active, category
 * Wage Revision: the Revision Order takes only that category, SRS l.3164; the DA order DEMO/DA-REV/LEA-01 is refused).
 * The Rule Administrator at Headquarters defines a staff revision, simulates it (no pay changed) and submits it; the
 * Payroll Checker at Headquarters approves it in W-01 with the OTP WAGE_REVISION_HQ; Headquarters then fixes pay in
 * bulk (steps 5 - 6, module 17) - effective two months ahead, so no Basic of the shared demo data changes now. A unit's
 * Payroll Maker reads the revision but defines nothing, on the screen or by the API.
 */
const remark = `E2E wage revision ${Date.now()}`;

/** The first day of the month `back` months before this one (negative: ahead), as YYYY-MM-DD. */
function monthStart(back: number): string {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - back);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}

test.describe.serial('Wage revision (I-25)', () => {
  test(
    'SRS-8-25: Headquarters defines, simulates and submits; the HQ Checker approves with the OTP',
    { tag: ['@SRS-8-25', '@SRS-13-25', '@positive'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'I-25',
      );
      await page.getByRole('button', { name: t('en', 'i25.act.define') }).click();
      const define = page.getByRole('button', { name: t('en', 'action.i25.define'), exact: true });
      await expect(define).toBeDisabled();
      const name = `Pay revision (E2E ${Date.now()})`;
      await page.getByLabel(t('en', 'i25.name')).fill(name);
      await page
        .getByLabel(t('en', 'i25.order'))
        .first()
        .selectOption({ label: 'DEMO/WR/I25-01 - Wage revision (demo order for I-25)' });
      await page.getByLabel(t('en', 'i25.type.STAFF_IDA')).check();
      await page.getByLabel(t('en', 'i25.effective')).first().fill(monthStart(-2));
      await page.getByLabel(t('en', 'i25.fitment')).first().selectOption('PERCENTAGE');
      await page.getByLabel(t('en', 'i25.value')).fill('3');
      await page.getByLabel(t('en', 'i25.rounding')).first().selectOption('NEXT_10');
      await page.getByLabel(t('en', 'common.reason')).first().fill('Pay revision order (E2E)');
      await define.click();
      await expect(page.getByRole('status').filter({ hasText: t('en', 'i25.defined') })).toBeVisible();

      const steps = page.getByRole('list', { name: t('en', 'i25.steps') });
      await expect(steps).toContainText(`${t('en', 'i25.step.DEFINE')}: ${t('en', 'i25.stepStatus.DONE')}`);
      await page.getByRole('button', { name: t('en', 'action.i25.simulate') }).click();
      await expect(page.getByRole('status').filter({ hasText: t('en', 'i25.simulated') })).toBeVisible();
      await expect(
        page
          .getByRole('table', { name: t('en', 'i25.members') })
          .locator('tbody tr')
          .first(),
      ).toBeVisible();
      await page.getByLabel(t('en', 'common.reason')).last().fill(remark);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status').filter({ hasText: t('en', 'i25.submitted') })).toBeVisible();

      await workAs(
        page,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'W-01',
      );
      await decide(page, new RegExp(remark), 'APPROVE', '', 'WAGE_REVISION_HQ');

      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'I-25',
      );
      const event = await onSomePage(
        page,
        page.getByRole('table', { name: t('en', 'screen.I-25') }).getByText(name),
      );
      await event.click();
      await page.getByRole('button', { name: t('en', 'action.i25.fix') }).click();
      // The bulk fixation runs in the request (PENDING 17-bulk-job): hundreds of members take seconds.
      await expect(
        page.getByRole('status').filter({ hasText: /Fixed \d+, exceptions \d+, arrears raised 0/ }),
      ).toBeVisible({ timeout: 60_000 });
    },
  );

  test(
    'SRS-8-25 / I-25 field table: only an Active circular of category Wage Revision is offered; a DA order is refused by the API',
    { tag: ['@SRS-8-25', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'I-25',
      );
      await page.getByRole('button', { name: t('en', 'i25.act.define') }).click();
      const order = page.getByLabel(t('en', 'i25.order')).first();
      await expect(order.locator('option', { hasText: 'DEMO/WR/I25-01' })).toHaveCount(1);
      await expect(order.locator('option', { hasText: 'DEMO/DA-REV/LEA-01' })).toHaveCount(0);
      await expect(page.getByTestId('i25-no-order')).toHaveCount(0);
      const circulars = await page.evaluate(async () => {
        const response = await fetch('/nginx/fci-payroll/v1/masters/circulars/current', {
          credentials: 'same-origin',
        });
        return (await response.json()) as { id: number; number: string; category: string | null }[];
      });
      const da = circulars.find((c) => c.number === 'DEMO/DA-REV/LEA-01');
      expect(da?.category).toBe('DA');
      const body = {
        name: 'Edge (E2E wrong order)',
        circularId: da?.id,
        payrollTypes: ['STAFF_IDA'],
        effectiveFrom: monthStart(-3),
        fitment: 'PERCENTAGE',
        value: 3,
        rounding: 'RUPEE',
        reason: 'Edge (E2E)',
      };
      expect(await apiProblem(page, '/v1/wage-revisions', 'POST', body)).toEqual({
        status: 422,
        errorCode: 'MST-422-CIRCULAR-CATEGORY',
      });
      // An unknown order reads as not Active (nothing more is revealed).
      expect(
        await apiProblem(page, '/v1/wage-revisions', 'POST', { ...body, circularId: 999999999 }),
      ).toEqual({
        status: 409,
        errorCode: 'MST-409-CIRCULAR-STATE',
      });
    },
  );

  test(
    'SRS-8-25: a unit desk reads the revision but defines nothing',
    { tag: ['@SRS-8-25', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'I-25',
      );
      const table = page.getByRole('table', { name: t('en', 'screen.I-25') });
      await expect(table).toContainText('WR-');
      const define = page.getByRole('button', { name: t('en', 'i25.act.define') });
      await expect(define).toBeDisabled();
      await expect(define).toHaveAttribute('data-why', t('en', 'i25.guard.define'));
      // A valid body, so the refusal is the access gate and not bean validation (which answers 400 first).
      expect(
        await apiStatus(page, '/v1/wage-revisions', 'POST', {
          name: 'forged',
          circularId: 1,
          payrollTypes: ['STAFF_IDA'],
          scope: 'ALL_INDIA',
          effectiveFrom: '2030-01-01',
          fitment: 'FACTOR',
          value: 1.1,
          rounding: 'RUPEE',
          reason: 'forged',
        }),
      ).toBe(403);
    },
  );
});
