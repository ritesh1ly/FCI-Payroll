import { expect, test } from '@playwright/test';
import { apiProblem, apiStatus, errorAlert, openWorkspaceAs, signIn, t } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';

/**
 * OD-130 (SRS-G-25 records the refused call; the enforcement is the owner's). Needs a stack with the
 * enforcement on, which no other spec can share because they make forged calls on purpose:
 *
 *   powershell -File scripts\local\deploy-local.ps1 -SecurityOffence Y   (with the usual stack B options)
 *   E2E_SECURITY_OFFENCE=Y npx playwright test e2e/01-sign-in-and-access/security-offence.spec.ts --workers=1
 *
 * Run the rest of the suite on a stack without it.
 */
const ENFORCED = process.env.E2E_SECURITY_OFFENCE === 'Y';
const OFFENDER = 'finance.mumbai@fci-demo.test';
const ADMIN = 'sysadmin@fci-demo.test';
const ENDED = 'Your session was ended: an unauthorized access attempt was recorded.';
const JUSTIFICATION = 'Reviewed with the security officer';
const statusOf = (login: string) =>
  sqlRows(`SELECT user_status FROM app_user WHERE login_id = '${login}'`)[0]?.[0];

test.describe('An unauthorized call ends the session (OD-130, SRS-G-25)', () => {
  test.describe.configure({ mode: 'serial' });
  test.skip(!ENFORCED, 'needs a stack with fci.security.offence.enabled = Y and E2E_SECURITY_OFFENCE=Y');
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  async function forge(page: Parameters<typeof apiStatus>[0]): Promise<void> {
    await signIn(page, 'en', OFFENDER);
    expect(await apiStatus(page, '/v1/admin/users')).toBe(403);
  }

  test(
    'a forged call logs the user out with the reason on the sign-in page',
    { tag: ['@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await forge(page);
      // A forged call has no X-FCI-Session-Ended reader: the page's next own call (the session probe
      // of the reload) is the 401 with the reason, which takes it to sign-in, saying why.
      await page.reload();
      await expect(page).toHaveURL(/signedOut=offence/);
      await expect(page.getByRole('alert').filter({ hasText: ENDED })).toBeVisible();
    },
  );

  test(
    'the third offence suspends and sign-in is refused',
    { tag: ['@SRS-G-25', '@SRS-G-49', '@negative'] },
    async ({ page, context }) => {
      await context.clearCookies();
      await forge(page);
      await context.clearCookies();
      await forge(page);
      expect(statusOf(OFFENDER)).toBe('SUSPENDED');
      await context.clearCookies();
      await signIn(page, 'en', OFFENDER);
      await expect(errorAlert(page)).toHaveText(t('en', 'error.AUTH-403-SUSPENDED'));
      await expect(page).not.toHaveURL(/\/payroll/);
    },
  );

  test(
    'the Administrator sees why, and reinstatement needs a justification',
    { tag: ['@SRS-G-49', '@negative'] },
    async ({ page, context }) => {
      await context.clearCookies();
      await openWorkspaceAs(page, 'en', ADMIN);
      await page.goto('/payroll/?s=A-01');
      await page.getByLabel(t('en', 'a01.search')).fill(OFFENDER);
      const row = page.getByRole('row', { name: new RegExp(OFFENDER) });
      await row.getByRole('button', { name: 'Sign-ins' }).click();
      await expect(page.getByText('Unauthorized access attempts', { exact: true })).toBeVisible();
      await expect(page.getByRole('cell', { name: 'Account suspended' })).toBeVisible();

      // The API refuses a blank justification, spaces too, whatever the screen allows.
      const id = sqlRows(`SELECT user_id FROM app_user WHERE login_id = '${OFFENDER}'`)[0]?.[0];
      for (const note of ['', '   ']) {
        const refused = await apiProblem(page, `/v1/admin/users/${id}/status`, 'POST', {
          status: 'ACTIVE',
          note,
        });
        expect(refused).toEqual({ status: 400, errorCode: 'WF-400-REASON' });
      }
      expect(statusOf(OFFENDER)).toBe('SUSPENDED');

      await row.getByRole('button', { name: 'Reinstate' }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByRole('button', { name: 'Reinstate' })).toBeDisabled();
      await dialog.getByRole('textbox').fill(JUSTIFICATION);
      await dialog.getByRole('button', { name: 'Reinstate' }).click();
      await expect(page.getByText('User reinstated.')).toBeVisible();
      expect(statusOf(OFFENDER)).toBe('ACTIVE');

      // The justification and who gave it are on record.
      const reset = sqlRows(
        `SELECT acting_user, detail FROM security_event WHERE event_type = 'OFFENCE_COUNT_RESET' AND target_key = '${id}'`,
      );
      expect(reset).toEqual([[ADMIN, JUSTIFICATION]]);
    },
  );

  test(
    'a reinstated employee works normally and the count starts again',
    { tag: ['@SRS-G-49', '@positive'] },
    async ({ page, context }) => {
      await context.clearCookies();
      await openWorkspaceAs(page, 'en', OFFENDER);
      await expect(page.locator('.side')).toBeVisible();
      await page.reload();
      await expect(page).toHaveURL(/\/payroll/);
      await expect(page.locator('.side')).toBeVisible();

      // One offence after reinstatement logs out but does not suspend: the earlier three are closed.
      await context.clearCookies();
      await forge(page);
      await page.reload();
      await expect(page).toHaveURL(/signedOut=offence/);
      expect(statusOf(OFFENDER)).toBe('ACTIVE');
    },
  );
});
