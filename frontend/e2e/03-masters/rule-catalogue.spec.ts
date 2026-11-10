import { expect, type Page, test } from '@playwright/test';
import { apiProblem, apiStatus, closeDetail, decide, errorAlert, t, workAs } from '../support/app';
import { apiAll, apiJson } from '../support/payroll-support';

/**
 * M-08 Rule / Parameter Umbrella Catalogue through the UI (docs/plan/06-rule-engine.md phase 06c):
 * the catalogue and its read-only roles (SRS-19-14), a version proposed by the Rule Administrator
 * and in force only once a different Rule Approver approves it in W-01 (SRS-19-4), the value each
 * rule type takes (SRS-19-1, SRS-19-5, SRS-19-15), the mandatory Active circular (SRS-19-17), an end
 * date without successor (SRS-19-6) and the pending view across M-08 / M-14 / M-15 (SRS-19-21).
 *
 * Re-runnable on one stack: the demo rules (db/seed V032_9, no value seeded) get versions dated far
 * in the future and later on every run (minutes since 2026 as days after 2200-01-01), so they stay
 * "not configured" today; a change an interrupted run left pending is rejected first.
 */
const RULE_ADMIN = 'su.hq@fci-demo.test';
const RULE_APPROVER = 'rule.approver@fci-demo.test';
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const FINANCE = 'finance.mumbai@fci-demo.test';
const DELHI_MAKER = 'maker.delhi@fci-demo.test';
const DELHI_CHECKER = 'ddo.delhi@fci-demo.test';
const HR = 'hr.delhi@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const ruleAdminAsApprover = { role: 'RULE_APPROVER', office: 'HQ001' };
const ruleApprover = { role: 'RULE_APPROVER', office: 'HQ001' };
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const finance = { role: 'FINANCE', office: 'MR001' };
const delhiMaker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const delhiChecker = { role: 'PAYROLL_CHECKER', office: 'DH001' };
const hr = { role: 'ESTABLISHMENT_HR', office: 'RO-DL' };

const CEILING = 'CPF.SANCTION.LIMIT';
const SLAB = 'INCENTIVE.SLAB';
const APPLICABILITY = 'LWF.348.APPLICABILITY';
const DEFINITION = 'FORM16.DEFINITION';

const stamp = Date.now();
const runDay = (offset = 0) => {
  const minutes = Math.floor((stamp - Date.UTC(2026, 0, 1)) / 60_000);
  return new Date(Date.UTC(2200, 0, 1) + (minutes * 4 + offset) * 86_400_000).toISOString().slice(0, 10);
};
const PDF = Buffer.from('%PDF-1.7\n1 0 obj\n<< >>\nendobj\n', 'ascii');

/** A date as the screens show it (shared/ui date(): Indian day-month-year, short month). */
function shown(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number) as [number, number, number];
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

/** A message with one argument left open, as a pattern ({@code VERSION} matches any number). */
function withAnyVersion(message: string): RegExp {
  const escaped = message.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(escaped.replace('VERSION', '\\d+'));
}

/** The catalogue pages at 20 (SRS-NF-3); the demo catalogue fits on one page of 100. */
async function showAllRules(page: Page) {
  const catalogue = page
    .locator('.card')
    .filter({ has: page.getByRole('heading', { name: t('en', 'm08w.catalogue') }) });
  await catalogue.getByLabel(t('en', 'page.size')).selectOption('100');
}

/** An action of a rule from its drawn View (M08-06). */
async function fromView(page: Page, key: string, name: string) {
  await catalogueRow(page, key)
    .getByRole('button', { name: t('en', 'm08w.viewOf', [key]) })
    .click();
  await page.getByRole('dialog').getByRole('button', { name }).click();
}

/** The catalogue row of a rule (its key may also appear in the pending view). */
function catalogueRow(page: Page, key: string) {
  const catalogue = page
    .locator('.card')
    .filter({ has: page.getByRole('heading', { name: t('en', 'm08w.catalogue') }) });
  return catalogue.getByRole('row').filter({ has: page.getByText(key, { exact: true }) });
}

/** Rejects the M-08 changes an interrupted earlier run left pending (one pending change per rule). */
async function rejectLeftovers(page: Page) {
  const pending = await apiAll<{ screen: string; key: string; approvalId: number }>(
    page,
    '/v1/rules/pending',
  );
  for (const item of pending.filter(
    (p) => p.screen === 'M-08' && [CEILING, SLAB, APPLICABILITY].includes(p.key),
  )) {
    await apiJson(page, `/v1/approvals/${item.approvalId}/decision`, 'POST', {
      decision: 'REJECT',
      reason: 'E2E: left over by an earlier run',
    });
  }
}

async function approveInInbox(page: Page, reason: string) {
  await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
  await decide(page, new RegExp(reason), 'APPROVE');
}

test.describe.serial('M-08 Rule / Parameter Umbrella Catalogue', () => {
  const circular = `E2E/RULE/${stamp}`;

  test(
    'a Payroll Maker and Establishment / HR have no access; tampered keys never reach the service',
    { tag: ['@SRS-19-14', '@SC-06-0012', '@negative'] },
    async ({ page }) => {
      for (const [login, scope, home] of [
        [DELHI_MAKER, delhiMaker, 'W-02'],
        [HR, hr, 'X-06'],
      ] as const) {
        await workAs(page, 'en', login, scope, home);
        await page.goto('/payroll/?s=M-08');
        await expect(page).toHaveURL(/\/payroll\/?$/);
        for (const path of [
          '/v1/rules',
          '/v1/rules/pending',
          '/v1/rules/vocabulary',
          `/v1/rules/${CEILING}/versions`,
        ]) {
          expect(await apiStatus(page, path), `${login} ${path}`).toBe(403);
        }
        expect(
          await apiStatus(page, `/v1/rules/${CEILING}/versions`, 'POST', {
            effectiveFrom: runDay(),
            value: 1,
            circularId: 1,
            reason: 'E2E',
          }),
        ).toBe(403);
      }
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      // An encoded separator is refused by the strict firewall before any handler runs (403 on
      // the deployed stack, as RequestTamperingIT allows); a key outside the pattern is refused by
      // the validation (400) or is unknown (404). None reaches the service.
      expect([400, 403, 404]).toContain(await apiStatus(page, '/v1/rules/..%2F..%2Fetc/versions'));
      for (const tampered of ['%3Cscript%3E', 'A%20B']) {
        expect([400, 404]).toContain(await apiStatus(page, `/v1/rules/${tampered}/versions`));
      }
    },
  );

  test(
    'the Payroll Checker and Finance read the catalogue only; seed values missing are shown, never guessed',
    {
      tag: [
        '@SRS-19-14',
        '@SRS-19-1',
        '@SRS-19-11',
        '@SC-06-0012',
        '@SC-06-0001',
        '@SC-06-0009',
        '@SC-06-0050',
        '@positive',
      ],
    },
    async ({ page }) => {
      for (const [login, scope] of [
        [HQ_CHECKER, hqChecker],
        [FINANCE, finance],
      ] as const) {
        await workAs(page, 'en', login, scope, 'M-08');
        await expect(page.getByText(t('en', 'm08.readOnly'))).toBeVisible();
        // The catalogue pages at 20 (SRS-NF-3): a rule is found by its key.
        await page.getByLabel(t('en', 'm08.search')).fill('DA.IDA.RATE');
        const row = catalogueRow(page, 'DA.IDA.RATE');
        await expect(row).toContainText(t('en', 'm08.ruleType.RATE'));
        await expect(row).toContainText(t('en', 'mst.notConfigured'));
        // SRS-G-25: the drawn Edit stays, disabled with the reason.
        await expect(
          row.getByRole('button', {
            name: new RegExp(`${t('en', 'action.supersede')}|${t('en', 'action.newRuleVersion')}`),
          }),
        ).toBeDisabled();
        await page.getByLabel(t('en', 'm08.search')).fill(DEFINITION);
        await expect(catalogueRow(page, DEFINITION)).toContainText(t('en', 'm08.notMaintained'));
        await expect(page.getByText(t('en', 'm08w.tile.missing'))).toBeVisible();
        await expect(page.getByText(t('en', 'm08.guard.noDefault'))).toBeVisible();
        expect(
          await apiStatus(page, `/v1/rules/${CEILING}/versions`, 'POST', {
            effectiveFrom: runDay(),
            value: 1,
            circularId: 1,
            reason: 'E2E',
          }),
        ).toBe(403);
      }
    },
  );

  test(
    'an Active circular is recorded on M-25 for the rule versions to rest on',
    { tag: ['@SRS-19-17', '@SRS-G-38', '@SC-06-0015', '@positive'] },
    async ({ page }) => {
      const reason = `E2E rule circular ${stamp}`;
      await workAs(page, 'en', DELHI_MAKER, delhiMaker, 'M-25');
      await page.getByRole('button', { name: t('en', 'm25.act.new'), exact: true }).click();
      await page.locator('#nc-number').fill(circular);
      await page.locator('#nc-auth').selectOption('HQ_PERSONNEL');
      await page.locator('#nc-cat').selectOption('LABOUR_NORM');
      await page.locator('#nc-subject').fill(`Rule revision ${stamp}`);
      await page.getByRole('checkbox', { name: t('en', 'm14.type.STAFF') }).check();
      await page
        .locator('#nc-file')
        .setInputFiles({ name: 'order.pdf', mimeType: 'application/pdf', buffer: PDF });
      await page.locator('#nc-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm25.submitted', [circular]));
      await workAs(page, 'en', DELHI_CHECKER, delhiChecker, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');
    },
  );

  test(
    'a first value is proposed with its date and circular, refused for its approver role and its proposer, then approved',
    {
      tag: [
        '@SRS-19-4',
        '@SRS-19-12',
        '@SRS-19-17',
        '@SRS-19-10',
        '@SC-06-0004',
        '@SC-06-0008',
        '@SC-06-0010',
        '@SC-06-0044',
        '@SC-06-0047',
        '@SC-06-0049',
        '@SC-06-0051',
        '@SC-06-0053',
        '@SC-06-0054',
        '@SC-06-0055',
        '@positive',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'M-08');
      await showAllRules(page);
      await rejectLeftovers(page);
      const reason = `E2E ceiling ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      const row = catalogueRow(page, CEILING);
      await row
        .getByRole('button', {
          name: new RegExp(`(${t('en', 'action.newRuleVersion')}|${t('en', 'action.supersede')})`),
        })
        .click();
      const submit = page.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await expect(submit).toBeDisabled();
      await expect(page.getByText(t('en', 'm08.need.value'))).toBeVisible();
      await page.locator('#rv-from').fill(runDay());
      // SRS-19-6: an effective-to before the effective-from is named and blocks submission.
      await page.locator('#rv-to').fill(runDay(-1));
      await expect(page.getByText(t('en', 'm08.need.toAfterFrom'))).toBeVisible();
      await page.locator('#rv-to').fill('');
      // Numeric (12,2): three decimals are named.
      await page.locator('#rv-value').fill('500000.125');
      await expect(page.getByText(t('en', 'm08.need.amount'))).toBeVisible();
      await page.locator('#rv-value').fill('500000');
      const option = await page.locator('#rv-circular option', { hasText: circular }).getAttribute('value');
      await page.locator('#rv-circular').selectOption(option ?? '');
      await page.locator('#rv-reason').fill(reason);
      await expect(submit).toBeEnabled();
      await submit.click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm08.submitted', [CEILING]));
      // One pending change per rule: no second proposal, the pending version is shown. A rule with no
      // approved version names its row edit "New rule version", one with a version "Supersede".
      await expect(catalogueRow(page, CEILING).locator('.pill.p-warn')).toBeVisible();
      await expect(
        catalogueRow(page, CEILING).getByRole('button', {
          name: new RegExp(`(${t('en', 'action.newRuleVersion')}|${t('en', 'action.supersede')})`),
        }),
      ).toBeDisabled();
      // SRS-19-4 (06e): the pending version is simulated on a sample before it is approved.
      await fromView(page, CEILING, t('en', 'm08.sim.of', [CEILING]));
      const simulation = page
        .locator('.card')
        .filter({ has: page.getByRole('heading', { name: t('en', 'm08.sim.title', [CEILING]) }) });
      await simulation.getByLabel(t('en', 'm08.sim.payrollType')).selectOption('STAFF');
      await simulation.getByRole('button', { name: t('en', 'm08.sim.run') }).click();
      await expect(page.getByText(t('en', 'm08.sim.done'))).toBeVisible();
      await expect(simulation.getByText(/^Population \d+; sampled \d+/)).toBeVisible();
      await simulation.getByRole('button', { name: t('en', 'common.close') }).click();

      // No self-approval (SRS-G-1, BR-RULE-005): the proposer holding Rule Approver sees the reason.
      await workAs(page, 'en', RULE_ADMIN, ruleAdminAsApprover, 'W-01');
      const own = page.getByRole('row', { name: new RegExp(reason) });
      await own.getByRole('button', { name: t('en', 'wf.view') }).click();
      await expect(
        page.getByRole('dialog').getByRole('button', { name: t('en', 'w01.APPROVE') }),
      ).toBeDisabled();
      await closeDetail(page);

      await approveInInbox(page, reason);
      // In force from its stated date: approved for the future, so not configured today.
      await workAs(page, 'en', HQ_CHECKER, hqChecker, 'M-08');
      await showAllRules(page);
      await fromView(page, CEILING, t('en', 'm08.versions'));
      const versions = page
        .locator('.card')
        .filter({ has: page.getByRole('heading', { name: t('en', 'm08.versionsTitle', [CEILING]) }) });
      await expect(versions).toContainText(t('en', 'm08.status.APPROVED_FUTURE'));
      await expect(versions).toContainText('500000');
      await expect(versions).toContainText(circular);
      await expect(versions).toContainText(RULE_ADMIN);
      // US-GAP-157 (FRS 24 audit): each version shows who approved it, a System field.
      await expect(
        versions.getByRole('columnheader', { name: new RegExp(t('en', 'm08.approvedBy')) }),
      ).toBeVisible();
      const approved = versions.getByRole('row').filter({ hasText: circular });
      await expect(approved).toContainText(RULE_APPROVER);
    },
  );

  test(
    'a version on or before the latest start is refused naming the conflicting version',
    { tag: ['@SC-06-0010', '@SRS-19-12', '@SC-06-0045', '@SC-06-0051', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      await catalogueRow(page, CEILING)
        .getByRole('button', { name: new RegExp(t('en', 'action.supersede')) })
        .click();
      await page.locator('#rv-from').fill(runDay());
      await page.locator('#rv-value').fill('600000');
      const option = await page.locator('#rv-circular option', { hasText: circular }).getAttribute('value');
      await page.locator('#rv-circular').selectOption(option ?? '');
      await page.locator('#rv-reason').fill(`E2E overlap ${stamp}`);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      // The latest approved version is this run's, from runDay(): it is named with its start.
      await expect(errorAlert(page)).toContainText(
        withAnyVersion(t('en', 'error.RULE-409-OVERLAP', ['VERSION', runDay()])),
      );
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
    },
  );

  test(
    'a slab with a gap is refused; contiguous bands are proposed and returned for correction',
    { tag: ['@SRS-19-5', '@SC-06-0005', '@SC-06-0046', '@SC-06-0053', '@negative'] },
    async ({ page }) => {
      const reason = `E2E slab ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      await catalogueRow(page, SLAB)
        .getByRole('button', {
          name: new RegExp(`(${t('en', 'action.newRuleVersion')}|${t('en', 'action.supersede')})`),
        })
        .click();
      await page.locator('#rv-from').fill(runDay(1));
      await page.getByLabel(t('en', 'm08.bandFromOf', [1])).fill('0');
      await page.getByLabel(t('en', 'm08.bandToOf', [1])).fill('1000');
      await page.getByLabel(t('en', 'm08.rateOrAmountOf', [1])).fill('0');
      await page.getByRole('button', { name: t('en', 'm08.addBand') }).click();
      // A gap: band 2 starts at 1500 instead of 1000.
      await page.getByLabel(t('en', 'm08.bandFromOf', [2])).fill('1500');
      await page.getByLabel(t('en', 'm08.rateOrAmountOf', [2])).fill('5');
      const option = await page.locator('#rv-circular option', { hasText: circular }).getAttribute('value');
      await page.locator('#rv-circular').selectOption(option ?? '');
      await page.locator('#rv-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(errorAlert(page)).toContainText(
        t('en', 'error.RULE-422-SLAB', ['gap between band 1 and band 2']),
      );
      // Contiguous: accepted, pending, then returned by the approver (re-runnable).
      await page.getByLabel(t('en', 'm08.bandFromOf', [2])).fill('1000');
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm08.submitted', [SLAB]));
      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
      await decide(page, new RegExp(reason), 'RETURN', 'E2E: returned for correction');
      // A returned proposal leaves nothing behind: its date can be proposed again (plan 06 D-7).
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      await expect(
        catalogueRow(page, SLAB).getByRole('button', {
          name: new RegExp(`(${t('en', 'action.newRuleVersion')}|${t('en', 'action.supersede')})`),
        }),
      ).toBeVisible();
    },
  );

  test(
    'SRS-19-5: slab bands that overlap are refused, on the form and on a forged request',
    { tag: ['@SRS-19-5', '@SC-06-0005', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      await catalogueRow(page, SLAB)
        .getByRole('button', {
          name: new RegExp(`(${t('en', 'action.newRuleVersion')}|${t('en', 'action.supersede')})`),
        })
        .click();
      await page.locator('#rv-from').fill(runDay(2));
      await page.getByLabel(t('en', 'm08.bandFromOf', [1])).fill('0');
      await page.getByLabel(t('en', 'm08.bandToOf', [1])).fill('1000');
      await page.getByLabel(t('en', 'm08.rateOrAmountOf', [1])).fill('0');
      await page.getByRole('button', { name: t('en', 'm08.addBand') }).click();
      // An overlap: band 2 starts at 500, inside band 1.
      await page.getByLabel(t('en', 'm08.bandFromOf', [2])).fill('500');
      await page.getByLabel(t('en', 'm08.rateOrAmountOf', [2])).fill('5');
      const option = await page.locator('#rv-circular option', { hasText: circular }).getAttribute('value');
      await page.locator('#rv-circular').selectOption(option ?? '');
      await page.locator('#rv-reason').fill(`E2E overlap ${stamp}`);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      const prefix = t('en', 'error.RULE-422-SLAB', ['§']).split('§')[0] ?? '';
      await expect(errorAlert(page)).toContainText(prefix);
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      // The same bands forged past the form: the server refuses them as well.
      const circularId = Number(option);
      expect(
        await apiProblem(page, `/v1/rules/${SLAB}/versions`, 'POST', {
          effectiveFrom: runDay(2),
          circularId,
          bands: [
            { order: 1, from: 0, to: 1000, rateOrAmount: 0 },
            { order: 2, from: 500, to: null, rateOrAmount: 5 },
          ],
          reason: `E2E overlap forged ${stamp}`,
        }),
      ).toEqual({ status: 422, errorCode: 'RULE-422-SLAB' });
    },
  );

  test(
    'a formula outside the grammar is refused; a condition of the grammar is accepted with its vocabulary shown',
    { tag: ['@SRS-19-15', '@SRS-19-8', '@SC-06-0013', '@SC-06-0007', '@edge'] },
    async ({ page }) => {
      const reason = `E2E applicability ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      await catalogueRow(page, APPLICABILITY)
        .getByRole('button', {
          name: new RegExp(`(${t('en', 'action.newRuleVersion')}|${t('en', 'action.supersede')})`),
        })
        .click();
      await expect(page.getByLabel(t('en', 'm08.condition'), { exact: true })).toBeVisible();
      await expect(page.getByText('ROUND(2)')).toBeVisible();
      await page.locator('#rv-from').fill(runDay(2));
      const option = await page.locator('#rv-circular option', { hasText: circular }).getAttribute('value');
      await page.locator('#rv-circular').selectOption(option ?? '');
      await page.locator('#rv-reason').fill(reason);
      for (const [formula, fault] of [
        ['System.exit(0)', 'System.exit'],
        ['SQRT(4) > 1', 'SQRT'],
        ['1 + 1', 'condition'],
      ] as const) {
        await page.locator('#rv-formula').fill(formula);
        await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
        await expect(errorAlert(page), formula).toContainText(fault);
      }
      await page.locator('#rv-formula').fill('1 = 1');
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm08.submitted', [APPLICABILITY]));
      await workAs(page, 'en', RULE_APPROVER, ruleApprover, 'W-01');
      await decide(page, new RegExp(reason), 'REJECT', 'E2E: not needed');
    },
  );

  test(
    'the umbrella view lists a pending M-08 change for the Payroll Checker',
    { tag: ['@SRS-19-21', '@SC-06-0019', '@positive'] },
    async ({ page }) => {
      const reason = `E2E end ${stamp}`;
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      await fromView(page, CEILING, t('en', 'm08.endOf', [CEILING]));
      await expect(page.getByText(t('en', 'm08.endHint'))).toBeVisible();
      await page.locator('#re-on').fill(runDay(-10));
      await page.locator('#re-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      // SRS-19-6: an end before the version's start is refused naming the start.
      await expect(errorAlert(page)).toContainText(t('en', 'error.RULE-422-END', [runDay()]));
      await page.locator('#re-on').fill(runDay(3));
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm08.endSubmitted', [CEILING]));

      await workAs(page, 'en', HQ_CHECKER, hqChecker, 'M-08');
      await showAllRules(page);
      // The umbrella list sits in a closed section under the drawn catalogue (OD-121).
      await page.locator('details > summary', { hasText: t('en', 'm08w.pendingList') }).click();
      const umbrella = page
        .locator('.card')
        .filter({ has: page.getByRole('heading', { name: t('en', 'm08.pendingTitle') }) });
      await expect(umbrella.getByRole('row').filter({ hasText: CEILING })).toContainText(
        t('en', 'entity.rule_version_end'),
      );
    },
  );

  test(
    'an end date without successor is approved and the version keeps its history',
    { tag: ['@SRS-19-6', '@SC-06-0006', '@SC-06-0048', '@SC-06-0052', '@positive'] },
    async ({ page }) => {
      await approveInInbox(page, `E2E end ${stamp}`);
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-08');
      await showAllRules(page);
      await fromView(page, CEILING, t('en', 'm08.versions'));
      const versions = page
        .locator('.card')
        .filter({ has: page.getByRole('heading', { name: t('en', 'm08.versionsTitle', [CEILING]) }) });
      await expect(versions).toContainText(shown(runDay(3)));
    },
  );

  test('M-08 reads in Hindi', { tag: ['@SRS-19-14', '@SC-06-0012', '@edge'] }, async ({ page }) => {
    await workAs(page, 'hi', HQ_CHECKER, hqChecker, 'M-08');
    await expect(page.getByText(t('hi', 'm08.catalogue'), { exact: true })).toBeVisible();
    await expect(page.getByText(t('hi', 'm08.tile.notConfigured'))).toBeVisible();
  });
});
