import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourMaster } from '@/modules/labour/components/LabourMaster';
import { GangStrengthReport } from '@/modules/labour/components/GangStrengthReport';
import { HrmsFallback } from '@/modules/general';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * Group g5 screen fixes (S\GROUP_WORK_g5.md): the Gang Strength Report with the Depot Manager's
 * closure of a shortfall alert (SRS-2-48; OD-90), the transfer started on the M-02 row with its order
 * to a gang of any office (SRS l.4429; OD-88, OD-93) and the X-07 Submit reason (DoD 4).
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key)}`);

const REPORT = {
  gangId: 5,
  gangName: 'Okhla Gang 7',
  depot: 'DP-DL01 - Depot Okhla',
  yymm: '202609',
  days: [
    {
      date: '2026-09-09',
      sanctioned: 4,
      present: 4,
      shortBy: 0,
      absent: [],
      attached: [],
      alertOpen: false,
    },
    {
      date: '2026-09-10',
      sanctioned: 4,
      present: 3,
      shortBy: 1,
      absent: ['L50804'],
      attached: ['L10003'],
      alertOpen: true,
    },
    {
      date: '2026-09-11',
      sanctioned: 4,
      present: 3,
      shortBy: 1,
      absent: ['L50803'],
      attached: [],
      alertOpen: false,
    },
  ],
};

describe('M-09 Gang Strength Report', () => {
  // SC-20-0134: the shortfall alert of a day; only the Depot Manager closes it with a reason.
  it('SRS-2-48: the Depot Manager closes an open shortfall alert with a reason', async () => {
    const calls = backend({
      'GET /v1/labour/gangs/5/strength-report?': () => REPORT,
      'POST /v1/labour/gangs/5/shortfalls/2026-09-10/closures': () => ({ closed: 1 }),
    });
    wrap(<GangStrengthReport gangId={5} />, me(['M-09:VIEW', 'M-09:APPROVE']));
    const table = await screen.findByRole('table', { name: t('m09.strengthReport') });
    const open = within(table).getByText('L50804').closest('tr') as HTMLElement;
    expect(within(open).getByText('L10003')).toBeTruthy();
    expect(within(open).getByText(t('m09.alertOpen'))).toBeTruthy();
    const filled = within(table).getByText('L50803').closest('tr') as HTMLElement;
    expect(within(filled).getByText(t('m09.alertClosed'))).toBeTruthy();
    await userEvent.click(
      within(open).getByRole('button', { name: t('m09.closeShortfallOf', ['2026-09-10']) }),
    );
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Rake late; pool labour sent');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('m09.shortfallClosed'))).toBeTruthy();
    expect(sent(calls, 'POST', '/closures').at(-1)).toMatchObject({ reason: 'Rake late; pool labour sent' });
  });

  it('SRS-2-48: another role sees the closure disabled with the reason; a refusal stays in the dialog', async () => {
    backend({
      'GET /v1/labour/gangs/5/strength-report?': () => REPORT,
      'POST /v1/labour/gangs/5/shortfalls/2026-09-10/closures': () =>
        problem(422, 'LBR-422-ENTRY', ['NO_OPEN_SHORTFALL']),
    });
    const view = wrap(<GangStrengthReport gangId={5} />, me(['M-09:VIEW']));
    const table = await screen.findByRole('table', { name: t('m09.strengthReport') });
    const button = within(table).getByRole('button', { name: t('m09.closeShortfallOf', ['2026-09-10']) });
    expect(button).toBeDisabled();
    expect(button.getAttribute('title')).toBe(t('m09.why.closeShortfall'));
    view.unmount();
    wrap(<GangStrengthReport gangId={5} />, me(['M-09:VIEW', 'M-09:APPROVE']));
    const again = await screen.findByRole('table', { name: t('m09.strengthReport') });
    await userEvent.click(
      within(again).getByRole('button', { name: t('m09.closeShortfallOf', ['2026-09-10']) }),
    );
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Pool labour sent');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await within(dialog).findByText(/NO_OPEN_SHORTFALL|not valid/)).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-2-48: a month without marks says so and the month can be chosen', async () => {
    const calls = backend({
      'GET /v1/labour/gangs/5/strength-report?': () => ({ ...REPORT, days: [] }),
    });
    wrap(<GangStrengthReport gangId={5} />, me(['M-09:VIEW']));
    expect(await screen.findByText(t('m09.strengthNone'))).toBeTruthy();
    fireEvent.change(screen.getByLabelText(label('i01.month')), { target: { value: '2026-08' } });
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('month=2026-08'))).toBe(true));
  });
});

const ROW = {
  employeeId: 13,
  labourCode: 'L30703',
  name: 'Dinesh Mahto',
  payrollType: 'NWNP_LABOUR',
  designation: 'NHL - Handling Labour (NWNP)',
  depot: 'DP-DL01 - Depot Okhla',
  status: 'ACTIVE',
  joiningDate: '2011-07-01',
  retirementDate: null,
  panMasked: null,
  bankAccountMasked: null,
  casualLabour: false,
};

const TARGET = {
  gangId: 9,
  gangCode: 'DP-PB01-G001',
  gangName: 'Khanna NWNP Gang',
  depot: 'DP-PB01 - Depot Khanna',
  payrollType: 'NWNP_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  sanctionedStrength: 5,
  members: 5,
  effectiveFrom: '2025-07-01',
};

describe('M-02 transfer with its order', () => {
  it('SRS-2-6: HR starts a transfer on the M-02 row to a gang of another office with the order', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => ({ depots: [], designations: [] }),
      'GET /v1/labour/members?': (url) => paged([ROW], 1, url),
      'GET /v1/labour/transfer-targets?': (url) => paged([TARGET], 1, url),
      'POST /v1/labour/members/13/transfers': () => ({
        status: 202,
        body: { id: 81, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.transfer') }));
    const form = screen.getByLabelText(t('m09.move.TRANSFER', ['L30703']));
    await vi.waitFor(() =>
      expect(
        within(form).queryByText('DP-PB01-G001 - Khanna NWNP Gang · DP-PB01 - Depot Khanna'),
      ).toBeTruthy(),
    );
    expect(
      calls.some((c) => c.url.includes('/transfer-targets?') && c.url.includes('payrollType=NWNP_LABOUR')),
    ).toBe(true);
    await userEvent.selectOptions(
      within(form).getByLabelText(label('m09.toGang'), { selector: 'select' }),
      '9',
    );
    await userEvent.type(within(form).getByLabelText(label('m09.moveReason')), 'Order 9/2026');
    await userEvent.type(within(form).getByLabelText(label('m02.orderReference')), 'TO/9/2026');
    await userEvent.type(within(form).getByLabelText(label('m02.issuingAuthority')), 'Area Manager');
    fireEvent.change(within(form).getByLabelText(label('m02.orderDate')), {
      target: { value: '2026-10-01' },
    });
    await userEvent.click(within(form).getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await screen.findByText(t('m09.moveSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/transfers').at(-1)).toMatchObject({
      toGangId: 9,
      orderReference: 'TO/9/2026',
      orderDate: '2026-10-01',
      issuingAuthority: 'Area Manager',
      reason: 'Order 9/2026',
    });
    expect(screen.queryByLabelText(t('m09.move.TRANSFER', ['L30703']))).toBeNull();
  });

  it('SRS-2-6: a refused transfer is shown and the form can be cancelled', async () => {
    backend({
      'GET /v1/labour/options': () => ({ depots: [], designations: [] }),
      'GET /v1/labour/members?': (url) => paged([ROW], 1, url),
      'GET /v1/labour/transfer-targets?': (url) => paged([TARGET], 1, url),
      'POST /v1/labour/members/13/transfers': () => problem(422, 'LBR-422-TRANSFER', ['RECEIVING_CLOSED']),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.transfer') }));
    const form = screen.getByLabelText(t('m09.move.TRANSFER', ['L30703']));
    await vi.waitFor(() => expect(within(form).queryByText(/Khanna NWNP Gang/)).toBeTruthy());
    await userEvent.selectOptions(
      within(form).getByLabelText(label('m09.toGang'), { selector: 'select' }),
      '9',
    );
    await userEvent.type(within(form).getByLabelText(label('m09.moveReason')), 'Order 9/2026');
    await userEvent.type(within(form).getByLabelText(label('m02.orderReference')), 'TO/9/2026');
    await userEvent.type(within(form).getByLabelText(label('m02.issuingAuthority')), 'Area Manager');
    await userEvent.click(within(form).getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await within(form).findByRole('alert')).toBeTruthy();
    await userEvent.click(within(form).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('m09.move.TRANSFER', ['L30703']))).toBeNull();
  });

  it('SRS-2-30: a viewer sees the transfer disabled with the reason', async () => {
    backend({
      'GET /v1/labour/options': () => ({ depots: [], designations: [] }),
      'GET /v1/labour/members?': (url) => paged([ROW], 1, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    const transfer = within(table).getByRole('button', { name: t('action.m02.transfer') });
    expect(transfer).toBeDisabled();
    expect(transfer.getAttribute('title')).toBe(t('reason.hrOnly'));
  });
});

describe('X-07 enablement', () => {
  it('DoD 4: Submit names the first missing field of an enablement', async () => {
    backend(
      {
        'GET /v1/hrms-fallback/enablements/live': () => [],
        'GET /v1/hrms-fallback/summary': () => ({ awaitingApproval: 0, reconciliationPending: 0 }),
      },
      { items: [], page: 0, size: 20, total: 0 },
    );
    wrap(<HrmsFallback />, me(['X-07:VIEW', 'X-07:CREATE']));
    const enable = await screen.findByRole('button', { name: t('x07.w.act.enable') });
    await userEvent.click(enable);
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit.getAttribute('title')).toBe(t('x07.why.type'));
  });
});
