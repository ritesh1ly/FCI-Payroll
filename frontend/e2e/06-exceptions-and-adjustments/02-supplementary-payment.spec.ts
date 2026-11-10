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
  PDF,
  row,
  viewRecord,
} from '../support/payroll-support';
import { TEST_CHECKER, TEST_MAKER, testChecker, testEis, testMaker } from '../support/cycle-office';
import { apiMultipart, businessToday, pageHref, sidebarCodes, t, workAs } from '../support/app';

/**
 * I-23 Supplementary Payment (Post-Lock) (SRS-G-33, SRS-G-34, BR-CYC-010) through the UI. The only
 * route for an amount of a month-end-locked period: an open period is refused with the message
 * that it belongs in its regular run; a pending salary of the locked period is recorded with its
 * order in one step; the member was already paid by the regular run of record, so the duplicate
 * check blocks it and no role can submit it. Records are cancelled at the end (re-runnable).
 * The approval with OTP waits for the statutory-deduction rules (decision D-5; integration-tested).
 */
// OD-141: in the cycle test office DT001, whose current period 05-payroll-processing/03-payroll-cycle
// locks and whose earlier months stay open (DH001's are locked demo months, V082_21).
const MAKER = TEST_MAKER;
const CHECKER = TEST_CHECKER;
const maker = testMaker;
const checker = testChecker;
const MEMBER = testEis('106845');

async function openForm(page: Page) {
  await page.getByRole('button', { name: t('en', 'i23.new') }).click();
}

async function fillRequest(page: Page, eis: string, yymm: string, applied: string, sanctioned = '') {
  await page.getByLabel(t('en', 'gen.eis')).fill(eis);
  await page.getByLabel(t('en', 'i23.forPeriod')).selectOption(yymm);
  await page.getByLabel(t('en', 'i23.applicationDate')).fill(applied);
  await page.getByLabel(t('en', 'i23.sanctionDate')).fill(sanctioned);
  // The drawn order card holds the order of the next request (WF-078, SRS-G-16), behind this dialog.
  await fillDrawnOrder(page);
}

test.describe.serial('Supplementary payment after lock (I-23)', () => {
  const stamp = Date.now();
  const today = businessToday();
  let locked = '';
  let reference = '';

  test.beforeEach(async ({ page }) => {
    await workAs(page, 'en', MAKER, maker, 'I-23');
    const cycle = await currentCycle(page);
    locked = cycle.status === 'LOCKED' ? cycle.yymm : '';
  });

  test(
    'BR-CYC-010: an open period is refused, its amount belongs in the regular run',
    { tag: ['@BR-CYC-010', '@SRS-G-33', '@SC-01-0093', '@SC-01-0103', '@SC-05-0037', '@negative'] },
    async ({ page }) => {
      const open = monthsBefore((await currentCycle(page)).yymm, 2);
      await openForm(page);
      await fillRequest(page, MEMBER, open, today);
      await page.getByRole('button', { name: t('en', 'i23.record') }).click();
      await expect(page.locator('.fl-error[role="alert"]')).toContainText(
        t('en', 'error.SUP-409-NOT-LOCKED', [open]),
      );
    },
  );

  test(
    'chk_supp_sanction_after_appl: a same-day sanction is refused before anything is sent',
    { tag: ['@SRS-EH-3', '@edge'] },
    async ({ page }) => {
      await openForm(page);
      await fillRequest(page, MEMBER, monthsBefore((await currentCycle(page)).yymm, 1), today, today);
      await expect(page.getByText(t('en', 'error.SUP-422-SANCTION'))).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'i23.record') })).toBeDisabled();
    },
  );

  test(
    'SRS-G-34: a salary the locked run already paid is recorded as a duplicate and cannot be submitted',
    {
      tag: [
        '@SRS-G-34',
        '@SRS-G-33',
        '@SC-01-0094',
        '@SC-01-0109',
        '@SC-01-0100',
        '@SC-01-0102',
        '@SC-01-0104',
        '@SC-01-0107',
        '@SC-01-0108',
        '@SC-01-0029',
        '@negative',
      ],
    },
    async ({ page }) => {
      test.skip(
        locked === '',
        'Needs the locked cycle of 05-payroll-processing/03-payroll-cycle (run the whole suite).',
      );
      await cancelLeftovers(page, '/v1/supplementary', MEMBER, ['DRAFT', 'BLOCKED', 'RETURNED', 'REJECTED']);
      await page.reload();
      await openForm(page);
      // The screen offers the pending salary only; other types come from their own transactions.
      await expect(page.getByLabel(t('en', 'i23.paymentType')).locator('option')).toHaveCount(1);
      await fillRequest(page, MEMBER, locked, today);
      await page.getByRole('button', { name: t('en', 'i23.record') }).click();
      const toast = await expectToast(page, /SUP-\d{8}/);
      reference = /SUP-\d{8}/.exec(toast)?.[0] ?? '';
      const r = row(page, reference);
      await expect(r).toContainText(monthLabel(locked));
      await expect(r).toContainText(t('en', 'i23.status.BLOCKED'));
      await expect(r).toContainText(t('en', 'i23.dup.BLOCKED'));
      const view = await viewRecord(page, reference);
      await expect(view.getByRole('button', { name: t('en', 'common.submitForApproval') })).toHaveAttribute(
        'title',
        t('en', 'i23.block.duplicate'),
      );
      await view.getByRole('button', { name: t('en', 'common.close') }).click();
      await expect(invoiceRow(page, reference)).toContainText(t('en', 'inv.NOT_RAISED'));
      // The order is kept with the request and downloads as an attachment: a stored row offers no edit.
      await r.locator('td').first().click();
      const card = drawnOrderCard(page);
      const stored = card.locator('tbody tr');
      await expect(stored).toHaveCount(1);
      await expect(stored.locator('input, select, button')).toHaveCount(0);
      const link = stored.getByRole('link', { name: t('en', 'wf.view'), exact: true });
      const response = await page.request.get(String(await link.getAttribute('href')));
      expect(response.status()).toBe(200);
      expect(response.headers()['content-disposition']).toMatch(/^attachment/);
      await expect(
        page.locator('.tile').filter({ hasText: t('en', 'i23.tile.blockedDrawn') }),
      ).not.toContainText(/^0$/);
    },
  );

  test(
    'SRS-G-37: the blocked request is cancelled with a reason and stays on the list',
    { tag: ['@SRS-G-37', '@SC-01-0035', '@SC-01-0098', '@SC-01-0109', '@SC-01-0111', '@positive'] },
    async ({ page }) => {
      test.skip(reference === '', 'Nothing recorded (needs the locked cycle).');
      await cancelWithReason(page, reference, `E2E clean-up ${stamp}`);
      await expectToast(page, t('en', 'i23.cancelled', [reference]));
      await expect(row(page, reference)).toContainText(t('en', 'i23.status.CANCELLED'));
      // SRS-G-37: every entry of a payment screen shows its invoice state; a cancelled one is drawn as a
      // new version and offers no further cancel.
      await expect(invoiceRow(page, reference)).toContainText(t('en', 'wf.inv.cancelledVersion'));
      await expect(invoiceRow(page, reference).getByRole('button')).toHaveCount(0);
    },
  );

  test(
    'the Checker views requests but records none; the administrator reads I-23 (SRS 14.4) and raises nothing',
    { tag: ['@SRS-G-25', '@SC-01-0025', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', CHECKER, checker, 'I-23');
      // Drawn and disabled with the reason (OD-121).
      const raise = page.getByRole('button', { name: t('en', 'i23.new') });
      await expect(raise).toBeDisabled();
      await expect(raise).toHaveAttribute('title', t('en', 'reason.makerOnly'));
      // The Checker approves in W-01 (with OTP), never on this screen.
      await expect(page.getByRole('link', { name: t('en', 'i23.approveAct') })).toHaveAttribute(
        'href',
        pageHref('/payroll/?s=W-01'),
      );
      for (const cancel of await page.locator('tr[data-invoice] button').all()) {
        await expect(cancel).toBeDisabled();
      }
      await workAs(
        page,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'A-01',
      );
      // SRS 14.4 l.5475: "maker, checker, fin, admin - raise: maker; approve with OTP: checker" (OD-125).
      expect(await sidebarCodes(page)).toContain('I-23');
      const forged = await apiMultipart(
        page,
        '/v1/supplementary',
        {
          eis: MEMBER,
          paymentType: 'SALARY_WAGE',
          forPeriodYymm: monthsBefore((await currentCycle(page)).yymm, 1),
          applicationDate: today,
          documentType: 'OTHER',
          orderDate: today,
          issuingAuthority: 'AGM (Pay) Delhi',
        },
        { name: 'order.pdf', mimeType: 'application/pdf', content: '%PDF-1.7\n' },
      );
      expect(forged.status).toBe(403);
    },
  );

  test(
    'SRS-G-16: the request is recorded only with its order copy; the amount is never typed',
    {
      tag: [
        '@SC-01-0028',
        '@SRS-G-16',
        '@SRS-G-30',
        '@BR-AUDIT-010',
        '@SC-01-0095',
        '@SC-01-0105',
        '@SC-01-0110',
        '@edge',
      ],
    },
    async ({ page }) => {
      await openForm(page);
      const record = page.getByRole('button', { name: t('en', 'i23.record') });
      await page.getByLabel(t('en', 'gen.eis')).fill(MEMBER);
      await page
        .getByLabel(t('en', 'i23.forPeriod'))
        .selectOption(monthsBefore((await currentCycle(page)).yymm, 1));
      await page.getByLabel(t('en', 'i23.applicationDate')).fill(today);
      // Every order particular but the file: still blocked.
      const orderDraft = page.locator('[data-order-card]');
      await orderDraft.locator('select').first().selectOption('OTHER');
      await orderDraft.locator('input[type="date"]').fill(today);
      await orderDraft.getByPlaceholder(t('en', 'wf.ord.officePlaceholder')).fill('AGM (Pay) Delhi');
      await expect(record).toBeDisabled();
      await orderDraft.locator('input[type="file"]').setInputFiles(PDF);
      await expect(record).toBeEnabled();
      // Amount: computed from the originating event, never an input (SRS-G-33).
      await expect(page.getByLabel(t('en', 'i23.amount'), { exact: true })).toHaveCount(0);
      // The invoice table shows the date the invoice was sent to APPS (BR-AUDIT-010).
      await expect(
        page.getByRole('columnheader', { name: new RegExp(t('en', 'wf.inv.sentDate')) }).first(),
      ).toBeVisible();
    },
  );

  test(
    'a member who is not a payroll master of the unit is refused',
    { tag: ['@SRS-G-33', '@SRS-EH-3', '@SC-01-0101', '@negative'] },
    async ({ page }) => {
      test.skip(locked === '', 'Needs the locked cycle of 05-payroll-processing/03-payroll-cycle.');
      await openForm(page);
      await fillRequest(page, '999999', locked, today);
      await page.getByRole('button', { name: t('en', 'i23.record') }).click();
      await expect(page.locator('.fl-error[role="alert"]')).toContainText(t('en', 'error.COM-404-NOT-FOUND'));
    },
  );

  test(
    'BR-CYC-010: a payment type owned by another module is refused even by a forged request',
    { tag: ['@BR-CYC-010', '@SRS-G-33', '@SC-01-0097', '@SC-01-0102', '@negative'] },
    async ({ page }) => {
      const result = await apiMultipart(
        page,
        '/v1/supplementary',
        {
          eis: MEMBER,
          paymentType: 'COURT_RECOVERY',
          forPeriodYymm: locked || monthsBefore((await currentCycle(page)).yymm, 1),
          applicationDate: today,
          documentType: 'OTHER',
          orderDate: today,
          issuingAuthority: 'AGM (Pay) Delhi',
        },
        { name: 'order.pdf', mimeType: 'application/pdf', content: '%PDF-1.7\n' },
      );
      expect(result.status).toBe(422);
      expect(result.errorCode).toMatch(/^SUP-422-(SOURCE|EXCLUDED)$/);
    },
  );
});
