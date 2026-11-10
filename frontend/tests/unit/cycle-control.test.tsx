import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CycleControl } from '@/modules/jobs';
import { backend, me, problem, sent, t, wrap, paged } from './support/harness';

/**
 * C-01 Payroll Cycle Control (SRS-15-11, SRS-15-14 … 18, SRS-15-39): gates, pre-lock checks and the
 * G6 quarantine queue of the session's period; the Checker's acceptances with a reason.
 */

afterEach(() => vi.unstubAllGlobals());

const OVERVIEW = {
  period: '2026-10',
  finYear: '2026-27',
  unitCode: 'DH001',
  unitLabel: 'Delhi',
  unitLevel: 'DO',
  cycleId: 7,
  status: 'VERIFIED',
  frozenBy: 'maker.delhi',
  frozenAt: '2026-10-20T10:00:00Z',
  postedToEbs: false,
  supplementaryOpen: false,
  tiles: {
    disbursement: '2026-11-04',
    daysToDisbursement: 4,
    engines: { STAFF: 'COMPLETE', DEPT_LABOUR: 'RUNNING' },
    blockingExceptions: 2,
    verifiedBy: null,
    verifiedAt: null,
  },
  preRun: [
    { code: 'P1_RETIREMENT', name: 'P1', status: 'PASS', failures: 0, members: [], correctionScreen: null },
    {
      code: 'P2_DUPLICATE_FPS',
      name: 'P2',
      status: 'FAIL',
      failures: 1,
      members: ['E9'],
      correctionScreen: null,
    },
    {
      code: 'P4_APPS_PERIOD_CLOSE',
      name: 'P4',
      status: 'NOT_AVAILABLE',
      failures: 0,
      members: [],
      correctionScreen: null,
    },
  ],
  run: {
    id: 31,
    status: 'FAILED',
    startedBy: 'maker.delhi',
    startedAt: '2026-10-20T11:00:00Z',
    units: 10,
    unitsCommitted: 9,
    unitsFailed: 1,
  },
  lease: null,
  levels: [
    { level: 'HQ', instances: 1, locked: 0 },
    { level: 'ZO', instances: 0, locked: 0 },
    { level: 'RO', instances: 0, locked: 0 },
    { level: 'DO', instances: 3, locked: 2 },
  ],
  fallback: [],
};

const PERIODS = [
  {
    period: '2026-10',
    status: 'FROZEN',
    computedAt: null,
    verifiedBy: null,
    verifiedAt: null,
    lockedAt: null,
    postedToEbs: false,
    supplementaryOpen: false,
    mdpOpen: false,
  },
  {
    period: '2026-09',
    status: 'LOCKED',
    computedAt: '2026-09-28T10:00:00Z',
    verifiedBy: 'ddo.delhi',
    verifiedAt: '2026-09-29T10:00:00Z',
    lockedAt: '2026-09-30T10:00:00Z',
    postedToEbs: false,
    supplementaryOpen: false,
    mdpOpen: true,
  },
  {
    period: '2026-08',
    status: 'LOCKED',
    computedAt: '2026-08-28T10:00:00Z',
    verifiedBy: 'ddo.delhi',
    verifiedAt: '2026-08-29T10:00:00Z',
    lockedAt: '2026-08-30T10:00:00Z',
    postedToEbs: true,
    supplementaryOpen: true,
    mdpOpen: false,
  },
];

/** The C-01 reads every test of the screen serves (playbook: a new read on a parent breaks a suite). */
const c01Reads = {
  'GET /v1/cycles/2026-10/overview': () => OVERVIEW,
  'GET /v1/cycles/2026-10/orders': () => [],
  'GET /v1/cycles/period-register': (url: string) => paged(PERIODS, 3, url),
  'GET /v1/document-types': () => [{ code: 'ORDER', name: 'Order', referenceRequired: true }],
};

const gate = (code: string, met: boolean, over: object = {}) => ({
  code,
  met,
  overridable: !['G0', 'G1', 'G3', 'G4'].includes(code),
  acceptable: code === 'G2' && !met,
  detail: `${code} detail`,
  acceptedBy: null,
  acceptedAt: null,
  acceptedReason: null,
  ...over,
});

const check = (over: object = {}) => ({
  checkId: 1,
  code: 'INCREMENTS_DUE_APPLIED',
  name: 'Increments due this month applied',
  appliesTo: 'ALL',
  severity: 'WARN',
  result: 'FAIL',
  failures: 1,
  detail: 'Not available',
  correctionScreen: 'I-05',
  acknowledgedBy: null,
  acknowledgeReason: null,
  ...over,
});

const VIEW = {
  cycleId: 7,
  status: 'VERIFIED',
  gates: [
    gate('G0', true),
    gate('G1', true),
    gate('G2', false),
    gate('G3', true),
    gate('G4', false),
    gate('G5', false, { acceptedBy: 'ddo', acceptedReason: 'Member excluded' }),
    gate('G6', false),
  ],
  checks: [
    check(),
    check({ checkId: 2, code: 'PF_UAN_VALID', name: 'PF / UAN valid', severity: 'BLOCK' }),
    check({
      checkId: 3,
      code: 'HRMS_FALLBACK_NOT_APPROVED',
      severity: 'BLOCK',
      result: 'PASS',
      failures: 0,
      correctionScreen: null,
    }),
    check({ checkId: 4, code: 'X_WARN', acknowledgedBy: 'ddo' }),
  ],
  blocking: ['G2', 'G4', 'G6'],
  lockable: false,
};

const QUARANTINE = [
  {
    id: 9,
    employeeId: 1,
    empNum: 'E001',
    name: 'A Member',
    reason: 'NEGATIVE_NET',
    net: -120.5,
    reviewStatus: 'UNREVIEWED',
    reviewedBy: null,
    reviewReason: null,
  },
  {
    id: 10,
    employeeId: 2,
    empNum: 'E002',
    name: 'B Member',
    reason: 'ZERO_NET',
    net: null,
    reviewStatus: 'ACCEPTED',
    reviewedBy: 'ddo',
    reviewReason: 'Agreed',
  },
];

/** SRS-15-17: the register of the unit, newest first. */
const REGISTER = [
  {
    id: 7,
    type: 'UNDO',
    period: '202609',
    entity: 'mdp_transaction',
    key: '41',
    stage: 'LOCKED',
    reason: 'Entered twice',
    requestedBy: 'maker.delhi',
    checkedBy: 'ddo.delhi',
    authorisedBy: 'sysadmin',
    approvalId: 90,
    executedAt: '2026-10-20T10:00:00Z',
  },
  {
    id: 6,
    type: 'GATE_OVERRIDE',
    period: '202610',
    entity: 'payroll_cycle_instance',
    key: 'G5',
    stage: null,
    reason: 'Statutory return filed late',
    requestedBy: 'maker.delhi',
    checkedBy: null,
    authorisedBy: 'ddo.delhi',
    approvalId: null,
    executedAt: '2026-10-19T10:00:00Z',
  },
];

function routes(
  post: () => unknown = () => VIEW,
  view: unknown = VIEW,
  quarantine: unknown[] = QUARANTINE,
  register: unknown[] = REGISTER,
  flagged: unknown[] = [],
  over: Record<string, (url: string, init?: RequestInit) => unknown> = {},
) {
  return backend({
    ...c01Reads,
    ...over,
    'GET /v1/tax/flagged-tds-cases?': (url) => paged(flagged, flagged.length, url),
    ...(over['GET /v1/cycles/2026-10/gates'] ? {} : { 'GET /v1/cycles/2026-10/gates': () => view }),
    'GET /v1/cycles/2026-10/quarantine': () => paged(quarantine),
    'GET /v1/cycle-register-entries?': (url) => paged(register, register.length, url),
    'POST /v1/cycles/2026-10/': post,
  });
}

const FLAGGED = [
  {
    employeeId: 9,
    eis: 'E101',
    name: 'Asha Rao',
    office: 'DH001 - Delhi Division',
    flag: 'PROOF_DEADLINE_REVERSAL',
    reference: 2,
    reason: null,
    at: null,
  },
  {
    employeeId: 10,
    eis: 'E102',
    name: 'Ravi Kumar',
    office: null,
    flag: 'REGIME_RESET',
    reference: 77,
    reason: 'Regime keyed wrongly',
    at: '2026-09-10T06:00:00Z',
  },
];

describe('C-01 TDS cases for review (FRS V0.5 §14.4 Step 4, US-GAP-29)', () => {
  it('SRS-7-14 / BR-TAX-001: the Checker sees the proof-deadline reversals and regime resets of the year', async () => {
    const calls = routes(undefined, VIEW, QUARANTINE, REGISTER, FLAGGED);
    wrap(<CycleControl />, ['C-01:VIEW', 'C-01:APPROVE']);
    // The panel waits for the period, then for its own query: a busy run needs longer than 1 s.
    const table = await screen.findByRole('table', { name: t('c01.tdsReview') }, { timeout: 5000 });
    expect(await within(table).findByText('E101')).toBeTruthy();
    expect(within(table).getByText(t('c01.tdsFlag.PROOF_DEADLINE_REVERSAL'))).toBeTruthy();
    expect(within(table).getByText(t('c01.tdsRound', [2]))).toBeTruthy();
    expect(within(table).getByText(t('c01.tdsFlag.REGIME_RESET'))).toBeTruthy();
    expect(within(table).getByText('Regime keyed wrongly')).toBeTruthy();
    expect(within(table).getByText(t('c01.tdsApproval', [77]))).toBeTruthy();
    expect(screen.getByText(t('c01.tdsReviewHint'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/v1/tax/flagged-tds-cases?period=2026-10'))).toBe(true);
  });

  it('FRS V0.5 §14.4: a year with nothing flagged says so', async () => {
    routes();
    wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByText(t('c01.noTdsReview'), {}, { timeout: 5000 })).toBeTruthy();
  });
});

describe('C-01 Payroll Cycle Control', () => {
  it('SRS-15-14: shows each gate met, unmet or accepted, the checks and the quarantine; the maker only views', async () => {
    routes();
    wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByText(t('c01.blocking', ['G2, G4, G6']))).toBeTruthy();
    expect(screen.getAllByText(t('c01.hard'))).toHaveLength(4);
    // Both the drawn panel and the closed section say who accepted it.
    expect(screen.getAllByText(t('c01.acceptedBy', ['ddo']))).toHaveLength(2);
    expect(screen.getByText('Member excluded')).toBeTruthy();
    expect(screen.getAllByText(t('c01.fail', [1]))).toHaveLength(2);
    expect(screen.getByText(t('c01.pass'))).toBeTruthy();
    expect(screen.getByText(t('c01.acknowledgedBy', ['ddo']))).toBeTruthy();
    expect(await screen.findByText('E001')).toBeTruthy();
    expect(screen.getByText(t('c01.review.ACCEPTED'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.acceptGate') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('action.acknowledge') })).toBeNull();
  });

  it('SRS-15-39 (C-01 #5): a failing check links to its fixing screen; C-08 is a screen of its own', async () => {
    routes(undefined, {
      ...VIEW,
      checks: [
        check(),
        check({ checkId: 5, code: 'NET_PAY_NOT_BELOW_MINIMUM', correctionScreen: 'C-08' }),
        check({ checkId: 6, code: 'ODD', correctionScreen: 'later' }),
      ],
    });
    wrap(<CycleControl />, ['C-01:VIEW']);
    const i05 = await screen.findByRole('link', { name: 'I-05' });
    expect(i05.getAttribute('href')).toBe('/payroll/?s=I-05');
    expect(screen.getByRole('link', { name: 'C-08' }).getAttribute('href')).toBe('/payroll/?s=C-08');
    // A code that is no screen stays text.
    expect(screen.getByText('later')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'later' })).toBeNull();
  });

  it('SRS-15-16: the checker accepts G2 with a reason, acknowledges a Warn check and reviews a member', async () => {
    const calls = routes();
    wrap(<CycleControl />, ['C-01:VIEW', 'C-01:APPROVE']);
    // Only G2 here: G5 is already accepted, G6 goes per member, hard gates never.
    const accept = await screen.findAllByRole('button', { name: t('action.acceptGate') });
    expect(accept).toHaveLength(1);
    await userEvent.click(accept[0] as HTMLElement);
    let dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Only warnings remain');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await screen.findByText(t('c01.accepted', ['G2']));
    expect(sent(calls, 'POST', '/gates/G2/accept')[0]).toMatchObject({ reason: 'Only warnings remain' });

    const acknowledge = screen.getAllByRole('button', { name: t('action.acknowledge') });
    expect(acknowledge).toHaveLength(1);
    await userEvent.click(acknowledge[0] as HTMLElement);
    dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Increments next month');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await screen.findByText(t('c01.acknowledged', ['INCREMENTS_DUE_APPLIED']));
    expect(sent(calls, 'POST', '/checks/1/acknowledge')).toHaveLength(1);

    await userEvent.click(screen.getByRole('button', { name: t('c01.corrected') }));
    dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Recovery reversed');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await screen.findByText(t('c01.reviewed', ['E001']));
    expect(sent(calls, 'POST', '/quarantine/9/review')[0]).toMatchObject({ accepted: false });
    await userEvent.click(screen.getByRole('button', { name: t('c01.acceptMember') }));
    await userEvent.click(within(screen.getByRole('dialog')).getAllByRole('button')[0] as HTMLElement);
  });

  it('SRS-15-16: a refusal shows its reason; a locked or lockable cycle offers nothing', async () => {
    routes(() => problem(422, 'CYC-422-GATE-HARD', ['G2']));
    wrap(<CycleControl />, ['C-01:VIEW', 'C-01:APPROVE']);
    await userEvent.click(await screen.findByRole('button', { name: t('action.acceptGate') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'x');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    expect(await screen.findByText(t('error.CYC-422-GATE-HARD', ['G2']))).toBeTruthy();
    vi.unstubAllGlobals();
    routes(undefined, { ...VIEW, status: 'LOCKED', blocking: [], lockable: true, checks: [] }, []);
    wrap(<CycleControl />, ['C-01:VIEW', 'C-01:APPROVE']);
    expect(await screen.findByText(t('c01.lockable'))).toBeTruthy();
    expect(screen.getByText(t('c01.noChecks'))).toBeTruthy();
    expect(await screen.findByText(t('c01.noQuarantine'))).toBeTruthy();
  });

  it('SRS-15-17: the register shows every gate override and undo with who asked, checked and authorised; the checker exports it', async () => {
    routes();
    const first = wrap(<CycleControl />, ['C-01:VIEW', 'C-01:EXPORT']);
    expect(await screen.findByText('Entered twice')).toBeTruthy();
    expect(screen.getByText(t('c01.reg.gate', ['G5']))).toBeTruthy();
    expect(screen.getByText('mdp_transaction 41')).toBeTruthy();
    expect(screen.getByText(t('c01.reg.checkedBy', ['ddo.delhi']))).toBeTruthy();
    const register = screen.getByRole('table', { name: t('c01w.registerTitle') });
    expect(within(register).getByText('7').closest('td')?.textContent).toContain(t('c01.regType.UNDO'));
    expect(screen.getAllByRole('button', { name: t('c01w.export') })).toHaveLength(1);
    expect(screen.getByRole('button', { name: t('c01w.export') })).not.toHaveAttribute('data-guard');
    first.unmount();
    // A viewer without the export permission has no export; an empty register says so.
    routes(undefined, undefined, undefined, []);
    wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByText(t('c01.registerEmpty'))).toBeTruthy();
    // Drawn for every role (OD-135): pressing it says why, and nothing is sent.
    expect(screen.getByRole('button', { name: t('c01w.export') })).toHaveAttribute(
      'data-guard',
      t('c01w.why.export'),
    );
  });
});

describe('C-01 as the pack draws it', () => {
  const MAKER = ['C-01:VIEW', 'C-02:CREATE', 'C-02:LEASE_RELEASE'];
  const CHECKER = ['C-01:VIEW', 'C-01:APPROVE', 'C-01:EXPORT', 'C-02:APPROVE'];
  const LEASE = {
    cycleId: 7,
    action: 'COMPUTE',
    heldBy: 'maker.other',
    acquiredAt: '2026-10-20T10:00:00Z',
    heartbeatAt: '2026-10-20T10:00:00Z',
    runId: 31,
    expired: true,
  };

  it('SRS l.1755: the band, the four tiles, P1-P5 and the levels read from the overview', async () => {
    routes();
    wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByText('D-4')).toBeTruthy();
    expect(screen.getByText('1 / 2')).toBeTruthy();
    expect(screen.getByText(t('c01w.tile.notStarted'))).toBeTruthy();
    expect(screen.getByText(t('c01w.preRunName.P1'))).toBeTruthy();
    // SRS-15-23: the failing records are named; a check with no source says so.
    expect(screen.getByText('E9')).toBeTruthy();
    expect(screen.getByText(t('cyc.preRun.NOT_AVAILABLE'))).toBeTruthy();
    expect(screen.getByRole('table', { name: t('c01w.levels', ['OCT 2026']) })).toHaveTextContent(
      t('c01w.level.DO'),
    );
    // SRS l.1766: nothing enabled for the cycle reads Not enabled.
    expect(screen.getByText(t('c01w.fallback.status.NOT_ENABLED'))).toBeTruthy();
    // SRS-15-25: a failed run can be resumed.
    expect(screen.getByText(t('c01w.run.id', [31]))).toBeTruthy();
    expect(screen.getByText(t('c01w.run.available'))).toBeTruthy();
  });

  it('SRS-15-12: Posted and Supplementary are flags beside the status; a locked row says why not', async () => {
    routes();
    wrap(<CycleControl />, MAKER);
    const open = (await screen.findByText('202610')).closest('tr') as HTMLElement;
    expect(within(open).getByRole('button', { name: t('wf.edit') })).toBeEnabled();
    const locked = screen.getByText('202609').closest('tr') as HTMLElement;
    expect(within(locked).getByText(t('c01w.mdpOpen'))).toBeTruthy();
    expect(within(locked).getByRole('button', { name: t('wf.edit') })).toHaveAttribute(
      'data-why',
      t('c01w.why.locked'),
    );
    const posted = screen.getByText('202608').closest('tr') as HTMLElement;
    expect(within(posted).getByText(t('c01w.supplementaryOpen'))).toBeTruthy();
    expect(within(posted).getByRole('button', { name: t('wf.edit') })).toHaveAttribute(
      'data-why',
      t('c01w.why.posted'),
    );
    // SRS-15-10: no reopen exists.
    expect(screen.getByRole('button', { name: t('c01w.reopen') })).toHaveAttribute(
      'data-why',
      t('c01w.why.reopen'),
    );
    await userEvent.click(within(locked).getByRole('button', { name: t('c01w.viewOf', ['2026-09']) }));
    expect(within(screen.getByRole('dialog')).getByText('ddo.delhi', { exact: false })).toBeTruthy();
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.close') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('OD-135: each action is drawn for every role, disabled with its reason for the others', async () => {
    routes();
    wrap(<CycleControl />, ['C-01:VIEW']);
    await screen.findByText('D-4');
    for (const [label, why] of [
      ['c01w.run', 'c01w.why.maker'],
      ['c01w.verifyLock', 'c01w.why.checker'],
      ['c01w.undo', 'c01w.why.admin'],
      ['c01w.resume', 'c01w.why.maker'],
      ['c01w.release', 'c01w.why.release'],
    ] as const) {
      expect(screen.getByRole('button', { name: t(label) })).toHaveAttribute('data-why', t(why));
    }
  });

  it('C01-2: the Maker runs on C-13, the Checker verifies on C-02, the Administrator authorises on W-01', async () => {
    routes();
    const first = wrap(<CycleControl />, MAKER);
    expect((await screen.findByRole('link', { name: t('c01w.run') })).getAttribute('href')).toBe(
      '/payroll/?s=C-13',
    );
    first.unmount();
    routes();
    const second = wrap(<CycleControl />, CHECKER);
    expect((await screen.findByRole('link', { name: t('c01w.verifyLock') })).getAttribute('href')).toBe(
      '/payroll/?s=C-02#c02-cycle',
    );
    second.unmount();
    const calls = routes();
    wrap(<CycleControl />, me(['C-01:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    expect((await screen.findByRole('link', { name: t('c01w.undo') })).getAttribute('href')).toBe(
      '/payroll/?s=W-01',
    );
    // SRS-G-25, OD-130: the Administrator holds no C-02, so no C-02 read is ever sent.
    expect(calls.some((c) => /pre-run-checks|\/v1\/cycles\/2026-10$/.test(c.url))).toBe(false);
  });

  it('SRS-15-28: the Maker asks to release a held lease with a reason; with none there is nothing to ask', async () => {
    const free = routes();
    const first = wrap(<CycleControl />, MAKER);
    await userEvent.click(await screen.findByRole('button', { name: t('c01w.release') }));
    expect(await screen.findByText(t('c01w.why.noStuckRun'))).toBeTruthy();
    expect(free.filter((c) => c.method === 'POST')).toEqual([]);
    first.unmount();
    const calls = routes(() => VIEW, VIEW, QUARANTINE, REGISTER, [], {
      'GET /v1/cycles/2026-10/overview': () => ({ ...OVERVIEW, lease: LEASE }),
    });
    wrap(<CycleControl />, MAKER);
    await screen.findByText(t('c01w.run.leased'));
    await userEvent.click(screen.getByRole('button', { name: t('c01w.release') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Stuck since noon');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    expect(await screen.findByText(t('c01w.releaseAsked'))).toBeTruthy();
    expect(sent(calls, 'POST', '/lease-releases')[0]).toEqual({ reason: 'Stuck since noon' });
  });

  it('SRS-15-28: a refused release shows its reason', async () => {
    routes(() => problem(409, 'CYC-409-LEASE-FREE'), VIEW, QUARANTINE, REGISTER, [], {
      'GET /v1/cycles/2026-10/overview': () => ({ ...OVERVIEW, lease: LEASE }),
    });
    wrap(<CycleControl />, MAKER);
    await screen.findByText(t('c01w.run.leased'));
    await userEvent.click(screen.getByRole('button', { name: t('c01w.release') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Stuck');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('US-05-10 AC3: the Pre-Payroll Exception Report lists the failed checks by office with the link', async () => {
    routes(() => VIEW, VIEW, QUARANTINE, REGISTER, [], {
      'GET /v1/cycles/2026-10/pre-payroll-report': (url) =>
        paged(
          [
            {
              unitCode: 'DH001',
              unitLabel: 'Delhi',
              checkCode: 'BANK',
              checkName: 'Bank account valid',
              severity: 'BLOCK',
              failures: 7,
              detail: '7 members',
              correctionScreen: 'I-21',
            },
            {
              unitCode: 'MR001',
              unitLabel: 'Mumbai',
              checkCode: 'ODD',
              checkName: 'Odd check',
              severity: 'WARN',
              failures: 1,
              detail: null,
              correctionScreen: null,
            },
          ],
          2,
          url,
        ),
    });
    wrap(<CycleControl />, ['C-01:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: t('c01w.report') }));
    const dialog = screen.getByRole('dialog', { name: t('c01w.report') });
    expect(await within(dialog).findByText('DH001')).toBeTruthy();
    expect(within(dialog).getByRole('link', { name: 'I-21' }).getAttribute('href')).toBe('/payroll/?s=I-21');
    expect(within(dialog).getByText('7 members')).toBeTruthy();
    expect(within(dialog).getByText('—')).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-15-42: an enabled fallback shows its status; an approved one does not block', async () => {
    routes(() => VIEW, VIEW, QUARANTINE, REGISTER, [], {
      'GET /v1/cycles/2026-10/overview': () => ({
        ...OVERVIEW,
        fallback: [
          {
            unitCode: 'DH001',
            unitLabel: 'Delhi',
            type: 'ATTENDANCE_LEAVE',
            status: 'UPLOADED',
            blocksLock: true,
          },
          {
            unitCode: 'MR001',
            unitLabel: 'Mumbai',
            type: 'SUSPENSION_ABSCONDING',
            status: 'APPROVED',
            blocksLock: false,
          },
        ],
      }),
    });
    wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByText(t('c01w.fallback.status.UPLOADED'))).toBeTruthy();
    expect(screen.getByText(t('c01w.fallback.ATTENDANCE_LEAVE', ['DH001 Delhi']))).toBeTruthy();
    expect(screen.getByText(t('c01w.fallback.text.APPROVED'))).toBeTruthy();
  });

  it('SRS-15-14: a period with no cycle draws the gates waiting; a verified cycle names the Checker', async () => {
    routes(() => VIEW, VIEW, QUARANTINE, REGISTER, [], {
      'GET /v1/cycles/2026-10/gates': () => problem(404, 'CYC-404-NONE'),
      'GET /v1/cycles/2026-10/overview': () => ({
        ...OVERVIEW,
        cycleId: null,
        status: null,
        frozenBy: null,
        frozenAt: null,
        run: null,
        tiles: { ...OVERVIEW.tiles, disbursement: null, daysToDisbursement: null, engines: {} },
      }),
    });
    const first = wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByText(t('c01.noCycle', ['OCT 2026']))).toBeTruthy();
    await waitFor(() => expect(screen.getAllByText(t('c01w.waiting')).length).toBeGreaterThanOrEqual(7));
    expect(screen.getByText(t('c01w.tile.dayNone'))).toBeTruthy();
    expect(screen.getByText(t('c01w.run.none'))).toBeTruthy();
    first.unmount();
    routes(() => VIEW, VIEW, QUARANTINE, REGISTER, [], {
      'GET /v1/cycles/2026-10/overview': () => ({
        ...OVERVIEW,
        tiles: { ...OVERVIEW.tiles, daysToDisbursement: -2, verifiedBy: 'ddo.delhi' },
      }),
    });
    wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByText('D+2')).toBeTruthy();
    expect(screen.getByText(t('c01w.tile.checker', ['ddo.delhi']))).toBeTruthy();
  });

  it('SRS-G-32: a stored override note shows on the card', async () => {
    const stored = {
      id: 3,
      fileName: 'override.pdf',
      documentTypeCode: 'ORDER',
      documentTypeName: 'Order',
      referenceNumber: 'FCI/1',
      uploadedBy: 'maker.delhi',
      uploadedAt: '2026-10-20T10:00:00Z',
    };
    routes(() => VIEW, VIEW, QUARANTINE, REGISTER, [], {
      'GET /v1/cycles/2026-10/orders': () => [stored],
    });
    wrap(<CycleControl />, MAKER);
    expect(await screen.findByText('override.pdf')).toBeTruthy();
    expect(screen.getByText(t('c01w.orderHint'))).toBeTruthy();
  });
});

describe('C-01 pre-payroll check count as drawn', () => {
  it('SRS-15-39: the I-21 check reads "N pending" and opens I-21; another check keeps its count', async () => {
    const checks = [
      check({ checkId: 1, name: 'HRMS changes', result: 'FAIL', failures: 5, correctionScreen: 'I-21' }),
      check({ checkId: 2, name: 'Bank', result: 'FAIL', failures: 7, correctionScreen: 'M-01' }),
    ];
    routes(undefined, { ...VIEW, checks }, []);
    wrap(<CycleControl />, ['C-01:VIEW']);
    const link = await screen.findByRole('link', { name: t('c01w.pending', [5]) });
    expect(link.getAttribute('href')).toBe('/payroll/?s=I-21');
    expect(screen.getByRole('link', { name: '7' }).getAttribute('href')).toBe('/payroll/?s=M-01');
  });
});
