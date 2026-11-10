import { expect, test } from '@playwright/test';
import { apiStatus, t, workAs } from '../support/app';
import { TEST_MAKER, testEis, testMaker } from '../support/cycle-office';
import { apiAll, currentCycle, expectToast, monthsBefore } from '../support/payroll-support';

/**
 * A double-click on a form's Submit records one transaction, not two (review 2026-10-24, A6: the
 * button is disabled while the request is in flight, and the server's Idempotency-Key makes a
 * replay harmless; IdempotencyIT proves the key, this proves the screen). I-20 is the vehicle: the
 * case recorded is cancelled with a reason at the end, so the spec leaves nothing open.
 */
// OD-141: in the cycle test office DT001, whose earlier months stay open (DH001's are locked demo months, V082_21).
const MAKER = TEST_MAKER;
const maker = testMaker;
/** A test-office member no other spec records cases for in that month (the I-20 spec uses 906620). */
const MEMBER = testEis('107304');

test.describe('One click, one transaction', () => {
  test(
    'SRS-G-10: a double-clicked Capture records one off-cycle case, then it is cancelled with a reason',
    { tag: ['@SRS-G-10', '@SRS-G-37', '@SC-01-0083', '@edge'] },
    async ({ page }) => {
      const reference = `HQ/PAY/E2E/DBL/${Date.now()}`;
      await workAs(page, 'en', MAKER, maker, 'I-20');
      const month = monthsBefore((await currentCycle(page)).yymm, 2);
      await page.getByRole('button', { name: t('en', 'i20.raise') }).click();
      await page.getByLabel(t('en', 'gen.eis')).fill(MEMBER);
      await page.getByLabel(t('en', 'i20.caseType')).selectOption('OTHER');
      await page.getByLabel(t('en', 'i20.salaryMonth')).selectOption(month);
      await page.getByLabel(t('en', 'i20.approvalReference')).fill(reference);
      await page.getByRole('button', { name: t('en', 'i20.capture') }).dblclick();
      await expectToast(page, /OFC-\d{8}/);

      await page.reload();
      await expect(page.getByRole('row', { name: new RegExp(reference) })).toHaveCount(1);
      const cases = await apiAll<{ id: number; approvalReference: string }>(page, '/v1/off-cycle');
      const mine = cases.filter((c) => c.approvalReference === reference);
      expect(mine).toHaveLength(1);

      // Closed with a reason, never deleted (SRS-G-37).
      expect(
        await apiStatus(page, `/v1/off-cycle/${mine[0]?.id}/cancel`, 'POST', {
          reason: 'E2E: double-click check done',
        }),
      ).toBe(200);
      await page.reload();
      await expect(page.getByRole('row', { name: new RegExp(reference) })).toContainText(
        t('en', 'i20.status.CANCELLED'),
      );
    },
  );
});
