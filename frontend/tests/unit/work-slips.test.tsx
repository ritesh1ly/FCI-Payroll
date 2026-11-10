import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkSlips } from '@/modules/labour';
import type { Operation, RowEntry, SlipEntry, SlipMonth } from '@/modules/labour/api/labourApi';
import {
  emptyRow,
  heightBand,
  nextSlip,
  rowIsEmpty,
  slipBlocked,
  slipRows,
  toSlipRequest,
} from '@/modules/labour/business/labourRules';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/** I-02 Work-Slip Capture (plan 20 phase 20c; SRS-2-3, SRS-2-4, SRS-2-5, SRS-2-15, SRS-2-42, SRS-3-9). */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const OPERATIONS: Operation[] = [
  { id: 1, code: 'W2P', name: 'Wagon-to-Platform', bagBased: true, normType: 'HANDLING', pbnw: false },
  { id: 4, code: 'PBNW', name: 'Present But No Work', bagBased: false, normType: 'NONE', pbnw: true },
];

const GANG = {
  gangId: 5,
  gangCode: 'DP-DL01-G002',
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DEPT_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  sanctionedStrength: 6,
  members: 2,
  effectiveFrom: '2025-07-01',
};

const SLIP = {
  id: 70,
  number: 'WS-5-20260901-1',
  date: '2026-09-01',
  status: 'DRAFT',
  continueWork: false,
  holiday: false,
  labourStrength: 2,
  amendmentOf: null,
  rows: [
    {
      order: 1,
      operation: 'W2P - Wagon-to-Platform',
      bagType: 'KATTA50',
      bags: 1200,
      stackHeight: 22,
      heightBand: 'ABOVE_20',
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
    { employeeId: 12, labourCode: 'L10702', name: 'Mohan Rawat', role: 'MANDAL', present: true },
  ],
};

function month(over: Partial<SlipMonth> = {}): SlipMonth {
  return {
    gangId: 5,
    gangName: 'Okhla Gang 1',
    depot: 'DP-DL01 - Depot Okhla',
    payrollType: 'DEPT_LABOUR',
    yymm: '202609',
    batchState: 'OPEN',
    approvalId: null,
    operations: OPERATIONS,
    bagTypes: [{ id: 2, code: 'KATTA50', name: 'Katta 50 kg', weightKg: 50, band: 'BAND_1' }],
    slips: [SLIP as SlipMonth['slips'][number]],
    ...over,
  };
}

function entry(over: Partial<SlipEntry> = {}): SlipEntry {
  return {
    date: '2026-09-02',
    continueWork: false,
    rows: [{ ...emptyRow(), operationId: 1, bagTypeId: 2, bags: 100, start: '09:00', end: '11:00' }],
    participants: [11],
    ...over,
  };
}

const MEMBERS = [
  { employeeId: 11, labourCode: 'L10701', name: 'Harpal Sandhu', role: 'SARDAR' },
  { employeeId: 12, labourCode: 'L10702', name: 'Mohan Rawat', role: 'MANDAL' },
];

describe('I-02 rules', () => {
  it('SRS-2-4 / SRS-2-42: a slip is whole before it is saved', () => {
    const today = '2026-10-28';
    expect(slipBlocked(entry(), OPERATIONS, today)).toBeNull();
    expect(slipBlocked(entry({ date: '' }), OPERATIONS, today)).toBe('i02.why.date');
    expect(slipBlocked(entry({ date: '2026-10-29' }), OPERATIONS, today)).toBe('i02.why.date');
    expect(slipBlocked(entry({ rows: [] }), OPERATIONS, today)).toBe('i02.why.rows');
    expect(
      slipBlocked(entry({ rows: [{ ...emptyRow(), start: '09:00', end: '10:00' }] }), OPERATIONS, today),
    ).toBe('i02.why.operation');
    const [row] = entry().rows as [RowEntry];
    expect(slipBlocked(entry({ rows: [{ ...row, start: '9:00' }] }), OPERATIONS, today)).toBe('i02.why.time');
    expect(slipBlocked(entry({ rows: [{ ...row, end: '08:00' }] }), OPERATIONS, today)).toBe(
      'i02.why.timeOrder',
    );
    expect(
      slipBlocked(entry({ continueWork: true, rows: [{ ...row, end: '08:00' }] }), OPERATIONS, today),
    ).toBeNull();
    expect(slipBlocked(entry({ rows: [{ ...row, bags: null }] }), OPERATIONS, today)).toBe('i02.why.bags');
    expect(slipBlocked(entry({ rows: [{ ...row, bagTypeId: null }] }), OPERATIONS, today)).toBe(
      'i02.why.bags',
    );
    expect(slipBlocked(entry({ rows: [{ ...row, bags: 100000 }] }), OPERATIONS, today)).toBe('i02.why.bags');
    const pbnw = { ...emptyRow(), operationId: 4, start: '09:00', end: '11:00' };
    expect(slipBlocked(entry({ rows: [pbnw] }), OPERATIONS, today)).toBe('i02.why.pbnw');
    expect(slipBlocked(entry({ rows: [{ ...pbnw, pbnwReason: 'OTHER' }] }), OPERATIONS, today)).toBe(
      'i02.why.pbnw',
    );
    expect(
      slipBlocked(entry({ rows: [{ ...pbnw, pbnwReason: 'RAKE_DELAYED' }] }), OPERATIONS, today),
    ).toBeNull();
    expect(slipBlocked(entry({ participants: [] }), OPERATIONS, today)).toBe('i02.why.participants');
  });

  it('SRS-2-5: a stack above 20 is Above-20; the request trims the texts', () => {
    expect(heightBand(null)).toBeNull();
    expect(heightBand(20)).toBe('UPTO_20');
    expect(heightBand(21)).toBe('ABOVE_20');
    const trimmed = toSlipRequest(
      entry({ rows: [{ ...(entry().rows[0] as RowEntry), pbnwText: ' x ', rakeReference: ' R1 ' }] }),
    );
    expect(trimmed.rows[0]).toMatchObject({ pbnwText: 'x', rakeReference: 'R1' });
  });
});

async function open(view: SlipMonth) {
  const calls = backend({
    'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
    'GET /v1/labour/gangs/5/members': () => MEMBERS,
    'GET /v1/labour/gangs/5/work-slips?': () => view,
    'POST /v1/labour/gangs/5/work-slips': () => ({ status: 201, body: { ...SLIP, id: 71 } }),
    'POST /v1/labour/work-slips/70/amendments': () => ({
      status: 201,
      body: { ...SLIP, id: 72, amendmentOf: 70 },
    }),
    'POST /v1/labour/work-slips/70/cancellations': () => ({ ...SLIP, status: 'CANCELLED' }),
    'POST /v1/labour/gangs/5/work-slip-batches': () => problem(422, 'LBR-422-WORK-SLIP', ['NO_SLIPS']),
  });
  return calls;
}

async function chooseGang() {
  const gang = screen.getByLabelText(label('m09.gang'), { selector: 'select' });
  await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
  await userEvent.selectOptions(gang, '5');
  fireEvent.change(screen.getByLabelText(label('i01.month'), { selector: 'input' }), {
    target: { value: '2026-09' },
  });
  return screen.findByRole('table', { name: t('i02.slips') });
}

describe('I-02 Work-Slip Capture', () => {
  it('SRS-2-4: the Labour Dealing Hand saves a slip of bag-based work for the gang', async () => {
    const calls = await open(month());
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    const table = await chooseGang();
    expect(within(table).getByText('WS-5-20260901-1')).toBeTruthy();
    expect(within(table).getByText(/Above-20|ABOVE_20/)).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.new') }));
    const save = screen.getByRole('button', { name: t('action.i02.save') });
    expect(save.getAttribute('title')).toBe(t('i02.why.date'));
    fireEvent.change(screen.getByLabelText(label('i02.date'), { selector: 'input' }), {
      target: { value: '2026-09-02' },
    });
    await userEvent.selectOptions(screen.getByLabelText(label('i02.operation')), '1');
    await userEvent.selectOptions(screen.getByLabelText(label('i02.bagType')), '2');
    await userEvent.type(screen.getByLabelText(label('i02.bags')), '1200');
    await userEvent.type(screen.getByLabelText(label('i02.stack')), '22');
    fireEvent.change(screen.getByLabelText(label('i02.start')), { target: { value: '09:00' } });
    fireEvent.change(screen.getByLabelText(label('i02.end')), { target: { value: '13:00' } });
    await userEvent.click(
      within(screen.getByTestId('i02-capture')).getByRole('button', { name: t('action.i02.addRow') }),
    );
    await userEvent.click(
      screen.getAllByRole('button', { name: t('action.i02.removeRow') })[1] as HTMLElement,
    );
    await userEvent.click(screen.getByLabelText(/L10702/));
    expect(save).toBeEnabled();
    await userEvent.click(save);
    expect(await screen.findByText(t('i02.saved'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/gangs/5/work-slips').at(-1)).toMatchObject({
      date: '2026-09-02',
      rows: [{ operationId: 1, bagTypeId: 2, bags: 1200, stackHeight: 22, start: '09:00', end: '13:00' }],
      participants: [11],
    });
  });

  it('SRS-2-15: a saved slip is amended or cancelled with a reason; a refused batch is shown', async () => {
    const calls = await open(month());
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    const table = await chooseGang();
    await userEvent.click(within(table).getByRole('button', { name: t('action.i02.amend') }));
    expect(screen.getByText(t('i02.amendOf', ['WS-5-20260901-1']))).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(label('i02.operation')), '4');
    await userEvent.selectOptions(screen.getByLabelText(label('i02.pbnwReason')), 'OTHER');
    await userEvent.type(screen.getByLabelText(label('i02.pbnwText')), 'Crane down');
    await userEvent.type(screen.getByLabelText(label('i02.rake')), 'RK-1');
    fireEvent.change(screen.getByLabelText(label('i02.start')), { target: { value: '09:00' } });
    fireEvent.change(screen.getByLabelText(label('i02.end')), { target: { value: '11:00' } });
    await userEvent.click(screen.getByLabelText(new RegExp(t('i02.continueWork'))));
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.save') }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Rake memo');
    await userEvent.click(within(dialog).getByRole('button', { name: /confirm|ok|save|submit/i }));
    expect(await screen.findByText(t('i02.saved'))).toBeTruthy();
    expect(sent(calls, 'POST', '/amendments').at(-1)).toMatchObject({ reason: 'Rake memo' });

    await userEvent.click(within(table).getByRole('button', { name: t('action.i02.cancel') }));
    const cancelDialog = await screen.findByRole('dialog');
    await userEvent.type(within(cancelDialog).getByRole('textbox'), 'Wrong gang');
    await userEvent.click(within(cancelDialog).getByRole('button', { name: /confirm|ok|save|submit/i }));
    expect(await screen.findByText(t('i02.cancelled', ['WS-5-20260901-1']))).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: t('action.i02.submit') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('SRS-2-29: a viewer sees a saved slip but its amend and cancel are LDH only', async () => {
    await open(month());
    wrap(<WorkSlips />, me(['I-02:VIEW']));
    const table = await chooseGang();
    for (const name of [t('action.i02.amend'), t('action.i02.cancel')]) {
      const button = within(table).getByRole('button', { name });
      expect(button).toBeDisabled();
      expect(button.getAttribute('title')).toBe(t('reason.ldhOnly'));
    }
  });

  it('SRS-2-29: a viewer cannot save; an approved month takes no new slip', async () => {
    await open(month({ batchState: 'APPROVED', slips: [] }));
    wrap(<WorkSlips />, me(['I-02:VIEW']));
    const gang = screen.getByLabelText(label('m09.gang'), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    fireEvent.change(screen.getByLabelText(label('i01.month'), { selector: 'input' }), {
      target: { value: '2026-09' },
    });
    expect(await screen.findByText(t('i02.none'))).toBeTruthy();
    const create = screen.getByRole('button', { name: t('action.i02.new') });
    expect(create).toBeDisabled();
    expect(create.getAttribute('title')).toBe(t('reason.ldhOnly'));
    expect(screen.getByRole('button', { name: t('action.i02.submit') }).getAttribute('title')).toBe(
      t('reason.ldhOnly'),
    );
  });
});

// SC-20-0195 (I-02 Save and Next) and SC-20-0189 (I-02 Holiday Flag).
describe('I-02 BMAD fixes: saved rows, slip-date members, the Ancillary Pool', () => {
  it('SRS-2-15 / SRS-2-4: an amendment opens with the saved rows and sends them unchanged', async () => {
    const calls = await open(month());
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    const table = await chooseGang();
    await userEvent.click(within(table).getByRole('button', { name: t('action.i02.amend') }));
    expect((screen.getByLabelText(label('i02.operation')) as HTMLSelectElement).value).toBe('1');
    expect((screen.getByLabelText(label('i02.bagType')) as HTMLSelectElement).value).toBe('2');
    expect((screen.getByLabelText(label('i02.bags')) as HTMLInputElement).value).toBe('1200');
    expect((screen.getByLabelText(label('i02.stack')) as HTMLInputElement).value).toBe('22');
    expect((screen.getByLabelText(label('i02.start')) as HTMLInputElement).value).toBe('09:00');
    expect((screen.getByLabelText(label('i02.end')) as HTMLInputElement).value).toBe('13:00');
    // I-02 l.4632: the labour are the gang's members on the slip date.
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/labour/gangs/5/members?date=2026-09-01'))).toBe(true),
    );
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.save') }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Bags recounted');
    await userEvent.click(within(dialog).getByRole('button', { name: /confirm|ok|save|submit/i }));
    expect(await screen.findByText(t('i02.saved'))).toBeTruthy();
    expect(sent(calls, 'POST', '/amendments').at(-1)).toMatchObject({
      reason: 'Bags recounted',
      slip: {
        rows: [{ operationId: 1, bagTypeId: 2, bags: 1200, stackHeight: 22, start: '09:00', end: '13:00' }],
        participants: [11, 12],
      },
    });
  });

  it('SRS-2-15: the saved rows map to entries; a slip without rows opens one empty row', () => {
    const slip = SLIP as SlipMonth['slips'][number];
    const [row] = slipRows(slip) as [RowEntry];
    expect(row).toEqual({
      operationId: 1,
      bagTypeId: 2,
      bags: 1200,
      stackHeight: 22,
      start: '09:00',
      end: '13:00',
      pbnwReason: null,
      pbnwText: '',
      rakeReference: '',
    });
    const first = slip.rows[0] as SlipMonth['slips'][number]['rows'][number];
    const pbnw = slipRows({
      ...slip,
      rows: [
        {
          ...first,
          operationId: 4,
          bagTypeId: null,
          pbnwReason: 'OTHER',
          pbnwText: 'Crane',
          rakeReference: 'RK-1',
        },
      ],
    });
    expect(pbnw[0]).toMatchObject({
      operationId: 4,
      bagTypeId: null,
      pbnwText: 'Crane',
      rakeReference: 'RK-1',
    });
    expect(slipRows({ ...slip, rows: [] })).toEqual([emptyRow()]);
  });

  it('I-02 l.4632: a new slip takes the members of its date; a date change re-reads them', async () => {
    const calls = backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/members?date=2026-09-02': () => [MEMBERS[0]],
      'GET /v1/labour/gangs/5/members': () => MEMBERS,
      'GET /v1/labour/gangs/5/work-slips?': () => month({ slips: [] }),
      'POST /v1/labour/gangs/5/work-slips': () => ({ status: 201, body: { ...SLIP, id: 71 } }),
    });
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE']));
    await chooseGangIn();
    await userEvent.click(await screen.findByRole('button', { name: t('action.i02.new') }));
    // Today's roster before a date is chosen.
    expect(await screen.findByText(/L10702 - Mohan Rawat/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText(label('i02.date'), { selector: 'input' }), {
      target: { value: '2026-09-02' },
    });
    // On 2 September only Harpal was in the gang: Mohan is not offered and not sent.
    expect(await screen.findByText(/L10701 - Harpal Sandhu/)).toBeTruthy();
    expect(screen.queryByText(/L10702 - Mohan Rawat/)).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(label('i02.operation')), '1');
    await userEvent.selectOptions(screen.getByLabelText(label('i02.bagType')), '2');
    await userEvent.type(screen.getByLabelText(label('i02.bags')), '100');
    fireEvent.change(screen.getByLabelText(label('i02.start')), { target: { value: '09:00' } });
    fireEvent.change(screen.getByLabelText(label('i02.end')), { target: { value: '11:00' } });
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.save') }));
    expect(await screen.findByText(t('i02.saved'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/gangs/5/work-slips').at(-1)).toMatchObject({ participants: [11] });
  });

  it('SRS-2-46 / l.4621: an Ancillary Pool slip offers no bag-based operation and no bag fields', async () => {
    const POOL = {
      ...GANG,
      gangId: 6,
      gangCode: 'DP-DL01-G001',
      gangName: 'Ancillary Pool',
      gangType: 'ANCILLARY_POOL',
    };
    backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG, POOL], 2, url),
      'GET /v1/labour/gangs/6/members': () => [
        { employeeId: 13, labourCode: 'L10703', name: 'Kishan Pal', role: 'ANCILLARY' },
      ],
      'GET /v1/labour/gangs/6/work-slips?': () => month({ gangId: 6, gangName: 'Ancillary Pool', slips: [] }),
    });
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE']));
    const gang = screen.getByLabelText(label('m09.gang'), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText(/DP-DL01-G001 - Ancillary Pool/)).toBeTruthy());
    await userEvent.selectOptions(gang, '6');
    fireEvent.change(screen.getByLabelText(label('i01.month'), { selector: 'input' }), {
      target: { value: '2026-09' },
    });
    await userEvent.click(await screen.findByRole('button', { name: t('action.i02.new') }));
    const op = screen.getByLabelText(label('i02.operation'));
    expect(within(op).queryByText('W2P - Wagon-to-Platform')).toBeNull();
    await userEvent.selectOptions(op, '4');
    expect(screen.queryByLabelText(label('i02.bags'))).toBeNull();
    expect(screen.queryByLabelText(label('i02.bagType'))).toBeNull();
    expect(await screen.findByText(/L10703 - Kishan Pal/)).toBeTruthy();
  });
});

async function chooseGangIn() {
  const gang = screen.getByLabelText(label('m09.gang'), { selector: 'select' });
  await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
  await userEvent.selectOptions(gang, '5');
  fireEvent.change(screen.getByLabelText(label('i01.month'), { selector: 'input' }), {
    target: { value: '2026-09' },
  });
}

describe('I-02 Save and Next and the Holiday Flag', () => {
  // SRS-2-17, SRS-NF-9: Save and Next keeps the gang and the last operation for the next row.
  it('BR-LABR-002: Save and Next keeps the gang and the last operation and advances the date', () => {
    expect(nextSlip(entry({ date: '2026-09-02' }), [11, 12])).toMatchObject({
      date: '2026-09-03',
      continueWork: false,
      rows: [{ operationId: 1, bagTypeId: null, bags: null, start: '', end: '' }],
      participants: [11, 12],
    });
    expect(nextSlip(entry({ date: '2026-09-30' }), [11]).date).toBe('');
    expect(nextSlip(entry({ rows: [] }), [11]).rows[0]?.operationId).toBeNull();
  });

  it('BR-LABR-002: the Labour Dealing Hand saves and goes straight to the next day', async () => {
    const calls = await open(month());
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    await chooseGang();
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.new') }));
    fireEvent.change(screen.getByLabelText(label('i02.date'), { selector: 'input' }), {
      target: { value: '2026-09-02' },
    });
    await userEvent.selectOptions(screen.getByLabelText(label('i02.operation')), '1');
    await userEvent.selectOptions(screen.getByLabelText(label('i02.bagType')), '2');
    await userEvent.type(screen.getByLabelText(label('i02.bags')), '500');
    fireEvent.change(screen.getByLabelText(label('i02.start')), { target: { value: '09:00' } });
    fireEvent.change(screen.getByLabelText(label('i02.end')), { target: { value: '12:00' } });
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.saveNext') }));
    expect(await screen.findByText(t('i02.saved'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/gangs/5/work-slips')).toHaveLength(1);
    const day = screen.getByLabelText(label('i02.date'), { selector: 'input' }) as HTMLInputElement;
    expect(day.value).toBe('2026-09-03');
    expect((screen.getByLabelText(label('i02.operation')) as HTMLSelectElement).value).toBe('1');
    expect((screen.getByLabelText(label('i02.bags')) as HTMLInputElement).value).toBe('');
  });

  it('SRS-2-4: an amendment has no Save and Next; the Holiday Flag shows on a slip of a K day', async () => {
    await open(month({ slips: [{ ...SLIP, holiday: true } as SlipMonth['slips'][number]] }));
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    const table = await chooseGang();
    expect(within(table).getByText(t('i02.holidayFlag'))).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: t('action.i02.amend') }));
    expect(screen.queryByRole('button', { name: t('action.i02.saveNext') })).toBeNull();
  });
});

describe('I-02 Add Row (SRS-5-7)', () => {
  // SC-22-0006
  it('SRS-5-7: only an empty row of the slip being entered can be removed', async () => {
    expect(rowIsEmpty(emptyRow())).toBe(true);
    expect(rowIsEmpty({ ...emptyRow(), operationId: 4 })).toBe(false);
    expect(rowIsEmpty({ ...emptyRow(), start: '18:00' })).toBe(false);
    expect(rowIsEmpty({ ...emptyRow(), bags: 0 })).toBe(false);
    expect(rowIsEmpty({ ...emptyRow(), pbnwText: ' ' })).toBe(false);
    await open(month({ payrollType: 'NWNP_LABOUR' }));
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    await chooseGang();
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.new') }));
    expect(screen.queryByRole('button', { name: t('action.i02.removeRow') })).toBeNull();
    await userEvent.click(
      within(screen.getByTestId('i02-capture')).getByRole('button', { name: t('action.i02.addRow') }),
    );
    const [first, second] = screen.getAllByRole('button', { name: t('action.i02.removeRow') });
    expect(second).toBeEnabled();
    expect(first).toBeEnabled();
    await userEvent.selectOptions(screen.getAllByLabelText(label('i02.operation'))[0] as HTMLElement, '1');
    expect(first).toBeDisabled();
    expect(first?.getAttribute('title')).toBe(t('i02.why.rowNotEmpty'));
    await userEvent.click(second as HTMLElement);
    expect(screen.getAllByLabelText(label('i02.operation'))).toHaveLength(1);
    expect(screen.queryByRole('button', { name: t('action.i02.removeRow') })).toBeNull();
  });
});

describe('I-02 group g5 fixes', () => {
  // DPS register l.4669 / l.4644 / l.4650 (UI #62, #63); SRS-2-29: a returned month shows the reason.
  it('l.4669: the DPS register names who entered and approved a slip and a returned slip shows its reason', async () => {
    await open(
      month({
        payrollType: 'DPS_LABOUR',
        batchState: 'RETURNED',
        returnReason: 'Recheck day 4',
        slips: [
          {
            ...SLIP,
            status: 'APPROVED',
            enteredBy: 'ldh.depot@fci-demo.test',
            decidedBy: 'dm.okhla@fci-demo.test',
            decidedAt: '2026-09-02T06:30:00Z',
          } as SlipMonth['slips'][number],
          {
            ...SLIP,
            id: 71,
            number: 'WS-5-20260902-1',
            status: 'REJECTED',
            enteredBy: 'ldh.depot@fci-demo.test',
            decidedBy: 'dm.okhla@fci-demo.test',
            returnReason: 'Bag count differs',
          } as unknown as SlipMonth['slips'][number],
        ],
      }),
    );
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    const gang = screen.getByLabelText(label('m09.gang'), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    fireEvent.change(screen.getByLabelText(label('i01.month'), { selector: 'input' }), {
      target: { value: '2026-09' },
    });
    const table = await screen.findByRole('table', { name: t('i02.bookingRegister') });
    expect(within(table).getByText(t('i02.enteredBy'))).toBeTruthy();
    expect(within(table).getAllByText('ldh.depot@fci-demo.test')).toHaveLength(2);
    expect(within(table).getAllByText('dm.okhla@fci-demo.test')).toHaveLength(2);
    expect(within(table).getByText(/02 Sept? 2026, 12:00/)).toBeTruthy();
    expect(within(table).getByText(t('i02.returnedWith', ['Bag count differs']))).toBeTruthy();
    expect(screen.getByLabelText(t('i01.returnReason')).textContent).toBe(
      t('i01.returnedWith', ['Recheck day 4']),
    );
  });

  // SRS-5-13: the NWNP slip names its list "Labours"; I-02 l.4619: the date stays in the chosen month.
  it('SRS-5-13: an NWNP slip names its labour list Labours and keeps its date within the month', async () => {
    await open(month({ payrollType: 'NWNP_LABOUR' }));
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    await chooseGang();
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.new') }));
    expect(within(screen.getByTestId('i02-capture')).getByText(t('i02.labours'))).toBeTruthy();
    expect(screen.queryByText(t('i02.participants'))).toBeNull();
    const day = screen.getByLabelText(label('i02.date'), { selector: 'input' });
    expect(day.getAttribute('min')).toBe('2026-09-01');
    expect(day.getAttribute('max')).toBe('2026-09-30');
  });
});
