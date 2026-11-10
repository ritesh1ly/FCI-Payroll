import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SimulationRun } from '@/modules/jobs';
import { membersOf, rateOk } from '@/modules/jobs/components/SimulationRun';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * C-09 General (Simulation) Run (plan 05 phase 05i; SRS-15-7, SRS C-09, D-17 to D-21): the amber
 * banner and SIMULATION watermark, the run form validated as the server does, an interactive result
 * with its member rows, a queued background run with its progress, an out-of-date record, and the
 * run disabled with its reason while the settings are not configured or for a viewer.
 */

afterEach(() => vi.unstubAllGlobals());

const tiles = (over: object = {}) => ({
  period: '2026-10',
  unitCode: 'DH001',
  unitLabel: 'Delhi',
  maxSelection: 1000,
  interactiveLimit: 5,
  runsThisCycle: 3,
  mineThisCycle: 1,
  largestThisCycle: 40,
  retained: 9,
  ...over,
});

const runRow = (over: object = {}) => ({
  id: 7,
  reference: 'SIM-7',
  requestedBy: 'maker.delhi',
  requestedAt: '2026-10-25T06:00:00Z',
  period: '2026-10',
  population: 'MEMBER_LIST',
  populationCount: 2,
  whatIf: 'DA_RATE',
  daIda: 60,
  daCda: null,
  mode: 'INTERACTIVE',
  status: 'COMPLETE',
  done: 2,
  jobRequestId: null,
  resultsRetained: false,
  failure: null,
  ...over,
});

const side = (net: number) => ({
  computed: 2,
  stopped: 0,
  notComputed: { 'CCA rates not seeded': 1 },
  gross: net,
  deductions: 0,
  net,
});

const detail = (over: object = {}) => ({
  run: runRow(),
  asIs: side(1000),
  whatIf: side(1100),
  components: [{ payCode: 111, name: 'DA', asIs: 500, whatIf: 600 }],
  current: true,
  members: [
    {
      eis: 'E001',
      name: 'Asha Rao',
      status: 'COMPUTED',
      reason: null,
      gross: 500,
      net: 500,
      whatIfGross: 550,
      whatIfNet: 550,
    },
  ],
  ...over,
});

function routes(
  over: Record<string, (url: string, init?: RequestInit) => unknown> = {},
  rows: unknown[] = [],
) {
  return backend({
    'GET /v1/simulations/tiles': () => tiles(),
    'GET /v1/simulations?': (url) => paged(rows, rows.length, url),
    ...over,
  });
}

const MAKER = ['C-09:VIEW', 'C-09:RUN'];

describe('C-09 General (Simulation) Run', () => {
  it('SRS-15-7: the employee numbers and the DA rate are read as the server reads them', () => {
    expect(membersOf('E1\nE2, E3,,\n ')).toEqual(['E1', 'E2', 'E3']);
    expect(rateOk('58')).toBe(true);
    expect(rateOk('58.1234')).toBe(true);
    expect(rateOk('1234567890.5')).toBe(true);
    expect(rateOk('12345678901')).toBe(false);
    expect(rateOk('5.12345')).toBe(false);
    expect(rateOk('5.')).toBe(false);
    expect(rateOk('.5')).toBe(false);
    expect(rateOk('5.1.2')).toBe(false);
    expect(rateOk('-5')).toBe(false);
    expect(rateOk('5a')).toBe(false);
  });

  it('SRS-15-7, OD-125: the drawn captions and the filters stand for a viewer; the count stays on the tile', async () => {
    routes();
    wrap(<SimulationRun />, me(['C-09:VIEW']));
    const retained = await screen.findByText(t('c09w.tile.retainedSub'));
    expect(retained).toHaveAttribute('title', t('c09w.tile.retainedCount', [9]));
    expect(screen.getByText(t('c09w.tile.largestSub'))).toBeTruthy();
    expect(screen.getByRole('note')).toHaveTextContent(t('c09.bannerText'));
    expect(screen.getByRole('combobox', { name: t('c09.population') })).toBeEnabled();
    expect(screen.getByRole('combobox', { name: t('c09.whatIf') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('c09w.request') })).toBeDisabled();
  });

  // SRS-15-9: a General Run result is marked as such on the screen.
  // SC-05-0009 (a General Run result is marked on every screen), SC-05-0117 (the simulation marker
  // on every output).
  it('SRS-15-7: a member list with a what-if DA rate is computed at once under the SIMULATION watermark', async () => {
    const calls = routes({ 'POST /v1/simulations': () => ({ status: 201, body: detail() }) });
    wrap(<SimulationRun />, me(MAKER));
    expect(screen.getByRole('note')).toHaveTextContent(t('c09.banner'));
    expect(await screen.findByText(t('c09.tile.runsSub', [1, 'DH001', '2026-10']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c09w.request') }));
    const form = screen.getByRole('dialog', { name: t('c09.new') });
    const run = within(form).getByRole('button', { name: t('c09.run') });
    await userEvent.selectOptions(within(form).getByLabelText(t('c09.population')), 'MEMBER_LIST');
    expect(run).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(t('c09.members')), 'E001{enter}E002');
    await userEvent.selectOptions(within(form).getByLabelText(t('c09.whatIf')), 'DA_RATE');
    expect(run).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(t('c09.daIda')), '60.12345');
    expect(run).toBeDisabled();
    await userEvent.clear(within(form).getByLabelText(t('c09.daIda')));
    await userEvent.type(within(form).getByLabelText(t('c09.daIda')), '60');
    await userEvent.click(run);
    expect(await screen.findByText(t('c09.done', ['SIM-7']))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/simulations')).toEqual([
      { population: 'MEMBER_LIST', members: ['E001', 'E002'], whatIf: 'DA_RATE', daIda: '60' },
    ]);
    const output = document.querySelector('.sim-output');
    expect(output?.getAttribute('data-watermark')).toBe(t('c09.watermark'));
    const members = screen.getByRole('table', { name: t('c09.memberRows') });
    expect(within(members).getByText('E001')).toBeTruthy();
    expect(within(members).getByText('550')).toBeTruthy();
    expect(screen.getByRole('table', { name: t('c09.components') })).toHaveTextContent('111 DA');
    expect(screen.getByText(t('c09.rowsNotKept'))).toBeTruthy();
  });

  it('SRS-15-7: a single member needs exactly one valid number; the office needs none', async () => {
    const calls = routes({
      'POST /v1/simulations': () => ({ status: 201, body: detail({ whatIf: null, members: [] }) }),
    });
    wrap(<SimulationRun />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('c09w.request') }));
    const form = await screen.findByRole('dialog', { name: t('c09.new') });
    const run = within(form).getByRole('button', { name: t('c09.run') });
    await userEvent.selectOptions(within(form).getByLabelText(t('c09.population')), 'MEMBER');
    await userEvent.type(within(form).getByLabelText(t('c09.member')), 'E001, E002');
    expect(run).toBeDisabled();
    await userEvent.selectOptions(within(form).getByLabelText(t('c09.population')), 'OFFICE');
    await userEvent.selectOptions(within(form).getByLabelText(t('c09.whatIf')), 'DA_RATE');
    await userEvent.type(within(form).getByLabelText(t('c09.daCda')), '61');
    await userEvent.click(run);
    expect(await screen.findByText(t('c09.done', ['SIM-7']))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/simulations')).toEqual([
      { population: 'OFFICE', members: [], whatIf: 'DA_RATE', daCda: '61' },
    ]);
  });

  it('SRS-15-7: above the interactive limit the run is queued and its progress shows', async () => {
    const queued = detail({
      run: runRow({
        id: 8,
        reference: 'SIM-8',
        mode: 'BACKGROUND',
        status: 'QUEUED',
        done: 0,
        jobRequestId: 44,
      }),
      asIs: null,
      whatIf: null,
      components: [],
      current: null,
      members: [],
    });
    routes({
      'POST /v1/simulations': () => ({ status: 202, body: queued }),
      'GET /v1/simulations/8': () => ({
        ...queued,
        run: { ...queued.run, status: 'RUNNING', done: 3, populationCount: 40 },
      }),
    });
    wrap(<SimulationRun />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('c09w.request') }));
    const form = await screen.findByRole('dialog', { name: t('c09.new') });
    await userEvent.click(within(form).getByRole('button', { name: t('c09.run') }));
    expect(await screen.findByText(t('c09.queued', ['SIM-8']))).toBeTruthy();
    expect(await screen.findByText(t('c09.progress', [t('c09.status.RUNNING'), 3, 40, 44]))).toBeTruthy();
  });

  it('SRS-15-7: an opened record that is out of date or failed says so; the list shows the record kept', async () => {
    routes(
      {
        'GET /v1/simulations/7': () => detail({ current: false, members: [] }),
        'GET /v1/simulations/9': () =>
          detail({
            run: runRow({ id: 9, reference: 'SIM-9', status: 'FAILED', failure: 'ABANDONED' }),
            asIs: null,
            whatIf: null,
            components: [],
            current: null,
            members: [],
          }),
      },
      [
        runRow(),
        runRow({ id: 9, reference: 'SIM-9', whatIf: 'AS_IS', status: 'FAILED', mode: 'BACKGROUND' }),
      ],
    );
    wrap(<SimulationRun />, me(['C-09:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('c09w.viewOf', ['SIM-7']) }));
    expect(await screen.findByText(t('c09.outOfDate'))).toBeTruthy();
    expect(screen.getAllByText(t('c09.retainedValue'))).toHaveLength(2);
    expect(screen.getByText(t('c09.whatIf.daRates', [60, '—']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c09w.viewOf', ['SIM-9']) }));
    expect(await screen.findByText(t('c09.failed', ['ABANDONED']))).toBeTruthy();
    // SRS-15-7: a viewer keeps the drawn request, disabled with the reason; no form opens.
    expect(screen.queryByRole('dialog', { name: t('c09.new') })).toBeNull();
    expect(screen.getByRole('button', { name: t('c09w.request') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('c09w.request') })).toHaveAttribute(
      'data-why',
      t('c09w.why.request'),
    );
    expect(screen.getByRole('button', { name: t('c09w.approve') })).toHaveAttribute(
      'data-why',
      t('c09w.why.approve'),
    );
  });

  it('SRS-15-7: a failure without a cause, a CDA-only rate and a member not computed with its reason', async () => {
    routes(
      {
        'GET /v1/simulations/11': () =>
          detail({
            run: runRow({
              id: 11,
              reference: 'SIM-11',
              daIda: null,
              daCda: 61,
              status: 'FAILED',
              failure: null,
            }),
            members: [
              {
                eis: 'E002',
                name: 'Ravi Kumar',
                status: 'NOT_COMPUTED',
                reason: 'CCA rates not seeded',
                gross: null,
                net: null,
                whatIfGross: null,
                whatIfNet: null,
              },
            ],
          }),
      },
      [runRow({ id: 11, reference: 'SIM-11', daIda: null, daCda: 61, status: 'FAILED' })],
    );
    wrap(<SimulationRun />, me(['C-09:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('c09w.viewOf', ['SIM-11']) }));
    expect(await screen.findByText(t('c09.failed', ['—']))).toBeTruthy();
    expect(screen.getAllByText(t('c09.whatIf.daRates', ['—', 61])).length).toBeGreaterThan(0);
    expect(screen.getByText('CCA rates not seeded')).toBeTruthy();
  });

  it('SRS-15-7: while the limits are not configured the run is disabled with the reason', async () => {
    backend({
      'GET /v1/simulations/tiles': () => tiles({ interactiveLimit: null }),
      'GET /v1/simulations?': (url) => paged([], 0, url),
    });
    wrap(<SimulationRun />, me(MAKER));
    expect(await screen.findByText(t('c09.tile.notSet'))).toBeTruthy();
    expect(screen.getByText(t('c09.none'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c09w.request') }));
    expect(screen.getByRole('button', { name: t('c09.run') })).toHaveAttribute(
      'title',
      t('c09.notConfigured'),
    );
  });

  it('SRS-15-7: a refused run is read inside the form, which stays open; Cancel closes it', async () => {
    routes({ 'POST /v1/simulations': () => problem(422, 'SIM-422-NOT-CONFIGURED') });
    wrap(<SimulationRun />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('c09w.request') }));
    const form = screen.getByRole('dialog', { name: t('c09.new') });
    await userEvent.click(within(form).getByRole('button', { name: t('c09.run') }));
    expect(await within(form).findByRole('alert')).toBeTruthy();
    await userEvent.click(within(form).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog', { name: t('c09.new') })).toBeNull();
  });

  it('SC-05-0117: the toolbar filters preset the form; the export carries the list search', async () => {
    const calls = routes({ 'POST /v1/simulations': () => ({ status: 201, body: detail() }) });
    wrap(<SimulationRun />, me([...MAKER, 'C-09:EXPORT']));
    await screen.findByText(t('c09.none'));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: t('c09.population') }), 'MEMBER');
    await userEvent.selectOptions(screen.getByRole('combobox', { name: t('c09.whatIf') }), 'DA_RATE');
    await userEvent.type(screen.getByRole('searchbox'), 'rao');
    await screen.findByText(t('c09.none'));
    expect(calls.some((c) => c.url.includes('search=rao'))).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('c09w.request') }));
    const form = screen.getByRole('dialog', { name: t('c09.new') });
    expect(within(form).getByLabelText(t('c09.population'))).toHaveValue('MEMBER');
    expect(within(form).getByLabelText(t('c09.whatIf'))).toHaveValue('DA_RATE');
    // OD-29: the pay-scale what-if is drawn and not offered.
    const scale = screen.getByRole('option', { name: t('c09w.filter.scale') }) as HTMLOptionElement;
    expect(scale.disabled).toBe(true);
    expect(scale.title).toBe(t('c09w.why.scale'));
  });

  it('WF-021: Approve Simulation Request sends nothing: SRS-15-7 has no approval step', async () => {
    const calls = routes();
    wrap(<SimulationRun />, me([...MAKER, 'C-02:APPROVE']));
    await userEvent.click(await screen.findByRole('button', { name: t('c09w.approve') }));
    expect(await screen.findByText(t('c09w.why.noApproval'))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('C09-4: the row Edit stays off with the drawn reason; no export right, a reason and no link', async () => {
    routes({}, [runRow()]);
    wrap(<SimulationRun />, me(['C-09:VIEW']));
    const row = (await screen.findByText('SIM-7')).closest('tr') as HTMLElement;
    expect(within(row).getByRole('button', { name: t('wf.edit') })).toHaveAttribute(
      'data-why',
      t('c09w.why.edit'),
    );
    expect(screen.getByRole('button', { name: t('wf.export') })).toHaveAttribute(
      'data-guard',
      t('c09w.why.export'),
    );
  });
});
