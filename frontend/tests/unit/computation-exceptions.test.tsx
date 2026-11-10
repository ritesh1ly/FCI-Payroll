import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ComputationExceptions } from '@/modules/general';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * C-08 Payroll Computation - Exceptions Review (SRS-G-9, SRS l.1641-1678, US-05-08): the X-05
 * worklist filtered to the current run, read on C-08 VIEW, the eight drawn fields, row actions by
 * the role that fixes the condition, and the drawn objects that wait for a source.
 */

afterEach(() => vi.unstubAllGlobals());

const condition = (over: object = {}) => ({
  moduleCode: 'C-02',
  exceptionCode: 'NEGATIVE_NET',
  payrollType: 'STAFF',
  count: 3,
  severity: 'BLOCKING',
  fieldName: 'Net pay',
  fieldSource: 'CALC',
  correctionScreen: 'I-06',
  blocksLock: true,
  owner: 'PAYROLL_MAKER',
  resolvable: true,
  detail: 'Negative / zero net pay',
  ...over,
});

const ROWS = [
  condition(),
  condition({
    moduleCode: 'M-01',
    exceptionCode: 'BANK_INVALID',
    payrollType: 'ALL',
    fieldName: 'Bank account / IFSC',
    fieldSource: 'HRMS',
    correctionScreen: null,
    owner: null,
    resolvable: false,
    detail: 'Bank account missing / invalid',
  }),
  condition({
    moduleCode: 'E-01',
    exceptionCode: 'REGIME_MISSING',
    severity: 'WARNING',
    fieldName: null,
    fieldSource: 'ESS',
    correctionScreen: null,
    blocksLock: false,
    owner: null,
    resolvable: false,
    detail: 'Tax regime not selected',
  }),
  condition({
    moduleCode: 'I-01',
    exceptionCode: 'WORK_SLIP_MISSING',
    payrollType: 'DPS_LABOUR',
    severity: 'WARNING',
    fieldSource: 'ENT',
    blocksLock: false,
    owner: 'LABOUR_DEALING_HAND',
    correctionScreen: 'I-02',
    detail: 'Work-slip missing',
  }),
  condition({
    moduleCode: 'M-01',
    exceptionCode: 'HOLD',
    payrollType: 'ALL',
    severity: 'WARNING',
    fieldSource: null,
    blocksLock: false,
    owner: 'ESTABLISHMENT_HR',
    correctionScreen: 'later',
    detail: 'Hold / stop-payment',
  }),
];

const review = (rows: unknown[], over: object = {}, url = '') => ({
  ...paged(rows, rows.length, url),
  period: '2026-10',
  tiles: { total: 829, blocking: 199, fixable: 522, hrms: 306, systemOrMember: 1 },
  ...over,
});

const ITEM = {
  id: 5,
  cycleInstanceId: 7,
  eis: 'E001',
  memberName: 'Asha Rao',
  moduleCode: 'I-01',
  exceptionCode: 'WORK_SLIP_MISSING',
  severity: 'WARNING',
  blocksGate: 'G2',
  fieldName: 'Booking-slip',
  fieldSource: 'ENT',
  resolvableInPayroll: true,
  correctionScreen: 'I-02',
  ownerRoleCode: 'LABOUR_DEALING_HAND',
  detail: 'Work-slip missing',
  status: 'OPEN',
  acceptedBy: null,
  acceptedReason: null,
  raisedAt: '2026-10-20T10:00:00Z',
  ageDays: 2,
  resolvedAt: null,
};

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}, rows = ROWS) {
  return backend({
    'GET /v1/computation-exceptions?': (url) => review(rows, {}, url),
    'GET /v1/computation-exceptions/items': (url) => paged([ITEM], 1, url),
    ...over,
  });
}

const row = async (code: string) => (await screen.findByText(code)).closest('tr') as HTMLElement;

describe('C-08 Payroll Computation - Exceptions Review', () => {
  it('SRS_l1669_theEightFieldsOfACondition_withTheFourTiles', async () => {
    routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW', 'C-08:EXPORT']));
    const r = await row('NEGATIVE_NET');
    expect(within(r).getByText('Negative / zero net pay')).toBeTruthy();
    expect(within(r).getByText(t('c08.type.STAFF'))).toBeTruthy();
    expect(within(r).getByText('3')).toBeTruthy();
    expect(within(r).getByText(t('x05.severity.BLOCKING'))).toBeTruthy();
    expect(within(r).getByText('Net pay')).toBeTruthy();
    expect(within(r).getByText(t('c08.fix.screen', ['I-06']))).toBeTruthy();
    expect(within(r).getByText(t('x05.yes'))).toBeTruthy();
    expect(await screen.findByText('829')).toBeTruthy();
    expect(screen.getByText(t('c08.tile.totalSub', [199]))).toBeTruthy();
    expect(screen.getByText('522')).toBeTruthy();
    expect(screen.getByText('306')).toBeTruthy();
    expect(screen.getByRole('table', { name: t('c08.worklist') })).toBeTruthy();
    // The session's month is what the read carries; the server decides the run.
    expect(screen.getAllByText(t('c08.exportWorklist')).length).toBe(1);
  });

  it('SRS_G_45_anHrmsOwnedConditionIsCorrectedInHrms_editDisabledWithTheReason', async () => {
    routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const r = await row('BANK_INVALID');
    expect(within(r).getByText(t('c08.fix.upstream'))).toBeTruthy();
    expect(within(r).getByRole('button', { name: t('x05.editOf', ['BANK_INVALID']) })).toHaveAttribute(
      'data-why',
      t('c08.why.hrmsOwned'),
    );
  });

  it('SC_05_0064_aMemberOwnedConditionIsChased_neverKeyed', async () => {
    routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const r = await row('REGIME_MISSING');
    expect(within(r).getByRole('button', { name: t('x05.editOf', ['REGIME_MISSING']) })).toHaveAttribute(
      'data-why',
      t('c08.why.member'),
    );
    // No field and no source: the cell says so, it does not stay blank.
    expect(within(r).getAllByText('—').length).toBeGreaterThan(0);
  });

  it('SRS_G_9_editIsTheRoleThatFixesTheCondition_otherRolesSeeWhy', async () => {
    routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const slip = await row('WORK_SLIP_MISSING');
    expect(
      within(slip).getByRole('button', { name: t('x05.editOf', ['WORK_SLIP_MISSING']) }),
    ).toHaveAttribute('data-why', t('c08.why.ldh'));
    const net = await row('NEGATIVE_NET');
    expect(within(net).getByRole('button', { name: t('x05.editOf', ['NEGATIVE_NET']) })).toHaveAttribute(
      'data-why',
      t('c08.why.maker'),
    );
    const hold = await row('HOLD');
    expect(within(hold).getByRole('button', { name: t('x05.editOf', ['HOLD']) })).toHaveAttribute(
      'data-why',
      t('c08.why.hr'),
    );
    expect(within(hold).getByRole('button', { name: t('wf.cancel') })).toBeDisabled();
  });

  it('SRS_l1652_theMakerEditOpensTheScreenWhereTheConditionIsFixed', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign, search: '' });
    routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await userEvent.click(await screen.findByRole('button', { name: t('x05.editOf', ['NEGATIVE_NET']) }));
    expect(assign).toHaveBeenCalledWith('/payroll/?s=I-06');
  });

  it('SRS_G_9_aCodeThatIsNoScreenOpensTheItems_notALink', async () => {
    routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW'], { roles: ['ESTABLISHMENT_HR'] }));
    await userEvent.click(await screen.findByRole('button', { name: t('x05.editOf', ['HOLD']) }));
    expect(await screen.findByText(t('x05.itemsOf', ['HOLD']))).toBeTruthy();
  });

  it('SRS_G_9_theCheckerAcceptsANonBlockingItemWithAReason_blockingStaysDisabled', async () => {
    const calls = routes({
      'POST /v1/exceptions/5/accept': () => ({ ...ITEM, status: 'ACCEPTED' }),
    });
    wrap(<ComputationExceptions />, me(['C-08:VIEW', 'X-05:APPROVE'], { roles: ['PAYROLL_CHECKER'] }));
    const net = await row('NEGATIVE_NET');
    expect(within(net).getByRole('button', { name: '✔' })).toHaveAttribute('data-why', t('c08.why.blocking'));
    const slip = await row('WORK_SLIP_MISSING');
    await userEvent.click(within(slip).getByRole('button', { name: '✔' }));
    await userEvent.click(await screen.findByRole('button', { name: t('x05.accept') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Slip follows in the next run');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await waitFor(() => expect(sent(calls, 'POST', '/v1/exceptions/5/accept')).toHaveLength(1));
    expect(await screen.findByText(t('x05.accepted', ['WORK_SLIP_MISSING']))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/exceptions/5/accept')[0]).toEqual({
      reason: 'Slip follows in the next run',
    });
  });

  it('SRS_G_9_aRefusedAcceptanceShowsItsReason_cancelSendsNothing', async () => {
    const calls = routes({
      'POST /v1/exceptions/5/accept': () => problem(409, 'EXC-409-BLOCKING'),
    });
    wrap(<ComputationExceptions />, me(['C-08:VIEW', 'X-05:APPROVE'], { roles: ['PAYROLL_CHECKER'] }));
    await userEvent.click(
      await screen.findByRole('button', { name: t('x05.viewOf', ['WORK_SLIP_MISSING']) }),
    );
    await userEvent.click(await screen.findByRole('button', { name: t('x05.accept') }));
    await userEvent.click(within(screen.getByRole('dialog')).getAllByRole('button')[0] as HTMLElement);
    expect(sent(calls, 'POST', '/v1/exceptions/5/accept')).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: t('x05.accept') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Why not');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('US_GAP_100_establishmentHrReadsWithoutActing', async () => {
    const calls = routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW', 'C-08:EXPORT'], { roles: ['ESTABLISHMENT_HR'] }));
    await row('NEGATIVE_NET');
    expect(screen.getByRole('button', { name: t('c08.rerun') })).toHaveAttribute(
      'data-why',
      t('c08.why.rerunMaker'),
    );
    expect(screen.getByRole('button', { name: t('c08.assign') })).toHaveAttribute(
      'data-why',
      t('c08.why.assign'),
    );
    expect(screen.getByRole('button', { name: t('c08.defer') })).toHaveAttribute(
      'data-why',
      t('c08.why.defer'),
    );
    expect(screen.queryByRole('button', { name: '✔' })).toBeNull();
    // Never an X-05 read: HR holds none (OD-130: a refused call logs the user out).
    expect(calls.some((c) => c.url.includes('/v1/exceptions'))).toBe(false);
  });

  it('WF_1100_theMakersDrawnButtonsSayWhyAndSendNothing', async () => {
    const calls = routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await row('NEGATIVE_NET');
    await userEvent.click(screen.getByRole('button', { name: t('c08.rerun') }));
    expect(await screen.findByText(t('c08.why.rerun'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c08.assign') }));
    expect(await screen.findByText(t('c08.why.assignRight'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c08.defer') }));
    expect(await screen.findByText(t('c08.why.deferSource'))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
    // No export right: the drawn exports are enabled as drawn, say why, and send nothing (OD-125).
    const exported = screen.getByRole('button', { name: t('c08.exportWorklist') });
    expect(exported).toBeEnabled();
    expect(exported).toHaveAttribute('data-guard', t('c08.why.export'));
    await userEvent.click(exported);
    expect(await screen.findByText(t('c08.why.export'))).toBeTruthy();
    expect(calls.filter((c) => c.url.includes('export'))).toEqual([]);
  });

  it('CLR_46_aGradeSaysWhyAndFiltersNothing_theStoredSeverityFilters', async () => {
    const calls = routes({
      'GET /v1/reference/lists': () =>
        referenceLists({
          C08_SEVERITY: [['CRITICAL', 'Critical']],
          EXCEPTION_SEVERITY: [['BLOCKING', 'Blocking']],
        }),
    });
    wrap(<ComputationExceptions />, me(['C-08:VIEW']));
    await row('NEGATIVE_NET');
    await screen.findByRole('option', { name: 'Blocking' });
    const drawn = screen.getAllByRole('combobox', { name: t('c08.severity') })[0] as HTMLElement;
    expect(drawn).toBeEnabled();
    expect(drawn).toHaveAttribute('data-why', t('c08.why.severity'));
    await userEvent.selectOptions(drawn, 'CRITICAL');
    expect(await screen.findByText(t('c08.why.severity'))).toBeTruthy();
    expect(drawn).toHaveValue('');
    expect(calls.some((c) => c.url.includes('severity=CRITICAL'))).toBe(false);
    await userEvent.selectOptions(document.getElementById('c08-stored') as HTMLElement, 'BLOCKING');
    await waitFor(() => expect(calls.some((c) => c.url.includes('severity=BLOCKING'))).toBe(true));
    expect(await screen.findByText(t('c08.filtered'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c08.clearFilter') }));
    await waitFor(() => expect(screen.queryByText(t('c08.filtered'))).toBeNull());
  });

  it('SRS_G_9_theListSearchIsServerSide', async () => {
    const calls = routes();
    wrap(<ComputationExceptions />, me(['C-08:VIEW']));
    await row('NEGATIVE_NET');
    await userEvent.type(screen.getByRole('searchbox'), 'bank');
    await waitFor(() => expect(calls.some((c) => c.url.includes('search=bank'))).toBe(true));
  });

  it('SRS_G_9_aSessionMonthWithoutARunSaysWhichRunIsShown', async () => {
    routes({ 'GET /v1/computation-exceptions?': (url) => review(ROWS, { period: '2026-01' }, url) });
    wrap(<ComputationExceptions />, me(['C-08:VIEW']));
    expect(await screen.findByText(t('c08.runNote', ['JAN 2026']))).toBeTruthy();
  });

  it('SRS_G_9_noRunInScopeIsAnEmptyList_andARefusalIsShown', async () => {
    routes(
      {
        'GET /v1/computation-exceptions?': (url) =>
          review(
            [],
            { period: null, tiles: { total: 0, blocking: 0, fixable: 0, hrms: 0, systemOrMember: 0 } },
            url,
          ),
      },
      [],
    );
    const first = wrap(<ComputationExceptions />, me(['C-08:VIEW']));
    expect(await screen.findByText(t('c08.none'))).toBeTruthy();
    expect(screen.queryByText(t('c08.runNote', ['JAN 2026']))).toBeNull();
    first.unmount();
    routes({ 'GET /v1/computation-exceptions?': () => problem(403, 'AUTHZ-403-SCOPE') });
    wrap(<ComputationExceptions />, me(['C-08:VIEW']));
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
  });
});
