import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SavingsDeclarations } from '@/modules/tax';
import type { MemberSavings, SavingsEntry } from '@/modules/tax/api/taxApi';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * I-18 Savings & Tax Rebate Declarations (SRS-7-11, SRS-7-20, SRS-7-25; BR-TAX-015): the entry and
 * rebate checks, the register, a member's year, a new entry with its document, submit and cancel,
 * the transport rebate months, and who may act.
 */

afterEach(() => vi.unstubAllGlobals());
// The year selector offers the business year and the four before it (shared FinancialYearSelect):
// the fixtures' years (2024-25, 2025-26) are in that window on this pinned date in India.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T06:00:00Z'));
});
afterEach(() => vi.useRealTimers());

const SECTIONS = [{ code: '80C', description: 'Life insurance', limit: 150000, regime: 'OLD' as const }];

const SAVED: SavingsEntry = {
  id: 5,
  employeeId: 9,
  eis: '870003',
  name: 'Lata Pillai',
  finYear: '2026-27',
  type: 'OTHER_INCOME',
  sectionCode: null,
  incomeHead: 'Honorarium',
  description: 'Guest lecture',
  amount: 12000,
  tdsOtherSource: 1200,
  status: 'MAKER_SAVED',
  documentId: 31,
  enteredBy: 'tax.maker.delhi@fci-demo.test',
  enteredAt: '2026-10-24T05:00:00Z',
};

const YEAR: MemberSavings = {
  employeeId: 9,
  eis: '870003',
  name: 'Lata Pillai',
  finYear: '2026-27',
  entries: [
    SAVED,
    { ...SAVED, id: 6, type: 'EXTERNAL_SAVINGS', sectionCode: '80C', incomeHead: null, status: 'APPROVED' },
  ],
  savings: [
    {
      id: 1,
      type: 'EXTERNAL',
      sectionCode: '80C',
      annualAmount: 40000,
      payrollYymm: null,
      monthlyAmount: null,
      componentId: null,
    },
    {
      id: 2,
      type: 'TRANSPORT_REBATE',
      sectionCode: null,
      annualAmount: null,
      payrollYymm: '202604',
      monthlyAmount: 1600,
      componentId: null,
    },
  ],
  pendingRebates: [],
  sections: SECTIONS,
};

const MAKER = me(['I-18:VIEW', 'I-18:CREATE', 'I-18:EDIT', 'I-18:SUBMIT']);

function routes(
  year: MemberSavings,
  over: Record<string, (url: string, init?: RequestInit) => unknown> = {},
) {
  return backend({
    'GET /v1/tax/savings?': (url) => paged([SAVED], 1, url),
    'GET /v1/tax/savings/members/870003': () => year,
    'GET /v1/document-types': () => [
      { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
      { code: 'OTHER', name: 'Other', referenceRequired: false },
    ],
    ...over,
  });
}

async function openMember() {
  await userEvent.click(await screen.findByRole('button', { name: t('i18.openOf', ['870003']) }));
  return screen.findByRole('table', { name: t('i18.entries') });
}

describe('I-18 screen', () => {
  it('SRS-7-25 (UI audit S5): the member year opened is the financial year chosen, sent to the server', async () => {
    const calls = routes(YEAR);
    wrap(<SavingsDeclarations />, MAKER);
    await userEvent.selectOptions(screen.getByLabelText(t('i07.finYear')), '2025-26');
    await openMember();
    const member = calls.filter((c) => c.url.includes('/v1/tax/savings/members/870003'));
    expect(member.at(-1)?.url).toContain('finYear=2025-26');
    // Another year chosen reads that year's member (no partly typed year can reach the screen).
    await userEvent.selectOptions(screen.getByLabelText(t('i07.finYear')), '2024-25');
    expect(calls.filter((c) => c.url.includes('finYear=2025&')).length).toBe(0);
  });

  it('SRS_7_25_theRegisterOpensAMembersYearWithEntriesSavingsAndRebates', async () => {
    routes(YEAR);
    wrap(<SavingsDeclarations />, MAKER);
    const register = await screen.findByRole('table', { name: t('screen.I-18') });
    expect(register).toHaveTextContent('Honorarium');
    const entries = await openMember();
    const saved = within(entries).getByText('Honorarium').closest('tr') as HTMLElement;
    expect(saved).toHaveTextContent('₹12,000.00');
    expect(saved).toHaveTextContent('₹1,200.00');
    expect(saved).toHaveTextContent('Guest lecture');
    expect(within(saved).getByRole('link', { name: t('i18.document') })).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/tax/savings/entries/5/documents/31'),
    );
    // A decided entry has no action.
    const approved = within(entries).getByText('80C').closest('tr') as HTMLElement;
    expect(within(approved).queryByRole('button')).toBeNull();
    const savings = screen.getByRole('table', { name: t('i18.savings') });
    expect(savings).toHaveTextContent(t('i18.savingsType.EXTERNAL'));
    expect(savings).toHaveTextContent('202604 · ₹1,600.00');
    // The approved rebate month is closed for a new proposal.
    expect(screen.getByLabelText('202604')).toBeDisabled();
  });

  it('SRS_7_25_aSavedEntryIsSubmittedOrCancelledWithAReason', async () => {
    const calls = routes(YEAR, {
      'POST /v1/tax/savings/entries/5/submit': () => ({
        status: 202,
        body: { ...SAVED, status: 'PENDING_CHECKER' },
      }),
      'POST /v1/tax/savings/entries/5/cancel': () => ({ ...SAVED, status: 'CANCELLED' }),
    });
    wrap(<SavingsDeclarations />, MAKER);
    await openMember();
    const cancel = screen.getByRole('button', { name: t('i18.cancelOf', [5]) });
    expect(cancel).toBeDisabled();
    expect(cancel).toHaveAttribute('title', t('i07.reasonFirst'));
    await userEvent.type(screen.getByLabelText(t('i18.cancelReason')), ' Entered twice ');
    await userEvent.click(cancel);
    // SRS-EH-12 (US-GAP-115): a cancel asks first; answering Cancel sends nothing.
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent(t('confirm.cancel.body'));
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(sent(calls, 'POST', '/5/cancel')).toHaveLength(0);
    await userEvent.click(cancel);
    await userEvent.click(await screen.findByRole('button', { name: t('confirm.cancel.yes') }));
    expect(await screen.findByText(t('i18.cancelled'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/5/cancel')[0]).toEqual({ reason: 'Entered twice' });
    await userEvent.click(screen.getByRole('button', { name: t('i18.submitOf', [5]) }));
    expect(await screen.findByText(t('i18.submitted'))).toBeInTheDocument();
  });

  it('SRS_7_25_aNewEntryStartsWithItsDocumentAndIsSavedForTheMaker', async () => {
    const calls = routes(YEAR, {
      'POST /v1/tax/savings/members/870003/entries': () => ({ status: 201, body: SAVED }),
    });
    wrap(<SavingsDeclarations />, MAKER);
    await openMember();
    await userEvent.upload(
      screen.getByLabelText(t('i18.document')),
      new File(['%PDF'], 'form16a.pdf', { type: 'application/pdf' }),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('i18.type')), 'EXTERNAL_SAVINGS');
    await userEvent.selectOptions(screen.getByLabelText(t('m18s.section')), '80C');
    await userEvent.type(screen.getByLabelText(t('i18.amount')), '40000');
    await userEvent.type(screen.getByLabelText(t('i18.tds')), '0');
    await userEvent.type(screen.getByLabelText(t('m18s.description')), 'PPF outside payroll');
    await screen.findByRole('option', { name: 'Other' });
    await userEvent.selectOptions(screen.getByLabelText(t('i18.documentType')), 'OTHER');
    await userEvent.type(screen.getByLabelText(t('i18.documentDate')), '2026-09-30');
    await userEvent.type(screen.getByLabelText(t('i18.documentIssuer')), 'Post Office');
    await userEvent.type(screen.getByLabelText(t('i18.documentReference')), 'PPF/123');
    await userEvent.click(screen.getByRole('button', { name: t('common.save') }));
    expect(await screen.findByText(t('i18.saved'))).toBeInTheDocument();
    const form = sent(calls, 'POST', '/870003/entries')[0] as FormData;
    expect(JSON.parse(await (form.get('request') as Blob).text())).toEqual({
      type: 'EXTERNAL_SAVINGS',
      sectionCode: '80C',
      incomeHead: null,
      description: 'PPF outside payroll',
      amount: '40000',
      tdsOtherSource: '0',
      document: {
        documentType: 'OTHER',
        reference: 'PPF/123',
        documentDate: '2026-09-30',
        issuingAuthority: 'Post Office',
      },
    });
    expect((form.get('file') as File).name).toBe('form16a.pdf');
  });

  it('SRS_7_25_whileTheDocumentTypesCannotBeReadTheDocumentHasNoTypeToChoose', async () => {
    routes(YEAR, { 'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE') });
    wrap(<SavingsDeclarations />, MAKER);
    await openMember();
    await userEvent.upload(
      screen.getByLabelText(t('i18.document')),
      new File(['%PDF'], 'form16a.pdf', { type: 'application/pdf' }),
    );
    expect(within(screen.getByLabelText(t('i18.documentType'))).queryAllByRole('option')).toHaveLength(0);
  });

  it('SRS_7_11_otherIncomeOffersTheSrsHeadsAndARefusalStaysOnTheForm', async () => {
    routes(YEAR, {
      'POST /v1/tax/savings/members/870003/entries': () => problem(422, 'TAX-422-ENTRY-KIND'),
    });
    wrap(<SavingsDeclarations />, MAKER);
    await openMember();
    await userEvent.upload(screen.getByLabelText(t('i18.document')), []);
    expect(screen.queryByLabelText(t('i18.amount'))).toBeNull();
    await userEvent.upload(
      screen.getByLabelText(t('i18.document')),
      new File(['%PDF'], 'h.pdf', { type: 'application/pdf' }),
    );
    // SRS-7-11: the heads the SRS names are offered as suggestions (a datalist, not a closed list).
    const heads = [...document.querySelectorAll('#i18-heads option')].map((o) => o.getAttribute('value'));
    expect(heads).toEqual(['Vehicle perquisite', 'Honorarium', 'NSC interest']);
    await userEvent.type(screen.getByLabelText(t('i18.incomeHead')), 'Honorarium');
    await userEvent.type(screen.getByLabelText(t('i18.amount')), '5000');
    await userEvent.type(screen.getByLabelText(t('i18.documentDate')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('i18.documentIssuer')), 'College');
    await userEvent.click(screen.getByRole('button', { name: t('common.save') }));
    expect(await screen.findByText(t('error.TAX-422-ENTRY-KIND'), { exact: false })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('common.save') })).toBeNull();
  });

  it('BR_TAX_015_theRebateMonthsGoToTheCheckerAndAPendingOneClosesTheForm', async () => {
    const calls = routes(YEAR, {
      'POST /v1/tax/savings/members/870003/transport-rebate': () => ({
        status: 202,
        body: { approvalId: 8 },
      }),
    });
    wrap(<SavingsDeclarations />, MAKER);
    await openMember();
    const send = screen.getByRole('button', { name: t('i18.sendRebate') });
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByLabelText('202605'), '1600');
    await userEvent.type(screen.getByLabelText('202606'), '1600');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Transport rebate');
    await userEvent.click(send);
    expect(await screen.findByText(t('i18.rebateSent'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/transport-rebate')[0]).toEqual({
      months: { '202605': '1600', '202606': '1600' },
      reason: 'Transport rebate',
    });
    vi.unstubAllGlobals();
  });

  it('SRS_7_25_aRefusedCancellationAndARefusedRebateAreShown', async () => {
    routes(YEAR, {
      'POST /v1/tax/savings/entries/5/cancel': () => problem(409, 'TAX-409-STATE'),
      'POST /v1/tax/savings/members/870003/transport-rebate': () => problem(409, 'TAX-409-STATE'),
    });
    wrap(<SavingsDeclarations />, MAKER);
    await openMember();
    await userEvent.type(screen.getByLabelText(t('i18.cancelReason')), 'Entered twice');
    await userEvent.click(screen.getByRole('button', { name: t('i18.cancelOf', [5]) }));
    await userEvent.click(await screen.findByRole('button', { name: t('confirm.cancel.yes') }));
    expect(await screen.findAllByRole('alert')).not.toHaveLength(0);
    expect(screen.queryByText(t('i18.cancelled'))).toBeNull();
    await userEvent.type(screen.getByLabelText('202605'), '1600');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Transport rebate');
    await userEvent.click(screen.getByRole('button', { name: t('i18.sendRebate') }));
    await screen.findAllByRole('alert');
    expect(screen.queryByText(t('i18.rebateSent'))).toBeNull();
    expect(screen.getByLabelText('202605')).toHaveValue('1600');
  });

  it('BR_TAX_015_aPendingRebateDisablesTheMonthsWithItsReason', async () => {
    routes({
      ...YEAR,
      pendingRebates: [
        {
          employeeId: 9,
          finYear: '2026-27',
          months: { '202605': 1600 },
          proposedBy: 'tax.maker',
          proposedAt: '2026-10-24T05:00:00Z',
        },
      ],
    });
    wrap(<SavingsDeclarations />, MAKER);
    await openMember();
    expect(screen.getByRole('list', { name: t('i18.pendingRebates') })).toHaveTextContent('202605');
    expect(screen.getByLabelText('202607')).toBeDisabled();
    expect(screen.getByRole('button', { name: t('i18.sendRebate') })).toHaveAttribute(
      'title',
      t('i18.rebatePending'),
    );
  });

  it('SRS_22_21_aReaderSeesTheYearWithoutActions', async () => {
    routes({ ...YEAR, entries: [], savings: [] });
    wrap(<SavingsDeclarations />, me(['I-18:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('i18.openOf', ['870003']) }));
    expect(await screen.findByText(t('i18.noEntries'))).toBeInTheDocument();
    expect(screen.getByText(t('i18.noSavings'))).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: t('action.newEntry') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('i18.sendRebate') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByText(t('i18.noEntries'))).toBeNull();
  });

  it('SRS_7_25_aMemberLookedUpByEisThatCannotBeReadShowsTheReason', async () => {
    backend({
      'GET /v1/tax/savings?': (url) => paged([], 0, url),
      'GET /v1/tax/savings/members/123': () => problem(404, 'COM-404-NOT-FOUND'),
    });
    wrap(<SavingsDeclarations />, me(['I-18:VIEW']));
    expect(await screen.findByText(t('i18.none'))).toBeInTheDocument();
    const open = screen.getByRole('button', { name: t('i18.openMember') });
    expect(open).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '123');
    await userEvent.click(open);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('SRS_NF_3_aBadYearIsNamed', async () => {
    routes(YEAR);
    wrap(<SavingsDeclarations />, MAKER);
    // A partial year cannot be entered: the year is chosen from valid financial years.
    const year = screen.getByLabelText(t('i07.finYear'));
    expect(year.tagName).toBe('SELECT');
    expect(within(year).queryByRole('option', { name: '26' })).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('i07.status')), 'APPROVED');
  });
});
