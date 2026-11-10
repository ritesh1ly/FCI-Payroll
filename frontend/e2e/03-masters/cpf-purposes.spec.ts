import { expect, test } from '@playwright/test';
import { apiStatus, decide, t, workAs } from '../support/app';

/**
 * CPF withdrawal purposes and checklists on M-19 through the UI (docs/plan/07-cpf.md phase 07f-1;
 * SRS-6-5, SRS-6-14, SRS-6-39; BR-CPF-028, 039): the SRS-named purposes seeded unconfirmed; the
 * Rule Administrator proposes a new purpose with its checklist and a different Rule Approver
 * approves it; the screen's own refusals; who may propose.
 *
 * Re-runnable on one stack: every run proposes a purpose code of its own.
 */
const RULE_ADMIN = 'su.hq@fci-demo.test';
const RULE_APPROVER = 'rule.approver@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const ruleApprover = { role: 'RULE_APPROVER', office: 'HQ001' };
const CODE = `E2E_P${Date.now() % 1_000_000_000}`;
const RUN = `E2E ${Date.now()}`;

function purposeTable(page: import('@playwright/test').Page) {
  return page.getByRole('table', { name: t('en', 'm19p.title') });
}

test.describe.serial('M-19 CPF withdrawal purposes', () => {
  test(
    'SRS-6-39: the SRS-named purposes are listed unconfirmed, property details on the house purposes, no Covid-19',
    {
      tag: [
        '@SRS-6-39',
        '@SRS-6-5',
        '@SRS-6-35',
        '@BR-CPF-028',
        '@BR-CPF-039',
        '@SC-07-0028',
        '@SC-07-0024',
        '@SC-07-0002',
        '@SC-07-0015',
        '@SRS-6-2',
        '@SRS-6-23',
        '@positive',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      const table = purposeTable(page);
      await expect(table).toContainText('READY_BUILT_HOUSE');
      const house = table.locator('tr', { hasText: 'READY_BUILT_HOUSE' }).first();
      await expect(house).toContainText(t('en', 'm19p.propertyDetails'));
      await expect(house).toContainText(t('en', 'm19p.unconfirmed'));
      await expect(table.locator('tr', { hasText: 'ILLNESS' }).first()).toContainText(
        t('en', 'mst.notConfigured'),
      );
      await expect(table).not.toContainText('COVID');
      // SRS-6-2 / SRS-6-23: the limits and conditions are rule versions on the M-19 register; the MGW basis is not.
      // The register's own rows (the purpose table names the limit key too).
      const register = page
        .locator('.card')
        .filter({ has: page.getByRole('heading', { name: t('en', 'm08.catalogue') }) });
      const rule = (key: string) =>
        register.getByRole('row').filter({ has: page.getByText(key, { exact: true }) });
      await expect(rule('CPF.LIMIT.MGW.MARRIAGE')).toContainText('12');
      await expect(rule('CPF.CONDITION.PARTFINAL.MAX_COUNT')).toContainText('6');
      await expect(rule('CPF.MGW')).toContainText(t('en', 'mst.notConfigured'));
    },
  );

  test(
    'SRS-6-14: a new purpose with its checklist is approved by a different Rule Approver',
    { tag: ['@SRS-6-14', '@SRS-6-5', '@SRS-G-1', '@SC-07-0042', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      await page.getByRole('button', { name: t('en', 'action.newPurpose') }).click();
      await page.getByLabel(t('en', 'm19p.code')).fill(CODE);
      await page.getByLabel(t('en', 'm19p.name')).fill(`${RUN} purpose`);
      await page.getByLabel(t('en', 'm19p.limit')).selectOption('CPF.LIMIT.MGW.MARRIAGE');
      await page.getByRole('button', { name: t('en', 'm19p.addDocument') }).click();
      await page.getByLabel(t('en', 'm19p.documentOf', [1]), { exact: true }).selectOption('CERTIFICATE');
      await page.getByLabel(t('en', 'common.reason')).fill(`${RUN} new purpose`);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('list', { name: t('en', 'm19p.pendingTitle') })).toContainText(CODE);

      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
      await decide(page, new RegExp(CODE), 'APPROVE');

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      const row = purposeTable(page).locator('tr', { hasText: CODE });
      await expect(row).toContainText('CERTIFICATE *');
      await expect(row).toContainText(t('en', 'm19p.tier.UNIT_CPF_ADMIN'));
      await expect(row.getByRole('button', { name: t('en', 'm19p.newVersionOf', [CODE]) })).toBeEnabled();
    },
  );

  test(
    'SRS-6-5: the form names Covid-19, a quota out of range and a duplicate document; the server refuses a version not after the one in force',
    { tag: ['@SRS-6-5', '@SRS-6-35', '@BR-CPF-028', '@SC-07-0005', '@SC-07-0024', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-19');
      await page.getByRole('button', { name: t('en', 'action.newPurpose') }).click();
      await page.getByLabel(t('en', 'm19p.code')).fill('COVID_19');
      const problems = page.getByRole('list', { name: t('en', 'm19p.problems') });
      await expect(problems).toContainText(t('en', 'error.CPF-422-PURPOSE-WITHDRAWN'));
      await page.getByLabel(t('en', 'm19p.quota')).fill('0');
      await expect(problems).toContainText(t('en', 'error.CPF-422-PURPOSE-QUOTA'));
      await page.getByRole('button', { name: t('en', 'm19p.addDocument') }).click();
      await page.getByRole('button', { name: t('en', 'm19p.addDocument') }).click();
      await page.getByLabel(t('en', 'm19p.documentOf', [1]), { exact: true }).selectOption('OTHER');
      await page.getByLabel(t('en', 'm19p.documentOf', [2]), { exact: true }).selectOption('OTHER');
      await expect(problems).toContainText(t('en', 'error.CPF-422-PURPOSE-DOCUMENT'));
      await expect(page.getByRole('button', { name: t('en', 'common.submitForApproval') })).toBeDisabled();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      expect(
        await apiStatus(page, '/v1/cpf/withdrawal-purposes', 'POST', {
          code: 'MARRIAGE',
          name: 'Marriage',
          category: 'ADVANCE',
          // A complete body (Jackson refuses a missing flag with 400), so the date rule answers.
          noCasteDispute: false,
          propertyDetails: false,
          confirmed: false,
          effectiveFrom: '2026-04-01',
          reason: `${RUN} forged`,
          documents: [],
        }),
      ).toBe(422);
    },
  );

  test(
    'SRS-22-21: the CPF Division reads the purposes in Hindi and proposes none; a Payroll Maker has no M-19',
    { tag: ['@SRS-22-21', '@SRS-6-5', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'hi', 'cpf.north@fci-demo.test', { role: 'CPF_DIVISION', office: 'NA11' }, 'M-19');
      await expect(page.getByRole('table', { name: t('hi', 'm19p.title') })).toContainText('MARRIAGE');
      await expect(page.getByRole('button', { name: t('hi', 'action.newPurpose') })).toHaveCount(0);
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      expect(await apiStatus(page, '/v1/cpf/withdrawal-purposes')).toBe(403);
    },
  );
});
