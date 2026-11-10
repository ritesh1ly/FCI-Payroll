import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArrearEvents } from '@/modules/arrear';
import { PayrollCycle, ReleaseSuspensions, SimulationRun } from '@/modules/jobs';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * Forms submitted incomplete by the Enter key send nothing (O-02 SRS-15-35, C-02 return SRS-15-30, C-09 SRS-15-7):
 * the submit guard holds even when the disabled button is bypassed. And the I-08 toolbar Recompute refused without a
 * reason still says the member cannot be computed (SRS-8-13).
 */
afterEach(() => vi.unstubAllGlobals());

const posts = (calls: { method: string }[]) => calls.filter((c) => c.method !== 'GET');

describe('incomplete forms submitted by Enter', () => {
  it('SRS_15_35_anIncompleteSuspensionSendsNothing', async () => {
    const calls = backend({ 'GET /v1/release-suspensions?': (url: string) => paged([], 0, url) });
    const user = userEvent.setup();
    wrap(<ReleaseSuspensions />, me(['O-02:VIEW', 'O-02:SUSPEND'], { atHeadquarters: true }));
    await screen.findByText(t('o02.none'));
    await user.click(screen.getByRole('button', { name: t('o02.suspend') }));
    const dialog = screen.getByRole('dialog');
    // no reason typed: the form is not ready
    fireEvent.submit(dialog.querySelector('form') ?? dialog);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(posts(calls)).toEqual([]);
  });

  it('SRS_15_30_anIncompleteReturnSendsNothing', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => referenceLists({ C02_PAY_PATTERN_FILTER: [] }),
      'GET /v1/cycles/2026-10': () => ({
        period: '2026-10',
        unitCode: 'DH001',
        unitLabel: 'DO Delhi',
        unitLevel: 'DO',
        inCalendar: true,
        computeBy: '2026-10-24',
        lockTarget: '2026-10-28',
        id: 9,
        status: 'COMPUTED',
        initiatedBy: 'maker',
        initiatedAt: '2026-10-19T09:00:00Z',
        frozenBy: 'maker',
        frozenAt: '2026-10-19T10:00:00Z',
        verifiedBy: null,
        verifiedAt: null,
        lockedBy: null,
        lockedAt: null,
        currentRunId: null,
        runs: [],
        resultsRunId: null,
        resultsRunType: null,
        totals: { members: 0, gross: 0, deductions: 0, net: 0, components: [] },
        lockApprovalId: null,
        lockApprovalStatus: null,
        postedToEbs: false,
        supplementaryOpen: false,
        lease: null,
        leaseReleaseApprovalId: null,
        openReturn: null,
        unfreezeApprovalId: null,
      }),
      'GET /v1/cycles/2026-10/pre-run-checks': () => [],
      'GET /v1/cycles/2026-10/results-period': () => ({ period: '2026-10', runType: 'ACTUAL' }),
      'GET /v1/cycles/2026-10/results/summary': () => ({
        ida: { members: 0, exceptions: 0 },
        cda: { members: 0, exceptions: 0 },
        gross: 0,
        deductions: 0,
        net: 0,
        held: 0,
        shown: { members: 0, gross: 0, deductions: 0, net: 0 },
      }),
      'GET /v1/cycles/2026-10/results?': (url: string) => paged([], 0, url),
      'GET /v1/cycles/2026-10/pli-payments': (url: string) => paged([], 0, url),
    });
    const user = userEvent.setup();
    wrap(<PayrollCycle />, me(['C-02:VIEW', 'C-02:APPROVE', 'C-02:RETURN'], { atHeadquarters: false }));
    await user.click(await screen.findByRole('button', { name: t('cyc.action.return') }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('button', { name: t('cyc.action.return') })).toBeDisabled();
    fireEvent.submit(dialog.querySelector('form') ?? dialog);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(posts(calls)).toEqual([]);
  });

  it('SRS_15_7_anIncompleteSimulationRunsNothing', async () => {
    const calls = backend({
      'GET /v1/simulations/tiles': () => ({
        period: '2026-10',
        unitCode: 'DH001',
        unitLabel: 'Delhi',
        maxSelection: 1000,
        interactiveLimit: 5,
        runsThisCycle: 0,
        mineThisCycle: 0,
        largestThisCycle: 0,
        retained: 0,
      }),
      'GET /v1/simulations?': (url: string) => paged([], 0, url),
    });
    const user = userEvent.setup();
    wrap(<SimulationRun />, me(['C-09:VIEW', 'C-09:RUN']));
    await user.click(await screen.findByRole('button', { name: t('c09w.request') }));
    const form = screen.getByRole('dialog', { name: t('c09.new') });
    // a member list with no member is not a selection
    await user.selectOptions(within(form).getByLabelText(t('c09.population')), 'MEMBER_LIST');
    expect(within(form).getByRole('button', { name: t('c09.run') })).toBeDisabled();
    fireEvent.submit(form);
    expect(screen.getByRole('dialog', { name: t('c09.new') })).toBeInTheDocument();
    expect(posts(calls)).toEqual([]);
  });
});

describe('I-08 toolbar recompute refused without a reason', () => {
  it('SRS_8_13_theToolbarRecomputeRefusedWithoutAReasonStillSaysSo', async () => {
    const event = {
      id: 10,
      eventCode: 'DA_REVISION',
      eventName: 'DA revision',
      reference: 'DA-2026-07',
      effectiveFrom: '2026-07-01',
      effectiveTo: null,
      office: null,
      status: 'RAISED',
      postedToYymm: null,
      members: 1,
      arrears: 0,
      recoveries: 0,
      raisedBy: 'job',
      raisedAt: '2026-10-01T10:00:00',
      source: null,
      singleMember: true,
      stated: false,
      beyondWindow: false,
      hqException: null,
    };
    const calls = backend({
      'GET /v1/arrear-events/summary': () => ({}),
      'GET /v1/arrear-events/controls': () => ({ maxRetroMonths: null, postRetirementMonths: null }),
      'GET /v1/arrear-events/types': () => [],
      'GET /v1/arrear-events/10/periods': () => [],
      'GET /v1/arrear-events/10/documents': () => [],
      'GET /v1/arrear-events?': (url: string) => paged([event], 1, url),
      'POST /v1/arrear-events/10/computations': () => ({ computed: false, reason: null }),
    });
    const user = userEvent.setup();
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    await screen.findByText('DA-2026-07');
    await user.click(screen.getByRole('button', { name: t('i08.recomputeAct') }));
    const refused = t('i08.notComputable', ['']);
    expect(
      await screen.findByText(
        (_, el) => el !== null && el.children.length === 0 && el.textContent === refused,
      ),
    ).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST' && c.url.includes('/10/computations'))).toHaveLength(1);
  });
});
