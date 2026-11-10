import { expect, type Page, test } from '@playwright/test';
import { apiStatus, dataRows, employeeList, t, workAs } from '../support/app';

/**
 * Switching office, region or zone in the top bar (demo data in every office: seed V024_9). The open
 * screen stays and reloads for the new office at once, never shows the previous office's data, and
 * starts fresh (no record or deep link of the previous office); a screen the new scope may not open
 * gives way to the dashboard. Each office level shows exactly its own subtree.
 */
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const checker = (office: string) => ({ role: 'PAYROLL_CHECKER', office });

/** The EIS numbers the M-01 list shows now. */
async function eisShown(page: Page): Promise<string[]> {
  return dataRows(employeeList(page)).locator('td:first-child').allTextContents();
}

/** Picks an office in the top bar (as the user does) without navigating anywhere. */
async function pickOffice(page: Page, office: string) {
  await page.getByLabel(t('en', 'top.office'), { exact: true }).selectOption(office);
  await expect(page.locator('.rolebar')).toContainText(office);
}

/** An employee of Delhi HQ (North Zone) and one of Kolkata (East Zone). */
const NORTH_EIS = '104882';
const EAST_EIS = '401001';
const NORTH = [NORTH_EIS, '107001'];

test.describe('Office, region and zone switching reloads the open screen', () => {
  test(
    'M-01 stays open and shows the new zone only, never the previous one',
    { tag: ['@SRS-G-29', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HQ_CHECKER, checker('NA11'), 'M-01');
      await expect(employeeList(page).getByRole('cell', { name: NORTH_EIS, exact: true })).toBeVisible();

      await pickOffice(page, 'ZO-E');
      // The same screen, reloaded: no navigation away.
      await expect(page).toHaveURL(/\?s=M-01$/);
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'screen.M-01') })).toBeVisible();
      await expect(employeeList(page).getByRole('cell', { name: EAST_EIS, exact: true })).toBeVisible();
      for (const eis of NORTH)
        await expect(page.getByRole('cell', { name: eis, exact: true })).toHaveCount(0);
    },
  );

  test(
    'no previous-office row appears even for a moment after the switch',
    { tag: ['@SRS-G-29', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', HQ_CHECKER, checker('NA11'), 'M-01');
      await expect(employeeList(page).getByRole('cell', { name: NORTH_EIS, exact: true })).toBeVisible();
      // From the moment the switch is done (the role bar names the new office) until the new data is in,
      // the list must never show a row of the previous office. (While the switch request is still on its
      // way the previous office is still the current one.)
      const seen = new Set<string>();
      const office = page.getByLabel(t('en', 'top.office'), { exact: true });
      await office.selectOption('ZO-E');
      await expect(page.locator('.rolebar')).toContainText('ZO-E');
      const until = Date.now() + 6_000;
      while (Date.now() < until) {
        const rows = await eisShown(page);
        rows.forEach((e) => seen.add(e));
        if (rows.includes(EAST_EIS)) break;
        await page.waitForTimeout(25);
      }
      expect([...seen].filter((e) => e.startsWith('10'))).toEqual([]);
    },
  );

  test(
    'an open dossier and a deep-linked EIS of the previous office do not survive the switch',
    { tag: ['@SRS-G-29', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', HQ_CHECKER, checker('NA11'), 'M-01');
      await page.goto(`/payroll/?s=M-01&eis=${NORTH_EIS}`);
      // The deep link opens the record in its dialog (as a row's View does).
      await expect(page.getByText(/Rakesh/).first()).toBeVisible();
      await pickOffice(page, 'ZO-W');
      await expect(page).toHaveURL(/\?s=M-01$/);
      await expect(page.getByText(/Rakesh/)).toHaveCount(0);
    },
  );

  test(
    'a screen the new role may not open gives way to the dashboard',
    { tag: ['@SRS-G-25', '@SC-01-0025', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', 'agm.north@fci-demo.test', checker('NA11'), 'W-01');
      await page.getByLabel(t('en', 'top.role'), { exact: true }).selectOption('REPORT_ADMINISTRATOR@NA11');
      await expect(page.locator('.rolebar b')).toContainText(t('en', 'role.REPORT_ADMINISTRATOR'));
      await expect(page).not.toHaveURL(/s=W-01/);
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'screen.W-01') })).toHaveCount(0);
    },
  );

  test(
    'C-02 follows the office: each office its own cycle board',
    {
      tag: [
        '@SRS-G-29',
        '@BR-CYC-008',
        '@SC-05-0004',
        '@SC-05-0020',
        '@SC-05-0087',
        '@SC-05-0098',
        '@positive',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', HQ_CHECKER, checker('KL001'), 'C-02');
      await expect(
        page
          .locator('main')
          .getByText(/Kolkata Divisional Office \(KL001\)/)
          .first(),
      ).toBeAttached();
      await pickOffice(page, 'CH001');
      await expect(page).toHaveURL(/\?s=C-02$/);
      await expect(
        page
          .locator('main')
          .getByText(/Chennai Divisional Office \(CH001\)/)
          .first(),
      ).toBeAttached();
      await expect(page.locator('main').getByText(/KL001/)).toHaveCount(0);
    },
  );
});

test.describe('each office level shows exactly its subtree', () => {
  // HQ, zone, region, DO: an employee of each level of the East Zone (seed V024_9).
  const levels: [string, string[], string[]][] = [
    ['HQ001', ['104882', '204913', '300101', '400001', '401001'], []],
    ['ZO-E', ['400001', '400101', '401001', '402001'], ['104882', '204913', '301001']],
    ['RO-WB', ['400101', '401001', '402001'], ['400001', '104882']],
    ['KL001', ['401001', '402001'], ['400001', '400101', '301001']],
    ['CH001', ['301001', '302001'], ['300001', '300101', '401001']],
  ];
  for (const [office, shown, hidden] of levels) {
    test(
      `${office}: its own offices, nothing above or beside it`,
      { tag: ['@SRS-G-29', '@SRS-NF-6', '@positive'] },
      async ({ page }) => {
        await workAs(page, 'en', HQ_CHECKER, checker(office), 'M-01');
        // The list pages at 50: search each EIS (the server filters the scope).
        for (const eis of shown) {
          await page.getByLabel(t('en', 'wf.searchInList')).fill(eis);
          await expect(employeeList(page).getByRole('cell', { name: eis, exact: true })).toBeVisible();
        }
        for (const eis of hidden) {
          await page.getByLabel(t('en', 'wf.searchInList')).fill(eis);
          await expect(page.getByRole('cell', { name: eis, exact: true })).toHaveCount(0);
        }
      },
    );
  }
});

test.describe('East and South offices of their own', () => {
  test(
    'the Kolkata maker sees Kolkata only; the Delhi checker cannot reach a Kolkata employee',
    { tag: ['@SRS-G-29', '@negative'] },
    async ({ browser }) => {
      const kolkata = await browser.newPage();
      await workAs(
        kolkata,
        'en',
        'maker.kolkata@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'KL001' },
        'M-01',
      );
      await expect(employeeList(kolkata).getByRole('cell', { name: '401003', exact: true })).toBeVisible();
      await expect(kolkata.getByRole('cell', { name: '104882', exact: true })).toHaveCount(0);
      expect(await apiStatus(kolkata, '/v1/employees/104882')).not.toBe(200);

      const delhi = await browser.newPage();
      await workAs(delhi, 'en', 'ddo.delhi@fci-demo.test', checker('DH001'), 'W-01');
      expect(await apiStatus(delhi, '/v1/employees/401003')).not.toBe(200);
    },
  );

  test(
    'the East zone checker covers the whole East Zone and nothing else',
    { tag: ['@SRS-G-29', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'agm.east@fci-demo.test', checker('ZO-E'), 'M-01');
      await page.getByLabel(t('en', 'wf.searchInList')).fill('402001');
      await expect(employeeList(page).getByRole('cell', { name: '402001', exact: true })).toBeVisible();
      await page.getByLabel(t('en', 'wf.searchInList')).fill('301001');
      await expect(page.getByRole('cell', { name: '301001', exact: true })).toHaveCount(0);
      // A scope it does not hold cannot be forced.
      expect(
        await apiStatus(page, '/auth/switch-scope', 'POST', { role: 'PAYROLL_CHECKER', location: 'ZO-S' }),
      ).toBe(403);
    },
  );

  test(
    'edge-case employees of the new offices are flagged, not hidden',
    { tag: ['@SRS-G-45', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.chennai@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'CH001' },
        'M-01',
      );
      const statusFilter = (p: Page) => p.getByRole('combobox', { name: t('en', 'm01.status'), exact: true });
      await statusFilter(page).selectOption('PAN_CPF_MISSING');
      await expect(employeeList(page).getByRole('cell', { name: '301005', exact: true })).toBeVisible();
      // FND follows CDA (V022 admits it): listed under its own pattern.
      await statusFilter(page).selectOption('');
      // FND stays behind More filters (WF-225), outside the drawn pattern filter.
      await page.getByText(t('en', 'm01.w.moreFilters')).click();
      await page.getByLabel(t('en', 'm01.w.fndOnly')).check();
      await expect(employeeList(page).getByRole('cell', { name: '301006', exact: true })).toBeVisible();
      await workAs(
        page,
        'en',
        'maker.kolkata@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'KL001' },
        'M-01',
      );
      await statusFilter(page).selectOption('PAYROLL_SETUP_PENDING');
      await expect(employeeList(page).getByRole('cell', { name: '401006', exact: true })).toBeVisible();
    },
  );
});
