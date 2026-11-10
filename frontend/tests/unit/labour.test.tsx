import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GangStructure } from '@/modules/labour/components/GangStructure';
import { LabourMaster } from '@/modules/labour/components/LabourMaster';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/** M-02 Labour Master and M-09 Gang / Shed Structure (plan 20 phase 20a; SRS-2-1, SRS-2-30, SRS-2-48). */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const OPTIONS = {
  depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla', family: null }],
  designations: [
    { id: 3, code: 'HL', name: 'Handling Labour', family: 'DEPT_LABOUR' },
    { id: 4, code: 'SDR', name: 'Sardar', family: 'DEPT_LABOUR' },
    { id: 9, code: 'DPS1', name: 'DPS Handler', family: 'DPS_LABOUR' },
  ],
};

const MEMBERS = [
  {
    employeeId: 11,
    labourCode: 'DPT-000001',
    name: 'Ram Kumar',
    payrollType: 'DEPT_LABOUR',
    designation: 'HL - Handling Labour',
    depot: 'DP-DL01 - Depot Okhla',
    status: 'ACTIVE',
    joiningDate: '2010-07-01',
    retirementDate: null,
    panMasked: 'XXXXXX234D',
    bankAccountMasked: 'XXXX5678',
  },
];

const DETAIL = {
  employeeId: 11,
  labourCode: 'DPT-000001',
  payrollType: 'DEPT_LABOUR',
  firstName: 'Ram',
  lastName: 'Kumar',
  fatherName: 'Shyam Lal',
  gender: 'M',
  birthDate: '1980-02-01',
  joiningDate: '2010-07-01',
  designationId: 3,
  depotLocationId: 7,
  panMasked: '******234K',
  ifsc: 'SBIN0DEMO01',
  bankAccountMasked: '******5678',
  mobileMasked: '******0001',
  pfApplicable: true,
  cpfCode: 'DL/77',
  uanNumber: '100200300400',
  casualLabour: false,
};

const GANGS = [
  {
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
  },
];

describe('M-02 Labour Master', () => {
  it('SRS-2-30: HR records a new member, sent to the Payroll Checker', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) =>
        paged(
          [
            ...MEMBERS,
            { ...MEMBERS[0], employeeId: 13, labourCode: 'DPT-000003', retirementDate: '2050-06-30' },
          ],
          2,
          url,
        ),
      'POST /v1/labour/members': () => ({ status: 202, body: { id: 41, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:CREATE', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    expect(within(table).getByText('DPT-000001')).toBeTruthy();
    expect(within(table).getByText(t('m02.retirementNotSet'))).toBeTruthy();
    // A member whose retirement date is known shows it.
    const retiring = within(table).getByText('DPT-000003').closest('tr') as HTMLElement;
    expect(within(retiring).queryByText(t('m02.retirementNotSet'))).toBeNull();
    expect(within(table).getAllByText(t('labour.status.ACTIVE'))).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.create') }));
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    expect(send).toBeDisabled();
    expect(send.getAttribute('title')).toBe(t('m02.why.name'));
    await userEvent.type(screen.getByLabelText(label('m02.firstName')), 'Shiv');
    await userEvent.type(screen.getByLabelText(label('m02.birthDate')), '1990-05-10');
    await userEvent.type(screen.getByLabelText(label('m02.joiningDate')), '2010-07-01');
    const designation = screen.getByLabelText(label('m02.designation'));
    expect(within(designation).queryByText('DPS1 - DPS Handler')).toBeNull();
    await userEvent.selectOptions(designation, '4');
    await userEvent.selectOptions(screen.getByLabelText(label('m02.depot')), '7');
    await userEvent.type(screen.getByLabelText(label('m02.ifsc')), 'sbin0demo01');
    await userEvent.type(screen.getByLabelText(label('m02.bank')), '0000092001');
    await userEvent.click(screen.getByLabelText(label('m02.pfApplicable')));
    expect(send.getAttribute('title')).toBe(t('m02.why.cpf'));
    await userEvent.type(screen.getByLabelText(label('m02.cpfCode')), 'dl/77');
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Appointment order');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m02.sent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/members').at(-1)).toMatchObject({
      firstName: 'Shiv',
      designationId: 4,
      depotLocationId: 7,
      ifsc: 'SBIN0DEMO01',
      pfApplicable: true,
      cpfCode: 'DL/77',
    });
  });

  it('SRS-2-30: an amendment opens with the record as it stands and sends only the change', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged(MEMBERS, 1, url),
      'GET /v1/labour/members/11': () => DETAIL,
      'POST /v1/labour/members/11/amendments': () => problem(409, 'LBR-409-DUPLICATE', ['PAN']),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    expect(screen.getByRole('button', { name: t('action.m02.create') })).toBeDisabled();
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.amend') }));
    expect(await screen.findByText(t('m02.amendOf', ['DPT-000001']))).toBeTruthy();
    const value = (key: string) => (screen.getByLabelText(label(key)) as HTMLInputElement).value;
    expect(value('m02.firstName')).toBe('Ram');
    expect(value('m02.fatherName')).toBe('Shyam Lal');
    expect(value('m02.gender')).toBe('M');
    expect(value('m02.birthDate')).toBe('1980-02-01');
    expect(value('m02.designation')).toBe('3');
    expect(value('m02.depot')).toBe('7');
    expect(value('m02.ifsc')).toBe('SBIN0DEMO01');
    expect(value('m02.cpfCode')).toBe('DL/77');
    expect(value('m02.uan')).toBe('100200300400');
    expect((screen.getByLabelText(label('m02.pfApplicable')) as HTMLInputElement).checked).toBe(true);
    // R-PII: the masked values are shown as hints, the inputs stay empty (kept when left empty).
    expect(value('m02.pan')).toBe('');
    expect(screen.getByText(t('m02.keepMasked', ['******234K']))).toBeTruthy();
    expect(screen.getByText(t('m02.keepMasked', ['******5678']))).toBeTruthy();
    expect(screen.getAllByLabelText(label('m02.payrollType'))[1]).toBeDisabled();
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'New account per bank letter');
    // Nothing changed yet: nothing to send.
    expect(send).toBeDisabled();
    expect(send.getAttribute('title')).toBe(t('m02.why.noChange'));
    await userEvent.type(screen.getByLabelText(label('m02.bank')), '0000092001');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByRole('alert')).toBeTruthy();
    // Only the account and the reason: PAN, father, gender, mobile, PF, CPF code and UAN are kept.
    expect(sent(calls, 'POST', '/amendments')).toEqual([
      { bankAccount: '0000092001', reason: 'New account per bank letter' },
    ]);
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('action.m02.send') })).toBeNull();
  });

  it('SRS-2-30: a member that cannot be read opens no amendment form and shows why', async () => {
    backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged(MEMBERS, 1, url),
      'GET /v1/labour/members/11': () => problem(404, 'COM-404-NOT-FOUND', []),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.amend') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(t('m02.amendOf', ['DPT-000001']))).toBeNull();
  });

  it('SRS-2-30: the Payroll Maker views only and filters the list', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged(MEMBERS, 1, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    const amend = within(table).getByRole('button', { name: t('action.m02.amend') });
    expect(amend).toBeDisabled();
    expect(amend.getAttribute('title')).toBe(t('reason.hrOnly'));
    // SRS-20-29 (US-03-07 AC1): a viewer opens a member's change history.
    expect(
      within(table).getByRole('button', { name: t('history.for', ['DPT-000001 · Ram Kumar']) }),
    ).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(label('m02.payrollType')), 'DPS_LABOUR');
    await userEvent.type(screen.getByLabelText(label('m02.search')), 'ram');
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('payrollType=DPS_LABOUR'))).toBe(true));
    expect(calls.some((c) => c.url.includes('text=ram'))).toBe(true);
  });

  // US-GAP-312 (SRS l.4456): the status of each member shows in words on M-02, Separated included.
  it('SRS-2-30: a separated member shows its Separated status beside an active one', async () => {
    const separated = {
      ...MEMBERS[0],
      employeeId: 12,
      labourCode: 'NWN-000002',
      name: 'Mohan Lal',
      payrollType: 'NWNP_LABOUR',
      status: 'SEPARATED',
    };
    backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged([...MEMBERS, separated], 2, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    const rows = within(table).getAllByRole('row');
    const leaver = rows.find((r) => r.textContent?.includes('NWN-000002'));
    const active = rows.find((r) => r.textContent?.includes('DPT-000001'));
    expect(leaver?.textContent).toContain(t('labour.status.SEPARATED'));
    expect(active?.textContent).toContain(t('labour.status.ACTIVE'));
    expect(active?.textContent).not.toContain(t('labour.status.SEPARATED'));
  });

  it('SRS-2-40: the members are searched by gang and depot as well, within the scope', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged(MEMBERS, 1, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.type(screen.getByLabelText(label('m02.searchGang')), ' Okhla Gang ');
    const depot = screen.getByLabelText(label('m02.searchDepot'));
    const first = OPTIONS.depots[0] as { id: number };
    await userEvent.selectOptions(depot, String(first.id));
    await vi.waitFor(() =>
      expect(
        calls.some((c) => c.url.includes('gang=Okhla+Gang') && c.url.includes(`depot=${first.id}`)),
      ).toBe(true),
    );
    // The first page was read without either criterion.
    expect(calls.some((c) => c.url.includes('/v1/labour/members?') && !c.url.includes('gang='))).toBe(true);
  });
});

describe('M-09 Gang / Shed Structure', () => {
  it('SRS-2-1: HR forms a gang of one Sardar and one Mandal', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/gangs?': (url) => paged(GANGS, 1, url),
      'GET /v1/labour/members?': (url) =>
        paged(
          url.includes('DPT-000002')
            ? [{ ...MEMBERS[0], employeeId: 12, labourCode: 'DPT-000002', name: 'Shiv' }]
            : url.includes('DPT-000001')
              ? MEMBERS
              : [],
          1,
          url,
        ),
      'POST /v1/labour/gangs': () => ({ status: 202, body: { id: 42, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:CREATE', 'M-09:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-09') });
    expect(within(table).getByText('Okhla Gang 1')).toBeTruthy();
    expect(within(table).getByText(t('m09.type.HANDLING'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.create') }));
    const send = screen.getByRole('button', { name: t('action.m09.send') });
    await userEvent.type(screen.getByLabelText(label('m09.gang'), { selector: 'input' }), 'Okhla Gang 2');
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Shed 2');
    expect(send.getAttribute('title')).toBe(t('m09.why.roles'));
    // BR-LABM-011 / M-09 l.4498: the Sanctioned Strength is mandatory.
    fireEvent.change(screen.getByLabelText(label('m09.strength')), { target: { value: '6' } });
    const code = screen.getByLabelText(label('m02.code'));
    const role = screen.getByLabelText(label('m09.role'));
    await userEvent.type(code, 'dpt-000001');
    await userEvent.selectOptions(role, 'SARDAR');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.addMember') }));
    expect(await screen.findByText(/DPT-000001 - Ram Kumar/)).toBeTruthy();
    await userEvent.type(code, 'DPT-000009');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.addMember') }));
    expect(await screen.findByText(t('m09.notFound', ['DPT-000009']))).toBeTruthy();
    await userEvent.clear(code);
    await userEvent.type(code, 'DPT-000002');
    await userEvent.selectOptions(role, 'MANDAL');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.addMember') }));
    expect(await screen.findByText(/DPT-000002 - Shiv/)).toBeTruthy();
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m09.sent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/gangs').at(-1)).toMatchObject({
      depotLocationId: 7,
      gangName: 'Okhla Gang 2',
      members: [
        { employeeId: 11, role: 'SARDAR' },
        { employeeId: 12, role: 'MANDAL' },
      ],
    });
  });

  it('SRS-2-48: a gang shows its members and HR changes its Sanctioned Strength', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/gangs?': (url) => paged(GANGS, 1, url),
      'GET /v1/labour/gangs/5/members': () => [
        { employeeId: 11, labourCode: 'DPT-000001', name: 'Ram Kumar', role: 'SARDAR' },
      ],
      'POST /v1/labour/gangs/5/sanctioned-strengths': () => ({
        status: 202,
        body: { id: 43, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-09') });
    expect(screen.getByRole('button', { name: t('action.m09.create') })).toBeDisabled();
    // SRS-20-29 (US-03-07 AC1): each gang opens its change history.
    expect(
      within(table).getByRole('button', { name: t('history.for', ['DP-DL01-G002 · Okhla Gang 1']) }),
    ).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: t('m09.open') }));
    const members = await screen.findByRole('table', { name: t('m09.membersOf', ['Okhla Gang 1']) });
    expect(within(members).getByText(t('m09.role.SARDAR'))).toBeTruthy();
    const change = screen.getByRole('button', { name: t('action.m09.strength') });
    expect(change.getAttribute('title')).toBe(t('m09.why.reason'));
    const strength = screen.getAllByLabelText(label('m09.strength'))[0] as HTMLElement;
    await userEvent.clear(strength);
    await userEvent.type(strength, '8');
    await userEvent.type(screen.getAllByLabelText(label('m02.reason'))[0] as HTMLElement, 'Second shift');
    await userEvent.click(change);
    expect(await screen.findByText(t('m09.sent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/sanctioned-strengths').at(-1)).toMatchObject({
      strength: 8,
      reason: 'Second shift',
    });
    await userEvent.selectOptions(screen.getByLabelText(label('m02.depot')), '7');
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('depot=7'))).toBe(true));
  });

  it('SRS-2-1: a viewer cannot form a gang; a refused gang shows the reason', async () => {
    backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/gangs?': (url) => paged([], 0, url),
    });
    wrap(<GangStructure />, me(['M-09:VIEW']));
    expect(await screen.findByText(t('m09.none'))).toBeTruthy();
    const create = screen.getByRole('button', { name: t('action.m09.create') });
    expect(create).toBeDisabled();
    expect(create.getAttribute('title')).toBe(t('reason.hrOnly'));
  });
});

describe('M-02 entry edges', () => {
  it('SRS-2-30: every optional field is sent; a changed payroll type clears the designation; a cleared choice is empty', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged(MEMBERS, 1, url),
      'POST /v1/labour/members': () => ({ status: 202, body: { id: 41, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:CREATE', 'M-02:EDIT']));
    await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.create') }));
    const designation = screen.getByLabelText(label('m02.designation')) as HTMLSelectElement;
    await userEvent.selectOptions(designation, '4');
    await userEvent.selectOptions(designation, '');
    expect(designation.value).toBe('');
    await userEvent.selectOptions(designation, '3');
    await userEvent.selectOptions(
      screen.getAllByLabelText(label('m02.payrollType'))[1] as HTMLElement,
      'DPS_LABOUR',
    );
    expect(designation.value).toBe('');
    expect(within(designation).getByText('DPS1 - DPS Handler')).toBeTruthy();
    await userEvent.selectOptions(designation, '9');
    const depot = screen.getByLabelText(label('m02.depot')) as HTMLSelectElement;
    await userEvent.selectOptions(depot, '7');
    await userEvent.selectOptions(depot, '');
    expect(depot.value).toBe('');
    await userEvent.selectOptions(depot, '7');
    await userEvent.type(screen.getByLabelText(label('m02.firstName')), 'Geeta');
    await userEvent.type(screen.getByLabelText(label('m02.lastName')), 'Devi');
    await userEvent.type(screen.getByLabelText(label('m02.fatherName')), 'Ram Lal');
    await userEvent.selectOptions(screen.getByLabelText(label('m02.gender')), 'F');
    await userEvent.type(screen.getByLabelText(label('m02.birthDate')), '1992-03-04');
    await userEvent.type(screen.getByLabelText(label('m02.joiningDate')), '2015-01-01');
    await userEvent.type(screen.getByLabelText(label('m02.pan')), 'dmopd0001a');
    await userEvent.type(screen.getByLabelText(label('m02.mobile')), '9000000001');
    await userEvent.type(screen.getByLabelText(label('m02.uan')), '100000000001');
    await userEvent.type(screen.getByLabelText(label('m02.ifsc')), 'SBIN0DEMO01');
    await userEvent.type(screen.getByLabelText(label('m02.bank')), '0000092002');
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Appointment order');
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.send') }));
    expect(await screen.findByText(t('m02.sent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/members').at(-1)).toMatchObject({
      payrollType: 'DPS_LABOUR',
      designationId: 9,
      lastName: 'Devi',
      fatherName: 'Ram Lal',
      gender: 'F',
      mobile: '9000000001',
      uanNumber: '100000000001',
    });
  });
});
