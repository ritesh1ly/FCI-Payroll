import { expect, type Page, test } from '@playwright/test';
import { apiStatus, decide, t, workAs } from '../support/app';

/**
 * Module 17 (plan 17) on the demo members of seed V049_3_9: Vikram Kumar (109991, E-4 with stages) and Meera Nair
 * (109992, E-2 without stages or rule), both due on the first of this month. Establishment / HR sees the Increment
 * Due list - 109991 proposed at the next stage, 109992 flagged "not configured" - and submits 109991; the Payroll
 * Checker approves in W-01 (once: on a re-run the increment is applied and 109991 is off the list). M-03 shows the E-4
 * stages; a unit Payroll Maker proposes no scale version, on the screen or by the API.
 */
const office = { office: 'DH001' };

/** M-03 keeps the scale list, the stage grid and the versions in a closed register under the drawn screen (OD-121). */
async function openScaleRegister(page: Page) {
  const register = page.getByTestId('m03-register');
  if ((await register.getAttribute('open')) === null) await register.locator(':scope > summary').click();
}
const remark = `E2E increment run ${Date.now()}`;

test.describe.serial('Increment & pay fixation (I-05, M-03)', () => {
  test(
    'SRS-13-21: the due list proposes the next stage, flags what cannot be computed, and the run is approved',
    { tag: ['@SRS-13-21', '@SRS-13-1', '@SRS-13-23', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'hr.delhi@fci-demo.test', { role: 'ESTABLISHMENT_HR', ...office }, 'I-05');
      const table = page.getByRole('table', { name: t('en', 'i05.due') });
      const meera = table.locator('tr[data-eis="109992"]');
      await expect(meera).toBeVisible();
      await expect(meera).toContainText(t('en', 'i05.flag.NOT_CONFIGURED'));
      await expect(meera.getByRole('checkbox')).toBeDisabled();
      const vikram = table.locator('tr[data-eis="109991"]');
      if ((await vikram.count()) === 0) {
        return; // applied by an earlier run: off the due list
      }
      await expect(vikram).toContainText('64,300');
      const box = vikram.getByRole('checkbox');
      if (await box.isEnabled()) {
        await box.check();
        await page.getByLabel(t('en', 'common.reason')).first().fill(remark);
        await page.getByRole('button', { name: new RegExp(t('en', 'action.i05.submit')) }).click();
        await expect(
          page.getByRole('status').filter({ hasText: t('en', 'i05.submitted', [1]) }),
        ).toBeVisible();
        await workAs(page, 'en', 'ddo.delhi@fci-demo.test', { role: 'PAYROLL_CHECKER', ...office }, 'W-01');
        await decide(page, new RegExp(remark), 'APPROVE');
      }
    },
  );

  // SC-17-0037 (scenario of a module after 07: cited, not tagged)
  test(
    'SRS-G-40: the I-05 due list in Hindi carries the Hindi column titles and flag',
    { tag: ['@SRS-G-40', '@SRS-13-21', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'hi', 'hr.delhi@fci-demo.test', { role: 'ESTABLISHMENT_HR', ...office }, 'I-05');
      const table = page.getByRole('table', { name: t('hi', 'i05.due') });
      await expect(table).toBeVisible();
      for (const key of ['i05.member', 'i05.payScale', 'i05.dueDate', 'i05.oldBasic', 'i05.revisedBasic']) {
        await expect(table.getByRole('columnheader', { name: t('hi', key) })).toBeVisible();
      }
      await expect(table.locator('tr[data-eis="109992"]')).toContainText(t('hi', 'i05.flag.NOT_CONFIGURED'));
      await workAs(page, 'en', 'hr.delhi@fci-demo.test', { role: 'ESTABLISHMENT_HR', ...office }, 'I-05');
    },
  );

  test(
    'M-03: the scale shows its stages; a unit Payroll Maker proposes no version',
    { tag: ['@SRS-13-23', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...office }, 'M-03');
      await openScaleRegister(page);
      const row = page.getByRole('table', { name: t('en', 'screen.M-03') }).locator('tr[data-code="E-4"]');
      await expect(row).toBeVisible();
      await row.getByRole('button', { name: t('en', 'common.view') }).click();
      await expect(page.getByRole('list', { name: t('en', 'm03.stageGrid') })).toContainText('64,300');
      await expect(page.getByRole('button', { name: t('en', 'action.newVersion') })).toHaveCount(0);
      expect(
        await apiStatus(page, '/v1/pay-scales/E-4/versions', 'POST', {
          min: 1,
          max: 2,
          effectiveFrom: '2030-01-01',
          circularId: 1,
          stages: [1],
          reason: 'x',
        }),
      ).toBe(403);
    },
  );

  test(
    'M-03: a version typed for one scale is not carried to the next scale viewed (the card is per scale)',
    { tag: ['@SRS-13-23', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'M-03',
      );
      await openScaleRegister(page);
      const table = page.getByRole('table', { name: t('en', 'screen.M-03') });
      await table
        .locator('tr[data-code="E-4"]')
        .getByRole('button', { name: t('en', 'common.view') })
        .click();
      const card = page.locator('.card').filter({ hasText: t('en', 'm03.scaleTitle', ['E-4']) });
      await expect(card.getByRole('list', { name: t('en', 'm03.stageGrid') })).toContainText('64,300');
      await card.getByLabel(t('en', 'm03.min'), { exact: true }).fill('12345');
      await card.getByLabel(t('en', 'm03.max'), { exact: true }).fill('23456');
      // Another scale's View while the E-4 draft is open: a fresh card, nothing of E-4 left in it.
      await table
        .locator('tr[data-code="E-2"]')
        .getByRole('button', { name: t('en', 'common.view') })
        .click();
      const other = page.locator('.card').filter({ hasText: t('en', 'm03.scaleTitle', ['E-2']) });
      await expect(other).toBeVisible();
      await expect(page.locator('.card').filter({ hasText: t('en', 'm03.scaleTitle', ['E-4']) })).toHaveCount(
        0,
      );
      await expect(other.getByLabel(t('en', 'm03.min'), { exact: true })).toHaveValue('');
      await expect(other.getByLabel(t('en', 'm03.max'), { exact: true })).toHaveValue('');
      await expect(other.getByRole('list', { name: t('en', 'm03.stageGrid') })).toHaveCount(0);
    },
  );
});
