import { expect, test } from '@playwright/test';
import { apiStatus, periodText, sessionPeriod, shiftPeriod, t, workAs } from '../support/app';

/**
 * The top-bar payroll period selector (M-10 payroll calendar): the session works on the current
 * period (the calendar month containing today) or any back-dated period of the approved calendar,
 * never a future one. C-02 follows the chosen period; the choice is kept by the session across a
 * reload; the server refuses future, unknown and malformed periods even when the UI is bypassed.
 */

const MAKER = 'maker.delhi@fci-demo.test';
const scope = { role: 'PAYROLL_MAKER', office: 'DH001' };

test.describe.serial('Payroll period selector (M-10 calendar)', () => {
  test(
    'M-10: the selector offers the current and back-dated periods with their state, no future one',
    { tag: ['@SRS-NF-7', '@BR-CYC-008', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, scope, 'C-02');
      const current = await sessionPeriod(page);
      const select = page.getByLabel(t('en', 'top.period'));
      await expect(select).toHaveValue(current);
      await expect(select.locator('option:checked')).toContainText(`${periodText(current)}`);
      await expect(select.locator('option:checked')).toContainText(t('en', 'period.current'));
      const values = await select
        .locator('option')
        .evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value));
      // The months before the current one (e.g. JUL, AUG, SEP for OCT) and older back-dated ones.
      for (const back of [1, 2, 3, 6]) expect(values).toContain(shiftPeriod(current, -back));
      expect(values).not.toContain(shiftPeriod(current, 1));
      expect(values.every((v) => v <= current)).toBe(true);
      // Newest first.
      expect([...values].sort().reverse()).toEqual(values);
      // A period without an initiated cycle says so.
      await expect(
        select.locator('option', { hasText: t('en', 'period.notStarted') }).first(),
      ).toBeAttached();
    },
  );

  test(
    'M-10: choosing a back-dated period moves C-02 and survives a reload; back to current',
    { tag: ['@SRS-NF-7', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, scope, 'C-02');
      const current = await sessionPeriod(page);
      const backDated = shiftPeriod(current, -2);
      const select = page.getByLabel(t('en', 'top.period'));
      await select.selectOption(backDated);
      await expect(page.getByRole('status')).toContainText(
        t('en', 'period.switched', [periodText(backDated)]),
      );
      await expect(select).toHaveValue(backDated);
      expect(await sessionPeriod(page)).toBe(backDated);
      // C-02 shows the cycle of the chosen period (or offers to initiate it), in its closed section.
      await page.locator('#c02-cycle summary').click();
      await expect(
        page.getByRole('heading', { level: 3, name: new RegExp(`^Payroll cycle ${periodText(backDated)} `) }),
      ).toBeVisible();

      await page.reload();
      await expect(page.getByLabel(t('en', 'top.period'))).toHaveValue(backDated);

      await page.getByLabel(t('en', 'top.period')).selectOption(current);
      await expect(page.getByLabel(t('en', 'top.period'))).toHaveValue(current);
      expect(await sessionPeriod(page)).toBe(current);
    },
  );

  test(
    'M-10: a future, unknown or malformed period is refused by the server',
    { tag: ['@SRS-NF-7', '@SRS-EH-3', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, scope, 'C-02');
      const current = await sessionPeriod(page);
      expect(await apiStatus(page, '/auth/period', 'PUT', { period: shiftPeriod(current, 1) })).toBe(422);
      expect(await apiStatus(page, '/auth/period', 'PUT', { period: '2019-01' })).toBe(422);
      expect(await apiStatus(page, '/auth/period', 'PUT', { period: '2026-13' })).toBe(400);
      expect(await apiStatus(page, '/auth/period', 'PUT', { period: 'July' })).toBe(400);
      expect(await apiStatus(page, '/auth/period', 'PUT', {})).toBe(400);
      // Nothing changed.
      expect(await sessionPeriod(page)).toBe(current);
    },
  );

  test(
    'the period is per session: another user still works on the current period',
    { tag: ['@SRS-NF-6', '@edge'] },
    async ({ browser }) => {
      const maker = await browser.newPage();
      await workAs(maker, 'en', MAKER, scope, 'C-02');
      const current = await sessionPeriod(maker);
      await maker.getByLabel(t('en', 'top.period')).selectOption(shiftPeriod(current, -1));
      await expect(maker.getByLabel(t('en', 'top.period'))).toHaveValue(shiftPeriod(current, -1));

      const checker = await browser.newPage();
      await workAs(
        checker,
        'en',
        'ddo.delhi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'DH001' },
        'C-02',
      );
      expect(await sessionPeriod(checker)).toBe(current);
      await expect(checker.getByLabel(t('en', 'top.period'))).toHaveValue(current);

      await maker.getByLabel(t('en', 'top.period')).selectOption(current);
      await maker.close();
      await checker.close();
    },
  );

  test(
    'the selector is labelled in Hindi too',
    { tag: ['@SRS-G-40', '@SC-01-0038', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'hi', MAKER, scope, 'C-02');
      const current = await sessionPeriod(page);
      await expect(page.getByLabel(t('hi', 'top.period'))).toHaveValue(current);
      await expect(page.getByLabel(t('hi', 'top.period')).locator('option:checked')).toContainText(
        t('hi', 'period.current'),
      );
    },
  );
});
