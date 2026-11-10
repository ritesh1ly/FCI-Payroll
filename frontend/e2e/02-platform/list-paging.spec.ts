import { expect, type Locator, type Page, test } from '@playwright/test';
import { apiStatus, dataRows, employeeList, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';
import { apiJson } from '../support/payroll-support';

/**
 * Lists open page by page (SRS-NF-3; owner requirement 2026-10-24, docs/plan/X-pagination.md): the
 * server sends one page, 20 rows to start; the user picks a page by its number or "Go to page", moves
 * with previous / next / first / last, and chooses 10, 20, 50 or 100 rows per page (A-07 settings,
 * served in /auth/me). At 100 rows the table scrolls inside its card under a sticky header. A size
 * the server does not offer is refused, never clamped.
 *
 * Data: db/seed V035_9 puts 120 complete members at the Chennai DO (CH001), each with one X-06 query
 * and one X-05 entry, so every list here holds more than 100 rows. Read only: re-runnable.
 */
const CHENNAI = 'maker.chennai@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'CH001' };

/** The pager of the list: M-01 as drawn has further paged tables under it, the list comes first. */
function pager(page: Page, locale: 'en' | 'hi' = 'en'): Locator {
  return page.getByRole('navigation', { name: t(locale, 'page.nav') }).first();
}

function rows(page: Page): Locator {
  return dataRows(employeeList(page));
}

async function total(page: Page, path: string): Promise<number> {
  return (await apiJson<{ total: number }>(page, `${path}?page=0&size=10`)).total;
}

async function expectRange(page: Page, first: number, last: number, of: number) {
  await expect(pager(page).locator('.fl-pager-range')).toHaveText(t('en', 'page.range', [first, last, of]));
}

test.describe('a long list, page by page', () => {
  test(
    'M-01: 20 rows first; a page by its number, next, previous, Go to page, last and first',
    { tag: ['@SRS-NF-3', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', CHENNAI, maker, 'M-01');
      const all = await total(page, '/v1/employees');
      expect(all).toBeGreaterThan(100);
      await expectRange(page, 1, 20, all);
      await expect(rows(page)).toHaveCount(20);
      await expect(pager(page).getByRole('button', { name: t('en', 'page.number', [1]) })).toHaveAttribute(
        'aria-current',
        'page',
      );
      await expect(pager(page).getByRole('button', { name: t('en', 'page.prev') })).toBeDisabled();

      // Page index selection.
      await pager(page)
        .getByRole('button', { name: t('en', 'page.number', [3]) })
        .click();
      await expectRange(page, 41, 60, all);
      await pager(page)
        .getByRole('button', { name: t('en', 'page.next') })
        .click();
      await expectRange(page, 61, 80, all);
      await pager(page)
        .getByRole('button', { name: t('en', 'page.prev') })
        .click();
      await expectRange(page, 41, 60, all);

      // "Go to page" reaches a page the buttons leave out (shown as a gap).
      await expect(pager(page).getByLabel(t('en', 'page.gap')).first()).toBeVisible();
      const pages = Math.ceil(all / 20);
      await pager(page)
        .getByLabel(t('en', 'page.goTo'))
        .selectOption({ value: String(pages - 2) });
      await expectRange(page, (pages - 2) * 20 + 1, (pages - 1) * 20, all);

      await pager(page)
        .getByRole('button', { name: t('en', 'page.last') })
        .click();
      await expectRange(page, (pages - 1) * 20 + 1, all, all);
      await expect(rows(page)).toHaveCount(all - (pages - 1) * 20);
      await expect(pager(page).getByRole('button', { name: t('en', 'page.next') })).toBeDisabled();
      await expect(pager(page).getByRole('button', { name: t('en', 'page.last') })).toBeDisabled();
      await pager(page)
        .getByRole('button', { name: t('en', 'page.first') })
        .click();
      await expectRange(page, 1, 20, all);
    },
  );

  test(
    'M-01: 100 rows per page fit in the card: the table scrolls under a sticky header; a new size starts at page 1',
    { tag: ['@SRS-NF-3', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', CHENNAI, maker, 'M-01');
      const all = await total(page, '/v1/employees');
      const size = pager(page).getByLabel(t('en', 'page.size'));
      await expect(size.getByRole('option')).toHaveText(['10', '20', '50', '100']);
      await expect(size).toHaveValue('20');

      await pager(page)
        .getByRole('button', { name: t('en', 'page.number', [2]) })
        .click();
      await size.selectOption('100');
      await expectRange(page, 1, 100, all);
      await expect(rows(page)).toHaveCount(100);
      const box = page.locator('.fl-table-scroll').first();
      const scroll = await box.evaluate((el) => ({
        overflow: getComputedStyle(el).overflowY,
        scrolls: el.scrollHeight > el.clientHeight,
        fits: el.getBoundingClientRect().height <= window.innerHeight * 0.7 + 1,
        sticky: getComputedStyle(el.querySelector('thead th') as Element).position,
      }));
      expect(scroll).toEqual({ overflow: 'auto', scrolls: true, fits: true, sticky: 'sticky' });
      // The header stays in view while the rows scroll (the card first in view: the page around it
      // has scrolled with the pager).
      await box.evaluate((el) => el.scrollIntoView({ block: 'start' }));
      await box.evaluate((el) => el.scrollTo(0, el.scrollHeight));
      await expect(box.locator('thead th').first()).toBeInViewport();
      await expect(rows(page).last()).toBeInViewport();

      await pager(page)
        .getByRole('button', { name: t('en', 'page.next') })
        .click();
      await expectRange(page, 101, Math.min(200, all), all);
      await size.selectOption('10');
      await expectRange(page, 1, 10, all);
      await expect(rows(page)).toHaveCount(10);
    },
  );

  // X-05 lists one row per condition since fix batch 1 (SRS l.634 "Count - Members affected",
  // counted by the server), so its demo items are a dozen conditions, not 100 rows; its paging is
  // covered by ExceptionWorklistIT and the X-05 Vitest (group g1 2026-11-02).
  test(
    'X-06: more than 100 queries open at 20, and at 50 per page',
    { tag: ['@SRS-NF-3', '@positive'] },
    async ({ page }) => {
      for (const screen of ['X-06']) {
        await workAs(page, 'en', CHENNAI, maker, screen);
        // The total under the screen's own filters, as the pager states it ("1–20 of N").
        const range = await pager(page).locator('.fl-pager-range').textContent();
        const all = Number(/(\d+)\D*$/.exec(range ?? '')?.[1]);
        expect(all, screen).toBeGreaterThan(100);
        await expectRange(page, 1, 20, all);
        await pager(page).getByLabel(t('en', 'page.size')).selectOption('50');
        await expectRange(page, 1, 50, all);
        await pager(page)
          .getByRole('button', { name: t('en', 'page.number', [3]) })
          .click();
        await expectRange(page, 101, Math.min(150, all), all);
      }
    },
  );

  // History seeds V037_8 and V037_9_1 (final states only, member 106512 and 889001 at DH001): every
  // other paged list of Modules 01-07 holds more than 100 rows, so its second page at 100 is reached.
  const HISTORY: [screen: string, login: string, scope: { role: string; office: string }][] = [
    ['M-11', 'pm.dh001@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'DH001' }],
    ['I-17', 'pm.dh001@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'DH001' }],
    ['I-20', 'pm.dh001@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'DH001' }],
    ['I-23', 'pm.dh001@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'DH001' }],
    ['X-07', 'pm.dh001@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'DH001' }],
    ['C-13', 'pm.dh001@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'DH001' }],
    ['I-04', 'ucpf.dh001@fci-demo.test', { role: 'UNIT_CPF_ADMIN', office: 'DH001' }],
    ['I-22', 'cpfmgr.na11@fci-demo.test', { role: 'CPF_MANAGER', office: 'NA11' }],
  ];
  for (const [screen, login, scope] of HISTORY) {
    test(
      `${screen}: its history holds more than 100 rows; the second page at 100 starts at row 101`,
      { tag: ['@SRS-NF-3', '@positive'] },
      async ({ page }) => {
        await workAs(page, 'en', login, scope, screen);
        // I-04 draws its loan tables first; the sanction history stays in the closed register (OD-121).
        const first =
          screen === 'I-04'
            ? page
                .getByTestId('i04-register')
                .getByRole('navigation', { name: t('en', 'page.nav') })
                .first()
            : pager(page);
        await expect(first).toBeVisible();
        const range = await first.locator('.fl-pager-range').textContent();
        const all = Number(/(\d+)\D*$/.exec(range ?? '')?.[1]);
        expect(all, screen).toBeGreaterThan(100);
        await first.getByLabel(t('en', 'page.size')).selectOption('100');
        await expect(first.locator('.fl-pager-range')).toHaveText(t('en', 'page.range', [1, 100, all]));
        await first.getByRole('button', { name: t('en', 'page.next') }).click();
        await expect(first.locator('.fl-pager-range')).toHaveText(
          t('en', 'page.range', [101, Math.min(200, all), all]),
        );
      },
    );
  }

  test(
    'X-07: the lines of one upload page as well (110 lines of a rejected history upload)',
    { tag: ['@SRS-NF-3', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const id = sqlRows(
        "SELECT fallback_upload_id FROM hrms_fallback_upload WHERE batch_no = 'FBU-PG-001'",
      )[0]?.[0];
      expect(id, 'seed V037_9_1').toBeTruthy();
      await workAs(page, 'en', 'pm.dh001@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'DH001' }, 'X-07');
      const lines = await apiJson<{ items: unknown[]; total: number }>(
        page,
        `/v1/hrms-fallback/uploads/${id}/lines?page=1&size=100`,
      );
      expect(lines.total).toBe(110);
      expect(lines.items).toHaveLength(10);
    },
  );

  test('the pager speaks Hindi on a Hindi screen', { tag: ['@SRS-NF-3', '@positive'] }, async ({ page }) => {
    await workAs(page, 'hi', CHENNAI, maker, 'M-01');
    const nav = pager(page, 'hi');
    await expect(nav.getByLabel(t('hi', 'page.size'))).toBeVisible();
    await expect(nav.getByRole('button', { name: t('hi', 'page.next') })).toBeVisible();
  });
});

test.describe('the server decides what a page may be', () => {
  test(
    'sizes from /auth/me; a size not offered, a huge size or a negative page is refused (COM-400-PAGE)',
    { tag: ['@SRS-NF-3', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', CHENNAI, maker, 'M-01');
      const me = await apiJson<{ paging: { sizes: number[]; defaultSize: number } }>(page, '/auth/me');
      expect(me.paging).toEqual({ sizes: [10, 20, 50, 100], defaultSize: 20 });
      for (const forged of ['size=25', 'size=101', 'size=100000', 'size=0', 'page=-1']) {
        expect(await apiStatus(page, `/v1/employees?${forged}`), forged).toBe(400);
        expect(await apiStatus(page, `/v1/queries?${forged}`), forged).toBe(400);
      }
      const past = await apiJson<{ items: unknown[]; total: number }>(
        page,
        '/v1/employees?page=999&size=100',
      );
      expect(past.items).toHaveLength(0);
      expect(past.total).toBeGreaterThan(100);
    },
  );
});
