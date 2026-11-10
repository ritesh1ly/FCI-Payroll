import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DeputationAdjustment } from '@/modules/cpf/components/DeputationAdjustment';
import type { DeputationEntry, DeputedMember } from '@/modules/cpf/api/cpfApi';
import { backend, me, paged, problem, t, wrap } from './support/harness';

/**
 * I-09 CPF Adjustment / Recovery Adjustment (plan 07 phase 07g; SRS-6-27 … 6-30): the deputed
 * member with the advance outstanding and the months without a receipt, a remittance sent with its
 * evidence, a refusal kept on screen, the register; nothing to record without I-09 CREATE.
 */

afterEach(() => vi.unstubAllGlobals());

const FILE = {
  id: 12,
  fileName: 'remittance.pdf',
  uploadedBy: 'cpf.manager.north@fci-demo.test',
  uploadedAt: '2026-10-01T05:00:00Z',
};

const ENTRY: DeputationEntry = {
  id: 3,
  eis: '871901',
  organisation: 'Demo Public Sector Undertaking One',
  type: 'RECOVERY_INSTALMENT',
  sanctionId: 5,
  payrollYymm: '202608',
  instalment: 5000,
  employee: 0,
  employer: 0,
  vpf: 0,
  outstandingBefore: 60000,
  balanceAfter: 55000,
  status: 'APPROVED',
  approvalId: 81,
  evidenceId: 12,
  evidence: [FILE],
};

const TWO_FILES = [
  ...ENTRY.evidence,
  {
    id: 13,
    fileName: 'wrong.pdf',
    uploadedBy: 'cpf.manager.north@fci-demo.test',
    uploadedAt: '2026-10-02T05:00:00Z',
  },
];

const MEMBER: DeputedMember = {
  eis: '871901',
  name: 'Kiran Bose',
  deputationFrom: '2026-07-01',
  deputationTo: null,
  advances: [{ sanctionId: 5, sanctionNumber: 'ADV-5', sanctioned: 60000, outstanding: 55000 }],
  monthsWithoutReceipt: ['202607', '202608'],
  entries: [ENTRY],
};

const MANAGER = me(['I-09:VIEW', 'I-09:CREATE'], { role: 'CPF_MANAGER' });

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/cpf/deputation-entries': (url) => paged([ENTRY], 1, url),
    'GET /v1/cpf/deputations/871901': () => MEMBER,
    'GET /v1/cpf/borrowing-organisations': () => [
      { id: 1, code: 'DEMO-PSU-1', name: 'Demo PSU One', contact: null, active: true },
      { id: 2, code: 'OLD', name: 'Old Org', contact: null, active: false },
    ],
    'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
    ...over,
  });
}

async function open() {
  await userEvent.type(screen.getByLabelText(t('i09.eis')), '871901');
  await userEvent.click(screen.getByRole('button', { name: t('i09.find') }));
  await screen.findByText('Kiran Bose (871901)');
}

async function evidence() {
  await screen.findByRole('option', { name: 'Other document' });
  await userEvent.selectOptions(screen.getByLabelText(t('ord.type')), 'OTHER');
  await userEvent.type(screen.getByLabelText(t('ord.date')), '2026-10-01');
  await userEvent.type(screen.getByLabelText(t('ord.authority')), 'Demo PSU accounts');
  await userEvent.upload(
    screen.getByLabelText(t('ord.file')),
    new File(['%PDF-1.7'], 'remittance.pdf', { type: 'application/pdf' }),
  );
}

describe('I-09 deputation adjustment', () => {
  it('SRS l.2167, SRS-G-2: an instalment row names its sanctioned advance and a rejected entry the AGM reason', async () => {
    routes({
      'GET /v1/cpf/deputation-entries': (url) =>
        paged(
          [
            { ...ENTRY, sanctionNumber: 'ADV-5' },
            { ...ENTRY, id: 9, status: 'REJECTED', decisionReason: 'Remittance advice unreadable' },
          ],
          2,
          url,
        ),
    });
    wrap(<DeputationAdjustment />, MANAGER);
    const register = await screen.findByRole('table', { name: t('i09.entries') });
    expect(
      within(register)
        .getAllByRole('columnheader')
        .map((h) => h.textContent ?? ''),
    ).toEqual(expect.arrayContaining([expect.stringContaining(t('i09.advanceRef'))]));
    expect(register).toHaveTextContent('ADV-5');
    expect(register).toHaveTextContent(t('i09.decisionReason', ['Remittance advice unreadable']));
  });

  it('SRS_6_28_theMemberShowsTheDeputationTheOutstandingAndTheMissingMonths', async () => {
    routes();
    wrap(<DeputationAdjustment />, MANAGER);
    expect(screen.getByText(t('i09.chooseMember'))).toBeInTheDocument();
    await open();
    expect(screen.getByLabelText(t('i09.deputationHead'))).toHaveTextContent('202607, 202608');
    const advances = screen.getByRole('table', { name: t('i09.advances') });
    expect(advances).toHaveTextContent('ADV-5');
    expect(advances).toHaveTextContent('55,000');
    const registers = await screen.findAllByRole('table', { name: t('i09.entries') });
    expect(registers[0]).toHaveTextContent(t('i09.status.APPROVED'));
    await userEvent.click(
      within(registers[0] as HTMLElement).getByRole('button', { name: t('i09.evidenceCount', [1]) }),
    );
    const files = screen.getByRole('table', { name: t('i09.evidence') });
    expect(within(files).getByRole('link', { name: 'remittance.pdf' })).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/cpf/deputation-entries/3/evidence/12'),
    );
    // SRS-G-31: the only file is never cancelled (the evidence is mandatory).
    const only = within(files).getByRole('button', { name: t('i09.evidenceCancel', ['remittance.pdf']) });
    expect(only).toBeDisabled();
    expect(only).toHaveAttribute('title', t('i09.evidenceLast'));
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('table', { name: t('i09.evidence') })).toBeNull();
  });

  it('SRS_G_31_aWrongFileIsCancelledWithItsReasonAndAnotherIsAddedAfterSubmission', async () => {
    const calls = routes({
      'GET /v1/cpf/deputations/871901': () => ({ ...MEMBER, entries: [{ ...ENTRY, evidence: TWO_FILES }] }),
      'POST /v1/cpf/deputation-entries/3/evidence/13/cancel': () => ENTRY.evidence,
      'POST /v1/cpf/deputation-entries/3/evidence': () => ({ status: 201, body: TWO_FILES }),
    });
    wrap(<DeputationAdjustment />, MANAGER);
    await open();
    const registers = await screen.findAllByRole('table', { name: t('i09.entries') });
    await userEvent.click(
      within(registers[0] as HTMLElement).getByRole('button', { name: t('i09.evidenceCount', [2]) }),
    );
    const wrong = screen.getByRole('button', { name: t('i09.evidenceCancel', ['wrong.pdf']) });
    expect(wrong).toBeDisabled();
    expect(wrong).toHaveAttribute('title', t('i09.evidenceReasonNeeded'));
    await userEvent.type(screen.getByLabelText(t('i09.evidenceReason')), ' Wrong advice ');
    await userEvent.click(wrong);
    await userEvent.click(await screen.findByRole('button', { name: t('confirm.cancel.yes') }));
    expect(await screen.findByText(t('i09.evidenceCancelled'))).toBeInTheDocument();
    const cancel = calls.find((c) => c.url.includes('/evidence/13/cancel'));
    expect(cancel?.body).toEqual({ reason: 'Wrong advice' });
    // Added after submission with its order particulars.
    const add = screen.getByRole('button', { name: t('i09.evidenceAdd') });
    expect(add).toBeDisabled();
    const panel = screen.getByRole('table', { name: t('i09.evidence') }).closest('.card') as HTMLElement;
    await within(panel).findByRole('option', { name: 'Other document' });
    await userEvent.selectOptions(within(panel).getByLabelText(t('ord.type')), 'OTHER');
    await userEvent.type(within(panel).getByLabelText(t('ord.date')), '2026-10-01');
    await userEvent.type(within(panel).getByLabelText(t('ord.authority')), 'Demo PSU accounts');
    await userEvent.upload(
      within(panel).getByLabelText(t('ord.file')),
      new File(['%PDF-1.7'], 'right.pdf', { type: 'application/pdf' }),
    );
    expect(add).toBeEnabled();
    await userEvent.click(add);
    expect(await screen.findByText(t('i09.evidenceAdded'))).toBeInTheDocument();
    const form = calls.find((c) => c.method === 'POST' && c.url.endsWith('/deputation-entries/3/evidence'))
      ?.body as FormData;
    expect((form.get('file') as File).name).toBe('right.pdf');
    expect(form.get('issuingAuthority')).toBe('Demo PSU accounts');
    expect(form.has('reference')).toBe(false);
  });

  it('SRS_G_31_fiveFilesAreTheMostAndAViewerNeitherAddsNorCancels', async () => {
    const five = [12, 13, 14, 15, 16].map((id) => ({ ...FILE, id, fileName: `f${id}.pdf` }));
    routes({
      'GET /v1/cpf/deputations/871901': () => ({ ...MEMBER, entries: [{ ...ENTRY, evidence: five }] }),
    });
    const view = wrap(<DeputationAdjustment />, MANAGER);
    await open();
    let registers = await screen.findAllByRole('table', { name: t('i09.entries') });
    await userEvent.click(
      within(registers[0] as HTMLElement).getByRole('button', { name: t('i09.evidenceCount', [5]) }),
    );
    const add = screen.getByRole('button', { name: t('i09.evidenceAdd') });
    expect(add).toBeDisabled();
    expect(add).toHaveAttribute('title', t('i09.evidenceFull', [5]));
    view.unmount();
    routes({
      'GET /v1/cpf/deputations/871901': () => ({ ...MEMBER, entries: [{ ...ENTRY, evidence: five }] }),
    });
    wrap(<DeputationAdjustment />, me(['I-09:VIEW'], { role: 'AGM_CPF' }));
    await open();
    registers = await screen.findAllByRole('table', { name: t('i09.entries') });
    await userEvent.click(
      within(registers[0] as HTMLElement).getByRole('button', { name: t('i09.evidenceCount', [5]) }),
    );
    expect(screen.queryByRole('button', { name: t('i09.evidenceAdd') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('i09.evidenceCancel', ['f12.pdf']) })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('table', { name: t('i09.evidence') })).toBeNull();
  });

  it('SRS_6_28_anAdvanceWithoutASanctionNumberShowsItsIdAndNoEntriesSaySo', async () => {
    routes({
      'GET /v1/cpf/deputations/871901': () => ({
        ...MEMBER,
        advances: [{ sanctionId: 6, sanctionNumber: null, sanctioned: 30000, outstanding: 30000 }],
        entries: [],
      }),
    });
    wrap(<DeputationAdjustment />, MANAGER);
    await open();
    expect(screen.getByRole('cell', { name: '6' })).toBeInTheDocument();
    expect(screen.getByText(t('i09.none'))).toBeInTheDocument();
  });

  it('SRS_6_30_aReceiptIsSentWithItsPartsAndEvidence', async () => {
    const calls = routes({ 'POST /v1/cpf/deputation-entries': () => ({ status: 201, body: ENTRY }) });
    wrap(<DeputationAdjustment />, MANAGER);
    await open();
    const submit = screen.getByRole('button', { name: t('i09.submit') });
    expect(submit).toBeDisabled();
    await screen.findByRole('option', { name: 'Demo PSU One (DEMO-PSU-1)' });
    expect(screen.queryByRole('option', { name: 'Old Org (OLD)' })).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('i09.organisation')), '1');
    fireEvent.change(screen.getByLabelText(t('i09.month')), { target: { value: '2026-08' } });
    await userEvent.type(screen.getByLabelText(t('i09.employee')), '1200');
    await userEvent.type(screen.getByLabelText(t('i09.employer')), '1200');
    await evidence();
    expect(submit).toBeEnabled();
    await userEvent.click(submit);
    expect(await screen.findByText(t('i09.submitted'))).toBeInTheDocument();
    const form = calls.find((c) => c.method === 'POST' && c.url.includes('deputation-entries'))
      ?.body as FormData;
    expect(form.get('type')).toBe('CONTRIBUTION_RECEIPT');
    expect(form.get('eis')).toBe('871901');
    expect(form.get('employee')).toBe('1200');
    expect(form.has('vpf')).toBe(false);
    expect((form.get('file') as File).name).toBe('remittance.pdf');
  });

  it('SRS_6_29_anInstalmentAboveTheOutstandingIsHeldAndAServerRefusalShown', async () => {
    routes({
      'POST /v1/cpf/deputation-entries': () => problem(422, 'CPF-422-OVER-REMITTANCE', ['55000']),
    });
    wrap(<DeputationAdjustment />, MANAGER);
    await open();
    await userEvent.selectOptions(screen.getByLabelText(t('i09.type')), 'RECOVERY_INSTALMENT');
    await screen.findByRole('option', { name: 'Demo PSU One (DEMO-PSU-1)' });
    await userEvent.selectOptions(screen.getByLabelText(t('i09.organisation')), '1');
    fireEvent.change(screen.getByLabelText(t('i09.month')), { target: { value: '2026-09' } });
    await userEvent.selectOptions(screen.getByLabelText(t('i09.advance')), '5');
    await userEvent.type(screen.getByLabelText(t('i09.instalment')), '55000.01');
    await evidence();
    const submit = screen.getByRole('button', { name: t('i09.submit') });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('title', t('i09.bad.over'));
    const instalment = screen.getByLabelText(t('i09.instalment'));
    await userEvent.clear(instalment);
    await userEvent.type(instalment, '5000');
    await userEvent.click(submit);
    expect(await screen.findByText(t('error.CPF-422-OVER-REMITTANCE', ['55000']))).toBeInTheDocument();
  });

  it('SRS_6_27_aBorrowingOrganisationIsAddedAndAViewerRecordsNothing', async () => {
    const calls = routes({
      'POST /v1/cpf/borrowing-organisations': () => ({
        status: 201,
        body: { id: 3, code: 'NEW-ORG', name: 'New Org', contact: null, active: true },
      }),
    });
    const view = wrap(<DeputationAdjustment />, MANAGER);
    const add = screen.getByRole('button', { name: t('i09.newOrg') });
    expect(add).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('i09.orgCode')), 'new-org');
    await userEvent.type(screen.getByLabelText(t('i09.orgName')), 'New Org');
    await userEvent.click(add);
    expect(await screen.findByText(t('i09.orgAdded'))).toBeInTheDocument();
    expect(calls.find((c) => c.url.includes('borrowing-organisations') && c.method === 'POST')?.body).toEqual(
      {
        code: 'NEW-ORG',
        name: 'New Org',
        contact: null,
      },
    );
    view.unmount();

    routes();
    wrap(<DeputationAdjustment />, me(['I-09:VIEW']));
    await open();
    expect(screen.queryByRole('button', { name: t('i09.submit') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('i09.newOrg') })).toBeNull();
  });
  it('SRS_6_28_anEndedDeputationWithoutAdvancesShowsTheReceiptsAndAFailedOrganisationIsShown', async () => {
    routes({
      'GET /v1/cpf/deputations/871901': () => ({
        ...MEMBER,
        deputationTo: '2027-06-30',
        advances: [],
        monthsWithoutReceipt: [],
        entries: [
          {
            ...ENTRY,
            id: 4,
            type: 'CONTRIBUTION_RECEIPT',
            sanctionId: null,
            instalment: 0,
            employee: 1200,
            employer: 1200,
            vpf: 100,
            balanceAfter: null,
            evidenceId: null,
            evidence: [],
          },
        ],
      }),
      'POST /v1/cpf/borrowing-organisations': () => problem(409, 'CPF-409-DUPLICATE', ['NEW-ORG']),
    });
    wrap(<DeputationAdjustment />, MANAGER);
    await open();
    expect(screen.getByLabelText(t('i09.deputationHead'))).toHaveTextContent(t('i09.none'));
    const registers = await screen.findAllByRole('table', { name: t('i09.entries') });
    expect(registers[0]).toHaveTextContent('1,200');
    await userEvent.type(screen.getByLabelText(t('i09.orgCode')), 'new-org');
    await userEvent.type(screen.getByLabelText(t('i09.orgName')), 'New Org');
    await userEvent.type(screen.getByLabelText(t('i09.orgContact')), 'accounts@psu.example');
    await userEvent.click(screen.getByRole('button', { name: t('i09.newOrg') }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
