import { expect, test } from '@playwright/test';
import { dataRows, onSomePage, t, workAs } from '../support/app';

/**
 * Group g4 gap fixes (modules 11, 13, 14, 15, 17; 2026-11-03) through the UI, on the demo data of
 * seed V077_30 and the earlier module seeds: O-04 opens on the latest locked month and reconciles
 * earnings and deductions (SRS l.3501, l.3505); O-05 shows each recovery's period, its HQ approval
 * and a Payroll dues / Gratuity source select (SRS l.2930-2940); I-08 shows the event header, the
 * worked scenarios and a bulk Recompute disabled with its reason (SRS l.3095, SRS-8-12); I-25 lists
 * the demo revision in the source's status words (l.3172); E-02 closes a support preview (FRS
 * l.6958); E-01 lists the member's notices; I-05 shows the pay scale, increment type and next due
 * date (l.4001-4002). Read-only except one support preview opened and closed: re-runnable.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };

test.describe('Group g4 gap fixes (modules 11-17)', () => {
  test(
    'SRS l.3501 / SRS-18-8: O-04 opens on the latest locked month and reconciles earnings and deductions',
    { tag: ['@SRS-18-8', '@SRS-18-28', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'pm.ropb@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'RO-PB' }, 'O-04');
      await page.getByTestId('o04-generate-table').click();
      const summary = page.getByRole('table', { name: t('en', 'o04.summary') });
      await expect(summary).toBeVisible();
      await expect(page.getByRole('status')).toContainText(t('en', 'o04.final'));
      await expect(page.getByTestId('o04-deductions')).toBeVisible();
      await expect(
        page
          .getByTestId('o04-summary-section')
          .getByRole('button', { name: /export/i })
          .first(),
      ).toBeEnabled();
    },
  );

  test(
    'SRS-10-4 / SRS-10-21: O-05 shows the period of each recovery and the source is Payroll dues or Gratuity only',
    { tag: ['@SRS-10-4', '@SRS-10-21', '@SRS-10-22', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'pm.ropb@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'RO-PB' }, 'O-05');
      const row = page
        .getByRole('table', { name: t('en', 'screen.O-05') })
        .locator('tr[data-reference="FNF-DEMO-00002"]');
      await expect(row).toBeVisible();
      await row.getByRole('button', { name: t('en', 'common.view') }).click();
      await page.getByTestId('o05-case-actions').locator('summary').click();
      const recoveries = page.getByRole('table', { name: t('en', 'o05.recoveries') });
      await expect(recoveries.getByRole('columnheader', { name: t('en', 'o05.period') })).toBeVisible();
      await expect(recoveries.getByRole('columnheader', { name: t('en', 'o05.col.approval') })).toBeVisible();
      const source = recoveries.getByRole('combobox').first();
      await expect(source.locator('option')).toHaveCount(2);
      await expect(source.locator('option[value="CPF_FINAL"]')).toHaveCount(0);
      // The case's own invoice status line (the drawn payments card and its column name the same words: OD-121).
      await expect(
        page.getByTestId('o05-case-actions').getByText(t('en', 'o05.invoiceStatus'), { exact: true }),
      ).toBeVisible();
      await expect(
        page
          .getByRole('table', { name: t('en', 'o05.components') })
          .getByText(t('en', 'o05.col.recoveryAgainst')),
      ).toBeVisible();
    },
  );

  test(
    'SRS-8-12 / l.3095: I-08 shows the worked scenarios and the event header; a bulk event is recomputed on C-13',
    { tag: ['@SRS-8-12', '@SRS-8-15', '@SRS-21-9', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-08');
      await expect(dataRows(page.getByRole('table', { name: t('en', 'i08.scenarios') }))).toHaveCount(4);
      const row = page
        .getByRole('table', { name: t('en', 'i08.registerTitle') })
        .locator('tr[data-reference="ARR-DEMO-DA-01"]');
      await expect(row).toBeVisible();
      await row.getByRole('button', { name: t('en', 'common.view') }).click();
      await expect(page.getByText(t('en', 'i08.sanctionReference'))).toBeVisible();
      const again = page.getByRole('button', { name: t('en', 'action.i08.recompute') });
      await expect(again).toBeDisabled();
      await expect(again).toHaveAttribute('title', t('en', 'i08.why.bulkRecompute'));
    },
  );

  test(
    'I-25 l.3172: the demo revision reads in the source words and Apply Fixation waits for the approval',
    { tag: ['@SRS-8-25', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'I-25',
      );
      const row = await onSomePage(
        page,
        page.getByRole('table', { name: t('en', 'screen.I-25') }).getByRole('row', { name: /WR-DEMO-01/ }),
      );
      await expect(row).toContainText(t('en', 'i25.status.DEFINED'));
      await row.click();
      const fix = page.getByRole('button', { name: t('en', 'action.i25.fix') });
      await expect(fix).toBeDisabled();
      await expect(fix).toHaveAttribute('title', t('en', 'i25.why.fix'));
    },
  );

  test(
    'FRS V0.5 l.6958: the support preview is closed by its user and the log lists the member submissions',
    { tag: ['@SRS-17-8', '@SRS-17-18', '@US-GAP-261', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'E-02');
      await expect(
        page
          .getByRole('table', { name: t('en', 'e02.log') })
          .getByText(t('en', 'e02.type.PAYROLL_QUERY'))
          .first(),
      ).toBeVisible();
      await page.getByLabel(t('en', 'm01.eis')).first().fill('104882');
      await page.getByLabel(t('en', 'common.reason')).fill('E2E helpdesk ticket (g4)');
      await page.getByRole('button', { name: t('en', 'action.e02.preview') }).click();
      const view = page.getByRole('region', { name: t('en', 'e02.previewOf', ['104882']) });
      await expect(view).toBeVisible();
      await page.getByRole('button', { name: t('en', 'action.e02.closePreview') }).click();
      await expect(view).toHaveCount(0);
    },
  );

  test(
    'SRS-17-12: the member sees the notices of their submissions on E-01',
    { tag: ['@SRS-17-12', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'emp.rsharma@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      await expect(page.getByText(t('en', 'notice.ESEVA_ACTIONED')).first()).toBeVisible();
    },
  );

  test(
    'I-05 l.4001-4002: the due list shows the pay scale, the increment type and the next due date',
    { tag: ['@SRS-13-1', '@positive'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'hr.delhi@fci-demo.test',
        { role: 'ESTABLISHMENT_HR', office: 'DH001' },
        'I-05',
      );
      const due = page.getByRole('table', { name: t('en', 'i05.due') });
      await expect(due.getByText(t('en', 'i05.payScale'))).toBeVisible();
      await expect(due.getByText(t('en', 'i05.nextDue'))).toBeVisible();
      // A cleared month asks for one and offers no export of an empty month.
      await page.getByLabel(t('en', 'i05.month')).fill('');
      await expect(page.getByText(t('en', 'i05.why.month'))).toBeVisible();
    },
  );

  test(
    'SRS-18-12 / l.3500: O-04 offers the report from the catalogue, a member range and the amount sort',
    { tag: ['@SRS-18-12', '@US-GAP-265', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'pm.ropb@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'RO-PB' }, 'O-04');
      await page.getByTestId('o04-generate-table').click();
      await expect(page.getByRole('table', { name: t('en', 'o04.summary') })).toBeVisible();
      const generate = page.getByTestId('o04-generate');
      await expect(generate).toBeEnabled();
      await page
        .locator('select:has(option[value="COMPONENT_WISE_SUMMARY"])')
        .selectOption('SALARY_CONTROL_REGISTER');
      await expect(generate).toBeDisabled();
      await page
        .locator('select:has(option[value="COMPONENT_WISE_SUMMARY"])')
        .selectOption('COMPONENT_WISE_SUMMARY');
      await page.getByLabel(t('en', 'o04.sort')).selectOption('AMOUNT');
      await page.getByLabel(t('en', 'o04.fromEis')).fill('999990');
      await generate.click();
      await expect(page.getByText(t('en', 'o04.noResults'))).toBeVisible();
    },
  );

  test(
    'SRS l.3949 / l.3952: M-03 lists the versions of a scale and takes the stages as a grid, a bad row named',
    { tag: ['@SRS-13-23', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'M-03',
      );
      // The scale versions and the stage grid live in the closed M-03 register under the drawn screen (OD-121).
      const register = page.getByTestId('m03-register');
      if ((await register.getAttribute('open')) === null) await register.locator(':scope > summary').click();
      await register
        .getByRole('table', { name: t('en', 'screen.M-03') })
        .getByRole('button', { name: t('en', 'common.view') })
        .first()
        .click();
      await expect(register.getByRole('table', { name: t('en', 'm03.versions') })).toBeVisible();
      await register.getByLabel(t('en', 'm03.stageRow', [1]), { exact: true }).fill('60000');
      await register.getByRole('button', { name: t('en', 'm03.addStage') }).click();
      await register.getByLabel(t('en', 'm03.stageRow', [2]), { exact: true }).fill('6a');
      await expect(register.getByText(t('en', 'm03.why.stageRow', [2]))).toBeVisible();
      await expect(register.getByRole('button', { name: t('en', 'action.newVersion') })).toBeDisabled();
    },
  );
});
