import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CycleStatusHome } from '@/modules/jobs';
import { periodLabel } from '@/shared/session';
import { backend, currentPeriod, me, paged, t, wrap } from './support/harness';

/** FY 2026–27 for a period of 2026-10 (April to March), as the band shows it. */
function fiscalYear(period: string): string {
  const [y = 0, m = 0] = period.split('-').map(Number);
  return m >= 4 ? `${y}–${String(y + 1).slice(2)}` : `${y - 1}–${String(y).slice(2)}`;
}

/**
 * D-01 Home — Cycle Status as Wireframes v6.1 draws it (OD-121; SRS D-01, D-19): the period band
 * with the three gated actions and the step track, the eight KPI tiles with their badges, the
 * computation status by payroll type with its Run log, what is blocking the cycle with the
 * worklist, the control items with their screens; a figure without a source names the reason,
 * never a zero; the Headquarters roll-up is read-only.
 */

afterEach(() => vi.unstubAllGlobals());

const status = (over: object = {}) => ({
  period: currentPeriod(),
  scopeCode: 'DH001',
  scopeLabel: 'Delhi',
  rollUp: false,
  units: 1,
  byStatus: { NOT_INITIATED: 0, INITIATED: 0, FROZEN: 0, COMPUTED: 1, VERIFIED: 0, LOCKED: 0 },
  kpis: {
    payable: 12,
    gross: 120000,
    deductions: 0,
    net: 120000,
    exceptions: { count: 2, unavailable: null },
    pendingApproval: { count: 1, unavailable: null },
    retroEvents: { count: 4, unavailable: null },
    interfaceHealth: { count: null, unavailable: 'AWAITING_FCI' },
  },
  types: [
    {
      payrollType: 'STAFF',
      population: 12,
      computed: 12,
      exceptions: 2,
      status: 'COMPLETE',
      unavailable: null,
    },
    {
      payrollType: 'DEPT_LABOUR',
      population: null,
      computed: null,
      exceptions: null,
      status: null,
      unavailable: 'AWAITING_FCI',
    },
  ],
  blockers: [
    { code: 'NET_PAY_FLOOR', figure: { count: 1, unavailable: null }, fixOn: 'C-02' },
    { code: 'BANK_DETAILS', figure: { count: 0, unavailable: null }, fixOn: 'M-01' },
    { code: 'HRMS_CHANGE_PENDING', figure: { count: 5, unavailable: null }, fixOn: null },
  ],
  ...over,
});

const band = () => screen.getByText(t('d01.step.compute')).closest('.pband') as HTMLElement;

describe('D-01 Home — Cycle Status', () => {
  it('SRS D-01, SRS-G-54: the period band, the KPI tiles, the payroll types and the blocking panel as drawn; a figure without a source says why', async () => {
    backend({ 'GET /v1/cycle-status': () => status() });
    wrap(<CycleStatusHome />, me(['D-01:VIEW', 'C-02:CREATE'], { period: currentPeriod() }));
    expect(await screen.findByText(periodLabel(currentPeriod()))).toBeTruthy();
    expect(band()).toHaveTextContent(
      t('d01.band.line', [fiscalYear(currentPeriod()), currentPeriod().replace('-', ''), 'Delhi']),
    );
    expect(within(band()).getByText(t('d01.state.COMPUTED'))).toBeTruthy();
    // The step track of a COMPUTED cycle: Initiate, Freeze and Compute done, Verify now, the rest ahead.
    const steps = within(band()).getAllByText(
      /^(Initiate|Freeze Inputs|Compute|Verify|Month-End Lock|Post to EBS AP|Supplementary \/ MDP)$/,
    );
    expect(steps.map((s) => s.closest('.st')?.className)).toEqual([
      'st done',
      'st done',
      'st done',
      'st now',
      'st ',
      'st ',
      'st ',
    ]);
    for (const key of [
      'payable',
      'gross',
      'deductions',
      'net',
      'exceptions',
      'pending',
      'retro',
      'interface',
    ]) {
      expect(screen.getByText(t(`d01.kpi.${key}`))).toBeTruthy();
    }
    expect(screen.getByText(t('d01.sub.net'))).toBeTruthy();
    // The retro-events figure (the step dots count 1-7 too, so look inside the tiles).
    expect(
      within(screen.getByText(t('d01.kpi.retro')).closest('.tile') as HTMLElement).getByText('4'),
    ).toBeTruthy();
    expect(screen.getAllByText(t('d01.unavailable.AWAITING_FCI')).length).toBeGreaterThan(0);
    const types = screen.getByRole('row', { name: new RegExp(t('d01.payrollType.DEPT_LABOUR')) });
    expect(types).toHaveTextContent(t('d01.unavailable.AWAITING_FCI'));
    expect(types).toHaveTextContent(t('d01.engineOf.DEPT_LABOUR'));
    const staff = screen.getByRole('row', { name: new RegExp(t('d01.payrollType.STAFF')) });
    expect(staff).toHaveTextContent(t('d01.engineState.COMPLETE'));
    expect(screen.getByRole('link', { name: t('d01.runLog') })).toHaveAttribute('href', '/payroll/?s=C-13');
    expect(screen.getByRole('link', { name: t('d01.openWorklist') })).toHaveAttribute(
      'href',
      '/payroll/?s=X-05',
    );
    expect(screen.getByText(t('d01.blocker.NET_PAY_FLOOR'))).toBeTruthy();
    expect(screen.getByText(t('d01.blocker.BANK_DETAILS.basis'))).toBeTruthy();
    // The control items with their screens; a figure its module does not report yet says so.
    expect(screen.getByText(t('d01.control.hrms.text', [5]))).toBeTruthy();
    expect(screen.getByText(t('d01.control.fallback.text', [t('d01.unavailable.NOT_YET')]))).toBeTruthy();
    for (const code of ['I-21', 'X-07', 'M-09']) {
      expect(screen.getByRole('link', { name: t('d01.open', [code]) })).toHaveAttribute(
        'href',
        `/payroll/?s=${code}`,
      );
    }
    expect(screen.getByRole('link', { name: t('d01.resumeCompute') })).toHaveAttribute(
      'href',
      '/payroll/?s=C-13',
    );
    expect(screen.getByRole('link', { name: t('d01.checklist') })).toHaveAttribute(
      'href',
      '/payroll/?s=C-01',
    );
  });

  it('SRS l.1604, SRS-15-31 (D-01 #2, #5): Unfreeze is offered only between the freeze and the lock, else drawn disabled with its reason', async () => {
    const offered = async (byStatus: Record<string, number>, rollUp = false) => {
      backend({ 'GET /v1/cycle-status': () => status({ byStatus, rollUp }) });
      const view = wrap(<CycleStatusHome />, me(['D-01:VIEW', 'C-02:CREATE'], { period: currentPeriod() }));
      await screen.findByText(periodLabel(currentPeriod()));
      const link = screen.queryByRole('link', { name: t('d01.unfreeze') });
      const button = screen.queryByRole('button', { name: t('d01.unfreeze') });
      view.unmount();
      return { link, button };
    };
    for (const state of ['FROZEN', 'COMPUTED', 'VERIFIED']) {
      const { link } = await offered({ [state]: 1 });
      expect(link, state).not.toBeNull();
      expect(link?.getAttribute('href')).toBe('/payroll/?s=C-02#c02-cycle');
    }
    for (const state of ['NOT_INITIATED', 'INITIATED', 'LOCKED']) {
      const { link, button } = await offered({ [state]: 1 });
      expect(link, state).toBeNull();
      expect(button).toBeDisabled();
      expect(button?.getAttribute('title')).toBe(t('d01.why.unfreeze'));
      expect(button?.getAttribute('data-why')).toBe(t('d01.why.unfreeze'));
    }
    // A roll-up is no unit of its own: nothing to un-freeze from the home.
    const rolled = await offered({ COMPUTED: 2 }, true);
    expect(rolled.link).toBeNull();
    expect(rolled.button).toBeDisabled();
  });

  it('SRS-G-54, §6.11.4: Month-End Lock opens for the Checker once verified, Post to EBS AP for Finance once locked; otherwise disabled with the reason naming the state', async () => {
    backend({ 'GET /v1/cycle-status': () => status({ byStatus: { VERIFIED: 1 } }) });
    const checker = wrap(<CycleStatusHome />, me(['D-01:VIEW', 'C-02:APPROVE'], { period: currentPeriod() }));
    await screen.findByText(periodLabel(currentPeriod()));
    expect(screen.getByRole('link', { name: t('d01.lock') })).toHaveAttribute(
      'href',
      '/payroll/?s=C-02#c02-cycle',
    );
    const post = screen.getByRole('button', { name: t('d01.post') });
    expect(post).toBeDisabled();
    expect(post.getAttribute('title')).toBe(t('d01.why.post', [t('d01.state.VERIFIED')]));
    expect(screen.getByRole('button', { name: t('d01.unfreeze') })).toBeDisabled();
    checker.unmount();

    backend({ 'GET /v1/cycle-status': () => status({ byStatus: { LOCKED: 1 } }) });
    wrap(<CycleStatusHome />, me(['D-01:VIEW', 'O-02:CREATE'], { period: currentPeriod() }));
    await screen.findByText(periodLabel(currentPeriod()));
    expect(screen.getByRole('link', { name: t('d01.post') })).toHaveAttribute('href', '/payroll/?s=O-02');
    const lock = screen.getByRole('button', { name: t('d01.lock') });
    expect(lock).toBeDisabled();
    expect(lock.getAttribute('title')).toBe(t('d01.why.lock', [t('d01.state.LOCKED')]));
  });

  it('SRS D-01, D-19: Headquarters sees the read-only roll-up in the band; only a Maker gets Resume Compute, the others see it disabled with the reason', async () => {
    backend({
      'GET /v1/cycle-status': () =>
        status({ rollUp: true, units: 2, scopeCode: 'HQ001', scopeLabel: 'Headquarters' }),
    });
    wrap(<CycleStatusHome />, me(['D-01:VIEW'], { period: currentPeriod() }));
    expect(await screen.findByText(periodLabel(currentPeriod()))).toBeTruthy();
    expect(band()).toHaveTextContent(t('d01.band.rollUp', ['Headquarters', 2]));
    expect(screen.queryByRole('link', { name: t('d01.resumeCompute') })).toBeNull();
    const resume = screen.getByRole('button', { name: t('d01.resumeCompute') });
    expect(resume).toBeDisabled();
    expect(resume.getAttribute('title')).toBe(t('d01.why.resumeCompute'));
  });

  it('SRS D-01 alternate flow: a consolidated scope lists its units read-only in a closed section; a unit without a run shows no figure', async () => {
    backend({
      'GET /v1/cycle-status': () =>
        status({ rollUp: true, units: 2, scopeCode: 'HQ001', scopeLabel: 'Headquarters' }),
      'GET /v1/cycle-status/units': (url) =>
        paged(
          [
            { unitCode: 'DH001', unitLabel: 'Delhi', status: 'COMPUTED', members: 12, net: 120000 },
            { unitCode: 'MR001', unitLabel: 'Mumbai', status: 'NOT_INITIATED', members: null, net: null },
          ],
          2,
          url,
        ),
    });
    wrap(<CycleStatusHome />, me(['D-01:VIEW'], { period: currentPeriod() }));
    const section = await screen.findByTestId('d01-units');
    expect(section).not.toHaveAttribute('open');
    const delhi = (await within(section).findByText('DH001')).closest('tr') as HTMLElement;
    expect(delhi).toHaveTextContent(t('d01.state.COMPUTED'));
    expect(delhi).toHaveTextContent('12');
    const mumbai = within(section).getByText('MR001').closest('tr') as HTMLElement;
    expect(mumbai).toHaveTextContent(t('d01.state.NOT_INITIATED'));
    expect(mumbai).toHaveTextContent('—');
    expect(within(delhi).queryAllByRole('button')).toHaveLength(0);
  });

  it('SRS D-01: a unit scope has no roll-up section', async () => {
    backend({ 'GET /v1/cycle-status': () => status() });
    wrap(<CycleStatusHome />, me(['D-01:VIEW'], { period: currentPeriod() }));
    expect(await screen.findByText(periodLabel(currentPeriod()))).toBeTruthy();
    expect(screen.queryByTestId('d01-units')).toBeNull();
  });

  it('SRS D-01: a scope with no cycle state counted shows "No cycle" on the band, nothing done on the track, and a blocker without a figure names its reason', async () => {
    backend({
      'GET /v1/cycle-status': () =>
        status({
          byStatus: {},
          kpis: {
            payable: 12,
            gross: 120000,
            deductions: 0,
            net: 120000,
            exceptions: { count: null, unavailable: 'AWAITING_FCI' },
            pendingApproval: { count: 0, unavailable: null },
            retroEvents: { count: 0, unavailable: null },
            interfaceHealth: { count: 0, unavailable: null },
          },
          blockers: [
            { code: 'BANK_DETAILS', figure: { count: null, unavailable: 'AWAITING_FCI' }, fixOn: 'M-01' },
          ],
          fallbackUploads: { count: 1, unavailable: null },
          gangShortfalls: { count: 3, unavailable: null },
        }),
    });
    wrap(<CycleStatusHome />, me(['D-01:VIEW'], { period: currentPeriod() }));
    expect(await screen.findByText(t('d01.state.NONE'))).toBeTruthy();
    expect(band().querySelectorAll('.st.done, .st.now')).toHaveLength(0);
    expect(screen.getByText(t('d01.blocker.BANK_DETAILS')).parentElement?.parentElement).toHaveTextContent(
      t('d01.unavailable.AWAITING_FCI'),
    );
    expect(screen.getByText(t('d01.control.fallback.text', [1]))).toBeTruthy();
    expect(screen.getByText(t('d01.control.gangs.text', [3]))).toBeTruthy();
  });
});
