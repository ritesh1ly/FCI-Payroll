import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GangStructure } from '@/modules/labour/components/GangStructure';
import { LabourMaster } from '@/modules/labour/components/LabourMaster';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/** M-02 separation and Light Duty, M-09 join and leader change (plan 20 phase 20e; SRS-2-1, SRS-2-30, SRS-2-49). */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const OPTIONS = {
  depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla', family: null }],
  designations: [
    { id: 3, code: 'HL', name: 'Handling Labour', family: 'DEPT_LABOUR' },
    { id: 5, code: 'ANC', name: 'Ancillary Labour', family: 'DEPT_LABOUR' },
  ],
};

const ROW = {
  employeeId: 11,
  labourCode: 'DPT-000001',
  name: 'Ram Kumar',
  payrollType: 'DEPT_LABOUR',
  designation: 'HL - Handling Labour',
  depot: 'DP-DL01 - Depot Okhla',
  status: 'ACTIVE',
  joiningDate: '2010-07-01',
  retirementDate: null,
  panMasked: null,
  bankAccountMasked: null,
};

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

describe('M-02 separation and Light Duty', () => {
  it('SRS-2-30: HR separates a member with the type, date and reason', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged([ROW], 1, url),
      'POST /v1/labour/members/11/separations': () => ({
        status: 202,
        body: { id: 80, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.separate') }));
    expect(screen.getByText(t('m02.separation', ['DPT-000001']))).toBeTruthy();
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    expect(send.getAttribute('title')).toBe(t('m02.why.lifecycle'));
    await userEvent.selectOptions(screen.getByLabelText(label('m02.separationType')), 'DEATH');
    fireEvent.change(screen.getByLabelText(label('m02.separationDate')), { target: { value: '2026-11-01' } });
    await userEvent.type(screen.getByLabelText(label('m09.changeReason')), 'Death in service');
    // SRS l.4429 / l.4971: the separation is sent with its order.
    expect(send.getAttribute('title')).toBe(t('m02.why.lifecycle'));
    await userEvent.type(screen.getByLabelText(label('m02.orderReference')), 'SO/12');
    fireEvent.change(screen.getByLabelText(label('m02.orderDate')), { target: { value: '2026-10-30' } });
    await userEvent.type(screen.getByLabelText(label('m02.issuingAuthority')), 'Area Manager');
    await userEvent.click(send);
    expect(await screen.findByText(t('m02.lifecycleSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/separations').at(-1)).toMatchObject({
      type: 'DEATH',
      date: '2026-11-01',
      reason: 'Death in service',
      orderReference: 'SO/12',
      orderDate: '2026-10-30',
      issuingAuthority: 'Area Manager',
    });
  });

  it('SRS-2-49: Permanent Light Duty needs the order and, for Protected pay, the Basic kept', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged([ROW], 1, url),
      'POST /v1/labour/members/11/light-duty-orders': () => problem(422, 'LBR-422-ENTRY', ['NO_POOL']),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.lightDuty') }));
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    await userEvent.selectOptions(screen.getByLabelText(label('m02.toDesignation')), '5');
    await userEvent.selectOptions(screen.getByLabelText(label('m02.payOption')), 'PROTECTED_PAY');
    await userEvent.type(screen.getByLabelText(label('m02.orderReference')), 'MB/7');
    await userEvent.type(screen.getByLabelText(label('m02.issuingAuthority')), 'Medical Board');
    await userEvent.type(screen.getByLabelText(label('m09.changeReason')), 'Medical finding');
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByLabelText(label('m02.protectedBasic')), '21500');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/light-duty-orders').at(-1)).toMatchObject({
      toDesignationId: 5,
      payOption: 'PROTECTED_PAY',
      protectedBasic: 21500,
      orderReference: 'MB/7',
    });
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByText(t('m02.lightDutyOf', ['DPT-000001']))).toBeNull();
  });

  it('SRS-2-30: a viewer has no separation', async () => {
    backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged([ROW], 1, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    expect(within(table).getByRole('button', { name: t('action.m02.separate') })).toBeDisabled();
  });
});

describe('M-09 join and leader change', () => {
  function routes(extra: Record<string, (url: string) => unknown> = {}) {
    return backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/members': () => [
        { employeeId: 11, labourCode: 'L10701', name: 'Harpal Sandhu', role: 'SARDAR' },
        { employeeId: 13, labourCode: 'L10002', name: 'Bhola Prasad', role: 'HANDLING' },
      ],
      ...extra,
    });
  }

  async function openGang() {
    const table = await screen.findByRole('table', { name: t('screen.M-09') });
    await userEvent.click(within(table).getByRole('button', { name: t('m09.open') }));
    await screen.findByRole('table', { name: t('m09.membersOf', ['Okhla Gang 1']) });
  }

  it('SRS-2-31: HR adds a labour found by code; an unknown code is named', async () => {
    const calls = routes({
      'GET /v1/labour/members?': (url) =>
        paged(
          url.includes('DPT-000009') ? [{ ...ROW, employeeId: 19, labourCode: 'DPT-000009' }] : [],
          1,
          url,
        ),
      'POST /v1/labour/gangs/5/members': () => ({
        status: 202,
        body: { id: 81, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT']));
    await openGang();
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.join') }));
    const code = screen.getByLabelText(label('m09.joinLabour'));
    await userEvent.type(code, 'DPT-000404');
    await userEvent.type(
      screen.getAllByLabelText(label('m09.changeReason')).at(-1) as HTMLElement,
      'New joiner',
    );
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await screen.findByText(t('m09.notFound', ['DPT-000404']))).toBeTruthy();
    await userEvent.clear(code);
    await userEvent.type(code, 'dpt-000009');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await screen.findByText(t('m09.moveSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/gangs/5/members').at(-1)).toMatchObject({ employeeId: 19 });
  });

  it('SRS-2-1: HR makes a handling member the Mandal', async () => {
    const calls = routes({
      'POST /v1/labour/gangs/5/leaders': () => ({
        status: 202,
        body: { id: 82, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT']));
    await openGang();
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.leader') }));
    const member = screen.getByLabelText(label('m09.leaderMember'));
    expect(within(member).queryByText(/L10701/)).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(label('m09.leaderRole')), 'MANDAL');
    await userEvent.selectOptions(member, '13');
    await userEvent.type(
      screen.getAllByLabelText(label('m09.changeReason')).at(-1) as HTMLElement,
      'Promotion',
    );
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await screen.findByText(t('m09.moveSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/leaders').at(-1)).toMatchObject({ role: 'MANDAL', employeeId: 13 });
  });
});
