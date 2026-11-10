import { expect, type Page, test } from '@playwright/test';
import { apiStatus, businessToday, decide, onSomePage, t, workAs } from '../support/app';
import { apiJson } from '../support/payroll-support';

/** The route list pages at 20; show 100 so a route proposed here is on the page (72 routes by module 16). */
async function allRoutes(page: Page) {
  await page
    .locator('#x01-routes')
    .getByRole('navigation', { name: t('en', 'page.nav') })
    .getByLabel(t('en', 'page.size'))
    .selectOption('100');
}

/**
 * A-08 Approval Workflow Master (Module 02 F-1; SRS-22-12, 22-16, 22-17, 22-19) and the inbox's
 * value / age columns (SRS-22-4): the HQ Administrator proposes a route, a Payroll Checker approves it
 * in W-01, and only then is it in force. Each run uses an action code of its own (no route the rest
 * of the suite uses is changed).
 */

const ADMIN = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const CHECKER = { role: 'PAYROLL_CHECKER', office: 'HQ001' };

test.describe('A-08 Approval Workflow Master', () => {
  test(
    'the HQ Administrator proposes a new route; a Payroll Checker approves it; then it is in force',
    {
      tag: [
        '@SRS-22-19',
        '@SRS-22-16',
        '@SC-02-0004',
        '@SC-02-0012',
        '@SC-02-0014',
        '@SC-02-0015',
        '@SC-02-0017',
        '@SC-02-0019',
        '@SC-02-0034',
        '@SC-02-0035',
        '@SC-02-0038',
        '@SC-02-0039',
        '@SC-02-0040',
        '@SC-02-0042',
        '@SC-02-0047',
        '@SC-02-0055',
        '@positive',
      ],
    },
    async ({ browser }) => {
      const code = `E2E_${Date.now().toString(36).toUpperCase()}`;
      const admin = await browser.newPage();
      await workAs(admin, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-08');
      await expect(admin.getByRole('row', { name: /A08_EDIT/ })).toContainText(t('en', 'w01.noTarget'));
      await admin.getByRole('button', { name: t('en', 'x01.addAction') }).click();
      await admin.getByLabel(t('en', 'a08.code')).fill(code);
      await admin.getByLabel(t('en', 'a08.name')).fill('E2E route');
      await admin.getByLabel(t('en', 'a08.module')).fill('GENERAL');
      await admin.getByLabel(t('en', 'a08.targetHours')).fill('48');
      await admin.getByLabel(t('en', 'a08.roleOf', [1])).fill('PAYROLL_MAKER');
      await admin.getByLabel(t('en', 'a08.roleOf', [2])).fill('PAYROLL_CHECKER');
      await admin.getByLabel(t('en', 'common.reason')).fill(`E2E ${code}`);
      await admin.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(admin.getByRole('status')).toContainText(
        t('en', 'a08.proposed', ['']).replace(/\.$/, '').trim(),
      );
      await allRoutes(admin);
      const row = admin.getByRole('row', { name: new RegExp(code) });
      await expect(row).toContainText(t('en', 'a08.notInForce'));

      const checker = await browser.newPage();
      await workAs(checker, 'en', 'checker.hq@fci-demo.test', CHECKER, 'W-01');
      // The inbox shows the item's age against its target and its value (none for a route).
      const item = await onSomePage(checker, checker.getByRole('row', { name: new RegExp(code) }).first());
      await expect(item).toContainText(t('en', 'w01.ageDays', [0]));
      await decide(checker, new RegExp(code), 'APPROVE');

      await admin.reload();
      await allRoutes(admin);
      await expect(admin.getByRole('row', { name: new RegExp(code) })).toContainText(
        t('en', 'w01.target', [48]),
      );
      await expect(admin.getByRole('row', { name: new RegExp(code) })).not.toContainText(
        t('en', 'a08.notInForce'),
      );
    },
  );

  test(
    'a route that breaks the rules is refused with the rule at the field',
    {
      tag: [
        '@SC-02-0015',
        '@SRS-22-17',
        '@SC-02-0016',
        '@SC-02-0036',
        '@SC-02-0037',
        '@SC-02-0041',
        '@negative',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-08');
      await page.getByRole('button', { name: t('en', 'x01.addAction') }).click();
      await page.getByLabel(t('en', 'a08.code')).fill('X');
      await page.getByLabel(t('en', 'a08.name')).fill('Broken');
      await page.getByLabel(t('en', 'a08.module')).fill('GENERAL');
      await page.getByLabel(t('en', 'a08.roleOf', [1])).fill('NO_SUCH_ROLE');
      await page.getByLabel(t('en', 'a08.roleOf', [2])).fill('PAYROLL_CHECKER');
      await page.getByLabel(t('en', 'common.reason')).fill('E2E broken');
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByText(t('en', 'a08.rule.ACTION_CODE'))).toBeVisible();
      await expect(page.getByText(t('en', 'a08.rule.LEVEL_ROLE'))).toBeVisible();
    },
  );

  test(
    'a Payroll Checker views the routes but proposes none',
    { tag: ['@SC-02-0017', '@SRS-22-19', '@SC-02-0051', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'checker.hq@fci-demo.test', CHECKER, 'A-08');
      await expect(page.getByRole('row', { name: /A08_EDIT/ })).toBeVisible();
      // Drawn for every role, disabled with the reason (OD-121); the row's Edit likewise.
      await expect(page.getByRole('button', { name: t('en', 'x01.addAction') })).toBeDisabled();
      await expect(page.getByRole('button', { name: `${t('en', 'common.edit')} A08_EDIT` })).toBeDisabled();
    },
  );
});

type RouteAnswer = {
  errorCode?: string;
  violations?: { field: string; code: string }[];
  approvalId?: number;
};

/** A route as A-08 sends it: two levels, Payroll Maker then Payroll Checker, from today. */
function route(over: Record<string, unknown> = {}) {
  return {
    actionCode: `E2E_${Date.now().toString(36).toUpperCase()}`,
    actionName: 'E2E route rules',
    moduleCode: 'GENERAL',
    officeLevel: 'HQ',
    levelCount: 2,
    attachmentRequired: false,
    targetTatHours: 24,
    effectiveFrom: businessToday(),
    levels: [
      { levelNo: 1, roleCode: 'PAYROLL_MAKER', officeLevel: 'HQ', otpAtLevel: false },
      { levelNo: 2, roleCode: 'PAYROLL_CHECKER', officeLevel: 'HQ', otpAtLevel: false },
    ],
    reason: 'E2E: a route the rules refuse',
    ...over,
  };
}

const ruleAt = (answer: RouteAnswer) => (answer.violations ?? []).map((v) => `${v.field}:${v.code}`);

test.describe('A-08 route rules (SRS-22-16, SRS-22-17, SRS-G-21)', () => {
  test(
    'each broken rule is named at its field; nothing is proposed',
    {
      tag: ['@SRS-22-16', '@SRS-22-17', '@SRS-22-12', '@SRS-G-21', '@SC-02-0016', '@SC-02-0036', '@negative'],
    },
    async ({ page }) => {
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-08');
      const cases: [string, Record<string, unknown>, string][] = [
        ['a lower-case code', { actionCode: 'e2e_lower' }, 'actionCode:ACTION_CODE'],
        ['a blank name', { actionName: ' ' }, 'actionName:ACTION_NAME'],
        ['a screen code not like I-20', { screenCode: 'I20' }, 'screenCode:SCREEN_CODE'],
        ['no office level', { officeLevel: null }, 'officeLevel:OFFICE_LEVEL'],
        ['one level only', { levelCount: 1 }, 'levelCount:LEVEL_COUNT'],
        ['three levels, two roles', { levelCount: 3 }, 'levels:LEVELS_MISMATCH'],
        [
          // SRS-22-12 (NEW-G1-09): a third level only where the matrix names one.
          'a third level for an action the matrix does not name',
          {
            levelCount: 3,
            levels: [
              { levelNo: 1, roleCode: 'PAYROLL_MAKER', officeLevel: 'HQ', otpAtLevel: false },
              { levelNo: 2, roleCode: 'PAYROLL_CHECKER', officeLevel: 'HQ', otpAtLevel: false },
              { levelNo: 3, roleCode: 'PAYROLL_CHECKER', officeLevel: 'HQ', otpAtLevel: false },
            ],
          },
          'levelCount:THIRD_LEVEL_NOT_NAMED',
        ],
        [
          'levels out of order',
          {
            levels: [
              { levelNo: 2, roleCode: 'PAYROLL_MAKER', officeLevel: 'HQ', otpAtLevel: false },
              { levelNo: 1, roleCode: 'PAYROLL_CHECKER', officeLevel: 'HQ', otpAtLevel: false },
            ],
          },
          'levels[0]:LEVEL_ORDER',
        ],
        [
          'a level without its office',
          {
            levels: [
              { levelNo: 1, roleCode: 'PAYROLL_MAKER', officeLevel: 'HQ', otpAtLevel: false },
              { levelNo: 2, roleCode: 'PAYROLL_CHECKER', officeLevel: null, otpAtLevel: false },
            ],
          },
          'levels[1]:LEVEL_OFFICE',
        ],
        [
          'an OTP at the maker',
          {
            otpAction: 'CYCLE_FREEZE_LOCK',
            levels: [
              { levelNo: 1, roleCode: 'PAYROLL_MAKER', officeLevel: 'HQ', otpAtLevel: true },
              { levelNo: 2, roleCode: 'PAYROLL_CHECKER', officeLevel: 'HQ', otpAtLevel: false },
            ],
          },
          'levels[0]:OTP_AT_MAKER',
        ],
        [
          'an OTP at a level, with no gated action',
          {
            levels: [
              { levelNo: 1, roleCode: 'PAYROLL_MAKER', officeLevel: 'HQ', otpAtLevel: false },
              { levelNo: 2, roleCode: 'PAYROLL_CHECKER', officeLevel: 'HQ', otpAtLevel: true },
            ],
          },
          'levels:OTP_WITHOUT_ACTION',
        ],
        ['a zero-hour target', { targetTatHours: 0 }, 'targetTatHours:TARGET_HOURS'],
        ['a target above 99,999 hours', { targetTatHours: 100_000 }, 'targetTatHours:TARGET_HOURS'],
        ['a start in the past', { effectiveFrom: '2020-01-01' }, 'effectiveFrom:EFFECTIVE_FROM_PAST'],
      ];
      for (const [name, over, expected] of cases) {
        const answer = await apiJson<RouteAnswer>(page, '/v1/admin/workflow-actions', 'POST', route(over));
        expect(answer.errorCode, name).toBe('WF-422-ROUTE');
        expect(ruleAt(answer), name).toContain(expected);
      }
      // A gated action outside the 18 is not an action at all: the request is malformed.
      expect(
        await apiStatus(page, '/v1/admin/workflow-actions', 'POST', route({ otpAction: 'NO_SUCH_OTP' })),
      ).toBe(400);
    },
  );

  test(
    'a route is changed at Headquarters only, with a reason, and one version waits at a time',
    { tag: ['@SRS-22-19', '@SRS-G-37', '@SC-02-0015', '@negative'] },
    async ({ browser }) => {
      const division = await browser.newPage();
      await workAs(
        division,
        'en',
        'sysadm.dh001@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'DH001' },
        'A-08',
      );
      expect(
        (await apiJson<RouteAnswer>(division, '/v1/admin/workflow-actions', 'POST', route())).errorCode,
      ).toBe('ADM-403-HQ-ONLY');

      const admin = await browser.newPage();
      await workAs(admin, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-08');
      expect(await apiStatus(admin, '/v1/admin/workflow-actions', 'POST', route({ reason: '' }))).toBe(400);
      const proposal = route();
      const first = await apiJson<RouteAnswer>(admin, '/v1/admin/workflow-actions', 'POST', proposal);
      expect(first.approvalId, JSON.stringify(first)).toBeGreaterThan(0);
      expect(
        (
          await apiJson<RouteAnswer>(admin, '/v1/admin/workflow-actions', 'POST', {
            ...proposal,
            actionName: 'E2E second version',
          })
        ).errorCode,
      ).toBe('WF-409-ROUTE-PENDING');

      // Rejected by the checker: the action never comes into force.
      const checker = await browser.newPage();
      await workAs(checker, 'en', 'checker.hq@fci-demo.test', CHECKER, 'W-01');
      expect(
        await apiStatus(checker, `/v1/approvals/${first.approvalId}/decision`, 'POST', {
          decision: 'REJECT',
          reason: 'E2E: route not wanted',
        }),
      ).toBe(200);
      await admin.reload();
      await allRoutes(admin);
      await expect(admin.getByRole('row', { name: new RegExp(proposal.actionCode) })).toContainText(
        t('en', 'a08.notInForce'),
      );
    },
  );
});
