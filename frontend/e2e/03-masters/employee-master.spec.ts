import { expect, type Page, test } from '@playwright/test';
import { apiStatus, businessToday, dataRows, employeeList, t, workAs } from '../support/app';

/**
 * Employee module through the UI: the M-01 master (filters from the M-16 lists, search, masked
 * personal data, office scope) and I-17 life-cycle events, view only (SRS-1-63: no event is keyed in
 * Payroll). Re-runnable: nothing is changed.
 */
const HR = 'hr.delhi@fci-demo.test';

const table = (page: Page) => employeeList(page);

test.describe('Employee master (M-01) and employee events (I-17)', () => {
  test(
    'filters come from the reference lists and narrow the list',
    { tag: ['@SRS-G-6', '@SRS-16-15', '@positive'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      const pattern = page.getByLabel(t('en', 'm01.payPattern'));
      // Options as drawn, from the PAY_PATTERN list (OD-123); FND stays behind More filters (WF row).
      await expect(pattern.locator('option')).toHaveText([
        t('en', 'm01.w.allPatterns'),
        t('en', 'm01.w.pattern.IDA'),
        t('en', 'm01.w.pattern.CDA'),
      ]);
      await pattern.selectOption('CDA');
      const cells = table(page).locator('tbody tr td:nth-child(4)');
      await expect(cells.first()).toHaveText('CDA');
      expect(new Set(await cells.allTextContents())).toEqual(new Set(['CDA']));
      await pattern.selectOption('');
      await page
        .getByRole('combobox', { name: t('en', 'm01.status'), exact: true })
        .selectOption('BANK_INVALID');
      // The stack-B data is shared and grows: later specs add members without a bank branch (the
      // labour gangs of 03-masters/labour-g5-closing are BANK_INVALID by rule), so the count is not
      // pinned. The filter is proved by what it leaves: only Bank invalid rows, and EIS 106118 among them.
      const status = table(page).locator('tbody tr td:nth-child(10)');
      await expect(status.first()).toHaveText(t('en', 'status.BANK_INVALID'));
      expect(new Set(await status.allTextContents())).toEqual(new Set([t('en', 'status.BANK_INVALID')]));
      await expect(table(page).locator('tbody tr', { hasText: '106118' })).toHaveCount(1);
      // The other pay patterns' members are gone from the list: the filter narrowed it.
      await expect(table(page).locator('tbody tr', { hasText: '105220' })).toHaveCount(0);
    },
  );

  test(
    'SRS-G-45: an HRMS-owned field is never changed in Payroll, not even by the role that edits M-01',
    { tag: ['@SRS-G-45', '@SRS-SEC-7', '@SC-01-0042', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, { role: 'ESTABLISHMENT_HR', office: 'DH001' }, 'M-01');
      // CLAUDE.md section 2, R-SRC (plan 16 D16-19): PAN, bank account, contact, basic pay and the
      // CPF code come from HRMS (an I-21 task). No Payroll endpoint takes them, and the only M-01
      // proposal (an allowance flag) refuses a field name as a flag.
      for (const field of ['panNumber', 'bankAccount', 'mobileNumber', 'basicPay', 'cpfCode']) {
        expect([404, 405], field).toContain(
          await apiStatus(page, '/v1/employees/105220/changes', 'POST', {
            field,
            value: 'AAAPA1234A',
            reason: 'E2E forged change of an HRMS field',
          }),
        );
      }
      // Refused either way: a flag proposal is multipart with its document (SRS-1-26, 415 for a bare
      // JSON body), and BANK_ACCOUNT is no allowance flag (422).
      expect([415, 422]).toContain(
        await apiStatus(page, '/v1/employees/105220/allowance-flags', 'POST', {
          flagCode: 'BANK_ACCOUNT',
          value: 'Y',
          effectiveFrom: businessToday(),
          reason: 'E2E forged change of an HRMS field',
        }),
      );
    },
  );

  test(
    'SRS-G-45: every HRMS-owned field of the M-01 dossier is read-only and the API refuses it for every role',
    { tag: ['@SRS-G-45', '@SRS-SEC-7', '@SRS-1-52', '@SC-01-0042', '@negative'] },
    async ({ page }) => {
      // R-SRC (CLAUDE.md section 2): HRMS-owned data is never keyed in Payroll. The dossier groups
      // that hold HRMS fields (service, statutory, control) show values only, with their source.
      for (const [login, role] of [
        [HR, 'ESTABLISHMENT_HR'],
        ['maker.delhi@fci-demo.test', 'PAYROLL_MAKER'],
      ] as const) {
        await workAs(page, 'en', login, { role, office: 'DH001' }, 'M-01');
        await page.getByLabel(t('en', 'wf.searchInList')).fill('105220');
        await expect(dataRows(table(page))).toHaveCount(1);
        await table(page)
          .getByRole('button', { name: /View record/ })
          .click();
        for (const group of ['m01.group.service', 'm01.group.statutory', 'm01.group.control']) {
          const fields = page.getByLabel(t('en', group), { exact: true });
          await expect(fields, `${role} ${group}`).toBeVisible();
          // The group's fields carry their HRMS source marker (R-SRC). The badge itself is shown
          // only in the trace view (`.src` is display:none on the normal screen), so its presence
          // is asserted, not its visibility.
          await expect(fields.locator('.src.s-HRMS'), `${role} ${group}`).not.toHaveCount(0);
          await expect(
            fields.locator('input, select, textarea, [contenteditable="true"]'),
            `${role} ${group}: nothing to type into`,
          ).toHaveCount(0);
        }
        // The same fields, forged on the record itself: no endpoint takes them, whatever the verb.
        for (const field of [
          'birthDate',
          'joiningDate',
          'retirementDate',
          'gender',
          'aadhaarNumber',
          'pranNumber',
          'uan',
          'epsNumber',
          'epfoMemberId',
          'panNumber',
          'bankAccount',
          'mobileNumber',
          'emailAddress',
          'cpfCode',
          'currentBasic',
        ]) {
          for (const method of ['PUT', 'PATCH'] as const) {
            expect([403, 404, 405], `${role} ${method} ${field}`).toContain(
              await apiStatus(page, '/v1/employees/105220', method, {
                [field]: '1990-01-01',
                reason: 'E2E forged change of an HRMS field',
              }),
            );
          }
        }
      }
    },
  );

  test(
    'search by EIS, the dossier masks PAN and bank account',
    { tag: ['@SRS-SEC-4', '@SRS-G-45', '@SC-01-0042', '@positive'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      await page.getByLabel(t('en', 'wf.searchInList')).fill('105220');
      await expect(dataRows(table(page))).toHaveCount(1);
      await table(page)
        .getByRole('button', { name: /View record/ })
        .click();
      await expect(page.getByText(/\*{3,}/).first()).toBeVisible();
      await expect(page.getByText('CDEPB9087M')).toHaveCount(0);
    },
  );

  test(
    'US-GAP-275: the dossier shows the Pay Status, System and derived - Active in service, Separated once separated',
    { tag: ['@US-GAP-275', '@SRS-1-1', '@positive'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      for (const [eis, status] of [
        ['105220', 'ACTIVE'],
        ['109990', 'SEPARATED'],
      ] as const) {
        await page.goto('/payroll/?s=M-01');
        await page.getByLabel(t('en', 'wf.searchInList')).fill(eis);
        await expect(dataRows(table(page))).toHaveCount(1);
        await table(page)
          .getByRole('button', { name: /View record/ })
          .click();
        const control = page.getByLabel(t('en', 'm01.group.control'), { exact: true });
        await expect(control).toContainText(`${status} - ${t('en', `m01.payStatus.${status}`)}`);
        // Derived, never entered: no input in the group for it.
        await expect(control.getByRole('textbox')).toHaveCount(0);
      }
    },
  );

  test(
    "another office's employee is out of scope, also by a forged EIS",
    { tag: ['@SRS-G-29', '@SRS-SEC-7', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      await page.getByLabel(t('en', 'wf.searchInList')).fill('205101');
      await expect(page.getByRole('cell', { name: '205101', exact: true })).toHaveCount(0);
      const status = await apiStatus(page, '/v1/employees/205101');
      expect([403, 404]).toContain(status);
    },
  );
});

test.describe('I-17 life-cycle events (from HRMS), view only', () => {
  const today = () => businessToday();

  test(
    'SRS-1-63: no role keys an event: no form for Establishment / HR, the Checker or the Maker',
    { tag: ['@SRS-1-63', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      for (const [login, role] of [
        [HR, 'ESTABLISHMENT_HR'],
        ['maker.delhi@fci-demo.test', 'PAYROLL_MAKER'],
      ] as const) {
        await workAs(page, 'en', login, { role, office: 'DH001' }, 'I-17');
        // As drawn on the I-17 rules panel (the pack's later "from HRMS" screen, WF-024, WF-229).
        await expect(page.getByText(t('en', 'evt.w.rule.1m'))).toBeVisible();
        await expect(page.getByRole('button', { name: t('en', 'common.submitForApproval') })).toHaveCount(0);
        await expect(page.locator('#evt-type, #evt-date, #evt-reason')).toHaveCount(0);
        await expect(page.getByRole('link', { name: t('en', 'evt.w.tasks') })).toBeVisible();
        await expect(page.getByRole('link', { name: t('en', 'evt.w.fallback') })).toBeVisible();
      }
    },
  );

  test(
    'SRS-1-63: the API refuses a keyed event of any type, for every role; the list still answers',
    { tag: ['@SRS-1-63', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, { role: 'ESTABLISHMENT_HR', office: 'DH001' }, 'I-17');
      for (const eventType of ['HOLD', 'SALARY_STOP', 'SALARY_RELEASE', 'DEPUTATION_OUT', 'SUSPENSION']) {
        expect(
          await apiStatus(page, '/v1/employees/106731/lifecycle-events', 'POST', {
            eventType,
            eventDate: today(),
            reason: 'e2e: forged',
          }),
          eventType,
        ).toBe(405);
      }
      expect(await apiStatus(page, '/v1/employees/106731/lifecycle-events')).toBe(200);
      expect(await apiStatus(page, '/v1/lifecycle-events')).toBe(200);
    },
  );

  test(
    'the header marks HRMS as the source of every event field; an employee is looked up by number',
    { tag: ['@SRS-1-63', '@SRS-G-36', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, { role: 'ESTABLISHMENT_HR', office: 'DH001' }, 'I-17');
      const header = page.locator('thead').first();
      await expect(header).toContainText(t('en', 'evt.w.col.task'));
      await expect(header).not.toContainText('ENT');
      // The lookup by number stays behind the closed section (WF-229).
      await page.getByText(t('en', 'evt.employeeHistory')).first().click();
      const show = page.getByRole('button', { name: t('en', 'evt.show') });
      await expect(show).toBeDisabled();
      await page.locator('#evt-lookup').fill('106731');
      await expect(show).toBeEnabled();
      await show.click();
    },
  );
});

test.describe('employee data is never cached', () => {
  test(
    'every response is no-store and every visit fetches again',
    { tag: ['@SRS-SEC-4', '@edge'] },
    async ({ page }) => {
      const employeeCalls: string[] = [];
      const cacheHeaders = new Set<string>();
      page.on('response', (r) => {
        const url = new URL(r.url());
        if (
          /^\/nginx\/fci-payroll\/v1\/(employees|eseva|mis|lifecycle-events|transfers|cycles\/[^/]+\/results)/.test(
            url.pathname,
          )
        ) {
          employeeCalls.push(url.pathname);
          cacheHeaders.add(r.headers()['cache-control'] ?? '(none)');
        }
      });
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      await expect(table(page).getByRole('cell', { name: '104882', exact: true })).toBeVisible();
      const first = employeeCalls.filter((p) => p === '/nginx/fci-payroll/v1/employees').length;
      // Leave the screen and come back within seconds: the list is fetched again, not served from memory.
      await page.locator('aside').getByRole('button', { name: /X-02/ }).click();
      // Wait until M-01 has really been left (unmounted); a click racing the navigation never leaves it.
      await expect(page).toHaveURL(/[?&]s=W-01/);
      await expect(page.getByRole('cell', { name: '104882', exact: true })).toHaveCount(0);
      await page.locator('aside').getByRole('button', { name: /M-01/ }).click();
      await expect(table(page).getByRole('cell', { name: '104882', exact: true })).toBeVisible();
      await expect
        .poll(() => employeeCalls.filter((p) => p === '/nginx/fci-payroll/v1/employees').length)
        .toBeGreaterThan(first);
      expect([...cacheHeaders].every((h) => h.includes('no-store'))).toBe(true);
    },
  );
});
