import { expect, test } from '@playwright/test';
import { t, workAs } from '../support/app';

/**
 * I-05 Increment & Pay Fixation (UI review 17-1): the default month is the business month in India, whatever the
 * browser's clock zone. The browser runs in UTC with its clock fixed either side of midnight IST at a month end,
 * when the UTC month is still the old one: the screen opens on India's month and asks the due list for it.
 */
test.use({ timezoneId: 'UTC' });

const office = { role: 'ESTABLISHMENT_HR', office: 'DH001' };

for (const [instant, month, note] of [
  ['2026-10-31T18:30:00Z', '2026-11', '00:00 IST on 1 November, 31 October in UTC'],
  ['2026-10-31T18:29:59Z', '2026-10', '23:59:59 IST on 31 October (one second before)'],
  ['2026-12-31T19:00:00Z', '2027-01', 'the year turns in India first'],
] as const) {
  test(
    `I-05: the default month is India's business month at ${note}`,
    { tag: ['@SRS-13-21', '@edge'] },
    async ({ page }) => {
      await page.clock.setFixedTime(new Date(instant));
      const asked = page.waitForRequest(
        (r) => r.url().includes('/v1/increments/due') && r.url().includes(`month=${month}`),
      );
      await workAs(page, 'en', 'hr.delhi@fci-demo.test', office, 'I-05');
      await expect(page.getByLabel(t('en', 'i05.month'))).toHaveValue(month);
      await asked;
    },
  );
}
