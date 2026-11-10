import { expect, test } from '@playwright/test';
import { t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';

/**
 * C-09 General (Simulation) Run (plan 05 phase 05i; SRS-15-7, SRS C-09, D-17 to D-21) at Delhi HQ (DH001), with the
 * demo limits (maximum 1000, interactive 5; seed V039_19_9): the Payroll Maker previews two members with a what-if
 * DA rate at once, under the amber banner and the SIMULATION watermark; the whole office goes to the background
 * (SIMULATION_RUN on the batch node) and completes; the Administrator views but may not run. Re-runnable: each
 * run adds run records only.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };

test.describe.serial('General (Simulation) Run (C-09, plan 05i)', () => {
  test(
    'SRS-15-7: two members with a what-if DA rate are computed at once and only the record is kept',
    { tag: ['@SRS-15-7', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const members = sqlRows(
        `SELECT emp_num FROM employee WHERE location_id = (SELECT location_id FROM org_location WHERE location_code = 'DH001') AND record_status = 'ACTIVE' AND employment_status NOT IN ('SEPARATED', 'DISMISSED') ORDER BY emp_num FETCH FIRST 2 ROWS ONLY`,
      ).map((r) => r[0] as string);
      await workAs(page, 'en', MAKER, maker, 'C-09');
      // SRS l.1833: the amber banner as Wireframes v6.1 draws it, on entry; the SIMULATION watermark is on every output (below).
      await expect(page.getByRole('note')).toBeVisible();
      await expect(page.getByRole('note')).toContainText(t('en', 'c09.banner'));
      await page.getByRole('button', { name: t('en', 'c09w.request') }).click();
      const form = page.getByRole('dialog', { name: t('en', 'c09.new') });
      await form.getByLabel(t('en', 'c09.population')).selectOption('MEMBER_LIST');
      await form.getByLabel(t('en', 'c09.members')).fill(members.join('\n'));
      await form.getByLabel(t('en', 'c09.whatIf')).selectOption('DA_RATE');
      await form.getByLabel(t('en', 'c09.daIda')).fill('60');
      await form.getByRole('button', { name: t('en', 'c09.run') }).click();
      await expect(page.getByText(/Simulation SIM-\d+ computed/)).toBeVisible();
      await expect(page.locator('.sim-output')).toHaveAttribute('data-watermark', 'SIMULATION');
      const rows = page.getByRole('table', { name: t('en', 'c09.memberRows') });
      for (const m of members) await expect(rows).toContainText(m);
      await expect(page.getByText(t('en', 'c09.rowsNotKept'))).toBeVisible();
    },
  );

  test(
    'SRS-15-7: the whole office is above the interactive limit, runs in the background and completes',
    { tag: ['@SRS-15-7', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-09');
      await page.getByRole('button', { name: t('en', 'c09w.request') }).click();
      const form = page.getByRole('dialog', { name: t('en', 'c09.new') });
      await form.getByRole('button', { name: t('en', 'c09.run') }).click();
      await expect(page.getByText(/Simulation SIM-\d+ queued in the background/)).toBeVisible();
      // The summary table appears once the batch node has run the job (the card is polled while queued).
      await expect(
        page.locator('.sim-output').getByRole('columnheader', { name: t('en', 'c09.notComputed') }),
      ).toBeVisible({ timeout: 90_000 });
      await page.reload();
      await expect(
        page.getByRole('row', { name: new RegExp(t('en', 'c09.mode.BACKGROUND')) }).first(),
      ).toContainText(t('en', 'c09.status.COMPLETE'));
    },
  );

  test(
    'SRS C-09: the Administrator views simulation runs but may not start one',
    { tag: ['@SRS-15-7', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'C-09',
      );
      await expect(page.getByText(t('en', 'c09w.recent'))).toBeVisible();
      // The Administrator views: the drawn request stays, disabled with the reason; no form opens.
      await expect(page.getByRole('button', { name: t('en', 'c09w.request') })).toHaveAttribute(
        'data-why',
        t('en', 'c09w.why.request'),
      );
      await expect(page.getByRole('dialog', { name: t('en', 'c09.new') })).toHaveCount(0);
    },
  );
});
