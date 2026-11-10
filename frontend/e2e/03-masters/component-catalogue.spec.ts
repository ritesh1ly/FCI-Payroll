import { expect, type Page, test } from '@playwright/test';
import { apiProblem, apiStatus, businessToday, decide, errorAlert, t, workAs } from '../support/app';
import { apiAll, apiJson } from '../support/payroll-support';

/**
 * M-07 Component Catalogue through the UI (docs/plan/06-rule-engine.md phase 06d; SRS-19-1,
 * SRS-G-42, FAP-14): the payroll roles read or see nothing; the Rule Administrator proposes a new
 * component that the Rule Approver rejects in W-01 (so the code stays free and the run repeats); a
 * revision of the demo code 912 approved in W-01, where its use by the penalty recovery list shows;
 * a future retirement refused.
 *
 * Re-runnable on one stack: a pay code is never reused, so the new code is only ever rejected; the
 * revision of 912 restates its values with a run-stamped reason; changes an interrupted run left
 * pending are rejected first.
 */
const RULE_ADMIN = 'su.hq@fci-demo.test';
const RULE_APPROVER = 'rule.approver@fci-demo.test';
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const DELHI_MAKER = 'maker.delhi@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const ruleApprover = { role: 'RULE_APPROVER', office: 'HQ001' };
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const delhiMaker = { role: 'PAYROLL_MAKER', office: 'DH001' };

const NEW_CODE = '998';
const DEMO_CODE = '912';
const stamp = Date.now();

/** Rejects the M-07 changes an interrupted earlier run left pending (one open change per code). */
async function rejectLeftovers(page: Page) {
  const inbox = await apiAll<{ approvalId: number; entityName: string; entityKey: string }>(
    page,
    '/v1/inbox',
  );
  for (const item of inbox.filter(
    (i) => i.entityName === 'pay_component' && [NEW_CODE, DEMO_CODE].includes(i.entityKey),
  )) {
    await apiJson(page, `/v1/approvals/${item.approvalId}/decision`, 'POST', {
      decision: 'REJECT',
      reason: 'E2E: left over by an earlier run',
    });
  }
}

/** The catalogue narrowed to the demo deductions, where 912 is on the first page. */
async function demoDeductions(page: Page) {
  await page.getByRole('combobox', { name: t('en', 'm07w.allComponents') }).selectOption('DEDUCTION');
  await page.getByText(t('en', 'wf.searchDisclosure'), { exact: true }).click();
  await page.locator('select[aria-label="' + t('en', 'm07.source') + '"]').selectOption('RULE');
  const table = page.getByRole('table', { name: t('en', 'm07w.staff') });
  await expect(table.getByRole('button', { name: DEMO_CODE, exact: true })).toBeVisible();
  return table;
}

test.describe.serial('M-07 Component Catalogue', () => {
  test(
    'a Payroll Maker reads the catalogue (SRS l.5435, OD-125) and proposes nothing; the Payroll Checker reads only; tampered codes never reach the service',
    { tag: ['@SRS-19-1', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-07');
      await expect(page.getByRole('table', { name: t('en', 'm07w.staff') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'm07w.new') })).toBeDisabled();
      expect(await apiStatus(page, '/v1/pay-components')).toBe(200);
      expect(await apiStatus(page, `/v1/pay-components/${DEMO_CODE}/usages`)).toBe(200);
      await workAs(page, 'en', HQ_CHECKER, hqChecker, 'M-07');
      await expect(page.getByRole('table', { name: t('en', 'm07w.staff') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'm07w.new') })).toBeDisabled();
      expect(
        await apiStatus(page, `/v1/pay-components/${DEMO_CODE}/retirements`, 'POST', {
          lastDay: businessToday(),
          reason: 'E2E',
        }),
      ).toBe(403);
      for (const tampered of ['0', '1000', 'abc', '..%2Fetc']) {
        expect([400, 404]).toContain(await apiStatus(page, `/v1/pay-components/${tampered}/usages`));
      }
    },
  );

  test(
    'a new component is proposed with its payroll types and rejected by the Rule Approver, so the code stays free',
    { tag: ['@SRS-G-42', '@SRS-19-1', '@positive'] },
    async ({ page }) => {
      const reason = `E2E new component ${stamp}`;
      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
      await rejectLeftovers(page);
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-07');
      await page.getByRole('button', { name: t('en', 'm07w.new') }).click();
      const send = page.getByRole('button', { name: t('en', 'm07w.save') });
      await expect(send).toBeDisabled();
      await page.locator('#m07-payCode').fill(NEW_CODE);
      await page.locator('#m07-name').fill('E2E washing allowance');
      await page.locator('#m07-basisRuleKey').fill('COMP.BASIS.FLAT');
      await page.locator('#m07-cpf').selectOption('N');
      await page.getByTestId('m07-more-fields').locator('summary').click();
      await page.locator('#m07-type').selectOption('EARNING');
      await page.locator('#m07-source').selectOption('RULE');
      await page.locator('#m07-naturalAccount').fill('NA-E2E-998');
      await page.locator('#m07-taxable').selectOption('Y');
      await page.getByRole('checkbox', { name: t('en', 'm07.payrollType.DPS_LABOUR') }).check();
      await page.locator('#m07-from').fill(businessToday());
      await page.locator('#m07-reason').fill(reason);
      await expect(send).toBeEnabled();
      await send.click();
      await expect(page.getByText(t('en', 'm07.sent'))).toBeVisible();
      // One open change per code: a second, otherwise valid definition (the Driver / Basis is mandatory,
      // SRS l.1907) is held by the pending one.
      const again = await apiProblem(page, '/v1/pay-components', 'POST', {
        payCode: Number(NEW_CODE),
        name: 'E2E again',
        type: 'EARNING',
        basisRuleKey: 'COMP.BASIS.FLAT',
        source: 'RULE',
        naturalAccount: 'NA-E2E-998',
        taxable: 'Y',
        inCpfBase: false,
        payrollTypes: ['DPS_LABOUR'],
        effectiveFrom: businessToday(),
        reason: 'E2E again',
      });
      expect(again).toEqual({ status: 409, errorCode: 'MST-409-COMPONENT-PENDING' });
      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
      await decide(page, new RegExp(reason), 'REJECT', 'E2E: proving the route only');
      const left = await apiAll<{ payCode: number }>(page, '/v1/pay-components?type=EARNING&source=RULE');
      expect(left.map((c) => c.payCode)).not.toContain(Number(NEW_CODE));
    },
  );

  // SC-06-0020 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'a revision of the demo code is approved in W-01; its use by the penalty recovery list shows',
    { tag: ['@SRS-G-42', '@FAP-14', '@positive'] },
    async ({ page }) => {
      const reason = `E2E revision ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-07');
      const table = await demoDeductions(page);
      await table.getByRole('button', { name: DEMO_CODE, exact: true }).click();
      const panel = page.getByRole('region', { name: t('en', 'm07.detail', [DEMO_CODE]) });
      await expect(panel.getByRole('list', { name: t('en', 'm07.whereUsed') })).toContainText(
        'PENALTY_RECOVERY',
      );
      await panel.getByRole('button', { name: t('en', 'm07.revise') }).click();
      await expect(page.locator('#m07-payCode')).toBeDisabled();
      await page.locator('#m07-naturalAccount').fill('NA-DEMO-912');
      await page.getByRole('checkbox', { name: t('en', 'm07.payrollType.STAFF') }).check();
      await page.locator('#m07-from').fill('2026-04-01');
      await page.locator('#m07-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'm07w.save') }).click();
      await expect(page.getByText(t('en', 'm07.sent'))).toBeVisible();
      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-07');
      const after = await demoDeductions(page);
      const row = after
        .getByRole('row')
        .filter({ has: page.getByRole('button', { name: DEMO_CODE, exact: true }) });
      await expect(row).toContainText(t('en', 'm07.active'));
      // The drawn Staff table has no payroll-type column (OD-121): the added type shows on the row's
      // detail and is stored (SRS l.1884, DDL pay_component_engine).
      await after.getByRole('button', { name: DEMO_CODE, exact: true }).click();
      await expect(page.getByRole('region', { name: t('en', 'm07.detail', [DEMO_CODE]) })).toContainText(
        t('en', 'm07.payrollType.STAFF'),
      );
      const stored = await apiAll<{ payCode: number; payrollTypes: string[] }>(
        page,
        '/v1/pay-components?type=DEDUCTION',
      );
      expect(stored.find((c) => c.payCode === Number(DEMO_CODE))?.payrollTypes).toContain('STAFF');
    },
  );

  test(
    'a revision may not drop a payroll type and a future retirement is refused',
    { tag: ['@FAP-14', '@SRS-G-42', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-07');
      const table = await demoDeductions(page);
      await table.getByRole('button', { name: DEMO_CODE, exact: true }).click();
      const panel = page.getByRole('region', { name: t('en', 'm07.detail', [DEMO_CODE]) });
      await panel.getByRole('button', { name: t('en', 'm07.revise') }).click();
      await page.getByRole('checkbox', { name: t('en', 'm07.payrollType.STAFF') }).uncheck();
      await page.locator('#m07-from').fill('2026-04-01');
      await page.locator('#m07-reason').fill('E2E drop');
      await expect(page.getByRole('list', { name: t('en', 'm07.problems') })).toContainText(
        t('en', 'm07.bad.removeType'),
      );
      await expect(page.getByRole('button', { name: t('en', 'm07w.save') })).toBeDisabled();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      await panel.getByLabel(t('en', 'm07.lastDay')).fill(businessToday(30));
      await panel.getByLabel(t('en', 'm07.reason')).fill('E2E future');
      await panel.getByRole('button', { name: t('en', 'action.retire') }).click();
      await expect(errorAlert(page)).toBeVisible();
      const listed = await apiAll<{ payCode: number; retired: boolean }>(
        page,
        '/v1/pay-components?type=DEDUCTION',
      );
      expect(listed.find((c) => c.payCode === Number(DEMO_CODE))?.retired).toBe(false);
    },
  );
  test(
    'SRS-LV-15 (US-GAP-298): "Subject to LWP" is a catalogue attribute - Basic and DA marked, a deduction not',
    { tag: ['@SRS-LV-15', '@BR-LEAVE-007', '@US-GAP-298', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-07');
      const table = page.getByRole('table', { name: t('en', 'm07w.staff') });
      await page.getByRole('combobox', { name: t('en', 'm07w.allComponents') }).selectOption('EARNING');
      // Demo seed V077_9 marks Basic (101) and DA (111), as the M-20 demo parameter used to.
      for (const code of ['101', '111']) {
        await table.getByRole('button', { name: code, exact: true }).click();
        await expect(page.getByRole('region', { name: t('en', 'm07.detail', [code]) })).toContainText(
          `${t('en', 'm07.subjectToLwp')}: Y`,
        );
      }
      await table.getByRole('button', { name: '101', exact: true }).click();
      const panel = page.getByRole('region', { name: t('en', 'm07.detail', ['101']) });
      await panel.getByRole('button', { name: t('en', 'm07.revise') }).click();
      await expect(page.getByRole('checkbox', { name: t('en', 'm07.subjectToLwp') })).toBeChecked();
      await page
        .getByRole('button', { name: t('en', 'common.cancel') })
        .first()
        .click();

      const deductions = await demoDeductions(page);
      await deductions.getByRole('button', { name: DEMO_CODE, exact: true }).click();
      await expect(page.getByRole('region', { name: t('en', 'm07.detail', [DEMO_CODE]) })).toContainText(
        `${t('en', 'm07.subjectToLwp')}: N`,
      );
      // The M-20 leave parameters no longer carry the list (BR-LEAVE-007: an M-07 attribute).
      const components = await apiAll<{ payCode: number; subjectToLwp: boolean }>(page, '/v1/pay-components');
      expect(components.filter((c) => c.subjectToLwp).map((c) => c.payCode)).toEqual(
        expect.arrayContaining([101, 111]),
      );
    },
  );
});
