import { expect, type Page, test } from '@playwright/test';
import { apiStatus, dataRows, employeeList, t, workAs } from '../support/app';
import { apiAll } from '../support/payroll-support';

/**
 * The office hierarchy in the browser (SRS-22-2, SRS-22-3, SRS-22-11, SRS-G-29; owner decisions of
 * 2026-10-24: a zone group at a lower office sees that office only, and a record of another office
 * reads as unknown). One coverage user per level (docs/rbac/ROLE_COVERAGE.md, seed V037_9) and every
 * edge; the full role x office matrix is OfficeHierarchyScopeIT at the API. Read-only: nothing is
 * changed, so the spec re-runs on any stack with the coverage seed.
 */
/** The offices the top bar offers the session, by code. */
async function offeredOffices(page: Page): Promise<string[]> {
  const options = page.getByLabel(t('en', 'top.office'), { exact: true }).locator('option');
  return (await options.evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value))).filter(Boolean);
}

/** The offices of every M-01 row the session may list (the whole list, page by page). */
async function listedOffices(page: Page): Promise<Set<string>> {
  const rows = await apiAll<{ location: string | null }>(page, '/v1/employees');
  return new Set(rows.map((r) => /\(([A-Z0-9-]+)\)$/.exec(r.location ?? '')?.[1] ?? `?${r.location}`));
}

test.describe('Office hierarchy: each level sees its subtree, never above or beside it', () => {
  test(
    'a depot role works at its depot only: no division is offered and its staff stay hidden',
    { tag: ['@SRS-22-11', '@SRS-G-29', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'ldh.dppb01@fci-demo.test',
        { role: 'LABOUR_DEALING_HAND', office: 'DP-PB01' },
        'W-02',
      );
      expect(await offeredOffices(page)).toEqual(['DP-PB01']);
      // A switch to the parent division is refused by the server too.
      expect(
        await apiStatus(page, '/auth/switch-scope', 'POST', {
          role: 'LABOUR_DEALING_HAND',
          location: 'LD001',
        }),
      ).toBe(403);
    },
  );

  test(
    'a region role is offered the region and its division; at the division it sees the division only',
    { tag: ['@SRS-22-2', '@SRS-22-11', '@SRS-G-29', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'pm.ropb@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'RO-PB' }, 'M-01');
      expect(new Set(await offeredOffices(page))).toEqual(new Set(['RO-PB', 'LD001']));
      const atRegion = await listedOffices(page);
      expect(atRegion.has('RO-PB')).toBe(true);
      expect([...atRegion].every((o) => ['RO-PB', 'LD001', 'DP-PB01'].includes(o))).toBe(true);
      // The sibling region of the same zone stays hidden.
      expect(atRegion.has('RO-DL') || atRegion.has('DH001')).toBe(false);
      await workAs(page, 'en', 'pm.ropb@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'LD001' }, 'M-01');
      expect([...(await listedOffices(page))].every((o) => ['LD001', 'DP-PB01'].includes(o))).toBe(true);
    },
  );

  test(
    'a zone role sees its whole zone at the zonal office, and only the chosen office below it',
    { tag: ['@SRS-22-2', '@SRS-22-11', '@SRS-G-29', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', 'pc.zoe@fci-demo.test', { role: 'PAYROLL_CHECKER', office: 'ZO-E' }, 'M-01');
      const zone = await listedOffices(page);
      expect(zone.has('KL001')).toBe(true);
      expect([...zone].every((o) => ['ZO-E', 'RO-WB', 'KL001', 'DP-WB01'].includes(o))).toBe(true);
      // Owner, 2026-10-24: picking a division narrows the zone group to that division.
      await workAs(page, 'en', 'pm.na11@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'DH001' }, 'M-01');
      const division = await listedOffices(page);
      expect([...division].every((o) => ['DH001', 'DP-DL01'].includes(o))).toBe(true);
      expect(division.has('LD001') || division.has('NA11')).toBe(false);
    },
  );

  test(
    'Headquarters sees every zone; a zone never sees Headquarters or another zone',
    { tag: ['@SRS-22-3', '@SRS-22-11', '@positive'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'pc.hq001@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'M-01',
      );
      const national = await listedOffices(page);
      for (const office of ['DH001', 'KL001', 'CH001', 'MR001'])
        expect(national.has(office), office).toBe(true);
      await workAs(page, 'en', 'pc.zow@fci-demo.test', { role: 'PAYROLL_CHECKER', office: 'ZO-W' }, 'M-01');
      const west = await listedOffices(page);
      expect([...west].some((o) => ['HQ001', 'DH001', 'KL001', 'CH001'].includes(o))).toBe(false);
    },
  );

  test(
    "another office's employee reads exactly as one that does not exist, on the screen and in the API",
    { tag: ['@SRS-G-29', '@SRS-SEC-7', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'pm.kl001@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'KL001' }, 'M-01');
      // 105220 is a Delhi member; 999999 does not exist. Both answer 404, so nothing tells them apart.
      expect(await apiStatus(page, '/v1/employees/105220')).toBe(404);
      expect(await apiStatus(page, '/v1/employees/999999')).toBe(404);
      await page.getByLabel(t('en', 'wf.searchInList')).fill('105220');
      await expect(dataRows(employeeList(page))).toHaveCount(0);
    },
  );
});
