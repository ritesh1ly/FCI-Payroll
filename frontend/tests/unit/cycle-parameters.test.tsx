import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CycleParameters } from '@/modules/jobs/components/CycleParameters';
import { backend, me, t, wrap } from './support/harness';

/**
 * M-10 Cycle Parameters panel (plan 05 phase 05f, D-23; SRS M-10 field table, SRS-15-39): each
 * value read-only with the screen that keeps it; an unset value says "not configured".
 */

afterEach(() => vi.unstubAllGlobals());

describe('M-10 cycle parameters', () => {
  it('SRS_15_39_theParametersAreShownReadOnlyWithTheScreenThatKeepsEach', async () => {
    backend({
      'GET /v1/cycle-parameters': () => ({
        authorities: [
          {
            action: 'C02_LOCK',
            level: 1,
            levelName: 'MAKER',
            role: 'PAYROLL_MAKER',
            office: 'PAYING_UNIT',
            otp: false,
          },
          {
            action: 'C02_LOCK',
            level: 2,
            levelName: 'CHECKER',
            role: 'PAYROLL_CHECKER',
            office: 'PAYING_UNIT',
            otp: true,
          },
          {
            action: 'C02_UNDO',
            level: 3,
            levelName: 'APPROVER',
            role: 'ADMINISTRATOR',
            office: 'HQ',
            otp: true,
          },
        ],
        mdpHeads: [{ payCode: '348', name: 'Labour Welfare Fund' }],
        backDateWindowMonths: 60,
        staffNetPayFloor: { basis: 'PERCENT_OF_GROSS', value: 25, confirmed: true },
        checks: [
          {
            code: 'P1_RETIREMENT',
            name: 'P1 - Retirement',
            stage: 'PRE_INITIATION',
            appliesTo: 'ALL',
            owner: 'ESTABLISHMENT_HR',
            severity: 'BLOCK',
            active: true,
            correctionScreen: 'I-21',
          },
          {
            code: 'P4_APPS_PERIOD_CLOSE',
            name: 'P4 - APPS period close',
            stage: 'PRE_INITIATION',
            appliesTo: 'ALL',
            owner: null,
            severity: 'BLOCK',
            active: false,
            correctionScreen: null,
          },
        ],
      }),
    });
    wrap(<CycleParameters />, me(['M-10:VIEW']));
    expect(await screen.findByText(/1\. PAYROLL_MAKER → 2\. PAYROLL_CHECKER \(OTP\)/)).toBeInTheDocument();
    expect(screen.getByText(/3\. ADMINISTRATOR \(OTP\)/)).toBeInTheDocument();
    expect(screen.getByText(/348 Labour Welfare Fund/)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(t('m10.params.months', [60])))).toBeInTheDocument();
    expect(screen.getByText(/PERCENT_OF_GROSS 25/)).toBeInTheDocument();
    const checks = screen.getByRole('table', { name: t('m10.params.checks') });
    expect(checks).toHaveTextContent(t('m10.params.stage.PRE_INITIATION'));
    expect(checks).toHaveTextContent(t('cyc.preRun.NOT_AVAILABLE'));
    expect(checks).toHaveTextContent('—');
    const links = screen.getAllByRole('link', { name: t('m10.params.keptOn', ['X-01']) });
    expect(links[0]).toHaveAttribute('href', '/payroll/?s=X-01');
  });

  it('SRS_15_39_anUnsetValueSaysNotConfigured', async () => {
    backend({ 'GET /v1/cycle-parameters': () => ({ backDateWindowMonths: null, staffNetPayFloor: null }) });
    wrap(<CycleParameters />, me(['M-10:VIEW']));
    // The two authorities read "not configured"; the MDP heads, the window and the floor the drawn pill.
    expect(await screen.findAllByText(t('mst.notConfigured'), { exact: false })).toHaveLength(2);
    expect(screen.getAllByText(t('m10w.params.setting'))).toHaveLength(3);
  });
});
