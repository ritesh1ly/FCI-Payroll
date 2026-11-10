import { expect, test } from '@playwright/test';
import { apiStatus, businessToday, decide, t, workAs } from '../support/app';
import { apiJson, expectToast } from '../support/payroll-support';

/**
 * Income-tax declarations through the UI (docs/plan/08-income-tax.md phase 08c; SRS-7-1, SRS-7-16,
 * SRS-7-24, SRS-17-2; BR-TAX-001, BR-TAX-002, BR-TAX-014): the member declares on E-01 with a proof,
 * sees the line capped at its limit; the tax desk's Payroll Maker verifies it on I-07; a different
 * Payroll Checker approves it in the Approvals Inbox; the regime is then frozen for the year.
 *
 * Demo data V039_9 / V039_9_1: the current year's window, the section master and tax.history@ (EIS 870005, DH001).
 * Re-runnable: the member's latest round is always one a new round may replace, and the regime,
 * once frozen by an earlier run, stays OLD.
 */
const MEMBER = 'tax.history@fci-demo.test';
const MAKER = 'tax.maker.delhi@fci-demo.test';
const CHECKER = 'tax.checker.delhi@fci-demo.test';
const member = { role: 'EMPLOYEE_ESEVA' };
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };
const EIS = '870005';

interface Round {
  id: number;
  number: number;
  status: string;
}

test.describe.serial('I-07 / E-01 income-tax declarations', () => {
  let round: Round;

  // SC-08-0038 (scenario of a module after 07: cited, not tagged)
  test(
    'SRS-7-16: the member declares with a proof and sees the line capped at its section limit',
    { tag: ['@SRS-7-16', '@SRS-7-24', '@SRS-17-2', '@BR-TAX-002', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MEMBER, member, 'E-01');
      await expect(page.getByText(t('en', 'e01t.windowOpen'))).toBeVisible();
      await page.getByRole('button', { name: t('en', 'action.declare') }).click();
      const old = page.getByRole('radio', { name: t('en', 'e01t.regime.OLD') });
      if (await old.isEnabled()) await old.check();
      await page.getByRole('button', { name: t('en', 'e01t.addLine') }).click();
      await page.getByLabel(t('en', 'e01t.sectionOf', [1])).selectOption('80C');
      await page.getByLabel(t('en', 'e01t.declaredOf', [1])).fill('160000');
      await expect(
        page
          .getByRole('table', { name: t('en', 'e01t.lines') })
          .getByText(t('en', 'e01t.cappedAt', ['₹1,50,000.00'])),
      ).toBeVisible();
      await page.getByLabel(t('en', 'e01t.proofOf', [1])).setInputFiles({
        name: 'lic-premium.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7 e2e proof'),
      });
      await page.getByLabel(t('en', 'e01t.proofDateOf', [1])).fill(businessToday(-1));
      await page.getByLabel(t('en', 'e01t.proofIssuerOf', [1])).fill('LIC Delhi');
      await page.getByRole('button', { name: t('en', 'common.submit'), exact: true }).click();
      // The toast first: the round exists once it shows.
      await expectToast(page, /Round \d+ sent to the Payroll Maker/);
      const mine = await apiJson<{ rounds: Round[] }>(page, '/v1/tax/declarations/mine');
      round = mine.rounds[0] as Round;
      expect(round.status).toBe('SUBMITTED');
      // A member never reads the I-07 register.
      expect(await apiStatus(page, '/v1/tax/declarations')).toBe(403);
    },
  );

  test(
    'SRS-7-24: the Payroll Maker verifies up to the allowed amount and a different Checker approves',
    { tag: ['@SRS-7-24', '@BR-TAX-014', '@BR-TAX-001', '@SRS-G-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-07');
      await page.getByRole('button', { name: t('en', 'i07.openOf', [EIS, round.number]) }).click();
      const amount = page.getByLabel(t('en', 'i07.verifiedOf', ['80C']));
      await expect(amount).toHaveValue('150000');
      await amount.fill('150000.01');
      await expect(page.getByRole('button', { name: t('en', 'action.verifyDeclaration') })).toBeDisabled();
      await amount.fill('140000');
      await page.getByRole('button', { name: t('en', 'action.verifyDeclaration') }).click();
      await expectToast(page, t('en', 'i07.verifiedSent', [EIS]));

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`round ${round.number}`), 'APPROVE');

      await workAs(page, 'en', MEMBER, member, 'E-01');
      const rounds = page.getByRole('list', { name: t('en', 'e01t.rounds') });
      await expect(rounds.locator(`[data-reference="round-${round.number}"]`)).toContainText(
        t('en', 'e01t.status.APPROVED'),
      );
      await page.getByRole('button', { name: t('en', 'action.declare') }).click();
      await expect(page.getByRole('radio', { name: t('en', 'e01t.regime.OLD') })).toBeDisabled();
      await expect(page.getByRole('radio', { name: t('en', 'e01t.regime.OLD') })).toBeChecked();
    },
  );

  test(
    'SRS-22-21: another office reads the round as missing; the Checker reads I-07 in Hindi with no Maker action',
    { tag: ['@SRS-22-21', '@BR-TAX-001', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.kolkata@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'KL001' },
        'I-07',
      );
      expect(await apiStatus(page, `/v1/tax/declarations/${round.id}`)).toBe(404);
      expect(await apiStatus(page, `/v1/tax/declarations/${round.id}/verify`, 'POST', { lines: [] })).toBe(
        404,
      );
      await workAs(page, 'hi', CHECKER, checker, 'I-07');
      await expect(page.getByRole('heading', { level: 3, name: t('hi', 'screen.I-07') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('hi', 'action.verifyDeclaration') })).toHaveCount(0);
    },
  );

  test(
    'SRS-7-24 (US-08-03 AC4): a round the Payroll Maker returns reaches the member with its reason',
    { tag: ['@SRS-7-24', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MEMBER, member, 'E-01');
      await page.getByRole('button', { name: t('en', 'action.declare') }).click();
      await page.getByRole('button', { name: t('en', 'e01t.addLine') }).click();
      await page.getByLabel(t('en', 'e01t.sectionOf', [1])).selectOption('80C');
      await page.getByLabel(t('en', 'e01t.declaredOf', [1])).fill('50000');
      await page.getByLabel(t('en', 'e01t.proofOf', [1])).setInputFiles({
        name: 'ppf-statement.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7 e2e proof'),
      });
      await page.getByLabel(t('en', 'e01t.proofDateOf', [1])).fill(businessToday(-1));
      await page.getByLabel(t('en', 'e01t.proofIssuerOf', [1])).fill('Post Office Delhi');
      await page.getByRole('button', { name: t('en', 'common.submit'), exact: true }).click();
      await expectToast(page, /Round \d+ sent to the Payroll Maker/);
      const mine = await apiJson<{ rounds: Round[] }>(page, '/v1/tax/declarations/mine');
      const returned = mine.rounds[0] as Round;

      await workAs(page, 'en', MAKER, maker, 'I-07');
      await page.getByRole('button', { name: t('en', 'i07.openOf', [EIS, returned.number]) }).click();
      // SRS-7-1: the reset reason is its own field; the return reason never fills it.
      await page.getByLabel(t('en', 'i07.returnReason')).fill('Proof unreadable, please upload it again');
      const resetReason = page.getByLabel(t('en', 'i07.resetReason'));
      if ((await resetReason.count()) > 0) await expect(resetReason).toHaveValue('');
      await page.getByRole('button', { name: t('en', 'action.returnToMember') }).click();
      await expectToast(page, t('en', 'i07.returned', [EIS]));

      await workAs(page, 'en', MEMBER, member, 'E-01');
      const rounds = page.getByRole('list', { name: t('en', 'e01t.rounds') });
      await expect(rounds.locator(`[data-reference="round-${returned.number}"]`)).toContainText(
        t('en', 'e01t.returnedBecause', ['Proof unreadable, please upload it again']),
      );
    },
  );

  test(
    'SRS-7-1 (US-08-02 AC3): a frozen regime is reset only by a different Checker, and then the member may choose again',
    { tag: ['@SRS-7-1', '@BR-TAX-001', '@SRS-G-1', '@positive'] },
    async ({ page }) => {
      const why = `${Date.now()} wrong regime recorded at the first round`;
      await workAs(page, 'en', MAKER, maker, 'I-07');
      await page.getByLabel(t('en', 'i07.status')).selectOption('APPROVED');
      await page.getByRole('button', { name: t('en', 'i07.openOf', [EIS, round.number]) }).click();
      const reset = page.getByRole('button', { name: t('en', 'action.resetRegime') });
      await expect(reset).toBeDisabled();
      await page.getByLabel(t('en', 'i07.resetReason')).fill(why);
      await reset.click();
      await expectToast(page, t('en', 'i07.resetSent', [EIS]));

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(why), 'APPROVE');

      await workAs(page, 'en', MEMBER, member, 'E-01');
      await page.getByRole('button', { name: t('en', 'action.declare') }).click();
      await expect(page.getByRole('radio', { name: t('en', 'e01t.regime.OLD') })).toBeEnabled();
    },
  );
});
