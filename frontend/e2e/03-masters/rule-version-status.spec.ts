import { expect, type Page, test } from '@playwright/test';
import { t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';

/**
 * E2E-37 (SRS-19-11, SRS-19-3, BR-RULE-002): a rule's versions on M-08 carry their status, derived
 * from the approval state and the dates and never stored - Draft, Pending approval, Active and
 * Superseded - and exactly one approved version is in force on a date (the database refuses an
 * overlapping one, FCIPAY-RUL-001).
 *
 * The demo rules carry no value (R-RULE) and M-08 writes a version only on its approval, so no
 * screen produces a version in force or a superseded one on the demo data: the H2 database fixture
 * gives one E2E-only rule (E2E.STATUS.CHAIN, no engine reads it) four versions, inserted when absent
 * so the spec is re-runnable on one stack - v1 approved for April 2026 only (Superseded), v2
 * approved from May 2026 with no end (Active), v3 Draft and v4 Pending approval, both far ahead.
 */
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const KEY = 'E2E.STATUS.CHAIN';

function fixture() {
  const rule = `(SELECT rule_id FROM rule_definition WHERE rule_key = '${KEY}')`;
  const version = (no: number, from: string, to: string | null, value: number, state: string) =>
    `INSERT INTO rule_version (rule_id, version_no, effective_from, effective_to, scalar_value, approval_state, created_by)` +
    ` SELECT ${rule}, ${no}, DATE '${from}', ${to ? `DATE '${to}'` : 'NULL'}, ${value}, '${state}', 'e2e.fixture' FROM dual` +
    ` WHERE NOT EXISTS (SELECT 1 FROM rule_version WHERE rule_id = ${rule} AND version_no = ${no})`;
  sql(
    [
      `INSERT INTO rule_definition (rule_key, rule_type_code, rule_domain, rule_name, description, owning_module, order_required, active_flag)` +
        ` SELECT '${KEY}', 'RATE', 'COMPUTATION', 'E2E status chain (fixture)', 'E2E-only rule: no engine reads it', 'RULES', 'N', 'Y' FROM dual` +
        ` WHERE NOT EXISTS (SELECT 1 FROM rule_definition WHERE rule_key = '${KEY}')`,
      version(1, '2026-04-01', '2026-04-30', 11, 'APPROVED'),
      version(2, '2026-05-01', null, 12, 'APPROVED'),
      version(3, '2300-01-01', null, 13, 'DRAFT'),
      version(4, '2301-01-01', null, 14, 'PENDING_APPROVAL'),
    ].join(';\n'),
  );
}

/** The versions card of the fixture rule, opened from its catalogue row. */
async function versionsOf(page: Page) {
  const catalogue = page
    .locator('.card')
    .filter({ has: page.getByRole('heading', { name: t('en', 'm08w.catalogue') }) });
  await catalogue.getByLabel(t('en', 'page.size')).selectOption('100');
  const row = catalogue.getByRole('row').filter({ has: page.getByText(KEY, { exact: true }) });
  await row.getByRole('button', { name: t('en', 'm08w.viewOf', [KEY]) }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: t('en', 'm08.versions') })
    .click();
  return page
    .locator('.card')
    .filter({ has: page.getByRole('heading', { name: t('en', 'm08.versionsTitle', [KEY]) }) });
}

test.describe('E2E-37 rule version statuses on M-08', () => {
  test.beforeAll(() => {
    if (dbFixtureAvailable()) fixture();
  });

  test(
    'E2E-37: each version shows Draft, Pending approval, Active or Superseded from its state and dates',
    { tag: ['@E2E-37', '@SRS-19-11', '@SRS-19-3', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', HQ_CHECKER, hqChecker, 'M-08');
      const versions = await versionsOf(page);
      const expected: [string, string][] = [
        ['v1', 'SUPERSEDED'],
        ['v2', 'ACTIVE'],
        ['v3', 'DRAFT'],
        ['v4', 'PENDING_APPROVAL'],
      ];
      for (const [version, status] of expected) {
        const row = versions
          .getByRole('row')
          .filter({ has: page.getByRole('cell', { name: new RegExp(`^${version}(?!\\d)`) }) });
        await expect(row, version).toHaveCount(1);
        await expect(row.locator('.pill'), version).toHaveText(t('en', `m08.status.${status}`));
      }
      // The status column is a System field: derived, never keyed.
      await expect(
        versions.getByRole('columnheader', { name: new RegExp(t('en', 'm08.state')) }),
      ).toBeVisible();
    },
  );

  test(
    'E2E-37: a second approved version overlapping the one in force is refused by the database',
    { tag: ['@E2E-37', '@BR-RULE-002', '@negative'] },
    () => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const rule = `(SELECT rule_id FROM rule_definition WHERE rule_key = '${KEY}')`;
      expect(() =>
        sql(
          `INSERT INTO rule_version (rule_id, version_no, effective_from, scalar_value, approval_state, created_by)` +
            ` SELECT ${rule}, 99, DATE '2026-06-01', 99, 'APPROVED', 'e2e.fixture' FROM dual`,
        ),
      ).toThrow(/FCIPAY-RUL-001/);
      const inForce = sqlRows(
        `SELECT COUNT(*) FROM rule_version WHERE rule_id = ${rule} AND approval_state = 'APPROVED'` +
          ` AND effective_from <= DATE '2026-06-01' AND COALESCE(effective_to, DATE '9999-12-31') >= DATE '2026-06-01'`,
      );
      expect(inForce[0]?.[0], 'exactly one version in force on a date').toBe('1');
    },
  );
});
