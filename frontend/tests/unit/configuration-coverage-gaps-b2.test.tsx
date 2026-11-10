import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PenaltyRulesScreen } from '@/modules/configuration';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * M-14 Penalty and Recovery Rule Configuration as drawn (SRS-14-9, SRS-16-13, SRS-G-48; OD-121, OD-135): the floor
 * and the exclusion proposals with their refusals and the cancelled OTP, the drawn buttons, the row actions (View,
 * Edit, the checker's tick), the pending states and the guard count that cannot be read.
 */

const scroll = vi.fn();
beforeEach(() => {
  scroll.mockClear();
  Element.prototype.scrollIntoView = scroll;
});
afterEach(() => vi.unstubAllGlobals());

const LISTS = referenceLists({
  PENALTY_PAYROLL_SCOPE: [
    ['STAFF', 'Employee / Staff'],
    ['DEPT_LABOUR', 'Departmental Labour'],
    ['ALL', 'All Payroll Types'],
  ],
  PENALTY_EXCLUDED_FROM: [['SUPPLEMENTARY', 'Supplementary Payment Run']],
});

const CURRENT = {
  id: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  floorBasis: 'ABSOLUTE_AMOUNT',
  floorValue: 5000,
  deductionCeilingPct: null,
  sourceInstrument: 'FCI order',
  confirmed: true,
  status: 'APPROVED',
};

const FLOORS = [
  { payrollType: 'STAFF', current: null, pending: null },
  { payrollType: 'DEPT_LABOUR', current: CURRENT, pending: null },
  {
    payrollType: 'DPS_LABOUR',
    current: CURRENT,
    pending: { ...CURRENT, id: 2, floorValue: 5500, sourceInstrument: null, status: 'PENDING_APPROVAL' },
  },
];

const EXCLUSIONS = {
  components: [
    { payCode: 366, name: 'Court Recovery', type: 'DEDUCTION', excluded: true },
    { payCode: 401, name: 'Festival Advance', type: 'DEDUCTION', excluded: false },
  ],
  pending: null,
};

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () => LISTS,
    'GET /v1/masters/net-pay-floors/guard-count': () => 14,
    'GET /v1/masters/net-pay-floors': () => paged(FLOORS),
    'GET /v1/masters/supplementary-exclusions': () => EXCLUSIONS,
    ...over,
  });
}

const ADMIN = me(['M-14:VIEW', 'M-14:CREATE', 'M-14:EDIT'], { roles: ['RULE_ADMINISTRATOR'] });
const saveButtons = () => screen.getAllByRole('button', { name: t('m14w.save') });
const floorSave = () => saveButtons()[0] as HTMLButtonElement;
const exclusionSave = () => saveButtons()[1] as HTMLButtonElement;

async function fillFloor(type = 'STAFF') {
  const scope = (await screen.findByLabelText(/Payroll Type Scope/)) as HTMLSelectElement;
  await within(scope).findByRole('option', { name: 'Employee / Staff' });
  await userEvent.selectOptions(scope, type);
  await userEvent.type(screen.getByLabelText(/Floor Amount/), '6000');
  fireEvent.change(screen.getAllByLabelText(/Effective From/)[0] as HTMLElement, {
    target: { value: '2026-12-01' },
  });
  await userEvent.type(document.getElementById('pf_reason') as HTMLElement, 'Board order');
}

async function cancelOtp() {
  const dialog = await screen.findByRole('dialog', { name: t('otp.title') });
  await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
}

const rowOf = (name: string) =>
  screen.getByRole('button', { name: `${t('common.view')} ${name}` }).closest('tr') as HTMLElement;

describe('M-14 floor proposal', () => {
  it('SRS_14_9_theAdministratorProposesAnAmountFloorAndTheFormEmpties', async () => {
    const calls = routes({
      'POST /v1/masters/net-pay-floors/STAFF/versions': () => ({ status: 201, body: CURRENT }),
    });
    wrap(<PenaltyRulesScreen />, ADMIN);
    await fillFloor();
    expect(floorSave()).toBeEnabled();
    expect(floorSave()).not.toHaveAttribute('data-why');
    await userEvent.click(floorSave());
    await waitFor(() =>
      expect(sent(calls, 'POST', '/net-pay-floors/STAFF/versions')).toEqual([
        {
          effectiveFrom: '2026-12-01',
          floorBasis: 'ABSOLUTE_AMOUNT',
          floorValue: 6000,
          deductionCeilingPct: null,
          sourceInstrument: null,
          confirmed: false,
          reason: 'Board order',
        },
      ]),
    );
    expect(await screen.findByText(t('mst.submitted', [t('m14.type.STAFF')]))).toBeTruthy();
    expect(screen.getByLabelText(/Floor Amount/)).toHaveValue(null);
    expect(floorSave()).toBeDisabled();
  });

  it('SRS_14_9_aRefusedFloorShowsTheProblemAndKeepsTheEntry', async () => {
    routes({ 'POST /v1/masters/net-pay-floors/STAFF/versions': () => problem(409, 'MDM-409-OVERLAP') });
    wrap(<PenaltyRulesScreen />, ADMIN);
    await fillFloor();
    await userEvent.click(floorSave());
    expect(await screen.findByText(t('error.MDM-409-OVERLAP'))).toBeTruthy();
    expect(screen.getByLabelText(/Floor Amount/)).toHaveValue(6000);
  });

  it('SRS_G_48_aCancelledOtpLeavesTheFloorFormSilent', async () => {
    routes({ 'POST /v1/masters/net-pay-floors/STAFF/versions': () => problem(428, 'AUTH-428-OTP', ['7']) });
    wrap(<PenaltyRulesScreen />, ADMIN);
    await fillFloor();
    await userEvent.click(floorSave());
    await cancelOtp();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByLabelText(/Floor Amount/)).toHaveValue(6000);
  });
});

describe('M-14 exclusion proposal', () => {
  it('SRS_16_13_anotherCodeIsAddedToTheExcludedListWithItsM07Name', async () => {
    const calls = routes({ 'POST /v1/masters/supplementary-exclusions': () => EXCLUSIONS });
    wrap(<PenaltyRulesScreen />, ADMIN);
    await screen.findByLabelText(/Component Description/);
    const code = document.getElementById('pe_code') as HTMLSelectElement;
    const other = screen.getByLabelText(t('m14w.f.otherCode'));
    expect(other).toBeDisabled();
    await userEvent.selectOptions(code, 'OTHER');
    expect(other).toBeEnabled();
    await userEvent.type(other, ' 401 ');
    expect(screen.getByLabelText(/Component Description/)).toHaveValue('Festival Advance');
    expect(exclusionSave()).toBeDisabled();
    await userEvent.type(document.getElementById('pe_reason') as HTMLElement, 'Board order');
    await userEvent.click(exclusionSave());
    await waitFor(() =>
      expect(sent(calls, 'POST', '/supplementary-exclusions')).toEqual([
        { payCodes: [366, 401], reason: 'Board order' },
      ]),
    );
    expect(await screen.findByText(t('m14.exclusionsSubmitted'))).toBeTruthy();
    expect(code).toHaveValue('');
  });

  it('SRS_16_13_anUnknownCodeCannotBeSent', async () => {
    routes();
    wrap(<PenaltyRulesScreen />, ADMIN);
    await screen.findByLabelText(/Component Description/);
    await userEvent.selectOptions(document.getElementById('pe_code') as HTMLElement, 'OTHER');
    await userEvent.type(screen.getByLabelText(t('m14w.f.otherCode')), '999');
    await userEvent.type(document.getElementById('pe_reason') as HTMLElement, 'Board order');
    expect(screen.getByLabelText(/Component Description/)).toHaveValue('');
    expect(exclusionSave()).toBeDisabled();
  });

  it('SRS_16_13_aRefusedExclusionShowsTheProblem_andACancelledOtpStaysSilent', async () => {
    let answer = problem(400, 'MDM-400-CODE');
    routes({ 'POST /v1/masters/supplementary-exclusions': () => answer });
    wrap(<PenaltyRulesScreen />, ADMIN);
    await screen.findByRole('option', { name: '366' });
    await userEvent.selectOptions(document.getElementById('pe_code') as HTMLElement, '366');
    expect(screen.getByLabelText(/Component Description/)).toHaveValue('Court Recovery');
    await userEvent.type(document.getElementById('pe_reason') as HTMLElement, 'Board order');
    await userEvent.click(exclusionSave());
    expect(await screen.findByText(t('error.MDM-400-CODE'))).toBeTruthy();
    answer = problem(428, 'AUTH-428-OTP', ['7']);
    await userEvent.click(exclusionSave());
    await cancelOtp();
    expect(screen.queryByText(t('error.MDM-400-CODE'))).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('OD_135_aCreateHolderWithoutEditIsToldWhyTheExclusionIsNotTheirs', async () => {
    const calls = routes();
    wrap(<PenaltyRulesScreen />, me(['M-14:VIEW', 'M-14:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }));
    const name = await screen.findByLabelText(/Component Description/);
    // Not the proposer of an exclusion: the name and date stay typable as drawn.
    expect(name).not.toHaveAttribute('readonly');
    await screen.findByRole('option', { name: '366' });
    await userEvent.selectOptions(document.getElementById('pe_code') as HTMLElement, '366');
    expect(name).toHaveValue('Court Recovery');
    expect(exclusionSave()).toBeEnabled();
    await userEvent.click(exclusionSave());
    expect(await screen.findByText(t('m14w.why.admin'))).toBeTruthy();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});

describe('M-14 drawn buttons and rows', () => {
  it('SRS_16_13_theNewButtonsScrollToTheirFormsAndHistoryOpensTheRegister', async () => {
    routes();
    wrap(<PenaltyRulesScreen />, ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('m14w.newFloor') }));
    expect(scroll).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: t('m14w.newExclusion') }));
    expect(scroll).toHaveBeenCalledTimes(2);
    await userEvent.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    await waitFor(() => expect(screen.getByTestId('m14-register').hasAttribute('open')).toBe(true));
  });

  it('SRS_16_13_theFloorRowsShowTheirStatesAndTheEditPicksTheType', async () => {
    routes();
    wrap(<PenaltyRulesScreen />, ADMIN);
    await screen.findByRole('button', { name: `${t('common.view')} DPS_LABOUR` });
    const pending = rowOf('DPS_LABOUR');
    expect(within(pending).getByText(t('m14w.status.pending'), { selector: '.pill' })).toHaveClass('p-warn');
    expect(pending).toHaveTextContent('5500');
    expect(within(rowOf('DEPT_LABOUR')).getByText(t('m14w.status.active'))).toHaveClass('p-ok');
    expect(within(rowOf('STAFF')).getByText(t('mst.notConfigured'))).toHaveClass('p-bad');
    const pendingTile = screen
      .getAllByText(t('m14w.tile.pending'))
      .map((e) => e.closest('.tile'))
      .find((e) => e !== null) as HTMLElement;
    await waitFor(() => expect(within(pendingTile).getByText('1')).toBeTruthy());
    await userEvent.click(
      within(rowOf('DEPT_LABOUR')).getByRole('button', { name: `${t('common.edit')} DEPT_LABOUR` }),
    );
    expect(screen.getByLabelText(/Payroll Type Scope/)).toHaveValue('DEPT_LABOUR');
    expect(scroll).toHaveBeenCalled();
  });

  it('SRS_16_13_viewingAFloorShowsItsVersionInForce_orDashesWhenNoneIs', async () => {
    routes();
    wrap(<PenaltyRulesScreen />, ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} DEPT_LABOUR` }));
    let dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-14')]) });
    expect(within(dialog).getByText('5000')).toBeTruthy();
    expect(within(dialog).getByText('FCI order')).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} STAFF` }));
    dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-14')]) });
    expect(within(dialog).getAllByText('—')).toHaveLength(3);
  });

  it('SRS_16_13_anExclusionRowOpensTheRegisterOnViewAndScrollsOnEdit', async () => {
    routes();
    wrap(<PenaltyRulesScreen />, ADMIN);
    const view = await screen.findByRole('button', { name: `${t('common.view')} 366` });
    expect(within(view.closest('tr') as HTMLElement).getByText(t('m14w.status.active'))).toHaveClass('p-ok');
    await userEvent.click(screen.getByRole('button', { name: `${t('common.edit')} 366` }));
    expect(scroll).toHaveBeenCalledTimes(1);
    await userEvent.click(view);
    await waitFor(() => expect(screen.getByTestId('m14-register').hasAttribute('open')).toBe(true));
  });

  it('M08_08_theCheckerTickGoesToTheInboxOnlyWhenSomethingWaits', async () => {
    const assign = vi.fn();
    routes({
      'GET /v1/masters/supplementary-exclusions': () => ({ ...EXCLUSIONS, pending: { approvalId: 5 } }),
    });
    vi.stubGlobal('location', { ...window.location, assign });
    wrap(<PenaltyRulesScreen />, me(['M-14:VIEW', 'M-14:APPROVE'], { roles: ['PAYROLL_CHECKER'] }));
    await screen.findByRole('button', { name: `${t('common.view')} DPS_LABOUR` });
    expect(screen.getAllByRole('button', { name: t('m14w.supersede') })[0]).toHaveAttribute(
      'data-guard',
      t('m14w.why.supersede'),
    );
    await userEvent.click(within(rowOf('STAFF')).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeTruthy();
    expect(assign).not.toHaveBeenCalled();
    await userEvent.click(within(rowOf('DPS_LABOUR')).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(1);
    const excl = rowOf('366');
    expect(within(excl).getByText(t('m14w.status.pending'), { selector: '.pill' })).toHaveClass('p-warn');
    await userEvent.click(within(excl).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(2);
    // The checker cannot edit: the row's Edit is off with the drawn reason.
    expect(screen.getByRole('button', { name: `${t('common.edit')} 366` })).toHaveAttribute(
      'data-why',
      t('m14w.why.edit'),
    );
  });

  it('SRS_16_13_aGuardCountThatCannotBeReadShowsTheProblemAndADash', async () => {
    routes({ 'GET /v1/masters/net-pay-floors/guard-count': () => problem(500, 'SYS-500') });
    wrap(<PenaltyRulesScreen />, ADMIN);
    expect(await screen.findByText(t('error.SYS-500'))).toBeTruthy();
    const tile = screen.getByText(t('m14w.tile.guard')).closest('.tile') as HTMLElement;
    expect(within(tile).getByText('—')).toBeTruthy();
  });
});
