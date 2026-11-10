import { expect, test } from '@playwright/test';
import { expectNoBrowserStorage, sessionPeriod, sidebarCodes, t, workAs, type Locale } from '../support/app';

/**
 * Every kind of user, in English and in Hindi, against the deployed stack (uat,demo): sign-in with
 * user ID and password straight into the workspace, the office and role chosen in the top bar, the RBAC menu.
 */
for (const locale of ['en', 'hi'] as Locale[]) {
  test.describe(`Personas, landing screens and menus (${locale})`, () => {
    test(
      'Employee: eSeva scope (no office), own record only',
      { tag: ['@SRS-G-35', '@SRS-G-25', '@SC-01-0025', '@positive'] },
      async ({ page }) => {
        await workAs(page, locale, 'emp.rsharma@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
        // E-01, X-06 (a member raises payroll queries on the own record, SRS-G-11) and X-08 (sign-in,
        // OTP and password, all users): SRS 14.4; the order is the pack's sidebar (OD-125).
        expect((await sidebarCodes(page)).sort()).toEqual(['E-01', 'X-06', 'X-08']);
        await expect(page.getByText('104882').first()).toBeVisible();
        // Deny by default (page guard): a screen outside the role is never rendered; the workspace
        // opens on its dashboard instead.
        await page.goto('/payroll/?s=M-01');
        await expect(page).toHaveURL(/\/payroll\/?$/);
        await expect(page.getByRole('heading', { level: 1, name: t(locale, 'dash.title') })).toBeVisible();
        await expectNoBrowserStorage(page);
      },
    );

    test(
      'Payroll maker at Delhi HQ: scoped employee master and governed buttons',
      { tag: ['@SRS-G-25', '@SRS-G-29', '@SC-01-0025', '@positive'] },
      async ({ page }) => {
        await workAs(
          page,
          locale,
          'maker.delhi@fci-demo.test',
          { role: 'PAYROLL_MAKER', office: 'DH001' },
          'W-02',
        );
        const codes = await sidebarCodes(page);
        // The sidebar prints the pack's codes (WF-070): X-02 is W-01, which SRS 14.4 lists for the
        // maker to read (OD-125); W-02 has no drawn entry (WF-204) and stays open by link (workAs).
        expect(codes).toEqual(expect.arrayContaining(['X-02', 'M-01', 'C-02']));
        expect(codes).not.toContain('W-02');
        expect(codes).not.toContain('A-01');
        expect(codes).not.toContain('M-16');
        await page.goto('/payroll/?s=W-01');
        await expect(page.getByRole('button', { name: t(locale, 'w01.approveSelected') })).toBeDisabled();
        await page.goto('/payroll/?s=W-02');
        await expect(page.getByLabel(t(locale, 'top.office'))).toHaveValue('DH001');
        await expect(page.getByLabel(t(locale, 'top.period'))).toHaveValue(await sessionPeriod(page));
        await page.locator('aside').getByRole('button', { name: /M-01/ }).click();
        await expect(page.getByRole('cell', { name: '105220', exact: true })).toBeVisible();
        await expect(page.getByText('(MR001)')).toHaveCount(0);
        await page.locator('aside').getByRole('button', { name: /C-02/ }).click();
        await page.locator('#c02-cycle summary').click();
        // The live cycle board (whatever state other tests left it in): verifying is never the maker's.
        await expect(page.getByRole('list', { name: t(locale, 'cyc.states') })).toBeVisible();
        await expect(page.getByRole('button', { name: t(locale, 'cyc.action.verify') })).toHaveCount(0);
        await expectNoBrowserStorage(page);
      },
    );

    test(
      'DDO / Manager: approval inbox of the own location',
      { tag: ['@SRS-G-29', '@SC-01-0027', '@positive'] },
      async ({ page }) => {
        await workAs(
          page,
          locale,
          'ddo.delhi@fci-demo.test',
          { role: 'PAYROLL_CHECKER', office: 'DH001' },
          'W-01',
        );
        await expect(page.getByRole('cell', { name: /Selection grade .*106845/ })).toBeVisible();
        await expect(page.getByRole('cell', { name: /205327/ })).toHaveCount(0);
      },
    );

    test(
      'AGM: Payroll Checker with zone scope at the zonal office',
      { tag: ['@SRS-G-29', '@SC-01-0027', '@positive'] },
      async ({ page }) => {
        await workAs(
          page,
          locale,
          'agm.west@fci-demo.test',
          { role: 'PAYROLL_CHECKER', office: 'ZO-W' },
          'W-01',
        );
        await page.locator('aside').getByRole('button', { name: /M-01/ }).click();
        await expect(page.getByRole('cell', { name: '205101', exact: true })).toBeVisible();
        await expect(page.getByRole('cell', { name: '105220', exact: true })).toHaveCount(0);
      },
    );

    test(
      'HQ rule approver: consolidated HQ MIS is read-only',
      { tag: ['@SRS-G-25', '@positive'] },
      async ({ page }) => {
        await workAs(page, locale, 'su.hq@fci-demo.test', { role: 'RULE_APPROVER', office: 'HQ001' }, 'R-01');
        await expect(page.getByText(t(locale, 'r01.readOnly'))).toBeVisible();
        await expect(page.getByRole('cell', { name: /DH001/ })).toBeVisible();
        await expect(page.getByRole('cell', { name: /MR001/ })).toBeVisible();
      },
    );

    test(
      'System Admin: administration screens, no payroll screens',
      { tag: ['@SRS-G-25', '@SC-01-0025', '@positive'] },
      async ({ page }) => {
        await workAs(
          page,
          locale,
          'sysadmin@fci-demo.test',
          { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
          'A-01',
        );
        const codes = await sidebarCodes(page);
        // As drawn: X-01 (built A-08) and X-04 (built A-05) in the sidebar, printed with the pack's
        // codes (WF-070); A-01 ... A-04 have no drawn entry and open by link (WF-204).
        expect(codes).toEqual(expect.arrayContaining(['X-01', 'X-04']));
        expect(codes).not.toContain('A-01');
        // SRS 14.4 lists the administrator on M-01 to read (OD-125, WF-071): no edit of it.
        await page.locator('aside').getByRole('button', { name: /M-01/ }).click();
        await expect(page.getByRole('heading', { level: 1, name: t(locale, 'screen.M-01') })).toBeVisible();
        await expect(page.getByRole('button', { name: t(locale, 'm01.w.editRecord') })).toBeDisabled();
        await page.goto('/payroll/?s=A-01');
        // The register pages at 20 (SRS-NF-3): a user is found by search.
        await page.getByLabel(t(locale, 'a01.search')).fill('maker.delhi');
        await expect(
          page.getByRole('cell', { name: 'maker.delhi@fci-demo.test', exact: true }),
        ).toBeVisible();
        await page.goto('/payroll/?s=A-02');
        const protectedBox = page.getByRole('checkbox', { name: /^SYSTEM_ADMINISTRATOR: A-01:VIEW/ });
        await expect(protectedBox).toBeDisabled();
        await expectNoBrowserStorage(page);
      },
    );

    test(
      'a user with two roles switches between them without signing out',
      { tag: ['@SRS-G-48', '@edge'] },
      async ({ page }) => {
        await workAs(
          page,
          locale,
          'agm.north@fci-demo.test',
          { role: 'PAYROLL_CHECKER', office: 'NA11' },
          'W-01',
        );
        const asChecker = await sidebarCodes(page);
        await page
          .getByLabel(t(locale, 'top.role'), { exact: true })
          .selectOption('REPORT_ADMINISTRATOR@NA11');
        await expect(page.locator('.rolebar b')).toContainText(t(locale, 'role.REPORT_ADMINISTRATOR'));
        await expect.poll(() => sidebarCodes(page)).not.toEqual(asChecker);
        expect(await sidebarCodes(page)).not.toContain('W-01');
        await expect(page).toHaveURL(/\/payroll/);
        await expectNoBrowserStorage(page);
      },
    );
  });
}
