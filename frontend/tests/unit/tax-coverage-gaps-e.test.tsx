import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ComputationScreen,
  DeclarationsScreen,
  Form16Screen,
  OutputDefinitionsScreen,
  ReturnsScreen,
  SavingsScreen,
} from '@/modules/tax';
import type { CertificateLine, DeclarationLine } from '@/modules/tax/api/screensApi';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * Coverage of the drawn tax screens' remaining paths (M-17, I-07, C-07, C-10, C-11; OD-121): the row and toolbar
 * actions, the Checker's way to W-01, the filters sent, the order copy of a round and the empty values.
 */
afterEach(() => vi.unstubAllGlobals());
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T06:00:00Z'));
});
afterEach(() => vi.useRealTimers());

function stubAssign() {
  const assign = vi.fn();
  vi.stubGlobal('location', { ...window.location, assign });
  return assign;
}

const LISTS = referenceLists({
  STATUTORY_OUTPUT_FAMILY: [['INCOME_TAX', 'Income tax']],
  TAX_RETURN_QUARTER: [
    ['Q1', 'Q1 Apr-Jun'],
    ['Q2', 'Q2 Jul-Sep'],
  ],
  ORDER_COPY_DOCUMENT_TYPE: [
    ['CIRCULAR', 'Circular'],
    ['OTHER', 'Other'],
  ],
});

describe('M-17 remaining paths', () => {
  const OUTPUTS = [
    {
      key: 'FORM_24Q',
      name: 'Form 24Q',
      producingScreen: 'C-11',
      statutoryReference: null,
      state: 'ACTIVE',
      versions: [
        {
          id: 3,
          number: 1,
          effectiveFrom: '2025-04-01',
          effectiveTo: null,
          layoutFormat: 'PRESCRIBED_FILE',
          periodLockRequired: true,
          inForce: true,
          fields: [],
          controls: [],
        },
      ],
      pendingApprovals: [4],
    },
  ];
  const SUMMARY = {
    carriedForward: 0,
    superseded: 0,
    families: 1,
    outputs: [
      {
        key: 'FORM_24Q',
        name: 'Form 24Q',
        family: 'INCOME_TAX',
        periodicity: null,
        lockRequirement: 'POST_LOCK',
        statutoryReference: null,
        carriedForward: false,
        catalogueReport: false,
      },
      {
        key: 'FORM_12BA',
        name: 'Form 12BA - perquisites',
        family: 'INCOME_TAX',
        periodicity: 'Annual',
        lockRequirement: 'NONE',
        statutoryReference: 'SRS-7-5',
        carriedForward: false,
        catalogueReport: false,
      },
    ],
  };
  const routes = {
    'GET /v1/tax/output-definitions/summary': () => SUMMARY,
    'GET /v1/tax/output-definitions': () => OUTPUTS,
    'GET /v1/reference/lists': () => LISTS,
  };

  it('BR_MIS_002_anOutputWithNoDefinitionIsBlockedAndAPrescribedFileReadsSo', async () => {
    backend(routes);
    wrap(<OutputDefinitionsScreen />, me(['M-17:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.m17.table') });
    const blocked = (await within(table).findByText('Form 12BA - perquisites')).closest('tr') as HTMLElement;
    expect(blocked).toHaveTextContent(t('txw.m17.status.blocked'));
    expect(blocked).toHaveTextContent(t('txw.m17.format.document'));
    const filed = within(table).getByText('Form 24Q').closest('tr') as HTMLElement;
    expect(filed).toHaveTextContent(t('txw.m17.format.file'));
    expect(filed).toHaveTextContent(t('txw.m17.status.pending'));
    // The output of the year before has its version only in that year: the other year shows none.
    await userEvent.selectOptions(screen.getByLabelText(t('txw.m17.effectiveFy', ['2026-27'])), '2025-26');
    expect(within(table).getByText('Form 24Q').closest('tr')).toHaveTextContent('v1');
    // A row chosen becomes the detail; its empty reference reads as a dash in the record.
    await userEvent.click(blocked);
    expect(blocked).toHaveClass('fl-row-selected');
    expect(screen.getByText(t('txw.m17.detail', ['Form 12BA', '—']))).toBeInTheDocument();
    await userEvent.click(within(filed).getByRole('button', { name: `${t('common.view')} FORM_24Q` }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent(t('txw.m17.d.lockYes'));
    expect(blocked).toHaveClass('fl-row-selected');
  });

  it('SRS_G_54_historyAndCompareOpenTheRegisterAndTheApproverGoesToTheInbox', async () => {
    backend(routes);
    const assign = stubAssign();
    wrap(<OutputDefinitionsScreen />, me(['M-17:VIEW', 'M-17:APPROVE']));
    const table = await screen.findByRole('table', { name: t('txw.m17.table') });
    const pending = (await within(table).findByText('Form 24Q')).closest('tr') as HTMLElement;
    await userEvent.click(within(pending).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
    const none = within(table).getByText('Form 12BA - perquisites').closest('tr') as HTMLElement;
    await userEvent.click(within(none).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledTimes(1);
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('txw.m17.history') }));
    expect(screen.getByTestId('m17-register')).toHaveAttribute('open');
  });

  it('SRS_G_54_compareOpensTheRegisterAndTheCheckerIsToldWhoDecides', async () => {
    backend(routes);
    wrap(<OutputDefinitionsScreen />, me(['M-17:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const table = await screen.findByRole('table', { name: t('txw.m17.table') });
    const row = (await within(table).findByText('Form 24Q')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('mst.why.ruleApprover'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('txw.m17.compare') }));
    expect(screen.getByTestId('m17-register')).toHaveAttribute('open');
  });

  it('SRS_G_54_theRuleAdministratorEditsFromARowAndAnHqOnlyRouteSaysWhy', async () => {
    backend(routes);
    wrap(
      <OutputDefinitionsScreen />,
      me(['M-17:VIEW', 'M-17:CREATE'], { hqOnlyScreens: ['M-17'], atHeadquarters: false }),
    );
    const table = await screen.findByRole('table', { name: t('txw.m17.table') });
    const row = (await within(table).findByText('Form 24Q')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: `${t('common.edit')} FORM_24Q` }));
    expect(screen.getByTestId('m17-register')).not.toHaveAttribute('open');
    expect(screen.queryByRole('button', { name: '✔' })).toBeNull();
  });
});

const LINE: DeclarationLine = {
  employeeId: 1,
  eis: '105220',
  name: 'S. Banerjee',
  regime: 'N',
  frozen: false,
  frozenAt: null,
  roundId: 11,
  roundStatus: 'SUBMITTED',
  declared80c: 50000,
  limit80c: 150000,
  declared80d: 0,
  declared24b: 0,
  allowed: 50000,
  capped: false,
  unproved: false,
};

describe('I-07 remaining paths', () => {
  const SUMMARY = {
    finYear: '2026-27',
    members: 0,
    newRegime: 0,
    oldRegime: 0,
    notSelected: 0,
    received: 0,
    pending: 0,
    capped: 0,
    unproved: 0,
    sections: [
      { code: '80C', regime: 'OLD', limit: null, effectiveFrom: '2026-04-01', rule: false },
      { code: 'TAX.SLAB.NEW', regime: 'NEW', limit: null, effectiveFrom: '2026-04-01', rule: true },
    ],
  };
  const routes = {
    'GET /v1/tax/declarations?': () => paged([]),
    'GET /v1/tax/declarations/summary': () => SUMMARY,
    'GET /v1/tax/declarations/members': () => paged([LINE]),
    'GET /v1/tax/declarations/11/orders': () => [],
    'GET /v1/document-types': () => [
      { code: 'CIRCULAR', name: 'Circular', referenceRequired: true },
      { code: 'OTHER', name: 'Other', referenceRequired: false },
    ],
    'GET /v1/reference/lists': () => LISTS,
  };

  it('SRS_7_20_theNewRegimeIsNamedAndARuleHeldReadsConfigured', async () => {
    backend(routes);
    wrap(<DeclarationsScreen />, me(['I-07:VIEW', 'M-18:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.i07.registerLabel') });
    const row = (await within(table).findByText(/105220/)).closest('tr') as HTMLElement;
    expect(row).toHaveTextContent(t('e01t.regime.NEW'));
    // The 80C limit not configured, the new slabs held as a rule; no member: the split is 0 % / 0 %.
    const sections = screen.getByText('80C', { selector: 'td' }).closest('tr') as HTMLElement;
    expect(sections).toHaveTextContent(t('txw.i07.sec.notConfigured'));
    expect(screen.getAllByText(t('txw.i07.sec.configured')).length).toBe(1);
    expect(screen.getByText('0% / 0%')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('txw.i07.openRules') })).toHaveAttribute(
      'href',
      expect.stringContaining('?s=M-18'),
    );
  });

  it('SRS_7_1_theMakerRecomputesOpensTheProofsFromTheToolbarAndTheRow', async () => {
    const calls = backend(routes);
    wrap(<DeclarationsScreen />, me(['I-07:VIEW', 'I-07:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('txw.i07.registerLabel') });
    const row = (await within(table).findByText(/105220/)).closest('tr') as HTMLElement;
    const before = calls.filter((c) => c.url.includes('/v1/tax/declarations/summary')).length;
    await userEvent.click(screen.getByRole('button', { name: t('txw.i07.recompute') }));
    expect(await screen.findByText(t('txw.i07.recomputed'))).toBeInTheDocument();
    await waitFor(() =>
      expect(calls.filter((c) => c.url.includes('/v1/tax/declarations/summary')).length).toBeGreaterThan(
        before,
      ),
    );
    await userEvent.click(within(row).getByRole('button', { name: `${t('common.edit')} 105220` }));
    expect(screen.getByTestId('i07-register')).toHaveAttribute('open');
    // The row's own buttons do not reselect the row (the cell stops the click).
    expect(row).toHaveClass('fl-row-selected');
    // The rules table opens M-18 only for a role that may read it.
    const rules = screen.getByRole('button', { name: t('txw.i07.openRules') });
    expect(rules).toHaveAttribute('data-guard', t('txw.i07.why.rules'));
  });

  it('SRS_7_24_theProofVerificationOpensTheRegisterAndTheCheckerGoesToW01', async () => {
    backend(routes);
    const assign = stubAssign();
    wrap(<DeclarationsScreen />, me(['I-07:VIEW', 'I-07:APPROVE']));
    const table = await screen.findByRole('table', { name: t('txw.i07.registerLabel') });
    const row = (await within(table).findByText(/105220/)).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
    await userEvent.click(within(row).getByRole('button', { name: `${t('common.view')} 105220` }));
    expect(screen.getByTestId('i07-register')).toHaveAttribute('open');
    await userEvent.click(screen.getByRole('button', { name: t('txw.i07.proofVerification') }));
    expect(screen.getByTestId('i07-register')).toHaveAttribute('open');
  });

  it('SRS_G_30_theMakerStoresTheOrderOfTheSelectedRoundFileByFile', async () => {
    const calls = backend({
      ...routes,
      'POST /v1/tax/declarations/11/orders': () => ({ status: 201, body: { id: 3 } }),
    });
    wrap(<DeclarationsScreen />, me(['I-07:VIEW', 'I-07:SUBMIT']));
    await screen.findByText(/105220/);
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    const [type] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    await screen.findByRole('option', { name: 'Circular' });
    const [reference, orderDate, , files] = Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
    const pdf = (name: string) => new File(['%PDF-1.7'], name, { type: 'application/pdf' });
    // A circular needs its reference (document_type_master): without it nothing is stored.
    await userEvent.selectOptions(type as HTMLSelectElement, 'CIRCULAR');
    fireEvent.change(orderDate as HTMLInputElement, { target: { value: '2026-10-01' } });
    await userEvent.upload(files as HTMLInputElement, pdf('a.pdf'));
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/orders')).toHaveLength(0);
    await userEvent.type(reference as HTMLInputElement, 'FCI/TAX/2026/11');
    await userEvent.upload(files as HTMLInputElement, [pdf('a.pdf'), pdf('b.pdf')]);
    await waitFor(() =>
      expect(
        calls.filter((c) => c.method === 'POST' && c.url.endsWith('/v1/tax/declarations/11/orders')),
      ).toHaveLength(2),
    );
    const [first] = sent(calls, 'POST', '/v1/tax/declarations/11/orders') as FormData[];
    expect(first?.get('documentType')).toBe('CIRCULAR');
    expect(first?.get('reference')).toBe('FCI/TAX/2026/11');
  });

  it('SRS_G_30_aStoredOrderOpensFromItsRoundsFileLink', async () => {
    backend({
      ...routes,
      'GET /v1/tax/declarations/11/orders': () => [
        {
          id: 8,
          fileName: 'order_11.pdf',
          documentTypeCode: 'OTHER',
          documentTypeName: 'Other',
          referenceNumber: null,
          uploadedBy: 'maker@fci-demo.test',
          uploadedAt: '2026-10-01T10:00:00Z',
        },
      ],
    });
    wrap(<DeclarationsScreen />, me(['I-07:VIEW']));
    const link = await screen.findByRole('link', { name: t('wf.ord.viewOrder') });
    expect(link.getAttribute('href')).toContain('/v1/tax/declarations/11/files/8');
  });
});

describe('C-07 remaining paths', () => {
  it('SRS_7_26_aTdsComputedIsShownAndAReasonOrAMissingRegimeIsSaid', async () => {
    backend({
      'GET /v1/tax/statutory-computation/summary': () => ({
        period: '202609',
        cpfRate: 12,
        types: [{ payrollType: 'STAFF', members: 2, employee: 1000, vpf: null, tds: 9055 }],
        noCpfCode: 0,
        npsMismatch: 0,
        regimeMissing: 1,
      }),
      'GET /v1/tax/statutory-computation/eps': () => paged([]),
      'GET /v1/tax/statutory-computation/members': () =>
        paged([
          {
            employeeId: 3,
            eis: '107001',
            name: 'K. Iyer',
            regime: null,
            taxable: null,
            annualTax: null,
            monthlyTds: null,
            professionalTax: null,
            higherRate: false,
            reason: 'TAX-422-REGIME-REQUIRED',
          },
          {
            employeeId: 4,
            eis: '107002',
            name: 'L. Das',
            regime: 'OLD',
            taxable: null,
            annualTax: null,
            monthlyTds: null,
            professionalTax: null,
            higherRate: false,
            reason: 'TAX-422-RATE-NOT-CONFIGURED:TAX.SLAB.OLD',
          },
        ]),
      'GET /v1/tax/statutory-computation/nps': () =>
        paged([
          {
            id: 3,
            eis: '108999',
            name: 'A. Roy',
            pattern: null,
            pfType: null,
            npsMember: false,
            pran: null,
            employeeAmount: null,
            employerAmount: null,
            mismatch: 'AMOUNT_WITHOUT_FLAG',
            applyStatus: 'HELD',
          },
        ]),
      'GET /v1/reference/lists': () => LISTS,
    });
    wrap(<ComputationScreen />, me(['C-07:VIEW']));
    expect(await screen.findByText('₹ 9,055')).toBeInTheDocument();
    expect(screen.getByText('202609')).toBeInTheDocument();
    const missing = (await screen.findByText(/107001/)).closest('tr') as HTMLElement;
    expect(missing).toHaveTextContent(t('error.TAX-422-REGIME-REQUIRED'));
    expect(missing.querySelectorAll('td')[1]).toHaveTextContent('—');
    const plain = screen.getByText(/107002/).closest('tr') as HTMLElement;
    expect(plain).toHaveTextContent(t('error.TAX-422-RATE-NOT-CONFIGURED', ['TAX.SLAB.OLD']));
    expect(plain).toHaveTextContent(t('e01t.regime.OLD'));
    const nps = (await screen.findByText(/108999/)).closest('tr') as HTMLElement;
    expect(nps).toHaveTextContent(t('txw.c07.nps.notNps'));
    expect(nps).toHaveTextContent(t('txw.c07.nps.AMOUNT_WITHOUT_FLAG'));
    expect(nps.querySelectorAll('td')[1]).toHaveTextContent('—');
    expect(nps.querySelectorAll('td')[2]).toHaveTextContent('—');
  });
});

const CERTIFICATE: CertificateLine = {
  id: 5,
  eis: '104882',
  name: 'R. Sharma',
  panMasked: 'ABCXXXX34K',
  panStatus: 'VALID',
  regime: 'O',
  taxable: 1233384,
  tds: 108660,
  partB: true,
  partA: true,
  signed: true,
  status: 'RELEASED',
};

describe('C-10 remaining paths', () => {
  const routes = {
    'GET /v1/tax/form16-certificates/summary': () => ({
      finYear: '2025-26',
      due: 2,
      partB: 2,
      partA: 1,
      merged: 1,
      issued: 1,
      form10e: 0,
      units: [],
    }),
    'GET /v1/tax/form16-certificates/lines': () =>
      paged([
        CERTIFICATE,
        { ...CERTIFICATE, id: 6, eis: '106004', name: 'P. Nayak', regime: null, panMasked: null },
      ]),
    'GET /v1/tax/form16-bulk-files': () => ({
      jobRequestId: 31,
      status: 'COMPLETED',
      written: 2,
      leftOut: 0,
    }),
    'GET /v1/tax/form16-bulk-files/31/file': () => ({}),
    'GET /v1/reference/lists': () => LISTS,
  };

  it('SRS_7_9_theYearIsSentTheSearchNarrowsAndARowOpensItsRecord', async () => {
    const calls = backend(routes);
    wrap(<Form16Screen />, me(['C-10:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
    const old = (await within(table).findByText('104882')).closest('tr') as HTMLElement;
    expect(old).toHaveTextContent(t('e01t.regime.OLD'));
    const none = within(table).getByText('106004').closest('tr') as HTMLElement;
    expect(none.querySelectorAll('td')[3]).toHaveTextContent('—');
    await userEvent.selectOptions(screen.getByLabelText(t('txw.fy', ['2025-26'])), '2024-25');
    await waitFor(() =>
      expect(
        calls.some((c) => c.url.includes('/form16-certificates/summary?') && c.url.includes('2024-25')),
      ).toBe(true),
    );
    await userEvent.type(screen.getByLabelText(t('wf.searchInList')), 'nayak');
    await waitFor(() => expect(within(table).queryByText('104882')).toBeNull());
    await userEvent.click(within(table).getByRole('button', { name: `${t('common.view')} 106004` }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('P. Nayak');
    expect(dialog).toHaveTextContent('—');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('OD_149_aCompletedBulkJobDownloadsItsZipFromBothButtons', async () => {
    const calls = backend(routes);
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      wrap(<Form16Screen />, me(['C-10:VIEW', 'C-10:EDIT']));
      await screen.findByRole('table', { name: t('txw.c10.registerLabel') });
      const [top, inTable] = screen
        .getAllByRole('button', { name: /./ })
        .filter((b) => [t('txw.c10.bulkPdf'), t('txw.c10.bulkDownload')].includes(b.textContent ?? ''));
      await waitFor(() => expect(top).toHaveAttribute('aria-describedby'));
      await userEvent.click(top as HTMLElement);
      await waitFor(() =>
        expect(calls.filter((c) => c.url.endsWith('/v1/tax/form16-bulk-files/31/file'))).toHaveLength(1),
      );
      await waitFor(() => expect(inTable).toHaveAttribute('aria-describedby'));
      await waitFor(() => expect(inTable).toBeEnabled());
      await userEvent.click(inTable as HTMLElement);
      await waitFor(() =>
        expect(calls.filter((c) => c.url.endsWith('/v1/tax/form16-bulk-files/31/file'))).toHaveLength(2),
      );
      expect(sent(calls, 'POST', '/v1/tax/form16-bulk-files')).toHaveLength(0);
      await userEvent.click(screen.getByRole('button', { name: t('txw.c10.uploadPartA') }));
      expect(screen.getByTestId('c10-register')).toHaveAttribute('open');
    } finally {
      click.mockRestore();
    }
  });

  it('SRS_7_9_theMakerReissuesFromTheRegisterCard', async () => {
    backend(routes);
    wrap(<Form16Screen />, me(['C-10:VIEW', 'C-10:EDIT']));
    await userEvent.click(await screen.findByRole('button', { name: t('txw.c10.reissue') }));
    expect(screen.getByTestId('c10-register')).toHaveAttribute('open');
  });
});

describe('C-11 remaining paths', () => {
  const routes = {
    'GET /v1/tax/challans': () => [],
    'GET /v1/tax/returns?': () => paged([]),
    'GET /v1/tax/returns/summary': () => ({
      finYear: '2026-27',
      quarter: 'Q2',
      status: null,
      validated: false,
      deductees: 0,
      staff: 0,
      labour: 0,
      tds: null,
      challans: 0,
      challansMapped: 0,
      panExceptions: 0,
      definitionVersion: null,
      definitionFrom: null,
      units: [{ id: 7, code: 'DH001', name: 'DO Delhi HQ' }],
    }),
    'GET /v1/tax/returns/lines': () =>
      paged([
        {
          id: 1,
          type: 'FORM_24Q',
          finYear: '2026-27',
          quarter: 'Q1',
          deductees: 0,
          tds: null,
          validated: false,
          filed: null,
          token: null,
          status: 'GENERATED',
          correction: false,
        },
        {
          id: 2,
          type: 'FORM_24Q',
          finYear: '2025-26',
          quarter: 'Q4',
          deductees: 3,
          tds: 5000,
          validated: true,
          filed: null,
          token: null,
          status: 'GENERATED',
          correction: false,
        },
      ]),
    'GET /v1/reference/lists': () => LISTS,
  };

  it('SRS_7_7_theYearQuarterAndUnitAreSentAndTheSearchNarrowsTheRows', async () => {
    const calls = backend(routes);
    wrap(<ReturnsScreen />, me(['C-11:VIEW']));
    const table = await screen.findByRole('table', { name: t('txw.c11.registerLabel') });
    const empty = (await within(table).findByText('Q1 FY27')).closest('tr') as HTMLElement;
    expect(empty.querySelectorAll('td')[3]).toHaveTextContent('—');
    await userEvent.selectOptions(screen.getByLabelText(t('txw.fy', ['2026-27'])), '2025-26');
    await screen.findByRole('option', { name: t('txw.c11.q.Q1') });
    await userEvent.selectOptions(screen.getByLabelText(t('txw.c11.quarter')), 'Q1');
    await waitFor(() => expect(calls.some((c) => c.url.includes('finYear=2025-26&quarter=Q1'))).toBe(true));
    await screen.findByRole('option', { name: 'DH001 · DO Delhi HQ' });
    await userEvent.selectOptions(screen.getByLabelText(t('txw.c11.allUnits')), '7');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/tax/returns/lines?office=7&'))).toBe(true),
    );
    await userEvent.type(screen.getByLabelText(t('wf.searchInList')), 'fy26');
    await waitFor(() => expect(within(table).queryByText('Q1 FY27')).toBeNull());
    expect(within(table).getByText('Q4 FY26')).toBeInTheDocument();
  });

  it('SRS_7_8_theCheckerGoesToTheInboxFromARowAndARefusedSummaryIsShown', async () => {
    backend({ ...routes, 'GET /v1/tax/returns/summary': () => problem(500, 'COM-500-INTERNAL') });
    const assign = stubAssign();
    wrap(<ReturnsScreen />, me(['C-11:VIEW', 'C-11:APPROVE']));
    const table = await screen.findByRole('table', { name: t('txw.c11.registerLabel') });
    const row = (await within(table).findByText('Q1 FY27')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
    expect(await screen.findByText(t('error.COM-500-INTERNAL'))).toBeInTheDocument();
  });
});

describe('I-18 and the order types', () => {
  it('SRS_G_54_theCheckerGoesFromARowOfI18ToTheInbox', async () => {
    backend({ 'GET /v1/tax/savings': () => paged([]) });
    const assign = stubAssign();
    wrap(<SavingsScreen />, me(['I-18:VIEW', 'I-18:APPROVE']));
    const [first] = screen.getAllByRole('button', { name: '✔' });
    await userEvent.click(first as HTMLElement);
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
  });

  it('SRS_G_30_aTypeTheDocumentMasterDoesNotHoldNeedsNoReference', async () => {
    const calls = backend({
      'GET /v1/tax/declarations/summary': () => ({
        finYear: '2026-27',
        members: 1,
        newRegime: 1,
        oldRegime: 0,
        notSelected: 0,
        received: 1,
        pending: 0,
        capped: 0,
        unproved: 0,
        sections: [],
      }),
      'GET /v1/tax/declarations/members': () => paged([LINE]),
      'GET /v1/tax/declarations/11/orders': () => [],
      'GET /v1/document-types': () => [{ code: 'CIRCULAR', name: 'Circular', referenceRequired: true }],
      'POST /v1/tax/declarations/11/orders': () => ({ status: 201, body: { id: 3 } }),
      'GET /v1/reference/lists': () => LISTS,
    });
    wrap(<DeclarationsScreen />, me(['I-07:VIEW', 'I-07:SUBMIT']));
    await screen.findByText(/105220/);
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    await screen.findByRole('option', { name: 'Other' });
    const [type] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    const [, orderDate, , files] = Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
    await userEvent.selectOptions(type as HTMLSelectElement, 'OTHER');
    fireEvent.change(orderDate as HTMLInputElement, { target: { value: '2026-10-01' } });
    await userEvent.upload(
      files as HTMLInputElement,
      new File(['%PDF'], 'o.pdf', { type: 'application/pdf' }),
    );
    await waitFor(() => expect(sent(calls, 'POST', '/v1/tax/declarations/11/orders')).toHaveLength(1));
  });

  it('SRS_7_26_aTdsWithoutItsPeriodShowsTheAmountAndNoPeriod', async () => {
    backend({
      'GET /v1/tax/statutory-computation/summary': () => ({
        period: null,
        cpfRate: 12,
        types: [{ payrollType: 'STAFF', members: 1, employee: null, vpf: null, tds: 100 }],
        noCpfCode: 0,
        npsMismatch: 0,
        regimeMissing: 0,
      }),
      'GET /v1/tax/statutory-computation/eps': () => paged([]),
      'GET /v1/tax/statutory-computation/members': () => paged([]),
      'GET /v1/tax/statutory-computation/nps': () => paged([]),
    });
    wrap(<ComputationScreen />, me(['C-07:VIEW']));
    expect(await screen.findByText('₹ 100')).toBeInTheDocument();
    const tile = screen.getByText(t('txw.c07.tile.tds')).closest('.tile') as HTMLElement;
    expect(tile.querySelector('.big')).toHaveTextContent('₹ 100');
    expect(tile.querySelector('.sub')?.textContent).toBe('');
  });
});
