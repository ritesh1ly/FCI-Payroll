import { expect, type Page, test } from '@playwright/test';
import { apiProblem, apiStatus, businessToday, decide, t, workAs } from '../support/app';
import { apiJson } from '../support/payroll-support';

/**
 * M-11 Staff Transfer as built (SRS-16-4 … 16-9, SRS-16-18; review 2026-10-24, A1): the releasing
 * unit's Establishment / HR records a transfer with its order, a Payroll Checker of the unit decides;
 * approved, the employee works at the new unit (the old unit no longer sees the record), the staff
 * number and the CPF code stay unless a new number is given; rejected, nothing changes. The rules
 * of the form and the request: an active destination unit that is not the current one, a transfer
 * date not in the future, a joining date not before it, one transfer waiting at a time, the maker
 * never deciding their own.
 *
 * The subject is the paging-history member 889001 (seed V037_9_1: no account, no other spec moves
 * it). Re-runnable: the spec moves the member to Mumbai and back, each leg approved by that unit's
 * checker, so every run starts and ends with the member at DH001.
 */
const EIS = '889001';
const HR_DELHI = 'hr.delhi@fci-demo.test';
const hrDelhi = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const CHECKER_DELHI = 'ddo.delhi@fci-demo.test';
const checkerDelhi = { role: 'PAYROLL_CHECKER', office: 'DH001' };
const HR_MUMBAI = 'hr.mr001@fci-demo.test';
const hrMumbai = { role: 'ESTABLISHMENT_HR', office: 'MR001' };
const CHECKER_MUMBAI = 'pc.mr001@fci-demo.test';
const checkerMumbai = { role: 'PAYROLL_CHECKER', office: 'MR001' };

/** Today as the server sees it is never behind the browser's UTC date (IST = UTC + 5:30). */
const TODAY = businessToday();

function request(over: Record<string, unknown>) {
  return {
    toLocation: 'MR001',
    transferDate: TODAY,
    orderReference: 'ORD/E2E/1',
    // SRS-G-31 (US-GAP-26): HRMS holds the order letter, so the reference alone is enough.
    orderHeldInHrms: true,
    splitWage: false,
    reason: 'E2E transfer rules',
    ...over,
  };
}

/** Records a transfer on the M-11 form; returns once the toast confirms it. */
async function recordOnForm(page: Page, to: string, reason: string) {
  await page.getByText(t('en', 'trf.w.closed')).click();
  await page.getByRole('button', { name: t('en', 'trf.record') }).click();
  const submit = page.getByRole('button', { name: t('en', 'common.submitForApproval') });
  await expect(submit).toBeDisabled();
  await page.locator('#trf-eis').fill(EIS);
  await page.locator('#trf-to').selectOption(to);
  await page.locator('#trf-date').fill(TODAY);
  await page.locator('#trf-order').fill(`ORD/E2E/${Date.now()}`);
  // SRS-G-31 (US-GAP-26): HRMS holds the order letter, so no copy is attached here.
  await page.getByRole('checkbox', { name: t('en', 'm11.heldInHrms') }).check();
  await expect(submit).toBeDisabled();
  await page.locator('#trf-reason').fill(reason);
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(page.getByRole('status').filter({ hasText: t('en', 'trf.submitted', [EIS]) })).toBeVisible();
}

test.describe.serial('M-11 Staff Transfer', () => {
  const stamp = Date.now();
  const rejected = `E2E transfer rejected ${stamp}`;
  const outbound = `E2E transfer to Mumbai ${stamp}`;
  const inbound = `E2E transfer back to Delhi ${stamp}`;

  test(
    'the request rules: an active destination that is not the current unit, no future date, no joining before the transfer',
    { tag: ['@SRS-16-9', '@SC-04-0035', '@SC-04-0036', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', HR_DELHI, hrDelhi, 'M-11');
      expect(await apiStatus(page, `/v1/employees/${EIS}`), 'the subject starts at Delhi').toBe(200);
      const path = `/v1/employees/${EIS}/transfers`;
      expect(await apiProblem(page, path, 'POST', request({ toLocation: 'NOWHERE' }))).toEqual({
        status: 400,
        errorCode: 'COM-400-VALIDATION',
      });
      expect(await apiProblem(page, path, 'POST', request({ toLocation: 'DH001' }))).toEqual({
        status: 400,
        errorCode: 'COM-400-VALIDATION',
      });
      expect(await apiProblem(page, path, 'POST', request({ transferDate: '2399-01-01' }))).toEqual({
        status: 422,
        errorCode: 'EMP-422-FUTURE',
      });
      expect(
        await apiProblem(page, path, 'POST', request({ relievingDate: TODAY, joiningDate: '2020-01-01' })),
      ).toEqual({ status: 400, errorCode: 'COM-400-VALIDATION' });
      expect(await apiProblem(page, path, 'POST', request({ reason: ' ' }))).toEqual({
        status: 400,
        errorCode: 'COM-400-VALIDATION',
      });
      // The form sends nothing without a destination and a reason.
      await page.getByText(t('en', 'trf.w.closed')).click();
      await page.getByRole('button', { name: t('en', 'trf.record') }).click();
      const submit = page.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await page.locator('#trf-eis').fill(EIS);
      await page.locator('#trf-date').fill(TODAY);
      await page.locator('#trf-reason').fill('E2E guard');
      // SRS-16-18, SRS-G-31 (US-GAP-26): the order reference, and HRMS holds this order letter, so
      // no copy is attached here.
      await page.locator('#trf-order').fill('ORD/E2E/GUARD');
      await page.getByRole('checkbox', { name: t('en', 'm11.heldInHrms') }).check();
      await expect(submit).toBeDisabled();
      await page.locator('#trf-to').selectOption('MR001');
      await expect(submit).toBeEnabled();
      await page.locator('#trf-reason').fill('');
      await expect(submit).toBeDisabled();
      // US-GAP-134 (M-11 field table l.1352): the LPC Reference is System - no field on the form,
      // and a request naming one is refused (R-SRC).
      await expect(page.getByLabel(t('en', 'trf.lpc'))).toHaveCount(0);
      await expect(page.locator('#trf-lpc')).toHaveCount(0);
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      expect(await apiProblem(page, path, 'POST', request({ lpcReference: 'LPC/E2E/1' }))).toEqual({
        status: 400,
        errorCode: 'COM-400-VALIDATION',
      });
    },
  );

  test(
    'SRS-G-1: the maker never decides their own transfer; rejected, the employee stays where they are',
    { tag: ['@SRS-16-18', '@SRS-G-1', '@SC-04-0027', '@SC-04-0028', '@negative'] },
    async ({ browser }) => {
      const hr = await browser.newPage();
      await workAs(hr, 'en', HR_DELHI, hrDelhi, 'M-11');
      const view = await apiJson<{ approvalId: number; status: string }>(
        hr,
        `/v1/employees/${EIS}/transfers`,
        'POST',
        request({ reason: rejected }),
      );
      expect(view.approvalId).toBeGreaterThan(0);
      expect(
        await apiStatus(hr, `/v1/approvals/${view.approvalId}/decision`, 'POST', { decision: 'APPROVE' }),
      ).toBe(403);

      const checker = await browser.newPage();
      await workAs(checker, 'en', CHECKER_DELHI, checkerDelhi, 'W-01');
      await decide(checker, new RegExp(rejected), 'REJECT', 'E2E: order not in force');

      await hr.reload();
      expect(await apiStatus(hr, `/v1/employees/${EIS}`), 'still at Delhi').toBe(200);
      await expect(hr.getByRole('row', { name: new RegExp(EIS) }).first()).toContainText(
        t('en', 'status.REJECTED'),
      );
    },
  );

  test(
    'SRS-16-9: recorded with its order and approved by the unit checker, the employee works at the new unit; one transfer waits at a time',
    {
      tag: [
        '@SRS-16-9',
        '@SRS-16-6',
        '@SRS-G-1',
        '@SC-04-0025',
        '@SC-04-0031',
        '@SC-04-0006',
        '@SC-04-0039',
        '@positive',
      ],
    },
    async ({ browser }) => {
      const hr = await browser.newPage();
      await workAs(hr, 'en', HR_DELHI, hrDelhi, 'M-11');
      const before = JSON.stringify(await apiJson<unknown>(hr, `/v1/employees/${EIS}`));
      expect(before).toContain('CPF/889001');
      await recordOnForm(hr, 'MR001', outbound);
      await expect(hr.getByRole('row', { name: new RegExp(EIS) }).first()).toContainText(
        t('en', 'status.PENDING_APPROVAL'),
      );
      expect(
        await apiProblem(
          hr,
          `/v1/employees/${EIS}/transfers`,
          'POST',
          request({ reason: `${outbound} again` }),
        ),
      ).toEqual({ status: 409, errorCode: 'WF-409-PENDING' });

      const checker = await browser.newPage();
      await workAs(checker, 'en', CHECKER_DELHI, checkerDelhi, 'W-01');
      await decide(checker, new RegExp(outbound), 'APPROVE');

      // SRS-G-53 (group g1, OD-68): Delhi, the previous unit, keeps read-only access - the record
      // is marked with the transfer and Delhi records nothing on it (no event is keyed at all since
      // SRS-1-63; EmployeeEventIT proves the flag refusal); Mumbai owns it, with the same
      // staff number and CPF code.
      const previous = (await apiJson<{ transferredOut?: string | null }>(hr, `/v1/employees/${EIS}`)) ?? {};
      expect(previous.transferredOut).toBeTruthy();
      expect(
        await apiStatus(hr, `/v1/employees/${EIS}/lifecycle-events`, 'POST', {
          eventType: 'HOLD',
          eventDate: businessToday(),
          reason: 'E2E previous unit may not record',
        }),
      ).toBeGreaterThanOrEqual(400);
      const mumbai = await browser.newPage();
      await workAs(mumbai, 'en', HR_MUMBAI, hrMumbai, 'M-11');
      const after = JSON.stringify(await apiJson<unknown>(mumbai, `/v1/employees/${EIS}`));
      expect(after).toContain('MR001');
      expect(after).toContain('CPF/889001');
      await expect(mumbai.getByRole('row', { name: new RegExp(EIS) }).first()).toContainText(
        t('en', 'status.APPROVED'),
      );
    },
  );

  test(
    'the receiving unit sends the employee back the same way (the spec leaves the member at Delhi)',
    { tag: ['@SRS-16-9', '@SRS-G-1', '@SC-04-0025', '@positive'] },
    async ({ browser }) => {
      const hr = await browser.newPage();
      await workAs(hr, 'en', HR_MUMBAI, hrMumbai, 'M-11');
      await recordOnForm(hr, 'DH001', inbound);
      const checker = await browser.newPage();
      await workAs(checker, 'en', CHECKER_MUMBAI, checkerMumbai, 'W-01');
      await decide(checker, new RegExp(inbound), 'APPROVE');
      // SRS-G-53 (OD-68): Mumbai, now the previous unit, reads the record only.
      const previous = (await apiJson<{ transferredOut?: string | null }>(hr, `/v1/employees/${EIS}`)) ?? {};
      expect(previous.transferredOut).toBeTruthy();
      const delhi = await browser.newPage();
      await workAs(delhi, 'en', HR_DELHI, hrDelhi, 'M-11');
      expect(await apiStatus(delhi, `/v1/employees/${EIS}`), 'back at Delhi').toBe(200);
    },
  );
});
