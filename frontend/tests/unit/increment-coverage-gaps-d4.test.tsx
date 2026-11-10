import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IncrementScreen, PayScalesScreen } from '@/modules/increment';
import { businessToday } from '@/shared/time';
import { amount, date } from '@/shared/ui';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * M-03 Pay Scales and I-05 Increment & Pay Fixation as Wireframes v6.1 draws them (OD-121; SRS M-03, SRS-13-1 …
 * 13-27, SRS-20-29): a scale pending approval or inactive, a band with no value set (a dash, never a guessed value,
 * R-RULE), the drawn entry carried into the register, the approver's ✔ (W-01 only for a version waiting), and on I-05
 * the HRMS task row, the retrospective span, the Maker sent to I-21 and the view of a barred line.
 */
afterEach(() => vi.unstubAllGlobals());
beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

const SCALE = {
  bandId: 1,
  code: 'E-4',
  name: 'Executive 4',
  scaleType: 'IDA',
  min: 60000,
  max: 90000,
  stagnationAmount: 2100,
  effectiveFrom: '2026-04-01',
  stages: 12,
  pending: true,
  active: false,
  incrementRuleKey: 'INCR_E4',
};
const BARE = {
  ...SCALE,
  bandId: 2,
  code: 'E-5',
  name: 'Executive 5',
  scaleType: null,
  min: null,
  max: null,
  stagnationAmount: null,
  effectiveFrom: null,
  pending: false,
  active: false,
  incrementRuleKey: null,
};

function m03(rows: unknown[] = [SCALE, BARE], mapping: unknown[] = []) {
  return backend({
    'GET /v1/pay-scales/mapping': () => paged(mapping),
    'GET /v1/pay-scales/E-4/orders': () => [],
    'GET /v1/pay-scales/E-5/orders': () => [],
    'GET /v1/pay-scales/wage-revisions': () => [],
    'GET /v1/pay-scales': () => paged(rows),
    'GET /v1/masters/circulars/current': () => [],
    'GET /v1/reference/lists': () =>
      referenceLists({
        PAY_SCALE_TYPE: [
          ['IDA', 'IDA'],
          ['CDA', 'CDA'],
        ],
      }),
    'GET /v1/document-types': () => [],
  });
}

describe('M-03 rows, entry and approval', () => {
  it('SRS_13_23_aPendingScaleAndAnInactiveBandWithNoValuesAreToldAsSuch', async () => {
    m03(undefined, [
      {
        gradeCode: null,
        gradeName: null,
        designationCode: 'TRN',
        designationName: 'Trainee',
        scaleCode: null,
        headcount: 3,
      },
    ]);
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const table = await screen.findByRole('table', { name: t('m03w.table') });
    const pending = (await within(table).findByText('E-4')).closest('tr') as HTMLElement;
    expect(within(pending).getByText(t('m03w.status.pending'))).toHaveClass('p-warn');
    const bare = within(table).getByText('E-5').closest('tr') as HTMLElement;
    expect(within(bare).getByText(t('m03w.status.inactive'))).toHaveClass('p-info');
    // pattern, min, max, increment rule, stagnation and from: six dashes, no assumed value
    expect(within(bare).getAllByText('—')).toHaveLength(6);
    // a designation without grade or scale is unmapped
    const mapping = screen.getByRole('table', { name: t('m03w.mapping') });
    const trainee = (await within(mapping).findByText('Trainee')).closest('tr') as HTMLElement;
    expect(within(trainee).getAllByText('—')).toHaveLength(2);
    expect(within(trainee).getByText(t('m03w.map.unmapped'))).toHaveClass('p-bad');
  });

  it('SRS_13_23_theViewOfABandWithNoValuesReadsDashesAndCloses', async () => {
    m03();
    const user = userEvent.setup();
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await user.click(await screen.findByRole('button', { name: `${t('common.view')} E-5` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('E-5 - Executive 5')).toBeInTheDocument();
    expect(within(dialog).getAllByText('—')).toHaveLength(6);
    expect(within(dialog).getByText(t('m03w.status.inactive'))).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(screen.getByRole('button', { name: `${t('common.view')} E-4` }));
    const pending = await screen.findByRole('dialog');
    expect(within(pending).getByText(amount(60000))).toBeInTheDocument();
    expect(within(pending).getByText(date('2026-04-01'))).toBeInTheDocument();
    expect(within(pending).getByText(t('m03w.status.pending'))).toBeInTheDocument();
  });

  it('SRS_13_23_theDrawnEntryCarriesEveryFieldIntoTheRegisterForm', async () => {
    m03();
    const user = userEvent.setup();
    wrap(<PayScalesScreen />, me(['M-03:VIEW', 'M-03:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }));
    await user.click(await screen.findByRole('button', { name: t('m03w.newVersion') }));
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
    await screen.findByRole('option', { name: 'CDA' });
    await user.type(document.getElementById('ps_scale') as HTMLElement, 'E-9');
    await user.selectOptions(document.getElementById('ps_pattern') as HTMLElement, 'CDA');
    await user.type(document.getElementById('ps_min') as HTMLElement, '70000');
    await user.type(document.getElementById('ps_max') as HTMLElement, '99000');
    await user.type(document.getElementById('ps_stag') as HTMLElement, '2500');
    await user.type(document.getElementById('ps_eff') as HTMLElement, '2026-04-01');
    // the increment is the band's rule key: disabled with its reason
    expect(document.getElementById('ps_incr')).toHaveAttribute('data-why', t('m03w.why.increment'));
    await user.click(screen.getByRole('button', { name: t('m03w.save') }));
    await waitFor(() => expect(screen.getByTestId('m03-register')).toHaveAttribute('open'));
    expect(screen.getAllByDisplayValue('E-9')).toHaveLength(2);
    expect(screen.getAllByDisplayValue('CDA')).toHaveLength(2);
  });

  it('SRS_13_23_theHistoryVersionsAndEditOpenTheRegister', async () => {
    m03();
    const user = userEvent.setup();
    const view = wrap(
      <PayScalesScreen />,
      me(['M-03:VIEW', 'M-03:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }),
    );
    await user.click(await screen.findByRole('button', { name: `${t('common.edit')} E-4` }));
    await waitFor(() => expect(screen.getByTestId('m03-register')).toHaveAttribute('open'));
    view.unmount();
    const second = wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await user.click(await screen.findByRole('button', { name: t('wf.historyAct') }));
    await waitFor(() => expect(screen.getByTestId('m03-register')).toHaveAttribute('open'));
    second.unmount();
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await user.click(await screen.findByRole('button', { name: t('m03w.versions') }));
    await waitFor(() => expect(screen.getByTestId('m03-register')).toHaveAttribute('open'));
    // the Maker's row Edit is off with the reason
    expect(screen.getByRole('button', { name: `${t('common.edit')} E-4` })).toHaveAttribute(
      'data-why',
      t('m03w.why.edit'),
    );
  });

  it('SRS_13_23_theApproverGoesToW01ForAWaitingVersionAndIsToldNothingWaitsOtherwise', async () => {
    m03();
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<PayScalesScreen />, me(['M-03:VIEW', 'M-03:APPROVE'], { roles: ['RULE_APPROVER'] }));
    const table = await screen.findByRole('table', { name: t('m03w.table') });
    const bare = (await within(table).findByText('E-5')).closest('tr') as HTMLElement;
    await user.click(within(bare).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    const pending = within(table).getByText('E-4').closest('tr') as HTMLElement;
    await user.click(within(pending).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS_13_23_anEmptyListLeavesTheOrderCardWithoutAScale', async () => {
    const calls = m03([]);
    wrap(<PayScalesScreen />, me(['M-03:VIEW', 'M-03:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }));
    expect(await screen.findByText(t('m03.none'))).toBeInTheDocument();
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    expect(within(card).getByRole('status')).toHaveTextContent(t('wf.ord.viewOnly'));
    expect(calls.filter((c) => c.url.includes('/orders'))).toHaveLength(0);
  });
});

const month = () => businessToday().slice(0, 7);

const TASK = {
  kind: 'HRMS_TASK',
  key: 'T1',
  employeeId: 3,
  eis: '881305',
  employee: 'Ritu Saini',
  scale: null,
  type: null,
  presentBasic: null,
  incrementPct: 3,
  revisedBasic: null,
  effective: `${month()}-01`,
  atMaximum: true,
  arrearEvent: null,
  arrearAmount: null,
  status: 'HRMS_TASK',
  flags: [],
  barredBy: [],
  orderReference: null,
  approvalId: null,
};
const OLD_CASE = {
  kind: 'CASE',
  key: 'K2',
  employeeId: 4,
  eis: '881306',
  employee: 'Ajay Rana',
  scale: 'E-3',
  type: null,
  presentBasic: 50000,
  incrementPct: null,
  revisedBasic: 51500,
  effective: '2020-01-01',
  atMaximum: false,
  arrearEvent: 130,
  arrearAmount: null,
  status: 'ODD_STATE',
  flags: ['DUPLICATE'],
  barredBy: ['SUSPENSION'],
  orderReference: null,
  approvalId: 9,
};
const STORED = {
  id: 5,
  fileName: 'case-order.pdf',
  documentTypeCode: 'SANCTION_ORDER',
  documentTypeName: 'Sanction Order',
  referenceNumber: 'DL/EST/9',
  uploadedBy: 'hr',
  uploadedAt: '2026-10-01T06:00:00Z',
};

function i05(rows: unknown[]) {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['SANCTION_ORDER', 'Sanction Order']] }),
    'GET /v1/increments/counts': () => ({
      due: 0,
      annual: 0,
      stagnation: 0,
      fixations: 0,
      retrospective: 0,
      latestArrearEvent: null,
      atMaximum: 0,
      duplicates: 0,
    }),
    'GET /v1/increments/worklist': () => paged(rows),
    'GET /v1/increments/due': () => paged([]),
    'GET /v1/pay-fixation-cases/9/orders': () => [STORED],
    'GET /v1/pay-fixation-cases': () => paged([]),
    'GET /v1/document-types': () => [
      { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
    ],
  });
}

describe('I-05 worklist lines and detail', () => {
  it('SRS_13_20_anHrmsTaskLineReadsItsFieldTheMakerEditsItOnI21', async () => {
    i05([TASK]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<IncrementScreen />, me(['I-05:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const table = await screen.findByRole('table', { name: t('i05w.table') });
    const row = (await within(table).findByText(/881305/)).closest('tr') as HTMLElement;
    expect(within(row).getByText(t('i05w.type.task', ['']))).toBeInTheDocument();
    expect(within(row).getByText('3%')).toBeInTheDocument();
    expect(within(row).getByText(t('common.yes'))).toBeInTheDocument();
    // no latest arrear event: the retro tile says so
    expect(screen.getByText(t('i05w.tile.retro.none'))).toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: `${t('common.edit')} 881305` }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=I-21'));
    // a task is not a case: the detail reads the present basic as a dash, no span (effective this month)
    expect(screen.getByText(t('i05w.detail', ['881305 Ritu Saini']))).toBeInTheDocument();
    expect(screen.getByText('— · ₹ —')).toBeInTheDocument();
  });

  it('SRS_13_4_aRetrospectiveCaseShowsTheSpanItsArrearEventAndItsStoredOrder', async () => {
    i05([OLD_CASE]);
    wrap(<IncrementScreen />, me(['I-05:VIEW', 'I-05:SUBMIT'], { roles: ['ESTABLISHMENT_HR'] }));
    await screen.findByText(t('i05w.detail', ['881306 Ajay Rana']));
    expect(screen.getByText(`— · ${date('2020-01-01')}`)).toBeInTheDocument();
    // effective in 2020, before this month: the span to today is drawn with its months
    const today = businessToday();
    const months = (Number(today.slice(0, 4)) - 2020) * 12 + Number(today.slice(5, 7)) - 1;
    expect(
      screen.getByText(t('i05w.d.span.v', [date('2020-01-01'), date(today), months])),
    ).toBeInTheDocument();
    // a case line without its type reads its kind; an unknown status keeps the info pill
    const table = screen.getByRole('table', { name: t('i05w.table') });
    const row = within(table)
      .getByText(/881306/)
      .closest('tr') as HTMLElement;
    expect(within(row).getByText(t('i05w.type.CASE'))).toBeInTheDocument();
    expect(row.querySelector('.pill:not(.p-info)')).toBeNull();
    const link = await screen.findByRole('link', { name: t('wf.ord.viewOrder') });
    expect(link).toHaveAttribute('href', expect.stringContaining('/9/'));
  });

  it('SRS_13_4_theViewListsFlagsAndBarsAndCloses', async () => {
    i05([OLD_CASE, TASK]);
    const user = userEvent.setup();
    wrap(<IncrementScreen />, me(['I-05:VIEW'], { roles: undefined as unknown as string[] }));
    // no role: compute is locked with its reason
    expect(await screen.findByRole('button', { name: t('i05w.compute') })).toHaveAttribute(
      'data-why',
      t('i05w.why.compute'),
    );
    await user.click(await screen.findByRole('button', { name: `${t('common.view')} 881306` }));
    let dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('DUPLICATE')).toBeInTheDocument();
    expect(within(dialog).getByText('SUSPENSION')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(screen.getByRole('button', { name: `${t('common.view')} 881305` }));
    dialog = await screen.findByRole('dialog');
    // no flags, no bars: two dashes besides the scale and the amounts
    expect(within(dialog).getAllByText('—').length).toBeGreaterThanOrEqual(5);
    // the viewed line becomes the detail
    expect(screen.getByText(t('i05w.detail', ['881305 Ritu Saini']))).toBeInTheDocument();
  });

  it('SRS_13_20_theCheckerGoesToW01ForALineAtTheCheckerOnly', async () => {
    i05([{ ...OLD_CASE, status: 'CHECKER' }, TASK]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<IncrementScreen />, me(['I-05:VIEW', 'I-05:APPROVE'], { roles: ['PAYROLL_CHECKER'] }));
    const table = await screen.findByRole('table', { name: t('i05w.table') });
    const task = (await within(table).findByText(/881305/)).closest('tr') as HTMLElement;
    await user.click(within(task).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    const line = within(table)
      .getByText(/881306/)
      .closest('tr') as HTMLElement;
    await user.click(within(line).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS_13_1_theDueListAndTheHistoryOpenTheClosedSection', async () => {
    const calls = i05([]);
    const user = userEvent.setup();
    const view = wrap(<IncrementScreen />, me(['I-05:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    expect(await screen.findByText(t('i05w.none'))).toBeInTheDocument();
    // no line at all: the detail reads dashes and no case order is read
    expect(screen.getByText(t('i05w.detail', ['—']))).toBeInTheDocument();
    expect(sent(calls, 'GET', '/orders')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: t('i05w.dueList') }));
    await waitFor(() => expect(screen.getByTestId('i05-register')).toHaveAttribute('open'));
    view.unmount();
    wrap(<IncrementScreen />, me(['I-05:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await user.click(await screen.findByRole('button', { name: t('i05w.history') }));
    await waitFor(() => expect(screen.getByTestId('i05-register')).toHaveAttribute('open'));
  });
});

describe('M-03 and I-05 order copies of an unlisted type', () => {
  async function fill(card: HTMLElement) {
    const [, orderDate, office, file] = Array.from(card.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    fireEvent.change(orderDate, { target: { value: '2026-01-02' } });
    await userEvent.type(office, 'Headquarters');
    await userEvent.upload(file, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
  }

  it('SRS_G_30_theScaleOrderOfATypeTheDocumentTypesDoNotListIsStoredWithoutReference', async () => {
    const posted: unknown[] = [];
    backend({
      'GET /v1/pay-scales/mapping': () => paged([]),
      'GET /v1/pay-scales/E-4/orders': () => [],
      'POST /v1/pay-scales/E-4/orders': (_url: string, init?: RequestInit) => {
        posted.push(init?.body);
        return { status: 201, body: {} };
      },
      'GET /v1/pay-scales': () => paged([SCALE]),
      'GET /v1/reference/lists': () => referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['GAZETTE', 'Gazette']] }),
      'GET /v1/document-types': () => [
        { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
      ],
    });
    wrap(<PayScalesScreen />, me(['M-03:VIEW', 'M-03:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }));
    await screen.findByRole('option', { name: 'Gazette' });
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    await fill(card);
    await waitFor(() => expect(posted).toHaveLength(1));
    expect((posted[0] as FormData).get('documentType')).toBe('GAZETTE');
    expect((posted[0] as FormData).get('reference')).toBeNull();
  });

  it('SRS_13_18_hrHoldsAnOrderOfAnUnlistedTypeAndTheCaseSectionOpens', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['GAZETTE', 'Gazette']] }),
      'GET /v1/increments/counts': () => ({}),
      'GET /v1/increments/worklist': () => paged([]),
      'GET /v1/increments/due': () => paged([]),
      'GET /v1/pay-fixation-cases': () => paged([]),
      'GET /v1/document-types': () => [
        { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
      ],
    });
    wrap(<IncrementScreen />, me(['I-05:VIEW', 'I-05:SUBMIT'], { roles: ['ESTABLISHMENT_HR'] }));
    await screen.findByRole('option', { name: 'Gazette' });
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    await fill(card);
    await waitFor(() => expect(screen.getByTestId('i05-register')).toHaveAttribute('open'));
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });
});
