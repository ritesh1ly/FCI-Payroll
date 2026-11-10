import { expect, type Page, test } from '@playwright/test';
import { decide, otpFromLog, t, workAs } from '../support/app';
import { LOCK_OFFICE, NO_CYCLE_OFFICE } from '../support/cycle-office';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';
import { currentCycle, openCycleActions } from '../support/payroll-support';

/**
 * Month-end lock through the application, with no database fixture standing in for a gate source
 * (SRS-15-11, SRS-15-14, SRS-15-15, SRS-15-18, SRS-15-32, SRS-G-1, BR-CYC-005): the Maker initiates
 * and freezes, the Actual run computes and writes the Salary Control Register, the disbursement
 * register and the statutory status (FRS V0.5 6.11.4 Step 6), the Checker verifies and sees G4 and
 * G5 met on C-01, the Maker requests the lock and the Checker approves it on W-01 with the OTP: a
 * missing code and a wrong code lock nothing, the right code locks the month, audited with the
 * approval. Runs in its own test office DT002 (LOCK_OFFICE, OD-141) only, once per fresh deployment.
 */
const OFFICE: typeof LOCK_OFFICE | null = LOCK_OFFICE;
const OTP = 'CYCLE_FREEZE_LOCK';

interface CycleView {
  id: number | null;
  status: string | null;
  lockApprovalStatus: string | null;
  lockApprovalId: number | null;
  lockedBy: string | null;
}

async function cycle(page: Page): Promise<CycleView> {
  return page.evaluate(async () => {
    const me = (await (await fetch('/nginx/fci-payroll/auth/me', { credentials: 'same-origin' })).json()) as {
      period: string;
    };
    const r = await fetch(`/nginx/fci-payroll/v1/cycles/${me.period}`, { credentials: 'same-origin' });
    return (await r.json()) as CycleView;
  });
}

test.describe.serial('Month-end lock through the application (C-02, C-01, W-01)', () => {
  test.skip(OFFICE === null, NO_CYCLE_OFFICE);
  const office = OFFICE ?? { code: '', maker: '', checker: '' };
  const maker = { role: 'PAYROLL_MAKER', office: office.code };
  const checker = { role: 'PAYROLL_CHECKER', office: office.code };
  const reason = `E2E month-end lock ${Date.now()}`;

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    await workAs(page, 'en', office.maker, maker, 'C-02');
    test.skip(
      (await cycle(page)).status === 'LOCKED',
      'The month is already locked: redeploy for a fresh run.',
    );
    await page.close();
  });

  test(
    'the Maker initiates and freezes, the Actual run computes and the Checker verifies',
    { tag: ['@SRS-15-8', '@SRS-15-32', '@BR-CYC-008', '@positive'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', office.maker, maker, 'C-02');
      await openCycleActions(page);
      if ((await cycle(page)).status === null) {
        await page.getByRole('button', { name: t('en', 'cyc.action.initiate') }).click();
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.initiate'));
      }
      if ((await cycle(page)).status === 'INITIATED') {
        const since = Date.now();
        await page.getByRole('button', { name: t('en', 'cyc.action.freeze') }).click();
        await page.getByRole('textbox', { name: t('en', 'otp.code') }).fill(await otpFromLog(OTP, since));
        await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.freeze'));
      }
      if ((await cycle(page)).status === 'FROZEN') {
        await page.getByRole('button', { name: t('en', 'cyc.action.compute') }).click();
        await expect(page).toHaveURL(/s=C-13/);
        const job = `E2E lock computation ${Date.now()}`;
        await page.getByRole('tab', { name: t('en', 'c13.tab.submit') }).click();
        await page.locator('#job-type').selectOption('PAYROLL_COMPUTATION');
        await page.locator('#job-reason').fill(job);
        await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
        await page
          .getByRole('dialog')
          .getByRole('button', { name: t('en', 'c13.submit.confirm') })
          .click();
        await expect(page.getByRole('status')).toContainText(/Job \d+ submitted/);
        const ddo = await browser.newPage();
        await workAs(ddo, 'en', office.checker, checker, 'W-01');
        await decide(ddo, new RegExp(job), 'APPROVE');
        await expect
          .poll(async () => (await cycle(page)).status, { timeout: 120_000, intervals: [2_000] })
          .toBe('COMPUTED');
      }
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', office.checker, checker, 'C-02');
      await openCycleActions(ddo);
      if ((await cycle(ddo)).status === 'COMPUTED') {
        await ddo.getByRole('button', { name: t('en', 'cyc.action.verify') }).click();
        await expect(ddo.getByRole('status')).toContainText(t('en', 'cyc.done.verify'));
      }
      expect((await cycle(ddo)).status).toBe('VERIFIED');
    },
  );

  test(
    'SRS-15-18: G4 and G5 are met from the registers the run wrote; no gate holds the lock',
    { tag: ['@SRS-15-14', '@SRS-15-15', '@SRS-15-18', '@BR-CYC-005', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', office.checker, checker, 'C-01');
      await page.getByTestId('c01-decisions').locator('summary').click();
      const gateRow = (code: string) =>
        page.getByRole('row').filter({ has: page.getByRole('cell', { name: new RegExp(`^${code}\\b`) }) });
      for (const gate of ['G0', 'G1', 'G3', 'G4', 'G5']) {
        await expect(gateRow(gate), gate).toContainText(t('en', 'c01.met'));
        await expect(gateRow(gate), gate).not.toContainText(t('en', 'c01.unmet'));
      }
      await expect(page.getByText(t('en', 'c01.blocking', ['§']).split('§')[0] ?? '')).toHaveCount(0);
    },
  );

  test(
    'SRS-15-32: the lock waits for the Checker; a missing or wrong OTP locks nothing, the right one locks the month',
    { tag: ['@SRS-15-11', '@SRS-15-32', '@SRS-G-1', '@SRS-G-21', '@BR-CYC-005', '@negative'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      // The Maker requests the lock (its own OTP when the route asks for one).
      const page = await browser.newPage();
      await workAs(page, 'en', office.maker, maker, 'C-02');
      await openCycleActions(page);
      if ((await cycle(page)).lockApprovalStatus !== 'PENDING_APPROVAL') {
        await page.getByRole('button', { name: t('en', 'cyc.action.lock') }).click();
        await page.getByRole('dialog').getByRole('textbox').fill(reason);
        const since = Date.now();
        await page.getByRole('dialog').getByRole('button').last().click();
        const otp = page.getByRole('textbox', { name: t('en', 'otp.code') });
        // isVisible() does not wait (its timeout option is ignored): the OTP field appears only after the
        // server's 428, so wait for it for a while; a route without an OTP leaves it absent.
        const asked = await otp
          .waitFor({ state: 'visible', timeout: 10_000 })
          .then(() => true)
          .catch(() => false);
        if (asked) {
          await otp.fill(await otpFromLog(OTP, since));
          await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
        }
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.lock'));
      }
      const pending = await cycle(page);
      expect(pending.status, 'not locked before the Checker decides').toBe('VERIFIED');
      expect(pending.lockApprovalStatus).toBe('PENDING_APPROVAL');
      const approvalId = pending.lockApprovalId;
      expect(approvalId).not.toBeNull();
      // SRS-G-1: the Maker has no decision on the item.
      // Drawn and off with its reason while the request waits (OD-121): the Maker cannot request it twice.
      const again = page.getByRole('button', { name: t('en', 'cyc.action.lock') });
      await expect(again).toBeDisabled();
      await expect(again).toHaveAttribute('title', t('en', 'cyc.blocked.lockPending'));

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', office.checker, checker, 'W-01');
      // The cycle's own lock approval (its id, as decision-rules.spec.ts does): on a re-run the request
      // waiting may be an earlier attempt's, whose reason is not this run's.
      const row = ddo
        .getByRole('row', { name: /payroll_cycle_instance|C-02/ })
        .filter({ has: ddo.locator('td b', { hasText: new RegExp(`^${approvalId}$`) }) });
      await expect(row.first()).toBeVisible();
      await row
        .first()
        .getByRole('button', { name: t('en', 'wf.view') })
        .click();
      await ddo
        .getByRole('dialog')
        .first()
        .getByRole('button', { name: t('en', 'w01.APPROVE') })
        .click();
      const since = Date.now();
      await ddo
        .getByRole('dialog')
        .last()
        .getByRole('button', { name: t('en', 'w01.APPROVE') })
        .click();
      const code = ddo.getByRole('textbox', { name: t('en', 'otp.code') });
      await expect(code).toBeVisible();
      const right = await otpFromLog(OTP, since);

      // Missing code: the dialog does not send it; the month stays verified.
      await ddo.getByRole('button', { name: t('en', 'otp.verify') }).click();
      await expect(code).toBeVisible();
      expect(await code.evaluate((el) => (el as HTMLInputElement).validity.valueMissing)).toBe(true);
      expect((await cycle(page)).status).toBe('VERIFIED');

      // Wrong code: refused, said in the dialog; the month stays verified.
      const wrong = right.slice(0, 5) + (right.endsWith('0') ? '1' : '0');
      await code.fill(wrong);
      await ddo.getByRole('button', { name: t('en', 'otp.verify') }).click();
      await expect(ddo.getByRole('dialog').getByRole('alert')).toContainText(t('en', 'error.AUTH-400-OTP'));
      expect((await cycle(page)).status).toBe('VERIFIED');

      // The right code: approved and locked.
      await code.fill(right);
      await ddo.getByRole('button', { name: t('en', 'otp.verify') }).click();
      await expect(ddo.getByRole('status')).toContainText(t('en', 'status.APPROVED'));
      const locked = await cycle(page);
      expect(locked.status).toBe('LOCKED');
      expect(locked.lockedBy).toBe(office.checker);

      // R-AUD: the lock is in the audit trail with the Checker and the approval.
      const audit = sqlRows(
        `SELECT old_value, new_value, changed_by, approval_id FROM audit_event WHERE entity_name = 'payroll_cycle_instance' AND entity_key = '${locked.id}' AND field_name = 'cycle_status' AND new_value = 'LOCKED'`,
      );
      expect(audit).toHaveLength(1);
      expect(audit[0]).toEqual(['VERIFIED', 'LOCKED', office.checker, String(approvalId)]);
      const { yymm } = await currentCycle(page);
      expect(yymm).toMatch(/^\d{6}$/);
      // Forward only: a locked month offers no action.
      await page.reload();
      await openCycleActions(page);
      for (const action of ['initiate', 'freeze', 'compute', 'verify', 'lock']) {
        await expect(page.getByRole('button', { name: t('en', `cyc.action.${action}`) })).toHaveCount(0);
      }
    },
  );
});
