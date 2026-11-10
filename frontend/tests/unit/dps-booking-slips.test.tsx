import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkSlips } from '@/modules/labour';
import type { SlipMonth } from '@/modules/labour/api/labourApi';
import { slipEditable, slipStatusKey } from '@/modules/labour/business/labourRules';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * I-02 DPS booking-slip register (plan 21 phase 21b; SRS-2-34, SRS-4-13, D21-3; CF-40): the slips
 * of a DPS gang are its booking-slips; a draft or returned one is sent to the Depot Manager, the
 * statuses read With the Depot Manager / Returned, and the month waits for every approval.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const GANG = {
  gangId: 9,
  gangCode: 'DP-DL01-G003',
  gangName: 'Okhla DPS Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DPS_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  sanctionedStrength: 6,
  members: 4,
  effectiveFrom: '2025-07-01',
};

function slip(id: number, status: string) {
  return {
    id,
    number: `WS-9-2026090${id}-1`,
    date: `2026-09-0${id}`,
    status,
    continueWork: false,
    holiday: false,
    labourStrength: 4,
    amendmentOf: null,
    rows: [
      {
        order: 1,
        operation: 'W2P - Wagon-to-Platform',
        bagType: 'KATTA50',
        bags: 400,
        stackHeight: null,
        heightBand: null,
        start: '09:00',
        end: '12:00',
        pbnwReason: null,
      },
    ],
    participants: [],
  };
}

const VIEW = {
  gangId: 9,
  gangName: 'Okhla DPS Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DPS_LABOUR',
  yymm: '202609',
  batchState: 'OPEN',
  approvalId: null,
  operations: [],
  bagTypes: [],
  slips: [slip(1, 'DRAFT'), slip(2, 'REJECTED'), slip(3, 'SUBMITTED'), slip(4, 'APPROVED')],
} as unknown as SlipMonth;

async function open() {
  const gang = screen.getByLabelText(label('m09.gang'), { selector: 'select' });
  await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G003 - Okhla DPS Gang 1')).toBeTruthy());
  await userEvent.selectOptions(gang, '9');
  fireEvent.change(screen.getByLabelText(label('i01.month'), { selector: 'input' }), {
    target: { value: '2026-09' },
  });
  return screen.findByRole('table', { name: t('i02.bookingRegister') });
}

describe('DPS booking-slip rules', () => {
  it('SRS-2-34: a draft or a returned booking-slip is corrected and sent; DPS states read for the Depot Manager', () => {
    expect(slipEditable('DRAFT', 'DPS_LABOUR')).toBe(true);
    expect(slipEditable('REJECTED', 'DPS_LABOUR')).toBe(true);
    expect(slipEditable('SUBMITTED', 'DPS_LABOUR')).toBe(false);
    expect(slipEditable('REJECTED', 'DEPT_LABOUR')).toBe(false);
    expect(slipEditable('DRAFT', 'DEPT_LABOUR')).toBe(true);
    expect(slipStatusKey('SUBMITTED', 'DPS_LABOUR')).toBe('i02.dpsStatus.SUBMITTED');
    expect(slipStatusKey('APPROVED', 'DPS_LABOUR')).toBe('i02.status.APPROVED');
    expect(slipStatusKey('SUBMITTED', 'DEPT_LABOUR')).toBe('i02.status.SUBMITTED');
  });
});

describe('I-02 DPS booking-slip register', () => {
  it('SRS-2-34: the Labour Dealing Hand sends a draft and a returned booking-slip to the Depot Manager', async () => {
    const calls = backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/9/members': () => [],
      'GET /v1/labour/gangs/9/work-slips?': () => VIEW,
      'POST /v1/labour/work-slips/1/submissions': () => ({
        status: 202,
        body: { id: 70, status: 'PENDING_APPROVAL' },
      }),
      'POST /v1/labour/work-slips/2/submissions': () =>
        problem(409, 'LBR-409-STATE', ['WS-9-20260902-1', 'SUBMITTED']),
      'POST /v1/labour/gangs/9/work-slip-batches': () =>
        problem(409, 'LBR-409-BOOKING-PENDING', [3, 'WS-9-20260901-1, WS-9-20260902-1, WS-9-20260903-1']),
    });
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    const register = await open();
    const rowOf = (n: number) =>
      within(register).getByText(`WS-9-2026090${n}-1`).closest('tr') as HTMLElement;
    expect(within(rowOf(1)).getByText(t('i02.dpsStatus.DRAFT'))).toBeTruthy();
    expect(within(rowOf(2)).getByText(t('i02.dpsStatus.REJECTED'))).toBeTruthy();
    expect(within(rowOf(3)).getByText(t('i02.dpsStatus.SUBMITTED'))).toBeTruthy();
    expect(within(rowOf(4)).getByText(t('i02.status.APPROVED'))).toBeTruthy();
    expect(within(rowOf(3)).queryByRole('button', { name: t('action.i02.sendSlip') })).toBeNull();
    expect(within(rowOf(4)).queryByRole('button')).toBeNull();
    await userEvent.click(within(rowOf(1)).getByRole('button', { name: t('action.i02.sendSlip') }));
    expect(await screen.findByText(t('i02.slipSent', ['WS-9-20260901-1']))).toBeTruthy();
    expect(sent(calls, 'POST', '/work-slips/1/submissions')).toHaveLength(1);
    await userEvent.click(within(rowOf(2)).getByRole('button', { name: t('action.i02.sendSlip') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.submit') }));
    expect(
      await screen.findByText(
        t('error.LBR-409-BOOKING-PENDING', [3, 'WS-9-20260901-1, WS-9-20260902-1, WS-9-20260903-1']),
      ),
    ).toBeTruthy();
  });

  it('SRS-G-1: without SUBMIT the booking-slip is not sent', async () => {
    backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/9/members': () => [],
      'GET /v1/labour/gangs/9/work-slips?': () => VIEW,
    });
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:EDIT']));
    const register = await open();
    const send = within(within(register).getByText('WS-9-20260901-1').closest('tr') as HTMLElement).getByRole(
      'button',
      { name: t('action.i02.sendSlip') },
    );
    expect(send).toBeDisabled();
    expect(send.getAttribute('title')).toBe(t('reason.ldhOnly'));
  });
});
