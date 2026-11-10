import { expect, test } from '@playwright/test';
import { apiStatus, businessToday, decide, previousFinYear, t, workAs } from '../support/app';
import { expectToast } from '../support/payroll-support';

/**
 * I-18 Savings & Tax Rebate Declarations through the UI (docs/plan/08-income-tax.md phase 08d;
 * SRS-7-11, SRS-7-25; BR-TAX-015): the tax desk's Payroll Maker records an honorarium of a member
 * with its supporting document and sends it; a different Payroll Checker approves it in the
 * Approvals Inbox; the entry then counts. The register of the year before (demo data V039_9_1) reaches a
 * second page. Re-runnable: every run records an entry of its own for tax.nopan@ (EIS 870003).
 */
const MAKER = 'tax.maker.delhi@fci-demo.test';
const CHECKER = 'tax.checker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };
const EIS = '870003';
const RUN = `E2E ${Date.now()}`;

test.describe.serial('I-18 savings and income', () => {
  test(
    'SRS-7-25: an honorarium with its document is saved, sent and approved by a different Checker',
    { tag: ['@SRS-7-25', '@SRS-7-11', '@BR-TAX-015', '@SRS-G-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-18');
      await page.getByLabel(t('en', 'm01.eis')).fill(EIS);
      await page.getByRole('button', { name: t('en', 'i18.openMember') }).click();
      await page.getByLabel(t('en', 'i18.document')).setInputFiles({
        name: 'honorarium.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7 e2e honorarium'),
      });
      await page.getByLabel(t('en', 'i18.incomeHead')).fill('Honorarium');
      await page.getByLabel(t('en', 'i18.amount')).fill('7500');
      await page.getByLabel(t('en', 'm18s.description')).fill(RUN);
      await page.getByLabel(t('en', 'i18.documentDate')).fill(businessToday(-1));
      await page.getByLabel(t('en', 'i18.documentIssuer')).fill('Institute, Delhi');
      await page.getByRole('button', { name: t('en', 'common.save') }).click();
      await expectToast(page, t('en', 'i18.saved'));
      const row = page.getByRole('table', { name: t('en', 'i18.entries') }).locator('tr', { hasText: RUN });
      await expect(row).toContainText(t('en', 'i18.status.MAKER_SAVED'));
      // The row action is named for its entry (aria-label i18.submitOf).
      await row.getByRole('button', { name: new RegExp(t('en', 'i18.submitOf', ['\\d+'])) }).click();
      await expectToast(page, t('en', 'i18.submitted'));
      await expect(row).toContainText(t('en', 'i18.status.PENDING_CHECKER'));

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`OTHER_INCOME ${EIS}`), 'APPROVE');

      await workAs(page, 'en', MAKER, maker, 'I-18');
      await page.getByLabel(t('en', 'm01.eis')).fill(EIS);
      await page.getByRole('button', { name: t('en', 'i18.openMember') }).click();
      await expect(
        page.getByRole('table', { name: t('en', 'i18.entries') }).locator('tr', { hasText: RUN }),
      ).toContainText(t('en', 'i18.status.APPROVED'));
    },
  );

  test(
    'SRS-NF-3: the register of the year before reaches a second page; another office reads the member as missing',
    { tag: ['@SRS-NF-3', '@SRS-22-21', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-18');
      await page.getByLabel(t('en', 'i07.finYear')).selectOption(previousFinYear());
      await page.getByLabel(t('en', 'page.size')).selectOption('100');
      await expect(page.getByRole('button', { name: t('en', 'page.next') })).toBeEnabled();
      await workAs(
        page,
        'en',
        'maker.kolkata@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'KL001' },
        'I-18',
      );
      expect(await apiStatus(page, `/v1/tax/savings/members/${EIS}`)).toBe(404);
      await workAs(page, 'en', 'tax.old@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      expect(await apiStatus(page, '/v1/tax/savings')).toBe(403);
    },
  );
  test(
    'US-GAP-115 (SRS-EH-12): cancelling a saved entry asks first; declined, it stays saved; confirmed, it is cancelled',
    { tag: ['@US-GAP-115', '@SRS-EH-12', '@SRS-G-54', '@edge'] },
    async ({ page }) => {
      const what = `${RUN} cancel`;
      await workAs(page, 'en', MAKER, maker, 'I-18');
      await page.getByLabel(t('en', 'm01.eis')).fill(EIS);
      await page.getByRole('button', { name: t('en', 'i18.openMember') }).click();
      await page.getByLabel(t('en', 'i18.document')).setInputFiles({
        name: 'honorarium.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7 e2e honorarium'),
      });
      await page.getByLabel(t('en', 'i18.incomeHead')).fill('Honorarium');
      await page.getByLabel(t('en', 'i18.amount')).fill('1200');
      await page.getByLabel(t('en', 'm18s.description')).fill(what);
      await page.getByLabel(t('en', 'i18.documentDate')).fill(businessToday(-1));
      await page.getByLabel(t('en', 'i18.documentIssuer')).fill('Institute, Delhi');
      await page.getByRole('button', { name: t('en', 'common.save') }).click();
      await expectToast(page, t('en', 'i18.saved'));
      const row = page.getByRole('table', { name: t('en', 'i18.entries') }).locator('tr', { hasText: what });
      const cancel = row.getByRole('button', { name: new RegExp(t('en', 'i18.cancelOf', ['\\d+'])) });
      // No reason, no cancellation.
      await expect(cancel).toBeDisabled();
      await page.locator('#i18-cancel-reason').fill('Entered twice (E2E)');
      await cancel.click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toContainText(t('en', 'confirm.cancel.body'));
      await dialog.getByRole('button', { name: t('en', 'common.cancel'), exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await expect(row).toContainText(t('en', 'i18.status.MAKER_SAVED'));
      await cancel.click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'confirm.cancel.yes') })
        .click();
      await expectToast(page, t('en', 'i18.cancelled'));
      await expect(row).toContainText(t('en', 'i18.status.CANCELLED'));
    },
  );
});
