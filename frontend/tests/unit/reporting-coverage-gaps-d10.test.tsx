import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CpfReconciliationReports, CpfReports } from '@/modules/reporting';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * O-07 CPF & e-Seva Reports and O-08 CPF Reconciliation Reports as Wireframes v6.1 draws them (OD-121; BR-CPF-036,
 * SRS-18-24): a blocked entry without a stated reason, a month not read yet (dashes, never a guessed version), a
 * report produced after approval with no version, a role without any role (send and generate locked with the reason)
 * and the O-08 view of a report with neither frequency nor blocked reason.
 */
afterEach(() => vi.unstubAllGlobals());

const NO_ROLES = me(['O-07:VIEW', 'O-08:VIEW'], { roles: undefined as unknown as string[] });

const ENTRY = {
  key: 'CPF_OBCB',
  name: 'x',
  family: 'f',
  section: 'A.4.6',
  domain: 'REPORT',
  producingScreen: 'O-04',
  payrollTypes: 'All',
  periodicity: null,
  lockRequirement: 'POST_APPROVAL',
  versionNo: 1,
  layoutFormat: null,
  blockedReason: null,
  status: 'BLOCKED',
};

describe('O-07 and O-08 fallbacks', () => {
  it('BR_CPF_036_withoutRolesOrMonthTheButtonsAreLockedAndTheVersionsReadDashes', async () => {
    backend({
      'GET /v1/reports/cpf-eseva/months': () => ({ status: 500, body: { errorCode: 'SYS-500' } }),
      'GET /v1/reports/cpf-eseva': () => paged([ENTRY]),
    });
    wrap(<CpfReports />, NO_ROLES);
    const name = await screen.findByText(t('o07w.r.CPF_OBCB'));
    const row = name.closest('tr') as HTMLElement;
    // produced after approval: no version column value
    expect(within(row).getByText('—')).toBeInTheDocument();
    // blocked with no reason: no title is invented
    expect(within(row).getByText(t('o03.status.blocked'))).not.toHaveAttribute('title');
    expect(screen.getByRole('button', { name: t('o07w.act.send') })).toHaveAttribute(
      'data-why',
      t('o07w.why.send'),
    );
    expect(screen.getByRole('button', { name: t('o07w.act.generate') })).toHaveAttribute(
      'data-why',
      t('o07w.why.maker'),
    );
    expect(screen.getByRole('button', { name: t('o07w.act.bulk') })).toHaveAttribute(
      'data-why',
      t('o07w.why.bulk'),
    );
    // the month did not arrive: the run tile reads provisional, its value a dash
    expect(screen.getByText(t('o07w.tile.run.PROVISIONAL'))).toBeInTheDocument();
  });

  it('BR_CPF_036_withoutASessionPeriodTheTitleAndTheVersionReadDashesAndNoMonthIsRead', async () => {
    const calls = backend({
      'GET /v1/reports/cpf-eseva/months': () => ({
        period: '202611',
        version: 'FINAL',
        previous: '202610',
        previousVersion: 'FINAL',
      }),
      'GET /v1/reports/cpf-eseva': () =>
        paged([{ ...ENTRY, lockRequirement: 'POST_LOCK', status: 'AVAILABLE' }]),
    });
    wrap(<CpfReports />, me(['O-07:VIEW'], { period: undefined as never }));
    expect(await screen.findByText(t('o07w.table', ['—']))).toBeInTheDocument();
    const row = (await screen.findByText(t('o07w.r.CPF_OBCB'))).closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeInTheDocument();
    expect(within(row).getByText(t('o03.status.available'))).toHaveClass('p-ok');
    expect(calls.filter((c) => c.url.includes('/months'))).toHaveLength(0);
  });

  it('SRS_18_24_anO08ReportWithoutFrequencyOrReasonReadsADashAndAvailableAndTheViewCloses', async () => {
    backend({ 'GET /v1/reports/cpf-reconciliation': () => paged([{ ...ENTRY, status: 'AVAILABLE' }]) });
    const user = userEvent.setup();
    wrap(<CpfReconciliationReports />, NO_ROLES);
    const row = (await screen.findByText(t('o08w.r.CPF_OBCB'))).closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeInTheDocument();
    // no role: generate is locked with the Maker's reason and no ✔ is drawn
    expect(screen.getByRole('button', { name: t('o08w.act.generate') })).toHaveAttribute(
      'data-why',
      t('o07w.why.maker'),
    );
    expect(within(row).queryByTitle(t('wf.approveTitle'))).toBeNull();
    await user.click(within(row).getByRole('button', { name: t('o08w.viewName', [t('o08w.r.CPF_OBCB')]) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('—')).toBeInTheDocument();
    expect(within(dialog).getByText(t('o03.status.available'))).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
