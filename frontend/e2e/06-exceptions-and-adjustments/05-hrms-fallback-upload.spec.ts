import { expect, type Page, test } from '@playwright/test';
import {
  apiAll,
  auditTrail,
  cancelWithReason,
  currentCycle,
  expectToast,
  monthsBefore,
  row,
} from '../support/payroll-support';
import { apiMultipart, decide, sidebarCodes, t, workAs } from '../support/app';
import { dbFixtureAvailable, sqlRows } from '../support/db';
import {
  TEST_CHECKER,
  TEST_MAKER,
  TEST_OFFICE,
  testChecker,
  testEis,
  testMaker,
} from '../support/cycle-office';

/**
 * X-07 HRMS Fallback Upload (SRS-G-46, SRS-G-47, SRS-EH-11, BR-INT-011) through the UI with the
 * demo profile's placeholder export layout (decision D-2). Headquarters enables an upload under
 * maker-checker; the office Maker uploads the export, every record is accepted or rejected with its
 * reason; the unit's Payroll Checker approves the accepted records with OTP. Fallback (b) needs its
 * order copy. Every run uses its own reason (timestamp), so it re-runs on the same stack.
 */
// OD-141: the office Maker and Checker of the cycle test office DT001, whose past months stay open (a fallback
// upload refuses a locked month; DH001's are locked demo months, V082_21). MEMBER: its copy of DH001's 106958.
const MAKER = TEST_MAKER;
const CHECKER = TEST_CHECKER;
const maker = testMaker;
const checker = testChecker;
const MEMBER = testEis('106958');
const ADMIN = 'sysadmin@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const OTP = 'HRMS_FALLBACK_UPLOAD_APPROVAL';

const ORDER_FILE = { name: 'order.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7 demo') };

/** The upload register and the withdrawals open from the drawn History button (WF-198). */
async function openHistory(page: Page) {
  await page.getByRole('button', { name: t('en', 'x07.w.history'), exact: true }).click();
}

/** The Upload card of a batch (the card shows the upload in hand). */
function uploadCard(page: Page, batch: string) {
  return page.locator(`[data-upload="${batch}"]`);
}

/** The drawn order card of the upload in hand: particulars first, picking the file stores it. */
async function attachOrder(page: Page) {
  const card = page.locator('[data-order-card]');
  await card.locator('select').first().selectOption('OTHER');
  await card.locator('input[type="date"]').fill(new Date().toISOString().slice(0, 10));
  await card.getByPlaceholder(t('en', 'wf.ord.officePlaceholder')).fill('AGM (Pay) Delhi');
  await card.locator('input[type="file"]').setInputFiles(ORDER_FILE);
  await expectToast(page, t('en', 'ord.attached'));
}

function dmy(yymm: string, day: number): string {
  return `${String(day).padStart(2, '0')}-${yymm.slice(4)}-${yymm.slice(0, 4)}`;
}

/**
 * A past month whose test-office (DT001) cycle is not locked (a fallback upload refuses a locked one): the
 * Q-05-2 prior-period fixture of the cycle specs (lockPriorForFixture) locks the month before
 * the current one, so the first of the months 1 - 5 back that is not LOCKED.
 */
function openPastMonth(current: string): string {
  const candidates = [1, 2, 3, 4, 5].map((back) => monthsBefore(current, back));
  if (!dbFixtureAvailable()) return candidates[0] as string;
  const locked = new Set(
    sqlRows(
      `SELECT c.payroll_yymm FROM payroll_cycle_instance c JOIN org_location l ON l.location_id = c.org_unit_id WHERE l.location_code = '${TEST_OFFICE}' AND c.cycle_status = 'LOCKED'`,
    ).map((r) => String(r[0])),
  );
  return candidates.find((m) => !locked.has(m)) ?? (candidates[0] as string);
}

function lastDay(yymm: string): number {
  return new Date(Date.UTC(Number(yymm.slice(0, 4)), Number(yymm.slice(4)), 0)).getUTCDate();
}

/** HQ proposes an enablement for the test office; the HQ Payroll Checker approves it in W-01. */
async function enable(page: Page, type: string, yymm: string, reason: string) {
  await workAs(page, 'en', ADMIN, admin, 'X-07');
  await page.getByRole('button', { name: t('en', 'x07.w.act.enable') }).click();
  await page.getByLabel(t('en', 'x07.type'), { exact: true }).selectOption(type);
  await page.getByLabel(t('en', 'x07.month')).selectOption(yymm);
  await page.getByLabel(t('en', 'x07.offices')).fill(TEST_OFFICE.toLowerCase());
  await page.getByLabel(t('en', 'common.reason')).fill(reason);
  await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
  await expectToast(page, /Sent for approval/);
  await workAs(page, 'en', HQ_CHECKER, hqChecker, 'W-01');
  await decide(page, new RegExp(reason), 'APPROVE');
}

/** Uploads {@code csv} under this run's enablement (found by its reason). */
async function upload(page: Page, reason: string, csv: string, name = 'hrms-export.csv') {
  const enablements = await apiAll<{ id: number; reason: string }>(page, '/v1/hrms-fallback/enablements');
  const id = enablements.find((e) => e.reason === reason)?.id;
  expect(id, `enablement "${reason}"`).toBeDefined();
  // The upload form opens from the header action (wireframe v6.1).
  const select = page.getByRole('combobox', { name: t('en', 'x07.enablement') });
  if (!(await select.isVisible()))
    await page.getByRole('button', { name: t('en', 'x07.w.act.upload') }).click();
  await page.getByRole('combobox', { name: t('en', 'x07.enablement') }).selectOption(String(id));
  await page
    .getByLabel(t('en', 'x07.file'))
    .setInputFiles({ name, mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await page.getByRole('button', { name: t('en', 'x07.uploadValidate') }).click();
}

test.describe.serial('HRMS fallback upload (X-07)', () => {
  const stamp = Date.now();
  let month = '';
  let batch = '';

  test(
    'only Headquarters enables; the office Maker sees no enable action',
    {
      tag: ['@SRS-G-46', '@SRS-G-25', '@SRS-G-47', '@SC-01-0025', '@SC-01-0044', '@SC-01-0138', '@negative'],
    },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-07');
      month = openPastMonth((await currentCycle(page)).yymm);
      // Drawn for every role, disabled with the pack's reason (OD-121).
      const enableUpload = page.getByRole('button', { name: t('en', 'x07.w.act.enable') });
      await expect(enableUpload).toBeDisabled();
      await expect(enableUpload).toHaveAttribute('data-why', t('en', 'x07.w.why.enable'));
      await expect(page.getByRole('button', { name: t('en', 'x07.w.act.upload') })).toBeEnabled();
      await expect(page.getByRole('button', { name: t('en', 'x07.w.act.recon') })).toBeEnabled();
    },
  );

  test(
    'SRS-G-47: HQ enables attendance and leave for the office under maker-checker',
    {
      tag: [
        '@SRS-G-46',
        '@SRS-G-1',
        '@SC-01-0043',
        '@SC-01-0122',
        '@SC-01-0128',
        '@SC-01-0129',
        '@SC-01-0130',
        '@SC-01-0131',
        '@SC-01-0132',
        '@positive',
      ],
    },
    async ({ page }) => {
      await enable(page, 'ATTENDANCE_LEAVE', month, `E2E HRMS down ${stamp} A`);
      await workAs(page, 'en', ADMIN, admin, 'X-07');
      await expect(page.locator('tr').filter({ hasText: `E2E HRMS down ${stamp} A` })).toContainText(
        t('en', 'x07.w.enablement.OPEN'),
      );
    },
  );

  test(
    'SRS-G-46: a file not in the export format is rejected as a whole',
    { tag: ['@SRS-G-46', '@SRS-EH-11', '@SC-01-0124', '@SC-01-0133', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-07');
      await upload(page, `E2E HRMS down ${stamp} A`, 'NAME,AMOUNT\r\nx,1\r\n', 'wrong.csv');
      await expect(page.locator('.fl-error[role="alert"]')).toContainText(t('en', 'error.HFB-422-FORMAT'));
    },
  );

  test(
    'SRS-EH-11: each record is accepted or rejected with its reason',
    { tag: ['@SRS-EH-11', '@SC-01-0122', '@SC-01-0133', '@SC-01-0134', '@SC-01-0136', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-07');
      const last = lastDay(month);
      await upload(
        page,
        `E2E HRMS down ${stamp} A`,
        'EMP_NO,FROM_DATE,TO_DATE,PAY_DAYS,LWP_DAYS,HALF_PAY_DAYS\r\n' +
          `${MEMBER},${dmy(month, 1)},${dmy(month, last)},${last - 1},1,0\r\n` +
          `999999,${dmy(month, 1)},${dmy(month, last)},${last},0,0\r\n`,
      );
      const toast = await expectToast(page, /FBU-/);
      batch = /FBU-[A-Z0-9-]+/.exec(toast)?.[0] ?? '';
      expect(toast).toContain(t('en', 'x07.uploaded', [batch, 1, 1]));
      // The new upload's card shows by itself; "Validation" opens its records and closes them again.
      const card = uploadCard(page, batch);
      await expect(card).toBeVisible();
      await expect(card).toContainText('EMPLOYEE_NOT_FOUND');
      const validation = card.getByRole('link', { name: new RegExp(t('en', 'x07.w.validation', [1, 1])) });
      const lines = page.locator(`[data-lines="${batch}"]`);
      await validation.click();
      await expect(lines).toContainText('EMPLOYEE_NOT_FOUND');
      await validation.click();
      await expect(lines).toHaveCount(0);
      await validation.click();
      // The source file is retained with the upload and downloads read-only.
      const source = card.getByRole('link', { name: t('en', 'x07.w.sourceKept') });
      const file = await page.request.get(String(await source.getAttribute('href')));
      expect(file.status()).toBe(200);
      await expect(lines.locator('tbody .pill')).toHaveText([
        t('en', 'x07.line.ACCEPTED'),
        t('en', 'x07.line.REJECTED'),
      ]);
    },
  );

  test(
    'the accepted records go to the unit Checker, who approves with OTP',
    {
      tag: [
        '@SRS-G-21',
        '@SRS-G-3',
        '@SC-01-0003',
        '@SC-01-0021',
        '@SC-01-0122',
        '@SC-01-0137',
        '@SC-01-0139',
        '@positive',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-07');
      await openHistory(page);
      await row(page, batch)
        .getByRole('button', { name: t('en', 'common.submitForApproval') })
        .click();
      await expectToast(page, t('en', 'x07.submitted', [batch]));
      await expect(row(page, batch)).toContainText(t('en', 'x07.upload.PENDING_APPROVAL'));
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(batch), 'APPROVE', '', OTP);
      await workAs(page, 'en', MAKER, maker, 'X-07');
      await openHistory(page);
      await expect(row(page, batch)).toContainText(t('en', 'x07.upload.APPROVED'));
    },
  );

  test(
    'SRS-G-2: the approved upload keeps its source file, validation result, Checker and audit trail',
    { tag: ['@SRS-G-2', '@SRS-G-46', '@SC-01-0127', '@SC-01-0002', '@SC-01-0137', '@positive'] },
    async ({ page, browser }) => {
      await workAs(page, 'en', MAKER, maker, 'X-07');
      await openHistory(page);
      await row(page, batch)
        .getByRole('button', { name: t('en', 'x07.lines') })
        .click();
      const detail = uploadCard(page, batch);
      await expect(detail).toContainText(CHECKER);
      const source = detail.getByRole('link', { name: t('en', 'x07.w.sourceKept') });
      expect((await page.request.get(String(await source.getAttribute('href')))).status()).toBe(200);
      const rows = await auditTrail(browser, 'hrms_fallback_upload', batch);
      expect(rows.length).toBeGreaterThan(0);
      expect(
        rows.some((r) => r.approvalId !== null),
        'approval linked',
      ).toBe(true);
      expect(new Set(rows.map((r) => r.changedBy))).toEqual(new Set([MAKER, CHECKER]));
      expect(rows.every((r) => r.moduleCode === 'X-07')).toBe(true);
    },
  );

  test(
    'BR-INT-011: fallback (b) is submitted only with its order copy; an unsubmitted upload is cancelled',
    {
      tag: [
        '@SRS-G-46',
        '@SRS-G-16',
        '@SRS-G-32',
        '@SRS-G-37',
        '@SC-01-0016',
        '@SC-01-0030',
        '@SC-01-0035',
        '@SC-01-0126',
        '@SC-01-0135',
        '@edge',
      ],
    },
    async ({ page }) => {
      await enable(page, 'SUSPENSION_ABSCONDING', month, `E2E HRMS down ${stamp} B`);
      await workAs(page, 'en', MAKER, maker, 'X-07');
      await upload(
        page,
        `E2E HRMS down ${stamp} B`,
        'EMP_NO,EVENT,EFFECTIVE_DATE,ORDER_NO,ORDER_DATE,ISSUED_BY\r\n' +
          `${MEMBER},SUSPENSION,${dmy(month, 10)},RO/DL/${stamp},${dmy(month, 9)},RO Delhi\r\n`,
        'orders.csv',
      );
      const orders = /FBU-[A-Z0-9-]+/.exec(await expectToast(page, /FBU-/))?.[0] ?? '';
      await openHistory(page);
      const submit = row(page, orders).getByRole('button', { name: t('en', 'common.submitForApproval') });
      await expect(submit).toBeDisabled();
      await expect(submit).toHaveAttribute('title', t('en', 'x07.block.order'));
      // The new upload's card and the order card are on the screen already.
      const card = page.locator('[data-order-card]');
      await expect(card).toContainText(t('en', 'ord.none'));
      await attachOrder(page);
      await expect(submit).toBeEnabled();
      await expect(card.locator('tbody a')).toHaveCount(1);
      await cancelWithReason(page, orders, `E2E clean-up ${stamp}`, 'register');
      await expectToast(page, t('en', 'x07.cancelled', [orders]));
      await expect(row(page, orders)).toContainText(t('en', 'x07.upload.CANCELLED'));
    },
  );

  test(
    'the Payroll Checker returns an upload with a reason; the Maker resubmits; a rejection ends it',
    { tag: ['@SRS-G-1', '@SRS-G-46', '@SC-01-0125', '@SC-01-0139', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-07');
      await upload(
        page,
        `E2E HRMS down ${stamp} B`,
        'EMP_NO,EVENT,EFFECTIVE_DATE,ORDER_NO,ORDER_DATE,ISSUED_BY\r\n' +
          `${MEMBER},SUSPENSION,${dmy(month, 11)},RO/DL/${stamp}/R,${dmy(month, 9)},RO Delhi\r\n`,
        'orders-return.csv',
      );
      const returned = /FBU-[A-Z0-9-]+/.exec(await expectToast(page, /FBU-/))?.[0] ?? '';
      await attachOrder(page);
      await openHistory(page);
      await row(page, returned)
        .getByRole('button', { name: t('en', 'common.submitForApproval') })
        .click();
      await expectToast(page, t('en', 'x07.submitted', [returned]));

      // Returned: a reason is mandatory, no OTP (only an approval takes one).
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(returned), 'RETURN', `Order number does not match ${stamp}`);
      await workAs(page, 'en', MAKER, maker, 'X-07');
      await openHistory(page);
      await expect(row(page, returned)).toContainText(t('en', 'x07.upload.RETURNED'));
      await expect(row(page, returned)).toContainText(`Order number does not match ${stamp}`);
      // The Maker resubmits the returned upload.
      await row(page, returned)
        .getByRole('button', { name: t('en', 'common.submitForApproval') })
        .click();
      await expect(row(page, returned)).toContainText(t('en', 'x07.upload.PENDING_APPROVAL'));

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(returned), 'REJECT', `Order not issued ${stamp}`);
      await workAs(page, 'en', MAKER, maker, 'X-07');
      await openHistory(page);
      await expect(row(page, returned)).toContainText(t('en', 'x07.upload.REJECTED'));
      // A rejected upload offers nothing more.
      await expect(
        row(page, returned).getByRole('button', { name: t('en', 'common.submitForApproval') }),
      ).toHaveCount(0);
    },
  );

  test(
    'SRS-EH-11: HQ withdraws an enablement nothing was approved under',
    { tag: ['@SRS-EH-11', '@SRS-G-37', '@SC-01-0139', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'X-07');
      await openHistory(page);
      const enablement = page.locator('tr').filter({ hasText: `E2E HRMS down ${stamp} B` });
      const reference = await enablement.first().getAttribute('data-reference');
      await page
        .locator(`tr[data-live="${reference}"]`)
        .getByRole('button', { name: t('en', 'x07.withdraw') })
        .click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('textbox').fill('HRMS is back');
      await dialog.getByRole('button').last().click();
      await expect(enablement.first()).toContainText(t('en', 'x07.enablement.WITHDRAWN'));
      // The administrator reads I-20 (SRS 14.4 l.5472, OD-125) and enters no payroll data (SRS role table).
      expect(await sidebarCodes(page)).toContain('I-20');
      await page.goto('/payroll/?s=I-20');
      await expect(page.getByRole('button', { name: t('en', 'i20.raise') })).toBeDisabled();
    },
  );

  test(
    'SRS-G-46: without an open enablement no upload is accepted, not even by a forged request',
    { tag: ['@SRS-G-46', '@SRS-EH-11', '@SC-01-0123', '@SC-01-0133', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-07');
      const enablements = await apiAll<{ id: number; reason: string; status: string }>(
        page,
        '/v1/hrms-fallback/enablements',
      );
      const withdrawn = enablements.find((e) => e.reason === `E2E HRMS down ${stamp} B`);
      expect(withdrawn?.status).toBe('WITHDRAWN');
      // The form does not offer it.
      await page.getByRole('button', { name: t('en', 'x07.w.act.upload') }).click();
      await expect(
        page
          .getByRole('combobox', { name: t('en', 'x07.enablement') })
          .locator(`option[value="${withdrawn?.id}"]`),
      ).toHaveCount(0);
      // Nor does the API take a file for it.
      const forged = await apiMultipart(
        page,
        `/v1/hrms-fallback/uploads?enablementId=${withdrawn?.id}`,
        {},
        { name: 'late.csv', mimeType: 'text/csv', content: `EMP_NO\r\n${MEMBER}\r\n` },
      );
      expect(forged.status).toBeGreaterThanOrEqual(400);
      expect(forged.status).toBeLessThan(500);
      expect(forged.errorCode).toMatch(/^HFB-/);
    },
  );
});
