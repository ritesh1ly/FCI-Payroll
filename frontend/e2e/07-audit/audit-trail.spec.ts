import { expect, test, type Page } from '@playwright/test';
import {
  apiStatus,
  businessToday,
  dataRows,
  decide,
  employeeList,
  m16OtherListRows,
  signIn,
  t,
  workAs,
} from '../support/app';

/**
 * Module 03 - X-04 Audit Trail & Change History (screen A-05) and the History button (SRS-20-2 …
 * 29): a record's change history with its audit fields, its state as of a date, the Legacy Audit
 * view beside the native trail (never merged, scoped, every enquiry logged), the reports, history
 * that nobody can change, and the retention periods as settings. Re-runnable: each run adds its own
 * M-16 value (dated 2090) and changes nothing else.
 */

const ADMIN = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };

/** "As it stood on" - the start of the as-of panel of a record rebuilt from its history. */
const REBUILT = (t('en', 'x04.asOf.rebuilt', ['']).split('(')[0] ?? '').trim();

/** The undrawn enquiry sits in a closed section; a second click would close it. */
async function openEnquiry(page: Page) {
  const section = page.getByTestId('x04-enquiry');
  if (!(await section.evaluate((d) => (d as HTMLDetailsElement).open))) {
    await section.locator('> summary').click();
  }
}

async function openRecord(page: Page, typeLabel: string, key: string, asOf = '') {
  await openEnquiry(page);
  const type = page.getByLabel(t('en', 'x04.type'));
  await expect(type.locator('option')).not.toHaveCount(1);
  await type.selectOption({ label: typeLabel });
  await page.getByLabel(t('en', 'x04.key')).fill(key);
  // Always set: the field keeps an earlier look's date, which would answer "as of" that date.
  await page.getByLabel(t('en', 'x04.asOf')).fill(asOf);
  await page.getByRole('button', { name: t('en', 'x04.show') }).click();
  // The record's change history is a closed section too; a second click would close it.
  const history = page.getByTestId('x04-record-history');
  await history.waitFor();
  if (!(await history.evaluate((d) => (d as HTMLDetailsElement).open))) {
    await history.locator('> summary').click();
  }
}

const legacyCard = (page: Page) =>
  page.locator('.card').filter({ has: page.getByRole('heading', { name: t('en', 'x04.legacy.title') }) });
const nativeCard = (page: Page) => page.getByTestId('x04-record-history');
/** SRS-20-3: the record as of a date is printed on the drawn Point-in-Time Reconstruction card (OD-121). */
const pitCard = (page: Page) =>
  page
    .locator('.card')
    .filter({ has: page.getByRole('heading', { name: t('en', 'x04w.pit'), exact: true }) });

test.describe('X-04 record history', () => {
  test(
    'an approved master change shows each audit field - outcome, maker and checker, effective date - the record as of a date, and the Change History Report',
    {
      tag: [
        '@SRS-20-29',
        '@SRS-20-2',
        '@SRS-20-3',
        '@SC-03-0001',
        '@SC-03-0002',
        '@SC-03-0011',
        '@SC-03-0012',
        '@SC-03-0016',
        '@SC-03-0017',
        '@SC-03-0018',
        '@SC-03-0019',
        '@SC-03-0021',
        '@SC-03-0022',
        '@SC-03-0023',
        '@SC-03-0024',
        '@SC-03-0025',
        '@SC-03-0026',
        '@SC-03-0027',
        '@SC-03-0028',
        '@positive',
      ],
    },
    async ({ browser }) => {
      const code = `E2E_AUD_${Date.now() % 1_000_000}`;
      // Unique per run: M-16 refuses a label the list already holds (M16-05), and an earlier run's value keeps it.
      const label = `E2E Audit Office ${code}`;
      const admin = await browser.newPage();
      await workAs(admin, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-05');
      expect(
        await apiStatus(admin, '/v1/masters/reference-lists/ISSUING_AUTHORITY/values', 'POST', {
          code,
          labelEn: label,
          sortOrder: 900,
          effectiveFrom: '2090-01-01',
          reason: 'E2E: audit trail',
        }),
      ).toBe(202);
      const checker = await browser.newPage();
      await workAs(
        checker,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'W-01',
      );
      await decide(checker, new RegExp(code), 'APPROVE');
      await checker.close();

      await openRecord(
        admin,
        t('en', 'x04.entity.reference_value'),
        `ISSUING_AUTHORITY.${code}`,
        '2090-01-01',
      );
      const native = nativeCard(admin);
      // The record as it stood on its first day, rebuilt from the change history, field by field.
      const pit = pitCard(admin);
      await expect(pit.getByText(REBUILT).first()).toBeVisible();
      await expect(pit.getByText('value_label_en', { exact: true })).toBeVisible();
      await expect(pit.getByText(label, { exact: true })).toBeVisible();
      // Each change with its audit fields: the applied label, its outcome, maker and checker.
      for (const header of [
        'x04.when',
        'x04.txn',
        'x04.old',
        'x04.new',
        'x04.outcome',
        'x04.effective',
        'x04.maker',
        'x04.checker',
        'x04.authorisation',
        'x04.ip',
        'x04.origin',
      ]) {
        await expect(native.getByRole('columnheader', { name: t('en', header) })).toBeVisible();
      }
      const applied = native.getByRole('row', { name: /value_label_en/ });
      await expect(applied).toContainText(label);
      await expect(applied).toContainText(t('en', 'x04.outcome.POSTED'));
      await expect(applied).toContainText(t('en', 'x04.origin.NATIVE'));
      await expect(applied).toContainText(/APR-\d+/);
      // SRS-G-2 / SRS-20 field table: the values, not only the columns - maker and checker as
      // signed in (name and employee number), the reason, the effective date, the transaction ID
      // and the acting user's IP address, each in its own cell.
      await expect(applied).toContainText('System Administrator');
      await expect(applied).toContainText('Harish Chandra (007910)');
      await expect(applied).toContainText('E2E: audit trail');
      // As the screen formats a calendar date (shared/ui date: en-IN, 2-digit day, short month).
      const effective = new Date(Date.UTC(2090, 0, 1)).toLocaleDateString('en-IN', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        timeZone: 'UTC',
      });
      await expect(applied).toContainText(effective);
      const cells = applied.getByRole('cell');
      await expect(cells.nth(1)).toHaveText(/^\d+$/);
      // The IP address cell: IPv4 or IPv6 characters only (one class, no backtracking).
      const texts = (await cells.allTextContents()).map((c) => c.trim());
      expect(texts.some((c) => /^[0-9a-fA-F:.]{3,45}$/.test(c) && /[.:]/.test(c))).toBe(true);

      // SRS-20-29 Change History Report, as CSV.
      await native
        .getByRole('button', { name: t('en', 'action.export') })
        .first()
        .click();
      const dialog = admin.getByRole('dialog');
      await dialog.locator('input[value="CSV"]').check();
      const downloading = admin.waitForEvent('download');
      await dialog.getByRole('link', { name: t('en', 'export.download') }).click();
      expect((await downloading).suggestedFilename()).toBe('change-history.csv');
      await admin.close();
    },
  );

  test(
    'the Legacy Audit rows stand beside the native trail, marked legacy-origin, shown as held, and export apart',
    {
      tag: [
        '@SRS-20-23',
        '@SRS-20-20',
        '@SRS-20-22',
        '@SRS-20-24',
        '@SC-03-0005',
        '@SC-03-0030',
        '@SC-03-0032',
        '@SC-03-0034',
        '@SC-03-0035',
        '@SC-03-0036',
        '@SC-03-0037',
        '@SC-03-0038',
        '@SC-03-0039',
        '@positive',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-05');
      await openRecord(page, t('en', 'x04.entity.employee'), '104882');
      const legacy = legacyCard(page);
      await expect(page.getByTestId('x04-enquiry').getByText(t('en', 'x04.legacy.logged'))).toBeVisible();
      // Headquarters sees every legacy row of the member, the national DDL_AUDIT row too.
      await expect(legacy.getByText(t('en', 'x04.origin.LEGACY'), { exact: true })).toHaveCount(4);
      await expect(legacy.getByText('PAY_EMP_BASIC_HIST')).toBeVisible();
      await expect(legacy.getByText('DDL_AUDIT')).toBeVisible();
      // A *_HIST row is a row snapshot, never split into before / after.
      await expect(legacy.getByText('BASIC: 49500')).toBeVisible();
      // The native side keeps its own rows; nothing legacy is merged into it.
      await expect(nativeCard(page).getByText('PAY_EMP_BASIC_HIST')).toHaveCount(0);

      await legacy.getByRole('button', { name: t('en', 'action.export') }).click();
      const dialog = page.getByRole('dialog');
      await dialog.locator('input[value="CSV"]').check();
      const downloading = page.waitForEvent('download');
      await dialog.getByRole('link', { name: t('en', 'export.download') }).click();
      expect((await downloading).suggestedFilename()).toBe('legacy-audit.csv');
    },
  );

  test(
    'a zone administrator sees only its zone: no national legacy row, and a member of another zone is refused',
    { tag: ['@SC-03-0035', '@SRS-20-23', '@SRS-20-8', '@SC-03-0040', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'rbac.admin.north@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'NA11' },
        'A-05',
      );
      await openRecord(page, t('en', 'x04.entity.employee'), '104882');
      const legacy = legacyCard(page);
      await expect(legacy.getByText(t('en', 'x04.origin.LEGACY'), { exact: true })).toHaveCount(3);
      await expect(legacy.getByText('DDL_AUDIT')).toHaveCount(0);

      await page.getByLabel(t('en', 'x04.key')).fill('204913');
      await page.getByRole('button', { name: t('en', 'x04.show') }).click();
      // Another zone's member reads as an unknown record, never as "outside your scope".
      await expect(page.getByText(t('en', 'error.AUD-404-RECORD')).first()).toBeVisible();
    },
  );
});

test.describe('X-04 record history: keys, dates and outcomes', () => {
  test(
    'SRS-20-2: a key that resolves to no record is refused, exactly as one outside the scope',
    { tag: ['@SRS-20-2', '@SC-03-0016', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-05');
      await openRecord(page, t('en', 'x04.entity.reference_value'), 'ISSUING_AUTHORITY.NO_SUCH_VALUE');
      await expect(page.getByText(t('en', 'error.AUD-404-RECORD')).first()).toBeVisible();
      await expect(nativeCard(page).locator('tbody tr')).toHaveCount(0);
    },
  );

  test(
    'SRS-20-3: before its first version a record was not in force; from it, the version is shown',
    { tag: ['@SRS-20-3', '@SC-03-0017', '@SC-03-0002', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'M-16');
      await page.locator('#m16-list').selectOption('CIRCULAR_CATEGORY');
      const code =
        (await m16OtherListRows(page).first().locator('td code').first().textContent())?.trim() ?? '';
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-05');
      await openRecord(
        page,
        t('en', 'x04.entity.reference_value'),
        `CIRCULAR_CATEGORY.${code}`,
        '1990-01-01',
      );
      const notInForce = t('en', 'x04.asOf.notInForce', ['§']).split('§')[0] ?? '';
      await expect(pitCard(page).getByText(notInForce)).toBeVisible();
      // From its first version on (today), the record is in force and its version is shown.
      await openRecord(
        page,
        t('en', 'x04.entity.reference_value'),
        `CIRCULAR_CATEGORY.${code}`,
        businessToday(),
      );
      await expect(
        pitCard(page)
          .getByText(new RegExp(`^${REBUILT} `))
          .first(),
      ).toBeVisible();
      await expect(pitCard(page).getByText(notInForce)).toHaveCount(0);
    },
  );

  test(
    'FAP-13: a rejected change shows as submitted and rejected, and nothing is posted',
    { tag: ['@SRS-20-2', '@SC-03-0019', '@SC-03-0021', '@SC-03-0015', '@edge'] },
    async ({ page }) => {
      const stamp = Date.now();
      const reason = `E2E rejected relabel ${stamp}`;
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'M-16');
      await page.locator('#m16-list').selectOption('CIRCULAR_CATEGORY');
      const row = m16OtherListRows(page).first();
      const code = (await row.locator('td code').first().textContent())?.trim() ?? '';
      const label = (await row.locator('td').nth(1).textContent())?.trim() ?? '';
      expect(
        await apiStatus(page, `/v1/masters/reference-lists/CIRCULAR_CATEGORY/values/${code}`, 'POST', {
          labelEn: `Rejected ${stamp}`,
          reason,
        }),
      ).toBe(202);
      await workAs(
        page,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'W-01',
      );
      await decide(page, new RegExp(reason), 'REJECT', `E2E: not this label ${stamp}`);

      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-05');
      await openRecord(page, t('en', 'x04.entity.reference_value'), `CIRCULAR_CATEGORY.${code}`);
      const mine = nativeCard(page).getByRole('row').filter({ hasText: reason });
      await expect(mine.filter({ hasText: t('en', 'x04.outcome.SUBMITTED') })).toHaveCount(1);
      // The rejection is a row of its own, with the checker's reason.
      await expect(
        nativeCard(page)
          .getByRole('row')
          .filter({ hasText: t('en', 'x04.outcome.REJECTED') })
          .filter({ hasText: `E2E: not this label ${stamp}` }),
      ).toHaveCount(1);
      // Nothing was applied: no posted row carries the rejected label, and the list keeps its label.
      await expect(
        nativeCard(page)
          .getByRole('row')
          .filter({ hasText: `Rejected ${stamp}` })
          .filter({ hasText: t('en', 'x04.outcome.POSTED') }),
      ).toHaveCount(0);
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'M-16');
      await page.locator('#m16-list').selectOption('CIRCULAR_CATEGORY');
      await expect(m16OtherListRows(page).first().locator('td').nth(1)).toHaveText(label);
    },
  );
});

test.describe('History button and history that nobody changes', () => {
  test(
    "the Payroll Maker opens an employee's change history from M-01, read-only",
    { tag: ['@SRS-20-29', '@SC-03-0011', '@positive'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      // As drawn, History sits over the list and reads the first row (OD-121).
      await page.getByLabel(t('en', 'wf.searchInList')).fill('104882');
      await expect(dataRows(employeeList(page))).toHaveCount(1);
      await page
        .getByRole('button', { name: t('en', 'history.for', ['104882 · ']).replace(/ $/, '') })
        .first()
        .click();
      const dialog = page.getByRole('dialog');
      await expect(
        dialog.getByRole('heading', { name: new RegExp(`^${t('en', 'history.title', ['104882'])}`) }),
      ).toBeVisible();
      await expect(dialog.getByText(t('en', 'x04.readOnly'))).toBeVisible();
      await dialog.getByLabel(t('en', 'x04.asOf')).fill('2026-01-01');
      await expect(dialog.getByText(REBUILT).first()).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
      // SRS 14.4 l.5503: X-04 is view only for maker, checker, ldh, hr, fin and admin (OD-125).
      expect(await apiStatus(page, '/v1/audit/record-types')).toBe(200);
    },
  );

  test(
    'any request to create, change or delete history is refused for every role, the System Administrator included',
    {
      tag: [
        '@SRS-20-19',
        '@SRS-20-21',
        '@SC-03-0008',
        '@SC-03-0013',
        '@SC-03-0014',
        '@SC-03-0015',
        '@negative',
      ],
    },
    async ({ page }) => {
      await signIn(page, 'en', 'sysadmin@fci-demo.test');
      await expect(page).toHaveURL(/\/payroll/);
      expect(await apiStatus(page, '/v1/audit/history', 'POST', { reason: 'e2e' })).toBe(405);
      expect(await apiStatus(page, '/v1/audit/legacy', 'PUT', { reason: 'e2e' })).toBe(405);
      expect(await apiStatus(page, '/v1/admin/audit', 'DELETE')).toBe(405);
    },
  );

  test(
    'the retention periods of the audit stores are settings: not decided until FCI sets them',
    { tag: ['@SRS-20-5', '@SC-03-0004', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-07');
      const card = page
        .locator('.card')
        .filter({ has: page.getByRole('heading', { name: t('en', 'a07.retention.title') }) });
      await expect(
        card.getByText(t('en', 'a07.retention.class.NATIVE_AUDIT'), { exact: true }),
      ).toBeVisible();
      await expect(
        card.getByText(t('en', 'a07.retention.class.LEGACY_AUDIT'), { exact: true }),
      ).toBeVisible();
      await expect(card.getByText(t('en', 'a07.retention.note'))).toBeVisible();
      await expect(card.getByText(t('en', 'a07.retention.notDecided'), { exact: true })).toHaveCount(2);
      // Every A-07 setting opens its change history.
      await expect(page.getByRole('button', { name: t('en', 'history.button') }).first()).toBeVisible();
    },
  );

  test(
    'the trail search finds a record type, user or field whatever its case and by part of its name; a typed wildcard is a plain character',
    { tag: ['@SRS-20-2', '@SRS-G-12', '@negative'] },
    async ({ page }) => {
      // The sign-in itself is on the trail (field LOGIN), so the window holds a row.
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-05');
      // The card title with a count of at least one ("Audit events (n)").
      const [before = '', after = ''] = t('en', 'a05.title', ['§']).split('§');
      const escape = (x: string) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const someRows = new RegExp(`${escape(before)}[1-9]\\d*${escape(after)}`);
      const search = async (entity: string, field: string) => {
        await page.getByLabel(t('en', 'a05.entity'), { exact: true }).fill(entity);
        await page.getByLabel(t('en', 'a05.field'), { exact: true }).fill(field);
        await page.getByRole('button', { name: t('en', 'common.search'), exact: true }).click();
      };
      await search('', 'LOGIN');
      await expect(page.getByText(someRows)).toBeVisible();
      // Lower case and a part of the stored name find the same rows.
      await search('', 'login');
      await expect(page.getByText(someRows)).toBeVisible();
      await search('', 'ogi');
      await expect(page.getByText(someRows)).toBeVisible();
      // A typed % or _ matches only itself: no stored name contains a percent sign.
      await search('%', '');
      await expect(page.getByText(t('en', 'a05.none')).first()).toBeVisible();
      await search('', 'LOG_N');
      await expect(page.getByText(t('en', 'a05.none')).first()).toBeVisible();
      // The record types are offered as suggestions on the record type box.
      await expect(page.locator('#a05-entity-types option')).not.toHaveCount(0);
    },
  );
});
