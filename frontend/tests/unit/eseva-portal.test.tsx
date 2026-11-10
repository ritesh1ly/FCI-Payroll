import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { messages } from '@/generated/messages';
import { MyHrmsUpdate } from '@/modules/employee';
import { EsevaPortal } from '@/modules/eseva';
import { SCREENS } from '@/modules/shell/components/screenRegistry';
import { periodLabel } from '@/shared/session';
import { businessToday, businessYymm, finYearOfDay, yymmToMonth } from '@/shared/time';
import {
  backend,
  type Call,
  captureDownloads,
  me,
  noContent,
  paged,
  problem,
  sent,
  t,
  wrap,
} from './support/harness';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/payroll',
  useSearchParams: () => new URLSearchParams(),
}));

/**
 * E-01 as Wireframes v6.1 draws it (OD-121, OD-135; SRS-17-1, SRS-17-6, SRS-17-7, SRS-17-8, SRS-17-10,
 * SRS-17-14, SRS-17-17, BR-ESEVA-004): the member's mode, the staff support preview (no /mine call, every
 * submit disabled with its drawn reason, a session opened with a reason and closed), TDS not configured,
 * no locked month yet, the labour layout, and the wrong paths. Dates derive from today.
 */

afterEach(() => vi.unstubAllGlobals());

const MEMBER = ['E-01:VIEW', 'E-01:SELF', 'E-01:CREATE'];
const STAFF = ['E-01:VIEW', 'E-02:VIEW'];
const YYMM = businessYymm();
const MONTH = periodLabel(yymmToMonth(YYMM));
const FY = finYearOfDay(businessToday());

const overview = (over: object = {}) => ({
  latest: { yymm: YYMM, gross: 215750, deductions: 35387, net: 180363 },
  cpf: { finYear: FY, balance: 1955520, vpfMonthly: 5000 },
  loan: { outstanding: 470400, number: 'LN-1', type: 'HBA', paid: 88, total: 144, open: 1 },
  tds: { finYear: FY, amount: 36220, regime: 'NEW' },
  requests: { open: 2, hrms: 1, cpf: 1, other: 0 },
  myRequests: [
    { type: 'HRMS_UPDATE_REQUEST', value: 'HUR-1', status: 'ROUTED', submittedAt: '2026-10-01T05:00:00Z' },
    { type: 'VPF_SUBSCRIPTION', value: 'VPF 7500', status: 'SUBMITTED', submittedAt: '2026-10-02T05:00:00Z' },
    { type: 'TAX_DECLARATION', value: null, status: 'ACTIONED', submittedAt: '2026-10-03T05:00:00Z' },
    { type: 'PAYROLL_QUERY', value: null, status: 'REJECTED', submittedAt: '2026-10-04T05:00:00Z' },
  ],
  documents: [
    { kind: 'PAY_SLIP', period: YYMM, issued: null, state: 'NOT_YET_AVAILABLE', id: null },
    { kind: 'PAY_SLIP', period: YYMM, issued: '2026-10-30T05:00:00Z', state: 'FINAL', id: null },
    { kind: 'FORM_16', period: FY, issued: '2026-06-12T05:00:00Z', state: 'ISSUED', id: 7 },
    { kind: 'FORM_16', period: FY, issued: null, state: 'NOT_DUE', id: null },
    { kind: 'FORM_12BA', period: FY, issued: null, state: 'NOT_AVAILABLE', id: null },
    { kind: 'CPF_SLIP', period: FY, issued: null, state: 'NOT_AVAILABLE', id: null },
  ],
  ...over,
});

const slip = (over: object = {}) => ({
  yymm: YYMM,
  eis: '104882',
  name: 'R. Sharma',
  designation: 'Manager',
  office: 'Regional Office Delhi',
  payPattern: 'IDA',
  panMasked: '******234K',
  bankAccountMasked: '********4412',
  pran: null,
  earnings: [
    { payCode: 101, name: 'Basic Pay', group: 'PAY', amount: 62400 },
    { payCode: 111, name: 'Dearness Allowance', group: 'PAY', amount: 31200 },
  ],
  deductions: [{ payCode: 904, name: 'Income Tax (TDS)', group: 'TAX', amount: 9055 }],
  gross: 215750,
  totalDeductions: 35387,
  net: 180363,
  layoutVariant: 'STAFF',
  provisional: false,
  ...over,
});

const OWN = {
  eis: '104882',
  firstName: 'R.',
  lastName: 'Sharma',
  designation: 'Manager',
  payPattern: 'IDA',
  band: 'E-4',
  location: 'Regional Office Delhi',
  cpfCode: 'CPF/104882',
};

const PREVIEW = {
  sessionId: 41,
  openedAt: '2026-10-31T05:00:00Z',
  submitEnabled: false,
  member: {
    eis: '104882',
    name: 'R. Sharma',
    office: 'Regional Office Delhi',
    employeeType: 'STAFF',
    employmentStatus: 'IN_SERVICE',
    panMasked: '******234K',
    aadhaarMasked: '********1234',
    bankAccountMasked: '********4412',
    mobileMasked: '******3210',
    emailMasked: 'r***@example.test',
  },
  payslipMonths: [{ yymm: YYMM, gross: 215750, deductions: 35387, net: 180363 }],
  submissions: [
    { type: 'VPF_SUBSCRIPTION', value: 'VPF 7500', status: 'SUBMITTED', submittedAt: '2026-10-02T05:00:00Z' },
  ],
};

const memberRoutes = (over: Record<string, (url: string) => unknown> = {}) => ({
  'GET /v1/eseva/overview/mine': () => overview(),
  'GET /v1/eseva/me': () => OWN,
  'GET /v1/payslips/mine/': () => slip(),
  'GET /v1/eseva/privacy-notice/mine': () => ({ version: '1', acknowledged: true }),
  'GET /v1/hrms-update-requests/fields': () => [{ code: 'BASIC_PAY', label: 'Basic Pay', group: 'PAY' }],
  'GET /v1/document-types': () => [],
  'GET /v1/hrms-update-requests/mine': (url: string) => paged([], 0, url),
  'GET /v1/eseva/notifications/mine': (url: string) => paged([], 0, url),
  ...over,
});

const staffRoutes = (over: Record<string, (url: string) => unknown> = {}) => ({
  'POST /v1/eseva/support-previews': () => PREVIEW,
  'POST /v1/eseva/support-previews/41/closures': () => noContent(),
  'GET /v1/eseva/support-previews/41/overview': () => overview(),
  'GET /v1/payslips/previews/41/': () => slip(),
  ...over,
});

function Portal() {
  return (
    <EsevaPortal
      record={<p>record part</p>}
      withdrawals={<p>withdrawals part</p>}
      vpf={<p>vpf part</p>}
      statement={<p>statement part</p>}
      tax={<p>tax part</p>}
      form16={<p>form16 part</p>}
      leave={<p>leave part</p>}
      payslips={(onOpen) => (
        <button type="button" onClick={() => onOpen(YYMM)}>
          month part
        </button>
      )}
      hrms={(preview) => <MyHrmsUpdate preview={preview} />}
    />
  );
}

const why = (label: string) => screen.getByRole('button', { name: label }).getAttribute('data-why');
const mineCalls = (calls: Call[]) => calls.filter((c) => /\/mine|\/eseva\/me/.test(c.url));

async function openSection(label: string) {
  await userEvent.click(screen.getByText(t(label)));
}

async function openSession(eis = '104882', reason = 'Helpdesk ticket') {
  await openSection('e01w.sec.session');
  await userEvent.type(screen.getByLabelText(t('m01.eis')), eis);
  await userEvent.type(screen.getByLabelText(t('common.reason')), reason);
  await userEvent.click(screen.getByRole('button', { name: t('action.e02.preview') }));
}

describe('E-01 member mode', () => {
  it('OD-153: a Form 16 released before issued copies were kept has its PDF disabled with the reason, never fetched', async () => {
    const calls = backend(
      memberRoutes({
        'GET /v1/eseva/overview/mine': () =>
          overview({
            documents: [
              { kind: 'PAY_SLIP', period: YYMM, issued: '2026-10-30T05:00:00Z', state: 'FINAL', id: null },
              { kind: 'FORM_16', period: FY, issued: '2026-06-12T05:00:00Z', state: 'ISSUED_NO_COPY', id: 7 },
            ],
          }),
      }),
    );
    wrap(<Portal />, MEMBER);
    const docs = await screen.findByRole('table', { name: t('e01w.docs.title') });
    const row = (await within(docs).findByText(t('e01w.doc.kind.FORM_16'))).closest('tr') as HTMLElement;
    const off = within(row).getByRole('button', { name: t('e01w.pdf') });
    expect(off).toBeDisabled();
    expect(off.dataset.why).toBe(t('e01w.why.noIssuedCopy'));
    // The status still reads issued.
    expect(within(row).getByText(t('e01w.docState.ISSUED_NO_COPY'))).toBeTruthy();
    // My Form 16 in the toolbar says the same reason and asks nothing of the server.
    expect(screen.getByRole('button', { name: t('e01w.myForm16') }).dataset.guard).toBe(
      t('e01w.why.noIssuedCopy'),
    );
    expect(sent(calls, 'GET', '/v1/tax/form16-certificates/7/part-b')).toHaveLength(0);
    expect(messages.hi['e01w.why.noIssuedCopy']).toBeTruthy();
    expect(messages.hi['e01w.why.noIssuedCopy']).not.toBe(messages.en['e01w.why.noIssuedCopy']);
  });

  it('SRS-17-1, SRS-17-17: the drawn objects read the own overview, in the drawn order', async () => {
    const calls = backend(memberRoutes());
    const { container } = wrap(<Portal />, MEMBER);
    expect(await screen.findByText('R. Sharma')).toBeTruthy();
    expect(
      screen.getByText(`${t('m01.eis')} 104882 · Manager · IDA E-4 · Regional Office Delhi`),
    ).toBeTruthy();
    expect(screen.getByText('RS')).toBeTruthy();
    await screen.findByText(t('e01w.netPay', [MONTH]));
    expect(screen.getByText('₹ 1,80,363')).toBeTruthy();
    expect(screen.getByText('₹ 19,55,520')).toBeTruthy();
    expect(screen.getByText('₹ 4,70,400')).toBeTruthy();
    expect(screen.getByText(t('e01w.tile.loanSub', ['HBA', 'LN-1', 88, 144]))).toBeTruthy();
    expect(screen.getByText('₹ 36,220')).toBeTruthy();
    expect(screen.getByText(`${t('e01t.regime.NEW')} · ${t('e01w.fy', [FY])}`)).toBeTruthy();
    expect(screen.getByText(t('e01w.tile.openSub', [1, 1, 0]))).toBeTruthy();
    const titles = [...container.querySelectorAll('h3')].map((h) => h.textContent);
    expect(titles).toEqual([
      t('e01w.docs.title'),
      t('e01w.payslip', [MONTH]),
      t('e01w.actions'),
      t('e01w.requests'),
      t('e01w.hrms.title'),
      t('i21.upd.mine'),
      t('e01w.labour.title'),
      t('e01w.claim.title'),
      t('e01w.vpf.title'),
    ]);
    const payslip = await screen.findByRole('table', { name: t('e01w.payslipLabel') });
    await within(payslip).findByText('Basic Pay');
    expect(within(payslip).getByText('35,387 · 1,80,363')).toBeTruthy();
    expect(within(payslip).getByText('Income Tax (TDS)')).toBeTruthy();
    // VPF card: the pending change as the member asked it, with its status.
    expect(screen.getByText('VPF 7500')).toBeTruthy();
    expect(screen.getByText(t('e01w.vpf.perMonth', ['5,000']))).toBeTruthy();
    expect(sent(calls, 'GET', '/v1/payslips/mine/')).toHaveLength(1);
  });

  it('SRS-17-1, OD-144, SRS-G-25, G19, G21, G20: the PDFs download the own documents, the others say why; loan and claim keep the reason', async () => {
    const calls = backend(memberRoutes());
    const files = captureDownloads();
    wrap(<Portal />, MEMBER);
    await screen.findByRole('table', { name: t('e01w.docs.title') });
    await screen.findByText(t('e01w.doc.kind.CPF_SLIP'));
    const before = calls.length;
    // My Payslip: the latest locked month; My Form 16: the latest released certificate (Part A merged first).
    await userEvent.click(screen.getByRole('button', { name: t('e01w.myPayslip') }));
    await userEvent.click(screen.getByRole('button', { name: t('e01w.myForm16') }));
    await waitFor(() => expect(files.names).toEqual([`payslip-${YYMM}.pdf`, `form16-${FY}.pdf`]));
    expect(sent(calls, 'GET', `/v1/payslips/mine/${YYMM}/pdf`)).toHaveLength(1);
    expect(sent(calls, 'GET', '/v1/tax/form16-certificates/7/part-b')).toHaveLength(1);
    const pdf = screen.getAllByRole('button', { name: t('e01w.pdf') });
    // Every row but the month not locked, which has no download (BR-ESEVA-004).
    expect(pdf).toHaveLength(5);
    // The year not closed is drawn disabled; Form 12BA and the CPF Slip say why; the others download.
    const notDue = pdf.filter((b) => b.dataset.why === t('e01w.why.notDue'));
    expect(notDue).toHaveLength(1);
    expect(notDue[0]).toBeDisabled();
    const notProduced = pdf.filter((b) => b.dataset.guard === t('e01w.why.pdf'));
    expect(notProduced).toHaveLength(2);
    await userEvent.click(notProduced[0] as HTMLElement);
    expect((await screen.findAllByText(t('e01w.why.pdf'))).length).toBeGreaterThan(0);
    const ready = pdf.filter((b) => !b.dataset.guard && !b.dataset.why);
    expect(ready).toHaveLength(2);
    await userEvent.click(ready[0] as HTMLElement);
    await waitFor(() => expect(files.names).toHaveLength(3));
    files.restore();
    const reissue = screen.getByRole('button', { name: t('e01w.reissue') });
    expect(reissue.dataset.guard).toBe(t('e01w.why.reissue'));
    await userEvent.click(reissue);
    expect(await screen.findByText(t('e01w.why.reissue'))).toBeTruthy();
    expect(calls.slice(before).filter((c) => c.method !== 'GET')).toEqual([]);
    expect(screen.queryByRole('button', { name: t('e01w.claim.submit') })).toBeNull();
    await openSection('e01w.sec.claim');
    const claim = await screen.findByRole('button', { name: t('e01w.claim.submit') });
    expect(claim).toBeDisabled();
    expect(claim.dataset.why).toBe(t('e01w.why.labourClaim'));
    const loan = screen.getByRole('button', { name: t('e01w.act.loan') });
    expect(loan).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(loan);
    expect(await screen.findByText(t('e01w.why.loan'))).toBeTruthy();
  });

  it('every undrawn function is closed and mounted only when asked for, by its section or its tile', async () => {
    const calls = backend(memberRoutes());
    wrap(<Portal />, MEMBER);
    await screen.findByText('R. Sharma');
    for (const part of ['record', 'withdrawals', 'vpf', 'statement', 'tax', 'form16', 'leave']) {
      expect(screen.queryByText(`${part} part`)).toBeNull();
    }
    expect(sent(calls, 'GET', '/v1/eseva/notifications/mine')).toHaveLength(0);
    await userEvent.click(screen.getByText(t('e01w.sec.record')));
    expect(await screen.findByText('record part')).toBeTruthy();
    await userEvent.click(screen.getByText(t('e01w.sec.record')));
    await waitFor(() => expect(screen.queryByText('record part')).toBeNull());
    await userEvent.click(screen.getByText(t('e01w.sec.leave')));
    expect(await screen.findByText('leave part')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: t('e01w.submitDeclaration') }));
    expect(await screen.findByText('tax part')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('e01w.act.payslip') }));
    expect(await screen.findByText('form16 part')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'month part' })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('e01w.act.cpf') }));
    expect(await screen.findByText('withdrawals part')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('e01w.act.statement') }));
    expect(await screen.findByText('statement part')).toBeTruthy();
    const status = screen.getByRole('button', { name: t('e01w.act.status') });
    status.focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByText(t('e01.noNotices'))).toBeTruthy();
    expect(sent(calls, 'GET', '/v1/eseva/notifications/mine').length).toBeGreaterThan(0);
  });

  it('the remaining drawn actions: regime, the HRMS card, Previous months and History', async () => {
    backend(memberRoutes());
    wrap(<Portal />, MEMBER);
    await screen.findByText('R. Sharma');
    const hrms = screen.getByRole('button', { name: t('e01w.act.hrms') });
    hrms.focus();
    await userEvent.keyboard(' ');
    await userEvent.click(screen.getByRole('button', { name: t('e01w.act.regime') }));
    expect(await screen.findByText('tax part')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('e01w.act.tax') }));
    await userEvent.click(screen.getByRole('button', { name: t('e01w.previousMonths') }));
    expect(await screen.findByRole('button', { name: 'month part' })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('e01w.history') }));
    expect(await screen.findByText(t('e01.noNotices'))).toBeTruthy();
  });

  it('SRS-17-10, SRS-18-13, OD-144: the Pay Slip document is masked, prints, and downloads its PDF', async () => {
    const calls = backend(memberRoutes());
    const files = captureDownloads();
    const print = vi.fn();
    vi.stubGlobal('print', print);
    wrap(<Portal />, MEMBER);
    await screen.findByText('R. Sharma');
    await userEvent.click(screen.getByRole('button', { name: t('e01w.previousMonths') }));
    await userEvent.click(await screen.findByRole('button', { name: 'month part' }));
    const paper = await screen.findByRole('dialog', { name: t('e01w.doc.title', [MONTH, 'R. Sharma']) });
    expect(within(paper).getByText('******234K')).toBeTruthy();
    expect(within(paper).getByText('********4412')).toBeTruthy();
    expect(within(paper).getByText('CPF/104882')).toBeTruthy();
    expect(paper).toHaveTextContent(
      t('e01w.doc.words', ['One Lakh Eighty Thousand Three Hundred and Sixty Three']),
    );
    expect(within(paper).getByText('36,220')).toBeTruthy();
    await userEvent.click(within(paper).getByRole('button', { name: t('e01w.doc.download') }));
    await waitFor(() => expect(files.names).toEqual([`payslip-${YYMM}.pdf`]));
    expect(sent(calls, 'GET', `/v1/payslips/mine/${YYMM}/pdf`)).toHaveLength(1);
    files.restore();
    await userEvent.click(within(paper).getByRole('button', { name: t('e01w.doc.print') }));
    expect(print).toHaveBeenCalledOnce();
    await userEvent.click(within(paper).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('the Pay Slip document: Escape closes it; a provisional slip says so; PRAN shows; TDS not configured', async () => {
    backend(
      memberRoutes({
        'GET /v1/eseva/overview/mine': () =>
          overview({ tds: { finYear: FY, amount: null, regime: null }, loan: null }),
        'GET /v1/payslips/mine/': () => slip({ provisional: true, pran: '110000000001', office: null }),
      }),
    );
    wrap(<Portal />, MEMBER);
    await screen.findByText('R. Sharma');
    await userEvent.click(screen.getByRole('button', { name: t('e01w.previousMonths') }));
    await userEvent.click(await screen.findByRole('button', { name: 'month part' }));
    const paper = await screen.findByRole('dialog');
    expect(paper).toHaveTextContent(t('e01w.doc.provisional'));
    expect(within(paper).getByText('110000000001')).toBeTruthy();
    expect(within(paper).getByText(t('e01w.notConfigured'))).toBeTruthy();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('SRS-17-14, G22: Field and Effective date are required; the raised request and its file go on', async () => {
    const calls = backend(
      memberRoutes({
        'GET /v1/hrms-update-requests/mine': (url) =>
          paged(
            [
              {
                id: 3,
                requestNo: 'HUR-2026-0003',
                fieldLabel: 'Basic Pay',
                channel: 'ESEVA',
                raisedAt: '2026-10-21T05:00:00Z',
                hrmsReference: 'HRMS-77',
                effectiveDate: '2026-10-01',
                status: 'TASK_OPENED',
                messageCode: 'HRMS_NOT_YET_UPDATED',
                taskNo: 'HCA-1',
                taskStatus: 'APPROVED',
              },
              {
                id: 4,
                requestNo: 'HUR-2026-0004',
                fieldLabel: 'Basic Pay',
                channel: 'ESEVA',
                raisedAt: '2026-10-21T05:00:00Z',
                hrmsReference: null,
                effectiveDate: '2026-10-01',
                status: 'NOT_YET_IN_HRMS',
                messageCode: null,
                taskNo: null,
              },
              {
                id: 5,
                requestNo: 'HUR-2026-0005',
                fieldLabel: 'Basic Pay',
                channel: 'ESEVA',
                raisedAt: '2026-10-21T05:00:00Z',
                hrmsReference: null,
                effectiveDate: '2026-10-01',
                status: 'FETCH_FAILED',
                messageCode: null,
                taskNo: null,
              },
            ],
            3,
            url,
          ),
        'GET /v1/hrms-update-requests/mine/': () => [],
        'POST /v1/hrms-update-requests/mine': () => ({
          status: 201,
          body: {
            id: 9,
            requestNo: 'HUR-2026-0009',
            status: 'TASK_OPENED',
            effectiveDate: '2026-10-01',
          },
        }),
      }),
    );
    wrap(<Portal />, MEMBER);
    const table = await screen.findByRole('table', { name: t('i21.upd.list') });
    expect(await within(table).findByText('HUR-2026-0003')).toBeTruthy();
    expect(within(table).getByText(t('i21.upd.status.APPROVED_APPLIED'))).toHaveClass('p-ok');
    expect(within(table).getByText(t('i21.upd.status.NOT_YET_IN_HRMS'))).toHaveClass('p-gray');
    expect(within(table).getByText(t('i21.upd.status.FETCH_FAILED'))).toHaveClass('p-bad');
    expect(within(table).getByText(t('i21.upd.msg.HRMS_NOT_YET_UPDATED'))).toBeTruthy();
    expect(within(table).getByText(t('i21.upd.task', ['HCA-1']))).toBeTruthy();
    expect([...table.querySelectorAll('th')].map((th) => th.firstChild?.textContent)).toEqual([
      t('i21.upd.request'),
      t('i21.field'),
      t('e01w.hrms.raised'),
      t('e01w.hrms.outcome'),
    ]);

    const form = screen.getByRole('form', { name: t('i21.upd.raise') });
    const raise = within(form).getByRole('button', { name: t('e01w.hrms.raise') });
    await userEvent.click(raise);
    expect(await within(form).findByRole('alert')).toHaveTextContent(t('e01w.hrms.incomplete'));
    expect(sent(calls, 'POST', '/v1/hrms-update-requests/mine')).toHaveLength(0);
    expect(within(form).getByLabelText(new RegExp(t('i21.field')))).toBeRequired();
    expect(within(form).getByLabelText(new RegExp(t('i21.effective')))).toBeRequired();
    await screen.findByRole('option', { name: 'Basic Pay' });
    await userEvent.selectOptions(within(form).getByLabelText(new RegExp(t('i21.field'))), 'BASIC_PAY');
    await userEvent.type(within(form).getByLabelText(new RegExp(t('i21.effective'))), '2026-10-01');
    await userEvent.type(within(form).getByLabelText(t('i21.upd.reference')), ' HRMS-9 ');
    await userEvent.upload(
      within(form).getByLabelText(t('e01w.hrms.orderCopy')),
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(raise);
    expect(
      await screen.findByText(t('i21.upd.raised', ['HUR-2026-0009', t('i21.upd.status.TASK_OPENED')])),
    ).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/hrms-update-requests/mine')).toEqual([
      { fieldCode: 'BASIC_PAY', effectiveDate: '2026-10-01', hrmsReference: 'HRMS-9' },
    ]);
    // The order details SRS-G-30 asks for are taken on the card that opens on the new request.
    expect(
      await screen.findByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0009']) }),
    ).toBeTruthy();
    // Not drawn on the rows: the order copies of the requests stand in a closed section.
    expect(
      within(screen.getByRole('table', { name: t('i21.upd.list') })).queryByRole('button', {
        name: t('i21.upd.order.of', ['HUR-2026-0003']),
      }),
    ).toBeNull();
    await openSection('e01w.hrms.orders');
    await userEvent.click(
      await screen.findByRole('button', { name: t('i21.upd.order.of', ['HUR-2026-0003']) }),
    );
    expect(
      await screen.findByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0003']) }),
    ).toBeTruthy();
  });

  it('SRS-17-14: a refused raise is shown and the form keeps what was entered; no file opens no card', async () => {
    backend(
      memberRoutes({
        'POST /v1/hrms-update-requests/mine': () => problem(409, 'COM-409-CONFLICT'),
      }),
    );
    wrap(<Portal />, MEMBER);
    const form = await screen.findByRole('form', { name: t('i21.upd.raise') });
    await screen.findByRole('option', { name: 'Basic Pay' });
    await userEvent.selectOptions(within(form).getByLabelText(new RegExp(t('i21.field'))), 'BASIC_PAY');
    await userEvent.type(within(form).getByLabelText(new RegExp(t('i21.effective'))), '2026-10-01');
    await userEvent.click(within(form).getByRole('button', { name: t('e01w.hrms.raise') }));
    expect(await within(form).findByRole('alert')).toBeTruthy();
    expect(within(form).getByLabelText(new RegExp(t('i21.field')))).toHaveValue('BASIC_PAY');
    expect(screen.queryByRole('region')).toBeNull();
  });

  it('SRS-17-6: without E-01 CREATE the raise is drawn disabled with its reason', async () => {
    backend(memberRoutes());
    wrap(<Portal />, ['E-01:VIEW', 'E-01:SELF']);
    const raise = await screen.findByRole('button', { name: t('e01w.hrms.raise') });
    expect(raise).toBeDisabled();
    expect(raise.dataset.why).toBe(t('e01w.why.hrmsRaise'));
  });

  it('SRS-SEC-9, WF-1510: a waiting privacy notice stands open at the top of the page, in no closed section, until acknowledged', async () => {
    backend(
      memberRoutes({
        'GET /v1/eseva/privacy-notice/mine': () => ({ version: '1', acknowledged: false }),
      }),
    );
    const { container } = wrap(<Portal />, MEMBER);
    const text = await screen.findByText(t('eseva.privacy.text'));
    // Seen without opening anything: the notice is in no <details>, and the closed-section line is gone.
    expect(text.closest('details')).toBeNull();
    expect(screen.queryByText(t('e01w.sec.privacy'))).toBeNull();
    expect(container.querySelector('[data-testid="e01-privacy"]')).not.toBeNull();
    const titles = [...container.querySelectorAll('h3')].map((h) => h.textContent);
    expect(titles[0]).toBe(t('eseva.privacy.title'));
    expect(screen.getByRole('button', { name: t('action.eseva.acknowledge') })).toBeEnabled();
  });
});

describe('E-01 data states', () => {
  it('a TDS with no configured pay code says so, never 0; no loan, no regime, more loans than one', async () => {
    backend(
      memberRoutes({
        'GET /v1/eseva/overview/mine': () =>
          overview({ tds: { finYear: FY, amount: null, regime: null }, loan: null, cpf: null }),
      }),
    );
    wrap(<Portal />, MEMBER);
    expect(await screen.findByText(t('e01w.notConfigured'))).toBeTruthy();
    expect(screen.queryByText('₹ 0')).toBeNull();
    expect(screen.getByText(`${t('e01w.tile.regimeNone')} · ${t('e01w.fy', [FY])}`)).toBeTruthy();
    expect(screen.getByText(t('e01w.tile.loanNone'))).toBeTruthy();
  });

  it('a TDS of zero is a real zero; a second open loan is counted; an empty VPF shows a dash', async () => {
    backend(
      memberRoutes({
        'GET /v1/eseva/overview/mine': () =>
          overview({
            tds: { finYear: FY, amount: 0, regime: 'OLD' },
            loan: { outstanding: 10, number: 'LN-1', type: 'HBA', paid: 1, total: 2, open: 3 },
            cpf: { finYear: FY, balance: 10, vpfMonthly: null },
          }),
      }),
    );
    wrap(<Portal />, MEMBER);
    expect(await screen.findByText('₹ 0')).toBeTruthy();
    expect(screen.getByText(new RegExp(t('e01w.tile.loanMore', [2]).replace(/[()+]/g, '\\$&')))).toBeTruthy();
    expect(screen.queryByText(t('e01w.vpf.perMonth', ['0']))).toBeNull();
  });

  it('BR-ESEVA-004: before the first lock the band and the table say no locked period, and no payslip is asked', async () => {
    const calls = backend(
      memberRoutes({
        'GET /v1/eseva/overview/mine': () => overview({ latest: null, myRequests: [], documents: [] }),
      }),
    );
    wrap(<Portal />, MEMBER);
    expect(await screen.findByText(t('e01w.netPayNone'))).toBeTruthy();
    expect(screen.getByText(t('e01w.payslipNone'))).toBeTruthy();
    expect(sent(calls, 'GET', '/v1/payslips/mine/')).toHaveLength(0);
  });

  it('SRS-17-7, BR-ESEVA-005: a labour slip shows the wage set and names the member on the labour card', async () => {
    backend(
      memberRoutes({
        'GET /v1/payslips/mine/': () =>
          slip({
            eis: 'DL-20180',
            name: 'P. Chand',
            layoutVariant: 'LABOUR',
            earnings: [{ payCode: 201, name: 'MGW', group: 'WAGE', amount: 26600 }],
          }),
      }),
    );
    const { container } = wrap(<Portal />, MEMBER);
    expect(await screen.findByText(t('e01w.labourLayout'))).toBeTruthy();
    expect(container.querySelector('[data-layout="LABOUR"]')).not.toBeNull();
    expect(screen.getByText('MGW')).toBeTruthy();
    expect(screen.getByText(t('e01w.labour.titleOf', ['DL-20180', 'P. Chand']))).toBeTruthy();
  });

  it('a staff slip has no labour line; the drawn labour card still shows (G24)', async () => {
    backend(memberRoutes());
    const { container } = wrap(<Portal />, MEMBER);
    await screen.findByText('Basic Pay');
    expect(container.querySelector('[data-layout="LABOUR"]')).toBeNull();
    expect(screen.getByText(t('e01w.labour.title'))).toBeTruthy();
    expect(screen.getByText(t('e01w.claim.routesValue'))).toBeTruthy();
  });

  it('wrong path: an overview that fails is shown, the drawn objects stay', async () => {
    backend(memberRoutes({ 'GET /v1/eseva/overview/mine': () => problem(503, 'COM-503-UNAVAILABLE') }));
    wrap(<Portal />, MEMBER);
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
    expect(screen.getByText(t('e01w.docs.title'))).toBeTruthy();
    expect(screen.getByText(t('e01w.actions'))).toBeTruthy();
  });
});

describe('E-01 staff support preview', () => {
  it('SRS-17-6, OD-130: with no session the drawn objects are empty and nothing is read', async () => {
    const calls = backend({}, {});
    wrap(<Portal />, STAFF);
    expect(await screen.findByText(t('e01w.docs.title'))).toBeTruthy();
    expect(screen.getByText(t('e01w.netPayNone'))).toBeTruthy();
    expect(screen.getByText(t('e01w.payslipNone'))).toBeTruthy();
    expect(calls.filter((c) => c.url.includes('/v1/'))).toEqual([]);
  });

  it('SRS-17-6: every submit is disabled with its drawn reason', async () => {
    backend(staffRoutes());
    wrap(<Portal />, STAFF);
    await openSession();
    await screen.findByText('Basic Pay');
    expect(why(t('e01w.submitDeclaration'))).toBe(t('e01w.why.declaration'));
    expect(screen.getByRole('button', { name: t('e01w.submitDeclaration') })).toBeDisabled();
    expect(why(t('e01w.hrms.raise'))).toBe(t('e01w.why.hrmsRaise'));
    expect(screen.getByRole('button', { name: t('e01w.hrms.raise') })).toBeDisabled();
    const form = screen.getByRole('form', { name: t('i21.upd.raise') });
    expect(within(form).getByLabelText(new RegExp(t('i21.field')))).toBeDisabled();
    expect(within(form).getByLabelText(t('i21.upd.reference'))).toBeDisabled();
    expect(within(form).getByLabelText(new RegExp(t('i21.effective')))).toBeDisabled();
    expect(within(form).getByLabelText(t('e01w.hrms.orderCopy'))).toBeDisabled();
    const tiles = ['payslip', 'tax', 'regime', 'hrms', 'loan', 'cpf', 'statement', 'status'].map((k) =>
      screen.getByRole('button', { name: t(`e01w.act.${k}`) }),
    );
    tiles.forEach((q) => expect(q.dataset.why).toBe(t('e01w.why.preview')));
    await userEvent.click(tiles[1] as HTMLElement);
    expect(await screen.findByText(t('e01w.why.preview'))).toBeTruthy();
  });

  it('SRS-17-6, LIB-1: with no session the drawn row keeps ⇩ PDF, reissue stays disabled, no /mine read', async () => {
    const calls = backend(staffRoutes());
    wrap(<Portal />, STAFF);
    const pdf = await screen.findByRole('button', { name: t('e01w.pdf') });
    expect(pdf.dataset.guard).toBe(t('e01w.why.pdf'));
    await userEvent.click(pdf);
    expect(await screen.findByText(t('e01w.why.pdf'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('e01w.reissue') })).toBeDisabled();
    expect(why(t('e01w.reissue'))).toBe(t('e01w.why.reissue'));
    expect(mineCalls(calls)).toEqual([]);
  });

  it('SRS-17-8: a session needs the member and a reason, and then shows that member; no /mine read', async () => {
    const calls = backend(staffRoutes());
    wrap(<Portal />, STAFF);
    expect(screen.queryByRole('button', { name: t('action.e02.preview') })).toBeNull();
    await openSection('e01w.sec.session');
    const open = await screen.findByRole('button', { name: t('action.e02.preview') });
    expect(open).toBeDisabled();
    expect(open.dataset.why).toBe(t('e02.why.preview'));
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '104882');
    expect(open).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Helpdesk ticket');
    expect(open).toBeEnabled();
    await userEvent.click(open);
    expect(await screen.findByRole('status')).toHaveTextContent(t('e02.readOnly', ['41']));
    expect(await screen.findByText('₹ 1,80,363')).toBeTruthy();
    expect(screen.getByText('R. Sharma')).toBeTruthy();
    expect(await screen.findByText(new RegExp(`${t('m01.eis')} 104882 · Manager · IDA`))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/eseva/support-previews')[0]).toEqual({
      eis: '104882',
      reason: 'Helpdesk ticket',
    });
    expect(calls.some((c) => c.url.includes('/v1/payslips/previews/41/'))).toBe(true);
    expect(mineCalls(calls)).toEqual([]);
  });

  it('SRS-17-8: Close stamps the session and empties the drawn objects', async () => {
    const calls = backend(staffRoutes());
    wrap(<Portal />, STAFF);
    await openSession();
    await screen.findByText('₹ 1,80,363');
    await userEvent.click(screen.getByRole('button', { name: t('action.e02.closePreview') }));
    expect(await screen.findByText(t('e01w.netPayNone'))).toBeTruthy();
    expect(screen.queryByText('R. Sharma')).toBeNull();
    expect(calls.some((c) => c.method === 'POST' && c.url.includes('/support-previews/41/closures'))).toBe(
      true,
    );
    expect(screen.getByRole('button', { name: t('action.e02.preview') })).toBeTruthy();
  });

  it('wrong paths: a refused member stays out, a failed close keeps the session', async () => {
    backend(staffRoutes({ 'POST /v1/eseva/support-previews': () => problem(404, 'COM-404-NOT-FOUND') }));
    wrap(<Portal />, STAFF);
    await openSession('999999');
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('wrong path: a close that fails is shown and the session stays open', async () => {
    backend(
      staffRoutes({
        'POST /v1/eseva/support-previews/41/closures': () => problem(503, 'COM-503-UNAVAILABLE'),
      }),
    );
    wrap(<Portal />, STAFF);
    await openSession();
    await screen.findByText('₹ 1,80,363');
    await userEvent.click(screen.getByRole('button', { name: t('action.e02.closePreview') }));
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
    expect(screen.getByRole('status')).toHaveTextContent(t('e02.readOnly', ['41']));
  });

  it('SRS-17-10, G04: the closed sections hold the masked member, the months, the submissions and E-02', async () => {
    backend(staffRoutes());
    wrap(<Portal />, STAFF);
    await openSession();
    await screen.findByText('₹ 1,80,363');
    await userEvent.click(screen.getByText(t('e01w.sec.member')));
    expect(await screen.findByText('********1234')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('e01w.history') }));
    expect(await screen.findByRole('list', { name: t('e02.submissions') })).toHaveTextContent(
      t('e02.type.VPF_SUBSCRIPTION'),
    );
    await userEvent.click(screen.getByText(t('e01w.sec.support')));
    expect(await screen.findByRole('link', { name: t('e01w.support.open') })).toHaveAttribute(
      'href',
      expect.stringContaining('s=E-02'),
    );
    await userEvent.click(screen.getByRole('button', { name: t('e01w.previousMonths') }));
    const months = await screen.findByRole('table', { name: t('e01p.months') });
    await userEvent.click(within(months).getByRole('button', { name: MONTH }));
    const paper = await screen.findByRole('dialog');
    expect(within(paper).getByText('******234K')).toBeTruthy();
    await userEvent.click(within(paper).getByRole('button', { name: t('common.close') }));
  });

  it('with no session the closed sections are empty; a user without E-02 gets no link (G04)', async () => {
    backend(staffRoutes());
    wrap(<Portal />, ['E-01:VIEW']);
    expect(screen.queryByText(t('e01w.sec.support'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('e01w.history') }));
    expect(await screen.findByText(t('e02.none'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('e01w.previousMonths') }));
    expect(await screen.findByText(t('e02.noPayslips'))).toBeTruthy();
    await userEvent.click(screen.getByText(t('e01w.sec.member')));
    expect(await screen.findByText(t('m01.pan'))).toBeTruthy();
  });

  it('the HRMS table of a preview shows the submissions, the full request needs no /mine read', async () => {
    backend(staffRoutes());
    wrap(<Portal />, STAFF);
    await openSession();
    const table = await screen.findByRole('table', { name: t('i21.upd.list') });
    expect(await within(table).findByText('HUR-1')).toBeTruthy();
    expect(within(table).getByText(t('e02.status.ROUTED'))).toBeTruthy();
  });

  it('OD-130: the screen as registered makes no /mine call for a staff user', async () => {
    const calls = backend(staffRoutes());
    const Screen = SCREENS['E-01']?.component;
    expect(Screen).toBeDefined();
    if (!Screen) return;
    wrap(<Screen />, STAFF);
    await openSession();
    await screen.findByText('₹ 1,80,363');
    expect(mineCalls(calls)).toEqual([]);
  });

  it('with no profile yet nothing is drawn and nothing is read', () => {
    const calls = backend({}, null);
    const { container } = wrap(<Portal />, me([], { permissions: undefined as unknown as string[] }));
    expect(container.textContent ?? '').not.toContain(t('e01w.why.pdf'));
    expect(calls.filter((c) => c.url.includes('/mine'))).toEqual([]);
  });
});
