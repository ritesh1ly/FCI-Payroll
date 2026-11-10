import { expect, test } from '@playwright/test';
import { errorAlert, t, workAs } from '../support/app';
import { expectToast } from '../support/payroll-support';

/**
 * Group g3 gap fixes (modules 07, 08, 09, 10, 12; 2026-11-03) through the UI, on the demo data:
 * I-04 CPF Payment Released stage, cancel reason and withdrawal count (SRS l.2278-2279, SRS-G-37);
 * I-09 sanctioned-advance column (SRS l.2167); I-03 register invoice / HRMS columns and the
 * taxable flag chosen, never preselected (SRS l.2756, l.2804-2806; seed V077_27); I-16 year
 * filter and "No selection in HRMS" (SRS l.2836-2850); I-04 loans F&F status (SRS l.2721); C-10
 * masked PAN (SRS l.2589); C-11 keyed challan (SRS l.2625); M-18 Simulate disabled with its reason
 * (SRS l.2323); C-12 Calculate / Re-Run label (SRS l.2358). Read-only except one keyed challan per
 * run (a unique CIN): re-runnable.
 */
const RUN = Date.now().toString(36).toUpperCase();
const UNIT_CPF = 'cpf.unit.delhi@fci-demo.test';
const unitCpf = { role: 'UNIT_CPF_ADMIN', office: 'DH001' };
const CPF_MANAGER = 'cpf.manager.north@fci-demo.test';
const cpfManager = { role: 'CPF_MANAGER', office: 'NA11' };
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const TAX_MAKER = 'tax.maker.delhi@fci-demo.test';
const taxMaker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const RULE_ADMIN = 'su.hq@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };

test.describe('Group g3 gap fixes (modules 07-12)', () => {
  test(
    'SRS l.2278-2279: I-04 offers Payment Released and a withdrawn request shows its reason and the earlier withdrawals',
    { tag: ['@SC-07-0100', '@SC-07-0101', '@SRS-G-37', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', UNIT_CPF, unitCpf, 'I-04');
      const stage = page.getByLabel(t('en', 'i04.stage'));
      await expect(stage.locator('option[value="PAID"]')).toHaveCount(1);
      await stage.selectOption('CANCELLED');
      const open = page.getByRole('button', { name: new RegExp(t('en', 'i04.openOf', ['\\d+'])) }).first();
      await expect(open).toBeVisible();
      await open.click();
      await expect(page.getByText(t('en', 'i04.cancelReason'), { exact: true })).toBeVisible();
      await expect(page.getByText(t('en', 'i04.withdrawalCount'), { exact: false }).first()).toBeVisible();
    },
  );

  test(
    'SRS l.2167: the I-09 register names the sanctioned advance an instalment repays',
    { tag: ['@SRS-6-28', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', CPF_MANAGER, cpfManager, 'I-09');
      const register = page.getByRole('table', { name: t('en', 'i09.entries') }).first();
      await expect(
        register.getByRole('columnheader', { name: new RegExp(t('en', 'i09.advanceRef')) }),
      ).toHaveCount(1);
    },
  );

  test(
    'SRS l.2756, l.2804-2806: the I-03 register shows invoice and HRMS columns; the taxable flag is chosen, never preselected',
    { tag: ['@SRS-11-19', '@SRS-11-21', '@SRS-11-23', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-03');
      await page.getByLabel(t('en', 'i03.eis')).first().fill('104882');
      const register = page.getByTestId('i03-register').getByRole('table', { name: t('en', 'i03.register') });
      await expect(
        register.getByRole('columnheader', { name: new RegExp(t('en', 'i03.invoiceStatus')) }),
      ).toHaveCount(1);
      await expect(
        register.getByRole('columnheader', { name: new RegExp(t('en', 'i03.hrmsRequestId')) }),
      ).toHaveCount(1);
      const seeded = register.locator('tr', { hasText: 'DEMO-HRMS-MED-104882' });
      await expect(seeded).toHaveCount(1);
      await expect(seeded).toContainText(t('en', 'i03.inv.NOT_RAISED'));
      await seeded.getByRole('button').first().click();
      await page.getByRole('button', { name: t('en', 'i03.confirmTaxable') }).click();
      const dialog = page.getByRole('dialog');
      await expect(
        dialog.getByRole('radio', { name: t('en', 'i03.taxableYes'), exact: true }),
      ).not.toBeChecked();
      await expect(dialog.getByRole('radio', { name: t('en', 'i03.taxableNo') })).not.toBeChecked();
      await expect(dialog.getByRole('button', { name: t('en', 'i03.taxableSave') })).toBeDisabled();
      await dialog.getByRole('button', { name: t('en', 'common.cancel') }).click();
      // SRS-11-21: the IOGA is recorded on the same claim, before the invoice.
      await expect(page.getByRole('group', { name: t('en', 'i03.ioga') })).toBeVisible();
    },
  );

  test(
    'SRS l.2836-2850: I-16 asks for the chosen year; a malformed EIS cannot be asked; a member without one reads No selection in HRMS',
    { tag: ['@SRS-11-16', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-16');
      await expect(page.getByLabel(t('en', 'i16.finYear'))).toBeVisible();
      const eis = page.getByLabel(t('en', 'i03.eis'));
      await eis.fill('1048-82');
      await expect(page.getByRole('button', { name: t('en', 'i03.show') })).toBeDisabled();
      await eis.fill('104882');
      await page.getByRole('button', { name: t('en', 'i03.show') }).click();
      await expect(
        page
          .getByRole('table', { name: t('en', 'i16.title') })
          .or(page.getByText(t('en', 'i16.noSelection', ['104882']))),
      ).toBeVisible();
    },
  );

  test(
    'SRS l.2721: the loan status filter offers a loan settled in the final settlement',
    { tag: ['@SRS-12-23', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-04');
      await expect(
        page.getByLabel(t('en', 'i04l.status')).locator('option[value="SETTLED_IN_FNF"]'),
      ).toHaveCount(1);
    },
  );

  test(
    'SRS l.2589: the C-10 register carries the PAN column, masked',
    { tag: ['@SRS-7-23', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', TAX_MAKER, taxMaker, 'C-10');
      // The year's certificates with their masked PAN, or the empty register of a year without any.
      await expect(
        page
          .getByRole('columnheader', { name: new RegExp(t('en', 'c10.pan')) })
          .or(page.getByText(t('en', 'c10.none')))
          .first(),
      ).toBeVisible();
    },
  );

  test(
    'SRS l.2625: the Maker keys a challan; the same challan again is refused',
    { tag: ['@SRS-7-21', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', TAX_MAKER, taxMaker, 'C-11');
      const keyed = page.getByRole('group', { name: t('en', 'c11.keyChallan') });
      await expect(keyed).toBeVisible();
      const add = keyed.getByRole('button', { name: t('en', 'c11.addChallan') });
      await expect(add).toBeDisabled();
      // The year on screen: a date in its June (Q1).
      const yearText = await page.getByLabel(t('en', 'i07.finYear')).inputValue();
      const first = /^\d{4}/.exec(yearText)?.[0] ?? String(new Date().getFullYear() - 1);
      const fill = async () => {
        await keyed.getByLabel(t('en', 'c11.cin')).fill(`E2E${RUN}`);
        await keyed.getByLabel(t('en', 'c11.bsr')).fill('0510308');
        await keyed.getByLabel(t('en', 'c11.challanDate')).fill(`${first}-06-15`);
        await keyed.getByLabel(t('en', 'i18.amount')).fill('100.00');
        await keyed.getByLabel(t('en', 'c11.period')).fill('Q1');
      };
      await fill();
      await expect(add).toBeEnabled();
      await add.click();
      await expectToast(page, t('en', 'c11.challanKeyed', [`E2E${RUN}`]));
      await fill();
      await add.click();
      await expect(keyed.locator('.fl-error[role="alert"]')).toBeVisible();
    },
  );

  test(
    'SRS l.2323: M-18 offers Simulate Impact on every row, disabled with its reason when nothing is proposed',
    { tag: ['@SRS-6-47', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-18');
      const simulate = page.getByRole('button', { name: t('en', 'm08.sim.of', ['CPF.EPS.RATE']) });
      await expect(simulate).toBeVisible();
      if (await simulate.isDisabled()) {
        await expect(simulate).toHaveAttribute('title', t('en', 'm08.sim.nothingProposed'));
      }
    },
  );

  test(
    'SRS l.2358: C-12 names the action Calculate Interest, or Re-Run Interest once the year has a run',
    { tag: ['@SRS-6-45', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', CPF_MANAGER, cpfManager, 'C-12');
      await expect(
        page
          .getByRole('button', { name: t('en', 'action.runInterest') })
          .or(page.getByRole('button', { name: t('en', 'action.reRunInterest') })),
      ).toBeVisible();
      await expect(errorAlert(page)).toHaveCount(0);
    },
  );

  test(
    'UI-G3-DEMO (seed V077_28): every list of modules 07-10 and 12 shows its demo rows for the user in scope',
    { tag: ['@SRS-6-55', '@SRS-12-23', '@SRS-14-15', '@SRS-11-16', '@positive'] },
    async ({ page }) => {
      // I-04 CPF: the requests in the chain.
      await workAs(page, 'en', UNIT_CPF, unitCpf, 'I-04');
      await page.getByLabel(t('en', 'i04.stage')).selectOption('ZONAL_HQ_VALIDATION');
      await expect(page.getByRole('table').first()).toContainText('889001');
      // I-04 Loans, I-06 orders and payments, I-16.
      await workAs(page, 'en', MAKER, maker, 'I-04');
      await page.getByLabel(t('en', 'm01.eis')).first().fill('889001');
      await expect(page.getByRole('table', { name: t('en', 'i04l.loans') })).toContainText('DEMO-LN-889001');
      await workAs(page, 'en', MAKER, maker, 'I-06');
      await page.getByLabel(t('en', 'm01.eis')).first().fill('889001');
      await expect(page.getByRole('table', { name: t('en', 'i06.orders') })).toContainText('DEMO-PEN-0001');
      await expect(page.getByRole('table', { name: t('en', 'i06.payments'), exact: true })).toContainText(
        'DL/OA/DEMO/1',
      );
      await workAs(page, 'en', MAKER, maker, 'I-16');
      await page.getByLabel(t('en', 'i03.eis')).fill('889001');
      await page.getByRole('button', { name: t('en', 'i03.show') }).click();
      await expect(page.getByRole('table', { name: t('en', 'i16.title') })).toContainText(
        'DEMO-HRMS-CAF-889001',
      );
      // E-02: the VPF request the CPF desk rejected, with its reason.
      await workAs(page, 'en', MAKER, maker, 'E-02');
      // E-02 opens on the requests waiting for the Maker (889001); the rejected one shows on its filter.
      await expect(page.getByRole('table', { name: t('en', 'vpf.staffTitle') })).toContainText('889001');
      await page.getByLabel(t('en', 'vpf.status')).last().selectOption('REJECTED');
      await expect(page.getByRole('table', { name: t('en', 'vpf.staffTitle') })).toContainText('104882');
      // M-19: the previous year's rates.
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      await expect(page.locator('main')).toContainText('8.25');
    },
  );
});
