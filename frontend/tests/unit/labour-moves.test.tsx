import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GangStructure } from '@/modules/labour/components/GangStructure';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/** M-09 transfer, attachment and gang end-date (plan 20 phase 20e; SRS-2-6, SRS-2-7, SRS-2-47). */

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
  members: 3,
  effectiveFrom: '2025-07-01',
};
const OTHER = { ...GANG, gangId: 6, gangCode: 'DP-DL01-G003', gangName: 'Okhla Gang 2' };

function routes(extra: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/labour/options': () => ({
      depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla', family: null }],
      designations: [],
    }),
    'GET /v1/labour/gangs?': (url) => paged([GANG, OTHER], 2, url),
    // SRS-2-6 / OD-88: the receiving gang of a transfer is searched in every office.
    'GET /v1/labour/transfer-targets?': (url) =>
      paged([GANG, { ...OTHER, depot: 'DP-PB01 - Depot Khanna' }], 2, url),
    'GET /v1/labour/gangs/5/members': () => [
      { employeeId: 11, labourCode: 'L10701', name: 'Harpal Sandhu', role: 'SARDAR' },
      { employeeId: 13, labourCode: 'L10002', name: 'Bhola Prasad', role: 'HANDLING' },
    ],
    ...extra,
  });
}

async function openGang() {
  const table = await screen.findByRole('table', { name: t('screen.M-09') });
  await userEvent.click(within(table).getAllByRole('button', { name: t('m09.open') })[0] as HTMLElement);
  return screen.findByRole('table', { name: t('m09.membersOf', ['Okhla Gang 1']) });
}

describe('M-09 moves', () => {
  it('SRS-2-6: HR transfers a handling member; a Sardar has no move', async () => {
    const calls = routes({
      'POST /v1/labour/members/13/transfers': () => ({
        status: 202,
        body: { id: 70, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT', 'M-02:EDIT']));
    const members = await openGang();
    const sardar = within(members).getByText('L10701').closest('tr') as HTMLElement;
    expect(within(sardar).queryByRole('button', { name: t('action.m02.transfer') })).toBeNull();
    const handling = within(members).getByText('L10002').closest('tr') as HTMLElement;
    await userEvent.click(within(handling).getByRole('button', { name: t('action.m02.transfer') }));
    expect(screen.getByText(t('m09.move.TRANSFER', ['L10002']))).toBeTruthy();
    const send = screen.getByRole('button', { name: t('action.m09.sendMove') });
    expect(send.getAttribute('title')).toBe(t('m09.why.move'));
    await userEvent.selectOptions(screen.getByLabelText(label('m09.toGang')), '6');
    fireEvent.change(screen.getByLabelText(label('m09.transferDate')), {
      target: { value: '2026-11-02' },
    });
    expect(screen.getByRole('option', { name: /Okhla Gang 2 · DP-PB01 - Depot Khanna/ })).toBeTruthy();
    await userEvent.type(screen.getByLabelText(label('m09.moveReason')), 'Order 7/2026');
    // SRS l.4429: the transfer is sent with its order.
    expect(send.getAttribute('title')).toBe(t('m02.why.order'));
    await userEvent.type(screen.getByLabelText(label('m02.orderReference')), 'TO/7/2026');
    fireEvent.change(screen.getByLabelText(label('m02.orderDate')), { target: { value: '2099-01-01' } });
    await userEvent.type(screen.getByLabelText(label('m02.issuingAuthority')), 'Area Manager');
    expect(send.getAttribute('title')).toBe(t('m02.why.orderDate'));
    fireEvent.change(screen.getByLabelText(label('m02.orderDate')), { target: { value: '2026-10-01' } });
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m09.moveSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/transfers').at(-1)).toMatchObject({
      toGangId: 6,
      transferDate: '2026-11-02',
      reason: 'Order 7/2026',
      orderReference: 'TO/7/2026',
      orderDate: '2026-10-01',
      issuingAuthority: 'Area Manager',
    });
  });

  it('SRS-2-47: HR attaches a member for dates; a refusal is shown', async () => {
    const calls = routes({
      'POST /v1/labour/members/13/attachments': () => problem(422, 'LBR-422-TRANSFER', ['OVERLAP']),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT']));
    const members = await openGang();
    const handling = within(members).getByText('L10002').closest('tr') as HTMLElement;
    expect(within(handling).getByRole('button', { name: t('action.m02.transfer') })).toBeDisabled();
    await userEvent.click(within(handling).getByRole('button', { name: t('action.m09.attach') }));
    await userEvent.selectOptions(screen.getByLabelText(label('m09.toGang')), '6');
    fireEvent.change(screen.getByLabelText(label('m09.attachTo')), {
      target: { value: '2026-11-05' },
    });
    await userEvent.type(screen.getByLabelText(label('m09.moveReason')), 'Rake surge');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/attachments').at(-1)).toMatchObject({ toGangId: 6, to: '2026-11-05' });
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('action.m09.sendMove') })).toBeNull();
  });

  it('SRS-2-7: HR ends the gang at a date', async () => {
    const calls = routes({
      'POST /v1/labour/gangs/5/end-dates': () => ({
        status: 202,
        body: { id: 71, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT']));
    await openGang();
    const end = screen.getAllByRole('button', { name: t('action.m09.endDate') }).at(-1) as HTMLElement;
    expect(end).toBeDisabled();
    fireEvent.change(screen.getByLabelText(label('m09.endDateLabel')), {
      target: { value: '2026-11-30' },
    });
    await userEvent.type(screen.getByLabelText(label('m09.endReason')), 'Shed closed');
    expect(end).toBeEnabled();
    await userEvent.click(end);
    expect(await screen.findByText(t('m09.moveSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/end-dates').at(-1)).toMatchObject({
      endDate: '2026-11-30',
      reason: 'Shed closed',
    });
  });
});
