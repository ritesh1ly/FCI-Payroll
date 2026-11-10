import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CycleControl, DirectPayroll, PayrollCalendar } from '@/modules/jobs';
import { backend, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-19 Maintain Direct Payroll, C-01 period view and M-10 dialogs as Wireframes v6.1 draws them (OD-121; SRS-15-2,
 * SRS-15-12, SRS-15-19, SRS-15-22, SRS-15-33, SRS-G-30, SRS-G-37): a viewer's entry view with the cancellation off
 * and no checker yet, the head filter while the heads failed to load, an incomplete entry submitted by Enter that
 * sends nothing, an order type the document types do not list (no reference required), the C-01 view of a posted,
 * never computed period, and the M-10 month picker cancelled and an incomplete period submitted by Enter.
 */
afterEach(() => vi.unstubAllGlobals());

const entry = (over: object = {}) => ({
  id: 41,
  reference: 'MDP-202607-41',
  period: '2026-07',
  eis: '100041',
  member: 'Member 41',
  payCode: 901,
  head: 'Professional Tax',
  amount: 200,
  status: 'ENTERED',
  reason: 'PT short-deducted',
  enteredBy: 'maker.chennai',
  enteredAt: '2026-10-20T10:00:00Z',
  checkedBy: null,
  decidedAt: null,
  pendingApprovalId: null,
  ...over,
});

const SUMMARY = {
  period: '2026-07',
  windowOpen: true,
  lockedAt: '2026-07-31T12:00:00Z',
  approved: 0,
  pending: 1,
  heads: 1,
  value: 200,
  approvedValue: 0,
};

function i19(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/mdp-transactions/heads': () => [{ payCode: 901, name: 'Professional Tax' }],
    'GET /v1/mdp-transactions/open-periods': () => ['2026-07'],
    'GET /v1/mdp-transactions/summary': () => SUMMARY,
    'GET /v1/mdp-transactions?': (url: string) => paged([entry()], 1, url),
    'GET /v1/mdp-transactions/41/documents': () => [],
    'GET /v1/document-types': () => [
      { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
    ],
    'GET /v1/reference/lists': () =>
      referenceLists({
        MDP_ENTRY_STATUS: [['ENTERED', 'Pending Checker']],
        ORDER_COPY_DOCUMENT_TYPE: [['MDP_AUTHORITY', 'MDP authority']],
      }),
    'POST /v1/mdp-transactions/41/documents': () => ({ status: 201, body: { id: 10 } }),
    ...over,
  });
}

const MAKER = ['I-19:VIEW', 'I-19:CREATE', 'I-19:EDIT', 'I-19:CANCEL'];

describe('I-19 view, filter, entry and order', () => {
  it('SRS_G_37_aViewerSeesTheCancellationOffWithTheMakersReasonAndNoCheckerYet', async () => {
    i19();
    const user = userEvent.setup();
    wrap(<DirectPayroll />, ['I-19:VIEW']);
    await user.click(await screen.findByRole('button', { name: `${t('common.view')} MDP-202607-41` }));
    const dialog = screen.getByRole('dialog', { name: t('i19.viewTitle', ['MDP-202607-41']) });
    const cancel = within(dialog).getByRole('button', { name: t('i19.cancelEntry') });
    expect(cancel).toBeDisabled();
    expect(cancel).toHaveAttribute('data-why', t('i19.why.makerEdit'));
    const checker = within(dialog).getByText(t('i19.col.checker')).nextElementSibling as HTMLElement;
    expect(checker).toHaveTextContent('—');
  });

  it('SRS_15_22_withTheHeadsNotLoadedTheHeadFilterOffersAllOnly', async () => {
    i19({ 'GET /v1/mdp-transactions/heads': () => problem(500, 'SYS-500') });
    const user = userEvent.setup();
    wrap(<DirectPayroll />, MAKER);
    await screen.findByText('MDP-202607-41');
    await user.click(screen.getByRole('button', { name: t('i19.filterAct') }));
    const head = screen.getByLabelText(t('i19.head'));
    expect(
      within(head)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual([t('common.all')]);
  });

  it('SRS_15_19_anIncompleteEntrySubmittedByEnterSendsNothing', async () => {
    const calls = i19();
    const user = userEvent.setup();
    wrap(<DirectPayroll />, MAKER);
    await screen.findByText('MDP-202607-41');
    await user.click(screen.getByRole('button', { name: t('i19.newEntry') }));
    const dialog = screen.getByRole('dialog');
    fireEvent.submit(dialog);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/mdp-transactions')).toHaveLength(0);
  });

  it('SRS_G_30_anOrderTypeTheDocumentTypesDoNotListNeedsNoReference', async () => {
    const calls = i19();
    const user = userEvent.setup();
    wrap(<DirectPayroll />, MAKER);
    await screen.findByText('MDP-202607-41');
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    await waitFor(() => expect(card.querySelector('input[type=file]')).not.toBeDisabled());
    const [, orderDate, , file] = Array.from(card.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    fireEvent.change(orderDate, { target: { value: '2026-07-01' } });
    await user.upload(file, new File(['%PDF'], 'a.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(sent(calls, 'POST', '/41/documents')).toHaveLength(1));
    const form = sent(calls, 'POST', '/41/documents')[0] as FormData;
    expect(form.get('documentType')).toBe('MDP_AUTHORITY');
    expect(form.get('reference')).toBeNull();
  });
});

describe('C-01 period view', () => {
  it('SRS_15_12_aPostedPeriodNeverComputedOrVerifiedReadsDashesAndPostedYes', async () => {
    backend({
      'GET /v1/cycles/2026-10/overview': () => ({ status: 404, body: { errorCode: 'CYC-404-NONE' } }),
      'GET /v1/cycles/2026-10/orders': () => [],
      'GET /v1/cycles/period-register': (url: string) =>
        paged(
          [
            {
              period: '2026-06',
              status: 'LOCKED',
              computedAt: null,
              verifiedBy: null,
              verifiedAt: null,
              lockedAt: null,
              postedToEbs: true,
              supplementaryOpen: false,
              mdpOpen: true,
            },
            {
              period: '2026-05',
              status: 'LOCKED',
              computedAt: '2026-05-28T10:00:00Z',
              verifiedBy: null,
              verifiedAt: null,
              lockedAt: '2026-05-30T10:00:00Z',
              postedToEbs: false,
              supplementaryOpen: false,
              mdpOpen: false,
            },
          ],
          2,
          url,
        ),
      'GET /v1/document-types': () => [],
      'GET /v1/tax/flagged-tds-cases?': (url: string) => paged([], 0, url),
      'GET /v1/cycles/2026-10/gates': () => ({ status: 404, body: { errorCode: 'CYC-404-NONE' } }),
      'GET /v1/cycles/2026-10/quarantine': () => paged([]),
      'GET /v1/cycle-register-entries?': (url: string) => paged([], 0, url),
    });
    const user = userEvent.setup();
    wrap(<CycleControl />, ['C-01:VIEW']);
    await user.click(await screen.findByRole('button', { name: t('c01w.viewOf', ['2026-06']) }));
    const dialog = screen.getByRole('dialog');
    const value = (key: string) =>
      (within(dialog).getByText(t(key)).nextElementSibling as HTMLElement).textContent;
    expect(value('c01w.col.computed')).toContain('—');
    expect(value('c01w.col.verified')).toContain('—');
    expect(value('c01w.col.posted')).toContain(t('x05.yes'));
    // the MDP window is open: supplementary Yes
    expect(value('c01w.col.supplementary')).toContain(t('x05.yes'));
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await user.click(screen.getByRole('button', { name: t('c01w.viewOf', ['2026-05']) }));
    const closed = screen.getByRole('dialog');
    const flag = (key: string) =>
      (within(closed).getByText(t(key)).nextElementSibling as HTMLElement).textContent;
    // neither the MDP window nor a supplementary run is open; not posted
    expect(flag('c01w.col.supplementary')).toContain(t('x05.no'));
    expect(flag('c01w.col.posted')).toContain(t('x05.no'));
  });
});

describe('M-10 dialogs', () => {
  const april = {
    month: '2027-04',
    start: '2027-04-01',
    end: '2027-04-30',
    freezeTarget: '2027-04-20',
    computeBy: null,
    verifyBy: null,
    lockTarget: '2027-04-28',
    disbursement: null,
    supplementaryFrom: '',
    supplementaryTo: '',
    mdpFrom: '',
    mdpTo: '',
  };
  function m10() {
    return backend({
      'GET /v1/payroll-calendars': () => ['2027-28'],
      'GET /v1/payroll-calendars/2027-28': () => ({
        finYear: '2027-28',
        periods: [{ period: april, initiated: false, cycleStatus: null, windowOpen: false }],
        pendingApprovalId: null,
      }),
      'GET /v1/run-order-steps': () => ({ steps: [], pendingApprovalId: null }),
    });
  }

  it('SRS_15_33_cancellingTheMonthPickerOpensNoPeriod', async () => {
    m10();
    const user = userEvent.setup();
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:EDIT']);
    await screen.findByText('202704', {}, { timeout: 5000 });
    await user.click(screen.getByRole('button', { name: t('m10w.maintain') }));
    const pick = screen.getByRole('dialog', { name: t('m10w.maintain') });
    await user.click(within(pick).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS_15_33_anIncompletePeriodSubmittedByEnterProposesNothing', async () => {
    const calls = m10();
    const user = userEvent.setup();
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:EDIT']);
    await user.click(
      await screen.findByRole('button', { name: t('m10.edit', ['APR 2027']) }, { timeout: 5000 }),
    );
    const dialog = screen.getByRole('dialog');
    // no reason yet: Enter submits the form, which proposes nothing
    fireEvent.submit(dialog);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(sent(calls, 'POST', '/proposals')).toHaveLength(0);
  });
});
