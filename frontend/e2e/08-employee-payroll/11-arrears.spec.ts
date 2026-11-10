import { expect, type Page, test } from '@playwright/test';
import { apiStatus, t, workAs } from '../support/app';

/**
 * I-08 Arrears & Retrospective Processing (plan 13 phase 13a) on the demo event ARR-DEMO-DA-01 (seed V048_2_9: a DA
 * arrear of Delhi HQ for the three months before this one). The Payroll Maker reviews the differentials and submits the
 * event with a mandatory remark (once: re-runs find it waiting for the Checker, never submitted twice); Finance sees the
 * event but cannot submit it, on the screen or by the API. Phase 13b: the demo back-dated leave event DEMO-RETRO-01 (seed
 * V048_9_9, RAISED) is computed again by the Maker - computed, or the reason it cannot be yet, never a guess.
 */
const EVENT = 'ARR-DEMO-DA-01';
const office = { office: 'DH001' };

/** The Arrear / Retro Event Register as Wireframes v6.1 draws it (OD-121): the card titled so. */
function register(page: Page) {
  return page
    .locator('.card')
    .filter({ has: page.getByRole('heading', { name: t('en', 'i08.registerTitle') }) });
}

async function openEvent(page: Page) {
  const row = register(page).locator(`tr[data-reference="${EVENT}"]`);
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: `${t('en', 'common.view')} ${EVENT}` }).click();
  await expect(page.getByText(t('en', 'i08.eventTitle', [EVENT]))).toBeVisible();
}

test.describe.serial('Arrears & retrospective processing (I-08)', () => {
  test(
    'SRS-8-14: the Maker reviews the differentials and submits the event with a remark for Approve & Merge',
    { tag: ['@SRS-8-14', '@SRS-8-21', '@SRS-21-9', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...office }, 'I-08');
      await openEvent(page);
      const details = page.getByRole('table', { name: t('en', 'i08.details') });
      await expect(details.locator('tbody tr')).toHaveCount(3);
      await expect(details).toContainText('111 - Dearness Allowance');
      const submit = page.getByRole('button', { name: t('en', 'action.i08.submit') });
      await expect(submit).toBeDisabled();
      await expect(submit).toHaveAttribute('title', t('en', 'i08.why.remark'));
      await page.getByLabel(t('en', 'common.reason')).fill('DA order (E2E)');
      await submit.click();
      await expect(
        page
          .getByRole('status')
          .filter({ hasText: t('en', 'i08.submitted') })
          .or(page.getByRole('alert').filter({ hasText: 'already waiting for approval' })),
      ).toBeVisible();
    },
  );

  // SC-13-0024 (scenario of a module after 07: cited, not tagged)
  test(
    'SRS-8-14 (E2E-08): an event waiting for the Checker is not merged - no posting month until it is approved',
    { tag: ['@SRS-8-14', '@E2E-08', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...office }, 'I-08');
      const event = await page.evaluate(async (reference) => {
        const r = await fetch('/nginx/fci-payroll/v1/arrear-events?size=100', { credentials: 'same-origin' });
        const body = (await r.json()) as {
          items: { reference: string; status: string; postedToYymm: string | null }[];
        };
        return body.items.find((e) => e.reference === reference);
      }, EVENT);
      expect(event).toBeDefined();
      expect(['COMPUTED', 'APPROVED', 'POSTED']).toContain(event?.status);
      if (event?.status === 'COMPUTED') {
        // Submitted by the test before, still waiting for the Payroll Checker: nothing merged yet.
        expect(event.postedToYymm).toBeNull();
        const row = register(page).locator(`tr[data-reference="${EVENT}"]`);
        await expect(row).toContainText(t('en', 'i08.status.COMPUTED'));
      } else {
        // Approved by a Checker in an earlier run: merged into an open cycle, never before.
        expect(event?.postedToYymm).toMatch(/^\d{6}$/);
      }
    },
  );

  test(
    "SRS-G-25: submitting is the Payroll Maker's; Finance is refused on the screen and by the API",
    { tag: ['@SRS-G-25', '@SRS-8-14', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'finance.hq@fci-demo.test', { role: 'FINANCE', office: 'HQ001' }, 'I-08');
      await openEvent(page);
      const submit = page.getByRole('button', { name: t('en', 'action.i08.submit') });
      await expect(submit).toBeDisabled();
      await expect(submit).toHaveAttribute('title', t('en', 'reason.makerOnly'));
      const id = await page.evaluate(async (reference) => {
        const r = await fetch('/nginx/fci-payroll/v1/arrear-events?size=100', { credentials: 'same-origin' });
        const body = (await r.json()) as { items: { id: number; reference: string }[] };
        return body.items.find((e) => e.reference === reference)?.id;
      }, EVENT);
      expect(id).toBeDefined();
      expect(await apiStatus(page, `/v1/arrear-events/${id}/submissions`, 'POST', { remark: 'x' })).toBe(403);
    },
  );

  test(
    'SRS-8-13: the Maker computes a back-dated single-member event again',
    { tag: ['@SRS-8-13', '@SRS-21-7', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...office }, 'I-08');
      const row = register(page).locator('tr[data-reference^="LEAVE DEMO-RETRO-01"]');
      await expect(row).toBeVisible();
      await expect(row).toContainText(t('en', 'i08.source', ['LEAVE']));
      await row.getByRole('button', { name: new RegExp(`^${t('en', 'common.view')} `) }).click();
      const again = page.getByRole('button', { name: t('en', 'action.i08.recompute') });
      await expect(again).toBeEnabled();
      await again.click();
      await expect(
        page
          .getByRole('status')
          .filter({ hasText: t('en', 'i08.computedAgain') })
          .or(page.getByText(/The member cannot be computed yet/)),
      ).toBeVisible();
    },
  );
});
