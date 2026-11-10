import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DepartmentalComputation,
  DpsComputation,
  IncentiveComputation,
  NwnpComputation,
} from '@/modules/labour';
import { gangTone } from '@/modules/labour/components/LabourComputation';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));

/**
 * C-03, C-04, C-05 and C-06 as Wireframes v6.1 draws them (OD-121; LBR-20d-2, LBR-20d-3, LBR-21d-1, NWNP-22d-1): the
 * drawn objects, the role of each action, and nothing that only a run could produce: a figure stays a dash and pressing
 * an action says why, with nothing sent.
 */
const serveAll = (routes: Record<string, unknown>) =>
  backend(Object.fromEntries(Object.entries(routes).map(([k, v]) => [k, () => v])));

afterEach(() => {
  vi.unstubAllGlobals();
  push.mockReset();
});

const LISTS = referenceLists({
  LABOUR_GANG_STATUS: [
    ['NOT_COMPUTED', 'Not computed'],
    ['WORK_SLIP_MISSING', 'Work-slip missing'],
    ['NO_MANDAL', 'No Mandal assigned'],
    ['NORM_MISSING', 'Norm not loaded'],
  ],
} as never);

const SUMMARY = {
  period: '2026-10',
  population: 8,
  depots: 1,
  gangs: 2,
  computed: 0,
  notReady: 1,
  wage: null,
  rates: [
    { role: 'SARDAR', mgw: 799, interimRelief: null, goiRate: null, basicWage: null, vda: null },
    { role: 'MANDAL', mgw: 786, interimRelief: null, goiRate: null, basicWage: null, vda: null },
  ],
};

const gang = (over: object = {}) => ({
  gangId: 1,
  gang: 'DP-DL01-G002',
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  labour: 4,
  sardar: 1,
  mandal: 1,
  handling: 2,
  ancillary: 0,
  presentDays: 96.5,
  presentLabour: 4,
  slips: 3,
  bags: 11640,
  noWorkHours: 4,
  mln: 3600,
  incentive: null,
  status: 'NOT_COMPUTED',
  ...over,
});

function serve(path: string) {
  return serveAll({
    'GET /v1/reference/lists': LISTS,
    [`GET ${path}/summary`]: SUMMARY,
    [`GET ${path}/gangs`]: paged([gang(), gang({ gangId: 2, gang: 'DP-DL01-G004', status: 'NO_MANDAL' })]),
  });
}

describe('C-03 Departmental Labour Computation', () => {
  it('SRS-G-54: draws the tiles and table, and a run figure stays a dash', async () => {
    serve('/v1/labour/computations/departmental');
    wrap(<DepartmentalComputation />, me(['C-03:VIEW']));
    const table = await screen.findByRole('table', { name: /Gang-wise Result/ });
    const row = (await within(table).findByText('DP-DL01-G002')).closest('tr') as HTMLElement;
    expect(within(row).getByText('96.5')).toBeInTheDocument();
    expect(within(row).getByText('Not deducted')).toBeInTheDocument();
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(4);
    expect(screen.getByText(t('c03.tile.incentiveSub'))).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Engine-specific rules' })).toBeInTheDocument();
    expect(await screen.findByText(/Sardar Not configured/)).toBeInTheDocument();
  });

  it('LBR-20d-3: the Maker presses Compute, is told why and nothing is sent', async () => {
    const calls = serve('/v1/labour/computations/departmental');
    wrap(<DepartmentalComputation />, me(['C-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    fireEvent.click(await screen.findByRole('button', { name: t('lbc.act.compute') }));
    expect((await screen.findAllByText(t('lbc.why.run.DEPT_LABOUR'))).length).toBeGreaterThan(0);
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
  });

  it('SRS-G-54: each action is off with the reason for a role the pack does not give it', async () => {
    serve('/v1/labour/computations/departmental');
    wrap(<DepartmentalComputation />, me(['C-03:VIEW'], { roles: ['LABOUR_DEALING_HAND'] }));
    expect(await screen.findByRole('button', { name: t('lbc.act.compute') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('lbc.act.approve') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('lbc.act.submit') })).toBeEnabled();
  });

  it('SRS-G-54: View shows what to do about the gang; Edit is off; Register opens O-03', async () => {
    serve('/v1/labour/computations/departmental');
    wrap(<DepartmentalComputation />, me(['C-03:VIEW']));
    fireEvent.click(await screen.findByRole('button', { name: t('lbc.viewName', ['DP-DL01-G004']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('lbc.fix.NO_MANDAL'))).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.getByRole('button', { name: t('lbc.editName', ['DP-DL01-G004']) })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: t('lbc.act.register') }));
    expect(push).toHaveBeenCalledWith('/payroll?s=O-03');
  });

  it('SRS-G-54: another month shown says so', async () => {
    serveAll({
      'GET /v1/reference/lists': LISTS,
      'GET /v1/labour/computations/departmental/summary': { ...SUMMARY, period: '2026-01' },
      'GET /v1/labour/computations/departmental/gangs': paged([]),
    });
    wrap(<DepartmentalComputation />, me(['C-03:VIEW']));
    expect(await screen.findByTestId('lbc-other-period')).toBeInTheDocument();
    expect(await screen.findByText(t('lbc.none'))).toBeInTheDocument();
  });
});

describe('C-04 and C-05', () => {
  it('LBR-21d-1: DPS shows whether IR and the GoI rate are configured, never a floor', async () => {
    serve('/v1/labour/computations/dps');
    wrap(<DpsComputation />, me(['C-04:VIEW']));
    const row = (await screen.findByText('DP-DL01-G002')).closest('tr') as HTMLElement;
    expect(within(row).getAllByText(t('lbc.notConfigured'))).toHaveLength(2);
    expect(screen.getByText(t('c04.tile.floorSub'))).toBeInTheDocument();
  });

  it('LBR-21d-1: a rate set with IR and GoI reads configured', async () => {
    serveAll({
      'GET /v1/reference/lists': LISTS,
      'GET /v1/labour/computations/dps/summary': {
        ...SUMMARY,
        rates: [{ role: 'SARDAR', mgw: 799, interimRelief: 5, goiRate: 800, basicWage: null, vda: null }],
      },
      'GET /v1/labour/computations/dps/gangs': paged([gang()]),
    });
    wrap(<DpsComputation />, me(['C-04:VIEW']));
    const row = (await screen.findByText('DP-DL01-G002')).closest('tr') as HTMLElement;
    await waitFor(() => expect(within(row).getAllByText(t('c04.configured'))).toHaveLength(2));
  });

  it('NWNP-22d-1: NWNP shows gang make-up, output and no-work hours as held', async () => {
    serve('/v1/labour/computations/nwnp');
    wrap(<NwnpComputation />, me(['C-05:VIEW']));
    const row = (await screen.findByText('DP-DL01-G002')).closest('tr') as HTMLElement;
    expect(within(row).getByText('1 / 1')).toBeInTheDocument();
    expect(within(row).getByText('11,640')).toBeInTheDocument();
    expect(screen.getByText(t('c05.tile.computedSub'))).toBeInTheDocument();
  });
});

describe('C-06 Labour Incentive Computation', () => {
  function incentive() {
    return serveAll({
      'GET /v1/reference/lists': LISTS,
      'GET /v1/labour/incentives/summary': {
        period: '2026-10',
        depots: 2,
        depotsWithNorms: 1,
        gangsComputed: 0,
        recomputations: 1,
        value: null,
      },
      'GET /v1/labour/incentives/gangs': paged([gang({ status: 'NORM_MISSING', mln: null, bags: null })]),
      'GET /v1/labour/incentives/recomputations': paged([
        {
          id: 1,
          gang: 'DP-DL01-G002',
          period: '202601',
          reason: 'LATE_ATTACHMENT',
          original: 68300,
          revised: 71920,
        },
      ]),
      'GET /v1/labour/incentives/basics': paged([
        {
          id: 1,
          labourCode: 'L10701',
          name: 'Harpal Sandhu',
          salaryBasic: null,
          incentiveBasic: 16900,
          effectiveFrom: '2026-01-01',
        },
      ]),
    });
  }

  it('LBR-20d-2: the tables as drawn; above-norm bags and the amount wait for the run', async () => {
    incentive();
    wrap(<IncentiveComputation />, me(['C-06:VIEW']));
    expect(await screen.findByText('1 / 2')).toBeInTheDocument();
    expect(await screen.findByText('Norm not loaded')).toBeInTheDocument();
    expect(await screen.findByText(t('c06.reason.LATE_ATTACHMENT'))).toBeInTheDocument();
    expect(await screen.findByText('L10701 Harpal Sandhu')).toBeInTheDocument();
  });

  it('SRS-G-54: the Checker approves nothing yet; the Maker action is off for it', async () => {
    const calls = incentive();
    wrap(<IncentiveComputation />, me(['C-06:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    expect(await screen.findByRole('button', { name: t('c06.act.compute') })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: t('c06.act.approve') }));
    expect((await screen.findAllByText(t('c06.why.run'))).length).toBeGreaterThan(0);
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
  });

  it('SRS-G-54: the Maker may press Compute; the summary opens I-02', async () => {
    incentive();
    wrap(<IncentiveComputation />, me(['C-06:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    expect(await screen.findByRole('button', { name: t('c06.act.compute') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('c06.act.approve') })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: t('c06.act.summary') }));
    expect(push).toHaveBeenCalledWith('/payroll?s=I-02');
  });
});

describe('gangTone', () => {
  it('SRS-G-54: reads each state as the pack colours it', () => {
    expect(gangTone('NOT_COMPUTED')).toBe('p-gray');
    expect(gangTone('COMPUTED')).toBe('p-ok');
    expect(gangTone('NO_MANDAL')).toBe('p-bad');
  });
});
