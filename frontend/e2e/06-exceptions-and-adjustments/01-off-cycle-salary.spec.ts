import { expect, type Page, test } from '@playwright/test';
import {
  cancelLeftovers,
  cancelWithReason,
  currentCycle,
  drawnOrderCard,
  expectToast,
  fillDrawnOrder,
  invoiceRow,
  monthLabel,
  monthsBefore,
  row,
  auditTrail,
  viewRecord,
} from '../support/payroll-support';
import { TEST_CHECKER, TEST_MAKER, testChecker, testEis, testMaker } from '../support/cycle-office';
import { apiStatus, decide, sidebarCodes, t, workAs } from '../support/app';

/**
 * I-20 Off-Cycle / Exceptional Salary (SRS-G-10, SRS-G-16, SRS-G-37) through the UI: the Maker
 * records a case (the amount is computed, never typed), attaches the approval note, submits; the
 * Payroll Checker approves in W-01; the month is then excluded from the regular run and a second
 * case for it is blocked by the duplicate-payment check. Every case is cancelled at the end, so the
 * spec re-runs on the same stack.
 */
// OD-141: in the cycle test office DT001, whose current period 05-payroll-processing/03-payroll-cycle
// locks and whose earlier months stay open (DH001's are locked demo months, V082_21).
const MAKER = TEST_MAKER;
const CHECKER = TEST_CHECKER;
const maker = testMaker;
const checker = testChecker;
const MEMBER = testEis('106620');

async function newCase(page: Page, eis: string, caseType: string, yymm: string, reference: string) {
  await page.getByRole('button', { name: t('en', 'i20.raise') }).click();
  await page.getByLabel(t('en', 'gen.eis')).fill(eis);
  await page.getByLabel(t('en', 'i20.caseType')).selectOption(caseType);
  await page.getByLabel(t('en', 'i20.salaryMonth')).selectOption(yymm);
  await page.getByLabel(t('en', 'i20.approvalReference')).fill(reference);
  await page.getByRole('button', { name: t('en', 'i20.capture') }).click();
}

/** The case reference from the capture toast ("Case OFC-… recorded; …"). */
async function capturedReference(page: Page): Promise<string> {
  const text = await expectToast(page, /OFC-\d{8}/);
  return /OFC-\d{8}/.exec(text)?.[0] ?? '';
}

test.describe.serial('Off-cycle / exceptional salary (I-20)', () => {
  const stamp = Date.now();
  let month = '';
  let first = '';
  let second = '';

  test(
    'the Maker records a case with a computed amount; it cannot be submitted without its order',
    {
      tag: [
        '@SRS-G-10',
        '@SRS-G-16',
        '@SC-01-0010',
        '@SC-01-0016',
        '@SC-01-0077',
        '@SC-01-0080',
        '@SC-01-0083',
        '@SC-01-0085',
        '@SC-01-0087',
        '@positive',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-20');
      await cancelLeftovers(page, '/v1/off-cycle', MEMBER, [
        'DRAFT',
        'BLOCKED',
        'RETURNED',
        'REJECTED',
        'APPROVED',
      ]);
      await page.reload();
      month = monthsBefore((await currentCycle(page)).yymm, 2);
      await newCase(page, MEMBER, 'LATE_JOINER', month, `HQ/PAY/E2E/${stamp}`);
      first = await capturedReference(page);
      const r = row(page, first);
      await expect(r).toContainText(t('en', 'i20.status.DRAFT'));
      await expect(r).toContainText(t('en', 'i20.dupPassed'));
      // Not yet invoiced; the cancel action lives in the invoice table.
      await expect(invoiceRow(page, first)).toContainText(t('en', 'inv.NOT_RAISED'));
      await expect(r).toContainText(monthLabel(month));
      // The amount is computed by the regular rules: a positive figure, no input for it anywhere.
      await expect(r.locator('td.num')).toHaveText(/[1-9]/);
      // The drawn row carries View / Edit; the submission sits in the case they open.
      const view = await viewRecord(page, first);
      const submit = view.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await expect(submit).toBeDisabled();
      await expect(submit).toHaveAttribute('title', t('en', 'i20.block.order'));
      await view.getByRole('button', { name: t('en', 'common.close') }).click();
    },
  );

  test(
    'with its approval note attached the case goes to the Payroll Checker',
    {
      tag: ['@SRS-G-30', '@SRS-G-4', '@SC-01-0004', '@SC-01-0028', '@SC-01-0075', '@SC-01-0086', '@positive'],
    },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-20');
      const r = row(page, first);
      await r.locator('td').first().click();
      const card = drawnOrderCard(page);
      await expect(card).toContainText(t('en', 'ord.none'));
      await fillDrawnOrder(page);
      await expect(card).toContainText(t('en', 'wf.ord.stored', ['1']));
      // The stored order is listed with a View link that downloads it as an attachment.
      const view = card.getByRole('link', { name: t('en', 'wf.view'), exact: true });
      await expect(view).toHaveCount(1);
      const file = await page.request.get(String(await view.getAttribute('href')));
      expect(file.status()).toBe(200);
      expect(file.headers()['content-disposition']).toMatch(/^attachment/);
      const opened = await viewRecord(page, first);
      await opened.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expectToast(page, t('en', 'i20.submitted', [first]));
      await expect(r).toContainText(t('en', 'i20.status.PENDING_CHECKER'));
      // Neither cancelled nor resubmitted while the Checker decides.
      await expect(
        invoiceRow(page, first).getByRole('button', { name: t('en', 'wf.inv.cancel') }),
      ).toBeDisabled();
      await expect(r.getByRole('button', { name: `${t('en', 'common.edit')} ${first}` })).toBeDisabled();
    },
  );

  test(
    'the Checker approves in W-01; the month is excluded from the regular run',
    {
      tag: ['@SRS-G-10', '@SRS-G-1', '@SC-01-0001', '@SC-01-0075', '@SC-01-0084', '@SC-01-0089', '@positive'],
    },
    async ({ page }) => {
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(first), 'APPROVE');
      await workAs(page, 'en', MAKER, maker, 'I-20');
      const r = row(page, first);
      await expect(r).toContainText(t('en', 'i20.status.APPROVED'));
      await expect(r).toContainText(t('en', 'i20.excluded', [monthLabel(month)]));
    },
  );

  test(
    'SRS-G-2: the case, its duplicate check and its approval are in the audit trail',
    { tag: ['@SRS-G-2', '@SRS-G-10', '@SC-01-0002', '@SC-01-0079', '@positive'] },
    async ({ browser }) => {
      const rows = await auditTrail(browser, 'off_cycle_payment', first);
      expect(rows.length, `audit rows of ${first}`).toBeGreaterThan(0);
      // Every row names the case, the actor, the screen and the source address; a decided row
      // carries the approval it came from.
      for (const r of rows) {
        expect(r.entityKey).toBe(first);
        expect(r.changedBy).toMatch(/@fci-demo\.test$/);
        expect(r.moduleCode).toBe('I-20');
        expect(r.sourceIp, 'IP address recorded').toBeTruthy();
      }
      expect(
        rows.some((r) => r.approvalId !== null),
        'the Checker approval is linked',
      ).toBe(true);
      expect(new Set(rows.map((r) => r.changedBy)).size, 'Maker and Checker both recorded').toBeGreaterThan(
        1,
      );
      expect(
        rows.some((r) => r.newValue !== null && r.newValue !== r.oldValue),
        'old and new values',
      ).toBe(true);
    },
  );

  test(
    'SRS-G-10: a second case for the same month fails the duplicate check and is blocked for everyone',
    { tag: ['@SC-01-0010', '@SRS-G-10', '@SC-01-0076', '@SC-01-0088', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-20');
      await newCase(page, MEMBER, 'OTHER', month, `HQ/PAY/E2E/${stamp}/2`);
      second = await capturedReference(page);
      const r = row(page, second);
      await expect(r).toContainText(t('en', 'i20.status.BLOCKED'));
      await expect(r).toContainText(/Failed - another off-cycle case covers/);
      const view = await viewRecord(page, second);
      await expect(view.getByRole('button', { name: t('en', 'common.submitForApproval') })).toHaveAttribute(
        'title',
        t('en', 'i20.block.duplicate'),
      );
      await view.getByRole('button', { name: t('en', 'common.close') }).click();
    },
  );

  test(
    'SRS-G-37: both cases are cancelled with a reason, never deleted',
    { tag: ['@SRS-G-37', '@SRS-EH-12', '@SC-01-0035', '@SC-01-0078', '@SC-01-0091', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-20');
      for (const reference of [second, first]) {
        await cancelWithReason(page, reference, `E2E clean-up ${stamp}`);
        await expectToast(page, t('en', 'i20.cancelled', [reference]));
        await expect(row(page, reference)).toContainText(t('en', 'i20.status.CANCELLED'));
        await expect(row(page, reference)).toContainText(`E2E clean-up ${stamp}`);
      }
    },
  );

  test(
    'a month-end-locked salary month is refused with the message naming I-23',
    { tag: ['@SRS-G-33', '@SC-01-0031', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-20');
      const cycle = await currentCycle(page);
      test.skip(
        cycle.status !== 'LOCKED',
        'Needs the locked cycle of 05-payroll-processing/03-payroll-cycle (run the whole suite).',
      );
      await newCase(page, MEMBER, 'LATE_JOINER', cycle.yymm, `HQ/PAY/E2E/${stamp}/L`);
      await expect(page.locator('.fl-error[role="alert"]')).toContainText(
        t('en', 'error.OFC-409-LOCKED', [cycle.yymm]),
      );
    },
  );

  test(
    'an unknown member and a reference the database cannot hold are refused',
    { tag: ['@SRS-EH-3', '@SC-01-0081', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-20');
      await page.getByRole('button', { name: t('en', 'i20.raise') }).click();
      await page.getByLabel(t('en', 'gen.eis')).fill('999999');
      await page.getByLabel(t('en', 'i20.caseType')).selectOption('OTHER');
      await page.getByLabel(t('en', 'i20.salaryMonth')).selectOption(month);
      await page.getByLabel(t('en', 'i20.approvalReference')).fill('आदेश');
      await expect(page.getByRole('button', { name: t('en', 'i20.capture') })).toBeDisabled();
      await page.getByLabel(t('en', 'i20.approvalReference')).fill('HQ/PAY/1');
      await page.getByRole('button', { name: t('en', 'i20.capture') }).click();
      await expect(page.locator('.fl-error[role="alert"]')).toContainText(t('en', 'error.COM-404-NOT-FOUND'));
    },
  );

  test(
    'the Checker views cases but records none; the administrator reads I-20 (SRS 14.4) and raises nothing',
    { tag: ['@SRS-G-25', '@SC-01-0025', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', CHECKER, checker, 'I-20');
      // Drawn and disabled with the reason (OD-121): no raise, no edit, no submit, no cancel.
      const raise = page.getByRole('button', { name: t('en', 'i20.raise') });
      await expect(raise).toBeDisabled();
      await expect(raise).toHaveAttribute('title', t('en', 'reason.makerOnly'));
      await expect(
        row(page, first).getByRole('button', { name: `${t('en', 'common.edit')} ${first}` }),
      ).toBeDisabled();
      const view = await viewRecord(page, first);
      await expect(view.getByRole('button', { name: t('en', 'common.submitForApproval') })).toHaveAttribute(
        'title',
        t('en', 'reason.makerOnly'),
      );
      await view.getByRole('button', { name: t('en', 'common.close') }).click();
      // Cancelled above, so its row offers nothing; a live row is drawn disabled (the I-23 spec).
      await expect(invoiceRow(page, first).getByRole('button')).toHaveCount(0);
      await workAs(
        page,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'A-01',
      );
      // SRS 14.4 l.5472: "maker, checker, fin, admin - enter: maker; approve: checker" (OD-125).
      expect(await sidebarCodes(page)).toContain('I-20');
      expect(
        await apiStatus(page, '/v1/off-cycle', 'POST', {
          eis: MEMBER,
          caseType: 'OTHER',
          salaryYymm: month,
          approvalReference: `HQ/PAY/E2E/${stamp}/A`,
        }),
      ).toBe(403);
    },
  );

  test(
    'field rules: the case types, a salary month before the current one, no amount input, the invoice columns',
    {
      tag: [
        '@SRS-G-10',
        '@BR-AUDIT-010',
        '@SC-01-0082',
        '@SC-01-0083',
        '@SC-01-0085',
        '@SC-01-0090',
        '@SC-01-0091',
        '@edge',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-20');
      await page.getByRole('button', { name: t('en', 'i20.raise') }).click();
      // Case Type: late joiner, death in service, or another exceptional case (FRS 6.9.6).
      const types = await page
        .getByLabel(t('en', 'i20.caseType'))
        .locator('option')
        .evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value).filter(Boolean));
      expect(types).toEqual(['LATE_JOINER', 'DEATH_IN_SERVICE', 'OTHER']);
      // Salary Month may precede the current period; never a future one.
      const current = (await currentCycle(page)).yymm;
      const months = await page
        .getByLabel(t('en', 'i20.salaryMonth'))
        .locator('option')
        .evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value).filter(Boolean));
      expect(months).toContain(monthsBefore(current, 1));
      expect(months.every((m) => m <= current)).toBe(true);
      // Amount: computed, never typed.
      await expect(page.getByLabel(t('en', 'i20.amount'), { exact: true })).toHaveCount(0);
      // Invoice table: number, status, the date sent to APPS (BR-AUDIT-010).
      for (const header of ['wf.inv.number', 'wf.inv.status', 'wf.inv.sentDate']) {
        await expect(
          page.getByRole('columnheader', { name: new RegExp(t('en', header)) }).first(),
        ).toBeVisible();
      }
      await expect(
        page.getByRole('columnheader', { name: new RegExp(t('en', 'i20.col.processingMonth')) }).first(),
      ).toBeVisible();
    },
  );
});
