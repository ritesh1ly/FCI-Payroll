import { expect, type Page, test } from '@playwright/test';
import { t, workAs } from '../support/app';

/**
 * C-02 shows the pay the SRS states, on the locked demo run of V079_32 and the members of V080_80 (PayRuleMembersIT
 * proves those lines are what the engine computes): HRA stopped in quarters or on lease (SRS-1-3 l.3544, l.5410,
 * SRS-11-17 l.2754), paid with no record, Grade Pay in the DA base (l.5409), a doctor's NPA on Basic + Grade Pay entering
 * the DA base (l.5412), stagnation inside Basic with no second term (Q-17-STAG). The trace never prints "suppressed"
 * beside a paid amount. Amounts and boundaries are unit tests (PayRulesSourceAmountsTest); this is what the screen shows.
 *
 * Read-only. Named to run before 03-payroll-cycle: C-02 shows the demo run only while DH001's session period has no run.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };

/** The Employee-wise Result narrowed to one member; the first row is then the trace's member. */
async function openMember(page: Page, eis: string) {
  await page.getByLabel(t('en', 'wf.searchInList')).fill(eis);
  // The body also holds the drawn Totals line: the search narrows it to one member.
  const table = page.locator('table.t').first();
  await expect(table.getByText(t('en', 'c02.totals', ['1']), { exact: true })).toBeVisible();
  const rows = table.locator('tbody tr').filter({ hasText: eis });
  await expect(rows).toHaveCount(1);
  await expect(page.getByText(new RegExp(`EIS ${eis},`))).toBeVisible();
  return rows.first();
}

const shown = (page: Page, text: string) =>
  expect(page.getByText(text, { exact: true }).first()).toBeVisible();

test.describe('C-02 pay rules (REF-1, REF-2, REF-3)', () => {
  test.beforeEach(async ({ page }) => {
    await workAs(page, 'en', MAKER, maker, 'C-02');
  });

  test(
    'SRS-1-3: a member in quarters has no HRA; gross is Basic + DA',
    { tag: ['@SRS-1-3', '@positive'] },
    async ({ page }) => {
      const row = await openMember(page, '880701');
      await expect(row).toContainText('50,000');
      await expect(row).toContainText('75,000');
      await shown(page, t('en', 'c02.trace.accomSuppressed', [t('en', 'c02.accom.DEPARTMENTAL_QUARTERS')]));
      await shown(page, t('en', 'c02.trace.hraSuppressed'));
    },
  );

  test(
    'SRS-11-17: a lease flagged suppressed is not paid HRA',
    { tag: ['@SRS-11-17', '@SRS-1-3', '@positive'] },
    async ({ page }) => {
      await openMember(page, '880702');
      await shown(
        page,
        t('en', 'c02.trace.accomSuppressed', [
          t('en', 'c02.trace.accomPaid', [t('en', 'c02.accom.THIRD_PARTY'), '18,000']),
        ]),
      );
      await shown(page, t('en', 'c02.trace.hraSuppressed'));
    },
  );

  test(
    'SRS-1-3: with no record HRA stands at Basic x the city rate (no source stops it; PENDING REF-1)',
    { tag: ['@SRS-1-3', '@edge'] },
    async ({ page }) => {
      await openMember(page, '880703');
      await shown(page, t('en', 'c02.trace.accomNone'));
      await shown(page, t('en', 'c02.rupees', ['15,600']));
    },
  );

  test(
    'SRS-1-3: a third-party lease flagged not suppressed keeps HRA and says nothing of suppression',
    { tag: ['@SRS-1-3', '@edge'] },
    async ({ page }) => {
      await openMember(page, '880707');
      await shown(page, t('en', 'c02.trace.accomPaid', [t('en', 'c02.accom.THIRD_PARTY'), '16,000']));
      await shown(page, t('en', 'c02.rupees', ['13,500']));
      await expect(page.getByText(t('en', 'c02.trace.hraSuppressed'), { exact: true })).toHaveCount(0);
    },
  );

  test(
    'SRS l.5409: Grade Pay enters the DA base: (50,000 + 4,600) x 50 %; HRA stays on Basic',
    { tag: ['@SRS-1-2', '@positive'] },
    async ({ page }) => {
      await openMember(page, '880704');
      await shown(page, t('en', 'c02.trace.formula', ['27,300', '54,600', '50']));
      await shown(page, t('en', 'c02.rupees', ['15,000']));
    },
  );

  test(
    'SRS l.5412: a doctor: NPA 25 % x 54,600 = 13,650 enters the DA base: (54,600 + 13,650) x 50 %',
    { tag: ['@SRS-1-2', '@SRS-1-4', '@positive'] },
    async ({ page }) => {
      const row = await openMember(page, '880705');
      await expect(row).toContainText('1,12,775');
      await shown(page, t('en', 'c02.trace.formula', ['34,125', '68,250', '50']));
      await expect(page.getByText(`${t('en', 'c02.rupees', ['13,650'])}`).first()).toBeVisible();
    },
  );

  test(
    'SRS-13-16: a stagnation case: the increment is inside Basic, counted once in the DA base (Q-17-STAG)',
    { tag: ['@SRS-13-16', '@edge'] },
    async ({ page }) => {
      await openMember(page, '880706');
      await shown(page, t('en', 'c02.trace.stagYes'));
      await shown(page, t('en', 'c02.trace.formula', ['25,750', '51,500', '50']));
    },
  );

  test(
    'SRS-1-3: a locked result that paid HRA under a suppressing record stays, and the trace says so',
    { tag: ['@SRS-1-3', '@SRS-11-17', '@negative'] },
    async ({ page }) => {
      await openMember(page, '106620');
      await shown(page, t('en', 'c02.rupees', ['2,868']));
      await shown(
        page,
        t('en', 'c02.trace.accomConflict', [
          t('en', 'c02.trace.accomPaid', [t('en', 'c02.accom.THIRD_PARTY'), '18,500']),
        ]),
      );
      await expect(page.getByText(t('en', 'c02.trace.hraSuppressed'), { exact: true })).toHaveCount(0);
    },
  );

  test(
    'FRS 7.4: the drawn trace of 104882: self-lease, HRA ₹ 0',
    { tag: ['@SRS-1-3', '@positive'] },
    async ({ page }) => {
      await openMember(page, '104882');
      await shown(
        page,
        t('en', 'c02.trace.accomSuppressed', [
          t('en', 'c02.trace.accomPaid', [t('en', 'c02.accom.SELF_LEASE'), '28,000']),
        ]),
      );
      await shown(page, t('en', 'c02.trace.hraSuppressed'));
    },
  );
});

test('SRS-1-3: the suppressed trace reads in Hindi', { tag: ['@SRS-1-3', '@positive'] }, async ({ page }) => {
  await workAs(page, 'hi', MAKER, maker, 'C-02');
  await page.getByLabel(t('hi', 'wf.searchInList')).fill('880701');
  const table = page.locator('table.t').first();
  await expect(table.getByText(t('hi', 'c02.totals', ['1']), { exact: true })).toBeVisible();
  await expect(table.locator('tbody tr').filter({ hasText: '880701' })).toHaveCount(1);
  await expect(
    page
      .getByText(t('hi', 'c02.trace.accomSuppressed', [t('hi', 'c02.accom.DEPARTMENTAL_QUARTERS')]), {
        exact: true,
      })
      .first(),
  ).toBeVisible();
});
