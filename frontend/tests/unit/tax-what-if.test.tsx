import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TaxComputation } from '@/modules/tax';
import type { Forecast } from '@/modules/tax/api/statutoryApi';
import { TaxWhatIf } from '@/modules/tax/components/TaxWhatIf';
import { backend, me, problem, t, wrap } from './support/harness';

/**
 * The what-if Income Tax Forecast (plan 08 phase 08-C09; SRS-7-28): offered on C-07 to whoever may
 * run a simulation (C-09 RUN) and to the employee on E-01; nothing is sent until something is
 * varied; the actual and the what-if forecast are shown side by side under the SIMULATION marker.
 */

afterEach(() => vi.unstubAllGlobals());

const FORECAST: Forecast = {
  employeeId: 7,
  eis: '870001',
  name: 'Asha Kulkarni',
  employmentStatus: 'IN_SERVICE',
  finYear: '2025-26',
  view: 'PROJECTED',
  inputs: { regime: 'OLD', pan: 'VALID', deductions: [], transportRebate: 0, otherIncome: [] },
  earnings: { months: [] },
  result: {
    taxableIncome: 1200000,
    slabTax: 172500,
    surcharge: 0,
    cess: 6900,
    annualTax: 179400,
    higherRateApplied: false,
    monthlyTds: 14950,
  },
  yearEndDifference: null,
  reasons: [],
};

const VARIED: Forecast = {
  ...FORECAST,
  inputs: { ...FORECAST.inputs, regime: 'NEW' },
  result: FORECAST.result ? { ...FORECAST.result, annualTax: 83200, monthlyTds: 6933 } : null,
};

async function member(eis: string) {
  await userEvent.type(screen.getByLabelText(t('m01.eis')), eis);
  await userEvent.click(screen.getByRole('button', { name: t('c07.compute') }));
  await screen.findByText(t('c07.view.PROJECTED'));
}

describe('what-if forecast', () => {
  it('SRS_7_28_theDeskVariesTheRegimeAndSeesBothForecasts', async () => {
    const calls = backend({
      'GET /v1/tax/forecasts/870001': () => FORECAST,
      'POST /v1/tax/forecasts/870001/what-ifs': () => ({ runId: 41, actual: FORECAST, whatIf: VARIED }),
    });
    wrap(<TaxComputation />, me(['C-07:VIEW', 'C-09:RUN']));
    await member('870001');
    expect(screen.getByRole('note')).toHaveTextContent(t('c07w.banner'));
    const run = screen.getByRole('button', { name: t('c07w.run') });
    expect(run).toBeDisabled();
    expect(screen.getByRole('list', { name: t('c07w.problems') })).toHaveTextContent(t('c07w.nothingVaried'));
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.regime')), 'NEW');
    expect(run).toBeEnabled();
    await userEvent.click(run);
    const outcome = await screen.findByRole('table', { name: t('c07w.outcome') });
    expect(outcome).toHaveTextContent('₹1,79,400.00');
    expect(outcome).toHaveTextContent('₹83,200.00');
    expect(within(outcome).getByText(t('e01t.regime.NEW'))).toBeInTheDocument();
    expect(screen.getByText(t('c07w.recorded', [41]))).toBeInTheDocument();
    const sent = calls.find((c) => c.method === 'POST');
    expect(sent?.url).toContain('/v1/tax/forecasts/870001/what-ifs?finYear=');
    expect(sent?.body).toEqual({ regime: 'NEW', claims: [], salaryChange: null, otherIncome: null });
  });

  it('SRS_7_28_withoutTheSimulationRightNoWhatIfIsOffered', async () => {
    backend({ 'GET /v1/tax/forecasts/870001': () => FORECAST });
    wrap(<TaxComputation />, me(['C-07:VIEW']));
    await member('870001');
    expect(screen.queryByText(t('c07w.title'))).toBeNull();
  });

  it('SRS_7_28_theEmployeeRunsTheirOwnAndARefusalStaysOnScreen', async () => {
    const calls = backend({
      'POST /v1/tax/forecasts/mine/what-ifs': () =>
        problem(422, 'TAX-422-WHAT-IF', ['80Z is not a section in force (M-18)']),
    });
    wrap(<TaxWhatIf eis={null} finYear="2025-26" />, me(['E-01:VIEW']));
    const run = screen.getByRole('button', { name: t('c07w.run') });
    await userEvent.type(screen.getByLabelText(t('m18s.section')), '80Z');
    expect(screen.getByRole('list', { name: t('c07w.problems') })).toHaveTextContent(t('c07w.badClaim'));
    await userEvent.type(screen.getByLabelText(t('c07.claimed')), '1000');
    await userEvent.type(screen.getByLabelText(t('c07w.salaryChange')), '-5000.5');
    await userEvent.type(screen.getByLabelText(t('c07w.otherIncome')), '200');
    await userEvent.click(run);
    expect(await screen.findByText(/80Z is not a section in force/)).toBeInTheDocument();
    const sent = calls.find((c) => c.method === 'POST');
    expect(sent?.url).toContain('/v1/tax/forecasts/mine/what-ifs?finYear=2025-26');
    expect(sent?.body).toEqual({
      regime: null,
      claims: [{ sectionCode: '80Z', amount: 1000 }],
      salaryChange: -5000.5,
      otherIncome: 200,
    });
  });

  it('SRS_7_28_aSecondSectionRowAndItsDuplicateAreChecked', async () => {
    backend({});
    wrap(<TaxWhatIf eis="870001" finYear="2025-26" />, me(['C-09:RUN']));
    await userEvent.click(screen.getByRole('button', { name: t('c07w.addClaim') }));
    const codes = screen.getAllByLabelText(t('m18s.section'));
    const amounts = screen.getAllByLabelText(t('c07.claimed'));
    expect(codes).toHaveLength(2);
    await userEvent.type(codes[0] as HTMLElement, '80C');
    await userEvent.type(amounts[0] as HTMLElement, '100');
    await userEvent.type(codes[1] as HTMLElement, '80C');
    await userEvent.type(amounts[1] as HTMLElement, '200');
    expect(screen.getByRole('list', { name: t('c07w.problems') })).toHaveTextContent(
      t('c07w.duplicateClaim'),
    );
    expect(screen.getByRole('button', { name: t('c07w.run') })).toBeDisabled();
  });
  it('SRS_7_28_aWhatIfThatCannotBeComputedNamesEachReason', async () => {
    backend({
      'POST /v1/tax/forecasts/mine/what-ifs': () => ({
        runId: 42,
        // The member has not chosen a regime yet.
        actual: { ...FORECAST, inputs: { ...FORECAST.inputs, regime: null } },
        whatIf: {
          ...VARIED,
          result: null,
          reasons: ['TAX-422-RATE-NOT-CONFIGURED:TAX.SLAB.NEW', 'TAX-422-PAN'],
        },
      }),
    });
    wrap(<TaxWhatIf eis={null} finYear="2025-26" />, me(['E-01:VIEW']));
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.regime')), 'NEW');
    await userEvent.click(screen.getByRole('button', { name: t('c07w.run') }));
    const reasons = await screen.findByRole('list', { name: t('c07.notComputed') });
    expect(within(reasons).getAllByRole('listitem')).toHaveLength(2);
    expect(reasons).toHaveTextContent(t('error.TAX-422-RATE-NOT-CONFIGURED', ['TAX.SLAB.NEW']));
    expect(screen.getByText(t('c07.noRegime'))).toBeTruthy();
  });
});
