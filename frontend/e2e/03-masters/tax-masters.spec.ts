import { expect, test } from '@playwright/test';
import { apiStatus, businessToday, decide, t, workAs } from '../support/app';

/**
 * The income-tax section and PT state masters on M-18 through the UI (docs/plan/08-income-tax.md
 * phase 08b; SRS-7-2, SRS-7-4, SRS-7-20, SRS-7-26; BR-TAX-002, BR-TAX-006): the Rule
 * Administrator proposes a section and a state, a different Rule Approver approves them, the
 * screen's own refusals, the server's refusal of a forged request, and who may propose.
 *
 * Re-runnable on one stack: every run proposes a section code and a state code of its own.
 */
const RULE_ADMIN = 'su.hq@fci-demo.test';
const RULE_APPROVER = 'rule.approver@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const ruleApprover = { role: 'RULE_APPROVER', office: 'HQ001' };
const STAMP = Date.now();
/** Letters from the run's time stamp: section and state codes allow no digits there. */
const letters = (n: number, width: number) =>
  Array.from({ length: width }, (_, i) => String.fromCharCode(65 + (Math.floor(n / 26 ** i) % 26))).join('');
const SECTION = `9${String(STAMP % 100).padStart(2, '0')}${letters(Math.floor(STAMP / 100), 5)}`;
const STATE = `E${letters(Math.floor(STAMP / 1000), 7)}`;
const RUN = `E2E ${STAMP}`;

/** The M-18 card titled {@code title} (the rule register above has a pager of its own). */
function card(page: import('@playwright/test').Page, title: string) {
  return page.locator('.card').filter({ has: page.getByRole('heading', { name: t('en', title) }) });
}

test.describe.serial('M-18 income-tax sections and PT states', () => {
  test(
    'SRS-7-20: a new section with its limit is approved by a different Rule Approver',
    { tag: ['@SRS-7-20', '@SRS-7-2', '@BR-TAX-002', '@SRS-G-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-18');
      await page.getByRole('button', { name: t('en', 'action.newSection') }).click();
      await page.getByLabel(t('en', 'm18s.code')).fill(SECTION);
      await page.getByLabel(t('en', 'm18s.description')).fill(`${RUN} section`);
      await page.getByLabel(t('en', 'm18s.limit')).fill('150000');
      await page.getByLabel(t('en', 'm18s.regime')).selectOption('BOTH');
      await page.getByLabel(t('en', 'common.reason')).fill(`${RUN} notified`);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('list', { name: t('en', 'm18s.pendingTitle') })).toContainText(SECTION);

      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
      await decide(page, new RegExp(SECTION), 'APPROVE');

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-18');
      const table = page.getByRole('table', { name: t('en', 'm18s.title') });
      await card(page, 'm18s.title').getByLabel(t('en', 'page.size')).selectOption('100');
      // The demo seed (V039_9) fills more than a page; the run's 9xx code sorts last.
      const last = card(page, 'm18s.title').getByRole('button', { name: t('en', 'page.last') });
      const row = table.locator('tr', { hasText: SECTION });
      // The pager settles after the size change: retry until the row shows, moving to the last
      // page when it is not on this one (found flaky 2026-10-26: one check raced the reload).
      await expect(async () => {
        if ((await row.count()) === 0 && (await last.isEnabled())) await last.click();
        await expect(row).toHaveCount(1, { timeout: 1000 });
      }).toPass({ timeout: 15000 });
      await expect(row).toContainText('₹1,50,000.00');
      await expect(row).toContainText(t('en', 'm18s.regime.BOTH'));
      await expect(row.getByRole('button', { name: t('en', 'm18s.newVersionOf', [SECTION]) })).toBeEnabled();
    },
  );

  test(
    'SRS-7-4: a new PT state carries the PT.SLAB rule once approved',
    { tag: ['@SRS-7-4', '@BR-TAX-006', '@SRS-G-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-18');
      await page.getByRole('button', { name: t('en', 'action.newState') }).click();
      await page.getByLabel(t('en', 'm18p.code')).fill(STATE);
      await page.getByLabel(t('en', 'm18p.name')).fill(`${RUN} state`);
      await page.getByLabel(t('en', 'm18p.registration')).fill('PTR-E2E-0001');
      await page.getByLabel(t('en', 'm18p.periodicity')).fill('MONTHLY');
      await page.getByLabel(t('en', 'common.reason')).fill(`${RUN} notified`);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('list', { name: t('en', 'm18p.pendingTitle') })).toContainText(STATE);

      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
      await decide(page, new RegExp(STATE), 'APPROVE');

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-18');
      await card(page, 'm18p.title').getByLabel(t('en', 'page.size')).selectOption('100');
      const row = page.getByRole('table', { name: t('en', 'm18p.title') }).locator('tr', { hasText: STATE });
      await expect(row).toContainText('PT.SLAB');
      await expect(row).toContainText(t('en', 'm18p.yes'));
    },
  );

  test(
    'SRS-7-26: the form names a bad section code and limit; the server refuses a version not after the one in force',
    { tag: ['@SRS-7-26', '@SRS-7-20', '@BR-TAX-002', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-18');
      await page.getByRole('button', { name: t('en', 'action.newSection') }).click();
      await page.getByLabel(t('en', 'm18s.code')).fill('C80');
      const problems = page.getByRole('list', { name: t('en', 'm18s.problems') });
      await expect(problems).toContainText(t('en', 'error.TAX-422-SECTION-CODE'));
      await page.getByLabel(t('en', 'm18s.limit')).fill('1.005');
      await expect(problems).toContainText(t('en', 'error.TAX-422-SECTION-LIMIT'));
      await expect(page.getByRole('button', { name: t('en', 'common.submitForApproval') })).toBeDisabled();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      expect(
        await apiStatus(page, '/v1/tax/sections', 'POST', {
          code: SECTION,
          description: 'Forged',
          limit: null,
          regime: 'OLD',
          effectiveFrom: businessToday(-1),
          reason: `${RUN} forged`,
        }),
      ).toBe(409);
    },
  );

  test(
    'SRS-22-21: the Rule Approver reads the masters in Hindi and proposes none; a Payroll Maker has no M-18',
    { tag: ['@SRS-22-21', '@SRS-7-26', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'hi', RULE_APPROVER, ruleApprover, 'M-18');
      await expect(page.getByRole('table', { name: t('hi', 'm18s.title') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('hi', 'action.newSection') })).toHaveCount(0);
      await expect(page.getByRole('button', { name: t('hi', 'action.newState') })).toHaveCount(0);
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      expect(await apiStatus(page, '/v1/tax/sections')).toBe(403);
      expect(await apiStatus(page, '/v1/tax/pt-states')).toBe(403);
    },
  );
});
