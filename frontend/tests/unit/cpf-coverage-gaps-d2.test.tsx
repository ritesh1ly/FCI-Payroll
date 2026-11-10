import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DeputationAdjustment,
  InterestRates,
  OpeningBalanceCorrection,
  ParentZoneChange,
} from '@/modules/cpf';
import type { DeputationEntry } from '@/modules/cpf/api/cpfApi';
import type {
  CorrectionLine,
  DeputedRow,
  ZoneChangeDetail,
  ZoneChangeRow,
} from '@/modules/cpf/api/cpfScreensApi';
import { businessToday } from '@/shared/time';
import { amount, date, dateTime } from '@/shared/ui';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-09 deputation, I-22 opening-balance correction, I-13 parent-zone change and M-19 as Wireframes v6.1 draws them
 * (OD-121; SRS-6-27 … 6-30, SRS-6-43, SRS-6-44, SRS-6-49, SRS-6-11, SRS-G-31, BR-CPF-021 … 027, BR-CPF-020): the
 * evidence card of the chosen member (added, never replaced), the decision tier of each correction, the carried balance
 * and the interface states of a zone change, and the roles each action is drawn for.
 */
afterEach(() => vi.unstubAllGlobals());

const MANAGER = me(['I-09:VIEW', 'I-09:CREATE', 'I-22:VIEW', 'I-22:CREATE'], { roles: ['CPF_MANAGER'] });
const AGM = me(['I-09:VIEW', 'I-09:APPROVE', 'I-22:VIEW', 'I-22:APPROVE'], { roles: ['AGM_CPF'] });
const NO_ROLES = me(['I-09:VIEW', 'I-22:VIEW', 'M-19:VIEW'], { roles: undefined as unknown as string[] });
const PDF = new File(['%PDF-1.7'], 'remittance.pdf', { type: 'application/pdf' });

const MEMBER: DeputedRow = {
  eis: '871902',
  name: 'H. Kumar',
  organisation: 'Ministry of Consumer Affairs',
  from: '2026-04-01',
  to: '2028-03-31',
  outstanding: 202800,
  instalment: 16900,
  balanceAfter: 185900,
  contribution: 9412,
  status: 'APPROVED',
};
const ODD: DeputedRow = { ...MEMBER, eis: '871909', name: 'P. Rao', status: 'CLOSED' as never };
const ADVICE: DeputedRow = {
  ...MEMBER,
  eis: '871901',
  name: 'K. Bose',
  organisation: null,
  to: null,
  outstanding: null as never,
  instalment: null,
  balanceAfter: null,
  contribution: null,
  status: 'AWAITING_ADVICE',
};

const ENTRY: DeputationEntry = {
  id: 8,
  eis: '871902',
  organisation: 'Ministry of Consumer Affairs',
  type: 'CONTRIBUTION_RECEIPT',
  sanctionId: null,
  payrollYymm: '202610',
  instalment: 16900,
  employee: 0,
  employer: 0,
  vpf: 0,
  outstandingBefore: 202800,
  balanceAfter: 185900,
  status: 'APPROVED',
  approvalId: 4,
  evidenceId: 31,
  evidence: [
    { id: 31, fileName: 'challan-oct.pdf', uploadedBy: 'cpf.manager', uploadedAt: '2026-10-05T05:00:00Z' },
  ],
};

function i09(rows: DeputedRow[], entries: DeputationEntry[] = [{ ...ENTRY, id: 7, evidence: [] }, ENTRY]) {
  return backend({
    'GET /v1/cpf/deputations/board': () => ({}),
    'GET /v1/cpf/deputations/members': () => paged(rows),
    'GET /v1/cpf/deputations/871902': () => ({
      eis: '871902',
      name: 'H. Kumar',
      deputationFrom: '2026-04-01',
      deputationTo: null,
      advances: [],
      monthsWithoutReceipt: [],
      entries,
    }),
    'GET /v1/cpf/deputations/': () => ({ entries: [] }),
    'GET /v1/document-types': () => [
      { code: 'OTHER', name: 'Other', referenceRequired: false },
      { code: 'SANCTION_ORDER', name: 'Sanction order', referenceRequired: true },
    ],
    'GET /v1/reference/lists': () =>
      referenceLists({
        ORDER_COPY_DOCUMENT_TYPE: [
          ['SANCTION_ORDER', 'Sanction Order'],
          ['OTHER', 'Other'],
          ['REMITTANCE_ADVICE', 'Remittance advice'],
        ],
      }),
    'POST /v1/cpf/deputation-entries/8/evidence': () => [ENTRY.evidence[0]],
    'GET /v1/cpf/deputation-entries': () => paged([]),
  });
}

async function orderCard(): Promise<HTMLElement> {
  return (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
}

describe('I-09 order card and rows', () => {
  it('SRS_G_31_theManagerAddsEvidenceToTheLatestEntryThatHoldsSomeAReferenceTypeNeedsItsNumber', async () => {
    const calls = i09([MEMBER]);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, MANAGER);
    // the card is keyed by the entry: it is drawn again once the member's entries arrive
    await screen.findByText('challan-oct.pdf', {}, { timeout: 5000 });
    const card = await orderCard();
    // the stored file of the entry that has evidence, linked to that entry (8), never to the first one (7)
    expect(await within(card).findByText('challan-oct.pdf')).toBeInTheDocument();
    expect(within(card).getByText(t('i09.type.CONTRIBUTION_RECEIPT'))).toBeInTheDocument();
    expect(within(card).getAllByRole('link', { name: t('wf.view') })[0]).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/cpf/deputation-entries/8/evidence/31'),
    );
    const [type] = Array.from(card.querySelectorAll('select')) as [HTMLSelectElement];
    const [reference, orderDate, office, file] = Array.from(card.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    await waitFor(() => expect(type.options.length).toBe(3), { timeout: 5000 });
    await user.selectOptions(type, 'SANCTION_ORDER');
    fireEvent.change(orderDate, { target: { value: businessToday() } });
    // a sanction order needs its reference: nothing is sent without it
    await user.upload(file, PDF);
    expect(
      await within(card).findByText(t('wf.ord.particularsFirst'), {}, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(sent(calls, 'POST', '/evidence')).toHaveLength(0);
    await user.type(reference, '  FCI/CPF/77  ');
    await user.type(office, ' RO Delhi ');
    await user.upload(file, PDF);
    await waitFor(
      () => expect(sent(calls, 'POST', '/v1/cpf/deputation-entries/8/evidence')).toHaveLength(1),
      {
        timeout: 5000,
      },
    );
    const form = sent(calls, 'POST', '/v1/cpf/deputation-entries/8/evidence')[0] as FormData;
    expect(form.get('reference')).toBe('FCI/CPF/77');
    expect(form.get('issuingAuthority')).toBe('RO Delhi');
    expect(form.get('documentType')).toBe('SANCTION_ORDER');
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeInTheDocument();
  });

  it('SRS_G_31_aMemberWithNoEntryHasNothingToAttachTo', async () => {
    const calls = i09([MEMBER], []);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, MANAGER);
    const card = await orderCard();
    expect(await within(card).findByText(t('wf.ord.viewOnly'))).toBeInTheDocument();
    const file = Array.from(card.querySelectorAll('input')).at(-1) as HTMLInputElement;
    await user.upload(file, PDF);
    expect(sent(calls, 'POST', '/evidence')).toHaveLength(0);
  });

  it('SRS_6_27_theManagerEditsFromARowAndAnUnknownStatusReadsGrey', async () => {
    i09([MEMBER, ODD]);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, MANAGER);
    const table = await screen.findByRole('table', { name: t('i09w.table') });
    const odd = (await within(table).findByText('871909')).closest('tr') as HTMLElement;
    expect(odd.querySelector('.pill')).toHaveClass('p-gray');
    const first = within(table).getByText('871902').closest('tr') as HTMLElement;
    expect(first).toHaveAttribute('aria-selected', 'true');
    expect(within(first).getByText(amount(185900))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('i09w.editName', ['871902']) }));
    await waitFor(() => expect(screen.getByTestId('i09-register')).toHaveAttribute('open'));
    // choosing another row moves the selection
    await user.click(odd);
    expect(odd).toHaveAttribute('aria-selected', 'true');
  });

  it('SRS_6_29_theAgmIsSentToW01OnlyForAnEntryWaitingOnTheChecker', async () => {
    i09([{ ...MEMBER, status: 'PENDING_CHECKER' }, ODD]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, AGM);
    const table = await screen.findByRole('table', { name: t('i09w.table') });
    const odd = (await within(table).findByText('871909')).closest('tr') as HTMLElement;
    await user.click(within(odd).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    const waiting = within(table).getByText('871902').closest('tr') as HTMLElement;
    await user.click(within(waiting).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS_6_27_allMembersAwaitingTheAdviceTheCardOpensOnTheFirstAndTheViewClosesWithTheDeputationEnd', async () => {
    const calls = i09([ADVICE]);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, NO_ROLES);
    const table = await screen.findByRole('table', { name: t('i09w.table') });
    await within(table).findByText('871901');
    // no role at all: import and record are locked with their reasons
    expect(screen.getByRole('button', { name: t('i09w.act.record') })).toHaveAttribute(
      'data-why',
      t('i09w.why.recordRole'),
    );
    await waitFor(() => expect(sent(calls, 'GET', '/v1/cpf/deputations/871901')).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: t('i09w.viewName', ['871901']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(new RegExp(t('i09.open')))).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(screen.getByRole('button', { name: t('i09w.viewName', ['871901']) }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('SRS_6_27_aDeputationWithAnEndShowsItInTheView', async () => {
    i09([MEMBER]);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, MANAGER);
    await user.click(await screen.findByRole('button', { name: t('i09w.viewName', ['871902']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByText(new RegExp(t('i09.open')))).toBeNull();
    expect(within(dialog).getByText('Ministry of Consumer Affairs')).toBeInTheDocument();
  });

  it('SRS_6_27_anEmptyListChoosesNoMember', async () => {
    const calls = i09([]);
    wrap(<DeputationAdjustment />, MANAGER);
    expect(await screen.findByText(t('i09.none'))).toBeInTheDocument();
    expect(calls.filter((c) => c.url.includes('/v1/cpf/deputations/8'))).toHaveLength(0);
  });
});

const LINE: CorrectionLine = {
  ref: 'OB-2026-0040',
  eis: '871903',
  name: 'M. Verma',
  cpfCode: null,
  part: 'EMPLOYER',
  current: 1000,
  corrected: 1500,
  difference: 500,
  reasonCode: 'CLERICAL_ERROR',
  evidence: null,
  interestEffect: 42.5,
  status: 'PENDING_HQ_CPF',
  evidenceId: 9,
  fileName: null,
  documentType: null,
  uploadedBy: null,
  uploadedAt: null,
};
const CANCELLED: CorrectionLine = { ...LINE, ref: 'OB-2026-0041', status: 'CANCELLED', evidenceId: null };

function i22(
  lines: CorrectionLine[],
  board: unknown = { open: 2, pendingAgm: 0, pendingHq: 1, interestQueued: 1234.5 },
) {
  return backend({
    'GET /v1/cpf/opening-balance-corrections/board': () => board,
    'GET /v1/cpf/opening-balance-corrections/lines': () => paged(lines),
    'GET /v1/cpf/opening-balance-corrections?': () => paged([]),
    'GET /v1/document-types': () => [],
    'GET /v1/reference/lists': () =>
      referenceLists({ OB_CORRECTION_REASON: [['CLERICAL_ERROR', 'Clerical error']] }),
  });
}

describe('I-22 rows, tiers and view', () => {
  it('BR_CPF_026_theQueuedInterestAndARowsInterestEffectShowOnceComputedAndADashForNoEvidence', async () => {
    i22([LINE, CANCELLED]);
    wrap(<OpeningBalanceCorrection />, MANAGER);
    const table = await screen.findByRole('table', { name: t('i22w.table') });
    const row = (await within(table).findByText('OB-2026-0040')).closest('tr') as HTMLElement;
    // no CPF code yet: the EIS stands in
    expect(within(row).getByText(/871903 M\. Verma/)).toBeInTheDocument();
    expect(within(row).getByText(amount(42.5))).toBeInTheDocument();
    expect(within(row).getByText('—')).toBeInTheDocument();
    expect(screen.getByText(amount(1234.5))).toBeInTheDocument();
    const cancelled = within(table).getByText('OB-2026-0041').closest('tr') as HTMLElement;
    expect(cancelled.querySelector('.pill')).toHaveClass('p-gray');
    // the stored evidence of the chosen line links to its request
    const card = await orderCard();
    expect(within(card).getAllByRole('link')[0]).toHaveAttribute(
      'href',
      expect.stringContaining('OB-2026-0040'),
    );
  });

  it('SRS_6_43_theManagerRaisesAndEditsFromTheRegisterAndPicksAnotherRow', async () => {
    i22([LINE, CANCELLED]);
    const user = userEvent.setup();
    wrap(<OpeningBalanceCorrection />, MANAGER);
    await user.click(await screen.findByRole('button', { name: t('i22w.act.raise') }));
    await waitFor(() => expect(screen.getByTestId('i22-register')).toHaveAttribute('open'));
    const edit = screen.getByRole('button', {
      name: t('i22w.editName', ['OB-2026-0041', t('i22.part.EMPLOYER')]),
    });
    expect(edit).toBeEnabled();
    const other = screen.getByText('OB-2026-0041').closest('tr') as HTMLElement;
    await user.click(other);
    expect(other).toHaveAttribute('aria-selected', 'true');
    await user.click(edit);
  });

  it('BR_APPR_008_theApproverIsSentToW01ForAHqTierCorrectionAndToldNothingWaitsOtherwise', async () => {
    i22([LINE, CANCELLED]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<OpeningBalanceCorrection />, AGM);
    const table = await screen.findByRole('table', { name: t('i22w.table') });
    const cancelled = (await within(table).findByText('OB-2026-0041')).closest('tr') as HTMLElement;
    await user.click(within(cancelled).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    const hq = within(table).getByText('OB-2026-0040').closest('tr') as HTMLElement;
    await user.click(within(hq).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('BR_CPF_026_theViewShowsTheInterestEffectAndTheEisWithoutACpfCode', async () => {
    i22([LINE]);
    const user = userEvent.setup();
    wrap(<OpeningBalanceCorrection />, NO_ROLES);
    expect(await screen.findByRole('button', { name: t('i22w.act.raise') })).toHaveAttribute(
      'data-why',
      t('i22w.why.raiseRole'),
    );
    await user.click(
      await screen.findByRole('button', {
        name: t('i22w.viewName', ['OB-2026-0040', t('i22.part.EMPLOYER')]),
      }),
    );
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('871903 M. Verma')).toBeInTheDocument();
    expect(within(dialog).getByText(amount(42.5))).toBeInTheDocument();
    expect(within(dialog).queryByText(t('i22.interestEffectNotConfigured'))).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS_6_43_anEmptyListLeavesTheCardEmpty', async () => {
    i22([], {});
    wrap(<OpeningBalanceCorrection />, MANAGER);
    expect(await screen.findByText(t('i22.noCorrections'))).toBeInTheDocument();
    const card = await orderCard();
    expect(within(card).getByText(t('ord.none'))).toBeInTheDocument();
  });
});

const APPLIED: ZoneChangeRow = {
  reference: 'HRMS-PZC-0014',
  eis: '871905',
  name: 'S. Gupta',
  reason: 'PROMOTION_CAT_II_TO_I',
  fromZone: 'West',
  toZone: 'North',
  order: 'CPF/HQ/2026/31',
  oldId: 'CPF/MH/2004/1188',
  newId: 'CPF/NA11/2026/0412',
  effective: '2026-07-01',
  decision: 'APPROVED',
  status: 'APPLIED',
  taskNumber: 'HCA-2026-9180',
};
const RAISED: ZoneChangeRow = {
  ...APPLIED,
  reference: 'HRMS-PZC-0020',
  name: 'R. Iyer',
  status: 'PENDING_TASK' as never,
  effective: null,
  newId: null,
  order: null,
  taskNumber: null,
};
const CARRIED: ZoneChangeDetail = {
  change: APPLIED,
  carriedEmployee: 120000,
  carriedEmployer: null,
  carriedVpf: null,
  interestTo: '2026-06-30',
  reconStatus: 'MATCHED',
  sspSyncedAt: '2026-07-03T05:00:00Z',
  ecrFrom: null,
  iocpfStatus: 'ACKNOWLEDGED',
  iocpfAcknowledged: '2026-07-04T05:00:00Z',
  ids: [
    { id: 'CPF/NA11/2026/0412', zone: null, from: '2026-07-01', to: null, status: 'ACTIVE', reason: null },
  ],
};

describe('I-13 carried balance, interfaces and tasks', () => {
  it('SRS_6_52_theCarriedBalanceAndTheInterfaceStatesOfAnAppliedChangeAreRead', async () => {
    backend({
      'GET /v1/cpf/parent-zone-changes/HRMS-PZC-0014': () => CARRIED,
      'GET /v1/cpf/parent-zone-changes': () => paged([RAISED, APPLIED]),
    });
    wrap(<ParentZoneChange />, me(['I-13:VIEW', 'I-21:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    // the card follows the applied change, not the first row
    expect(await screen.findByText(t('i13w.history', ['S. Gupta']))).toBeInTheDocument();
    expect(await screen.findByText(t('i13w.recon.MATCHED'))).toBeInTheDocument();
    expect(screen.getByText(t('i13w.k.oldClosed', [date('2026-07-01')]))).toBeInTheDocument();
    // the carried balance with the missing parts read as zero, interest to its date
    expect(
      screen.getByText(t('i13w.carried.value', [amount(120000), amount(0), amount(0), date('2026-06-30')])),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        t('i13w.k.iocpfState', [t('i13w.apps.ACKNOWLEDGED'), dateTime('2026-07-04T05:00:00Z')]),
      ),
    ).toBeInTheDocument();
    // no ECR month yet: a dash in the SSP line, never a guessed month
    expect(
      screen.getByText(t('i13w.k.sspDone', [dateTime('2026-07-03T05:00:00Z'), '—'])),
    ).toBeInTheDocument();
    // a member id without a zone, open-ended
    const history = screen.getByRole('table', { name: t('i13w.history', ['S. Gupta']) });
    expect(within(history).getAllByText('—')).toHaveLength(2);
  });

  it('SRS_6_49_theHolderOfI21GoesToItsTasksFromTheToolbarAndFromTheView', async () => {
    backend({
      'GET /v1/cpf/parent-zone-changes/': () => ({
        ...CARRIED,
        carriedEmployee: null,
        iocpfStatus: 'SENT',
        iocpfAcknowledged: null,
        sspSyncedAt: null,
      }),
      'GET /v1/cpf/parent-zone-changes': () => paged([RAISED]),
    });
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<ParentZoneChange />, me(['I-13:VIEW', 'I-21:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    // the only row is chosen; still open: the old id is not closed
    expect(await screen.findByText(t('i13w.k.oldOpen'))).toBeInTheDocument();
    expect(await screen.findByText(t('i13w.k.iocpfState', [t('i13w.apps.SENT'), '—']))).toBeInTheDocument();
    expect(screen.getByText(t('i13w.carried.none'))).toBeInTheDocument();
    expect(screen.getByText(t('i13w.k.sspNone'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('i13w.act.tasks') }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=I-21'));
    // a change with no task: the view offers no task link
    await user.click(screen.getByRole('button', { name: t('i13w.viewName', ['HRMS-PZC-0020']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('button', { name: t('i13w.act.tasks') })).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS_6_49_theViewOfAChangeWithATaskSendsTheHolderToI21', async () => {
    backend({
      'GET /v1/cpf/parent-zone-changes/HRMS-PZC-0014': () => CARRIED,
      'GET /v1/cpf/parent-zone-changes': () => paged([APPLIED]),
    });
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<ParentZoneChange />, me(['I-13:VIEW', 'I-21:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    await user.click(await screen.findByRole('button', { name: t('i13w.viewName', ['HRMS-PZC-0014']) }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: t('i13w.act.tasks') }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=I-21'));
  });

  it('SRS_6_52_theIocpfListShowsADashForARequestNotYetSent', async () => {
    backend({
      'GET /v1/cpf/parent-zone-changes/': () => CARRIED,
      'GET /v1/cpf/parent-zone-changes': () => paged([]),
      'GET /v1/cpf/iocpf-pending': () =>
        paged([
          {
            number: 'IOCPF-2',
            eis: '871906',
            name: 'A. Roy',
            reason: 'PARENT_ZONE_CHANGE',
            total: 500,
            appsStatus: 'PENDING',
            sentAt: null,
            retries: 0,
          },
        ]),
    });
    const user = userEvent.setup();
    wrap(<ParentZoneChange />, me(['I-13:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    expect(await screen.findByText(t('i13w.none'))).toBeInTheDocument();
    // nothing chosen: the card titles read dashes
    expect(screen.getByText(t('i13w.history', ['—']))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('i13w.act.iocpf') }));
    const row = (await screen.findByText('IOCPF-2')).closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeInTheDocument();
    expect(within(row).getByText(t('i13w.apps.PENDING'))).toBeInTheDocument();
  });
});

describe('M-19 without roles', () => {
  it('SRS_6_11_aProfileWithoutRolesSeesNewLockedWithTheReason', async () => {
    backend({
      'GET /v1/cpf/interest-rates': () => ({ rates: [], pending: [] }),
      'GET /v1/rules/register/m-19': () => [],
    });
    wrap(<InterestRates />, NO_ROLES);
    const created = await screen.findByRole('button', { name: t('m19w.act.new') });
    expect(created).toBeDisabled();
    expect(created).toHaveAttribute('data-why', t('m19w.why.new'));
  });
});

describe('I-09 evidence of an unlisted type', () => {
  it('SRS_G_31_aTypeTheDocumentTypesDoNotListNeedsNoReference', async () => {
    const calls = i09([MEMBER]);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, MANAGER);
    await screen.findByText('challan-oct.pdf', {}, { timeout: 5000 });
    const card = await orderCard();
    const [type] = Array.from(card.querySelectorAll('select')) as [HTMLSelectElement];
    const [, orderDate, , file] = Array.from(card.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    await waitFor(() => expect(type.options.length).toBe(3), { timeout: 5000 });
    await user.selectOptions(type, 'REMITTANCE_ADVICE');
    fireEvent.change(orderDate, { target: { value: businessToday() } });
    await user.upload(file, PDF);
    await waitFor(
      () => expect(sent(calls, 'POST', '/v1/cpf/deputation-entries/8/evidence')).toHaveLength(1),
      {
        timeout: 5000,
      },
    );
    const form = sent(calls, 'POST', '/v1/cpf/deputation-entries/8/evidence')[0] as FormData;
    expect(form.get('documentType')).toBe('REMITTANCE_ADVICE');
    expect(form.get('reference')).toBeNull();
  });
});
