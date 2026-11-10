import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkSlips } from '@/modules/labour';
import type { RegisterRow } from '@/modules/labour/api/captureApi';
import type { Operation, SlipMonth } from '@/modules/labour/api/labourApi';
import { previousMonth } from '@/modules/labour/business/labourRules';
import { businessToday } from '@/shared/time';
import { date } from '@/shared/ui';
import { backend, me, paged, sent, t, wrap, type Route } from './support/harness';

/**
 * I-02 Work-Slip Capture as drawn (Wireframes v6.1, OD-121; SRS-2-4, SRS-2-15, SRS-2-34, SRS-5-14, BR-LABR-002)
 * coverage gaps: the drawn Add row / Save / Save & Next / Reset row driving the closed capture section, the row Edit
 * and ✔, the filters, the rows with nothing recorded and the overtime checks.
 */

afterEach(() => vi.unstubAllGlobals());

const search = vi.hoisted(() => ({ current: '' }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search.current),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const MONTH = previousMonth(businessToday());
const DAY = `${MONTH}-02`;
const GANGS = [{ id: 8, code: 'GNG-01', name: 'Okhla Shed A Gang 1', type: 'HANDLING' }];
const ANNA = { received: 0, confirmed: 0, returned: 0 };

const OPERATIONS: Operation[] = [
  { id: 1, code: 'W2P', name: 'Wagon-to-Platform', bagBased: true, normType: 'HANDLING', pbnw: false },
];

const SAVED = {
  id: 70,
  number: 'WS-8-1',
  date: `${MONTH}-01`,
  status: 'DRAFT',
  continueWork: false,
  holiday: false,
  labourStrength: 1,
  amendmentOf: null,
  rows: [
    {
      order: 1,
      operation: 'W2P - Wagon-to-Platform',
      bagType: 'KATTA50',
      bags: 1200,
      stackHeight: 12,
      heightBand: 'UPTO_20',
      start: '09:00',
      end: '13:00',
      pbnwReason: null,
      operationId: 1,
      bagTypeId: 2,
      pbnwText: null,
      rakeReference: null,
    },
  ],
  participants: [
    { employeeId: 11, labourCode: 'L10701', name: 'Harpal Sandhu', role: 'SARDAR', present: true },
  ],
};

const VIEW: SlipMonth = {
  gangId: 8,
  gangName: 'Okhla Shed A Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DEPT_LABOUR',
  yymm: MONTH.replace('-', ''),
  batchState: 'OPEN',
  approvalId: null,
  operations: OPERATIONS,
  bagTypes: [{ id: 2, code: 'KATTA50', name: 'Katta 50 kg', weightKg: 50, band: 'BAND_1' }],
  slips: [SAVED as SlipMonth['slips'][number]],
};

function row(over: Partial<RegisterRow> = {}): RegisterRow {
  return {
    slipId: 7,
    rowOrder: 1,
    number: 'WS-88402',
    gangId: 8,
    gangCode: 'GNG-01',
    date: `${MONTH}-02`,
    bagType: null,
    operationCode: 'W2P',
    operation: null,
    bags: null,
    start: null,
    end: null,
    normType: null,
    continueWork: false,
    labours: 4,
    status: 'APPROVED',
    source: 'ENT',
    duplicateOf: null,
    reason: null,
    enteredBy: null,
    decidedBy: null,
    decidedAt: null,
    ...over,
  };
}

const OVERTIME = [
  {
    key: 'OT-A',
    labourCode: 'DL-20190',
    name: 'Suresh Pal',
    gangCode: 'GNG-01',
    pool: false,
    operationCode: 'CLN',
    operation: 'Cleaning',
    workingAs: null,
    start: '16:00',
    end: '18:00',
    hours: 2,
    bags: null,
    minimumMet: null,
    ancillary: true,
    pbnwReason: null,
  },
  {
    key: 'OT-Y',
    labourCode: 'DL-20180',
    name: 'Prem Chand',
    gangCode: 'GNG-01',
    pool: false,
    operationCode: 'W2P',
    operation: 'Wagon-to-Platform',
    workingAs: 'HANDLING',
    start: '16:00',
    end: '19:00',
    hours: 3,
    bags: 400,
    minimumMet: 'Y',
    ancillary: false,
    pbnwReason: null,
  },
  {
    key: 'OT-N',
    labourCode: 'DL-20181',
    name: 'Raju Das',
    gangCode: 'GNG-01',
    pool: false,
    operationCode: 'W2P',
    operation: 'Wagon-to-Platform',
    workingAs: 'HANDLING',
    start: null,
    end: null,
    hours: null,
    bags: null,
    minimumMet: null,
    ancillary: false,
    pbnwReason: null,
  },
];

function routes(rows: RegisterRow[], over: Record<string, Route> = {}) {
  return backend({
    'GET /v1/labour/work-slips/overview': () => ({
      month: MONTH,
      saved: 1234,
      rejected: 0,
      pendingBooking: 0,
      anna: ANNA,
      gangs: GANGS,
      dates: [DAY],
    }),
    'GET /v1/labour/work-slips': () => paged(rows),
    'GET /v1/labour/overtime': () => paged(OVERTIME),
    'GET /v1/labour/gangs?': () => paged([]),
    'GET /v1/labour/gangs/8/work-slips': () => VIEW,
    'GET /v1/labour/gangs/8/members': () => [
      { employeeId: 11, labourCode: 'L10701', name: 'Harpal Sandhu', role: 'SARDAR' },
    ],
    'POST /v1/labour/gangs/8/work-slips': () => ({ status: 201, body: { ...SAVED, id: 71 } }),
    ...over,
  });
}

const LDH = ['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT'];
const drawn = (key: string) =>
  screen.getAllByRole('button', { name: t(key) }).find((b) => !b.hasAttribute('data-guard')) as HTMLElement;
const capture = () => screen.getByTestId('i02-capture');

async function pickGang() {
  const gang = screen.getByRole('combobox', { name: t('i01w.allGangs') });
  await within(gang).findByRole('option', { name: 'GNG-01' });
  fireEvent.change(gang, { target: { value: '8' } });
}

async function fillRow(index = 0) {
  fireEvent.change(within(capture()).getByLabelText(label('i02.date'), { selector: 'input' }), {
    target: { value: DAY },
  });
  await userEvent.selectOptions(
    within(capture()).getAllByLabelText(label('i02.operation'))[index] as HTMLElement,
    '1',
  );
  await userEvent.selectOptions(
    within(capture()).getAllByLabelText(label('i02.bagType'))[index] as HTMLElement,
    '2',
  );
  const bags = within(capture()).getAllByLabelText(label('i02.bags'))[index] as HTMLElement;
  await userEvent.clear(bags);
  await userEvent.type(bags, '100');
  fireEvent.change(within(capture()).getAllByLabelText(label('i02.start'))[index] as HTMLElement, {
    target: { value: '09:00' },
  });
  fireEvent.change(within(capture()).getAllByLabelText(label('i02.end'))[index] as HTMLElement, {
    target: { value: '11:00' },
  });
}

describe('I-02 drawn entry buttons drive the capture section', () => {
  it('BR-LABR-002: Add row opens a new slip, adds rows, Reset row clears the last; Save and Next saves and moves on', async () => {
    search.current = '';
    const calls = routes([row()]);
    wrap(<WorkSlips />, me(LDH));
    await pickGang();
    // With no entry open, the drawn Add row opens a new slip on the gang of the filter.
    fireEvent.click(drawn('i02w.addRow'));
    expect(capture().hasAttribute('open')).toBe(true);
    const operations = await within(capture()).findAllByLabelText(label('i02.operation'));
    expect(operations).toHaveLength(1);
    fireEvent.click(drawn('i02w.addRow'));
    const ops = within(capture()).getAllByLabelText(label('i02.operation'));
    expect(ops).toHaveLength(2);
    await userEvent.selectOptions(ops[1] as HTMLElement, '1');
    await userEvent.type(within(capture()).getAllByLabelText(label('i02.bags'))[0] as HTMLElement, '55');
    fireEvent.click(drawn('i02w.reset'));
    expect(within(capture()).getAllByLabelText(label('i02.operation'))).toHaveLength(2);
    expect((within(capture()).getAllByLabelText(label('i02.operation'))[1] as HTMLSelectElement).value).toBe(
      '',
    );
    expect(within(capture()).queryAllByLabelText(label('i02.bags'))).toHaveLength(0);
    // An incomplete slip is refused with the reason; nothing is sent.
    fireEvent.click(drawn('i02w.saveNext'));
    expect((await screen.findAllByText(t('i02.why.date'))).length).toBeGreaterThan(0);
    fireEvent.click(drawn('i02w.reset'));
    await fillRow(0);
    await userEvent.click(
      within(capture()).getAllByRole('button', { name: t('action.i02.removeRow') })[1] as HTMLElement,
    );
    expect(sent(calls, 'POST', '/work-slips')).toHaveLength(0);
    fireEvent.click(drawn('i02w.saveNext'));
    expect(await screen.findByText(t('i02.saved'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/labour/gangs/8/work-slips').at(-1)).toMatchObject({
      date: DAY,
      rows: [{ operationId: 1, bagTypeId: 2, bags: 100, start: '09:00', end: '11:00' }],
      participants: [11],
    });
    // The next day's entry stays open with the operation kept.
    await waitFor(() =>
      expect(
        (within(capture()).getAllByLabelText(label('i02.operation'))[0] as HTMLSelectElement).value,
      ).toBe('1'),
    );
  });

  it('SRS-2-4: Add row stops at twenty rows', async () => {
    search.current = '';
    routes([row()]);
    wrap(<WorkSlips />, me(LDH));
    await pickGang();
    fireEvent.click(screen.getByRole('button', { name: t('i02w.new') }));
    await within(capture()).findAllByLabelText(label('i02.operation'));
    // 19 clicks fill the twenty rows; the twentieth click adds none.
    const add = drawn('i02w.addRow');
    for (let i = 0; i < 20; i++) fireEvent.click(add);
    expect(within(capture()).getAllByLabelText(label('i02.operation'))).toHaveLength(20);
  }, 60000);

  it('SRS-2-4: the drawn Save sends a whole new slip', async () => {
    search.current = '';
    const calls = routes([row()]);
    wrap(<WorkSlips />, me(LDH));
    await pickGang();
    fireEvent.click(screen.getByRole('button', { name: t('i02w.new') }));
    await within(capture()).findAllByLabelText(label('i02.operation'));
    fireEvent.click(drawn('i02w.save'));
    expect((await screen.findAllByText(t('i02.why.date'))).length).toBeGreaterThan(0);
    await fillRow(0);
    fireEvent.click(drawn('i02w.save'));
    expect(await screen.findByText(t('i02.saved'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/labour/gangs/8/work-slips')).toHaveLength(1);
  });

  it('SRS-2-15: on an amendment Save asks the reason and Save and Next is refused', async () => {
    search.current = '';
    routes([row()]);
    wrap(<WorkSlips />, me(LDH));
    await pickGang();
    fireEvent.click(screen.getByRole('button', { name: t('i02w.new') }));
    const slips = await within(capture()).findByRole('table', { name: t('i02.slips') });
    fireEvent.click(within(slips).getByRole('button', { name: t('action.i02.amend') }));
    await waitFor(() => expect(within(capture()).getByLabelText(/L10701/)).toBeChecked());
    fireEvent.click(drawn('i02w.saveNext'));
    expect(await screen.findByText(t('i02w.why.noNextOnAmend'))).toBeInTheDocument();
    fireEvent.click(drawn('i02w.save'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('i02.amendWhy'))).toBeInTheDocument();
  });

  it('SRS-2-4: a new slip with no gang chosen sends nothing on Save', async () => {
    search.current = '';
    const calls = routes([row()]);
    wrap(<WorkSlips />, me(LDH));
    await screen.findByText('WS-88402');
    fireEvent.click(screen.getByRole('button', { name: t('i02w.new') }));
    expect(capture().hasAttribute('open')).toBe(true);
    fireEvent.click(drawn('i02w.save'));
    fireEvent.click(drawn('i02w.saveNext'));
    fireEvent.click(drawn('i02w.addRow'));
    fireEvent.click(drawn('i02w.reset'));
    await new Promise((r) => setTimeout(r, 50));
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    expect(screen.queryByText(t('i02w.why.noEntry'))).not.toBeInTheDocument();
  });

  it('SRS-2-15: the register row Edit opens the capture on the row gang and month', async () => {
    search.current = '';
    const calls = routes([row()]);
    wrap(<WorkSlips />, me(LDH));
    fireEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} WS-88402` }));
    expect(capture().hasAttribute('open')).toBe(true);
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes(`/v1/labour/gangs/8/work-slips?month=${MONTH}`))).toBe(true),
    );
    expect(await within(capture()).findByRole('table', { name: t('i02.slips') })).toBeInTheDocument();
    expect(within(capture()).queryByLabelText(label('i02.operation'))).not.toBeInTheDocument();
  });
  it('SRS-2-15: a row of a gang not in the month list opens the capture asking for the gang', async () => {
    search.current = '';
    const calls = routes([row({ gangId: 99, gangCode: 'GNG-99', number: 'WS-99' })]);
    wrap(<WorkSlips />, me(LDH));
    fireEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} WS-99` }));
    expect(capture().hasAttribute('open')).toBe(true);
    expect(within(capture()).getByText(t('i01.chooseGang'))).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/gangs/99/'))).toBe(false);
  });
});

describe('I-02 register rows, filters and checks', () => {
  it('SRS-2-4: a row with nothing recorded reads —, its View too; the Checker’s ✔ goes to W-01', async () => {
    search.current = '';
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    routes([row()]);
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:APPROVE']));
    const table = await screen.findByRole('table', { name: t('i02w.registerName') });
    const tr = (await within(table).findByText('WS-88402')).closest('tr') as HTMLElement;
    // Bag type, operation, bags, start, end and norm.
    expect(within(tr).getAllByText('—')).toHaveLength(6);
    fireEvent.click(within(tr).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
    fireEvent.click(within(tr).getByRole('button', { name: `${t('common.view')} WS-88402` }));
    const dialog = await screen.findByRole('dialog');
    // Operation, bags, entered by, approved by and reason.
    expect(within(dialog).getAllByText('—')).toHaveLength(5);
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // The queue counts keep Indian grouping.
    expect(screen.getByText('1,234')).toBeInTheDocument();
  });

  it('SRS-5-14: an NWNP row without continuing work reads No', async () => {
    search.current = 'engine=NWNP';
    routes([row({ continueWork: false, payrollType: 'NWNP_LABOUR' } as Partial<RegisterRow>)]);
    wrap(<WorkSlips />, me(['I-02:VIEW']));
    expect(await screen.findByText(t('i02w.continueNo'))).toBeInTheDocument();
  });

  it('SRS-2-34: the DPS booking register reads — for what is not recorded; New Booking-Slip opens the capture', async () => {
    search.current = 'engine=DPS';
    routes([row({ number: 'DWS-1', status: 'SUBMITTED' })]);
    wrap(<WorkSlips />, me(LDH));
    const booking = await screen.findByRole('table', { name: t('i02w.booking') });
    const tr = (await within(booking).findByText('DWS-1')).closest('tr') as HTMLElement;
    // Operation, bags, entered by and approved by.
    expect(within(tr).getAllByText('—')).toHaveLength(4);
    fireEvent.click(screen.getByRole('button', { name: t('i02w.newBooking') }));
    expect(capture().hasAttribute('open')).toBe(true);
  });

  it('SRS-2-34: a viewer of the DPS view sees New Booking-Slip locked with the reason', async () => {
    search.current = 'engine=DPS';
    routes([]);
    wrap(<WorkSlips />, me(['I-02:VIEW']));
    await screen.findByRole('table', { name: t('i02w.booking') });
    const locked = screen.getByRole('button', { name: t('i02w.newBooking') });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAttribute('data-why', t('i01w.why.capture'));
  });

  it('SRS-2-4: the type and date filters ask the register again; a type change clears the date', async () => {
    search.current = '';
    const calls = routes([row()]);
    wrap(<WorkSlips />, me(['I-02:VIEW']));
    const dates = screen.getByRole('combobox', { name: t('i02w.allDates') });
    await within(dates).findByRole('option', { name: date(DAY) });
    fireEvent.change(dates, { target: { value: DAY } });
    await waitFor(() =>
      expect(
        calls.some((c) => c.url.includes('/v1/labour/work-slips?') && c.url.includes(`date=${DAY}`)),
      ).toBe(true),
    );
    fireEvent.change(screen.getByPlaceholderText(t('wf.searchInList')), { target: { value: ' WS-88 ' } });
    await waitFor(() =>
      expect(
        calls.some((c) => c.url.includes('/v1/labour/work-slips?') && c.url.includes('text=WS-88')),
      ).toBe(true),
    );
    fireEvent.change(screen.getByRole('combobox', { name: t('i01w.typeOf') }), {
      target: { value: 'DPS_LABOUR' },
    });
    expect(await screen.findByRole('table', { name: t('i02w.booking') })).toBeInTheDocument();
    expect((dates as HTMLSelectElement).value).toBe('');
  });

  it('SRS-2-42: the overtime check reads time-based for ancillary work, eligible when met, — when not assessed', async () => {
    search.current = '';
    routes([]);
    wrap(<WorkSlips />, me(['I-02:VIEW']));
    const ot = await screen.findByRole('table', { name: t('i02w.ot') });
    const ancillary = (await within(ot).findByText(/Suresh Pal/)).closest('tr') as HTMLElement;
    expect(within(ancillary).getByText(t('i02w.chk.time'))).toBeInTheDocument();
    expect(within(ancillary).getByText(t('i02w.noBagField'))).toBeInTheDocument();
    const met = within(ot)
      .getByText(/Prem Chand/)
      .closest('tr') as HTMLElement;
    expect(within(met).getByText(t('i02w.chk.eligible'))).toBeInTheDocument();
    expect(within(met).getByText('400')).toBeInTheDocument();
    const open = within(ot)
      .getByText(/Raju Das/)
      .closest('tr') as HTMLElement;
    // Start, end, hours, bags and the check.
    expect(within(open).getAllByText('—')).toHaveLength(5);
  });
});
