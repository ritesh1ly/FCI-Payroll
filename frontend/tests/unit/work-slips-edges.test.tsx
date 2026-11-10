import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkSlips } from '@/modules/labour';
import type { SlipMonth } from '@/modules/labour/api/labourApi';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * I-02 Work-Slip Capture edge cases (plan 20 phase 20c; SRS-2-4, SRS-2-15, SRS-2-29): a refused
 * save or cancellation is shown and keeps the slip, a cancelled form or reason sends nothing,
 * a cleared field is empty again, a slip without bags or strength still reads, a submitted batch
 * is confirmed.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

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
  labourStrength: null,
  amendmentOf: null,
  rows: [
    {
      order: 1,
      operation: 'PBNW - Present But No Work',
      bagType: null,
      bags: null,
      stackHeight: null,
      heightBand: null,
      start: '09:00',
      end: '11:00',
      pbnwReason: 'RAKE_DELAYED',
    },
  ],
  participants: [
    { employeeId: 11, labourCode: 'L10701', name: 'Harpal Sandhu', role: 'SARDAR', present: true },
  ],
};

const VIEW = {
  gangId: 5,
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DEPT_LABOUR',
  yymm: '202609',
  batchState: 'OPEN',
  approvalId: null,
  operations: [
    { id: 1, code: 'W2P', name: 'Wagon-to-Platform', bagBased: true, normType: 'HANDLING', pbnw: false },
    { id: 4, code: 'PBNW', name: 'Present But No Work', bagBased: false, normType: 'NONE', pbnw: true },
  ],
  bagTypes: [{ id: 2, code: 'KATTA50', name: 'Katta 50 kg', weightKg: 50, band: 'BAND_1' }],
  slips: [SLIP],
} as unknown as SlipMonth;

const MEMBERS = [
  { employeeId: 11, labourCode: 'L10701', name: 'Harpal Sandhu', role: 'SARDAR' },
  { employeeId: 12, labourCode: 'L10702', name: 'Mohan Rawat', role: 'MANDAL' },
];

function routes(extra: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
    'GET /v1/labour/gangs/5/members': () => MEMBERS,
    'GET /v1/labour/gangs/5/work-slips?': () => VIEW,
    ...extra,
  });
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

const ALL = ['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT'];

describe('I-02 edges', () => {
  it('SRS-2-4: a slip without bags or strength reads; a refused save keeps the form; cancel sends nothing', async () => {
    const calls = routes({
      'POST /v1/labour/gangs/5/work-slips': () => problem(422, 'LBR-422-WORK-SLIP', ['OVERLAP']),
    });
    wrap(<WorkSlips />, me(ALL));
    const table = await chooseGang();
    const row = within(table).getByText('WS-5-20260901-1').closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeTruthy();
    expect(within(row).getByText(new RegExp(t('i02.pbnw.RAKE_DELAYED')))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.new') }));
    fireEvent.change(screen.getByLabelText(label('i02.date'), { selector: 'input' }), {
      target: { value: '2026-09-02' },
    });
    const operation = screen.getByLabelText(label('i02.operation'));
    await userEvent.selectOptions(operation, '1');
    const bagType = screen.getByLabelText(label('i02.bagType'));
    await userEvent.selectOptions(bagType, '2');
    const bags = screen.getByLabelText(label('i02.bags'));
    await userEvent.type(bags, '50');
    await userEvent.clear(bags);
    expect((bags as HTMLInputElement).value).toBe('');
    await userEvent.type(bags, '400');
    const stack = screen.getByLabelText(label('i02.stack'));
    await userEvent.type(stack, '12');
    await userEvent.clear(stack);
    expect((stack as HTMLInputElement).value).toBe('');
    fireEvent.change(screen.getByLabelText(label('i02.start')), { target: { value: '09:00' } });
    fireEvent.change(screen.getByLabelText(label('i02.end')), { target: { value: '12:00' } });
    // Every member takes part by default; one is taken off and put back.
    const mohan = screen.getByLabelText(/L10702/) as HTMLInputElement;
    expect(mohan.checked).toBe(true);
    await userEvent.click(mohan);
    await userEvent.click(mohan);
    expect(mohan.checked).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.save') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/gangs/5/work-slips').at(-1)).toMatchObject({
      participants: [11, 12],
      rows: [{ operationId: 1, bagTypeId: 2, bags: 400, stackHeight: null }],
    });
    expect(screen.getByRole('button', { name: t('action.i02.save') })).toBeTruthy();
    // Clearing the operation and bag type empties them again.
    await userEvent.selectOptions(bagType, '');
    await userEvent.selectOptions(operation, '');
    expect((operation as HTMLSelectElement).value).toBe('');
    await userEvent.click(screen.getAllByRole('button', { name: t('common.cancel') }).at(-1) as HTMLElement);
    expect(screen.queryByRole('button', { name: t('action.i02.save') })).toBeNull();
  });

  it('SRS-2-15: a cancelled reason sends nothing; a refused cancellation is shown', async () => {
    const calls = routes({
      'POST /v1/labour/work-slips/70/cancellations': () => problem(409, 'LBR-409-WORK-SLIP'),
    });
    wrap(<WorkSlips />, me(ALL));
    const table = await chooseGang();
    await userEvent.click(within(table).getByRole('button', { name: t('action.i02.cancel') }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/cancellations')).toHaveLength(0);
    await userEvent.click(within(table).getByRole('button', { name: t('action.i02.cancel') }));
    const again = await screen.findByRole('dialog');
    await userEvent.type(within(again).getByRole('textbox'), 'Wrong gang');
    await userEvent.click(within(again).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/cancellations').at(-1)).toMatchObject({ reason: 'Wrong gang' });
  });

  it('SRS-2-29: the batch is submitted; last month and no gang are offered', async () => {
    const calls = routes({
      'POST /v1/labour/gangs/5/work-slip-batches': () => ({
        status: 202,
        body: { id: 90, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<WorkSlips />, me(ALL));
    await chooseGang();
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.submit') }));
    expect(await screen.findByText(t('i01.submitted'))).toBeTruthy();
    expect(sent(calls, 'POST', '/work-slip-batches').at(-1)).toMatchObject({ month: '2026-09' });
    const month = screen.getByLabelText(label('i01.month'), { selector: 'input' }) as HTMLInputElement;
    fireEvent.change(month, { target: { value: '2026-01' } });
    await userEvent.click(screen.getByRole('button', { name: t('i01.lastMonth') }));
    expect(month.value).toMatch(/^\d{4}-\d{2}$/);
    expect(month.value).not.toBe('2026-01');
    await userEvent.selectOptions(screen.getByLabelText(label('m09.gang'), { selector: 'select' }), '');
    expect(screen.queryByRole('table', { name: t('i02.slips') })).toBeNull();
  });
});
