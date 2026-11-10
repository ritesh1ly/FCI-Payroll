import { expect, type Page, test } from '@playwright/test';
import { apiProblem, decide, errorAlert, pressDecision, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { apiJson, expectToast, toastNumber } from '../support/payroll-support';

/**
 * The CPF withdrawal chain through the UI (docs/plan/07-cpf.md phase 07f-2; SRS-6-4, UC-05): the
 * member raises a request on E-01 with its checklist document; the Unit CPF Administrator verifies
 * it on I-04; the North Zone CPF Division recommends it at the capped amount; the CPF Sanctioning
 * Authority sanctions it with OTP in the Approvals Inbox; the sanction number shows on both screens.
 *
 * The MGW basis (OQ-3) is not configured, so the run's own purpose takes the one limit the seeds
 * can compute (a fraction of the balance), approved on M-19 by two different users. The member's
 * balance is a ledger year of its own (9998-99, later than any other spec's) through the database
 * fixture; an advance left open by an earlier run is marked paid first, so the suite is re-runnable.
 */
const MEMBER = 'emp.rsharma@fci-demo.test';
const UNIT = 'cpf.unit.delhi@fci-demo.test';
const DIVISION = 'cpf.north@fci-demo.test';
const AUTHORITY = 'cpf.sanction.north@fci-demo.test';
// An eSeva session has no office (the member's own record is its scope).
const member = { role: 'EMPLOYEE_ESEVA' };
const unit = { role: 'UNIT_CPF_ADMIN', office: 'DH001' };
const division = { role: 'CPF_DIVISION', office: 'NA11' };
const authority = { role: 'CPF_SANCTIONING_AUTHORITY', office: 'NA11' };
const EIS = '104882';
const RUN = Date.now();
const PURPOSE = `E2E_ADV_${RUN}`;
const MEMBER_ID = `(SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id = e.parent_zone_id WHERE e.emp_num = '${EIS}' AND z.zone_code = 'NA11')`;

/** A balance the spec controls and no earlier advance in the way. */
function prepareMember() {
  sql(
    `INSERT INTO employee_pf_header (employee_id, fin_year, zone_id, opening_balance_employee, opening_balance_employer, opening_balance_vpf, closing_balance_employee, closing_balance_employer, closing_balance_vpf, opening_balance_source) SELECT ${MEMBER_ID}, '9998-99', (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'), 10000, 6000, 2000, 10000, 6000, 2000, 'MIGRATION' FROM dual WHERE NOT EXISTS (SELECT 1 FROM employee_pf_header WHERE employee_id = ${MEMBER_ID} AND fin_year = '9998-99')`,
  );
  sql(
    `UPDATE pf_sanction SET workflow_stage = 'PAID' WHERE employee_id = ${MEMBER_ID} AND sanction_type = 'ADVANCE' AND workflow_stage IN ('UNIT_VERIFICATION','ZONAL_HQ_VALIDATION','COMPETENT_AUTHORITY','SANCTIONED','PAYMENT_BLOCKED')`,
  );
}

/** The run's purpose, proposed by the Rule Administrator and approved by a Rule Approver (M-19). */
async function approvedPurpose(page: Page) {
  await workAs(page, 'en', 'su.hq@fci-demo.test', { role: 'RULE_ADMINISTRATOR', office: 'HQ001' }, 'M-19');
  const today = new Date(Date.now() + 5.5 * 3_600_000).toISOString().slice(0, 10);
  const proposed = await apiJson<{ approvalId: number }>(page, '/v1/cpf/withdrawal-purposes', 'POST', {
    code: PURPOSE,
    name: `E2E advance ${RUN}`,
    category: 'ADVANCE',
    limitRuleKey: 'CPF.LIMIT.BALANCE.BEFORE_RETIREMENT',
    noCasteDispute: false,
    propertyDetails: false,
    effectiveFrom: today,
    confirmed: true,
    documents: [{ documentType: 'CERTIFICATE', mandatory: true, tier: 'UNIT_CPF_ADMIN' }],
    reason: 'E2E: purpose for the sanction chain',
  });
  await workAs(page, 'en', 'rule.approver@fci-demo.test', { role: 'RULE_APPROVER', office: 'HQ001' }, 'W-01');
  await decide(page, new RegExp(PURPOSE), 'APPROVE');
  return proposed.approvalId;
}

/** Fills the member's request on the E-01 card, up to (not including) the submission. */
async function fill(page: Page, amount: string) {
  await workAs(page, 'en', MEMBER, member, 'E-01');
  await page.getByRole('button', { name: t('en', 'e01s.new') }).click();
  await page.getByLabel(t('en', 'i04.purpose')).selectOption(PURPOSE);
  await page.getByLabel(t('en', 'i04.requested')).fill(amount);
  const submit = page.getByRole('button', { name: t('en', 'e01s.submit') });
  // SRS-6-14: without the checklist document the request cannot go.
  await expect(page.getByText(t('en', 'e01s.problem.documents', ['CERTIFICATE']))).toBeVisible();
  await expect(submit).toBeDisabled();
  await page
    .getByLabel(t('en', 'ord.file'), { exact: true })
    .first()
    .setInputFiles({
      name: 'marriage-card.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.7 e2e certificate'),
    });
  await page.getByLabel(t('en', 'ord.date')).fill('2026-09-01');
  await page.getByLabel(t('en', 'ord.authority')).fill('Registrar, Delhi');
}

/** The member's request through the E-01 card; returns its number. */
/**
 * This run's sanction in W-01: the I-04 row whose key (approval_queue.entity_key) is the sanction id. The screen
 * name alone also matches the sanctions an earlier test or run left waiting, and the first of those may not be
 * this user's to decide (full run 2026-11-08: no Approve in its detail).
 */
function sanctionRow(id: string): RegExp {
  const screen = t('en', 'screen.I-04').replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`^(?=[\\s\\S]*${screen})(?=[\\s\\S]*\\b${id}\\b)`);
}

async function raise(page: Page, amount: string): Promise<string> {
  await fill(page, amount);
  await page.getByRole('button', { name: t('en', 'e01s.submit') }).click();
  return toastNumber(page, 'e01s.submitted');
}

async function openOnI04(page: Page, id: string) {
  await page.getByRole('button', { name: t('en', 'i04.openOf', [id]) }).click();
  await expect(
    page.getByRole('heading', { name: new RegExp(`^${t('en', 'i04.requestOf', [id, '']).trim()}`) }),
  ).toBeVisible();
}

/** Tier 2: the recommendation carries its reason (the I04_SUBMIT approval's, 10 characters or more). */
async function recommend(page: Page, reason: string) {
  await page.getByRole('button', { name: t('en', 'i04.recommend') }).click();
  await page.getByRole('dialog').getByRole('textbox').fill(reason);
  await page
    .getByRole('dialog')
    .getByRole('button', { name: t('en', 'i04.recommend') })
    .click();
}

test.describe.serial('I-04 / E-01 CPF withdrawal chain', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test(
    'SRS-6-4: the member raises, the unit verifies, the CPF Division recommends at the cap, the Competent Authority sanctions with OTP',
    {
      tag: [
        '@SRS-6-4',
        '@SRS-6-14',
        '@SRS-6-26',
        '@SRS-6-38',
        '@SC-07-0004',
        '@SC-07-0017',
        '@SC-07-0027',
        '@SC-07-0083',
        '@SC-07-0084',
        '@SC-07-0093',
        '@SC-07-0095',
        '@SC-07-0096',
        '@SC-07-0097',
        '@SC-07-0098',
        '@SC-07-0099',
        '@SC-07-0100',
        '@SC-07-0042',
        '@SC-07-0049',
        '@SC-07-0002',
        '@SC-07-0005',
        '@SC-07-0015',
        '@positive',
      ],
    },
    async ({ page }) => {
      prepareMember();
      await approvedPurpose(page);
      const id = await raise(page, '50000');

      await workAs(page, 'en', UNIT, unit, 'I-04');
      await openOnI04(page, id);
      // Tier 1 verifies the UAN and the KYC (SRS I-04 field table); the Aadhaar shows its last four
      // digits only (SRS-6-51), never in full (seed V036_9: UAN 100000104882, Aadhaar 999900104886).
      await expect(page.getByText('100000104882')).toBeVisible();
      await expect(page.getByText('XXXX-XXXX-4886')).toBeVisible();
      await expect(page.locator('main')).not.toContainText('999900104886');
      await page.getByRole('button', { name: t('en', 'i04.forward') }).click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.ZONAL_HQ_VALIDATION')));

      await workAs(page, 'en', DIVISION, division, 'I-04');
      await openOnI04(page, id);
      await recommend(page, 'Eligible; capped at the ceiling');
      await expectToast(page, new RegExp(t('en', 'i04.stage.COMPETENT_AUTHORITY')));
      // SRS-6-26: above the ceiling the amount is capped, not refused.
      await expect(page.locator(`tr[data-reference="${id}"]`)).toContainText(t('en', 'i04.capped'));

      await workAs(page, 'en', AUTHORITY, authority, 'W-01');
      await decide(page, sanctionRow(id), 'APPROVE', '', 'CPF_SANCTION_FINAL_PAYMENT');

      await workAs(page, 'en', MEMBER, member, 'E-01');
      const mine = page
        .getByRole('table', { name: t('en', 'e01s.mine') })
        .locator(`tr[data-reference="${id}"]`);
      await expect(mine).toContainText(t('en', 'i04.stage.SANCTIONED'));
      await expect(mine).toContainText(/\d{2}\/\d{4}\/NA11\/\d{5}/);
      // US-GAP-166 (SRS-6-26): the member is told the amount was capped at the limit.
      await expect(mine).toContainText(t('en', 'e01s.capped'));
    },
  );

  test(
    'SRS-6-37: no second advance while one is sanctioned and unpaid; the member withdraws a request before the sanction',
    { tag: ['@SC-07-0042', '@SRS-6-37', '@SRS-12-9', '@SC-07-0026', '@SC-07-0025', '@negative'] },
    async ({ page }) => {
      // The advance of the previous test is sanctioned and not yet paid: a second one is refused.
      await fill(page, '100');
      await page.getByRole('button', { name: t('en', 'e01s.submit') }).click();
      await expect(errorAlert(page)).toContainText(t('en', 'error.CPF-409-ACTIVE-ADVANCE'));
      // Once it is paid, a new advance may be raised - and withdrawn before its sanction.
      prepareMember();
      const id = await raise(page, '100');
      const row = page
        .getByRole('table', { name: t('en', 'e01s.mine') })
        .locator(`tr[data-reference="${id}"]`);
      await row.getByRole('button', { name: t('en', 'e01s.withdraw') }).click();
      await page.getByRole('dialog').getByRole('textbox').fill('Raised in error');
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'e01s.withdraw') })
        .click();
      await expect(row).toContainText(t('en', 'i04.stage.CANCELLED'));
      await expect(row.getByRole('button')).toHaveCount(0);
    },
  );

  test(
    'SRS-12-9: the unit returns a request to the member with the reason; the member corrects and resubmits it',
    { tag: ['@SRS-12-9', '@SRS-6-4', '@SC-07-0086', '@edge'] },
    async ({ page }) => {
      prepareMember();
      const id = await raise(page, '2000');
      await workAs(page, 'en', UNIT, unit, 'I-04');
      await openOnI04(page, id);
      await page.getByRole('button', { name: t('en', 'i04.returnToMember') }).click();
      await page.getByRole('dialog').getByRole('textbox').fill('Certificate unreadable');
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'i04.return') })
        .click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.RETURNED')));

      await workAs(page, 'en', MEMBER, member, 'E-01');
      const row = page
        .getByRole('table', { name: t('en', 'e01s.mine') })
        .locator(`tr[data-reference="${id}"]`);
      await expect(row).toContainText('Certificate unreadable');
      await row.getByRole('button', { name: t('en', 'e01s.resubmit') }).click();
      await page.getByLabel(t('en', 'i04.requested')).last().fill('1500');
      await page
        .getByRole('button', { name: t('en', 'e01s.resubmit') })
        .last()
        .click();
      await expect(row).toContainText(t('en', 'i04.stage.UNIT_VERIFICATION'));
    },
  );

  test(
    'D-15: a purpose whose limit needs the MGW basis stops at tier 2 as not configured; the unit holds no tier-2 action',
    {
      tag: [
        '@SC-07-0002',
        '@SC-07-0015',
        '@SRS-6-23',
        '@SRS-6-2',
        '@SC-07-0087',
        '@SC-07-0016',
        '@SC-07-0028',
        '@negative',
      ],
    },
    async ({ page }) => {
      prepareMember();
      // The seeded Marriage purpose takes 12 x MGW, which is not configured (OQ-3).
      await workAs(page, 'en', MEMBER, member, 'E-01');
      const purposes = await apiJson<{ code: string; documents: unknown[] }[]>(
        page,
        '/v1/cpf/sanctions/purposes',
      );
      test.skip(
        !purposes.some((p) => p.code === 'MARRIAGE'),
        'the Marriage purpose is not in force on this stack',
      );
      await page.getByRole('button', { name: t('en', 'e01s.new') }).click();
      await page.getByLabel(t('en', 'i04.purpose')).selectOption('MARRIAGE');
      await page.getByLabel(t('en', 'i04.requested')).fill('100');
      // The seeded purposes carry no checklist yet (Q-074): the request goes without documents.
      const submit = page.getByRole('button', { name: t('en', 'e01s.submit') });
      await submit.click();
      const id = await toastNumber(page, 'e01s.submitted');

      await workAs(page, 'en', UNIT, unit, 'I-04');
      await openOnI04(page, id);
      await expect(page.getByRole('button', { name: t('en', 'i04.recommend') })).toHaveCount(0);
      await page.getByRole('button', { name: t('en', 'i04.forward') }).click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.ZONAL_HQ_VALIDATION')));

      await workAs(page, 'en', DIVISION, division, 'I-04');
      await openOnI04(page, id);
      await recommend(page, 'Eligible; capped at the ceiling');
      // UI audit S46: the refusal is shown in the dialog, which keeps the typed reason; then closed.
      await expect(page.getByRole('dialog').locator('.fl-error[role="alert"]')).toContainText(
        t('en', 'error.CPF-422-LIMIT-NOT-CONFIGURED', ['CPF.MGW']),
      );
      await expect(page.getByRole('dialog').getByRole('textbox')).toHaveValue(
        'Eligible; capped at the ceiling',
      );
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.cancel') })
        .click();
      // Hand it back to the unit with the reason rather than leaving it waiting.
      await page.getByRole('button', { name: t('en', 'i04.returnToUnit') }).click();
      await page.getByRole('dialog').getByRole('textbox').fill('Waiting for the MGW basis');
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'i04.return') })
        .click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.UNIT_VERIFICATION')));
    },
  );

  test(
    'SRS-6-4: a sanction the balance can no longer carry at tier 3 is numbered but its payment is blocked for review',
    { tag: ['@SRS-6-4', '@SRS-6-57', '@SC-07-0090', '@edge'] },
    async ({ page }) => {
      prepareMember();
      // 3000 is within the ceiling (0.5 x (employee + VPF) = 6000) when tier 2 recommends it.
      const id = await raise(page, '3000');
      await workAs(page, 'en', UNIT, unit, 'I-04');
      await openOnI04(page, id);
      await page.getByRole('button', { name: t('en', 'i04.forward') }).click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.ZONAL_HQ_VALIDATION')));
      await workAs(page, 'en', DIVISION, division, 'I-04');
      await openOnI04(page, id);
      await recommend(page, 'Eligible within the ceiling');
      await expectToast(page, new RegExp(t('en', 'i04.stage.COMPETENT_AUTHORITY')));
      // The balance falls (another posting) before the Competent Authority decides.
      const balance = (employee: number, employer: number, vpf: number) =>
        sql(
          `UPDATE employee_pf_header SET closing_balance_employee = ${employee}, closing_balance_employer = ${employer}, closing_balance_vpf = ${vpf} WHERE employee_id = ${MEMBER_ID} AND fin_year = '9998-99'`,
        );
      balance(100, 50, 0);
      try {
        await workAs(page, 'en', AUTHORITY, authority, 'W-01');
        await decide(page, sanctionRow(id), 'APPROVE', '', 'CPF_SANCTION_FINAL_PAYMENT');
        await workAs(page, 'en', DIVISION, division, 'I-04');
        await openOnI04(page, id);
        await expect(page.getByText(t('en', 'i04.blockedHint'))).toBeVisible();
        const row = page.locator(`tr[data-reference="${id}"]`);
        await expect(row).toContainText(t('en', 'i04.stage.PAYMENT_BLOCKED'));
        await expect(row).toContainText(/\d{2}\/\d{4}\/NA11\/\d{5}/);
      } finally {
        balance(10000, 6000, 2000);
      }
    },
  );

  test(
    'BR-CPF-039: a house purpose asks for the property; without it the form will not send and a forged request is refused',
    { tag: ['@BR-CPF-039', '@SRS-6-39', '@SC-07-0094', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MEMBER, member, 'E-01');
      const purposes = await apiJson<{ code: string; propertyDetails: boolean }[]>(
        page,
        '/v1/cpf/sanctions/purposes',
      );
      const house = purposes.find((p) => p.propertyDetails);
      test.skip(!house, 'no purpose in force asks for property details on this stack');
      const code = (house as { code: string }).code;
      await page.getByRole('button', { name: t('en', 'e01s.new') }).click();
      await page.getByLabel(t('en', 'i04.purpose')).selectOption(code);
      await page.getByLabel(t('en', 'i04.requested')).fill('90000');
      const problem = page.getByText(t('en', 'e01s.problem.property'));
      await expect(problem).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'e01s.submit') })).toBeDisabled();
      await page.getByLabel(t('en', 'e01s.address')).fill('Plot 4, Sector 9, Delhi');
      await expect(problem).toBeVisible();
      await page.getByLabel(t('en', 'e01s.description')).fill('Two rooms, ground floor');
      await expect(problem).toHaveCount(0);
      // The server refuses the same request without the property, whatever the screen says.
      const refused = await page.evaluate(async (purpose) => {
        const token =
          document.cookie
            .split('; ')
            .find((c) => c.startsWith('XSRF-TOKEN='))
            ?.split('=')[1] ?? '';
        const form = new FormData();
        form.append(
          'request',
          new Blob([JSON.stringify({ purposeCode: purpose, amount: '90000', documents: [] })], {
            type: 'application/json',
          }),
        );
        const response = await fetch('/nginx/fci-payroll/v1/cpf/sanctions', {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'X-XSRF-TOKEN': token, 'Idempotency-Key': crypto.randomUUID() },
          body: form,
        });
        return { status: response.status, body: (await response.json()) as { errorCode?: string } };
      }, code);
      expect(refused.status).toBe(422);
      expect(refused.body.errorCode).toBe('CPF-422-PROPERTY');
    },
  );
  test(
    'SRS-6-14: a request carries at most five documents; a sixth is refused naming the limit, even forged',
    { tag: ['@SRS-6-14', '@SC-07-0097', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MEMBER, member, 'E-01');
      const refused = await page.evaluate(async (purpose) => {
        const token =
          document.cookie
            .split('; ')
            .find((c) => c.startsWith('XSRF-TOKEN='))
            ?.split('=')[1] ?? '';
        const form = new FormData();
        const documents = Array.from({ length: 6 }, (_, i) => ({
          documentType: 'CERTIFICATE',
          reference: `CERT/E2E/${i}`,
          documentDate: '2026-09-01',
          issuingAuthority: 'Registrar, Delhi',
        }));
        form.append(
          'request',
          new Blob([JSON.stringify({ purposeCode: purpose, amount: '100', documents })], {
            type: 'application/json',
          }),
        );
        for (let i = 0; i < 6; i++) {
          form.append('file', new Blob(['%PDF-1.7 e2e'], { type: 'application/pdf' }), `cert-${i}.pdf`);
        }
        const response = await fetch('/nginx/fci-payroll/v1/cpf/sanctions', {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'X-XSRF-TOKEN': token, 'Idempotency-Key': crypto.randomUUID() },
          body: form,
        });
        return {
          status: response.status,
          body: (await response.json()) as { errorCode?: string; args?: unknown[] },
        };
      }, PURPOSE);
      expect(refused.status).toBe(422);
      expect(refused.body.errorCode).toBe('CPF-422-DOCUMENT-COUNT');
      // Problem arguments travel as text (ProblemDetailFactory), as every message argument does.
      expect(refused.body.args).toEqual(['5']);
    },
  );

  test(
    'US-GAP-164: tier 2 rejects with the reason the member sees on E-01; the unit has no rejection (CPF-422-TIER1-REJECT)',
    { tag: ['@US-GAP-164', '@US-GAP-165', '@SRS-12-9', '@SRS-6-4', '@negative'] },
    async ({ page }) => {
      prepareMember();
      const id = await raise(page, '2500');
      await workAs(page, 'en', UNIT, unit, 'I-04');
      await openOnI04(page, id);
      // Tier 1 forwards or returns to the member; it never rejects - neither on screen nor forged.
      await expect(page.getByRole('button', { name: t('en', 'i04.reject'), exact: true })).toHaveCount(0);
      expect(
        await apiProblem(page, `/v1/cpf/sanctions/${id}/verify`, 'POST', {
          decision: 'REJECT',
          remark: 'Not eligible (E2E forged)',
        }),
      ).toEqual({ status: 422, errorCode: 'CPF-422-TIER1-REJECT' });
      await page.getByRole('button', { name: t('en', 'i04.forward') }).click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.ZONAL_HQ_VALIDATION')));

      await workAs(page, 'en', DIVISION, division, 'I-04');
      await openOnI04(page, id);
      await page.getByRole('button', { name: t('en', 'i04.reject'), exact: true }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toContainText(t('en', 'i04.rejectTitle', [id]));
      // The reason is mandatory: without it the rejection cannot be confirmed.
      const confirm = dialog.getByRole('button', { name: t('en', 'i04.reject'), exact: true });
      await expect(confirm).toBeDisabled();
      await dialog.getByRole('textbox').fill('Service of less than five years');
      await confirm.click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.REJECTED')));

      await workAs(page, 'en', MEMBER, member, 'E-01');
      const row = page
        .getByRole('table', { name: t('en', 'e01s.mine') })
        .locator(`tr[data-reference="${id}"]`);
      await expect(row).toContainText(t('en', 'i04.stage.REJECTED'));
      await expect(row).toContainText('Service of less than five years');
      // A rejected request is closed: nothing left to resubmit or withdraw.
      await expect(row.getByRole('button', { name: t('en', 'e01s.resubmit') })).toHaveCount(0);
    },
  );

  test(
    'SRS-6-31 / SRS-G-21: a deputed member applies through the same three tiers; tier 3 without the OTP leaves it with the Competent Authority',
    { tag: ['@SRS-6-31', '@BR-CPF-024', '@SRS-G-21', '@edge'] },
    async ({ page }) => {
      prepareMember();
      const order = `E2E/DEP/${RUN}`;
      sql(
        `INSERT INTO employee_lifecycle_event (employee_id, event_type, event_date, reason, order_reference, event_source, created_by) SELECT ${MEMBER_ID}, 'DEPUTATION_OUT', DATE '${new Date(Date.now() + 5.5 * 3_600_000 - 40 * 86_400_000).toISOString().slice(0, 10)}', 'E2E deputation', '${order}', 'HRMS', 'seed.sysadmin' FROM dual WHERE NOT EXISTS (SELECT 1 FROM employee_lifecycle_event WHERE order_reference = '${order}')`,
      );
      try {
        const purposes = await (async () => {
          await workAs(page, 'en', MEMBER, member, 'E-01');
          return apiJson<{ code: string }[]>(page, '/v1/cpf/sanctions/purposes');
        })();
        if (!purposes.some((x) => x.code === PURPOSE)) await approvedPurpose(page);
        const id = await raise(page, '100');

        await workAs(page, 'en', UNIT, unit, 'I-04');
        await openOnI04(page, id);
        await page.getByRole('button', { name: t('en', 'i04.forward') }).click();
        await expectToast(page, new RegExp(t('en', 'i04.stage.ZONAL_HQ_VALIDATION')));
        await workAs(page, 'en', DIVISION, division, 'I-04');
        await openOnI04(page, id);
        await recommend(page, 'Eligible within the ceiling (deputed)');
        await expectToast(page, new RegExp(t('en', 'i04.stage.COMPETENT_AUTHORITY')));

        await workAs(page, 'en', AUTHORITY, authority, 'W-01');
        const item = page.getByRole('row', { name: sanctionRow(id) }).first();
        await (
          await pressDecision(page, item, 'APPROVE')
        )
          .getByRole('button', { name: t('en', 'w01.APPROVE') })
          .click();
        await expect(page.getByRole('textbox', { name: t('en', 'otp.code') })).toBeVisible();
        await page
          .getByRole('button', { name: t('en', 'common.cancel') })
          .last()
          .click();
        await workAs(page, 'en', DIVISION, division, 'I-04');
        await openOnI04(page, id);
        await expect(page.getByText(t('en', 'i04.tier3Hint'))).toBeVisible();

        await workAs(page, 'en', AUTHORITY, authority, 'W-01');
        await decide(page, sanctionRow(id), 'APPROVE', '', 'CPF_SANCTION_FINAL_PAYMENT');
        await workAs(page, 'en', MEMBER, member, 'E-01');
        await expect(
          page.getByRole('table', { name: t('en', 'e01s.mine') }).locator(`tr[data-reference="${id}"]`),
        ).toContainText(t('en', 'i04.stage.SANCTIONED'));
      } finally {
        sql(`DELETE FROM employee_lifecycle_event WHERE order_reference = '${order}'`);
        prepareMember();
      }
    },
  );
});
