import { expect, type Page, test } from '@playwright/test';
import { apiStatus, decide, t, workAs } from '../support/app';

/**
 * M-19 PF Interest Rates & Parameters and M-18 Statutory Rates through the UI (docs/plan/07-cpf.md
 * phase 07b): the Rule Administrator at Headquarters records a rate and a different Rule Approver
 * activates it in W-01 (SRS-6-11, BR-CPF-033); a rate recorded undeclared is declared later; the
 * screen's own checks and the server's refusal of a rate slipped in before the approved one; who
 * may read and who may change; the CPF rule keys of the rule engine, not configured until a version
 * is approved (SRS-6-47).
 *
 * Re-runnable on one stack: every run records its rates in a financial year of its own, far in the
 * future (the minutes since 2026 pick the year), so no earlier run's rate is in the way.
 */
const RULE_ADMIN = 'su.hq@fci-demo.test';
const RULE_APPROVER = 'rule.approver@fci-demo.test';
const CPF_DIVISION = 'cpf.north@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const ruleApprover = { role: 'RULE_APPROVER', office: 'HQ001' };
const cpfDivision = { role: 'CPF_DIVISION', office: 'NA11' };
const delhiMaker = { role: 'PAYROLL_MAKER', office: 'DH001' };

const stamp = Date.now();
const FIRST = 2101 + (Math.floor((stamp - Date.UTC(2026, 0, 1)) / 60_000) % 7_800);
const YEAR = `${FIRST}-${String((FIRST + 1) % 100).padStart(2, '0')}`;
const RUN = `E2E ${stamp}`;

function rateRow(page: Page, source: 'FCI_TRUST' | 'RPFC') {
  return page.locator(`tr[data-reference="${YEAR}|CPF|${source}"]`);
}

async function recordRate(page: Page, source: 'FCI_TRUST' | 'RPFC', percent: string, declared: boolean) {
  await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
  await page.getByRole('button', { name: t('en', 'action.newRate'), exact: true }).click();
  const year = page.getByLabel(t('en', 'm19.finYear'));
  await year.fill(YEAR);
  await page.getByLabel(t('en', 'm19.rateSource')).selectOption(source);
  await page.getByLabel(t('en', 'm19.ratePercent')).fill(percent);
  const declaredBox = page.getByRole('checkbox', { name: t('en', 'm19.declared'), exact: true });
  if ((await declaredBox.isChecked()) !== declared) await declaredBox.click();
  await page.getByLabel(t('en', 'common.reason')).fill(`${RUN} ${source}`);
  await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
  await expect(page.getByRole('heading', { name: t('en', 'm19.newRate') })).toHaveCount(0);
  await expect(page.getByRole('list', { name: t('en', 'm19.pendingTitle') })).toContainText(YEAR);
}

async function approve(page: Page, source: 'FCI_TRUST' | 'RPFC') {
  await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
  await decide(page, new RegExp(`${YEAR}\\|CPF\\|${source}`), 'APPROVE');
}

test.describe.serial('M-19 PF interest rates and M-18 statutory rates', () => {
  test(
    'SRS-6-11: the Rule Administrator records a PF interest rate and a different Rule Approver activates it',
    { tag: ['@SRS-6-11', '@BR-CPF-033', '@SC-07-0010', '@SC-07-0106', '@positive'] },
    async ({ page }) => {
      await recordRate(page, 'FCI_TRUST', '8.25', true);
      await approve(page, 'FCI_TRUST');
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      const row = rateRow(page, 'FCI_TRUST');
      await expect(row).toContainText('8.25 %');
      await expect(row).toContainText(t('en', 'm19.population.ACTIVE_AND_DEPUTED'));
      await expect(row).toContainText(t('en', 'status.ACTIVE'));
      await expect(row.getByRole('button', { name: /^Declare the rate/ })).toHaveCount(0);
    },
  );

  test(
    'BR-CPF-033: the form names a wrong year or a date outside it; the server refuses a rate slipped in before the approved one',
    { tag: ['@BR-CPF-033', '@SRS-6-11', '@SC-07-0010', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      await page.getByRole('button', { name: t('en', 'action.newRate'), exact: true }).click();
      await page
        .getByLabel(t('en', 'm19.finYear'))
        .fill(`${FIRST}-${String((FIRST + 2) % 100).padStart(2, '0')}`);
      const problems = page.getByRole('list', { name: t('en', 'm19.problems') });
      await expect(problems).toContainText(t('en', 'error.CPF-422-FIN-YEAR'));
      await page.getByLabel(t('en', 'm19.finYear')).fill(YEAR);
      await page.getByLabel(t('en', 'mst.from')).fill(`${FIRST + 1}-04-01`);
      await expect(problems).toContainText(t('en', 'error.CPF-422-PERIOD'));
      await expect(page.getByRole('button', { name: t('en', 'common.submitForApproval') })).toBeDisabled();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      // A request the screen would never send: on the approved rate's own first day.
      expect(
        await apiStatus(page, '/v1/cpf/interest-rates', 'POST', {
          finYear: YEAR,
          fundType: 'CPF',
          rateSource: 'FCI_TRUST',
          ratePercent: '8.30',
          appliesFrom: `${FIRST}-04-01`,
          declared: true,
          reason: `${RUN} forged`,
        }),
      ).toBe(409);
    },
  );

  test(
    'SRS-6-11: an EPFO rate recorded undeclared is declared later at the declared rate',
    { tag: ['@SRS-6-11', '@BR-CPF-033', '@SC-07-0010', '@positive'] },
    async ({ page }) => {
      await recordRate(page, 'RPFC', '8.00', false);
      await approve(page, 'RPFC');
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      const row = rateRow(page, 'RPFC');
      await expect(row).toContainText(t('en', 'm19.undeclared'));
      await expect(row).toContainText(t('en', 'm19.population.INACTIVE'));
      await row.getByRole('button', { name: /^Declare the rate/ }).click();
      await page.getByLabel(t('en', 'm19.declaredRate')).fill('8.65');
      await page.getByLabel(t('en', 'common.reason')).fill(`${RUN} EPFO notified`);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await approve(page, 'RPFC');
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      await expect(rateRow(page, 'RPFC')).toContainText('8.65 %');
      await expect(rateRow(page, 'RPFC')).not.toContainText(t('en', 'm19.undeclared'));
    },
  );

  test(
    'SRS-22-21: the CPF Division reads the rates and the parameters only; a Payroll Maker has no M-19',
    { tag: ['@SRS-22-21', '@SRS-6-11', '@SRS-6-45', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'hi', CPF_DIVISION, cpfDivision, 'M-19');
      await expect(page.getByRole('heading', { level: 1, name: t('hi', 'screen.M-19') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('hi', 'action.newRate'), exact: true })).toHaveCount(0);
      // V077_20 (SRS M-19, SRS-6-45): the parameters card shows the M-19 rule keys, read only.
      await expect(page.getByText(t('hi', 'm19.parametersNoAccess'))).toHaveCount(0);
      await expect(
        page.getByRole('row').filter({ has: page.getByText('CPF.INTEREST.EXMEMBER_YEARS', { exact: true }) }),
      ).toBeVisible();
      await expect(page.getByRole('button', { name: t('hi', 'action.newRuleVersion') })).toHaveCount(0);
      expect(
        await apiStatus(page, '/v1/rules/CPF.VPF.MAX/versions', 'POST', {
          effectiveFrom: `${FIRST}-04-01`,
          value: '12',
          circularId: 1,
          reason: `${RUN} not mine`,
        }),
      ).toBe(403);
      expect(
        await apiStatus(page, '/v1/cpf/interest-rates', 'POST', {
          finYear: YEAR,
          fundType: 'CPF',
          rateSource: 'FCI_TRUST',
          ratePercent: '9',
          appliesFrom: `${FIRST}-10-01`,
          declared: true,
          reason: `${RUN} not mine`,
        }),
      ).toBe(403);
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', delhiMaker, 'W-02');
      expect(await apiStatus(page, '/v1/cpf/interest-rates')).toBe(403);
    },
  );

  test(
    'SRS-6-47: M-18 lists the statutory rate keys and M-19 the CPF parameters, not configured until a version is approved',
    { tag: ['@SRS-6-47', '@SRS-6-9', '@SC-07-0036', '@SC-07-0104', '@SC-07-0105', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-18');
      for (const key of [
        'CPF.EPS.RATE',
        'CPF.HIGHERPENSION.RATE',
        'CPF.RATE.EMPLOYEE',
        'CPF.RATE.EMPLOYER',
      ]) {
        await expect(
          page
            .getByTestId('m18-register')
            .getByRole('row')
            .filter({ has: page.getByText(key, { exact: true }) }),
        ).toBeVisible();
      }
      await expect(page.getByText('CPF.VPF.MAX', { exact: true })).toHaveCount(0);
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      const stop = page
        .getByRole('row')
        .filter({ has: page.getByText('CPF.INTEREST.EXMEMBER_YEARS', { exact: true }) });
      await expect(stop).toContainText('3');
      // The VPF maximum has a demo version since module 16 phase 16e-2 (V040_9_9); the MGW basis
      // stays without one, so it shows as not configured.
      await expect(
        page.getByRole('row').filter({ has: page.getByText('CPF.MGW', { exact: true }) }),
      ).toContainText(t('en', 'mst.notConfigured'));
    },
  );
});
