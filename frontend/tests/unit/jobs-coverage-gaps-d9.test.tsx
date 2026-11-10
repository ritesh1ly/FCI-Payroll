import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CycleStatusHome } from '@/modules/jobs';
import { backend, currentPeriod, me, t, wrap } from './support/harness';

/**
 * D-01 Home — Cycle Status as Wireframes v6.1 draws it (OD-121; SRS D-01, SRS-G-54): the engine state of each payroll
 * type with its pill (complete, running, any other state grey) and the progress bar, which reads zero while nothing is
 * computed and never passes 100 %.
 */
afterEach(() => vi.unstubAllGlobals());

const status = () => ({
  period: currentPeriod(),
  scopeCode: 'DH001',
  scopeLabel: 'Delhi',
  rollUp: false,
  units: 1,
  byStatus: { NOT_INITIATED: 0, INITIATED: 0, FROZEN: 0, COMPUTED: 1, VERIFIED: 0, LOCKED: 0 },
  kpis: {
    payable: 0,
    gross: 0,
    deductions: 0,
    net: 0,
    exceptions: { count: 0, unavailable: null },
    pendingApproval: { count: 0, unavailable: null },
    retroEvents: { count: 0, unavailable: null },
    interfaceHealth: { count: null, unavailable: 'AWAITING_FCI' },
  },
  types: [
    {
      payrollType: 'STAFF',
      population: 10,
      computed: 15,
      exceptions: 0,
      status: 'RUNNING',
      unavailable: null,
    },
    {
      payrollType: 'DEPT_LABOUR',
      population: 8,
      computed: 0,
      exceptions: 0,
      status: 'FAILED',
      unavailable: null,
    },
    {
      payrollType: 'DPS_LABOUR',
      population: 0,
      computed: 0,
      exceptions: 0,
      status: 'COMPLETE',
      unavailable: null,
    },
  ],
  blockers: [],
});

describe('D-01 computation status by payroll type', () => {
  it('SRS_D01_eachEngineStateHasItsPillAndTheProgressNeverPassesFullOrReadsAGuess', async () => {
    backend({ 'GET /v1/cycle-status': () => status() });
    wrap(<CycleStatusHome />, me(['D-01:VIEW'], { period: currentPeriod() }));
    const running = (await screen.findByText(t('d01.engineState.RUNNING'))).closest('tr') as HTMLElement;
    expect(within(running).getByText(t('d01.engineState.RUNNING'))).toHaveClass('p-warn');
    // 15 of 10 computed: capped at 100
    expect(running.querySelector('progress')).toHaveAttribute('value', '100');
    const failed = screen.getByText(t('d01.engineState.FAILED')).closest('tr') as HTMLElement;
    expect(within(failed).getByText(t('d01.engineState.FAILED'))).toHaveClass('p-gray');
    // nothing computed yet: zero, not a share
    expect(failed.querySelector('progress')).toHaveAttribute('value', '0');
    const complete = screen.getByText(t('d01.engineState.COMPLETE')).closest('tr') as HTMLElement;
    expect(within(complete).getByText(t('d01.engineState.COMPLETE'))).toHaveClass('p-ok');
    // no population: zero, never a division by zero
    expect(complete.querySelector('progress')).toHaveAttribute('value', '0');
  });
});
