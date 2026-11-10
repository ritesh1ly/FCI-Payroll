import { expect, type Page, test } from '@playwright/test';
import { apiStatus, decide, errorAlert, t, workAs } from '../support/app';
import { auditRows } from '../support/payroll-support';

/**
 * The Module 04 masters through the UI (docs/plan/04-masters-mdm.md): M-14 net-pay floors, M-15
 * ceiling norms, M-25 circulars, M-27 operations and M-26 labels, each proposed by its maker and in
 * force only once a different checker approves it in W-01. Every run uses its own effective dates
 * (minutes since 2026, as days after 2200-01-01) and its own codes, so the suite is re-runnable.
 */
const RULE_ADMIN = 'su.hq@fci-demo.test';
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const SYSADMIN = 'sysadmin@fci-demo.test';
const DELHI_MAKER = 'maker.delhi@fci-demo.test';
const DELHI_CHECKER = 'ddo.delhi@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const sysadmin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const delhiMaker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const delhiChecker = { role: 'PAYROLL_CHECKER', office: 'DH001' };

const stamp = Date.now();
/** A date no earlier run used: later runs always get later dates. */
/** Today in the business time zone (IST): a new operation code starts today, so it is in force. */
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
const runDate = (() => {
  const minutes = Math.floor((stamp - Date.UTC(2026, 0, 1)) / 60_000);
  return new Date(Date.UTC(2200, 0, 1) + minutes * 86_400_000).toISOString().slice(0, 10);
})();
const PDF = Buffer.from('%PDF-1.7\n1 0 obj\n<< >>\nendobj\n', 'ascii');

async function approveInInbox(
  page: Page,
  login: string,
  scope: { role: string; office: string },
  reason: string,
) {
  await workAs(page, 'en', login, scope, 'W-01');
  await decide(page, new RegExp(reason), 'APPROVE');
}

/**
 * M-14 and M-15 keep the version forms in a closed register under the drawn screen (OD-121; the drawn
 * Save & Submit is a separate button): these tests drive the register, so they open it and work inside it.
 */
async function register(page: Page, screen: 'M-14' | 'M-15') {
  const section = page.getByTestId(screen === 'M-14' ? 'm14-register' : 'm15-register');
  await section.waitFor();
  if ((await section.getAttribute('open')) === null) await section.locator(':scope > summary').click();
  return section;
}

test.describe.serial('Module 04 masters (M-14, M-15, M-25, M-26, M-27)', () => {
  const circular = `E2E/${stamp}`;
  const operation = `E${String(stamp).slice(-7)}`;

  test(
    'a unit payroll maker reads the HQ rule masters and enters nothing (SRS 14.4)',
    { tag: ['@SRS-16-13', '@SRS-16-14', '@SRS-G-41', '@SC-04-0016', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'W-02');
      // SRS 14.4 lists the maker on M-14 and M-15 (OD-125, WF-071), "enter: admin" (SRS-16-13,
      // SRS-16-14): national configuration with no personal data, read only.
      for (const screen of ['M-14', 'M-15']) {
        await page.goto(`/payroll/?s=${screen}`);
        await expect(
          page.getByRole('heading', { level: 1, name: t('en', `screen.${screen}`) }),
        ).toBeVisible();
      }
      expect(await apiStatus(page, '/v1/masters/net-pay-floors')).toBe(200);
      expect(await apiStatus(page, '/v1/masters/ceiling-norms')).toBe(200);
      for (const path of ['net-pay-floors/STAFF', 'ceiling-norms/TA']) {
        expect(
          await apiStatus(page, `/v1/masters/${path}/versions`, 'POST', {
            effectiveFrom: runDate,
            reason: 'Not allowed',
          }),
          path,
        ).toBe(403);
      }
      // The maker views operations (labour users pick them) but may not propose one.
      expect(await apiStatus(page, '/v1/masters/operations')).toBe(200);
      expect(
        await apiStatus(page, `/v1/masters/operations/${operation}/versions`, 'POST', {
          effectiveFrom: runDate,
          name: 'Not allowed',
          departmental: true,
          dps: false,
          nwnp: false,
          bagBased: false,
          rateNormLink: 'TIME_BASED',
          dusting: false,
          presentButNoWork: false,
          circularId: 1,
          reason: 'E2E',
        }),
      ).toBe(403);
    },
  );

  test(
    'a net-pay floor is proposed at HQ, approved by the Payroll Checker and kept as a dated version',
    {
      tag: [
        '@SRS-16-13',
        '@SRS-G-37',
        '@SC-04-0012',
        '@SC-04-0043',
        '@SC-04-0046',
        '@SC-04-0047',
        '@SC-04-0048',
        '@SC-04-0049',
        '@SC-04-0050',
        '@positive',
      ],
    },
    async ({ page }) => {
      const reason = `E2E floor ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-14');
      const m14 = await register(page, 'M-14');
      const row = m14.getByRole('row', { name: new RegExp(t('en', 'm14.type.DPS_LABOUR')) });
      await row.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).click();
      await page.locator('#fl-from').fill(runDate);
      await page.locator('#fl-basis').selectOption('ABSOLUTE_AMOUNT');
      await page.locator('#fl-value').fill('1500');
      await page.locator('#fl-reason').fill(reason);
      await m14.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(
        t('en', 'mst.submitted', [t('en', 'm14.type.DPS_LABOUR')]),
      );
      await expect(row).toContainText(t('en', 'mst.pendingFrom', ['§']).split('§')[1] ?? '');

      await approveInInbox(page, HQ_CHECKER, hqChecker, reason);

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-14');
      const history = await register(page, 'M-14');
      await history
        .getByRole('row', { name: new RegExp(t('en', 'm14.type.DPS_LABOUR')) })
        .getByRole('button', { name: t('en', 'mst.history'), exact: true })
        .click();
      await expect(
        history.getByRole('cell', { name: t('en', 'status.APPROVED'), exact: true }).first(),
      ).toBeVisible();
    },
  );

  test(
    'a ceiling norm for a claim type is proposed and approved; zero is refused',
    {
      tag: [
        '@SRS-16-14',
        '@SC-04-0013',
        '@SC-04-0053',
        '@SC-04-0057',
        '@SC-04-0059',
        '@SC-04-0060',
        '@SC-04-0061',
        '@positive',
      ],
    },
    async ({ page }) => {
      const reason = `E2E ceiling ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-15');
      const m15 = await register(page, 'M-15');
      const row = m15.getByRole('row', { name: /LABOUR_TA/ });
      await row.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).click();
      await page.locator('#cn-from').fill(runDate);
      await page.locator('#cn-value').fill('0');
      await page.locator('#cn-elig').selectOption('DEMO_ALL');
      await page.locator('#cn-circular').selectOption({ index: 1 });
      await page.locator('#cn-reason').fill(reason);
      await m15.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(errorAlert(page)).toContainText(t('en', 'error.MST-422-CEILING'));
      await page.locator('#cn-value').fill('750.50');
      await m15.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'mst.submitted', ['Labour TA']));

      await approveInInbox(page, HQ_CHECKER, hqChecker, reason);
    },
  );

  test(
    'SRS-16-13 / SRS-16-14: a floor or ceiling version that would start before the version in force is refused with that date',
    {
      tag: [
        '@SRS-16-13',
        '@SRS-16-14',
        '@SRS-G-37',
        '@SC-04-0044',
        '@SC-04-0054',
        '@SC-04-0062',
        '@negative',
      ],
    },
    async ({ page }) => {
      // The two tests above put a version in force from runDate; a day earlier would overlap it.
      const dayBefore = new Date(Date.parse(`${runDate}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10);
      const refused = t('en', 'error.MST-422-DATE', ['§']).split('§')[0] ?? '';

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-14');
      const m14 = await register(page, 'M-14');
      const floor = m14.getByRole('row', { name: new RegExp(t('en', 'm14.type.DPS_LABOUR')) });
      await floor.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).click();
      await page.locator('#fl-from').fill(dayBefore);
      await page.locator('#fl-basis').selectOption('ABSOLUTE_AMOUNT');
      await page.locator('#fl-value').fill('1400');
      await page.locator('#fl-reason').fill(`E2E overlapping floor ${stamp}`);
      await m14.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(errorAlert(page)).toContainText(refused);

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-15');
      const m15 = await register(page, 'M-15');
      const ceiling = m15.getByRole('row', { name: /LABOUR_TA/ });
      await ceiling.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).click();
      await page.locator('#cn-from').fill(dayBefore);
      await page.locator('#cn-value').fill('700');
      await page.locator('#cn-elig').selectOption('DEMO_ALL');
      await page.locator('#cn-circular').selectOption({ index: 1 });
      await page.locator('#cn-reason').fill(`E2E overlapping ceiling ${stamp}`);
      await m15.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(errorAlert(page)).toContainText(refused);
    },
  );

  test(
    'SRS-16-13: a floor of zero is accepted, a negative one refused, and no version goes without its reason',
    { tag: ['@SRS-16-13', '@SRS-G-37', '@SC-04-0048', '@SC-04-0050', '@edge'] },
    async ({ page }) => {
      // A day after the version in force (runDate), so only the value and the reason decide.
      const dayAfter = new Date(Date.parse(`${runDate}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10);
      const reason = `E2E zero floor ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-14');
      const m14 = await register(page, 'M-14');
      const floor = m14.getByRole('row', { name: new RegExp(t('en', 'm14.type.DPS_LABOUR')) });
      await floor.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).click();
      await page.locator('#fl-from').fill(dayAfter);
      await page.locator('#fl-basis').selectOption('ABSOLUTE_AMOUNT');
      const submit = m14.getByRole('button', { name: t('en', 'common.submitForApproval') });
      // The reason is mandatory under change control: the form will not send without it ...
      await page.locator('#fl-value').fill('0');
      await page.locator('#fl-reason').fill('');
      await expect(submit).toBeDisabled();
      // ... nor will the server.
      expect(
        await apiStatus(page, '/v1/masters/net-pay-floors/DPS_LABOUR/versions', 'POST', {
          effectiveFrom: dayAfter,
          floorBasis: 'ABSOLUTE_AMOUNT',
          floorValue: 0,
          confirmed: true,
          reason: ' ',
        }),
      ).toBe(400);
      // "Zero or more": a negative floor is refused naming the rule ...
      await page.locator('#fl-value').fill('-1');
      await page.locator('#fl-reason').fill(reason);
      await submit.click();
      await expect(errorAlert(page)).toContainText(t('en', 'error.MST-422-FLOOR'));
      // ... and zero itself is a valid floor.
      await page.locator('#fl-value').fill('0');
      await submit.click();
      await expect(page.getByRole('status')).toContainText(
        t('en', 'mst.submitted', [t('en', 'm14.type.DPS_LABOUR')]),
      );
      // Re-runnable: the HQ checker rejects it, so no proposal is left waiting on the payroll type.
      await workAs(page, 'en', HQ_CHECKER, hqChecker, 'W-01');
      await decide(page, new RegExp(reason), 'REJECT', 'E2E: edge proposal, not needed');
    },
  );

  test(
    'a circular is recorded once with its PDF, approved by the checker of the office and searchable',
    { tag: ['@SRS-G-38', '@SRS-16-23', '@SC-04-0022', '@positive'] },
    async ({ page }) => {
      const reason = `E2E circular ${stamp}`;
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-25');
      await page.getByRole('button', { name: t('en', 'm25.act.new'), exact: true }).click();
      await page.locator('#nc-number').fill(circular);
      await page.locator('#nc-auth').selectOption('HQ_PERSONNEL');
      await page.locator('#nc-cat').selectOption('LABOUR_NORM');
      await page.locator('#nc-subject').fill(`Handling norms ${stamp}`);
      await page.getByRole('checkbox', { name: t('en', 'm14.type.DEPT_LABOUR') }).check();
      await page
        .locator('#nc-file')
        .setInputFiles({ name: 'order.pdf', mimeType: 'application/pdf', buffer: PDF });
      await page.locator('#nc-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm25.submitted', [circular]));
      await expect(page.getByRole('row', { name: new RegExp(circular) })).toContainText(
        t('en', 'm25.status.PENDING_APPROVAL'),
      );

      await approveInInbox(page, DELHI_CHECKER, delhiChecker, reason);

      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-25');
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.locator('#ci-text').fill(String(stamp));
      const row = page.getByRole('row', { name: new RegExp(circular) });
      await expect(row).toContainText(t('en', 'm25.status.ACTIVE'));
      await expect(row).toContainText(t('en', 'm25.pdf'));
      await row.getByRole('button', { name: `${t('en', 'common.view')} ${circular}`, exact: true }).click();
      await expect(
        page.getByRole('dialog').getByRole('link', { name: t('en', 'm25.document') }),
      ).toBeVisible();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.close') })
        .click();
    },
  );

  test(
    'an operation links its Active circular, which then shows it under where used',
    { tag: ['@SRS-G-41', '@SRS-G-38', '@SC-04-0024', '@positive'] },
    async ({ page }) => {
      const reason = `E2E operation ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-27');
      await page.getByRole('button', { name: t('en', 'm27.act.new'), exact: true }).click();
      await page.locator('#op-code').fill(operation);
      await page.locator('#op-name').fill(`Wagon to Platform ${stamp}`);
      const submit = page.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await page.locator('#op-reason').fill(reason);
      // No labour payroll and no circular yet: nothing to submit.
      await expect(submit).toBeDisabled();
      await page.getByRole('checkbox', { name: t('en', 'm14.type.DEPT_LABOUR') }).check();
      await page.locator('#op-bag').check();
      // A bag-based operation always links a norm slab.
      await expect(page.locator('#op-link')).toHaveValue('NORM_SLAB');
      await expect(page.locator('#op-link')).toBeDisabled();
      const option = await page.locator('#op-circ option', { hasText: circular }).getAttribute('value');
      await page.locator('#op-circ').selectOption(option ?? '');
      await page.locator('#op-from').fill(today);
      await submit.click();
      await expect(page.getByRole('status')).toContainText(t('en', 'mst.submitted', [operation]));

      await approveInInbox(page, HQ_CHECKER, hqChecker, reason);

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-25');
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.locator('#ci-text').fill(String(stamp));
      await page
        .getByRole('row', { name: new RegExp(circular) })
        .locator('td')
        .first()
        .click();
      await expect(page.getByRole('cell', { name: new RegExp(operation) })).toBeVisible();
    },
  );

  test(
    'a Hindi Global label waits for the HQ checker; screen text cannot take a local scope',
    { tag: ['@SRS-G-39', '@SRS-G-40', '@SRS-16-24', '@SC-04-0023', '@positive'] },
    async ({ page }) => {
      const key = `LBL.E2E.${stamp}`;
      const reason = `E2E label ${stamp}`;
      await workAs(page, 'en', SYSADMIN, sysadmin, 'M-26');
      await page.getByRole('button', { name: t('en', 'm26.act.upload'), exact: true }).click();
      await page.getByRole('button', { name: t('en', 'action.newLabel'), exact: true }).click();
      await page.locator('#nl-key').fill(key);
      await page.locator('#nl-type').selectOption('UI_LABEL');
      // Screen text stays Global: the scope cannot be changed.
      await expect(page.locator('#nl-scope')).toBeDisabled();
      await page.locator('#nl-lang').selectOption('HI');
      await page.locator('#nl-text').fill('परीक्षण लेबल');
      await page.locator('#nl-from').fill('2026-04-01');
      await page.locator('#nl-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm26.submitted', [key]));

      await approveInInbox(page, HQ_CHECKER, hqChecker, reason);

      await workAs(page, 'en', SYSADMIN, sysadmin, 'M-26');
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.locator('#lb-text').fill(key.toLowerCase());
      const row = page.getByRole('row', { name: new RegExp(key.replace(/\./g, '\\.')) });
      await expect(row).toContainText('परीक्षण लेबल');
      await expect(row).toContainText(t('en', 'm25.status.ACTIVE'));
    },
  );

  test(
    'every circular, operation and label version and the circular link are kept in the audit trail (A-05)',
    {
      tag: ['@SRS-G-38', '@SRS-G-41', '@SRS-G-39', '@SRS-G-2', '@positive'],
    },
    async ({ browser }) => {
      // SC-01-0155, SC-01-0171, SC-01-0183 (main flows) (Module 01 numbering of a use case kb_review_01 maps to module 02 / 04: a comment, not an e2e_trace tag).
      // SC-01-0158 (l.934): the circular, its version and its links; SC-01-0186 (l.1001): the
      // operation version, linked to its circular; SC-01-0174 (l.970): the label version. Each
      // record carries the approval id of its checker decision.
      const circularRows = await auditRows(browser, 'circular_order');
      const ours = circularRows.filter((r) => r.reason === `E2E circular ${stamp}`);
      expect(ours.length, 'the circular submission').toBeGreaterThan(0);
      expect(ours.some((r) => r.approvalId !== null)).toBe(true);
      const key = ours[0]?.entityKey ?? '';
      expect(
        circularRows.filter(
          (r) =>
            r.entityKey === key && r.fieldName === 'link' && r.newValue === `operation_master:${operation}`,
        ),
        'the link to the operation',
      ).toHaveLength(1);

      const operationRows = await auditRows(browser, 'operation_master');
      const op = operationRows.filter((r) => r.reason === `E2E operation ${stamp}`);
      expect(op.length, 'the operation version').toBeGreaterThan(0);
      expect(op.some((r) => r.approvalId !== null)).toBe(true);

      const labelRows = await auditRows(browser, 'label_master');
      const label = labelRows.filter((r) => r.reason === `E2E label ${stamp}`);
      expect(label.length, 'the label version').toBeGreaterThan(0);
      expect(label.some((r) => r.approvalId !== null)).toBe(true);
    },
  );

  test(
    'a unit proposes a local name for a localisable master value and the missing-Hindi report lists the value',
    { tag: ['@SRS-G-39', '@SRS-16-24', '@positive'] },
    async ({ page }) => {
      const reason = `E2E local name ${stamp}`;
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-26');
      const missing = page.getByRole('button', { name: t('en', 'm26.act.missing'), exact: true });
      await missing.click();
      await expect(page.getByRole('cell', { name: 'DESIG.SARDAR' }).first()).toBeVisible();
      await expect(page.getByText(t('en', 'm26.status.HINDI_PENDING')).first()).toBeVisible();
      await missing.click();
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.locator('#lb-text').fill('desig.sardar');
      await page
        .locator('tr[data-key="DESIG.SARDAR"]', { hasText: t('en', 'm26.scope.GLOBAL') })
        .first()
        .getByRole('button', { name: `${t('en', 'common.edit')} DESIG.SARDAR`, exact: true })
        .click();
      await page.locator('#nl-lang').selectOption('EN');
      await page.locator('#nl-scope').selectOption('OFFICE');
      await page.locator('#nl-text').fill(`Mukadam ${String(stamp).slice(-4)}`);
      await page.locator('#nl-from').fill(runDate);
      await page.locator('#nl-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm26.submitted', ['DESIG.SARDAR']));
      // Routed to the Regional Office above the unit (route M26_LOCAL), not to the unit's own checker.
      expect(
        await apiStatus(page, '/v1/masters/labels?text=desig.sardar&scope=OFFICE&status=PENDING_APPROVAL'),
      ).toBe(200);
    },
  );

  test(
    'the Regional Office checker decides the local name; Headquarters withdraws it through its checker',
    { tag: ['@SRS-G-39', '@SRS-16-24', '@positive'] },
    async ({ page }) => {
      const local = `Mukadam ${String(stamp).slice(-4)}`;
      await approveInInbox(
        page,
        'agm.north@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'NA11' },
        `E2E local name ${stamp}`,
      );
      const withdrawReason = `E2E withdraw local name ${stamp}`;
      await workAs(page, 'en', SYSADMIN, sysadmin, 'M-26');
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.locator('#lb-text').fill(local.toLowerCase());
      const row = page.getByRole('row', { name: new RegExp(local) });
      await expect(row).toContainText(t('en', 'm25.status.ACTIVE'));
      await row.getByRole('button', { name: `${t('en', 'common.view')} DESIG.SARDAR`, exact: true }).click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'action.withdraw'), exact: true })
        .click();
      await page.getByRole('dialog').getByRole('textbox').fill(withdrawReason);
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'action.withdraw') })
        .click();
      await expect(page.getByRole('status')).toContainText(
        t('en', 'm26.withdrawSubmitted', ['DESIG.SARDAR']),
      );
      await approveInInbox(page, HQ_CHECKER, hqChecker, withdrawReason);
      await workAs(page, 'en', SYSADMIN, sysadmin, 'M-26');
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.locator('#lb-text').fill(local.toLowerCase());
      await expect(page.getByRole('row', { name: new RegExp(local) })).toContainText(
        t('en', 'm25.status.WITHDRAWN'),
      );
    },
  );

  test(
    'a copy that is not a PDF is refused, whatever its name',
    { tag: ['@SRS-G-38', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-25');
      await page.getByRole('button', { name: t('en', 'm25.act.new'), exact: true }).click();
      await page.locator('#nc-number').fill(`E2E/FAKE/${stamp}`);
      await page.locator('#nc-auth').selectOption('HQ_PERSONNEL');
      await page.locator('#nc-cat').selectOption('LABOUR_NORM');
      await page.locator('#nc-subject').fill('Not a PDF');
      await page.getByRole('checkbox', { name: t('en', 'm14.type.STAFF') }).check();
      await page.locator('#nc-file').setInputFiles({
        name: 'order.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('MZ not a pdf'),
      });
      await page.locator('#nc-reason').fill('E2E fake copy');
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(errorAlert(page)).toContainText(t('en', 'error.MST-422-CIRCULAR-DOC'));
    },
  );

  test(
    'an empty copy and a renamed executable are refused; a 2 MB PDF gets through NGINX; a 12 MB one is never recorded',
    { tag: ['@SRS-G-38', '@SRS-G-30', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-25');
      const record = async (number: string, file: { name: string; mimeType: string; buffer: Buffer }) => {
        await page.getByRole('button', { name: t('en', 'm25.act.new'), exact: true }).click();
        await page.locator('#nc-number').fill(number);
        await page.locator('#nc-auth').selectOption('HQ_PERSONNEL');
        await page.locator('#nc-cat').selectOption('LABOUR_NORM');
        await page.locator('#nc-subject').fill(`Upload limits ${stamp}`);
        await page.getByRole('checkbox', { name: t('en', 'm14.type.STAFF') }).check();
        await page.locator('#nc-file').setInputFiles(file);
        await page.locator('#nc-reason').fill('E2E upload limits');
        await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      };
      const cancel = () => page.getByRole('button', { name: t('en', 'common.cancel') }).click();

      // Checked by content, whatever the name says.
      await record(`E2E/EMPTY/${stamp}`, {
        name: 'order.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.alloc(0),
      });
      await expect(errorAlert(page)).toContainText(t('en', 'error.MST-422-CIRCULAR-DOC'));
      await cancel();
      await record(`E2E/EXE/${stamp}`, {
        name: 'order.pdf.exe',
        mimeType: 'application/octet-stream',
        buffer: Buffer.from('MZ\x90\x00 an executable'),
      });
      await expect(errorAlert(page)).toContainText(t('en', 'error.MST-422-CIRCULAR-DOC'));
      await cancel();

      // A scanned order is often over 1 MB: NGINX gives the upload endpoints 11 MB (found 2026-10-22:
      // every copy over 1 MB was refused with 413 before the backend saw it).
      const twoMb = Buffer.concat([PDF, Buffer.alloc(2 * 1024 * 1024, 0x20)]);
      const accepted = `E2E/2MB/${stamp}`;
      await record(accepted, { name: 'scanned-order.pdf', mimeType: 'application/pdf', buffer: twoMb });
      await expect(page.getByRole('status')).toContainText(t('en', 'm25.submitted', [accepted]));

      // Over the 10 MB document limit: refused (by the backend, or by NGINX above 11 MB) and never recorded.
      const tooBig = `E2E/12MB/${stamp}`;
      const big = Buffer.concat([PDF, Buffer.alloc(12 * 1024 * 1024, 0x20)]);
      await record(tooBig, { name: 'too-big.pdf', mimeType: 'application/pdf', buffer: big });
      await expect(errorAlert(page)).toBeVisible();
      await expect(page.getByRole('status').filter({ hasText: tooBig })).toHaveCount(0);
      expect(await apiStatus(page, `/v1/masters/circulars?text=${encodeURIComponent(tooBig)}`)).toBe(200);
      await expect(page.getByRole('cell', { name: tooBig, exact: true })).toHaveCount(0);
    },
  );

  test(
    'a withdrawn circular is no longer offered to an operation',
    { tag: ['@SRS-G-38', '@SRS-G-41', '@edge'] },
    async ({ page }) => {
      const number = `E2E/WD/${stamp}`;
      const reason = `E2E circular to withdraw ${stamp}`;
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-25');
      await page.getByRole('button', { name: t('en', 'm25.act.new'), exact: true }).click();
      await page.locator('#nc-number').fill(number);
      await page.locator('#nc-auth').selectOption('HQ_PERSONNEL');
      await page.locator('#nc-cat').selectOption('LABOUR_NORM');
      await page.locator('#nc-subject').fill(`To withdraw ${stamp}`);
      await page.getByRole('checkbox', { name: t('en', 'm14.type.DEPT_LABOUR') }).check();
      await page
        .locator('#nc-file')
        .setInputFiles({ name: 'order.pdf', mimeType: 'application/pdf', buffer: PDF });
      await page.locator('#nc-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm25.submitted', [number]));
      await approveInInbox(page, DELHI_CHECKER, delhiChecker, reason);

      const withdrawReason = `E2E withdraw circular ${stamp}`;
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-25');
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.locator('#ci-text').fill(`wd/${stamp}`);
      await page
        .getByRole('row', { name: new RegExp(number) })
        .locator('td')
        .first()
        .click();
      await page.getByRole('button', { name: t('en', 'm25.act.withdraw'), exact: true }).click();
      await page.getByRole('dialog').getByRole('textbox').fill(withdrawReason);
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'action.withdraw') })
        .click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm25.withdrawSubmitted', [number]));
      await approveInInbox(page, DELHI_CHECKER, delhiChecker, withdrawReason);

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-27');
      await page.getByRole('button', { name: t('en', 'm27.act.new'), exact: true }).click();
      await expect(page.locator('#op-circ option', { hasText: circular })).toHaveCount(1);
      await expect(page.locator('#op-circ option', { hasText: number })).toHaveCount(0);
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
    },
  );

  test(
    'an operation is end-dated only when the checker approves',
    { tag: ['@SRS-G-41', '@SRS-16-22', '@positive'] },
    async ({ page }) => {
      const reason = `E2E end-date ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-27');
      await page
        .getByRole('row', { name: new RegExp(operation) })
        .getByRole('button', { name: `${t('en', 'common.view')} ${operation}`, exact: true })
        .click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'action.endDate'), exact: true })
        .click();
      await page.locator('#op-last').fill(today);
      await page.locator('#op-end-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm27.endSubmitted', [operation]));
      await approveInInbox(page, HQ_CHECKER, hqChecker, reason);
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-27');
      // Once end-dated, nothing further to end-date.
      await page
        .getByRole('row', { name: new RegExp(operation) })
        .getByRole('button', { name: `${t('en', 'common.view')} ${operation}`, exact: true })
        .click();
      await expect(
        page.getByRole('dialog').getByRole('button', { name: t('en', 'action.endDate'), exact: true }),
      ).toHaveCount(0);
    },
  );

  test(
    'the supplementary exclusion codes change only when the checker approves',
    { tag: ['@SRS-16-13', '@SC-04-0045', '@positive'] },
    async ({ page }) => {
      const code101 = () => page.getByRole('checkbox', { name: t('en', 'm14.excludeCode', [101]) });
      const flip = async (reason: string) => {
        await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-14');
        const m14 = await register(page, 'M-14');
        await m14.getByRole('button', { name: t('en', 'action.changeExclusions'), exact: true }).click();
        await code101().click();
        await page.locator('#ex-reason').fill(reason);
        await m14
          .getByRole('button', { name: t('en', 'common.submitForApproval') })
          .last()
          .click();
        await expect(page.getByRole('status')).toContainText(t('en', 'm14.exclusionsSubmitted'));
        // Pending: no second change is offered until the checker decides.
        await expect(m14.getByRole('button', { name: t('en', 'action.changeExclusions') })).toHaveCount(0);
        await approveInInbox(page, HQ_CHECKER, hqChecker, reason);
      };
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-14');
      await register(page, 'M-14');
      const before = await code101().isChecked();
      await flip(`E2E exclude 101 ${stamp}`);
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-14');
      await register(page, 'M-14');
      expect(await code101().isChecked()).toBe(!before);
      // Leave the demo data as it was.
      await flip(`E2E restore 101 ${stamp}`);
    },
  );
});
