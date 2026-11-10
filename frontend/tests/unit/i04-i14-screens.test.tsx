import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CpfPendingTile, CpfSanctionCard, CpfSanctions } from '@/modules/cpf';
import { LoansScreen } from '@/modules/loans';
import { NpsDeductionScreen } from '@/modules/nps';
import { backend, me, paged, problem, referenceLists, t, wrap } from './support/harness';

/**
 * I-04 and I-14 as Wireframes v6.1 draws them (OD-121; SRS-12-1, SRS-12-5, SRS-12-17, BR-LOAN-011, SRS-6-4,
 * SRS-9-1, SRS-9-1a, BR-AUDIT-009, SRS-G-30): the drawn objects for the Maker and the other roles, a role's
 * disabled action with its reason, HRMS records read-only, and every older function behind the closed section.
 */

afterEach(() => vi.unstubAllGlobals());

const HRMS_LOAN = {
  id: 1,
  loanNumber: 'LN-2024-0912',
  eis: '880601',
  employee: 'Rohit Sinha',
  loanType: 'PLI_ADVANCE',
  sanctioned: 1209600,
  recoveryStart: '202501',
  principalInstalments: 144,
  instalment: 8400,
  paidInstalments: 89,
  principalOutstanding: 462000,
  interestAmount: 0,
  interestOutstanding: 0,
  status: 'ACTIVE',
  source: 'HRMS',
  separated: false,
  scheduleReconciled: true,
};
const LABOUR_LOAN = {
  ...HRMS_LOAN,
  id: 2,
  loanNumber: 'LN-2026-0402',
  eis: '880603',
  employee: 'Harpal Rana',
  loanType: 'FESTIVAL_ADVANCE',
  source: 'LABOUR_ENT',
};
const HALTED = {
  ...HRMS_LOAN,
  id: 3,
  loanNumber: 'LN-2023-0447',
  status: 'STOPPED',
  separated: true,
};
const OFF = { ...HRMS_LOAN, id: 4, loanNumber: 'LN-2026-0388', scheduleReconciled: false };
const SUMMARY = {
  yymm: '202610',
  activeLoans: 8,
  outstanding: 29428000,
  recoveryPrincipal: 60200000,
  recoveryInterest: 6900000,
  importFailures: 3,
  failingInterface: 'INTF_PAY_LOAN_RESCHEDULE',
};
const INVOICES = [
  {
    id: 2,
    entry: 'LN-2026-0412',
    eis: '880606',
    employee: 'Mohini Vaid',
    paymentType: 'PLI Advance',
    source: 'HRMS',
    amount: 480000,
    invoiceNumber: '202610700201',
    invoiceStatus: 'SENT_TO_APPS',
    sentToApps: '2026-07-28',
    appsCancelled: false,
  },
  {
    id: 3,
    entry: 'LN-2026-0420',
    eis: '880607',
    employee: 'Laxmi Dutta',
    paymentType: 'Festival Advance',
    source: 'LABOUR_ENT',
    amount: 10000,
    invoiceNumber: null,
    invoiceStatus: 'NOT_RAISED',
    sentToApps: null,
    appsCancelled: false,
  },
];
const ORDER = {
  id: 7,
  fileName: 'order_LN-2026-0402.pdf',
  documentTypeCode: 'SANCTION_ORDER',
  documentTypeName: 'Sanction Order',
  referenceNumber: 'FCI/RO-DEL/LOAN/0402',
  uploadedBy: 'maker.delhi@fci-demo.test',
  uploadedAt: '2026-07-12T05:34:00Z',
};

const CPF_ROWS = [
  {
    id: 11,
    eis: '880601',
    memberName: 'Rohit Sinha',
    type: 'ADVANCE',
    purposeCode: 'MARRIAGE',
    purposeName: 'Marriage',
    limitRule: 'CPF advance limit - marriage (x MGW)',
    requested: 240000,
    ceiling: 1123200,
    sanctioned: 240000,
    capped: false,
    stage: 'COMPETENT_AUTHORITY',
    sanctionNumber: null,
    sanctionDate: null,
    tiers: [],
    documents: [],
  },
  {
    id: 12,
    eis: '880602',
    memberName: 'Swati Mukherjee',
    type: 'PART_FINAL',
    purposeCode: 'CONSTRUCTION',
    purposeName: 'House construction',
    limitRule: null,
    requested: 900000,
    ceiling: 730080,
    sanctioned: 730080,
    capped: true,
    stage: 'SANCTIONED',
    sanctionNumber: 'CPF/2026/1839',
    sanctionDate: '2026-10-20',
    tiers: [],
    documents: [],
  },
  {
    id: 13,
    eis: '880608',
    memberName: 'Asha Khurana',
    type: 'ADVANCE',
    purposeCode: 'ILLNESS',
    purposeName: 'Illness',
    limitRule: null,
    requested: 150000,
    ceiling: null,
    sanctioned: null,
    capped: false,
    stage: 'UNIT_VERIFICATION',
    sanctionNumber: null,
    sanctionDate: null,
    tiers: [],
    documents: [],
  },
];

const MAKER = ['I-04:VIEW', 'I-04:LOAN_SANCTION', 'I-04:LOAN_FORECLOSE', 'I-04:LOAN_RESCHEDULE'];

function loanRoutes(rows: unknown[] = [HRMS_LOAN, LABOUR_LOAN, HALTED, OFF]) {
  return {
    'GET /v1/loans/summary': () => SUMMARY,
    'GET /v1/loans/disbursements': (url: string) => paged(INVOICES, 2, url),
    'GET /v1/loans/1/orders': () => [],
    'GET /v1/loans/2/orders': () => [ORDER],
    'GET /v1/loans/types': () => [],
    'GET /v1/loans/requests': (url: string) => paged([], 0, url),
    'GET /v1/loans/2': () => ({ loan: LABOUR_LOAN, versions: [], nextInstalment: null }),
    'GET /v1/loans?': (url: string) => paged(rows, rows.length, url),
    'GET /v1/document-types': () => [
      { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
    ],
    'GET /v1/reference/lists': () =>
      referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['SANCTION_ORDER', 'Sanction Order']] }),
    'GET /v1/cpf/sanctions': (url: string) => paged(CPF_ROWS, 3, url),
    'GET /v1/cpf/sanctions/pending-summary': () => ({
      pending: 2,
      advance: 2,
      partFinal: 0,
      finalPayment: 0,
    }),
    'GET /v1/cpf/sanctions/13': () => CPF_ROWS[2],
  };
}

function screenOf() {
  return (
    <LoansScreen
      cpfTile={<CpfPendingTile />}
      cpf={(onOpen) => <CpfSanctionCard onOpen={onOpen} />}
      cpfRegister={(opening) => <CpfSanctions opening={opening} />}
    />
  );
}

describe('I-04 as drawn', () => {
  it('SRS-12-1, OD-121: the Maker sees the tiles, the ledger with its statuses and the enabled actions', async () => {
    backend(loanRoutes());
    wrap(screenOf(), MAKER);
    const ledger = await screen.findByRole('table', { name: t('i04w.ledger') });
    expect(await within(ledger).findByText('LN-2024-0912')).toBeInTheDocument();
    expect(within(ledger).getByText(t('i04w.status.HALTED_SEPARATION'))).toBeInTheDocument();
    expect(within(ledger).getByText(t('i04w.status.SCHEDULE_OFF'))).toBeInTheDocument();
    expect(await screen.findByText('INTF_PAY_LOAN_RESCHEDULE')).toBeInTheDocument();
    expect(screen.getByText(t('i04w.tile.activeSub', ['2.94']))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('i04w.foreclose') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('i04w.newSanction') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('i04w.cpfChain') })).toBeDisabled();
  });

  it('OD-121: the Checker has the drawn check mark on a loan set up here, not on an employee loan', async () => {
    backend(loanRoutes());
    wrap(screenOf(), me(['I-04:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const ledger = await screen.findByRole('table', { name: t('i04w.ledger') });
    const here = (await within(ledger).findByText('LN-2026-0402')).closest('tr') as HTMLElement;
    const hrms = within(ledger).getByText('LN-2024-0912').closest('tr') as HTMLElement;
    expect(within(here).getByTitle(t('wf.approveTitle'))).toBeEnabled();
    expect(within(hrms).queryByTitle(t('wf.approveTitle'))).not.toBeInTheDocument();
  });

  it('OD-121: the Maker has no check mark', async () => {
    backend(loanRoutes());
    wrap(screenOf(), MAKER);
    const ledger = await screen.findByRole('table', { name: t('i04w.ledger') });
    await within(ledger).findByText('LN-2026-0402');
    expect(within(ledger).queryByTitle(t('wf.approveTitle'))).not.toBeInTheDocument();
  });

  it('BR-LOAN-011: another role sees the actions locked with the reason', async () => {
    backend(loanRoutes());
    wrap(screenOf(), ['I-04:VIEW']);
    await screen.findByRole('table', { name: t('i04w.ledger') });
    const foreclose = screen.getByRole('button', { name: t('i04w.foreclose') });
    expect(foreclose).toBeDisabled();
    expect(foreclose).toHaveAttribute('title', t('i04w.forecloseWhy'));
    expect(screen.getByRole('button', { name: t('i04w.reschedule') })).toHaveAttribute(
      'title',
      t('i04w.rescheduleWhy'),
    );
    expect(screen.getByRole('button', { name: t('i04w.import') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('i04w.newSanction') })).toBeDisabled();
    expect(await screen.findByRole('button', { name: t('i04w.editOf', ['LN-2026-0402']) })).toBeDisabled();
  });

  it('SRS-12-17: an employee loan is read-only; a loan set up here opens for its change', async () => {
    const user = userEvent.setup();
    backend(loanRoutes());
    wrap(screenOf(), MAKER);
    const hrms = await screen.findByRole('button', { name: t('i04w.editOf', ['LN-2024-0912']) });
    expect(hrms).toBeDisabled();
    expect(hrms.dataset.why).toBe(t('i04w.editHrms'));
    await user.click(screen.getByRole('button', { name: t('i04w.editOf', ['LN-2026-0402']) }));
    const section = screen.getByTestId('i04-register');
    expect(section).toHaveAttribute('open');
    expect(await within(section).findByRole('region', { name: 'LN-2026-0402' })).toBeInTheDocument();
  });

  it('SRS-12-22: Foreclose opens the first loan set up here when an employee loan is chosen', async () => {
    const user = userEvent.setup();
    backend(loanRoutes());
    wrap(screenOf(), MAKER);
    await screen.findByText('LN-2024-0912');
    await user.click(screen.getByRole('button', { name: t('i04w.foreclose') }));
    expect(await screen.findByRole('region', { name: 'LN-2026-0402' })).toBeInTheDocument();
  });

  it('SRS-12-17: with only employee loans Reschedule says where they change', async () => {
    const user = userEvent.setup();
    backend(loanRoutes([HRMS_LOAN]));
    wrap(screenOf(), MAKER);
    await screen.findByText('LN-2024-0912');
    await user.click(screen.getByRole('button', { name: t('i04w.reschedule') }));
    expect(await screen.findByText(t('i04w.noLabourLoan'))).toBeInTheDocument();
    expect(screen.getByTestId('i04-register')).not.toHaveAttribute('open');
  });

  it('Import Loan Events sends nothing and says that HRMS pushes the events', async () => {
    const user = userEvent.setup();
    const calls = backend(loanRoutes());
    wrap(screenOf(), MAKER);
    await user.click(await screen.findByRole('button', { name: t('i04w.import') }));
    expect(await screen.findByText(t('i04w.importWhy'))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('View shows the record and opens it in the closed section', async () => {
    const user = userEvent.setup();
    backend(loanRoutes());
    wrap(screenOf(), ['I-04:VIEW']);
    await user.click(await screen.findByRole('button', { name: t('i04w.viewOf', ['LN-2026-0402']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('880603 Harpal Rana')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('i04w.openRecord') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(await screen.findByRole('region', { name: 'LN-2026-0402' })).toBeInTheDocument();
  });

  it('New Loan Sanction opens the closed section on the sanction form', async () => {
    const user = userEvent.setup();
    backend(loanRoutes());
    wrap(screenOf(), MAKER);
    await user.click(await screen.findByRole('button', { name: t('i04w.newSanction') }));
    expect(await screen.findByText(t('i04l.newSanction'))).toBeInTheDocument();
  });

  it('BR-AUDIT-009: every drawn Cancel is off with its reason (no version record yet)', async () => {
    backend(loanRoutes());
    wrap(screenOf(), MAKER);
    const table = await screen.findByRole('table', { name: t('i04w.disbursements') });
    const sent = await within(table).findAllByRole('button', { name: t('wf.inv.cancel') });
    expect(sent[0]).toHaveAttribute('title', t('i04w.inv.whySent'));
    expect(sent[1]).toHaveAttribute('title', t('i04w.inv.whyPending'));
    expect(within(table).getByText(t('i04w.inv.fromLabour', ['Festival Advance']))).toBeInTheDocument();
  });

  it('BR-AUDIT-009: a role other than the Maker is told who raises a cancellation', async () => {
    backend(loanRoutes());
    wrap(screenOf(), ['I-04:VIEW']);
    const table = await screen.findByRole('table', { name: t('i04w.disbursements') });
    const buttons = await within(table).findAllByRole('button', { name: t('wf.inv.cancel') });
    expect(buttons[1]).toHaveAttribute('title', t('i04w.inv.whyMaker'));
  });

  it('SRS-G-30: the chosen loan set up here stores an order; its file goes to the loan', async () => {
    const calls = backend({ ...loanRoutes([LABOUR_LOAN]), 'POST /v1/loans/2/orders': () => ORDER });
    wrap(screenOf(), MAKER);
    expect(await screen.findByText('order_LN-2026-0402.pdf')).toBeInTheDocument();
    const file = new File(['%PDF'], 'order.pdf', { type: 'application/pdf' });
    const picker = document.querySelector<HTMLInputElement>('input[type="file"]');
    if (!picker) throw new Error('no file input');
    const inputs = document.querySelectorAll<HTMLInputElement>('.rf-field input');
    const [reference, date] = [...inputs].filter((i) => i.type !== 'file');
    if (!reference || !date) throw new Error('no order fields');
    fireEvent.change(reference, { target: { value: 'FCI/EST/2026/118' } });
    fireEvent.change(date, { target: { value: '2026-07-01' } });
    await waitFor(() => expect(picker).toBeEnabled());
    await userEvent.setup().upload(picker, file);
    await waitFor(() =>
      expect(calls.some((c) => c.method === 'POST' && c.url.includes('/v1/loans/2/orders'))).toBe(true),
    );
  });

  it('a refused summary is shown; the tiles read zero', async () => {
    backend({ ...loanRoutes(), 'GET /v1/loans/summary': () => problem(403, 'AUTHZ-403-SCOPE') });
    wrap(screenOf(), MAKER);
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
    expect(screen.getByText(t('i04w.tile.failuresNone'))).toBeInTheDocument();
  });

  it('every older function stays in the closed section', async () => {
    const user = userEvent.setup();
    backend(loanRoutes());
    wrap(screenOf(), ['I-04:VIEW', 'I-04:EXPORT']);
    await screen.findByText('LN-2024-0912');
    expect(screen.queryByRole('table', { name: t('i04l.loans') })).toBeNull();
    await user.click(screen.getByText(t('i04w.register')));
    expect(await screen.findByRole('table', { name: t('i04l.loans') })).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: t('i04.register') })).toBeInTheDocument();
    expect(screen.getByText(t('i04l.report'))).toBeInTheDocument();
  });
});

describe('I-04 CPF card as drawn', () => {
  const card = (onOpen = vi.fn()) => <CpfSanctionCard onOpen={onOpen} />;

  it('SRS-6-4: the tile counts the requests waiting at a tier', async () => {
    backend(loanRoutes());
    wrap(<CpfPendingTile />, ['I-04:VIEW']);
    expect(await screen.findByText(t('i04c.tileSub', [2, 0, 0]))).toBeInTheDocument();
  });

  it('OD-121: the Checker has the drawn check mark on a request in the chain and is told who decides', async () => {
    backend(loanRoutes());
    wrap(card(), me(['I-04:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const table = await screen.findByRole('table', { name: t('i04c.card') });
    const marks = await within(table).findAllByTitle(t('wf.approveTitle'));
    await userEvent.click(marks[0] as HTMLElement);
    expect(await screen.findByText(t('i04c.tierWhy'))).toBeInTheDocument();
  });

  it('BR-CPF-029: rule, eligible, tier and status of each row', async () => {
    backend(loanRoutes());
    wrap(card(), MAKER);
    const table = await screen.findByRole('table', { name: t('i04c.card') });
    expect(await within(table).findByText('CPF advance limit - marriage (x MGW)')).toBeInTheDocument();
    expect(within(table).getByText(t('i04c.requestNo', [11]))).toBeInTheDocument();
    expect(within(table).getByText('CPF/2026/1839')).toBeInTheDocument();
    expect(within(table).getByText(t('i04c.status.capped'))).toBeInTheDocument();
    expect(within(table).getByText(t('i04c.eligiblePending'))).toBeInTheDocument();
    expect(within(table).getByText(t('i04c.tier.unit'))).toBeInTheDocument();
  });

  it('SRS-6-4: the Maker is told the tier is another role; the tier that may act opens the request', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    backend(loanRoutes());
    const view = wrap(card(onOpen), MAKER);
    const edit = await screen.findByRole('button', { name: t('i04c.editOf', [t('i04c.requestNo', [13])]) });
    await user.click(edit);
    expect(await screen.findByText(t('i04c.tierWhy'))).toBeInTheDocument();
    expect(onOpen).not.toHaveBeenCalled();
    view.unmount();
    wrap(card(onOpen), ['I-04:VIEW', 'I-04:EDIT']);
    await user.click(
      await screen.findByRole('button', { name: t('i04c.editOf', [t('i04c.requestNo', [13])]) }),
    );
    expect(onOpen).toHaveBeenCalledWith(13);
  });

  it('a sanctioned request and a viewer without a role right have Edit off with the reason', async () => {
    backend(loanRoutes());
    const view = wrap(card(), MAKER);
    expect(await screen.findByRole('button', { name: t('i04c.editOf', ['CPF/2026/1839']) })).toHaveAttribute(
      'title',
      t('wf.editOff', [t('i04c.editDone')]),
    );
    view.unmount();
    wrap(card(), ['I-04:VIEW']);
    expect(
      await screen.findByRole('button', { name: t('i04c.editOf', [t('i04c.requestNo', [13])]) }),
    ).toBeDisabled();
  });

  it('BR-CPF-029: Limit check reads the chosen request; none is chosen on an empty page', async () => {
    const user = userEvent.setup();
    backend(loanRoutes());
    const view = wrap(card(), MAKER);
    await screen.findByText('CPF/2026/1839');
    await user.click(screen.getByRole('button', { name: t('i04c.limitCheck') }));
    expect(
      await screen.findByText(t('i04c.check.within', [t('i04c.requestNo', [11]), '2,40,000', '11,23,200'])),
    ).toBeInTheDocument();
    view.unmount();
    backend({ 'GET /v1/cpf/sanctions': (url: string) => paged([], 0, url) });
    wrap(card(), MAKER);
    await user.click(await screen.findByRole('button', { name: t('i04c.limitCheck') }));
    expect((await screen.findAllByText(t('i04.none'))).length).toBeGreaterThan(0);
  });

  it('View shows the request and opens it', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    backend(loanRoutes());
    wrap(card(onOpen), ['I-04:VIEW']);
    await user.click(await screen.findByRole('button', { name: t('i04c.viewOf', ['CPF/2026/1839']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('i04c.rule.configured'))).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('i04w.openRecord') }));
    expect(onOpen).toHaveBeenCalledWith(12);
  });
});

describe('I-14 as drawn', () => {
  const ROWS = [
    {
      id: 1,
      eis: '880612',
      employee: 'Vijay Pillai',
      office: 'DO Delhi',
      npsFlag: true,
      pran: '110045678912',
      employeeAmount: null,
      employerAmount: null,
      deducted: null,
      effectiveFrom: null,
      status: 'FLAG_WITHOUT_AMOUNT',
      payPattern: 'CDA',
      providentFund: 'NPS',
    },
    {
      id: 2,
      eis: '880613',
      employee: 'Debashis Paul',
      office: null,
      npsFlag: false,
      pran: null,
      employeeAmount: 6200,
      employerAmount: null,
      deducted: null,
      effectiveFrom: '2026-01-01',
      status: 'AMOUNT_WITHOUT_FLAG',
      payPattern: 'CDA',
      providentFund: 'GPF',
    },
    {
      id: 3,
      eis: '880610',
      employee: 'Subir Ghosh',
      office: 'DO Delhi',
      npsFlag: true,
      pran: '110012345678',
      employeeAmount: 9120,
      employerAmount: 12768,
      deducted: 9120,
      effectiveFrom: '2026-01-01',
      status: 'APPLIED',
      payPattern: null,
      providentFund: null,
    },
  ];
  const routes = {
    'GET /v1/nps-deductions/summary': () => ({
      yymm: '202601',
      members: 3,
      employeeAmount: 46200000,
      employerAmount: 64700000,
      mismatches: 2,
    }),
    'GET /v1/nps-deductions/counts': () => [{ status: 'APPLIED', count: 1 }],
    'GET /v1/nps-deductions?': (url: string) => paged(ROWS, 3, url),
  };

  it('SRS-9-1: the tiles and the table read the month shown, as received', async () => {
    const calls = backend(routes);
    wrap(<NpsDeductionScreen />, ['I-14:VIEW', 'I-14:EXPORT']);
    expect(await screen.findByText(t('i14w.table', ['JAN 2026']))).toBeInTheDocument();
    expect(screen.getByText(t('i14w.tile.members', ['JAN']))).toBeInTheDocument();
    expect(screen.getByText(t('i14w.crore', ['4.62']))).toBeInTheDocument();
    expect(await screen.findByText(t('i14w.status.FLAG_WITHOUT_AMOUNT'))).toBeInTheDocument();
    expect(screen.getByText('— CDA, GPF')).toBeInTheDocument();
    expect(screen.getByText(t('i14w.flag.no'))).toBeInTheDocument();
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/nps-deductions?yymm=202601'))).toBe(true),
    );
  });

  it('R-SRC: Edit is off with its reason on every record; nothing writes', async () => {
    const calls = backend(routes);
    wrap(<NpsDeductionScreen />, ['I-14:VIEW']);
    const edit = await screen.findByRole('button', { name: t('i14w.editOf', ['880610']) });
    expect(edit).toBeDisabled();
    expect(edit.dataset.why).toBe(t('i14w.editWhy'));
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
  });

  it('a viewer without I-14 EXPORT is told why on pressing Export and the report', async () => {
    const user = userEvent.setup();
    backend(routes);
    wrap(<NpsDeductionScreen />, ['I-14:VIEW']);
    await user.click(await screen.findByRole('button', { name: t('i14w.exportTop') }));
    expect(await screen.findByText(t('reason.noPermission'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('i14w.export') })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: t('i14w.report') }));
    expect(screen.getByTestId('i14-register')).not.toHaveAttribute('open');
  });

  it('SRS-9-6: the report button opens the closed section with the filters and the report', async () => {
    const user = userEvent.setup();
    backend(routes);
    wrap(<NpsDeductionScreen />, ['I-14:VIEW', 'I-14:EXPORT']);
    await user.click(await screen.findByRole('button', { name: t('i14w.report') }));
    expect(screen.getByTestId('i14-register')).toHaveAttribute('open');
    expect(await screen.findByLabelText(t('i14.month'))).toBeInTheDocument();
    expect(screen.getByText(t('i14.report'))).toBeInTheDocument();
  });

  it('View shows the record and a mismatch says it is corrected in HRMS', async () => {
    const user = userEvent.setup();
    backend(routes);
    wrap(<NpsDeductionScreen />, ['I-14:VIEW']);
    await user.click(await screen.findByRole('button', { name: t('i14w.viewOf', ['880612']) }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(`${t('i14w.status.FLAG_WITHOUT_AMOUNT')} — ${t('i14.fixInHrms')}`),
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await user.click(screen.getByRole('button', { name: t('i14w.viewOf', ['880610']) }));
    expect(within(await screen.findByRole('dialog')).getByText(t('i14w.status.APPLIED'))).toBeInTheDocument();
  });

  it('a unit with no records keeps the month of the session and reads zero', async () => {
    backend({
      'GET /v1/nps-deductions/summary': () => ({
        yymm: null,
        members: 0,
        employeeAmount: 0,
        employerAmount: 0,
        mismatches: 0,
      }),
      'GET /v1/nps-deductions?': (url: string) => paged([], 0, url),
    });
    wrap(<NpsDeductionScreen />, me(['I-14:VIEW'], { period: '2026-10' }));
    expect(await screen.findByText(t('i14w.table', ['OCT 2026']))).toBeInTheDocument();
    expect(await screen.findByText(t('i14.none'))).toBeInTheDocument();
  });
});
