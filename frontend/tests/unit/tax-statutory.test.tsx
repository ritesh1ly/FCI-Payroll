import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Form16Register, MyForm16, OutputDefinitions, StatutoryReturns, TaxComputation } from '@/modules/tax';
import type { Certificate, Forecast, StatutoryOutput, StatutoryReturn } from '@/modules/tax/api/statutoryApi';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * C-07 Statutory Computation, M-17 Statutory Output Definitions, C-10 Form 16, C-11 Form 24Q and
 * the member's E-01 certificates (plan 08 phases 08e-08h): what each screen shows, what it sends,
 * the refusals it keeps on screen and who may act.
 */

afterEach(() => vi.unstubAllGlobals());
// The year selector offers the business year and the four before it (shared FinancialYearSelect):
// the fixtures' years (2024-25, 2025-26) are in that window on this pinned date in India.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T06:00:00Z'));
});
afterEach(() => vi.useRealTimers());

/** Chooses a financial year: a select of valid years (UI audit 2026-11-02), never typed. */
async function year(value: string) {
  await userEvent.selectOptions(screen.getByLabelText(t('i07.finYear')), value);
}

/** A typed "2025" or "2026" can no longer reach the screen: the selector offers no such year. */
function noBadYear(value: string) {
  const select = screen.getByLabelText(t('i07.finYear'));
  expect(select.tagName).toBe('SELECT');
  expect(within(select).queryByRole('option', { name: value })).toBeNull();
}

// ---------------------------------------------------------------- C-07

const FORECAST: Forecast = {
  employeeId: 7,
  eis: '870001',
  name: 'Asha Kulkarni',
  employmentStatus: 'IN_SERVICE',
  finYear: '2025-26',
  view: 'ACTUAL',
  inputs: {
    regime: 'OLD',
    pan: 'VALID',
    deductions: [{ sectionCode: '80C', claimed: 165000, limit: 150000, allowed: 150000 }],
    transportRebate: 9600,
    otherIncome: [{ head: 'Honorarium', amount: 12000, tdsOtherSource: 1200 }],
  },
  earnings: {
    months: [
      { yymm: '202504', actual: true, taxableGross: 100000, tdsDeducted: 8000, employerNps: 0, lines: [] },
      { yymm: '202505', actual: false, taxableGross: 100000, tdsDeducted: 8000, employerNps: 0, lines: [] },
    ],
  },
  result: {
    taxableIncome: 1200000,
    slabTax: 172500,
    surcharge: 0,
    cess: 6900,
    annualTax: 179400,
    higherRateApplied: false,
    monthlyTds: 83400,
  },
  yearEndDifference: 83400,
  reasons: [],
};

describe('C-07 screen', () => {
  it('SRS_7_19_theActualYearShowsItsInputsEarningsTaxAndDifference', async () => {
    backend({ 'GET /v1/tax/forecasts/870001': () => FORECAST });
    wrap(<TaxComputation />, me(['C-07:VIEW', 'C-07:EXPORT']));
    expect(screen.getByText(t('c07.chooseMember'))).toBeInTheDocument();
    const compute = screen.getByRole('button', { name: t('c07.compute') });
    expect(compute).toBeDisabled();
    await year('2025-26');
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '870001');
    await userEvent.click(compute);
    expect(await screen.findByText(t('c07.view.ACTUAL'))).toBeInTheDocument();
    const deductions = screen.getByRole('table', { name: t('c07.deductions') });
    expect(deductions).toHaveTextContent('₹1,65,000.00');
    expect(deductions).toHaveTextContent('₹1,50,000.00');
    expect(screen.getByRole('table', { name: t('c07.otherIncome') })).toHaveTextContent('Honorarium');
    expect(screen.getByRole('table', { name: t('c07.earnings') })).toHaveTextContent(t('c07.actual'));
    expect(screen.getByRole('table', { name: t('c07.earnings') })).toHaveTextContent(t('c07.projected'));
    const result = screen.getByLabelText(t('c07.result'));
    expect(result).toHaveTextContent('₹1,79,400.00');
    expect(result).toHaveTextContent(t('c07.yearEndDifference'));
    expect(screen.queryByText(t('c07.higherRate'))).toBeNull();
    // SRS-7-27: the forecast downloads, with the year.
    expect(screen.getByRole('link', { name: t('c07.exportXlsx') })).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/tax/forecasts/870001/export?finYear=2025-26&format=XLSX'),
    );
  });

  it('SRS_7_26_whatIsMissingIsShownAsAReasonAndNoExportWithoutTheRight', async () => {
    backend({
      'GET /v1/tax/forecasts/870005': () => ({
        ...FORECAST,
        eis: '870005',
        view: 'PROJECTED',
        inputs: { ...FORECAST.inputs, regime: null, pan: 'MISSING', deductions: [], otherIncome: [] },
        earnings: null,
        result: null,
        yearEndDifference: null,
        reasons: ['TAX-422-REGIME-REQUIRED', 'TAX-422-RATE-NOT-CONFIGURED:TAX.SLAB.OLD'],
      }),
    });
    wrap(<TaxComputation />, me(['C-07:VIEW']));
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '870005');
    await userEvent.click(screen.getByRole('button', { name: t('c07.compute') }));
    const reasons = await screen.findByRole('list', { name: t('c07.notComputed') });
    expect(reasons).toHaveTextContent(t('error.TAX-422-REGIME-REQUIRED'));
    expect(reasons).toHaveTextContent(t('error.TAX-422-RATE-NOT-CONFIGURED', ['TAX.SLAB.OLD']));
    expect(screen.getByText(t('c07.noRegime'))).toBeInTheDocument();
    expect(screen.getByText(t('c07.pan.MISSING'))).toBeInTheDocument();
    expect(screen.getByText(t('c07.noDeductions'))).toBeInTheDocument();
    expect(screen.getByText(t('error.TAX-422-EARNINGS-NOT-AVAILABLE'))).toBeInTheDocument();
    expect(screen.queryByLabelText(t('c07.result'))).toBeNull();
    expect(screen.queryByRole('link', { name: t('c07.exportCsv') })).toBeNull();
  });

  it('SRS_7_13_theHigherRateIsSaidAndAMemberOutsideTheOfficesIsAProblem', async () => {
    backend({
      'GET /v1/tax/forecasts/870003': () => ({
        ...FORECAST,
        eis: '870003',
        yearEndDifference: null,
        result: { ...FORECAST.result, higherRateApplied: true },
      }),
      'GET /v1/tax/forecasts/870004': () => problem(404, 'COM-404-NOT-FOUND'),
    });
    wrap(<TaxComputation />, me(['C-07:VIEW']));
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '870003');
    await userEvent.click(screen.getByRole('button', { name: t('c07.compute') }));
    expect(await screen.findByText(t('c07.higherRate'))).toBeInTheDocument();
    expect(screen.getByLabelText(t('c07.result'))).not.toHaveTextContent(t('c07.yearEndDifference'));
    await userEvent.clear(screen.getByLabelText(t('m01.eis')));
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '870004');
    await userEvent.click(screen.getByRole('button', { name: t('c07.compute') }));
    expect(await screen.findByText(t('error.COM-404-NOT-FOUND'), { exact: false })).toBeInTheDocument();
    // A year that is not a financial year cannot be chosen.
    noBadYear('2026');
  });
});

// ---------------------------------------------------------------- M-17

const OUTPUTS: StatutoryOutput[] = [
  {
    key: 'FORM16_PART_B',
    name: 'Form 16 Part B',
    producingScreen: 'C-10',
    statutoryReference: 'Rule 31',
    state: 'ACTIVE',
    pendingApprovals: [],
    versions: [
      {
        id: 3,
        number: 1,
        effectiveFrom: '2026-04-01',
        effectiveTo: null,
        layoutFormat: 'PDF',
        periodLockRequired: true,
        inForce: true,
        fields: [{ label: 'Basic', binding: 'COMPONENT', reference: '101', formatMask: null }],
        controls: [{ label: 'TDS', reconcilesAgainst: 'CHALLAN', tolerance: 0, releaseGate: true }],
      },
    ],
  },
  {
    key: 'PT_SCHEDULE',
    name: 'PT schedule',
    producingScreen: null,
    statutoryReference: null,
    state: 'BLOCKED',
    pendingApprovals: [41],
    versions: [],
  },
];

describe('M-17 screen', () => {
  it('BR_MIS_002_theCatalogueShowsWhatIsActiveAndWhatIsBlocked', async () => {
    backend({ 'GET /v1/tax/output-definitions': () => OUTPUTS });
    wrap(<OutputDefinitions />, me(['M-17:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-17') });
    const active = within(table).getByText('Form 16 Part B').closest('tr') as HTMLElement;
    expect(active).toHaveTextContent(t('m17.state.ACTIVE'));
    const blocked = within(table).getByText('PT schedule').closest('tr') as HTMLElement;
    expect(blocked).toHaveTextContent(t('m17.state.BLOCKED'));
    expect(blocked).toHaveTextContent(t('status.PENDING_APPROVAL'));
    // SRS-20-29 (US-03-07 AC1): each output opens its change history, a blocked one included.
    expect(within(active).getByRole('button', { name: t('history.for', ['Form 16 Part B']) })).toBeTruthy();
    expect(within(blocked).getByRole('button', { name: t('history.for', ['PT schedule']) })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('m17.openOf', ['FORM16_PART_B']) }));
    expect(screen.getByRole('table', { name: t('m17.fieldsOf', [1]) })).toHaveTextContent('101');
    expect(screen.getByRole('list', { name: t('m17.controlsOf', [1]) })).toHaveTextContent(
      t('m17.releaseGate'),
    );
    // A viewer proposes nothing.
    expect(screen.queryByText(t('m17.newVersion'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: t('m17.openOf', ['PT_SCHEDULE']) }));
    expect(screen.getByText(t('m17.blocked'))).toBeInTheDocument();
  });

  it('BR_MIS_002_anEndedVersionShowsItsLastDayAndOneWithoutAPeriodLockSaysSo', async () => {
    const [form16] = OUTPUTS as [(typeof OUTPUTS)[number]];
    backend({
      'GET /v1/tax/output-definitions': () => [
        {
          ...form16,
          versions: [
            {
              ...form16.versions[0],
              id: 2,
              effectiveTo: '2026-03-31',
              periodLockRequired: false,
              inForce: false,
            },
          ],
        },
      ],
    });
    wrap(<OutputDefinitions />, me(['M-17:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('m17.openOf', ['FORM16_PART_B']) }));
    expect(screen.getByText(t('m17.noLock'), { exact: false })).toBeInTheDocument();
    expect(screen.queryByText(t('m17.inForce'))).toBeNull();
  });

  it('BR_TAX_013_theAdministratorProposesAVersionAfterTheLatestWithItsFields', async () => {
    const calls = backend({
      'GET /v1/tax/output-definitions': () => OUTPUTS,
      'POST /v1/tax/output-definitions/FORM16_PART_B/versions': () => ({
        status: 202,
        body: { approvalId: 77 },
      }),
    });
    wrap(<OutputDefinitions />, me(['M-17:VIEW', 'M-17:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('m17.openOf', ['FORM16_PART_B']) }));
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    const problems = screen.getByRole('list', { name: t('common.problems') });
    expect(problems).toHaveTextContent(t('m17.needFrom'));
    await userEvent.type(screen.getByLabelText(t('m17.effectiveFrom')), '2026-04-01');
    expect(problems).toHaveTextContent(t('m17.afterLatest'));
    await userEvent.clear(screen.getByLabelText(t('m17.effectiveFrom')));
    await userEvent.type(screen.getByLabelText(t('m17.effectiveFrom')), '2027-04-01');
    await userEvent.selectOptions(screen.getByLabelText(t('m17.layoutFormat')), 'PRESCRIBED_FILE');
    await userEvent.type(screen.getByLabelText(t('m17.labelOf', [1])), 'Basic');
    await userEvent.type(screen.getByLabelText(t('m17.refOf', [1])), '101');
    await userEvent.click(screen.getByRole('button', { name: t('m17.addField') }));
    await userEvent.type(screen.getByLabelText(t('m17.labelOf', [2])), 'PAN');
    await userEvent.selectOptions(screen.getByLabelText(t('m17.bindingOf', [2])), 'FIELD_SOURCE');
    await userEvent.type(screen.getByLabelText(t('m17.refOf', [2])), 'employee.pan_number');
    await userEvent.type(screen.getByLabelText(t('m17.maskOf', [2])), 'XXXXX9999X');
    await userEvent.click(screen.getByRole('button', { name: t('m17.addControl') }));
    await userEvent.type(screen.getByLabelText(t('m17.controlLabel')), 'TDS');
    await userEvent.selectOptions(screen.getByLabelText(t('m17.against')), 'CHALLAN');
    await userEvent.type(screen.getByLabelText(t('m17.rounding')), 'round.rupee');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Layout of 2027-28');
    expect(submit).toBeEnabled();
    await userEvent.click(submit);
    expect(await screen.findByText(t('m17.proposed'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/versions')[0]).toEqual({
      effectiveFrom: '2027-04-01',
      layoutFormat: 'PRESCRIBED_FILE',
      layoutNote: null,
      roundingRuleKey: 'ROUND.RUPEE',
      periodLockRequired: true,
      fields: [
        { label: 'Basic', binding: 'COMPONENT', reference: '101', formatMask: null },
        { label: 'PAN', binding: 'FIELD_SOURCE', reference: 'employee.pan_number', formatMask: 'XXXXX9999X' },
      ],
      controls: [{ label: 'TDS', reconcilesAgainst: 'CHALLAN', tolerance: '0.00', releaseGate: true }],
      reason: 'Layout of 2027-28',
    });
  });

  it('SRS_M17_aControlTotalAddedByMistakeIsRemovedAndNoLongerBlocksTheProposal', async () => {
    const calls = backend({
      'GET /v1/tax/output-definitions': () => OUTPUTS,
      'POST /v1/tax/output-definitions/FORM16_PART_B/versions': () => ({
        status: 202,
        body: { approvalId: 78 },
      }),
    });
    wrap(<OutputDefinitions />, me(['M-17:VIEW', 'M-17:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('m17.openOf', ['FORM16_PART_B']) }));
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.type(screen.getByLabelText(t('m17.effectiveFrom')), '2027-04-01');
    await userEvent.type(screen.getByLabelText(t('m17.labelOf', [1])), 'Basic');
    await userEvent.type(screen.getByLabelText(t('m17.refOf', [1])), '101');
    await userEvent.type(screen.getByLabelText(t('m17.rounding')), 'round.rupee');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Layout of 2027-28');
    expect(submit).toBeEnabled();
    // Two control rows: the first filled, the second added by mistake and left empty.
    await userEvent.click(screen.getByRole('button', { name: t('m17.addControl') }));
    await userEvent.click(screen.getByRole('button', { name: t('m17.addControl') }));
    const labels = screen.getAllByLabelText(t('m17.controlLabel'));
    await userEvent.type(labels[0] as HTMLElement, 'TDS');
    expect(submit).toBeDisabled();
    expect(screen.getByRole('list', { name: t('common.problems') })).toHaveTextContent(t('m17.badLabel'));
    await userEvent.click(screen.getByRole('button', { name: t('m17.removeControl', [2]) }));
    expect(screen.getAllByLabelText(t('m17.controlLabel'))).toHaveLength(1);
    // Edge: removing the row added last leaves the first as typed (no state carried from the removed row).
    expect((screen.getByLabelText(t('m17.controlLabel')) as HTMLInputElement).value).toBe('TDS');
    expect(screen.queryByRole('button', { name: t('m17.removeControl', [2]) })).toBeNull();
    expect(submit).toBeEnabled();
    await userEvent.click(submit);
    expect(await screen.findByText(t('m17.proposed'))).toBeInTheDocument();
    expect((sent(calls, 'POST', '/versions')[0] as { controls: unknown[] }).controls).toEqual([
      { label: 'TDS', reconcilesAgainst: 'POSTED_PAYROLL', tolerance: '0.00', releaseGate: true },
    ]);
  });

  it('BR_MIS_002_aWaitingProposalStopsANewOneAndARefusalStaysOnTheForm', async () => {
    backend({
      'GET /v1/tax/output-definitions': () => OUTPUTS,
      'POST /v1/tax/output-definitions/FORM16_PART_B/versions': () =>
        problem(422, 'TAX-422-OUTPUT-BINDING', ['Basic', 'x']),
    });
    wrap(<OutputDefinitions />, me(['M-17:VIEW', 'M-17:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('m17.openOf', ['PT_SCHEDULE']) }));
    expect(screen.getByText(t('m17.waiting', ['41']))).toBeInTheDocument();
    expect(screen.queryByText(t('m17.newVersion'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: t('m17.openOf', ['FORM16_PART_B']) }));
    const remove = screen.getByRole('button', { name: t('m17.removeField', [1]) });
    expect(remove).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('m17.addField') }));
    await userEvent.click(screen.getByRole('button', { name: t('m17.removeField', [2]) }));
    expect(screen.queryByLabelText(t('m17.labelOf', [2]))).toBeNull();
    await userEvent.type(screen.getByLabelText(t('m17.effectiveFrom')), '2027-04-01');
    await userEvent.type(screen.getByLabelText(t('m17.labelOf', [1])), 'Basic');
    await userEvent.type(screen.getByLabelText(t('m17.refOf', [1])), '999');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'IT');
    await userEvent.click(screen.getByLabelText(t('m17.lockRequired')));
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(
      await screen.findByText(t('error.TAX-422-OUTPUT-BINDING', ['Basic', 'x']), { exact: false }),
    ).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------- C-10

const CERT: Certificate = {
  id: 11,
  employeeId: 7,
  eis: '870001',
  name: 'Asha Kulkarni',
  finYear: '2025-26',
  grossSalary: 1200000,
  taxableIncome: 1200000,
  taxDeducted: 96000,
  partAStatus: 'PENDING',
  status: 'PENDING_PART_A',
  partBGeneratedAt: '2026-05-01T05:00:00Z',
  releasedAt: null,
  partADocumentId: null,
};

const C10_MAKER = me(['C-10:VIEW', 'C-10:CREATE', 'C-10:EDIT', 'C-10:SUBMIT']);

describe('C-10 screen', () => {
  it('SRS l.2589, SRS-7-23: the register shows the PAN masked and a missing or invalid PAN as the exception', async () => {
    backend({
      'GET /v1/tax/form16-certificates?': (url) =>
        paged(
          [
            { ...CERT, panMasked: 'XXXXXX001A', panStatus: 'VALID' },
            { ...CERT, id: 12, eis: '870003', panMasked: null, panStatus: 'MISSING' },
          ],
          2,
          url,
        ),
    });
    wrap(<Form16Register />, C10_MAKER);
    expect(await screen.findByText('XXXXXX001A')).toBeInTheDocument();
    expect(screen.getByText(t('c10.panStatus.MISSING'))).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/[A-Z]{5}\d{4}[A-Z]/);
  });

  it('SRS_7_9_partBIsGeneratedWithTheMembersSkippedAndPartAIsUploaded', async () => {
    const calls = backend({
      'GET /v1/tax/form16-certificates?': (url) => paged([CERT], 1, url),
      'POST /v1/tax/form16-certificates/generate': () => ({
        generated: 1,
        skipped: [{ eis: '870002', reason: 'TAX-422-EARNINGS-NOT-AVAILABLE' }],
      }),
      'POST /v1/tax/form16-certificates/11/part-a': () => ({
        ...CERT,
        status: 'MERGED',
        partAStatus: 'RECEIVED',
        partADocumentId: 5,
      }),
      'GET /v1/document-types': () => [
        { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
      ],
    });
    wrap(<Form16Register />, C10_MAKER);
    await year('2025-26');
    const table = await screen.findByRole('table', { name: t('screen.C-10') });
    expect(table).toHaveTextContent('₹96,000.00');
    expect(table).toHaveTextContent(t('c10.partA.PENDING'));
    expect(table).not.toHaveTextContent('KLKPK7001A');
    await userEvent.click(screen.getByRole('button', { name: t('c10.generate') }));
    expect(await screen.findByText(t('c10.generationOf', [1]))).toBeInTheDocument();
    expect(screen.getByRole('list', { name: t('c10.skipped') })).toHaveTextContent('870002');
    expect(sent(calls, 'POST', '/form16-certificates/generate')[0]).toEqual({
      finYear: '2025-26',
      eis: null,
    });

    await userEvent.click(screen.getByRole('button', { name: t('c10.partAOf', ['870001']) }));
    const upload = screen.getByRole('button', { name: t('c10.uploadPartA') });
    expect(upload).toBeDisabled();
    expect(upload).toHaveAttribute('title', t('c10.partAFirst'));
    await userEvent.upload(
      screen.getByLabelText(t('c10.partAFile')),
      new File(['%PDF'], 'part-a.pdf', { type: 'application/pdf' }),
    );
    await userEvent.type(screen.getByLabelText(t('c10.partARef')), 'TRACES-1');
    await userEvent.type(screen.getByLabelText(t('i18.documentDate')), '2026-06-15');
    await userEvent.type(screen.getByLabelText(t('i18.documentIssuer')), 'Income Tax Department');
    await userEvent.click(upload);
    expect(await screen.findByText(t('c10.partAMerged'))).toBeInTheDocument();
    const form = sent(calls, 'POST', '/part-a')[0] as FormData;
    expect(JSON.parse(await (form.get('request') as Blob).text())).toEqual({
      documentType: 'CERTIFICATE',
      reference: 'TRACES-1',
      documentDate: '2026-06-15',
      issuingAuthority: 'Income Tax Department',
    });
    expect((form.get('file') as File).name).toBe('part-a.pdf');
  });

  it('BR_TAX_012 (UI audit S13): "Request release" waits for a merged certificate and says why', async () => {
    let merged = 0;
    const calls = backend({
      'GET /v1/tax/form16-certificates?': (url) =>
        url.includes('status=MERGED') ? paged([], merged, url) : paged([CERT], 1, url),
    });
    wrap(<Form16Register />, C10_MAKER);
    await year('2025-26');
    const release = await screen.findByRole('button', { name: t('c10.release') });
    await waitFor(() => expect(release).toHaveAttribute('title', t('c10.releaseNoMerged')));
    expect(release).toBeDisabled();
    // The count reads the smallest page the server offers, never a size it refuses.
    expect(calls.some((c) => c.url.includes('status=MERGED&page=0&size=10'))).toBe(true);
    merged = 1;
    await year('2024-25');
    await waitFor(() => expect(screen.getByRole('button', { name: t('c10.release') })).toBeEnabled());
    expect(screen.getByRole('button', { name: t('c10.release') })).not.toHaveAttribute('title');
  });

  it('BR_TAX_012_theReleaseIsRefusedUntilReconciledAndAReleasedOneIsReissuedWithAReason', async () => {
    const released = {
      ...CERT,
      id: 12,
      status: 'RELEASED' as const,
      partAStatus: 'RECEIVED' as const,
      partADocumentId: 5,
      releasedAt: '2026-07-01T05:00:00Z',
    };
    const calls = backend({
      'GET /v1/tax/form16-certificates?': (url) => paged([released], 1, url),
      'POST /v1/tax/form16-certificates/release': () =>
        problem(409, 'TAX-409-NOT-RECONCILED', ['TDS 96000.00 against challans 0']),
      'POST /v1/tax/form16-certificates/12/reissue': () => ({ ...released, status: 'MERGED' }),
    });
    wrap(<Form16Register />, C10_MAKER);
    await year('2025-26');
    const table = await screen.findByRole('table', { name: t('screen.C-10') });
    expect(within(table).getByRole('link', { name: t('c10.partA.RECEIVED') })).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/tax/form16-certificates/12/part-a'),
    );
    expect(screen.queryByRole('button', { name: t('c10.partAOf', ['870001']) })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('c10.release') }));
    expect(
      await screen.findByText(t('error.TAX-409-NOT-RECONCILED', ['TDS 96000.00 against challans 0']), {
        exact: false,
      }),
    ).toBeInTheDocument();
    const reissue = screen.getByRole('button', { name: t('c10.reissueOf', ['870001']) });
    expect(reissue).toBeDisabled();
    await userEvent.type(
      screen.getByLabelText(t('c10.reissueReasonOf', ['870001'])),
      ' Correction return filed ',
    );
    await userEvent.click(reissue);
    expect(await screen.findByText(t('c10.reissued'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/reissue')[0]).toEqual({ reason: 'Correction return filed' });
  });

  it('SRS_7_21_theCheckerOnlyReadsAndABadYearReadsNothing', async () => {
    backend({ 'GET /v1/tax/form16-certificates?': (url) => paged([], 0, url) });
    wrap(<Form16Register />, me(['C-10:VIEW', 'C-10:APPROVE']));
    expect(await screen.findByText(t('c10.none'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('c10.generate') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('c10.release') })).toBeNull();
    noBadYear('2025');
  });
});

// ---------------------------------------------------------------- E-01

describe('E-01 Form 16 card', () => {
  it('SRS_7_9_theMemberSeesTheReleasedCertificatesWithPartA', async () => {
    backend({
      'GET /v1/tax/form16-certificates/mine': () => [
        { ...CERT, status: 'RELEASED', partADocumentId: 5, releasedAt: '2026-07-01T05:00:00Z' },
        { ...CERT, id: 13, finYear: '2024-25', status: 'RELEASED', partADocumentId: null },
      ],
    });
    wrap(<MyForm16 />, me(['E-01:VIEW']));
    const table = await screen.findByRole('table', { name: t('e01f.title') });
    expect(within(table).getByRole('link', { name: t('e01f.downloadOf', ['2025-26']) })).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/tax/form16-certificates/11/part-a'),
    );
    expect(within(table).queryByRole('link', { name: t('e01f.downloadOf', ['2024-25']) })).toBeNull();
  });

  it('SRS_7_9_noCertificateYetAndAFailureIsShown', async () => {
    backend({ 'GET /v1/tax/form16-certificates/mine': () => [] });
    const { unmount } = wrap(<MyForm16 />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('e01f.none'))).toBeInTheDocument();
    unmount();
    backend({ 'GET /v1/tax/form16-certificates/mine': () => problem(403, 'COM-403-FORBIDDEN') });
    wrap(<MyForm16 />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('error.COM-403-FORBIDDEN'), { exact: false })).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------- C-11

const RETURN: StatutoryReturn = {
  id: 21,
  type: 'FORM_24Q',
  finYear: '2025-26',
  quarter: 'Q1',
  deductees: 2,
  totalTds: 39000,
  status: 'GENERATED',
  validated: false,
  validationUtility: null,
  validationResult: [],
  correction: false,
  correctsReturnId: null,
  generatedAt: '2026-07-10T05:00:00Z',
  releasedBy: null,
  filings: [],
};

const C11_MAKER = me(['C-11:VIEW', 'C-11:CREATE', 'C-11:EDIT', 'C-11:SUBMIT']);

function c11(returns: StatutoryReturn[], over: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/tax/returns?': (url) => paged(returns, returns.length, url),
    'GET /v1/tax/challans?': () => [
      {
        id: 1,
        cin: 'CIN1',
        bsr: '0510308',
        date: '2025-06-20',
        amount: 24000,
        reference: 'UTR1',
        period: 'Q1',
      },
    ],
    'GET /v1/tax/returns/21/deductees': (url) =>
      paged(
        [
          {
            employeeId: 7,
            eis: '870001',
            name: 'Asha',
            panMasked: 'XXXXXX001A',
            panValid: true,
            higherRateApplied: false,
            amountPaid: 300000,
            taxDeducted: 24000,
            challanId: 1,
          },
          {
            employeeId: 8,
            eis: '870003',
            name: 'Lata',
            panMasked: null,
            panValid: false,
            higherRateApplied: true,
            amountPaid: 180000,
            taxDeducted: 15000,
            challanId: null,
          },
        ],
        2,
        url,
      ),
    ...over,
  });
}

describe('C-11 screen', () => {
  it('SRS-7-31: a correction names the return it corrects; a filing without an acknowledgement token shows a dash', async () => {
    const filed: StatutoryReturn = {
      ...RETURN,
      status: 'FILED',
      validated: true,
      validationUtility: 'FVU',
      releasedBy: 'tax.checker.delhi@fci-demo.test',
      filings: [
        {
          filingDate: '2026-07-20',
          signatoryName: 'R K Sharma',
          signatoryDesignation: 'AGM',
          acknowledgementToken: null,
          evidenceId: null,
        },
      ],
    } as StatutoryReturn;
    const correction: StatutoryReturn = {
      ...RETURN,
      id: 22,
      type: 'FORM_24Q_CORRECTION',
      correction: true,
      correctsReturnId: 21,
    } as StatutoryReturn;
    c11([filed, correction]);
    wrap(<StatutoryReturns />, C11_MAKER);
    await year('2025-26');
    expect(await screen.findByText(t('c11.corrects', [21]))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('c11.openOf', [21]) }));
    expect(
      await screen.findByText(
        (_, el) => el?.tagName === 'LI' && /R K Sharma, AGM; token —/.test(el.textContent ?? ''),
      ),
    ).toBeInTheDocument();
  });

  it('SRS_7_21_theChallanFileIsImportedAndTheHeldOnesAreListed', async () => {
    const calls = c11([], {
      'POST /v1/tax/challans/import': () => ({ imported: 2, duplicates: ['CIN1 / 0510308 / 2025-06-20'] }),
    });
    wrap(<StatutoryReturns />, C11_MAKER);
    await year('2025-26');
    expect(await screen.findByRole('table', { name: t('c11.challans') })).toHaveTextContent('CIN1');
    const importButton = screen.getByRole('button', { name: t('c11.import') });
    expect(importButton).toBeDisabled();
    expect(importButton).toHaveAttribute('title', t('c11.needFile'));
    await userEvent.upload(
      screen.getByLabelText(t('c11.challanFile')),
      new File(['cin,bsr'], 'challans.csv', { type: 'text/csv' }),
    );
    await userEvent.click(importButton);
    expect(await screen.findByText(t('c11.imported', [2]))).toBeInTheDocument();
    expect(screen.getByLabelText(t('c11.duplicates'))).toHaveTextContent('CIN1 / 0510308 / 2025-06-20');
    expect(((sent(calls, 'POST', '/challans/import')[0] as FormData).get('file') as File).name).toBe(
      'challans.csv',
    );
  });

  it('SRS l.2625: a challan is keyed by the Maker, checked as a file line, and refused when already held', async () => {
    let answer: unknown = {
      id: 2,
      cin: 'CIN2',
      bsr: '0510308',
      date: '2025-07-07',
      amount: 1200.5,
      reference: null,
      period: 'Q2',
    };
    const calls = c11([], { 'POST /v1/tax/challans': () => answer });
    wrap(<StatutoryReturns />, C11_MAKER);
    await year('2025-26');
    const keyed = await screen.findByRole('group', { name: t('c11.keyChallan') });
    const add = within(keyed).getByRole('button', { name: t('c11.addChallan') });
    expect(add).toBeDisabled();
    expect(within(keyed).getByRole('list', { name: t('c11.keyProblems') })).toHaveTextContent(
      t('c11.problem.cin'),
    );
    await userEvent.type(within(keyed).getByLabelText(t('c11.cin')), 'CIN2');
    await userEvent.type(within(keyed).getByLabelText(t('c11.bsr')), '0510308');
    await userEvent.type(within(keyed).getByLabelText(t('c11.challanDate')), '2025-07-07');
    await userEvent.type(within(keyed).getByLabelText(t('i18.amount')), '1200.505');
    await userEvent.type(within(keyed).getByLabelText(t('c11.period')), 'Q5');
    expect(within(keyed).getByRole('list', { name: t('c11.keyProblems') })).toHaveTextContent(
      t('c11.problem.amount'),
    );
    expect(within(keyed).getByRole('list', { name: t('c11.keyProblems') })).toHaveTextContent(
      t('c11.problem.period'),
    );
    await userEvent.clear(within(keyed).getByLabelText(t('i18.amount')));
    await userEvent.type(within(keyed).getByLabelText(t('i18.amount')), '1200.50');
    await userEvent.clear(within(keyed).getByLabelText(t('c11.period')));
    await userEvent.type(within(keyed).getByLabelText(t('c11.period')), 'Q2');
    await userEvent.click(add);
    expect(await screen.findByText(t('c11.challanKeyed', ['CIN2']))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/tax/challans')[0]).toEqual({
      cin: 'CIN2',
      bsr: '0510308',
      date: '2025-07-07',
      amount: '1200.50',
      reference: null,
      period: 'Q2',
    });
    // Held already: the server's refusal is shown.
    answer = problem(422, 'TAX-422-CHALLAN-FILE', [1, 'the challan is already held']);
    await userEvent.type(within(keyed).getByLabelText(t('c11.cin')), 'CIN2');
    await userEvent.type(within(keyed).getByLabelText(t('c11.bsr')), '0510308');
    await userEvent.type(within(keyed).getByLabelText(t('c11.challanDate')), '2025-07-07');
    await userEvent.type(within(keyed).getByLabelText(t('i18.amount')), '1200.50');
    await userEvent.type(within(keyed).getByLabelText(t('c11.period')), 'Q2');
    await userEvent.click(add);
    expect(await within(keyed).findByRole('alert')).toBeInTheDocument();
  });

  it('SRS_7_7_aGeneratedQuarterIsRegeneratedInPlaceAndAReleasedOneIsCorrected', async () => {
    const filed = { ...RETURN, id: 22, quarter: 'Q2' as const, status: 'FILED' as const };
    const calls = c11([RETURN, filed], {
      'POST /v1/tax/returns/generate': () => ({ status: 201, body: { ...RETURN, id: 23 } }),
    });
    wrap(<StatutoryReturns />, C11_MAKER);
    await year('2025-26');
    const quarter = (await screen.findAllByRole('combobox'))[1] as HTMLSelectElement;
    await screen.findByRole('option', { name: t('c11.returnOf', [22, 'Q2']) });
    // Q1 is only generated (regenerated in place); Q2 is filed (corrected instead).
    expect([...quarter.options].map((o) => o.value)).toEqual(['', 'Q1', 'Q3', 'Q4']);
    const generate = screen.getByRole('button', { name: t('c11.generate') });
    expect(generate).toBeDisabled();
    await userEvent.selectOptions(quarter, 'Q3');
    await userEvent.click(generate);
    expect(await screen.findByText(t('c11.generated'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/returns/generate')[0]).toEqual({
      finYear: '2025-26',
      quarter: 'Q3',
      correctsReturnId: null,
    });
    await userEvent.selectOptions(screen.getByLabelText(t('c11.correctionOf')), '22');
    expect(quarter).toBeDisabled();
    await userEvent.click(generate);
    await screen.findAllByText(t('c11.generated'));
    expect(sent(calls, 'POST', '/returns/generate')[1]).toEqual({
      finYear: '2025-26',
      quarter: 'Q2',
      correctsReturnId: 22,
    });
  });

  it('SRS_7_8_aGeneratedReturnIsValidatedWithEachRejectionAndAValidatedOneIsReleased', async () => {
    const calls = c11([RETURN], {
      'POST /v1/tax/returns/21/validate': () => ({
        ...RETURN,
        validationResult: ['PAN missing or invalid for member 870003', 'no challan mapped for member 870003'],
      }),
    });
    wrap(<StatutoryReturns />, C11_MAKER);
    await year('2025-26');
    await userEvent.click(await screen.findByRole('button', { name: t('c11.openOf', [21]) }));
    const deductees = await screen.findByRole('table', { name: t('c11.deductees') });
    expect(deductees).toHaveTextContent('XXXXXX001A');
    expect(deductees).toHaveTextContent(t('c07.higherRate'));
    expect(deductees).toHaveTextContent(t('c11.noChallan'));
    await userEvent.click(screen.getByRole('button', { name: t('c11.validate') }));
    const rejections = await screen.findByRole('list', { name: t('c11.rejections') });
    expect(rejections).toHaveTextContent('PAN missing or invalid for member 870003');
    expect(screen.queryByRole('button', { name: t('c11.release') })).toBeNull();
    expect(sent(calls, 'POST', '/21/validate')).toHaveLength(1);
  });

  it('SRS_7_31_aReleasedReturnIsFiledWithItsAcknowledgement', async () => {
    const released = {
      ...RETURN,
      status: 'RELEASED' as const,
      validated: true,
      validationUtility: 'FVU',
      releasedBy: 'tax.checker.delhi@fci-demo.test',
    };
    const calls = c11([released], {
      'POST /v1/tax/returns/21/filing': () => ({
        ...released,
        status: 'FILED',
        filings: [
          {
            filingDate: '2026-07-20',
            signatoryName: 'R K Sharma',
            signatoryDesignation: 'AGM',
            acknowledgementToken: 'T1',
            evidenceId: 9,
          },
        ],
      }),
    });
    wrap(<StatutoryReturns />, C11_MAKER);
    await year('2025-26');
    await userEvent.click(await screen.findByRole('button', { name: t('c11.openOf', [21]) }));
    expect(
      screen.getByText(t('c11.releasedBy', ['tax.checker.delhi@fci-demo.test']), { exact: false }),
    ).toBeInTheDocument();
    const record = screen.getByRole('button', { name: t('c11.recordFiling') });
    expect(record).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('c11.signatory')), 'R K Sharma');
    await userEvent.type(screen.getByLabelText(t('c11.designation')), 'AGM');
    await userEvent.type(screen.getByLabelText(t('c11.token')), 'T1');
    await userEvent.upload(
      screen.getByLabelText(t('c11.evidence')),
      new File(['%PDF'], 'ack.pdf', { type: 'application/pdf' }),
    );
    expect(screen.getByRole('list', { name: t('common.problems') })).toHaveTextContent(t('c11.needEvidence'));
    await userEvent.type(screen.getByLabelText(t('i18.documentIssuer')), 'TIN-FC');
    await userEvent.click(record);
    expect(await screen.findByText(t('c11.filed'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('c11.evidence') })).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/tax/returns/21/documents/9'),
    );
    const form = sent(calls, 'POST', '/21/filing')[0] as FormData;
    const request = JSON.parse(await (form.get('request') as Blob).text());
    expect(request.signatoryName).toBe('R K Sharma');
    expect(request.evidence.issuingAuthority).toBe('TIN-FC');
    expect((form.get('file') as File).name).toBe('ack.pdf');
  });

  it('SRS_7_21_theCheckerReadsOnlyAndAValidatedReturnGoesForRelease', async () => {
    const validated = { ...RETURN, status: 'VALIDATED' as const, validated: true };
    const calls = c11([validated], {
      'POST /v1/tax/returns/21/release': () => ({ status: 202, body: { approvalId: 5 } }),
    });
    const { unmount } = wrap(<StatutoryReturns />, C11_MAKER);
    await year('2025-26');
    await userEvent.click(await screen.findByRole('button', { name: t('c11.openOf', [21]) }));
    await userEvent.click(screen.getByRole('button', { name: t('c11.release') }));
    expect(await screen.findByText(t('c11.releaseRequested'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/21/release')).toHaveLength(1);
    unmount();
    c11([validated]);
    wrap(<StatutoryReturns />, me(['C-11:VIEW', 'C-11:APPROVE']));
    await year('2025-26');
    expect(screen.queryByRole('button', { name: t('c11.import') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('c11.generate') })).toBeNull();
    await userEvent.click(await screen.findByRole('button', { name: t('c11.openOf', [21]) }));
    expect(screen.queryByRole('button', { name: t('c11.release') })).toBeNull();
    noBadYear('2025');
  });
});

// ---------------------------------------------------------------- refusals and the remaining inputs

describe('C-10, C-11 and M-17 refusals stay on screen', () => {
  it('SRS_7_21_eachC11RefusalIsShownAndTheDetailCloses', async () => {
    const released = { ...RETURN, id: 24, quarter: 'Q4' as const, status: 'RELEASED' as const };
    c11([RETURN, released], {
      'POST /v1/tax/challans/import': () => problem(422, 'TAX-422-CHALLAN-FILE', [3, 'six columns']),
      'POST /v1/tax/returns/generate': () => problem(409, 'TAX-409-PERIOD-NOT-LOCKED', ['202510']),
      'POST /v1/tax/returns/21/validate': () => problem(409, 'TAX-409-DECLARATION-STATE', ['VALIDATED']),
      'POST /v1/tax/returns/24/filings': () => problem(409, 'TAX-409-DECLARATION-STATE', ['FILED']),
      'GET /v1/tax/returns/24/deductees': (url) => paged([], 0, url),
    });
    wrap(<StatutoryReturns />, C11_MAKER);
    await year('2025-26');
    await screen.findByRole('table', { name: t('c11.challans') });
    await userEvent.upload(
      screen.getByLabelText(t('c11.challanFile')),
      new File(['x'], 'c.csv', { type: 'text/csv' }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('c11.import') }));
    expect(
      await screen.findByText(t('error.TAX-422-CHALLAN-FILE', [3, 'six columns']), { exact: false }),
    ).toBeInTheDocument();
    const quarter = (await screen.findAllByRole('combobox'))[1] as HTMLSelectElement;
    await screen.findByRole('option', { name: 'Q2' });
    await userEvent.selectOptions(quarter, 'Q2');
    await userEvent.click(screen.getByRole('button', { name: t('c11.generate') }));
    expect(
      await screen.findByText(t('error.TAX-409-PERIOD-NOT-LOCKED', ['202510']), { exact: false }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('c11.openOf', [21]) }));
    await userEvent.click(screen.getByRole('button', { name: t('c11.validate') }));
    expect(
      await screen.findByText(t('error.TAX-409-DECLARATION-STATE', ['VALIDATED']), { exact: false }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('button', { name: t('c11.validate') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('c11.openOf', [24]) }));
    expect(await screen.findByText(t('c11.noDeductees'))).toBeInTheDocument();
    const date = screen.getByLabelText(t('c11.filingDate'));
    await userEvent.clear(date);
    expect(screen.getByRole('list', { name: t('common.problems') })).toHaveTextContent(
      t('c11.needFilingDate'),
    );
    await userEvent.type(date, '2026-07-20');
    await userEvent.type(screen.getByLabelText(t('c11.signatory')), 'R K Sharma');
    await userEvent.type(screen.getByLabelText(t('c11.designation')), 'AGM');
    await userEvent.click(screen.getByRole('button', { name: t('c11.recordFiling') }));
    expect(
      await screen.findByText(t('error.TAX-409-DECLARATION-STATE', ['FILED']), { exact: false }),
    ).toBeInTheDocument();
  });

  it('SRS_7_9_aPartARefusalIsShownAndTheStatusFilterIsSent', async () => {
    const calls = backend({
      'GET /v1/tax/form16-certificates?': (url) => paged([CERT], 1, url),
      'POST /v1/tax/form16-certificates/11/part-a': () =>
        problem(409, 'TAX-409-DECLARATION-STATE', ['RELEASED']),
      'GET /v1/document-types': () => [
        { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
        { code: 'OTHER', name: 'Other', referenceRequired: false },
      ],
    });
    wrap(<Form16Register />, C10_MAKER);
    await year('2025-26');
    await screen.findByRole('table', { name: t('screen.C-10') });
    await userEvent.selectOptions(screen.getByLabelText(t('i07.status')), 'MERGED');
    await screen.findByRole('table', { name: t('screen.C-10') });
    expect(calls.some((c) => c.url.includes('status=MERGED'))).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('c10.partAOf', ['870001']) }));
    await userEvent.upload(
      screen.getByLabelText(t('c10.partAFile')),
      new File(['%PDF'], 'a.pdf', { type: 'application/pdf' }),
    );
    await screen.findByRole('option', { name: 'Other' });
    await userEvent.selectOptions(screen.getByLabelText(t('i18.documentType')), 'OTHER');
    await userEvent.type(screen.getByLabelText(t('i18.documentDate')), '2026-06-15');
    await userEvent.type(screen.getByLabelText(t('i18.documentIssuer')), 'ITD');
    await userEvent.click(screen.getByRole('button', { name: t('c10.uploadPartA') }));
    expect(
      await screen.findByText(t('error.TAX-409-DECLARATION-STATE', ['RELEASED']), { exact: false }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByLabelText(t('c10.partAFile'))).toBeNull();
  });

  it('BR_MIS_002_theNoteTheToleranceAndTheGateAreSent', async () => {
    const calls = backend({
      'GET /v1/tax/output-definitions': () => OUTPUTS,
      'POST /v1/tax/output-definitions/FORM16_PART_B/versions': () => ({
        status: 202,
        body: { approvalId: 78 },
      }),
    });
    wrap(<OutputDefinitions />, me(['M-17:VIEW', 'M-17:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('m17.openOf', ['FORM16_PART_B']) }));
    await userEvent.type(screen.getByLabelText(t('m17.effectiveFrom')), '2027-04-01');
    await userEvent.type(screen.getByLabelText(t('m17.note')), 'As supplied');
    await userEvent.type(screen.getByLabelText(t('m17.labelOf', [1])), 'Form');
    await userEvent.selectOptions(screen.getByLabelText(t('m17.bindingOf', [1])), 'LITERAL');
    await userEvent.type(screen.getByLabelText(t('m17.refOf', [1])), 'Form 16');
    await userEvent.click(screen.getByRole('button', { name: t('m17.addControl') }));
    await userEvent.type(screen.getByLabelText(t('m17.controlLabel')), 'TDS');
    const tolerance = screen.getByLabelText(t('m17.tolerance'));
    await userEvent.clear(tolerance);
    await userEvent.type(tolerance, '1.005');
    expect(screen.getByRole('list', { name: t('common.problems') })).toHaveTextContent(t('m17.badTolerance'));
    await userEvent.clear(tolerance);
    await userEvent.type(tolerance, '1.5');
    await userEvent.click(screen.getByRole('checkbox', { name: t('m17.releaseGate') }));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'IT');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('m17.proposed'))).toBeInTheDocument();
    const body = sent(calls, 'POST', '/versions')[0] as { layoutNote: string; controls: unknown[] };
    expect(body.layoutNote).toBe('As supplied');
    expect(body.controls).toEqual([
      { label: 'TDS', reconcilesAgainst: 'POSTED_PAYROLL', tolerance: '1.5', releaseGate: false },
    ]);
  });
});

describe('Module 08 gap closure', () => {
  it('SRS_7_27_aRetiredMembersYearIsMarkedAndEmployerNpsIsIncomeWithItsExemption', async () => {
    backend({
      'GET /v1/tax/forecasts/870009': () => ({
        ...FORECAST,
        eis: '870009',
        employmentStatus: 'RETIRED',
        earnings: {
          months: [
            {
              yymm: '202504',
              actual: true,
              taxableGross: 90000,
              tdsDeducted: 0,
              employerNps: 4500,
              lines: [],
            },
            {
              yymm: '202505',
              actual: true,
              taxableGross: 90000,
              tdsDeducted: 0,
              employerNps: 4500,
              lines: [],
            },
          ],
        },
      }),
    });
    wrap(<TaxComputation />, me(['C-07:VIEW']));
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '870009');
    await userEvent.click(screen.getByRole('button', { name: t('c07.compute') }));
    expect(await screen.findByText(t('c07.status.RETIRED'), { exact: false })).toBeInTheDocument();
    expect(screen.getByText(t('c07.employerNps'))).toBeInTheDocument();
    expect(screen.getByText(t('c07.npsExemption'))).toBeInTheDocument();
    expect(screen.getAllByText('₹9,000.00')).toHaveLength(2);
  });

  it('SRS_7_27_aMemberInServiceShowsNoStatusAndNoNpsWithoutAContribution', async () => {
    backend({ 'GET /v1/tax/forecasts/870001': () => FORECAST });
    wrap(<TaxComputation />, me(['C-07:VIEW']));
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '870001');
    await userEvent.click(screen.getByRole('button', { name: t('c07.compute') }));
    await screen.findByText(t('c07.view.ACTUAL'));
    expect(screen.queryByText(t('c07.employerNps'))).toBeNull();
    expect(screen.queryByText(t('c07.status.SEPARATED'), { exact: false })).toBeNull();
  });

  it('SRS_7_9_theMakerSelectsTheMemberPopulation', async () => {
    const calls = backend({
      'GET /v1/tax/form16-certificates?': (url) => paged([], 0, url),
      'POST /v1/tax/form16-certificates/generate': () => ({ generated: 2, skipped: [] }),
    });
    wrap(<Form16Register />, C10_MAKER);
    await year('2025-26');
    const generate = screen.getByRole('button', { name: t('c10.generate') });
    await userEvent.type(screen.getByLabelText(t('c10.members')), '870001, 87-0003');
    expect(generate).toBeDisabled();
    expect(generate).toHaveAttribute('title', t('c10.badMembers'));
    await userEvent.clear(screen.getByLabelText(t('c10.members')));
    await userEvent.type(screen.getByLabelText(t('c10.members')), '870001 870003');
    await userEvent.click(generate);
    expect(await screen.findByText(t('c10.generationOf', [2]))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/generate')[0]).toEqual({ finYear: '2025-26', eis: ['870001', '870003'] });
  });

  it('SRS_NF_7_theWholeUnitRunsInTheBackgroundThroughC13', async () => {
    backend({ 'GET /v1/tax/form16-certificates?': (url) => paged([], 0, url) });
    wrap(<Form16Register />, me([...C10_MAKER.permissions, 'C-13:CREATE']));
    await year('2025-26');
    const link = screen.getByRole('link', { name: t('tax.runInBackground') });
    expect(link).toHaveAttribute('href', '/payroll?s=C-13&job=TAX_FORM16_PART_B&finYear=2025-26');
    // A chosen population stays on the screen; a bad year names the problem.
    await userEvent.type(screen.getByLabelText(t('c10.members')), '870001');
    const waiting = screen.getByRole('button', { name: t('tax.runInBackground') });
    expect(waiting).toBeDisabled();
    expect(waiting).toHaveAttribute('title', t('c10.backgroundWholeUnit'));
    noBadYear('2025');
  });

  it('SRS_NF_7_noBackgroundRunWithoutTheC13Right', async () => {
    backend({ 'GET /v1/tax/form16-certificates?': (url) => paged([], 0, url) });
    wrap(<Form16Register />, C10_MAKER);
    await year('2025-26');
    expect(screen.queryByText(t('tax.runInBackground'))).not.toBeInTheDocument();
  });

  it('SRS_NF_7_theOriginalOfAQuarterRunsInTheBackgroundACorrectionDoesNot', async () => {
    const filed = { ...RETURN, id: 22, quarter: 'Q2' as const, status: 'FILED' as const };
    c11([filed]);
    wrap(<StatutoryReturns />, me([...C11_MAKER.permissions, 'C-13:CREATE']));
    await year('2025-26');
    await screen.findByRole('option', { name: t('c11.returnOf', [22, 'Q2']) });
    expect(screen.getByText(t('c11.backgroundHint'))).toBeInTheDocument();
    const idle = screen.getByRole('button', { name: t('tax.runInBackground') });
    expect(idle).toBeDisabled();
    expect(idle).toHaveAttribute('title', t('c11.chooseQuarter'));
    const quarter = (await screen.findAllByRole('combobox'))[1] as HTMLSelectElement;
    await userEvent.selectOptions(quarter, 'Q3');
    expect(screen.getByRole('link', { name: t('tax.runInBackground') })).toHaveAttribute(
      'href',
      '/payroll?s=C-13&job=TAX_24Q_RETURN&finYear=2025-26&quarter=Q3',
    );
    await userEvent.selectOptions(screen.getByLabelText(t('c11.correctionOf')), '22');
    expect(screen.getByRole('button', { name: t('tax.runInBackground') })).toHaveAttribute(
      'title',
      t('c11.backgroundOriginalOnly'),
    );
  });
});
