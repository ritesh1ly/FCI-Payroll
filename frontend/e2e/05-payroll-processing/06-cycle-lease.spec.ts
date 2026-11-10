import { expect, type Page, test } from '@playwright/test';
import { decide, t, workAs } from '../support/app';
import { dbFixtureAvailable, lockPriorForFixture, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { currentCycle, openCycleActions } from '../support/payroll-support';

/**
 * C-02 cycle lease (plan 05 phase 05c; SRS-15-28, BR-CYC-015, SRS-G-21): while another action holds the
 * instance, C-02 names the holder and since when and the actions are disabled with that reason; the
 * Payroll Maker asks for the release of a stuck lease with a reason, the Payroll Checker approves it on
 * W-01 with the OTP CYCLE_UNFREEZE_STUCK_RELEASE, and the cycle is free again.
 * At the Chennai DO (CH001), whose cycle no other spec moves; the stuck run is a database fixture
 * (a lease row: no way to hang a real run on demand), so the test needs E2E_DB_*.
 */
const MAKER = 'maker.chennai@fci-demo.test';
const CHECKER = 'ddo.chennai@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'CH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'CH001' };
const HOLDER = 'e2e.stuck.run@fci-demo.test';

const instance = (yymm: string) =>
  `(SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '${yymm}' AND org_unit_id = (SELECT location_id FROM org_location WHERE location_code = 'CH001'))`;

/** The C-02 board of the session's office, from the API the screen reads. */
async function board(page: Page): Promise<{ lease: { heldBy: string } | null; status: string | null }> {
  return page.evaluate(async () => {
    const me = (await (await fetch('/nginx/fci-payroll/auth/me', { credentials: 'same-origin' })).json()) as {
      period: string;
    };
    const r = await fetch(`/nginx/fci-payroll/v1/cycles/${me.period}`, { credentials: 'same-origin' });
    return (await r.json()) as { lease: { heldBy: string } | null; status: string | null };
  });
}

test.describe.serial('Cycle lease: holder shown, actions disabled, force-release with OTP (C-02)', () => {
  test(
    'SRS-15-28: a stuck run holds the cycle; the maker asks for its release and the checker releases it with an OTP',
    {
      tag: ['@SRS-15-28', '@BR-CYC-015', '@SRS-G-21', '@SC-05-0107', '@E2E-03', '@positive'],
    },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      if ((await board(page)).status === null) {
        lockPriorForFixture((await currentCycle(page)).period, 'CH001');
        await page.getByRole('button', { name: t('en', 'cyc.action.initiate') }).click();
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.initiate'));
      }
      const { yymm } = await currentCycle(page);
      test.skip(
        (await board(page)).status !== 'INITIATED',
        'the Chennai cycle of the period moved past INITIATED: redeploy for a fresh run',
      );
      // The stuck run: its lease row, beating now (so it does not expire during the test). A
      // leftover of an earlier run of this spec is replaced; a lease is a transient token (DDL).
      sql(
        [
          `DELETE FROM cycle_lease WHERE cycle_instance_id = ${instance(yymm)}`,
          `INSERT INTO cycle_lease (cycle_instance_id, lease_action, held_by, acquired_at, heartbeat_at) VALUES (${instance(yymm)}, 'COMPUTE', '${HOLDER}', CURRENT_TIMESTAMP, DATEADD('MINUTE', 60, CURRENT_TIMESTAMP))`,
        ].join(';\n'),
      );
      await page.reload();

      // The holder and since when, and the freeze disabled naming them.
      const banner = page.getByTestId('cycle-lease');
      await expect(banner).toContainText(HOLDER);
      await expect(banner).toContainText(t('en', 'cyc.leaseAction.COMPUTE'));
      const freeze = page.getByRole('button', { name: t('en', 'cyc.action.freeze') });
      await expect(freeze).toBeDisabled();
      await expect(freeze).toHaveAttribute('title', new RegExp(HOLDER.replace(/\./g, '\\.')));

      // The request: a reason, no OTP for the maker.
      const reason = `E2E stuck run ${Date.now()}`;
      await page.getByRole('button', { name: t('en', 'cyc.action.leaseRelease') }).click();
      await page.getByRole('dialog').getByRole('textbox').fill(reason);
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.submit') })
        .click();
      await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.leaseRelease'));
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.leaseRelease') })).toBeDisabled();
      expect((await board(page)).lease?.heldBy).toBe(HOLDER);

      // The checker decides it on W-01 with the OTP of a stuck-run release.
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(reason), 'APPROVE', '', 'CYCLE_UNFREEZE_STUCK_RELEASE');

      await expect.poll(async () => (await board(page)).lease, { timeout: 20_000 }).toBeNull();
      await page.reload();
      await expect(page.getByTestId('cycle-lease')).toHaveCount(0);
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.freeze') })).toBeEnabled();
      const logged = sqlRows(
        `SELECT released_by, force_release_reason FROM cycle_lease_log WHERE cycle_instance_id = ${instance(yymm)} AND event_type = 'FORCE_RELEASED' AND force_release_reason = '${reason}'`,
      );
      expect(logged).toEqual([[CHECKER, reason]]);
    },
  );
});
