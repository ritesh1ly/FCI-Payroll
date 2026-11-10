import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PayrollCycle } from '@/modules/jobs';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * C-02 Payroll Computation as Wireframes v6.1 draws it (OD-121; SRS l.3686-3692, FRS §7.4): a result without pay
 * pattern, scale or city class reads dashes in its view, never a guess; a rate line whose rule carries no version
 * names the rule alone, in the trace card and in the full trace.
 */
afterEach(() => {
  vi.unstubAllGlobals();
  window.location.hash = '';
});

const cycle = {
  period: '2026-10',
  unitCode: 'DH001',
  unitLabel: 'DO Delhi',
  unitLevel: 'DO',
  inCalendar: true,
  computeBy: '2026-10-24',
  lockTarget: '2026-10-28',
  id: null,
  status: null,
  initiatedBy: null,
  initiatedAt: null,
  frozenBy: null,
  frozenAt: null,
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
};

const ROW = {
  resultId: 2,
  eis: '104882',
  name: 'Employee 2',
  gross: 1000,
  deductions: 0,
  net: 1000,
  status: 'CURRENT',
  locked: true,
  payPattern: null,
  basic: 1000,
  da: 0,
  cityClass: null,
  scale: null,
  hra: null,
  other: 0,
  rowStatus: 'COMPUTED',
  statusGroup: 'COMPUTED',
};

const DA = {
  payCode: 111,
  name: 'Dearness Allowance',
  head: 'DA',
  amount: 500,
  rate: 50,
  basis: 1000,
  rule: 'DA.IDA.RATE',
  ruleVersion: null,
};

function routes() {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({ C02_PAY_PATTERN_FILTER: [], C02_RESULT_FILTER: [], C02_RESULT_STATUS: [] }),
    'GET /v1/cycles/2026-10': () => cycle,
    'GET /v1/cycles/2026-10/pre-run-checks': () => [],
    'GET /v1/cycles/2026-10/results-period': () => ({ period: '2026-01', runType: 'ACTUAL' }),
    'GET /v1/cycles/2026-01/results/summary': () => ({
      ida: { members: 1, exceptions: 0 },
      cda: { members: 0, exceptions: 0 },
      gross: 1000,
      deductions: 0,
      net: 1000,
      held: 0,
      shown: { members: 1, gross: 1000, deductions: 0, net: 1000 },
    }),
    'GET /v1/cycles/2026-01/results?': (url: string) => paged([ROW], 1, url),
    'GET /v1/cycles/2026-01/results/2/trace': () => ({
      resultId: 2,
      eis: '104882',
      name: 'Employee 2',
      period: '2026-01',
      payPattern: null,
      cityClass: null,
      stagnationCase: false,
      accommodation: { type: 'QUARTERS', amount: null, hraSuppressed: false },
      gross: 1000,
      deductions: 0,
      net: 1000,
      lines: [DA],
    }),
    'GET /v1/cycles/2026-01/results/2': (url: string) => paged([DA], 1, url),
    'GET /v1/cycles/2026-01/pli-payments': (url: string) => paged([], 0, url),
  });
}

describe('C-02 result view and trace', () => {
  it('FRS_7_4_aResultWithoutPatternScaleOrCityReadsDashesInItsView', async () => {
    routes();
    const user = userEvent.setup();
    wrap(<PayrollCycle />, me(['C-02:VIEW']));
    await user.click(await screen.findByRole('button', { name: t('c02.viewName', ['104882']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('— · —')).toBeInTheDocument();
    const city = within(dialog).getByText(t('c02.cityClass')).nextElementSibling as HTMLElement;
    expect(city).toHaveTextContent('—');
  });

  it('FRS_7_4_aRuleWithoutVersionIsNamedAloneInTheCardAndTheFullTrace', async () => {
    routes();
    const user = userEvent.setup();
    wrap(<PayrollCycle />, me(['C-02:VIEW']));
    expect(await screen.findByText(t('c02.trace.rateRule', ['50', 'DA.IDA.RATE', '']))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('c02.trace.full') }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(new RegExp(t('c02.trace.rule', ['DA.IDA.RATE', '']).trim())),
    ).toBeInTheDocument();
  });
});
