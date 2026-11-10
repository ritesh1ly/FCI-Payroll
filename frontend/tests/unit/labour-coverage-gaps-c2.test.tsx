import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourMaster } from '@/modules/labour';
import type { GangLine, MemberLine } from '@/modules/labour/api/labourScreenApi';
import { backend, me, paged, referenceLists, t, wrap, type Route } from './support/harness';

/**
 * M-02 Labour Master (Wireframes v6.1, OD-121; SRS-2-27, SRS-2-30, SRS-2-59, SRS-G-30) coverage gaps: the filters,
 * the register actions of Establishment / HR, a member with nothing recorded, the Payroll Checker's ✔, the
 * attachments' states and the order copy stored against the member viewed.
 */

const engine = vi.hoisted(() => ({ value: null as string | null }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(engine.value ? `engine=${engine.value}` : ''),
}));

afterEach(() => {
  engine.value = null;
  vi.unstubAllGlobals();
});

const MEMBER: MemberLine = {
  employeeId: 11,
  labourCode: 'DL-20114',
  name: 'Hemant Kumar',
  payrollType: 'DEPT_LABOUR',
  designation: 'Sardar',
  gangRole: 'SARDAR',
  gang: 'GNG-01',
  depot: 'Depot Okhla',
  aadhaarMasked: '********2201',
  basicWage: 24800,
  cpfCode: 'CPF/DL-20114',
  status: 'ACTIVE',
  transferPending: false,
};

const BARE: MemberLine = {
  ...MEMBER,
  employeeId: 12,
  labourCode: 'NW-10450',
  name: 'Ravi Teja',
  payrollType: 'NWNP_LABOUR',
  designation: null,
  gangRole: null,
  gang: null,
  depot: null,
  aadhaarMasked: null,
  basicWage: null,
  cpfCode: null,
};

const GANG: GangLine = {
  gangId: 5,
  gangCode: 'GNG-01',
  gangName: 'Okhla Shed A Gang 1',
  payrollType: 'DEPT_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  depot: 'Depot Okhla',
  shed: null,
  sardar: 'Hemant Kumar',
  mandal: 'Jagdish Yadav',
  labourCount: 4,
  effectiveFrom: '2025-08-07',
  effectiveTo: null,
  endBlocked: false,
  pending: false,
};

const lists = referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['SANCTION_ORDER', 'Sanction Order']] });

const routes = (rows: MemberLine[] = [MEMBER], over: Record<string, Route> = {}): Record<string, Route> => ({
  'GET /v1/reference/lists': () => lists,
  'GET /v1/labour/options': () => ({
    depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
    designations: [],
  }),
  'GET /v1/labour/gangs': () => paged([GANG]),
  'GET /v1/labour/registers/members': () => paged(rows),
  'GET /v1/labour/registers/attachments': () =>
    paged([
      {
        id: 1,
        reference: 'TA-2026-9413',
        labourCode: 'DL-20302',
        labour: 'Mohan Lal',
        category: 'HANDLING',
        gang: 'Okhla Shed A Gang 1',
        from: '2026-10-01',
        to: '2026-12-31',
        status: 'ON_HOLD',
      },
    ]),
  'GET /v1/labour/registers/light-duty-orders': () => paged([]),
  'GET /v1/labour/members/11/documents': () => [],
  'GET /v1/labour/members/12/documents': () => [],
  'GET /v1/document-types': () => [{ code: 'SANCTION_ORDER', referenceRequired: true }],
  'GET /v1/labour/members': () => paged([]),
  ...over,
});

const memberCalls = (calls: { url: string }[]) => calls.filter((c) => c.url.includes('/registers/members'));
const register = () => screen.getByTestId('m02-register') as HTMLDetailsElement;

describe('M-02 filters', () => {
  it('SRS-2-27: the type, depot and gang filters each ask the register again with the choice', async () => {
    const calls = backend(routes());
    wrap(<LabourMaster />, ['M-02:VIEW']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    const gang = screen.getByRole('combobox', { name: t('i01w.allGangs') });
    await within(gang).findByRole('option', { name: 'GNG-01' });
    fireEvent.change(gang, { target: { value: '5' } });
    await waitFor(() => expect(memberCalls(calls).some((c) => c.url.includes('gang=GNG-01'))).toBe(true));
    expect((gang as HTMLSelectElement).value).toBe('5');
    fireEvent.change(gang, { target: { value: '' } });
    await waitFor(() => expect((gang as HTMLSelectElement).value).toBe(''));

    const depot = screen.getByRole('combobox', { name: t('lw.depotFilter') });
    await within(depot).findByRole('option', { name: 'Depot Okhla' });
    fireEvent.change(depot, { target: { value: '7' } });
    await waitFor(() => expect(memberCalls(calls).some((c) => c.url.includes('depot=7'))).toBe(true));
    fireEvent.change(depot, { target: { value: '' } });
    await waitFor(() => expect((depot as HTMLSelectElement).value).toBe(''));

    fireEvent.change(screen.getByPlaceholderText(t('wf.searchInList')), {
      target: { value: ' Hemant ' },
    });
    await waitFor(() => expect(memberCalls(calls).some((c) => c.url.includes('q=Hemant'))).toBe(true));
    fireEvent.change(gang, { target: { value: '5' } });
    fireEvent.change(screen.getByRole('combobox', { name: t('i01w.typeOf') }), {
      target: { value: 'DPS_LABOUR' },
    });
    await waitFor(() => expect(memberCalls(calls).some((c) => c.url.includes('type=DPS_LABOUR'))).toBe(true));
    expect(
      screen.getByRole('heading', { name: t('m02w.title', [t('i01w.type.DPS_LABOUR')]) }),
    ).toBeInTheDocument();
    expect((gang as HTMLSelectElement).value).toBe('');
  });
});

describe('M-02 Establishment / HR actions', () => {
  it('SRS-2-30: Edit Record, Transfer and Separation open the register and ask for a member', async () => {
    backend(routes());
    wrap(<LabourMaster />, ['M-02:VIEW', 'M-02:EDIT', 'M-09:EDIT']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    expect(screen.getByRole('link', { name: t('m02w.newAttachment') })).toHaveAttribute(
      'href',
      expect.stringContaining('?s=M-09'),
    );
    for (const name of ['m02w.editRecord', 'm02w.transfer', 'm02w.separation']) {
      fireEvent.click(screen.getByRole('button', { name: t(name) }));
      await waitFor(() => expect(register().open).toBe(true));
      expect(screen.getAllByText(t('m02w.pickMember')).length).toBeGreaterThan(0);
      register().open = false;
      fireEvent(register(), new Event('toggle'));
      await waitFor(() => expect(register().open).toBe(false));
    }
  });

  it('SRS-G-30: the order copy is stored against the member viewed with its particulars', async () => {
    const calls = backend(
      routes([MEMBER], {
        'GET /v1/document-types': () => [{ code: 'SANCTION_ORDER', referenceRequired: false }],
        'POST /v1/labour/members/11/documents': () => ({ id: 4 }),
        'GET /v1/labour/members/11/documents': () => [
          {
            id: 3,
            fileName: 'labour_order.pdf',
            documentTypeCode: 'SANCTION_ORDER',
            documentTypeName: 'Sanction Order',
            referenceNumber: 'DO/OKH/TR/1',
            uploadedBy: 'hr.delhi@fci-demo.test',
            uploadedAt: '2026-10-08T10:00:00Z',
          },
        ],
      }),
    );
    wrap(<LabourMaster />, ['M-02:VIEW', 'M-02:EDIT']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    const stored = await within(card).findByText('labour_order.pdf');
    expect(stored.closest('tr')?.querySelector('a')).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/labour/members/11/documents/3'),
    );
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    const pdf = (name: string) => new File(['%PDF'], name, { type: 'application/pdf' });
    // Without the order date, nothing is sent.
    fireEvent.change(file, { target: { files: [pdf('order.pdf')] } });
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    const fill = () => {
      fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
        target: { value: '2026-01-05' },
      });
    };
    fill();
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.referencePlaceholder')), {
      target: { value: ' DO/OKH/TR/2 ' },
    });
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.officePlaceholder')), {
      target: { value: 'Depot Manager, Okhla' },
    });
    fireEvent.change(file, { target: { files: [pdf('order.pdf')] } });
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeInTheDocument();
    // A type that needs no reference: the order goes without reference and office, one POST per file.
    fill();
    fireEvent.change(file, { target: { files: [pdf('order2.pdf'), pdf('annex.pdf')] } });
    expect(await within(card).findByText(t('wf.ord.stored', [2]))).toBeInTheDocument();
    const posts = calls
      .filter((c) => c.method === 'POST' && c.url.includes('/members/11/documents'))
      .map((c) => c.body as FormData);
    expect(posts).toHaveLength(3);
    expect(posts[0]?.get('reference')).toBe('DO/OKH/TR/2');
    expect(posts[0]?.get('issuingAuthority')).toBe('Depot Manager, Okhla');
    expect(posts[0]?.get('documentType')).toBe('SANCTION_ORDER');
    expect(posts[0]?.get('orderDate')).toBe('2026-01-05');
    expect(posts[1]?.get('reference')).toBeNull();
    expect(posts[1]?.get('issuingAuthority')).toBeNull();
    expect((posts[2]?.get('file') as File).name).toBe('annex.pdf');
  });
  it('SRS-G-30: a document type the document module does not list needs no reference', async () => {
    const calls = backend(
      routes([MEMBER], {
        'GET /v1/document-types': () => [],
        'POST /v1/labour/members/11/documents': () => ({ id: 5 }),
      }),
    );
    wrap(<LabourMaster />, ['M-02:VIEW', 'M-02:EDIT']);
    await within(await screen.findByRole('table', { name: t('m02w.tableLabel') })).findByText('DL-20114');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    await within(card).findByText(t('ord.none'));
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-01-05' },
    });
    fireEvent.change(card.querySelector('input[type=file]') as HTMLInputElement, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });
});

describe('M-02 a member with nothing recorded', () => {
  it('SRS-2-27: the NWNP row and its View read — for every missing value and the MGW of no gang role', async () => {
    engine.value = 'NWNP';
    backend(routes([BARE]));
    wrap(<LabourMaster />, ['M-02:VIEW']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('NW-10450');
    const row = within(table).getByText('NW-10450').closest('tr') as HTMLElement;
    // Designation, gang, depot, Aadhaar, the MGW of no gang role (m02w.mgw.NONE) and CPF code.
    expect(t('m02w.mgw.NONE')).toBe('—');
    expect(within(row).getAllByText('—')).toHaveLength(6);
    fireEvent.click(within(row).getByRole('button', { name: `${t('common.view')} NW-10450` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getAllByText('—')).toHaveLength(6);
    expect(within(dialog).getByText(t('m02w.col.mgw'))).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('SRS-2-27: a Departmental View reads the Basic wage', async () => {
    backend(routes());
    wrap(<LabourMaster />, ['M-02:VIEW']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    fireEvent.click(within(table).getByRole('button', { name: `${t('common.view')} DL-20114` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('m02w.col.basic'))).toBeInTheDocument();
    expect(within(dialog).getByText('24,800')).toBeInTheDocument();
  });

  it('SRS-2-27: an empty register says so, and History tells there is no member to show', async () => {
    backend(routes([]));
    wrap(<LabourMaster />, ['M-02:VIEW']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    expect(await within(table).findByText(t('m02w.none'))).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t('lw.history') }));
    expect(await screen.findAllByText(t('m02w.none'))).toHaveLength(2);
  });
});

describe('M-02 Payroll Checker and attachments', () => {
  it('SRS-2-59: the Checker’s ✔ goes to W-01 for a pending transfer and says nothing waits otherwise', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    backend(
      routes([
        { ...MEMBER, transferPending: true },
        { ...BARE, payrollType: 'DEPT_LABOUR' },
      ]),
    );
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:APPROVE'], { roles: ['PAYROLL_CHECKER'] }));
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    const [pending, idle] = within(table).getAllByRole('button', { name: '✔' });
    fireEvent.click(pending as HTMLElement);
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
    fireEvent.click(idle as HTMLElement);
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it('SRS-2-47: a dated attachment shows its end date and an unknown state is grey', async () => {
    backend(routes());
    wrap(<LabourMaster />, ['M-02:VIEW']);
    const table = await screen.findByRole('table', { name: t('m02w.attach') });
    const row = (await within(table).findByText('TA-2026-9413')).closest('tr') as HTMLElement;
    expect(within(row).getByText('31 Dec 2026')).toBeInTheDocument();
    expect(within(row).queryByText(t('m02w.att.permanent'))).not.toBeInTheDocument();
    expect(row.querySelector('.pill')).toHaveClass('p-gray');
    const explained = screen.getByRole('button', { name: t('m02w.newAttachment') });
    fireEvent.click(explained);
    expect(await screen.findByText(t('m02w.why.attach'))).toBeInTheDocument();
  });
});
