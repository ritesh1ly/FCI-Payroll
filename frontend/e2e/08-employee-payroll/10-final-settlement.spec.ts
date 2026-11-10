import { expect, type Page, test } from '@playwright/test';
import { apiStatus, t, workAs } from '../support/app';

/**
 * O-05 Full & Final Settlement (plan 11) on the demo case FNF-DEMO-00001 (seed V047_3_9: a retired
 * Delhi HQ member, the establishment no-dues awaited). The unit's desks see the case with its stage
 * and awaited clearance; Establishment / HR records the no-dues (once: re-runs find it received);
 * the Maker's computation refuses on partial data (no pay on record for the demo member), never a
 * settlement on assumptions; the exclusion of a clearance is the Payroll Checker's, once the
 * settlement is submitted to them (SRS-10-14, US-GAP-219); Finance cannot release a settlement that
 * is not approved; a Payroll Maker cannot release by the API either.
 */
const CASE = 'FNF-DEMO-00001';
const office = { office: 'DH001' };

async function openCase(page: Page) {
  const row = page
    .getByRole('table', { name: t('en', 'screen.O-05') })
    .locator(`tr[data-reference="${CASE}"]`);
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: t('en', 'common.view') }).click();
  await expect(page.getByText(t('en', 'o05.recoveriesTitle', [CASE, 'Mohan Retiree']))).toBeVisible();
  // The functions the drawing leaves out stand behind the closed Case actions section.
  await page.getByTestId('o05-case-actions').locator('summary').click();
}

test.describe.serial('Full & final settlement (O-05)', () => {
  test(
    'SRS-10-11 / 10-27: the case shows its stage and clearances; Establishment / HR records the no-dues',
    { tag: ['@SRS-10-11', '@SRS-10-27', '@SRS-10-3', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'hr.delhi@fci-demo.test', { role: 'ESTABLISHMENT_HR', ...office }, 'O-05');
      await openCase(page);
      const checklist = page.getByTestId('o05-clearances');
      await expect(checklist).toContainText(t('en', 'o05.clearance.LOANS_ADVANCES'));
      await expect(page.getByText(t('en', 'o05.cpfInfo'))).toBeVisible();
      const record = page.getByRole('button', { name: t('en', 'action.o05.noDues') });
      if (await record.isVisible()) {
        await expect(record).toBeDisabled();
        await page.getByLabel(t('en', 'common.reason')).fill('Nil (E2E)');
        // SRS-10-27: a nil clearance is typed as 0, never assumed from an empty field.
        await expect(record).toBeDisabled();
        await page.getByLabel(t('en', 'o05.dues')).fill('0');
        await record.click();
        await expect(
          page.getByRole('status').filter({ hasText: t('en', 'o05.noDuesRecorded') }),
        ).toBeVisible();
      }
      const noDues = checklist
        .locator('.lst')
        .filter({ hasText: t('en', 'o05.clearance.ESTABLISHMENT_NO_DUES') });
      await expect(noDues).toContainText(t('en', 'o05.clearanceStatus.RECEIVED'));
    },
  );

  test(
    'SRS-10-28: the Maker computes; a source without data stops it - never settled on partial data',
    { tag: ['@SRS-10-28', '@SRS-10-13', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...office }, 'O-05');
      await openCase(page);
      const compute = page.getByRole('button', { name: t('en', 'action.o05.compute') });
      await expect(compute).toBeEnabled();
      await compute.click();
      await expect(page.locator('.fl-error[role="alert"]').first()).toContainText(
        'The settlement cannot be computed',
      );
      await expect(page.getByRole('button', { name: t('en', 'common.submitForApproval') })).toBeDisabled();
      const release = page.getByRole('button', { name: t('en', 'action.o05.release') });
      await expect(release).toBeDisabled();
      await expect(release).toHaveAttribute('title', t('en', 'reason.financeOnly'));
      const exclude = page.getByRole('button', { name: t('en', 'action.o05.exclude') });
      await expect(exclude).toBeDisabled();
      await expect(exclude).toHaveAttribute('title', t('en', 'reason.checkerOnly'));
    },
  );

  test(
    'SRS-10-14: the Checker records an exclusion only once the settlement is submitted to them',
    { tag: ['@SRS-10-14', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'ddo.delhi@fci-demo.test', { role: 'PAYROLL_CHECKER', ...office }, 'O-05');
      await openCase(page);
      const exclude = page.getByRole('button', { name: t('en', 'action.o05.exclude') });
      await expect(exclude).toBeDisabled();
      await expect(exclude).toHaveAttribute('title', t('en', 'o05.why.exclude'));
      await expect(page.getByRole('button', { name: t('en', 'action.o05.compute') })).toHaveAttribute(
        'title',
        t('en', 'o05.why.makerOnly'),
      );
    },
  );

  test(
    "SRS-10-19: release is Finance's, and only for an approved settlement",
    { tag: ['@SRS-10-19', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'finance.hq@fci-demo.test', { role: 'FINANCE', office: 'HQ001' }, 'O-05');
      await openCase(page);
      const release = page.getByRole('button', { name: t('en', 'action.o05.release') });
      await expect(release).toBeDisabled();
      await expect(release).toHaveAttribute('title', t('en', 'o05.why.release'));
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...office }, 'O-05');
      const id = await page.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/v1/settlement-cases?size=100', {
          credentials: 'same-origin',
        });
        const body = (await r.json()) as { items: { caseId: number; caseReference: string }[] };
        return body.items.find((c) => c.caseReference === 'FNF-DEMO-00001')?.caseId;
      });
      expect(await apiStatus(page, `/v1/settlement-cases/${id}/releases`, 'POST', {})).toBe(403);
    },
  );
});
