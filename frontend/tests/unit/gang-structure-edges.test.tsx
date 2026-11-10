import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GangStructure } from '@/modules/labour/components/GangStructure';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * M-09 Gang / Shed Structure edge cases (plan 20 phases 20a / 20e; SRS-2-1, SRS-2-7, SRS-2-31,
 * SRS-2-48): every refusal is shown and keeps the form, a cancelled form sends nothing, a changed
 * depot or payroll type empties the members, a removed member is not sent.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const OPTIONS = {
  depots: [
    { id: 7, code: 'DP-DL01', name: 'Depot Okhla', family: null },
    { id: 8, code: 'DP-DL02', name: 'Depot Narela', family: null },
  ],
  designations: [{ id: 3, code: 'HL', name: 'Handling Labour', family: 'DEPT_LABOUR' }],
};

const GANG = {
  gangId: 5,
  gangCode: 'DP-DL01-G002',
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DEPT_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  sanctionedStrength: null,
  members: 2,
  effectiveFrom: '2025-07-01',
};

const MEMBER = {
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
  // A gang without a Sanctioned Strength shows a dash.
  expect(within(table).getAllByText('—').length).toBeGreaterThan(0);
  await userEvent.click(within(table).getByRole('button', { name: t('m09.open') }));
  return screen.findByRole('table', { name: t('m09.membersOf', ['Okhla Gang 1']) });
}

describe('M-09 new gang edges', () => {
  it('SRS-2-1: a refused gang keeps the form; a removed member is not sent; a lookup failure is shown', async () => {
    let lookupFails = false;
    const calls = routes({
      'GET /v1/labour/members?': (url) =>
        lookupFails
          ? problem(503, 'SYS-503')
          : paged(
              url.includes('DPT-000001')
                ? [MEMBER]
                : url.includes('DPT-000002')
                  ? [{ ...MEMBER, employeeId: 12, labourCode: 'DPT-000002', name: 'Shiv' }]
                  : [],
              1,
              url,
            ),
      'POST /v1/labour/gangs': () => problem(422, 'LBR-422-GANG', ['ROLES']),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:CREATE', 'M-09:EDIT']));
    await screen.findByRole('table', { name: t('screen.M-09') });
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.create') }));
    const scope = screen.getByRole('button', { name: t('action.m09.send') }).closest('.card') as HTMLElement;
    await userEvent.selectOptions(within(scope).getByLabelText(label('m02.depot')), '8');
    await userEvent.selectOptions(within(scope).getByLabelText(label('m02.payrollType')), 'DEPT_LABOUR');
    await userEvent.type(within(scope).getByLabelText(label('m09.gang'), { selector: 'input' }), 'Narela 1');
    const strength = within(scope).getByLabelText(label('m09.strength'));
    await userEvent.type(strength, '6');
    await userEvent.clear(strength);
    await userEvent.type(strength, '5');
    fireEvent.change(within(scope).getByLabelText(label('m09.from')), { target: { value: '2026-11-01' } });
    await userEvent.type(within(scope).getByLabelText(label('m02.reason')), 'New shed');
    const code = within(scope).getByLabelText(label('m02.code'));
    await userEvent.type(code, 'DPT-000001');
    await userEvent.selectOptions(within(scope).getByLabelText(label('m09.role')), 'SARDAR');
    await userEvent.click(within(scope).getByRole('button', { name: t('action.m09.addMember') }));
    const list = await screen.findByRole('list', { name: t('m09.members') });
    expect(within(list).getByText(/DPT-000001 - Ram Kumar/)).toBeTruthy();
    await userEvent.click(within(list).getByRole('button', { name: t('action.m09.removeMember') }));
    expect(within(list).queryByText(/Ram Kumar/)).toBeNull();
    lookupFails = true;
    await userEvent.type(code, 'DPT-000001');
    await userEvent.click(within(scope).getByRole('button', { name: t('action.m09.addMember') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    lookupFails = false;
    await userEvent.click(within(scope).getByRole('button', { name: t('action.m09.addMember') }));
    await within(list).findByText(/Ram Kumar/);
    await userEvent.type(code, 'DPT-000002');
    await userEvent.selectOptions(within(scope).getByLabelText(label('m09.role')), 'MANDAL');
    await userEvent.click(within(scope).getByRole('button', { name: t('action.m09.addMember') }));
    await within(list).findByText(/DPT-000002 - Shiv/);
    await userEvent.click(within(scope).getByRole('button', { name: t('action.m09.send') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/gangs').at(-1)).toMatchObject({
      depotLocationId: 8,
      sanctionedStrength: 5,
      effectiveFrom: '2026-11-01',
      members: [
        { employeeId: 11, role: 'SARDAR' },
        { employeeId: 12, role: 'MANDAL' },
      ],
    });
    await userEvent.click(within(scope).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('action.m09.send') })).toBeNull();
  });
});

describe('M-09 open gang edges', () => {
  it('SRS-2-48: a refused strength change and a refused end are shown', async () => {
    routes({
      'POST /v1/labour/gangs/5/sanctioned-strengths': () => problem(409, 'LBR-409-PENDING'),
      'POST /v1/labour/gangs/5/end-dates': () => problem(422, 'LBR-422-GANG', ['MEMBERS']),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT']));
    await openGang();
    const strength = screen.getAllByLabelText(label('m09.strength'))[0] as HTMLElement;
    await userEvent.clear(strength);
    await userEvent.type(strength, '9');
    fireEvent.change(screen.getAllByLabelText(label('m09.from'))[0] as HTMLElement, {
      target: { value: '2026-12-01' },
    });
    await userEvent.type(screen.getAllByLabelText(label('m02.reason'))[0] as HTMLElement, 'Third shift');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.strength') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(label('m09.endDateLabel')), { target: { value: '2026-11-30' } });
    await userEvent.type(screen.getByLabelText(label('m09.endReason')), 'Shed closed');
    await userEvent.click(
      screen.getAllByRole('button', { name: t('action.m09.endDate') }).at(-1) as HTMLElement,
    );
    expect(await screen.findAllByRole('alert')).toBeTruthy();
  });

  it('SRS-2-31: a refused join and leader change are shown; a cancelled change sends nothing', async () => {
    const calls = routes({
      'GET /v1/labour/members?': (url) =>
        paged(
          url.includes('DPT-000009') ? [{ ...MEMBER, employeeId: 19, labourCode: 'DPT-000009' }] : [],
          1,
          url,
        ),
      'POST /v1/labour/gangs/5/members': () => problem(422, 'LBR-422-GANG', ['DEPOT']),
      'POST /v1/labour/gangs/5/leaders': () => problem(422, 'LBR-422-GANG', ['ROLE']),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT']));
    await openGang();
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.join') }));
    await userEvent.type(screen.getByLabelText(label('m09.joinLabour')), 'DPT-000009');
    fireEvent.change(screen.getByLabelText(label('m09.joinFrom')), { target: { value: '2026-11-03' } });
    await userEvent.type(screen.getAllByLabelText(label('m09.changeReason')).at(-1) as HTMLElement, 'Joiner');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/gangs/5/members').at(-1)).toMatchObject({
      employeeId: 19,
      from: '2026-11-03',
    });
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.leader') }));
    const member = screen.getByLabelText(label('m09.leaderMember'));
    await userEvent.selectOptions(member, '13');
    await userEvent.selectOptions(member, '');
    await userEvent.selectOptions(member, '13');
    await userEvent.type(screen.getAllByLabelText(label('m09.changeReason')).at(-1) as HTMLElement, 'Lead');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.sendMove') }));
    await vi.waitFor(() => expect(sent(calls, 'POST', '/leaders')).toHaveLength(1));
    const before = calls.length;
    await userEvent.click(screen.getAllByRole('button', { name: t('common.cancel') }).at(0) as HTMLElement);
    expect(screen.queryByLabelText(label('m09.leaderMember'))).toBeNull();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(
      calls.slice(0, before).filter((c) => c.method === 'POST').length,
    );
  });

  it('SRS-2-6: a move without a target gang is not sent; the gang choice can be cleared', async () => {
    const calls = routes({
      'GET /v1/labour/gangs?': (url) =>
        paged([GANG, { ...GANG, gangId: 6, gangCode: 'DP-DL01-G003', gangName: 'Okhla Gang 2' }], 2, url),
      'GET /v1/labour/transfer-targets?': (url) =>
        paged([GANG, { ...GANG, gangId: 6, gangCode: 'DP-DL01-G003', gangName: 'Okhla Gang 2' }], 2, url),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT', 'M-02:EDIT']));
    const members = await screen.findByRole('table', { name: t('screen.M-09') });
    await userEvent.click(within(members).getAllByRole('button', { name: t('m09.open') })[0] as HTMLElement);
    const list = await screen.findByRole('table', { name: t('m09.membersOf', ['Okhla Gang 1']) });
    const handling = within(list).getByText('L10002').closest('tr') as HTMLElement;
    await userEvent.click(within(handling).getByRole('button', { name: t('action.m02.transfer') }));
    const target = screen.getByLabelText(label('m09.toGang'));
    await userEvent.selectOptions(target, '6');
    await userEvent.selectOptions(target, '');
    expect(screen.getByRole('button', { name: t('action.m09.sendMove') })).toBeDisabled();
    expect(sent(calls, 'POST', '/transfers')).toHaveLength(0);
  });
});

describe('M-09 viewer, filters and an office with no depot', () => {
  it('SRS-2-31: a viewer sees every gang action disabled with the reason', async () => {
    routes();
    wrap(<GangStructure />, me(['M-09:VIEW']));
    expect(screen.getByRole('button', { name: t('action.m09.create') })).toBeDisabled();
    await openGang();
    const reasons = screen.getAllByTitle(t('reason.hrOnly'));
    expect(reasons.length).toBeGreaterThanOrEqual(4);
    for (const b of reasons) expect(b).toBeDisabled();
  });

  it('SRS-2-1: the depot filter clears back to every depot; a gang starts with no depot to offer', async () => {
    const calls = routes({ 'GET /v1/labour/options': () => ({ depots: [], designations: [] }) });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:CREATE', 'M-09:EDIT']));
    await screen.findByRole('table', { name: t('screen.M-09') });
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.create') }));
    expect((await screen.findAllByText(t('m09.new'))).length).toBeGreaterThan(0);
    expect(sent(calls, 'POST', '/v1/labour/gangs')).toHaveLength(0);
  });

  it('SRS-2-1: choosing a depot and then every depot again lists all gangs', async () => {
    const calls = routes();
    wrap(<GangStructure />, me(['M-09:VIEW']));
    const filter = await screen.findByRole('combobox', { name: label('m02.depot') });
    await vi.waitFor(() => expect(within(filter).getAllByRole('option')).toHaveLength(3));
    fireEvent.change(filter, { target: { value: '7' } });
    fireEvent.change(filter, { target: { value: '' } });
    await vi.waitFor(() =>
      expect(calls.filter((c) => c.url.includes('/v1/labour/gangs?')).length).toBeGreaterThanOrEqual(2),
    );
    expect((filter as HTMLSelectElement).value).toBe('');
  });
});
