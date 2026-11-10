import { fireEvent, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DepartmentalComputation,
  DpsComputation,
  IncentiveComputation,
  NwnpComputation,
} from '@/modules/labour';
import { amount } from '@/shared/ui';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

/**
 * C-03, C-04, C-05 and C-06 as drawn (Wireframes v6.1, OD-121; SRS-G-54, LBR-20d-2, LBR-21d-1, NWNP-22d-1) coverage
 * gaps: a gang the run has not counted, the rate trace of every role, a stored wage and incentive value, the Payroll
 * Checker's approve, the drawn I-02 and O-03 links per engine, and a profile without period or roles.
 */

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));

const serveAll = (routes: Record<string, unknown>) =>
  backend(Object.fromEntries(Object.entries(routes).map(([k, v]) => [k, () => v])));

afterEach(() => {
  vi.unstubAllGlobals();
  push.mockReset();
});

const LISTS = referenceLists({ LABOUR_GANG_STATUS: [['NOT_COMPUTED', 'Not computed']] });

const RATES = [
  { role: 'SARDAR', mgw: 799, interimRelief: null, goiRate: null, basicWage: 24800, vda: null },
  { role: 'MANDAL', mgw: 786, interimRelief: null, goiRate: null, basicWage: 23900, vda: null },
  { role: 'HANDLING', mgw: 770, interimRelief: null, goiRate: null, basicWage: 22600, vda: null },
  { role: 'ANCILLARY', mgw: 760, interimRelief: null, goiRate: null, basicWage: null, vda: null },
];

const SUMMARY = {
  period: '2026-10',
  population: 8,
  depots: 1,
  gangs: 1,
  computed: 1,
  notReady: 0,
  wage: 182400,
  rates: RATES,
};

const BARE_GANG = {
  gangId: 1,
  gang: 'DP-DL01-G002',
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  labour: 4,
  sardar: 1,
  mandal: 1,
  handling: 2,
  ancillary: 0,
  presentDays: null,
  presentLabour: 4,
  slips: 0,
  bags: null,
  noWorkHours: null,
  mln: null,
  incentive: null,
  status: 'NOT_COMPUTED',
};

function serve(path: string, summary: object = SUMMARY) {
  return serveAll({
    'GET /v1/reference/lists': LISTS,
    [`GET ${path}/summary`]: summary,
    [`GET ${path}/gangs`]: paged([BARE_GANG]),
  });
}

const PERIOD = { period: '2026-10' };

describe('C-03 / C-04 / C-05 coverage gaps', () => {
  it('SRS-G-54: a gang the run has not counted reads — for present days, in the row and its View', async () => {
    serve('/v1/labour/computations/departmental');
    wrap(<DepartmentalComputation />, me(['C-03:VIEW'], PERIOD));
    const row = (await screen.findByText('DP-DL01-G002')).closest('tr') as HTMLElement;
    // Present days, four run figures and the incentive: no figure is guessed.
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(5);
    expect(within(row).queryByText('96.5')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t('lbc.viewName', ['DP-DL01-G002']) }));
    const dialog = await screen.findByRole('dialog');
    // Present days and bags.
    expect(within(dialog).getAllByText('—')).toHaveLength(2);
  });

  it('SRS-3-13: the Departmental trace reads the Basic of each role and the stored wage', async () => {
    serve('/v1/labour/computations/departmental');
    wrap(<DepartmentalComputation />, me(['C-03:VIEW'], PERIOD));
    await screen.findByText('DP-DL01-G002');
    expect(
      await screen.findByText(t('c03.tr.basic.v', [amount(24800), amount(23900), amount(22600)])),
    ).toBeInTheDocument();
    expect(screen.getByText(amount(182400))).toBeInTheDocument();
  });

  it('SRS-G-54: the Payroll Checker’s approve says why; the drawn work-slips opens I-02', async () => {
    const calls = serve('/v1/labour/computations/departmental');
    wrap(<DepartmentalComputation />, me(['C-03:VIEW'], { ...PERIOD, roles: ['PAYROLL_CHECKER'] }));
    const approve = await screen.findByRole('button', { name: t('lbc.act.approve') });
    expect(approve).toBeEnabled();
    fireEvent.click(approve);
    expect((await screen.findAllByText(t('lbc.why.run.DEPT_LABOUR'))).length).toBeGreaterThan(1);
    expect(screen.getByRole('button', { name: t('lbc.act.compute') })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: t('lbc.act.slips') }));
    expect(push).toHaveBeenCalledWith('/payroll?s=I-02');
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
  });

  it('SRS-G-54: a profile without period or roles: every action is off with its reason', async () => {
    serve('/v1/labour/computations/departmental');
    wrap(<DepartmentalComputation />, me(['C-03:VIEW'], { period: undefined, roles: undefined } as never));
    for (const [name, why] of [
      ['lbc.act.compute', 'lbc.why.compute'],
      ['lbc.act.submit', 'lbc.why.submit'],
      ['lbc.act.approve', 'lbc.why.approve'],
    ] as const) {
      const button = await screen.findByRole('button', { name: t(name) });
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('data-why', t(why));
    }
  });

  it('LBR-21d-1: the DPS register opens O-03 in the DPS view', async () => {
    serve('/v1/labour/computations/dps');
    wrap(<DpsComputation />, me(['C-04:VIEW'], PERIOD));
    await screen.findByText('DP-DL01-G002');
    fireEvent.click(screen.getByRole('button', { name: t('lbc.act.register') }));
    expect(push).toHaveBeenCalledWith('/payroll?s=O-03&engine=DPS');
  });

  it('NWNP-22d-1: no bags or no-work hours held read —; the trace reads the MGW of every role', async () => {
    serve('/v1/labour/computations/nwnp');
    wrap(<NwnpComputation />, me(['C-05:VIEW'], PERIOD));
    const row = (await screen.findByText('DP-DL01-G002')).closest('tr') as HTMLElement;
    expect(within(row).getByText('1 / 1')).toBeInTheDocument();
    // Bags, the run figure and no-work hours, then the incentive.
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(3);
    expect(within(row).queryByText('11,640')).not.toBeInTheDocument();
    expect(await screen.findByText(`${amount(770)} / ${amount(760)}`)).toBeInTheDocument();
    expect(screen.getByText(`${amount(799)} / ${amount(786)}`)).toBeInTheDocument();
  });
});

describe('C-06 coverage gaps', () => {
  function incentive(over: Record<string, unknown> = {}) {
    return serveAll({
      'GET /v1/reference/lists': LISTS,
      'GET /v1/labour/incentives/summary': {
        period: '2026-10',
        depots: 2,
        depotsWithNorms: 1,
        gangsComputed: 1,
        recomputations: 1,
        value: 71920,
      },
      'GET /v1/labour/incentives/gangs': paged([
        { ...BARE_GANG, bags: 11640, mln: 3600, incentive: 4120, status: 'NOT_COMPUTED' },
      ]),
      'GET /v1/labour/incentives/recomputations': paged([
        {
          id: 1,
          gang: 'DP-DL01-G002',
          period: '202601',
          reason: 'LATE_ATTACHMENT',
          original: null,
          revised: null,
        },
      ]),
      'GET /v1/labour/incentives/basics': paged([
        {
          id: 1,
          labourCode: 'L10701',
          name: 'Harpal Sandhu',
          salaryBasic: 16900,
          incentiveBasic: 16900,
          effectiveFrom: '2026-01-01',
        },
      ]),
      ...over,
    });
  }

  it('LBR-20d-2: stored bags, norm, incentive and value read as amounts; a recomputation without amounts reads —', async () => {
    incentive();
    wrap(<IncentiveComputation />, me(['C-06:VIEW'], PERIOD));
    const gangRow = (await screen.findByText(/DP-DL01-G002/, { selector: 'td, td *' })).closest(
      'tr',
    ) as HTMLElement;
    expect(within(gangRow).getByText('11,640')).toBeInTheDocument();
    expect(within(gangRow).getByText('3,600')).toBeInTheDocument();
    expect(within(gangRow).getByText(amount(4120))).toBeInTheDocument();
    expect(screen.getByText(amount(71920))).toBeInTheDocument();
    const recompute = (await screen.findByText(t('c06.reason.LATE_ATTACHMENT'))).closest('tr') as HTMLElement;
    // Original, revised and the posting (always a dash until the run).
    expect(within(recompute).getAllByText('—')).toHaveLength(3);
    const basic = (await screen.findByText('L10701 Harpal Sandhu')).closest('tr') as HTMLElement;
    expect(within(basic).getAllByText(amount(16900)).length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByTestId('c06-other-period')).not.toBeInTheDocument();
  });

  it('SRS-G-54: a summary of another month than the profile period says so', async () => {
    incentive({
      'GET /v1/labour/incentives/summary': {
        period: '2026-01',
        depots: 2,
        depotsWithNorms: 1,
        gangsComputed: 0,
        recomputations: 0,
        value: null,
      },
    });
    wrap(<IncentiveComputation />, me(['C-06:VIEW'], PERIOD));
    expect(await screen.findByTestId('c06-other-period')).toBeInTheDocument();
  });

  it('SRS-G-54: another month shown says so; a profile without roles sees Compute and Approve off', async () => {
    incentive({
      'GET /v1/labour/incentives/summary': {
        period: '2026-01',
        depots: 0,
        depotsWithNorms: 0,
        gangsComputed: 0,
        recomputations: 0,
        value: null,
      },
    });
    wrap(<IncentiveComputation />, me(['C-06:VIEW'], { period: undefined, roles: undefined } as never));
    expect(await screen.findByRole('button', { name: t('c06.act.compute') })).toHaveAttribute(
      'data-why',
      t('c06.why.maker'),
    );
    expect(screen.getByRole('button', { name: t('c06.act.approve') })).toHaveAttribute(
      'data-why',
      t('c06.why.checker'),
    );
  });
});
