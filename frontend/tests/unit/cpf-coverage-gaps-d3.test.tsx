import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CpfSanctionCard, InterestRun, SupplementaryPayment } from '@/modules/cpf';
import type { SupplementaryRow } from '@/modules/cpf/api/cpfScreensApi';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * I-04 CPF card, C-12 interest run and I-12 supplementary payment as Wireframes v6.1 draws them (OD-121; SRS-6-4,
 * BR-CPF-029, SRS-6-45, SRS-6-46, SRS-6-48): the fallbacks of a request without a purpose name or a tier, the chosen
 * request kept across pages, the run lifecycle once a run exists or the year is frozen, and the row a user picks.
 */
afterEach(() => vi.unstubAllGlobals());

const BASE = {
  id: 21,
  eis: '880611',
  memberName: 'Neha Batra',
  type: 'ADVANCE',
  purposeCode: 'EDUCATION',
  purposeName: null,
  limitRule: 'CPF advance limit - education',
  requested: 50000,
  ceiling: 80000,
  sanctioned: null,
  capped: false,
  stage: 'RETURNED',
  sanctionNumber: null,
  sanctionDate: null,
  tiers: [],
  documents: [],
};
const NO_PURPOSE = { ...BASE, id: 22, purposeCode: null, eis: '880612', memberName: 'Om Prakash' };
const PAGE_TWO = { ...BASE, id: 23, eis: '880613', memberName: 'Ira Sen', stage: 'ZONAL_HQ_VALIDATION' };

describe('I-04 CPF card fallbacks', () => {
  it('BR_CPF_029_aRequestWithoutPurposeNameShowsItsCodeWithoutEitherADashAndOutsideTheChainNoTier', async () => {
    backend({ 'GET /v1/cpf/sanctions': (url: string) => paged([BASE, NO_PURPOSE], 2, url) });
    wrap(<CpfSanctionCard onOpen={vi.fn()} />, ['I-04:VIEW']);
    const table = await screen.findByRole('table', { name: t('i04c.card') });
    const coded = (await within(table).findByText(t('i04c.requestNo', [21]))).closest('tr') as HTMLElement;
    expect(within(coded).getByText(`${t('i04.kind.ADVANCE')} · EDUCATION`)).toBeInTheDocument();
    // the rule stored on the request is shown as it is
    expect(within(coded).getByText('CPF advance limit - education')).toBeInTheDocument();
    // a returned request waits at no tier: a dash, no pill
    const cells = within(coded).getAllByRole('cell');
    expect(cells[7]).toHaveTextContent('—');
    expect((cells[7] as HTMLElement).querySelector('.pill')).toBeNull();
    const bare = within(table)
      .getByText(t('i04c.requestNo', [22]))
      .closest('tr') as HTMLElement;
    expect(within(bare).getByText(`${t('i04.kind.ADVANCE')} · —`)).toBeInTheDocument();
  });

  it('BR_CPF_029_theViewOfARequestOutsideTheChainReadsNoTierAndCloses', async () => {
    backend({ 'GET /v1/cpf/sanctions': (url: string) => paged([BASE], 1, url) });
    const user = userEvent.setup();
    const onOpen = vi.fn();
    wrap(<CpfSanctionCard onOpen={onOpen} />, ['I-04:VIEW']);
    await user.click(
      await screen.findByRole('button', { name: t('i04c.viewOf', [t('i04c.requestNo', [21])]) }),
    );
    const dialog = await screen.findByRole('dialog');
    const tierRow = within(dialog).getByText(t('i04c.col.tier')).parentElement as HTMLElement;
    expect(tierRow).toHaveTextContent('—');
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('BR_CPF_029_theChosenRequestStaysChosenWhenAnotherPageIsShown', async () => {
    backend({
      'GET /v1/cpf/sanctions': (url: string) =>
        url.includes('page=1') ? paged([PAGE_TWO], 21, url) : paged([BASE], 21, url),
    });
    const user = userEvent.setup();
    wrap(<CpfSanctionCard onOpen={vi.fn()} />, ['I-04:VIEW']);
    await screen.findByText(t('i04c.requestNo', [21]));
    await user.click(screen.getByRole('button', { name: /next/i }));
    expect(await screen.findByText(t('i04c.requestNo', [23]))).toBeInTheDocument();
    // the limit check still reads the request chosen on the first page (21: 50,000 within 80,000)
    await user.click(screen.getByRole('button', { name: t('i04c.limitCheck') }));
    expect(
      await screen.findByText(t('i04c.check.within', [t('i04c.requestNo', [21]), '50,000', '80,000'])),
    ).toBeInTheDocument();
  });
});

const SUMMARY = {
  finYear: '2026-27',
  zone: 'NA11',
  members: 2,
  calculated: 2,
  notYetCalculated: 0,
  reRuns: 1,
  freezeStatus: 'FROZEN',
  frozenBy: 'agm',
  frozenAt: '2026-11-01T05:00:00Z',
  runs: [
    {
      run: 2,
      scope: 'ZONE',
      eis: null,
      initiatedBy: 'm@x',
      initiatedAt: '2026-11-01T05:00:00Z',
      members: 2,
      calculated: 2,
      notCalculated: 0,
      stopped: 0,
      reason: 'Year end',
    },
  ],
};

describe('C-12 lifecycle and audit', () => {
  it('SRS_6_46_aFrozenYearWithARunReadsFrozenInTheLifecycleAndTheRunByScope', async () => {
    backend({ 'GET /v1/cpf/interest-runs': () => SUMMARY });
    wrap(<InterestRun />, me(['C-12:VIEW'], { roles: undefined as unknown as string[] }));
    expect(await screen.findByText('Year end')).toBeInTheDocument();
    expect(screen.getByText(t('c12.scope.ZONE'))).toBeInTheDocument();
    expect(screen.getByText(t('c12w.life.run', [1]))).toBeInTheDocument();
    // freeze tile and two lifecycle steps read frozen
    expect(screen.getAllByText(t('c12.freeze.FROZEN'))).toHaveLength(3);
    // no role: run and freeze locked with their reasons
    expect(screen.getByRole('button', { name: t('c12w.act.run') })).toHaveAttribute(
      'data-why',
      t('c12w.why.run'),
    );
    expect(screen.getByRole('button', { name: t('c12w.act.freeze') })).toHaveAttribute(
      'data-why',
      t('c12w.why.freeze'),
    );
  });

  it('SRS_6_45_noRunYetIsSaidAndTheYearReadsAvailableAndNotFrozen', async () => {
    backend({
      'GET /v1/cpf/interest-runs': () => ({ ...SUMMARY, freezeStatus: 'OPEN', runs: [] }),
    });
    wrap(<InterestRun />, me(['C-12:VIEW'], { roles: ['FINANCE'] }));
    expect(await screen.findByText(t('c12.noRuns'))).toBeInTheDocument();
    expect(screen.getByText(t('c12w.life.noRun'))).toBeInTheDocument();
    expect(screen.getByText(t('c12w.life.available'))).toBeInTheDocument();
    expect(screen.getByText(t('c12w.life.notFrozen'))).toBeInTheDocument();
  });

  it('SRS_6_45_theRegisterOpenedFromItsSectionStartsOnNoForm', async () => {
    backend({ 'GET /v1/cpf/interest-runs': () => SUMMARY });
    const user = userEvent.setup();
    wrap(<InterestRun />, me(['C-12:VIEW'], { roles: ['FINANCE'] }));
    await screen.findByText('Year end');
    const section = screen.getByTestId('c12-actions');
    await user.click(within(section).getByText(t('c12w.register')));
    await waitFor(() => expect(section).toHaveAttribute('open'));
    expect(screen.queryByLabelText(t('c12.reason'))).toBeNull();
  });
});

const PAYMENT: SupplementaryRow = {
  entry: 'CSP-2026-0012',
  eis: '871907',
  name: 'P. Barre',
  designation: null,
  category: 'II',
  employeeType: 'STAFF',
  cpfCode: null,
  posting: null,
  employmentStatus: 'SEPARATED',
  sanctionNumber: null,
  employee: 149,
  employer: 162,
  vpf: 0,
  sanctioned: 311,
  orderNumber: '17(49)',
  orderDate: null,
  paymentStatus: 'RELEASED',
  invoiceNumber: null,
  invoiceStatus: null,
  sentToApps: null,
  appsCancelStatus: null,
};
const SECOND: SupplementaryRow = { ...PAYMENT, entry: 'CSP-2026-0013', eis: '871908', name: 'L. Nair' };

describe('I-12 rows', () => {
  it('SRS_6_48_anOrderWithoutDateShowsADashAndPickingARowMovesTheCard', async () => {
    backend({ 'GET /v1/cpf/supplementary-payments': () => paged([PAYMENT, SECOND]) });
    const user = userEvent.setup();
    wrap(<SupplementaryPayment />, me(['I-12:VIEW'], { roles: undefined as unknown as string[] }));
    expect(await screen.findByText(t('i12w.member', ['871907', 'P. Barre']))).toBeInTheDocument();
    expect(screen.getByText('17(49) · —')).toBeInTheDocument();
    // no role: letter, record and release are locked with their reasons
    expect(screen.getByRole('button', { name: t('i12w.act.letter') })).toHaveAttribute(
      'data-why',
      t('i12w.why.letter'),
    );
    const row = screen.getByText('CSP-2026-0013').closest('tr') as HTMLElement;
    await user.click(row);
    expect(row).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText(t('i12w.member', ['871908', 'L. Nair']))).toBeInTheDocument();
  });

  it('SRS_6_48_noEntryLeavesTheCardOnDashes', async () => {
    backend({ 'GET /v1/cpf/supplementary-payments': () => paged([]) });
    wrap(<SupplementaryPayment />, me(['I-12:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    expect(await screen.findByText(t('i12w.member', ['—', '—']))).toBeInTheDocument();
  });
});
