import { type Browser, expect, type Page, test } from '@playwright/test';
import {
  apiProblem,
  businessToday,
  DEMO_PASSWORD,
  decide,
  enterCredentials,
  errorAlert,
  loginOtpField,
  onSomePage,
  openLogin,
  otpFromLog,
  pickerMonth,
  pressDecision,
  signIn,
  t,
  workAs,
} from '../support/app';
import {
  TEST_CHECKER,
  TEST_MAKER,
  TEST_OFFICE,
  testChecker,
  testEis,
  testMaker,
} from '../support/cycle-office';
import { dbFixtureAvailable, lockPriorForFixture, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { receivedTask, sendTaskOrder } from '../support/hrms-tasks';
import {
  apiAll,
  apiJson,
  currentCycle,
  expectToast,
  fillDrawnOrder,
  fillOrder,
  openCycleActions,
  PDF,
  toastNumber,
  viewRecord,
} from '../support/payroll-support';

/**
 * The OTP-gated actions of X-08 (SRS-G-21, SRS-G-3, FRS 6.4.2.5) that menus 1-10 reach, one test per
 * action, through the real screen. Each proves four things: without the code the server answers 428
 * and nothing changes; a wrong code is refused, nothing changes and the dialog stays for another try;
 * the code sent to the acting user completes it, by the role named on the route; a role not named
 * cannot complete it. The record is read from the database (a read-only oracle) around each step.
 * The drawn no-OTP cases stay OTP-free (I-21 date of birth, I-21 CPF parent zone: BR-CPF-040).
 */
const RUN = Date.now();

/** YYYYMM, {@code n} months after today. */
function monthsAhead(n: number): string {
  const today = businessToday();
  const d = new Date(Date.UTC(Number(today.slice(0, 4)), Number(today.slice(5, 7)) - 1 + n, 1));
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}
const ERROR_OTP = 'error.AUTH-400-OTP';

const otpField = (page: Page) => page.getByRole('textbox', { name: t('en', 'otp.code') });
const verifyButton = (page: Page) => page.getByRole('button', { name: t('en', 'otp.verify') });
const status = (page: Page, code: number) => page.waitForResponse((r) => r.status() === code);
const wrongOf = (code: string) => String((Number(code) + 1) % 1_000_000).padStart(6, '0');
const rows = (query: string) => JSON.stringify(sqlRows(query));

/** The newest item of a screen its maker sent and that still waits. */
function pendingItem(moduleCode: string, submittedBy: string): string {
  const id = sqlRows(
    `SELECT MAX(approval_id) FROM approval_queue WHERE module_code = '${moduleCode}' AND status = 'PENDING_APPROVAL' AND submitted_by = '${submittedBy}'`,
  )[0]?.[0];
  expect(id, `pending ${moduleCode} of ${submittedBy}`).toBeTruthy();
  return id as string;
}

const queued = (id: string) =>
  `SELECT status, current_level, decided_by FROM approval_queue WHERE approval_id = ${id}`;

/**
 * W-01: Approve asks the OTP (428), a wrong code is refused, the right one completes it. The record
 * read by {@code record} must not move before the right code.
 */
async function approveThroughTheGate(
  page: Page,
  rowName: RegExp,
  purpose: string,
  record: string,
  outcome: 'APPROVED' | 'PENDING_APPROVAL' = 'APPROVED',
) {
  const before = rows(record);
  const item = await onSomePage(page, page.getByRole('row', { name: rowName }).first());
  const dialog = await pressDecision(page, item, 'APPROVE');
  const since = Date.now();
  const asked = status(page, 428);
  await dialog.getByRole('button', { name: t('en', 'w01.APPROVE') }).click();
  await asked;
  await expect(otpField(page)).toBeVisible();
  expect(rows(record), 'nothing changes without the code').toBe(before);

  const code = await otpFromLog(purpose, since);
  await otpField(page).fill(wrongOf(code));
  const refused = status(page, 400);
  await verifyButton(page).click();
  await refused;
  await expect(page.getByRole('alert').filter({ hasText: t('en', ERROR_OTP) })).toBeVisible();
  await expect(otpField(page), 'the dialog stays open for another try').toBeVisible();
  expect(rows(record), 'nothing changes with a wrong code').toBe(before);

  await otpField(page).fill(code);
  await verifyButton(page).click();
  await expect(page.getByRole('status')).toContainText(t('en', `status.${outcome}`));
  await expect.poll(() => rows(record)).not.toBe(before);
}

/** A role the route does not name, on the same item: refused before any code is asked. */
async function refusedToRole(
  browser: Browser,
  login: string,
  scope: { role: string; office: string },
  id: string,
  record: string,
  screen = 'W-01',
) {
  const page = await browser.newPage();
  const before = rows(record);
  await workAs(page, 'en', login, scope, screen);
  const answer = await apiProblem(page, `/v1/approvals/${id}/decision`, 'POST', { decision: 'APPROVE' });
  expect(answer.status, `${scope.role} on approval ${id}: ${answer.errorCode}`).toBeGreaterThanOrEqual(403);
  expect(answer.status).toBeLessThan(428);
  expect(rows(record)).toBe(before);
  await page.close();
}

/** A fresh active account with the demo password (own-password-otp.spec.ts). */
function freshAccount(kind: string): string {
  const login = `e2e.${kind}.${RUN}@fci-demo.test`;
  const source = `(SELECT user_id FROM app_user WHERE login_id = 'rbac.reports.punjab@fci-demo.test')`;
  const self = `(SELECT user_id FROM app_user WHERE login_id = '${login}')`;
  sql(
    [
      `INSERT INTO app_user (login_id, display_name, user_category, registered_mobile, registered_email, user_status) VALUES ('${login}', 'E2E OTP', 'OPERATIONS', '9000199998', '${login}', 'ACTIVE')`,
      `INSERT INTO app_user_credential (user_id, password_hash, hash_algorithm, must_change_flag) SELECT ${self}, password_hash, hash_algorithm, 'N' FROM app_user_credential WHERE user_id = ${source}`,
      `INSERT INTO app_user_preference (user_id, preferred_locale) VALUES (${self}, 'en')`,
      `INSERT INTO app_user_access_group (user_id, access_group_id, effective_from, assigned_by, approval_id) SELECT ${self}, access_group_id, effective_from, 'e2e.fixture', approval_id FROM app_user_access_group WHERE user_id = ${source} AND revoked_at IS NULL`,
    ].join(';\n'),
  );
  return login;
}

// I-21 ------------------------------------------------------------------------------------------
const MAKER = 'maker.delhi@fci-demo.test';
const CHECKER = 'ddo.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };
// Delhi has 33 active members; the HRMS specs use 8-10 and 20-29.
const hrmsOffset = { n: 10 };

/** A task on a member of its own (the offset), so no two tests of a run meet. */
function received(field: string, value: string): string {
  hrmsOffset.n += 1;
  return receivedTask(field, value, hrmsOffset.n);
}

const task = (taskNo: string) => `SELECT task_status FROM hrms_change_task WHERE task_no = '${taskNo}'`;

test.describe('X-08 OTP-gated actions in scope (SRS-G-21, FRS 6.4.2.5)', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test(
    'SRS-G-21 #1 AUTH_LOGIN: the password alone opens nothing, a wrong code is refused, the right one opens the workspace',
    { tag: ['@SRS-G-21', '@SRS-G-48', '@SRS-G-3', '@negative'] },
    async ({ page }) => {
      const login = freshAccount('login');
      await openLogin(page, 'en');
      const since = Date.now();
      await enterCredentials(page, 'en', login);
      const field = loginOtpField(page, 'en');
      await expect(field).toBeVisible();
      expect((await page.request.get('/nginx/fci-payroll/auth/me')).status(), 'no session yet').toBe(401);
      const code = await otpFromLog('AUTH_LOGIN', since);
      await field.fill(wrongOf(code));
      await page.getByRole('button', { name: t('en', 'login.otp.verify'), exact: true }).click();
      await expect(errorAlert(page)).toHaveText(t('en', ERROR_OTP));
      expect((await page.request.get('/nginx/fci-payroll/auth/me')).status(), 'still no session').toBe(401);
      await field.fill(code);
      await page.getByRole('button', { name: t('en', 'login.otp.verify'), exact: true }).click();
      await expect(page).toHaveURL(/\/payroll/);
      expect((await page.request.get('/nginx/fci-payroll/auth/me')).status()).toBe(200);
    },
  );

  test(
    'SRS-G-21 #2 AUTH_OWN_PASSWORD_CHANGE: refused without and with a wrong code; the right code changes only the own password',
    { tag: ['@SRS-G-21', '@SRS-G-48', '@US-GAP-128', '@negative'] },
    async ({ page }) => {
      const login = freshAccount('pwd');
      await signIn(page, 'en', login);
      await expect(page).toHaveURL(/\/payroll/);
      const hash = `SELECT c.password_hash FROM app_user_credential c JOIN app_user u ON u.user_id = c.user_id WHERE u.login_id = '${login}'`;
      const before = rows(hash);
      await page.getByRole('button', { name: t('en', 'account.menu') }).click();
      await page.getByRole('menuitem', { name: t('en', 'account.changePassword') }).click();
      const next = 'Example#Pass5678';
      await page.getByLabel(t('en', 'password.current'), { exact: true }).fill(DEMO_PASSWORD);
      await page.getByLabel(t('en', 'password.new'), { exact: true }).fill(next);
      await page.getByLabel(t('en', 'password.confirm'), { exact: true }).fill(next);
      const since = Date.now();
      const asked = status(page, 428);
      await page.getByRole('button', { name: t('en', 'password.submit') }).click();
      await asked;
      await expect(otpField(page)).toBeVisible();
      expect(rows(hash)).toBe(before);
      const code = await otpFromLog('AUTH_OWN_PASSWORD_CHANGE', since);
      await otpField(page).fill(wrongOf(code));
      const refused = status(page, 400);
      await verifyButton(page).click();
      await refused;
      expect(rows(hash)).toBe(before);
      await otpField(page).fill(code);
      await verifyButton(page).click();
      await expect(page.getByText(t('en', 'password.changed'))).toBeVisible();
      expect(rows(hash)).not.toBe(before);
    },
  );

  test(
    'SRS-G-21 #3 CYCLE_FREEZE_LOCK: the Maker freezes the cycle only with the code; D-01 shows it frozen and the lock stays off',
    { tag: ['@SRS-G-21', '@SRS-15-32', '@BR-CYC-008', '@negative'] },
    async ({ page }) => {
      // A unit no other spec runs a cycle in: a freeze is forward-only for the rest of the stack's life.
      const free = ['KL001'].find(
        (unit) =>
          sqlRows(
            `SELECT COUNT(*) FROM payroll_cycle_instance c JOIN org_location l ON l.location_id = c.org_unit_id WHERE l.location_code = '${unit}' AND c.cycle_status IN ('FROZEN','COMPUTED','VERIFIED','LOCKED') AND c.payroll_yymm = TO_CHAR(CURRENT_DATE, 'YYYYMM')`,
          )[0]?.[0] === '0',
      );
      test.skip(
        free === undefined,
        'The spare unit is past INITIATED on this stack: redeploy for a fresh run.',
      );
      const UNIT = free as string;
      const LOGIN = `pm.${UNIT.toLowerCase()}@fci-demo.test`;
      const OFFICE = { role: 'PAYROLL_MAKER', office: UNIT };
      const record = `SELECT MAX(c.cycle_status) FROM payroll_cycle_instance c JOIN org_location l ON l.location_id = c.org_unit_id WHERE l.location_code = '${UNIT}' AND c.payroll_yymm = TO_CHAR(CURRENT_DATE, 'YYYYMM')`;
      await workAs(page, 'en', LOGIN, OFFICE, 'D-01');
      await expect(page.locator('.pband')).toBeVisible();
      await workAs(page, 'en', LOGIN, OFFICE, 'C-02');
      await openCycleActions(page);
      if (sqlRows(record)[0]?.[0] !== 'INITIATED') {
        lockPriorForFixture((await currentCycle(page)).period, UNIT);
        await page.getByRole('button', { name: t('en', 'cyc.action.initiate') }).click();
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.initiate'));
      }
      expect(sqlRows(record)[0]?.[0]).toBe('INITIATED');
      const since = Date.now();
      const asked = status(page, 428);
      await page.getByRole('button', { name: t('en', 'cyc.action.freeze') }).click();
      await asked;
      await expect(otpField(page)).toBeVisible();
      expect(sqlRows(record)[0]?.[0]).toBe('INITIATED');
      const code = await otpFromLog('CYCLE_FREEZE_LOCK', since);
      await otpField(page).fill(wrongOf(code));
      const refused = status(page, 400);
      await verifyButton(page).click();
      await refused;
      expect(sqlRows(record)[0]?.[0]).toBe('INITIATED');
      await otpField(page).fill(code);
      await verifyButton(page).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.freeze'));
      expect(sqlRows(record)[0]?.[0]).toBe('FROZEN');
      await workAs(page, 'en', LOGIN, OFFICE, 'D-01');
      await expect(page.locator('.pband')).toContainText(t('en', 'd01.state.FROZEN'));
      await expect(page.getByRole('button', { name: t('en', 'd01.lock') })).toBeDisabled();
    },
  );

  test(
    'SRS-G-21 #5 INCREMENT_DATE_BASIC_CORRECTION: a Basic Pay change on I-21 needs the Checker code; the Maker cannot approve it',
    { tag: ['@SRS-G-21', '@SRS-1-61', '@negative'] },
    async ({ browser }) => {
      for (const [field, value] of [
        ['BASIC_PAY', '61900'],
        ['INCREMENT_DATE', '2026-12-01'],
      ] as const) {
        const taskNo = received(field, value);
        const page = await browser.newPage();
        await workAs(page, 'en', MAKER, maker, 'I-21');
        await sendTaskOrder(page, taskNo);
        const id = pendingItem('I-21', MAKER);
        await refusedToRole(browser, MAKER, maker, id, task(taskNo), 'I-21');
        const ddo = await browser.newPage();
        await workAs(ddo, 'en', CHECKER, checker, 'W-01');
        await approveThroughTheGate(ddo, new RegExp(taskNo), 'INCREMENT_DATE_BASIC_CORRECTION', task(taskNo));
        expect(sqlRows(task(taskNo))[0]?.[0]).toBe('APPROVED');
        await ddo.close();
        await page.close();
      }
    },
  );

  test(
    'SRS-G-21: the drawn no-OTP cases stay OTP-free: I-21 date of birth and the CPF parent zone task (BR-CPF-040)',
    { tag: ['@SRS-G-21', '@BR-CPF-040', '@SRS-1-28', '@positive'] },
    async ({ browser }) => {
      const dob = received('DATE_OF_BIRTH', '1980-05-20');
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await sendTaskOrder(page, dob);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(dob), 'APPROVE');
      await expect(otpField(ddo)).toHaveCount(0);
      expect(sqlRows(task(dob))[0]?.[0]).toBe('APPROVED');
      await ddo.close();

      // The CPF parent-zone task asks no OTP (BR-CPF-040); the Maker cannot send it yet: its apply waits
      // for the interest method (PENDING 07k-blocked, Q-072), and the order form says so.
      const zone = received('CPF_PARENT_ZONE', 'NA11');
      expect(sqlRows(`SELECT otp_required FROM hrms_change_task WHERE task_no = '${zone}'`)[0]?.[0]).toBe(
        'N',
      );
      await page.reload();
      const view = await onSomePage(
        page,
        page.getByRole('button', { name: t('en', 'i21.w.viewOf', [zone]) }),
      );
      await view.click();
      const send = page
        .getByRole('form', { name: t('en', 'i21.order') })
        .getByRole('button', { name: t('en', 'i21.submit') });
      await expect(send).toBeDisabled();
      await expect(send).toHaveAttribute('title', t('en', 'i21.why.notApplicable'));
      await page.close();
    },
  );

  test(
    'SRS-G-21 #9 CPF_SANCTION_FINAL_PAYMENT: tier 3 on I-04 sanctions only with the Competent Authority code; the CPF Division cannot',
    { tag: ['@SRS-G-21', '@SRS-6-4', '@SRS-6-31', '@negative'] },
    async ({ browser, page }) => {
      const eis = '104882';
      const memberId = `(SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id = e.parent_zone_id WHERE e.emp_num = '${eis}' AND z.zone_code = 'NA11')`;
      const unit = { role: 'UNIT_CPF_ADMIN', office: 'DH001' };
      const division = { role: 'CPF_DIVISION', office: 'NA11' };
      const authority = { role: 'CPF_SANCTIONING_AUTHORITY', office: 'NA11' };
      const purpose = `E2E_OTP_${RUN}`;
      const year = '9997-98';
      const close = `UPDATE pf_sanction SET workflow_stage = 'PAID' WHERE employee_id = ${memberId} AND sanction_type = 'ADVANCE' AND workflow_stage IN ('UNIT_VERIFICATION','ZONAL_HQ_VALIDATION','COMPETENT_AUTHORITY','SANCTIONED','PAYMENT_BLOCKED')`;
      sql(
        `INSERT INTO employee_pf_header (employee_id, fin_year, zone_id, opening_balance_employee, opening_balance_employer, opening_balance_vpf, closing_balance_employee, closing_balance_employer, closing_balance_vpf, opening_balance_source) SELECT ${memberId}, '${year}', (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'), 10000, 6000, 2000, 10000, 6000, 2000, 'MIGRATION' FROM dual WHERE NOT EXISTS (SELECT 1 FROM employee_pf_header WHERE employee_id = ${memberId} AND fin_year = '${year}')`,
      );
      sql(close);
      await workAs(
        page,
        'en',
        'su.hq@fci-demo.test',
        { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
        'M-19',
      );
      await apiJson(page, '/v1/cpf/withdrawal-purposes', 'POST', {
        code: purpose,
        name: `E2E advance ${RUN}`,
        category: 'ADVANCE',
        limitRuleKey: 'CPF.LIMIT.BALANCE.BEFORE_RETIREMENT',
        noCasteDispute: false,
        propertyDetails: false,
        effectiveFrom: businessToday(),
        confirmed: true,
        documents: [{ documentType: 'CERTIFICATE', mandatory: true, tier: 'UNIT_CPF_ADMIN' }],
        reason: 'E2E: purpose for the OTP gate',
      });
      await workAs(
        page,
        'en',
        'rule.approver@fci-demo.test',
        { role: 'RULE_APPROVER', office: 'HQ001' },
        'W-01',
      );
      await decide(page, new RegExp(purpose), 'APPROVE');

      await workAs(page, 'en', 'emp.rsharma@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      await page.getByRole('button', { name: t('en', 'e01s.new') }).click();
      await page.getByLabel(t('en', 'i04.purpose')).selectOption(purpose);
      await page.getByLabel(t('en', 'i04.requested')).fill('100');
      await page
        .getByLabel(t('en', 'ord.file'), { exact: true })
        .first()
        .setInputFiles({
          name: 'card.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('%PDF-1.7 e2e'),
        });
      await page.getByLabel(t('en', 'ord.date')).fill('2026-09-01');
      await page.getByLabel(t('en', 'ord.authority')).fill('Registrar, Delhi');
      await page.getByRole('button', { name: t('en', 'e01s.submit') }).click();
      const id = await toastNumber(page, 'e01s.submitted');

      const open = async (who: string, scope: { role: string; office: string }) => {
        await workAs(page, 'en', who, scope, 'I-04');
        await page.getByRole('button', { name: t('en', 'i04.openOf', [id]) }).click();
      };
      await open('cpf.unit.delhi@fci-demo.test', unit);
      await page.getByRole('button', { name: t('en', 'i04.forward') }).click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.ZONAL_HQ_VALIDATION')));
      await open('cpf.north@fci-demo.test', division);
      await page.getByRole('button', { name: t('en', 'i04.recommend') }).click();
      await page.getByRole('dialog').getByRole('textbox').fill('Eligible within the ceiling (OTP gate)');
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'i04.recommend') })
        .click();
      await expectToast(page, new RegExp(t('en', 'i04.stage.COMPETENT_AUTHORITY')));

      const record = `SELECT workflow_stage FROM pf_sanction WHERE sanction_id = ${id}`;
      const approval = pendingItem('I-04', 'cpf.north@fci-demo.test');
      await refusedToRole(browser, 'cpf.north@fci-demo.test', division, approval, record, 'I-04');
      await workAs(page, 'en', 'cpf.sanction.north@fci-demo.test', authority, 'W-01');
      await approveThroughTheGate(
        page,
        new RegExp(t('en', 'screen.I-04')),
        'CPF_SANCTION_FINAL_PAYMENT',
        record,
      );
      expect(sqlRows(record)[0]?.[0]).toBe('SANCTIONED');
      sql(close);
    },
  );

  test(
    'SRS-G-21 #10 CPF_OPENING_BALANCE_CORRECTION: AGM (CPF) and the HQ CPF approver each need their own code on I-22; the Manager cannot approve',
    { tag: ['@SRS-G-21', '@SRS-6-43', '@BR-CPF-027', '@negative'] },
    async ({ browser, page }) => {
      const eis = '104882';
      const manager = { role: 'CPF_MANAGER', office: 'NA11' };
      const MANAGER = 'cpf.manager.north@fci-demo.test';
      const base = 3101 + (Math.floor(RUN / 60_000) % 2_600);
      const day = `${base}-04-01`;
      const year = `${base}-${String((base + 1) % 100).padStart(2, '0')}`;
      const member = `(SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id = e.parent_zone_id WHERE e.emp_num = '${eis}' AND z.zone_code = 'NA11')`;
      sql(
        `INSERT INTO employee_pf_header (employee_id, fin_year, zone_id, opening_balance_employee, opening_balance_employer, opening_balance_vpf, closing_balance_employee, closing_balance_employer, closing_balance_vpf, opening_balance_source) SELECT ${member}, '${year}', (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'), 1000, 500, 0, 1000, 500, 0, 'MIGRATION' FROM dual WHERE NOT EXISTS (SELECT 1 FROM employee_pf_header WHERE employee_id = ${member} AND fin_year = '${year}')`,
      );
      await workAs(page, 'en', MANAGER, manager, 'I-22');
      await page.getByLabel(t('en', 'i22.by.eis')).fill(eis);
      await page.getByLabel(t('en', 'i22.asAt')).fill(day);
      await page.getByRole('button', { name: t('en', 'i22.show') }).click();
      await page.getByRole('button', { name: t('en', 'action.newCorrection') }).click();
      await page.getByLabel(t('en', 'i22.correctedOf', [t('en', 'i22.part.EMPLOYEE')])).fill('1100.00');
      await page.getByLabel(t('en', 'i22.reason')).selectOption('CLERICAL_ERROR');
      await page.getByLabel(t('en', 'i22.interestDate')).fill(day);
      await page.getByLabel(t('en', 'i22.remarks')).fill(`E2E ${RUN} OTP gate`);
      await fillOrder(page);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      const ref = /OB-\d{4}-\d{6}/.exec(await expectToast(page, /OB-\d{4}-\d{6}/))?.[0] ?? '';
      const id = pendingItem('I-22', MANAGER);
      const record = queued(id);
      await refusedToRole(browser, MANAGER, manager, id, record, 'I-22');
      await workAs(page, 'en', 'agm.cpf.north@fci-demo.test', { role: 'AGM_CPF', office: 'NA11' }, 'W-01');
      await approveThroughTheGate(
        page,
        new RegExp(ref),
        'CPF_OPENING_BALANCE_CORRECTION',
        record,
        'PENDING_APPROVAL',
      );
      expect(sqlRows(queued(id))[0]?.[0]).toBe('PENDING_APPROVAL');
      await workAs(
        page,
        'en',
        'cpf.approver.hq@fci-demo.test',
        { role: 'HQ_CPF_APPROVER', office: 'HQ001' },
        'W-01',
      );
      await approveThroughTheGate(page, new RegExp(ref), 'CPF_OPENING_BALANCE_CORRECTION', record);
      await workAs(page, 'en', MANAGER, manager, 'I-22');
      await expect(page.locator(`tr[data-reference="${ref}"]`)).toContainText(t('en', 'i22.status.APPLIED'));
    },
  );

  test(
    'SRS-G-21 #11 WAGE_REVISION_HQ: the HQ Checker approves I-25 only with the code; the Rule Administrator cannot',
    { tag: ['@SRS-G-21', '@SRS-8-25', '@negative'] },
    async ({ browser, page }) => {
      const remark = `E2E OTP wage revision ${RUN}`;
      const ADMIN = 'su.hq@fci-demo.test';
      const admin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
      const d = new Date();
      d.setDate(1);
      d.setMonth(d.getMonth() + 3);
      const from = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
      await workAs(page, 'en', ADMIN, admin, 'I-25');
      await page.getByRole('button', { name: t('en', 'i25.act.define') }).click();
      await page.getByLabel(t('en', 'i25.name')).fill(`OTP revision (E2E ${RUN})`);
      await page
        .getByLabel(t('en', 'i25.order'))
        .first()
        .selectOption({ label: 'DEMO/WR/I25-01 - Wage revision (demo order for I-25)' });
      await page.getByLabel(t('en', 'i25.type.STAFF_IDA')).check();
      await page.getByLabel(t('en', 'i25.effective')).first().fill(from);
      await page.getByLabel(t('en', 'i25.fitment')).first().selectOption('PERCENTAGE');
      await page.getByLabel(t('en', 'i25.value')).fill('3');
      await page.getByLabel(t('en', 'i25.rounding')).first().selectOption('NEXT_10');
      await page.getByLabel(t('en', 'common.reason')).first().fill('Pay revision order (E2E)');
      await page.getByRole('button', { name: t('en', 'action.i25.define'), exact: true }).click();
      await expect(page.getByRole('status').filter({ hasText: t('en', 'i25.defined') })).toBeVisible();
      await page.getByRole('button', { name: t('en', 'action.i25.simulate') }).click();
      await expect(page.getByRole('status').filter({ hasText: t('en', 'i25.simulated') })).toBeVisible();
      await page.getByLabel(t('en', 'common.reason')).last().fill(remark);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status').filter({ hasText: t('en', 'i25.submitted') })).toBeVisible();
      const id = pendingItem('I-25', ADMIN);
      await refusedToRole(browser, ADMIN, admin, id, queued(id), 'I-25');
      await workAs(
        page,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'W-01',
      );
      await approveThroughTheGate(page, new RegExp(remark), 'WAGE_REVISION_HQ', queued(id));
    },
  );

  test(
    'SRS-G-21 #12 UNDO_TRANSACTION: route C02_UNDO on I-08: the Checker passes it on without a code and the Administrator authorises it only with the code; the Maker cannot',
    { tag: ['@SRS-G-21', '@SRS-21-6', '@SRS-15-3', '@negative'] },
    async ({ browser, page }) => {
      const MK = 'pm.ropb@fci-demo.test';
      const mk = { role: 'PAYROLL_MAKER', office: 'RO-PB' };
      const reference = `E2E-OTP-UNDO-${RUN}`;
      const unit = `(SELECT location_id FROM org_location WHERE location_code = 'RO-PB')`;
      const month = `(SELECT payroll_yymm FROM payroll_cycle_instance WHERE org_unit_id = ${unit} AND cycle_status = 'INITIATED' AND initiated_by = 'seed.demo')`;
      sql(
        [
          `INSERT INTO arrear_event (event_type_id, event_reference, effective_from, scope_payroll_type, scope_location_id, scope_from_yymm, source_module, event_status, posted_to_yymm, raised_by) SELECT t.event_type_id, '${reference}', DATE '2026-05-01', 'STAFF', ${unit}, '202605', 'RATE_CHANGE', 'APPROVED', ${month}, 'e2e.fixture' FROM arrear_event_type t WHERE t.event_code = 'DA_REVISION'`,
          `INSERT INTO arrear_event_member (arrear_event_id, employee_id) SELECT e.arrear_event_id, m.employee_id FROM arrear_event e, employee m WHERE e.event_reference = '${reference}' AND m.emp_num = '119901'`,
          `INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id, differential_yymm, original_amount, recomputed_amount, arrear_amount, arrear_status, period_date) SELECT e.arrear_event_id, m.employee_id, c.component_id, '202605', 10000, 10500, 500, 'APPROVED', DATE '2026-05-01' FROM arrear_event e, employee m, pay_component c WHERE e.event_reference = '${reference}' AND m.emp_num = '119901' AND c.legacy_pay_code = 111`,
        ].join(';\n'),
      );
      await workAs(page, 'en', MK, mk, 'I-08');
      await page.getByLabel(t('en', 'page.size')).first().selectOption('100');
      const event = await onSomePage(
        page,
        page
          .getByRole('table', { name: t('en', 'i08.registerTitle') })
          .locator(`tr[data-reference="${reference}"]`),
      );
      // As drawn, Undo event acts on the selected row (OD-121).
      await event.locator('td').first().click();
      await page.getByRole('button', { name: t('en', 'action.i08.undo') }).click();
      const dialog = page.getByRole('dialog', { name: t('en', 'i08.undo.title', [reference]) });
      await dialog.getByLabel(t('en', 'common.reason')).fill(`E2E ${RUN}: DA order withdrawn`);
      await dialog.getByRole('button', { name: t('en', 'action.i08.undo') }).click();
      await expect(page.getByRole('status').filter({ hasText: /Undo requested/ })).toContainText(
        /Undo requested \(approval \d+\)/,
      );
      const eventId = sqlRows(
        `SELECT arrear_event_id FROM arrear_event WHERE event_reference = '${reference}'`,
      )[0]?.[0];
      const id = sqlRows(
        `SELECT MAX(approval_id) FROM approval_queue WHERE entity_key = 'arrear_event:${eventId}'`,
      )[0]?.[0] as string;
      const record = `SELECT event_status FROM arrear_event WHERE event_reference = '${reference}'`;
      await refusedToRole(browser, MK, mk, id, queued(id), 'I-08');
      expect(sqlRows(record)[0]?.[0]).toBe('APPROVED');
      // W-01 lists the item with the Maker's reason.
      const item = new RegExp(`E2E ${RUN}: DA order withdrawn`);
      await workAs(page, 'en', 'pc.ropb@fci-demo.test', { role: 'PAYROLL_CHECKER', office: 'RO-PB' }, 'W-01');
      await decide(page, item, 'APPROVE', '', undefined, 'PENDING_APPROVAL');
      await workAs(
        page,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'W-01',
      );
      await approveThroughTheGate(page, item, 'UNDO_TRANSACTION', queued(id));
      expect(sqlRows(record)[0]?.[0]).not.toBe('APPROVED');
    },
  );

  test(
    'SRS-G-21 #14 SUPPLEMENTARY_POST_LOCK: the Checker approves I-23 only with the code; the Maker cannot',
    { tag: ['@SRS-G-21', '@SRS-G-33', '@SRS-G-34', '@negative'] },
    async ({ browser, page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-23');
      const cycle = await currentCycle(page);
      lockPriorForFixture(cycle.period, 'DH001');
      const locked = sqlRows(
        `SELECT MAX(c.payroll_yymm) FROM payroll_cycle_instance c JOIN org_location l ON l.location_id = c.org_unit_id WHERE l.location_code = 'DH001' AND c.cycle_status = 'LOCKED' AND c.payroll_yymm < '${cycle.yymm}'`,
      )[0]?.[0] as string;
      await page.getByRole('button', { name: t('en', 'i23.new') }).click();
      await page.getByLabel(t('en', 'gen.eis')).fill('107304');
      await page.getByLabel(t('en', 'i23.forPeriod')).selectOption(locked);
      await page.getByLabel(t('en', 'i23.applicationDate')).fill(businessToday());
      await fillDrawnOrder(page);
      await page.getByRole('button', { name: t('en', 'i23.record') }).click();
      const reference = /SUP-\d{8}/.exec(await expectToast(page, /SUP-\d{8}/))?.[0] ?? '';
      const paymentId = Number(reference.slice(4));
      // D-5, PENDING DB-X3: the statutory rules are not configured, so only a RECOVERY can be submitted, and
      // only the salary type can be raised on the screen. The fixture turns this request into a recovery of
      // an excess (SRS-G-33); everything from the submission on is the real flow.
      sql(
        `UPDATE supplementary_payment SET payment_type = 'RECOVERY', amount = -500, duplicate_check_status = 'PASSED', duplicate_of_payment_id = NULL WHERE payment_id = ${paymentId}`,
      );
      await page.reload();
      const view = await viewRecord(page, reference);
      await view.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expectToast(page, t('en', 'i23.submitted', [reference]));
      const record = `SELECT payment_status FROM supplementary_payment WHERE payment_id = ${paymentId}`;
      const id = pendingItem('I-23', MAKER);
      await refusedToRole(browser, MAKER, maker, id, record, 'I-23');
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await approveThroughTheGate(ddo, new RegExp(`\\b${id}\\b`), 'SUPPLEMENTARY_POST_LOCK', record);
      expect(sqlRows(record)[0]?.[0]).toBe('APPROVED');
      await ddo.close();
    },
  );

  test(
    'SRS-G-21 #15 LOAN_FORECLOSURE: the Checker approves a foreclosure on I-04 only with the code; the Maker cannot',
    { tag: ['@SRS-G-21', '@SRS-12-21', '@BR-LOAN-011', '@negative'] },
    async ({ browser, page }) => {
      const [eis] = sqlRows(
        `SELECT e.emp_num FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.employment_status = 'IN_SERVICE' AND e.record_status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM loan_header h WHERE h.employee_id = e.employee_id) ORDER BY e.emp_num DESC OFFSET 12 ROWS FETCH NEXT 1 ROWS ONLY`,
      )[0] as [string];
      const self = `(SELECT employee_id FROM employee WHERE emp_num = '${eis}')`;
      const unit = `(SELECT location_id FROM org_location WHERE location_code = 'DH001')`;
      sql(
        [
          `INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level, fin_year, cycle_status, initiated_by) SELECT '199803', ${unit}, 'DO', '1997-98', 'COMPUTED', 'e2e.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = '199803' AND org_unit_id = ${unit})`,
          `INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id, payroll_type, payroll_yymm, period_date, run_type, gross_earnings, total_deductions, net_pay, result_status, is_locked) SELECT (SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '199803' AND org_unit_id = ${unit}), ${unit}, ${self}, 'STAFF', '199803', DATE '1998-03-01', 'ACTUAL', 40000, 5000, 35000, 'CURRENT', 'Y' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_result WHERE employee_id = ${self} AND payroll_yymm = '199803')`,
        ].join(';\n'),
      );
      await workAs(page, 'en', MAKER, maker, 'I-04');
      const register = () => page.locator('details[data-testid="i04-register"]');
      const openRegister = async () => {
        if (!(await register().evaluate((d) => (d as HTMLDetailsElement).open)))
          await register().locator('summary').first().click();
      };
      const form = page.locator('.card').filter({ hasText: t('en', 'i04l.newSanction') });
      await form.getByLabel(t('en', 'm01.eis')).fill(eis);
      await form.getByLabel(t('en', 'i04l.type')).selectOption('FESTIVAL_ADVANCE');
      await form.getByLabel(t('en', 'i04l.sanctioned')).fill('20000');
      await form.getByLabel(t('en', 'i04l.instalments')).fill('4');
      await form.getByLabel(t('en', 'i04l.instalment'), { exact: true }).fill('5000');
      await form.getByLabel(t('en', 'i04l.start')).fill(pickerMonth(monthsAhead(1)));
      await form.getByRole('button', { name: t('en', 'i04l.check') }).click();
      await expect(
        form.getByRole('table', { name: t('en', 'i04l.schedule') }).locator('tbody tr'),
      ).toHaveCount(4);
      await fillOrder(page, form);
      await form.getByRole('button', { name: t('en', 'i04l.sendSanction') }).click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`FESTIVAL_ADVANCE of 20000 for ${eis}`), 'APPROVE');

      await page.reload();
      await openRegister();
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      const loans = page.getByRole('table', { name: t('en', 'i04l.loans') });
      const loanNumber = /LN-\d{4}-\d{6}/.exec((await loans.textContent()) ?? '')?.[0] ?? '';
      await loans.getByRole('button', { name: t('en', 'i04l.openLoan', [loanNumber]) }).click();
      for (const prefix of ['fc', 'fco']) {
        await page.locator(`#${prefix}-doctype`).selectOption('OTHER');
        await page.locator(`#${prefix}-docdate`).fill(businessToday());
        await page.locator(`#${prefix}-docauth`).fill('AGM (Pay) Delhi');
        await page.locator(`#${prefix}-docfile`).setInputFiles(PDF);
      }
      await page
        .getByRole('group', { name: t('en', 'i04l.foreclose') })
        .getByRole('button', { name: t('en', 'i04l.sendForeclosure') })
        .click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));
      const record = `SELECT h.loan_status FROM loan_header h WHERE h.loan_number = '${loanNumber}'`;
      const id = pendingItem('I-04', MAKER);
      await refusedToRole(browser, MAKER, maker, id, record, 'I-04');
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await approveThroughTheGate(
        ddo,
        new RegExp(`Foreclosure of ${loanNumber}`),
        'LOAN_FORECLOSURE',
        record,
      );
      expect(sqlRows(record)[0]?.[0]).toBe('FORECLOSED');
    },
  );

  test.fixme(
    'SRS-G-21 #16 PLI_ADJUSTMENT on C-02: no flow',
    { tag: ['@SRS-G-21', '@negative'] },
    async () => {
      // No screen raises a PLI adjustment and no route carries PLI_ADJUSTMENT: nothing to drive.
    },
  );

  test(
    'SRS-G-21 #17 ROLE_ACCESS_GROUP_CHANGE: an access change of X-01 (A-04) needs the Administrator code, then the Checker code; the Administrator cannot approve it',
    { tag: ['@SRS-G-21', '@SRS-22-23', '@negative'] },
    async ({ browser, page }) => {
      const ADMIN = 'sysadmin@fci-demo.test';
      const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
      const from = new Date(Date.now() + (10 + (RUN % 300)) * 86_400_000).toISOString().slice(0, 10);
      const assigned = `SELECT COUNT(*) FROM approval_queue WHERE module_code = 'A-04' AND submitted_by = '${ADMIN}'`;
      const before = rows(assigned);
      await workAs(page, 'en', ADMIN, admin, 'A-04');
      await page.getByRole('button', { name: t('en', 'action.newAssignment') }).click();
      await page.getByLabel(t('en', 'a04.findUser')).fill('su.pension');
      const user = page.getByLabel(t('en', 'a04.user'), { exact: true });
      await user.selectOption({ label: 'su.pension@fci-demo.test — Kavita Joshi' });
      await page
        .getByLabel(t('en', 'a04.group'))
        .selectOption({ label: 'AG-FIN-MR001 — Finance - Mumbai Regional' });
      await page.getByLabel(t('en', 'a04.from'), { exact: true }).fill(from);
      await page.getByLabel(t('en', 'common.reason')).fill('Finance cover, OTP gate');
      const since = Date.now();
      const asked = status(page, 428);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await asked;
      await expect(otpField(page)).toBeVisible();
      expect(rows(assigned), 'not submitted without the code').toBe(before);
      const code = await otpFromLog('ROLE_ACCESS_GROUP_CHANGE', since);
      await otpField(page).fill(wrongOf(code));
      const refused = status(page, 400);
      await verifyButton(page).click();
      await refused;
      expect(rows(assigned), 'not submitted with a wrong code').toBe(before);
      await otpField(page).fill(code);
      await verifyButton(page).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'a04.submitted'));
      const id = pendingItem('A-04', ADMIN);
      await refusedToRole(browser, ADMIN, admin, id, queued(id), 'A-04');
      const decider = await browser.newPage();
      await workAs(
        decider,
        'en',
        'tax.checker.west@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'ZO-W' },
        'W-01',
      );
      await approveThroughTheGate(
        decider,
        /su.pension@fci-demo.test\|AG-FIN-MR001/,
        'ROLE_ACCESS_GROUP_CHANGE',
        queued(id),
      );
    },
  );

  test(
    'SRS-G-21 #18 HRMS_FALLBACK_UPLOAD_APPROVAL: the unit Checker approves an X-07 upload only with the code; the Maker cannot',
    { tag: ['@SRS-G-21', '@SRS-G-47', '@SRS-G-3', '@negative'] },
    async ({ browser, page }) => {
      const reason = `E2E OTP HRMS down ${RUN}`;
      // OD-141: at the cycle test office DT001, whose past months stay open (a fallback upload refuses a
      // locked month; DH001's are locked demo months, V082_21).
      const unitMaker = TEST_MAKER;
      const unitMakerScope = testMaker;
      const open = sqlRows(
        `SELECT c.payroll_yymm FROM payroll_cycle_instance c JOIN org_location l ON l.location_id = c.org_unit_id WHERE l.location_code = '${TEST_OFFICE}' AND c.cycle_status = 'LOCKED'`,
      ).map((r) => String(r[0]));
      const now = businessToday();
      const months = [2, 3, 4, 5, 6, 7].map((back) => {
        const d = new Date(Date.UTC(Number(now.slice(0, 4)), Number(now.slice(5, 7)) - 1 - back, 1));
        return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
      });
      const month = months.find((m) => !open.includes(m)) as string;
      const last = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(4)), 0)).getUTCDate();
      const dmy = (day: number) => `${String(day).padStart(2, '0')}-${month.slice(4)}-${month.slice(0, 4)}`;

      await workAs(
        page,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'X-07',
      );
      await page.getByRole('button', { name: t('en', 'x07.w.act.enable') }).click();
      await page.getByLabel(t('en', 'x07.type'), { exact: true }).selectOption('ATTENDANCE_LEAVE');
      await page.getByLabel(t('en', 'x07.month')).selectOption(month);
      await page.getByLabel(t('en', 'x07.offices')).fill(TEST_OFFICE.toLowerCase());
      await page.getByLabel(t('en', 'common.reason')).fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expectToast(page, /Sent for approval/);
      await workAs(
        page,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'W-01',
      );
      await decide(page, new RegExp(reason), 'APPROVE');

      await workAs(page, 'en', unitMaker, unitMakerScope, 'X-07');
      const enablements = await apiAll<{ id: number; reason: string }>(page, '/v1/hrms-fallback/enablements');
      const enablement = enablements.find((e) => e.reason === reason)?.id;
      expect(enablement).toBeDefined();
      await page.getByRole('button', { name: t('en', 'x07.w.act.upload') }).click();
      await page.getByRole('combobox', { name: t('en', 'x07.enablement') }).selectOption(String(enablement));
      await page.getByLabel(t('en', 'x07.file')).setInputFiles({
        name: 'hrms-export.csv',
        mimeType: 'text/csv',
        buffer: Buffer.from(
          'EMP_NO,FROM_DATE,TO_DATE,PAY_DAYS,LWP_DAYS,HALF_PAY_DAYS\r\n' +
            `${testEis('106958')},${dmy(1)},${dmy(last)},${last - 1},1,0\r\n`,
        ),
      });
      await page.getByRole('button', { name: t('en', 'x07.uploadValidate') }).click();
      const batch = /FBU-[A-Z0-9-]+/.exec(await expectToast(page, /FBU-/))?.[0] ?? '';
      await page.getByRole('button', { name: t('en', 'x07.w.history'), exact: true }).click();
      await page
        .locator(`tr[data-reference="${batch}"]`)
        .getByRole('button', { name: t('en', 'common.submitForApproval') })
        .click();
      await expectToast(page, t('en', 'x07.submitted', [batch]));
      const record = `SELECT upload_status FROM hrms_fallback_upload WHERE batch_no = '${batch}'`;
      const id = pendingItem('X-07', unitMaker);
      await refusedToRole(browser, unitMaker, unitMakerScope, id, record, 'X-07');
      await workAs(page, 'en', TEST_CHECKER, testChecker, 'W-01');
      await approveThroughTheGate(page, new RegExp(batch), 'HRMS_FALLBACK_UPLOAD_APPROVAL', record);
      expect(sqlRows(record)[0]?.[0]).toBe('APPROVED');
    },
  );

  test(
    'SRS-G-22, SRS-EH-13: an expired code leaves the record unchanged and the item pending; Resend gives a new code and the earlier one is dead',
    { tag: ['@SRS-G-22', '@SRS-EH-13', '@SRS-G-3', '@negative'] },
    async ({ browser }) => {
      const taskNo = received('INCREMENT_DATE', '2026-12-01');
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await sendTaskOrder(page, taskNo);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      const item = await onSomePage(ddo, ddo.getByRole('row', { name: new RegExp(taskNo) }).first());
      const dialog = await pressDecision(ddo, item, 'APPROVE');
      const since = Date.now();
      await dialog.getByRole('button', { name: t('en', 'w01.APPROVE') }).click();
      await expect(otpField(ddo)).toBeVisible();
      const code = await otpFromLog('INCREMENT_DATE_BASIC_CORRECTION', since);
      // Never wait real time: the challenge is expired in the database.
      sql(
        `UPDATE otp_challenge SET issued_at = DATEADD('HOUR', -3, issued_at) WHERE outcome = 'ISSUED' AND gated_action = 'INCREMENT_DATE_BASIC_CORRECTION' AND challenged_user = '${CHECKER}'`,
      );
      await otpField(ddo).fill(code);
      await verifyButton(ddo).click();
      await expect(ddo.getByRole('alert').filter({ hasText: t('en', ERROR_OTP) })).toBeVisible();
      expect(sqlRows(task(taskNo))[0]?.[0]).toBe('PENDING_CHECKER');

      const resent = Date.now();
      await ddo.getByRole('button', { name: t('en', 'otp.resend') }).click();
      await expect(ddo.getByText(t('en', 'otp.resent'))).toBeVisible();
      const fresh = await otpFromLog('INCREMENT_DATE_BASIC_CORRECTION', resent);
      await otpField(ddo).fill(code === fresh ? wrongOf(fresh) : code);
      await verifyButton(ddo).click();
      await expect(ddo.getByRole('alert').filter({ hasText: t('en', ERROR_OTP) })).toBeVisible();
      await otpField(ddo).fill(fresh);
      await verifyButton(ddo).click();
      await expect(ddo.getByRole('status')).toContainText(t('en', 'status.APPROVED'));
      expect(sqlRows(task(taskNo))[0]?.[0]).toBe('APPROVED');
    },
  );

  test(
    'SRS-EH-13: cancelling the OTP dialog leaves the item pending and it can be decided again',
    { tag: ['@SRS-EH-13', '@SRS-G-21', '@edge'] },
    async ({ browser }) => {
      const taskNo = received('INCREMENT_DATE', '2026-12-01');
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await sendTaskOrder(page, taskNo);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      const item = await onSomePage(ddo, ddo.getByRole('row', { name: new RegExp(taskNo) }).first());
      const dialog = await pressDecision(ddo, item, 'APPROVE');
      await dialog.getByRole('button', { name: t('en', 'w01.APPROVE') }).click();
      await expect(otpField(ddo)).toBeVisible();
      await ddo
        .getByRole('button', { name: t('en', 'common.cancel') })
        .last()
        .click();
      await expect(otpField(ddo)).toHaveCount(0);
      await ddo.reload();
      await expect(
        await onSomePage(ddo, ddo.getByRole('row', { name: new RegExp(taskNo) }).first()),
      ).toBeVisible();
      await decide(ddo, new RegExp(taskNo), 'APPROVE', '', 'INCREMENT_DATE_BASIC_CORRECTION');
      expect(sqlRows(task(taskNo))[0]?.[0]).toBe('APPROVED');
    },
  );
});
