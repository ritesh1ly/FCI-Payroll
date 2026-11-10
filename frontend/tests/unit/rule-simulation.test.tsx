import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RuleSimulation } from '@/modules/configuration/api/rulesApi';
import { RuleSimulationPanel } from '@/modules/configuration/components/RuleSimulation';
import { backend, me, paged, problem, t, wrap } from './support/harness';

/**
 * M-08 impact simulation (plan 06 phase 06e; SRS-19-4): the runs of the version awaiting approval
 * with their groups and a stale alert; a run narrowed to a payroll type and an office; a refusal
 * shown; a reader sees the runs without the run controls.
 */

afterEach(() => vi.unstubAllGlobals());

const RUN: RuleSimulation = {
  id: 3,
  approvalId: 40,
  ruleKey: 'DA.IDA.RATE',
  payrollType: 'STAFF',
  officeId: null,
  requestedBy: 'su.hq@fci-demo.test',
  requestedAt: '2026-10-27T05:00:00Z',
  population: 120,
  impact: {
    sampled: 100,
    inScope: 90,
    changed: 90,
    groups: [
      {
        payrollType: 'STAFF',
        officeId: 7,
        officeName: 'Delhi Region',
        members: 90,
        current: 'NOT_CONFIGURED',
        proposed: '55',
        changes: true,
      },
      {
        payrollType: 'STAFF',
        officeId: 9,
        officeName: null,
        members: 10,
        current: '50',
        proposed: 'NOT_APPLICABLE',
        changes: true,
      },
    ],
  },
  ruleVersionId: null,
  inputsChanged: true,
};

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/rules/DA.IDA.RATE/simulations': (url: string) => paged([RUN], 1, url),
    'GET /v1/rules/simulation-offices': () => [
      { id: 7, code: 'RO-DL', name: 'Delhi Region', type: 'REGION' },
    ],
    ...over,
  });
}

describe('M-08 impact simulation', () => {
  it('SRS_19_4_theRunsOfThePendingVersionShowTheirGroupsAndAStaleAlert', async () => {
    routes();
    wrap(
      <RuleSimulationPanel ruleKey="DA.IDA.RATE" mayRun={false} onClose={() => undefined} />,
      me(['M-08:VIEW']),
    );
    const groups = await screen.findByRole('table', { name: t('m08.sim.groups', [3]) });
    expect(groups).toHaveTextContent('Delhi Region');
    expect(groups).toHaveTextContent(t('m08.sim.NOT_CONFIGURED'));
    expect(groups).toHaveTextContent(t('m08.sim.NOT_APPLICABLE'));
    expect(groups).toHaveTextContent('55');
    expect(screen.getByText(t('m08.sim.summary', [120, 100, 90, 90]))).toBeInTheDocument();
    expect(screen.getByText(t('m08.sim.stale'), { exact: false })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('m08.sim.run') })).toBeNull();
  });

  it('SRS_19_4_aRunIsNarrowedToAPayrollTypeAndAnOfficeAndARefusalIsShown', async () => {
    let refuse = true;
    const calls = routes({
      'POST /v1/rules/DA.IDA.RATE/simulations': () =>
        refuse ? problem(409, 'RULE-409-NO-PROPOSAL', ['DA.IDA.RATE']) : { status: 201, body: RUN },
    });
    const closed = vi.fn();
    wrap(
      <RuleSimulationPanel ruleKey="DA.IDA.RATE" mayRun onClose={closed} />,
      me(['M-08:VIEW', 'M-08:APPROVE']),
    );
    const scope = await screen.findByLabelText(t('m08.sim.scope'));
    await within(scope).findByRole('option', { name: 'RO-DL · Delhi Region' });
    await userEvent.selectOptions(within(scope).getByLabelText(t('m08.sim.payrollType')), 'STAFF');
    await userEvent.selectOptions(within(scope).getByLabelText(t('m08.sim.office')), '7');
    await userEvent.click(within(scope).getByRole('button', { name: t('m08.sim.run') }));
    expect(await screen.findByText(t('error.RULE-409-NO-PROPOSAL', ['DA.IDA.RATE']))).toBeInTheDocument();
    refuse = false;
    await userEvent.selectOptions(within(scope).getByLabelText(t('m08.sim.office')), '');
    await userEvent.click(within(scope).getByRole('button', { name: t('m08.sim.run') }));
    expect(await screen.findByText(t('m08.sim.done'))).toBeInTheDocument();
    const posts = calls.filter((c) => c.method === 'POST').map((c) => c.body);
    expect(posts).toEqual([
      { payrollType: 'STAFF', officeId: 7 },
      { payrollType: 'STAFF', officeId: null },
    ]);
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(closed).toHaveBeenCalled();
  });

  it('SRS_19_4_noRunYetSaysSo', async () => {
    routes({ 'GET /v1/rules/DA.IDA.RATE/simulations': (url: string) => paged([], 0, url) });
    wrap(
      <RuleSimulationPanel ruleKey="DA.IDA.RATE" mayRun={false} onClose={() => undefined} />,
      me(['M-08:VIEW']),
    );
    expect(await screen.findByText(t('m08.sim.none'))).toBeInTheDocument();
  });
});
