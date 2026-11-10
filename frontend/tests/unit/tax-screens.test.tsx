import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { messages } from '@/generated/messages';
import {
  ComputationScreen,
  DeclarationsScreen,
  Form16Screen,
  OutputDefinitionsScreen,
  ReturnsScreen,
  SavingsScreen,
} from '@/modules/tax';
import type { CertificateLine, DeclarationLine } from '@/modules/tax/api/screensApi';
import {
  certificateStatus,
  declarationStatus,
  lastCompletedQuarter,
  maskedToken,
  proofLabel,
  returnSteps,
  versionOfYear,
} from '@/modules/tax/business/screenRules';
import {
  backend,
  captureDownloads,
  me,
  paged,
  problem,
  referenceLists,
  sent,
  t,
  wrap,
} from './support/harness';

/**
 * M-17, I-07, I-18, C-07, C-10 and C-11 as Wireframes v6.1 draws them (OD-121): the drawn objects per role, the reason on
 * a locked action, the closed register that keeps each older function, and what each read sends (SRS-G-54).
 */
afterEach(() => vi.unstubAllGlobals());
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T06:00:00Z'));
});
afterEach(() => vi.useRealTimers());

const LISTS = referenceLists({
  STATUTORY_OUTPUT_FAMILY: [
    ['INCOME_TAX', 'Income tax'],
    ['PROFESSIONAL_TAX', 'State - Professional Tax'],
  ],
  FORM16_REGISTER_STATUS: [['RELEASED', 'Issued']],
  TAX_RETURN_QUARTER: [
    ['Q1', 'Q1 Apr-Jun'],
    ['Q2', 'Q2 Jul-Sep'],
  ],
});

const VERSION = {
  id: 1,
  number: 2,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  layoutFormat: 'PDF',
  periodLockRequired: true,
  inForce: true,
  fields: [{ label: 'TDS deducted', binding: 'AGGREGATE', reference: 'SUM', formatMask: null }],
  controls: [
    { label: 'Posted payroll', reconcilesAgainst: 'POSTED_PAYROLL', tolerance: 0, releaseGate: true },
  ],
};
const OUTPUTS = [
  {
    key: 'FORM16_PART_B',
    name: 'Form 16 Part B - annual tax certificate',
    producingScreen: 'C-10',
    statutoryReference: 'SRS-7-5',
    state: 'ACTIVE',
    versions: [VERSION],
    pendingApprovals: [],
  },
  {
    key: 'PT_SCHEDULE',
    name: 'Professional Tax Schedule',
    producingScreen: 'C-07',
    statutoryReference: null,
    state: 'BLOCKED',
    versions: [],
    pendingApprovals: [],
  },
  {
    key: 'FORM_24Q',
    name: 'Form 24Q',
    producingScreen: 'C-11',
    statutoryReference: null,
    state: 'BLOCKED',
    versions: [],
    pendingApprovals: [9],
  },
];
const OUTPUT_SUMMARY = {
  carriedForward: 1,
  superseded: 4,
  families: 2,
  outputs: [
    {
      key: 'FORM16_PART_B',
      name: 'Form 16 Part B - annual tax certificate',
      family: 'INCOME_TAX',
      periodicity: 'Annual',
      lockRequirement: 'POST_LOCK',
      statutoryReference: 'SRS-7-5',
      carriedForward: false,
      catalogueReport: false,
    },
    {
      key: 'PT_SCHEDULE',
      name: 'Professional Tax Schedule',
      family: 'PROFESSIONAL_TAX',
      periodicity: 'Monthly',
      lockRequirement: 'NONE',
      statutoryReference: null,
      carriedForward: true,
      catalogueReport: false,
    },
    {
      key: 'FORM_24Q',
      name: 'Form 24Q',
      family: 'INCOME_TAX',
      periodicity: 'Quarterly',
      lockRequirement: 'POST_LOCK',
      statutoryReference: null,
      carriedForward: false,
      catalogueReport: false,
    },
  ],
};

function m17Backend() {
  return backend({
    'GET /v1/tax/output-definitions/summary': () => OUTPUT_SUMMARY,
    'GET /v1/tax/output-definitions': () => OUTPUTS,
    'GET /v1/reference/lists': () => LISTS,
  });
}

describe('M-17 as drawn', () => {
  it('SRS_G_54_theRegisterShowsEachStateAndTheProposalIsLockedWithItsReasonForAViewer', async () => {
    m17Backend();
    wrap(<OutputDefinitionsScreen />, me(['M-17:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.m17.table') });
    const active = (await within(table).findByText(/^Form 16 Part B/)).closest('tr') as HTMLElement;
    expect(active).toHaveTextContent(t('txw.m17.status.active'));
    expect(active).toHaveTextContent('v2');
    const carried = within(table).getByText('Professional Tax Schedule').closest('tr') as HTMLElement;
    expect(carried).toHaveTextContent(t('txw.m17.status.carried'));
    const waiting = within(table).getByText('Form 24Q').closest('tr') as HTMLElement;
    expect(waiting).toHaveTextContent(t('txw.m17.status.pending'));
    const locked = screen.getByRole('button', { name: t('txw.m17.new') });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAttribute('title', t('txw.m17.why.statAdmin'));
    expect(screen.getByRole('button', { name: t('txw.m17.import') })).toBeDisabled();
  });

  it('BR_MIS_002_theFamilyFilterNarrowsTheRowsAndTheSearchToo', async () => {
    m17Backend();
    wrap(<OutputDefinitionsScreen />, me(['M-17:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.m17.table') });
    await within(table).findByText('Form 24Q');
    await screen.findByRole('option', { name: t('txw.m17.family.PROFESSIONAL_TAX') });
    await userEvent.selectOptions(screen.getByLabelText(t('txw.m17.allFamilies')), 'PROFESSIONAL_TAX');
    expect(within(table).queryByText('Form 24Q')).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('txw.m17.allFamilies')), '');
    await userEvent.type(screen.getByLabelText(t('wf.searchInList')), '24q');
    expect(within(table).queryByText('Professional Tax Schedule')).toBeNull();
  });

  it('BR_TAX_013_theRuleAdministratorProposesInTheRegisterAndTheSystemAdministratorIsToldWhy', async () => {
    m17Backend();
    wrap(<OutputDefinitionsScreen />, me(['M-17:VIEW', 'M-17:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }));
    await userEvent.click(await screen.findByRole('button', { name: t('txw.m17.new') }));
    expect(await screen.findByRole('table', { name: t('screen.M-17') })).toBeInTheDocument();
    vi.unstubAllGlobals();
  });

  it('SRS_G_54_theSystemAdministratorSeesTheEntryDrawnAndIsToldWhyNothingOpens', async () => {
    m17Backend();
    wrap(<OutputDefinitionsScreen />, me(['M-17:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const add = await screen.findByRole('button', { name: t('txw.m17.new') });
    expect(add).toBeEnabled();
    await userEvent.click(add);
    expect(screen.getByTestId('m17-register')).not.toHaveAttribute('open');
    expect(await screen.findByRole('button', { name: t('txw.m17.editDefinition') })).toBeEnabled();
  });

  it('SRS_G_54_aRowOpensItsRecordReadOnlyAndARefusalIsShown', async () => {
    m17Backend();
    wrap(<OutputDefinitionsScreen />, me(['M-17:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} FORM16_PART_B` }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('TDS deducted');
    expect(dialog).toHaveTextContent(t('txw.m17.d.lockYes'));
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

const LINE: DeclarationLine = {
  employeeId: 1,
  eis: '105220',
  name: 'S. Banerjee',
  regime: 'O',
  frozen: true,
  frozenAt: '2026-04-12T00:00:00Z',
  roundId: 11,
  roundStatus: 'APPROVED',
  declared80c: 180000,
  limit80c: 150000,
  declared80d: 25000,
  declared24b: 200000,
  allowed: 375000,
  capped: true,
  unproved: false,
};
const NO_REGIME: DeclarationLine = {
  ...LINE,
  employeeId: 2,
  eis: '106845',
  name: 'M. Rao',
  regime: null,
  frozen: false,
  frozenAt: null,
  roundId: null,
  roundStatus: null,
  declared80c: null,
  declared80d: null,
  declared24b: null,
  allowed: null,
  capped: false,
};
const DECLARATIONS = {
  finYear: '2026-27',
  members: 10,
  newRegime: 6,
  oldRegime: 3,
  notSelected: 1,
  received: 5,
  pending: 5,
  capped: 1,
  unproved: 3,
  sections: [{ code: '80C', regime: 'OLD', limit: 150000, effectiveFrom: '2026-04-01', rule: false }],
};

function i07Backend() {
  return backend({
    'GET /v1/tax/declarations?': () => paged([]),
    'GET /v1/tax/declarations/summary': () => DECLARATIONS,
    'GET /v1/tax/declarations/members': () => paged([LINE, NO_REGIME]),
    'GET /v1/tax/declarations/11/orders': () => [],
    'GET /v1/document-types': () => [],
    'GET /v1/reference/lists': () => referenceLists({}),
  });
}

describe('I-07 as drawn', () => {
  it('SRS_7_1_aMemberWithoutARegimeHasALineAndBlocksTheTax', async () => {
    i07Backend();
    wrap(<DeclarationsScreen />, me(['I-07:VIEW', 'I-07:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('txw.i07.registerLabel') });
    const none = (await within(table).findByText(/106845/)).closest('tr') as HTMLElement;
    expect(none).toHaveTextContent(t('txw.i07.status.noRegime'));
    const capped = within(table)
      .getByText(/105220/)
      .closest('tr') as HTMLElement;
    expect(capped).toHaveTextContent(t('txw.i07.status.capped'));
    expect(capped).toHaveTextContent('1,50,000');
    expect(screen.getByRole('button', { name: t('txw.i07.freeze') })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('txw.i07.proofs') }));
    expect(screen.getByTestId('i07-register')).toHaveAttribute('open');
  });

  it('SRS_G_30_theCardOpensOnTheLatestStoredRoundWhenThePageHasNoRound', async () => {
    backend({
      'GET /v1/tax/declarations/summary': () => ({ ...DECLARATIONS, orderRound: 77 }),
      'GET /v1/tax/declarations/members': () => paged([NO_REGIME]),
      'GET /v1/tax/declarations/77/orders': () => [
        {
          id: 5,
          fileName: 'order_880622.pdf',
          documentTypeCode: 'SANCTION_ORDER',
          documentTypeName: 'Sanction order',
          referenceNumber: 'FCI/RO-DEL/TAX/880622',
          uploadedBy: 'maker.delhi@fci-demo.test',
          uploadedAt: '2026-10-01T10:00:00Z',
        },
      ],
      'GET /v1/document-types': () => [],
      'GET /v1/reference/lists': () => referenceLists({}),
    });
    wrap(<DeclarationsScreen />, me(['I-07:VIEW']));
    expect(await screen.findByText('order_880622.pdf')).toBeInTheDocument();
    // A member chosen with no round has no order: the card does not keep another member's.
    await userEvent.click((await screen.findByText(/106845/)).closest('tr') as HTMLElement);
    await waitFor(() => expect(screen.queryByText('order_880622.pdf')).not.toBeInTheDocument());
  });

  it('SRS_G_54_theCheckerFreezesFromTheInboxAndAViewerCannotRecompute', async () => {
    i07Backend();
    wrap(<DeclarationsScreen />, me(['I-07:VIEW', 'I-07:APPROVE']));
    expect(await screen.findByRole('button', { name: t('txw.i07.freeze') })).toBeEnabled();
    const recompute = screen.getByRole('button', { name: t('txw.i07.recompute') });
    expect(recompute).toBeDisabled();
    expect(recompute).toHaveAttribute('title', t('txw.why.makerOnly'));
  });

  it('SRS_7_26_aRuleNotConfiguredReadsSoAndARefusedReadIsShown', async () => {
    backend({
      'GET /v1/tax/declarations/summary': () => problem(403, 'AUTHZ-403-SCOPE'),
      'GET /v1/tax/declarations/members': () => paged([]),
      'GET /v1/document-types': () => [],
      'GET /v1/reference/lists': () => referenceLists({}),
    });
    wrap(<DeclarationsScreen />, me(['I-07:VIEW']));
    expect((await screen.findAllByText(t('txw.i07.sec.notConfigured'))).length).toBeGreaterThan(1);
    expect(await screen.findByText(t('error.AUTHZ-403-SCOPE'))).toBeInTheDocument();
  });
});

describe('I-18 as drawn', () => {
  it('SRS_7_11_theFourScreensAndTheMapAreDrawnAndTheMakerOpensTheRegister', async () => {
    backend({ 'GET /v1/tax/savings': () => paged([]) });
    wrap(<SavingsScreen />, me(['I-18:VIEW', 'I-18:CREATE']));
    expect(screen.getByRole('table', { name: t('txw.i18.screens') })).toHaveTextContent(
      t('txw.i18.s.transport'),
    );
    expect(screen.getByRole('table', { name: t('txw.i18.map') })).toHaveTextContent('80DDB');
    await userEvent.click(screen.getByRole('button', { name: t('txw.i18.add') }));
    expect(screen.getByTestId('i18-register')).toHaveAttribute('open');
  });

  it('SRS_G_54_aViewerHasTheEntryLockedAndTheCheckerHasTheDecision', () => {
    backend({});
    wrap(<SavingsScreen />, me(['I-18:VIEW', 'I-18:APPROVE']));
    expect(screen.getByRole('button', { name: t('txw.i18.add') })).toBeDisabled();
    expect(screen.getAllByRole('button', { name: '✔' })).toHaveLength(4);
  });
});

describe('C-07 as drawn', () => {
  const SUMMARY = {
    period: '202601',
    cpfRate: 12,
    types: [{ payrollType: 'STAFF', members: 14, employee: 95000, vpf: 2000, tds: null }],
    noCpfCode: 1,
    npsMismatch: 2,
    regimeMissing: 3,
  };
  const routes = {
    'GET /v1/tax/statutory-computation/summary': () => SUMMARY,
    'GET /v1/tax/statutory-computation/eps': () =>
      paged([{ employeeId: 1, eis: '104882', name: 'R. Sharma', wages: 93600 }]),
    'GET /v1/tax/statutory-computation/members': () =>
      paged([
        {
          employeeId: 1,
          eis: '104882',
          name: 'R. Sharma',
          regime: 'NEW',
          taxable: 1233384,
          annualTax: 108660,
          monthlyTds: 9055,
          professionalTax: null,
          higherRate: false,
          reason: null,
        },
        {
          employeeId: 2,
          eis: '106004',
          name: 'P. Nayak',
          regime: 'NEW',
          taxable: 784000,
          annualTax: 156800,
          monthlyTds: 13067,
          professionalTax: null,
          higherRate: true,
          reason: null,
        },
      ]),
    'GET /v1/tax/statutory-computation/nps': () =>
      paged([
        {
          id: 1,
          eis: '108911',
          name: 'S. Ghosh',
          pattern: 'CDA',
          pfType: 'NPS',
          npsMember: true,
          pran: '110012345678',
          employeeAmount: 9120,
          employerAmount: 12768,
          mismatch: 'CONSISTENT',
          applyStatus: 'APPLIED',
        },
        {
          id: 2,
          eis: '108930',
          name: 'V. Pillai',
          pattern: 'CDA',
          pfType: 'NPS',
          npsMember: true,
          pran: null,
          employeeAmount: null,
          employerAmount: null,
          mismatch: 'FLAG_WITHOUT_AMOUNT',
          applyStatus: 'HELD',
        },
      ]),
  };

  it('SRS_9_1_npsIsShownAsReceivedAndAFlagWithoutAmountIsTheException', async () => {
    backend(routes);
    wrap(<ComputationScreen />, me(['C-07:VIEW']));
    const nps = await screen.findByRole('table', { name: t('txw.c07.nps') });
    expect(await within(nps).findByText(/108911/)).toBeInTheDocument();
    expect(nps).toHaveTextContent(t('txw.c07.nps.received'));
    expect(nps).toHaveTextContent(t('txw.c07.nps.FLAG_WITHOUT_AMOUNT'));
  });

  it('SRS_7_13_theHigherRateIsSaidAndTheEpsRatesStayNotConfigured', async () => {
    backend(routes);
    wrap(<ComputationScreen />, me(['C-07:VIEW']));
    const tax = await screen.findByRole('table', { name: t('txw.c07.tax') });
    expect(await within(tax).findByText(t('txw.c07.higherRate'))).toBeInTheDocument();
    const eps = screen.getByRole('table', { name: t('txw.c07.eps') });
    expect(await within(eps).findByText(/104882/)).toBeInTheDocument();
    expect(eps).toHaveTextContent(t('txw.c07.notConfigured'));
    expect(screen.getByRole('button', { name: t('txw.c07.compute') })).toBeDisabled();
  });

  it('SRS_7_26_aRateNotConfiguredAndAPayrollTypeNotRunAreSaid', async () => {
    backend({
      ...routes,
      'GET /v1/tax/statutory-computation/summary': () => ({ ...SUMMARY, cpfRate: null, types: [] }),
    });
    wrap(<ComputationScreen />, me(['C-07:VIEW', 'I-07:SUBMIT']));
    const cpf = await screen.findByRole('table', { name: t('txw.c07.cpf') });
    const staff = await within(cpf).findByText(t('txw.c07.type.STAFF'));
    expect(staff.closest('tr')).toHaveTextContent(t('txw.c07.notComputed'));
    expect(staff.closest('tr')).toHaveTextContent(t('txw.c07.notConfigured'));
    await userEvent.click(screen.getByRole('button', { name: t('txw.c07.compute') }));
    expect(screen.getByTestId('c07-member')).toHaveAttribute('open');
  });
});

const CERTIFICATE: CertificateLine = {
  id: 5,
  eis: '104882',
  name: 'R. Sharma',
  panMasked: 'ABCXXXX34K',
  panStatus: 'VALID',
  regime: 'N',
  taxable: 1233384,
  tds: 108660,
  partB: true,
  partA: true,
  signed: true,
  status: 'RELEASED',
};

describe('C-10 as drawn', () => {
  const routes = {
    'GET /v1/tax/form16-certificates/summary': () => ({
      finYear: '2025-26',
      due: 6,
      partB: 6,
      partA: 4,
      merged: 4,
      issued: 3,
      form10e: 0,
      units: [{ id: 7, code: 'DH001', name: 'DO Delhi HQ' }],
    }),
    'GET /v1/tax/form16-certificates/lines': () =>
      paged([
        CERTIFICATE,
        {
          ...CERTIFICATE,
          id: 6,
          eis: '106004',
          panMasked: null,
          panStatus: 'MISSING',
          partA: false,
          signed: false,
          status: 'PENDING_PART_A',
        },
      ]),
    'GET /v1/reference/lists': () => LISTS,
  };

  it('SRS_7_23_thePanIsMaskedAndAMissingPanBlocksTheCertificate', async () => {
    backend(routes);
    wrap(<Form16Screen />, me(['C-10:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
    expect(await within(table).findByText('ABCXXXX34K')).toBeInTheDocument();
    const missing = within(table).getByText('106004').closest('tr') as HTMLElement;
    expect(missing).toHaveTextContent(t('txw.c10.status.blockedPan'));
    // OD-144: Part B is generated, so its PDF downloads (Part A not received yet: Part B alone).
    expect(within(missing).getByRole('button', { name: t('txw.c10.pdf') })).toHaveAttribute(
      'title',
      t('txw.c10.pdfPartBTitle'),
    );
    expect(screen.getByRole('button', { name: t('txw.c10.generate') })).toHaveAttribute(
      'title',
      t('txw.c10.why.generate'),
    );
  });

  it('OD_153_aCertificateReleasedBeforeIssuedCopiesWereKeptHasItsPdfDisabledWithTheReason', async () => {
    const calls = backend({
      ...routes,
      'GET /v1/tax/form16-certificates/lines': () =>
        paged([
          { ...CERTIFICATE, issuedCopy: false },
          { ...CERTIFICATE, id: 8, eis: '106008', issuedCopy: true },
          { ...CERTIFICATE, id: 9, eis: '106009', status: 'MERGED', issuedCopy: false },
        ]),
    });
    wrap(<Form16Screen />, me(['C-10:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
    const old = (await within(table).findByText(String(CERTIFICATE.eis))).closest('tr') as HTMLElement;
    const off = within(old).getByRole('button', { name: t('txw.c10.pdf') });
    expect(off).toBeDisabled();
    expect(off).toHaveAttribute('data-why', t('txw.c10.why.noIssuedCopy'));
    expect(off).toHaveAttribute('title', t('txw.c10.why.noIssuedCopy'));
    await userEvent.click(off);
    // Never recomputed: nothing is asked of the server for it.
    expect(sent(calls, 'GET', `/v1/tax/form16-certificates/${CERTIFICATE.id}/part-b`)).toHaveLength(0);
    // A released certificate with its issued copy, and one not yet released, keep their PDF.
    for (const eis of ['106008', '106009']) {
      const row = within(table).getByText(eis).closest('tr') as HTMLElement;
      expect(within(row).getByRole('button', { name: t('txw.c10.pdf') })).toBeEnabled();
    }
    // The reason is in both languages (en + hi), never the key.
    expect(messages.hi['txw.c10.why.noIssuedCopy']).toBeTruthy();
    expect(messages.hi['txw.c10.why.noIssuedCopy']).not.toBe(messages.en['txw.c10.why.noIssuedCopy']);
    expect(t('txw.c10.why.noIssuedCopy')).not.toBe('txw.c10.why.noIssuedCopy');
  });

  it('OD_144_aRowPdfDownloadsTheCertificateAndARowWithoutPartBSaysWhy', async () => {
    const calls = backend({
      ...routes,
      'GET /v1/tax/form16-certificates/lines': () =>
        paged([CERTIFICATE, { ...CERTIFICATE, id: 9, eis: '106009', partB: false, partA: false }]),
    });
    const files = captureDownloads();
    try {
      wrap(<Form16Screen />, me(['C-10:VIEW']));
      const table = await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
      const none = (await within(table).findByText('106009')).closest('tr') as HTMLElement;
      const off = within(none).getByRole('button', { name: t('txw.c10.pdf') });
      expect(off).toBeDisabled();
      expect(off).toHaveAttribute('data-why', t('txw.c10.why.noPdf'));
      const first = within(table).getByText(String(CERTIFICATE.eis)).closest('tr') as HTMLElement;
      await userEvent.click(within(first).getByRole('button', { name: t('txw.c10.pdf') }));
      // OD-153: the file is named as the server names it, by financial year (form16-<FY>.pdf).
      await waitFor(() => expect(files.names).toEqual(['form16-2025-26.pdf']));
      expect(sent(calls, 'GET', `/v1/tax/form16-certificates/${CERTIFICATE.id}/part-b`)).toHaveLength(1);
      expect(screen.getByRole('button', { name: t('txw.c10.preview') })).toHaveAttribute(
        'data-guard',
        t('txw.c10.why.bulkPdf'),
      );
    } finally {
      files.restore();
    }
  });

  it('OD_149_SRS_NF_2_c10BulkQueuesTheYearsJobOnceAndAJobOfAnotherYearIsNotShown', async () => {
    const calls = backend({
      ...routes,
      'GET /v1/tax/form16-bulk-files': () => ({ status: 204, body: null }),
      'POST /v1/tax/form16-bulk-files': () => ({
        jobRequestId: 77,
        status: 'PENDING',
        written: 0,
        leftOut: 0,
      }),
    });
    wrap(<Form16Screen />, me(['C-10:VIEW']));
    await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
    const [toolbar] = screen.getAllByRole('button', { name: t('txw.c10.bulkPdf') });
    await waitFor(() => expect(toolbar).toBeEnabled());
    await userEvent.dblClick(toolbar as HTMLElement);
    expect((await screen.findAllByText(t('bulk.state.PENDING', [0, 0]))).length).toBeGreaterThan(0);
    expect(sent(calls, 'POST', '/v1/tax/form16-bulk-files')).toHaveLength(1);
    expect(calls.some((c) => c.url.includes('/v1/tax/form16-bulk-files?finYear='))).toBe(true);
  });

  it('OD_121_theCheckersTickIsDrawnDisabledWithTheReason', async () => {
    backend(routes);
    const { unmount } = wrap(<Form16Screen />, me(['C-10:VIEW', 'C-10:APPROVE']));
    const table = await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
    const row = (await within(table).findByText('106004')).closest('tr') as HTMLElement;
    const tick = within(row).getByRole('button', { name: '✔' });
    expect(tick).toBeDisabled();
    expect(tick).toHaveAttribute('title', t('txw.c10.why.decide'));
    unmount();
    wrap(<Form16Screen />, me(['C-10:VIEW']));
    const plain = await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
    await within(plain).findByText('106004');
    expect(within(plain).queryByRole('button', { name: '✔' })).toBeNull();
  });

  it('SRS_7_9_theFiltersAreSentAndTheMakerOpensTheRegister', async () => {
    const calls = backend(routes);
    wrap(<Form16Screen />, me(['C-10:VIEW', 'C-10:CREATE', 'C-10:EDIT']));
    await screen.findByRole('option', { name: t('txw.c10.filter.RELEASED') });
    await userEvent.selectOptions(screen.getByLabelText(t('txw.c10.allStatuses')), 'RELEASED');
    await screen.findByRole('option', { name: 'DH001 · DO Delhi HQ' });
    await userEvent.selectOptions(screen.getByLabelText(t('txw.c10.allOffices')), '7');
    await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
    expect(calls.some((c) => c.url.includes('status=RELEASED') && c.url.includes('office=7'))).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('txw.c10.generate') }));
    expect(screen.getByTestId('c10-register')).toHaveAttribute('open');
  });
});

describe('C-11 as drawn', () => {
  const routes = {
    'GET /v1/tax/challans': () => [],
    'GET /v1/tax/returns?': () => paged([]),
    'GET /v1/tax/returns/summary': () => ({
      finYear: '2026-27',
      quarter: 'Q2',
      status: 'GENERATED',
      validated: false,
      deductees: 10,
      staff: 10,
      labour: 0,
      tds: 184500,
      challans: 3,
      challansMapped: 2,
      panExceptions: 1,
      definitionVersion: 2,
      definitionFrom: '2026-04-01',
      units: [{ id: 7, code: 'DH001', name: 'DO Delhi HQ' }],
    }),
    'GET /v1/tax/returns/lines': () =>
      paged([
        {
          id: 1,
          type: 'FORM_24Q',
          finYear: '2026-27',
          quarter: 'Q2',
          deductees: 10,
          tds: 184500,
          validated: false,
          filed: null,
          token: null,
          status: 'GENERATED',
          correction: false,
        },
        {
          id: 2,
          type: 'FORM_24Q_CORRECTION',
          finYear: '2025-26',
          quarter: 'Q4',
          deductees: 2,
          tds: 41800,
          validated: true,
          filed: '2026-06-02',
          token: 'ACK-805518',
          status: 'FILED',
          correction: true,
        },
      ]),
    'GET /v1/reference/lists': () => LISTS,
  };

  it('SRS_7_21_itOpensOnTheLastCompletedQuarterWithAnUnmappedChallanSaid', async () => {
    const calls = backend(routes);
    wrap(<ReturnsScreen />, me(['C-11:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.c11.registerLabel') });
    expect(await within(table).findByText('ACK-••5518')).toBeInTheDocument();
    expect(table).toHaveTextContent(t('txw.c11.correction'));
    expect(await screen.findByText(t('txw.c11.tile.challansSub', [1]))).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('finYear=2026-27&quarter=Q2'))).toBe(true);
    expect(screen.getByRole('button', { name: t('txw.c11.generate') })).toBeDisabled();
  });

  it('SRS_7_7_theMakerOpensTheRegisterAndARowOpensItsRecord', async () => {
    backend(routes);
    wrap(<ReturnsScreen />, me(['C-11:VIEW', 'C-11:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} Q2 FY27` }));
    expect(screen.getByRole('dialog')).toHaveTextContent('1,84,500');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.close') }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('txw.c11.generate') }));
    expect(screen.getByTestId('c11-register')).toHaveAttribute('open');
  });
});

describe('the drawn rules of display', () => {
  it('SRS_7_7_theLastCompletedQuarterCrossesTheFinancialYear', () => {
    expect(lastCompletedQuarter('2026-11-07')).toEqual({ finYear: '2026-27', quarter: 'Q2' });
    expect(lastCompletedQuarter('2026-04-01')).toEqual({ finYear: '2025-26', quarter: 'Q4' });
    expect(lastCompletedQuarter('2027-01-15')).toEqual({ finYear: '2026-27', quarter: 'Q3' });
    expect(lastCompletedQuarter('2026-07-01')).toEqual({ finYear: '2026-27', quarter: 'Q1' });
  });

  it('SRS_7_1_theStatusOfALineFollowsItsRecord', () => {
    expect(declarationStatus(NO_REGIME)).toBe('txw.i07.status.noRegime');
    expect(declarationStatus(LINE)).toBe('txw.i07.status.capped');
    expect(declarationStatus({ ...LINE, capped: false, unproved: true })).toBe('txw.i07.status.proofPending');
    expect(declarationStatus({ ...LINE, capped: false, roundId: null })).toBe('txw.i07.status.noRound');
    expect(declarationStatus({ ...LINE, capped: false })).toBe('txw.i07.status.complete');
    expect(proofLabel(NO_REGIME)).toBeNull();
    expect(proofLabel({ ...NO_REGIME, regime: 'N' })).toBe('txw.i07.proof.none');
    expect(proofLabel({ ...LINE, unproved: true })).toBe('txw.i07.proof.notSubmitted');
    expect(proofLabel(LINE)).toBe('txw.i07.proof.verified');
  });

  it('BR_TAX_010_aCertificateStatusPutsTheReleasedThenThePanFirst', () => {
    expect(certificateStatus(CERTIFICATE)).toBe('txw.c10.status.issued');
    expect(certificateStatus({ ...CERTIFICATE, status: 'MERGED', panStatus: 'INVALID' })).toBe(
      'txw.c10.status.blockedPan',
    );
    expect(certificateStatus({ ...CERTIFICATE, status: 'MERGED' })).toBe('txw.c10.status.unsigned');
    expect(certificateStatus({ ...CERTIFICATE, status: 'PENDING_PART_A' })).toBe(
      'txw.c10.status.correctionPending',
    );
  });

  it('BR_MIS_002_theVersionOfAYearIsTheNewestEffectiveByItsLastDay', () => {
    const v = (effectiveFrom: string) => ({ ...VERSION, effectiveFrom }) as never;
    const held = [v('2025-04-01'), v('2026-04-01')];
    expect(versionOfYear(held, '2026-27')?.effectiveFrom).toBe('2026-04-01');
    expect(versionOfYear(held, '2025-26')?.effectiveFrom).toBe('2025-04-01');
    expect(versionOfYear(held, '2024-25')).toBeNull();
  });

  it('SRS_7_8_theStepsFollowTheReturn', () => {
    expect(returnSteps(undefined)[0]).toBe('txw.c11.pill.pending');
    expect(returnSteps({ status: 'GENERATED', validated: false } as never)[4]).toBe('txw.c11.pill.pending');
    expect(returnSteps({ status: 'VALIDATED', validated: true } as never)[4]).toBe('txw.c11.pill.passed');
    expect(returnSteps({ status: 'RELEASED', validated: true } as never)[5]).toBe('txw.c11.pill.done');
    expect(returnSteps({ status: 'FILED', validated: true } as never)[7]).toBe('txw.c11.pill.done');
    expect(maskedToken(null)).toBe('—');
    expect(maskedToken('ACK-805518')).toBe('ACK-••5518');
    expect(maskedToken('805518')).toBe('••5518');
  });
});
