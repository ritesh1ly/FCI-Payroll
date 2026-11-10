import { expect, type Page, test } from '@playwright/test';
import { dataRows, decide, employeeList, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';
import { fillOrder } from '../support/payroll-support';

/**
 * M-01 Payroll allowance flags (plan 16 phase 16d-2; SRS-1-4, SRS-1-11) at Delhi HQ (DH001): Establishment/HR
 * proposes the NPA flag of a member from a date with a reason, the Payroll Checker approves it on W-01 and the
 * version shows in the history; the Payroll Maker has no change action. Re-runnable: each run proposes the opposite
 * of the member's latest NPA version, from the day after it.
 */
const HR = 'hr.delhi@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const NPA = 'Non-Practising Allowance';

/** A Delhi member near the end of the list, the next NPA value and its date. */
function next(): { eis: string; value: 'Y' | 'N'; from: string } {
  const rows = sqlRows(
    `SELECT e.emp_num, (SELECT f.flag_value FROM employee_eligibility_flag f WHERE f.employee_id = e.employee_id AND f.flag_code = 'NPA' ORDER BY f.effective_from DESC FETCH FIRST 1 ROWS ONLY) AS latest, (SELECT TO_CHAR(MAX(f.effective_from) + 1, 'YYYY-MM-DD') FROM employee_eligibility_flag f WHERE f.employee_id = e.employee_id AND f.flag_code = 'NPA') AS after_day, TO_CHAR(CURRENT_DATE, 'YYYY-MM-DD') AS today_day FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.record_status = 'ACTIVE' ORDER BY e.emp_num DESC OFFSET 4 ROWS FETCH NEXT 1 ROWS ONLY`,
  );
  // The H2 shell prints a NULL as the text "null".
  const [eis, latest, after, today] = (rows[0] ?? []).map((c) => (c === 'null' ? null : c));
  return { eis: String(eis), value: latest === 'Y' ? 'N' : 'Y', from: String(after ?? today) };
}

async function openFlags(page: Page, eis: string) {
  await page.getByLabel(t('en', 'wf.searchInList')).fill(eis);
  const list = employeeList(page);
  await expect(dataRows(list)).toHaveCount(1);
  await list.getByRole('button', { name: /View record/ }).click();
  return page.getByRole('table', { name: t('en', 'm01.flags.title') });
}

test.describe.serial('M-01 allowance flags (plan 16d-2)', () => {
  // SC-16-0096 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'SRS-1-11: establishment proposes the NPA flag and the payroll checker approves it on W-01',
    { tag: ['@SRS-1-11', '@SRS-1-4', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const { eis, value, from } = next();
      const reason = `E2E NPA ${value} from ${from}`;
      const page = await browser.newPage();
      await workAs(page, 'en', HR, hr, 'M-01');
      const flags = await openFlags(page, eis);
      await flags.getByRole('button', { name: t('en', 'm01.flags.change', [NPA]) }).click();
      const form = page.getByRole('form', { name: t('en', 'm01.flags.change', [NPA]) });
      await form.getByLabel(t('en', 'm01.flags.requested')).selectOption(value);
      await form.getByLabel(t('en', 'm01.flags.from')).fill(from);
      await form.getByLabel(t('en', 'common.reason')).fill(reason);
      await fillOrder(page, form);
      await form.getByRole('button', { name: t('en', 'm01.flags.submit') }).click();
      await expect(page.getByRole('status').filter({ hasText: NPA })).toBeVisible();
      await expect(flags.getByRole('row', { name: new RegExp(NPA) })).toContainText(
        t('en', 'm01.flags.pending'),
      );

      const ddo = await browser.newPage();
      await workAs(
        ddo,
        'en',
        'ddo.delhi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'DH001' },
        'W-01',
      );
      await decide(ddo, new RegExp(`NPA [YN] -> ${value} from ${from}`), 'APPROVE');

      await page.reload();
      await openFlags(page, eis);
      await expect(page.getByRole('table', { name: t('en', 'm01.flags.history') })).toContainText(reason);
    },
  );

  test(
    'SRS-1-11: the payroll maker sees the flags without a change action',
    { tag: ['@SRS-1-11', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      const flags = await openFlags(page, next().eis);
      await expect(flags.getByRole('row', { name: new RegExp(NPA) })).toBeVisible();
      await expect(flags.getByRole('button', { name: t('en', 'm01.flags.change', [NPA]) })).toHaveCount(0);
    },
  );
});
