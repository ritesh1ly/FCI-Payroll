import { expect, test } from '@playwright/test';
import { decide, errorAlert, sidebarCodes, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { expectToast, fillOrder } from '../support/payroll-support';

/**
 * I-03 Allowances & Reimbursements and I-16 Cafeteria (plan 10; SRS-11-9, SRS-11-11, SRS-11-13,
 * SRS-11-14, SRS-11-16): the Labour Dealing Hand of Depot Okhla enters a labour TA claim above the
 * entitlement with its bill (the payable capped, not rejected); the Payroll Maker of Delhi validates
 * and submits it; the Payroll Checker approves it on W-01. Re-runnable: each run claims a different
 * month (derived from the run's minute), so no claim overlaps an earlier run's; the demo norm of
 * seed V045_3_9 (labour TA 5,000 a month) is open-ended.
 */
const LDH = 'ldh.depot@fci-demo.test';
const MAKER = 'maker.delhi@fci-demo.test';
const CHECKER = 'ddo.delhi@fci-demo.test';
const ldh = { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' };
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };
const LABOUR = 'L10002';

/** A month of this run: April 2026 plus the minutes since 2026 (months apart per run). */
function runMonth(): { from: string; to: string } {
  const minutes = Math.floor((Date.now() - Date.UTC(2026, 0, 1)) / 60_000);
  const index = 2026 * 12 + 3 + (minutes % 6000);
  const y = Math.floor(index / 12);
  const m = String((index % 12) + 1).padStart(2, '0');
  return { from: `${y}-${m}-01`, to: `${y}-${m}-05` };
}

test.describe.serial('Allowances and reimbursements (I-03 / I-16, plan 10)', () => {
  test(
    'SRS-11-13: a labour claim above the entitlement is entered with its bill, capped, validated and approved',
    { tag: ['@SRS-11-13', '@SRS-11-11', '@SRS-11-14', '@SRS-11-9', '@positive'] },
    async ({ browser }) => {
      const period = runMonth();
      const page = await browser.newPage();
      await workAs(page, 'en', LDH, ldh, 'I-03');
      await page.getByRole('button', { name: t('en', 'i03.newClaim') }).click();
      const form = page
        .locator('.card')
        .filter({ has: page.getByRole('button', { name: t('en', 'i03.enter') }) });
      await form.getByLabel(t('en', 'i03.labourNumber')).fill(LABOUR);
      await form.getByLabel(t('en', 'i03.type')).selectOption('LABOUR_TA');
      await form.getByLabel(t('en', 'i03.from')).fill(period.from);
      await form.getByLabel(t('en', 'i03.to')).fill(period.to);
      await form.locator('#i03-amount').fill('6000');
      await fillOrder(page, form);
      await form.getByRole('button', { name: t('en', 'i03.addDocument') }).click();
      await expect(form.getByRole('list', { name: t('en', 'i03.documentsAdded') })).toContainText('.pdf');
      await form.getByRole('button', { name: t('en', 'i03.enter') }).click();
      await expectToast(page, t('en', 'i03.entered'));
      const result = (await form.getByRole('status').textContent()) ?? '';
      const id = /Claim (\d+)/.exec(result)?.[1] ?? '';
      expect(id).not.toBe('');
      expect(result).toContain('5,000');

      const desk = await browser.newPage();
      await workAs(desk, 'en', MAKER, maker, 'I-03');
      await desk.getByLabel(t('en', 'i03.eis')).first().fill(LABOUR);
      const register = desk.getByTestId('i03-register').getByRole('table', { name: t('en', 'i03.register') });
      await register.getByRole('button', { name: t('en', 'i03.claimNo', [id]) }).click();
      const panel = desk.getByRole('region', { name: t('en', 'i03.claimNo', [id]) });
      await expect(panel).toContainText(t('en', 'i03.cappedHint'));
      // US-GAP-209: the claim's tax impact by the M-07 Taxable of its pay code (demo code 915: not taxable).
      await expect(panel.getByTestId('i03-tax-impact')).toContainText(
        `(taxable: ${t('en', 'i03.taxTreatment.N')})`,
      );
      const reason = `E2E claim ${Date.now()}`;
      await panel.getByLabel(t('en', 'i03.reason')).fill(reason);
      await panel.getByRole('button', { name: t('en', 'i03.validate') }).click();
      await expectToast(desk, t('en', 'i03.sent'));

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(reason), 'APPROVE');
      await desk.reload();
      // The Maker's claim desk stays in the closed register after a reload (OD-121), as workAs opens it on entry.
      const closed = desk.getByTestId('i03-register');
      await closed.waitFor();
      if ((await closed.getAttribute('open')) === null)
        await desk.locator('[data-testid="i03-register"] > summary').click();
      await desk.getByLabel(t('en', 'i03.eis')).first().fill(LABOUR);
      await desk
        .getByRole('table', { name: t('en', 'i03.register') })
        .getByRole('button', { name: t('en', 'i03.claimNo', [id]) })
        .click();
      await expect(desk.getByRole('region', { name: t('en', 'i03.claimNo', [id]) })).toContainText(
        t('en', 'i03.sum', ['6,000', '5,000', '5,000']),
      );
    },
  );

  test(
    'SRS-11-16: I-16 lists the cafeteria selections read-only; the Labour Dealing Hand has no I-16',
    { tag: ['@SRS-11-16', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-03');
      expect(await sidebarCodes(page)).not.toContain('I-16');
      await workAs(page, 'en', MAKER, maker, 'I-16');
      await expect(page.getByText(t('en', 'i16.hint'))).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'action.newEntry') })).toHaveCount(0);
    },
  );
  test(
    'US-GAP-212: a claim type normed only by eligibility category is refused while the claimant has none (ALW-422-CATEGORY)',
    { tag: ['@US-GAP-212', '@SRS-11-9', '@negative'] },
    async ({ page }) => {
      const period = runMonth();
      await workAs(page, 'en', LDH, ldh, 'I-03');
      await page.getByRole('button', { name: t('en', 'i03.newClaim') }).click();
      const form = page
        .locator('.card')
        .filter({ has: page.getByRole('button', { name: t('en', 'i03.enter') }) });
      await form.getByLabel(t('en', 'i03.labourNumber')).fill(LABOUR);
      await form.getByLabel(t('en', 'i03.type')).selectOption('DEMO_CATEGORY_TA');
      await form.getByLabel(t('en', 'i03.from')).fill(period.from);
      await form.getByLabel(t('en', 'i03.to')).fill(period.to);
      await form.locator('#i03-amount').fill('500');
      await form.getByRole('button', { name: t('en', 'i03.enter') }).click();
      await expect(errorAlert(page).first()).toContainText(
        t('en', 'error.ALW-422-CATEGORY', ['DEMO_CATEGORY_TA']),
      );
    },
  );

  test(
    'US-GAP-216: a labour claim of a member not in service is refused on entry and on the Maker validation (ALW-422-INACTIVE)',
    { tag: ['@US-GAP-216', '@SRS-11-13', '@negative'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const member = 'L10003';
      const status = (s: string) =>
        sql(
          `UPDATE employee SET employment_status = '${s}' WHERE emp_num = '${member}' AND emp_lbr_flag = 'Y'`,
        );
      const period = runMonth();
      const page = await browser.newPage();
      await workAs(page, 'en', LDH, ldh, 'I-03');
      const enter = async (from: string, to: string) => {
        await page.getByRole('button', { name: t('en', 'i03.newClaim') }).click();
        const form = page
          .locator('.card')
          .filter({ has: page.getByRole('button', { name: t('en', 'i03.enter') }) });
        await form.getByLabel(t('en', 'i03.labourNumber')).fill(member);
        await form.getByLabel(t('en', 'i03.type')).selectOption('LABOUR_TA');
        await form.getByLabel(t('en', 'i03.from')).fill(from);
        await form.getByLabel(t('en', 'i03.to')).fill(to);
        await form.locator('#i03-amount').fill('1200');
        await fillOrder(page, form);
        await form.getByRole('button', { name: t('en', 'i03.addDocument') }).click();
        await form.getByRole('button', { name: t('en', 'i03.enter') }).click();
        return form;
      };
      // Entered while the member is in service: the claim waits for the Maker.
      const form = await enter(period.from, period.to);
      await expectToast(page, t('en', 'i03.entered'));
      const id = /Claim (\d+)/.exec((await form.getByRole('status').textContent()) ?? '')?.[1] ?? '';
      expect(id).not.toBe('');
      status('SEPARATED');
      try {
        // The member separated meanwhile: the Maker's validation is refused, naming the status.
        const desk = await browser.newPage();
        await workAs(desk, 'en', MAKER, maker, 'I-03');
        await desk.getByLabel(t('en', 'i03.eis')).first().fill(member);
        await desk
          .getByTestId('i03-register')
          .getByRole('table', { name: t('en', 'i03.register') })
          .getByRole('button', { name: t('en', 'i03.claimNo', [id]) })
          .click();
        const panel = desk.getByRole('region', { name: t('en', 'i03.claimNo', [id]) });
        await panel.getByLabel(t('en', 'i03.reason')).fill(`E2E inactive ${Date.now()}`);
        await panel.getByRole('button', { name: t('en', 'i03.validate') }).click();
        await expect(errorAlert(desk).first()).toContainText(
          t('en', 'error.ALW-422-INACTIVE', [member, 'SEPARATED']),
        );
        // And no new claim is entered for them.
        await page.reload();
        await enter(period.from.replace(/-01$/, '-10'), period.from.replace(/-01$/, '-12'));
        await expect(errorAlert(page).first()).toContainText(
          t('en', 'error.ALW-422-INACTIVE', [member, 'SEPARATED']),
        );
      } finally {
        status('IN_SERVICE');
      }
    },
  );
});
