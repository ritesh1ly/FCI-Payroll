import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CpfSanctions, CpfWithdrawals } from '@/modules/cpf';
import type { PurposeOption, Sanction } from '@/modules/cpf/api/cpfApi';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * I-04 CPF sanctions and the E-01 withdrawal requests (SRS-6-4, UC-05; SRS-6-14, SRS-6-26,
 * SRS-12-9, BR-CPF-039): the register with its stage filter, the tier actions of the unit and the
 * CPF Division, the member's request with the checklist documents, withdrawal and resubmission.
 */

afterEach(() => vi.unstubAllGlobals());

const sanction = (over: Partial<Sanction> = {}): Sanction => ({
  id: 41,
  eis: '104882',
  memberName: 'Rakesh Sharma',
  type: 'ADVANCE',
  purposeCode: 'MARRIAGE',
  purposeName: 'Marriage',
  requested: 50000,
  ceiling: null,
  sanctioned: null,
  capped: false,
  stage: 'UNIT_VERIFICATION',
  sanctionNumber: null,
  sanctionDate: null,
  propertyAddress: null,
  propertyDescription: null,
  approvalId: null,
  cancelReason: null,
  tiers: [],
  uan: '100000104882',
  aadhaarMasked: 'XXXX-XXXX-4886',
  noCasteDisputeRequired: false,
  documents: [
    {
      id: 7,
      documentTypeCode: 'CERTIFICATE',
      documentTypeName: 'Certificate',
      referenceNumber: 'CERT/1',
      orderDate: '2026-09-01',
      issuingAuthority: 'Registrar',
      fileName: 'card.pdf',
    },
  ],
  ...over,
});

const SANCTIONED = sanction({
  id: 40,
  stage: 'SANCTIONED',
  ceiling: 6000,
  sanctioned: 6000,
  capped: true,
  sanctionNumber: '10/2026/NA11/00001',
  sanctionDate: '2026-10-20',
  tiers: [
    {
      pass: 1,
      tier: 1,
      tierCode: 'UNIT_CPF_ADMIN',
      action: 'FORWARDED',
      actedBy: 'cpf.unit.delhi',
      actedAt: '2026-10-18T05:00:00Z',
      remark: null,
      ceiling: null,
      capped: null,
    },
    {
      pass: 1,
      tier: 2,
      tierCode: 'ZONAL_HQ_CPF_DIVISION',
      action: 'RECOMMENDED',
      actedBy: 'cpf.north',
      actedAt: '2026-10-19T05:00:00Z',
      remark: 'Capped',
      ceiling: 6000,
      capped: 6000,
    },
    {
      pass: 1,
      tier: 3,
      tierCode: 'COMPETENT_AUTHORITY',
      action: 'SANCTIONED',
      actedBy: 'cpf.sanction.north',
      actedAt: '2026-10-20T05:00:00Z',
      remark: null,
      ceiling: null,
      capped: null,
    },
  ],
});

const UNIT = me(['I-04:VIEW', 'I-04:EDIT'], {
  role: 'UNIT_CPF_ADMIN',
  location: { code: 'DH001', label: 'DO — Delhi HQ (DH001)' },
});
const DIVISION = me(['I-04:VIEW', 'I-04:SUBMIT'], {
  role: 'CPF_DIVISION',
  location: { code: 'NA11', label: 'ZO — North (NA11)' },
});

describe('I-04 CPF sanctions', () => {
  it('SRS_6_55_theRegisterListsEachRequestWithItsStageAndNumberAndFiltersOnTheServer', async () => {
    const calls = backend({
      'GET /v1/cpf/sanctions/40': () => SANCTIONED,
      'GET /v1/cpf/sanctions?': (url) => paged([SANCTIONED, sanction()], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    const row = await screen.findByText('10/2026/NA11/00001');
    expect(row.closest('tr')?.textContent).toContain(t('i04.capped'));
    expect(screen.getByText(t('i04.stage.UNIT_VERIFICATION'), { selector: '.pill' })).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('i04.stage')), 'SANCTIONED');
    await waitFor(() => expect(calls.some((c) => c.url.includes('stage=SANCTIONED'))).toBe(true));
    // Open the sanctioned one: three tiers, no action left on I-04.
    await userEvent.click(screen.getByRole('button', { name: t('i04.openOf', [40]) }));
    const tiers = await screen.findByRole('table', { name: t('i04.tiers') });
    expect(within(tiers).getAllByRole('row')).toHaveLength(4);
    expect(within(tiers).getByText(t('i04.tierAction.SANCTIONED'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('i04.forward') })).toBeNull();
    expect(screen.getByRole('link', { name: 'card.pdf' }).getAttribute('href')).toContain(
      '/v1/cpf/sanctions/40/documents/7',
    );
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('table', { name: t('i04.tiers') })).toBeNull();
  });

  it('SRS l.2278-2279, SRS-G-37: the stage filter offers Payment Released; the detail counts the earlier withdrawals and a withdrawn one shows its reason', async () => {
    const withdrawn = sanction({ id: 43, stage: 'CANCELLED', cancelReason: 'Raised twice by mistake' });
    const counted = sanction({
      earlierWithdrawals: [
        {
          id: 30,
          type: 'PART_FINAL',
          purposeCode: 'PLOT_PURCHASE',
          stage: 'SANCTIONED',
          sanctionNumber: '04/2025/NA11/00002',
          sanctionDate: '2025-04-10',
        },
      ],
    });
    const calls = backend({
      'GET /v1/cpf/sanctions/41': () => counted,
      'GET /v1/cpf/sanctions/43': () => withdrawn,
      'GET /v1/cpf/sanctions?': (url) => paged([counted, withdrawn], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    await screen.findByRole('button', { name: t('i04.openOf', [41]) });
    await userEvent.selectOptions(screen.getByLabelText(t('i04.stage')), 'PAID');
    await waitFor(() => expect(calls.some((c) => c.url.includes('stage=PAID'))).toBe(true));
    await userEvent.click(screen.getByRole('button', { name: t('i04.openOf', [41]) }));
    const earlier = await screen.findByRole('list', { name: t('i04.withdrawalCount') });
    expect(earlier).toHaveTextContent('04/2025/NA11/00002');
    expect(earlier).toHaveTextContent('PLOT_PURCHASE');
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: t('i04.openOf', [43]) }));
    expect(await screen.findByText('Raised twice by mistake')).toBeTruthy();
  });

  it('UI audit S46: a refused return keeps the reason dialog open with the refusal and the typed reason', async () => {
    backend({
      'GET /v1/cpf/sanctions/41': () => sanction(),
      'GET /v1/cpf/sanctions?': (url) => paged([sanction()], undefined, url),
      'POST /v1/cpf/sanctions/41/verify': () => problem(409, 'CPF-409-STAGE'),
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    await userEvent.click(await screen.findByRole('button', { name: t('i04.returnToMember') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Certificate unreadable');
    await userEvent.click(within(dialog).getByRole('button', { name: t('i04.return') }));
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toBeTruthy();
    expect(within(screen.getByRole('dialog')).getByRole('textbox')).toHaveValue('Certificate unreadable');
  });

  it('SRS_6_4_theUnitForwardsOrReturnsWithAReasonAndARefusalIsShown', async () => {
    let answer: unknown = problem(422, 'CPF-422-ELIGIBILITY-DATA', ['uan_number']);
    const calls = backend({
      'GET /v1/cpf/sanctions/41': () => sanction(),
      'GET /v1/cpf/sanctions?': (url) => paged([sanction()], undefined, url),
      'POST /v1/cpf/sanctions/41/verify': () => answer,
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    // Tier 1 verifies the UAN and the KYC; the Aadhaar shows its last four digits only (SRS-6-51).
    expect(await screen.findByText('100000104882')).toBeTruthy();
    expect(screen.getByText('XXXX-XXXX-4886')).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/9999\s?0010\s?4886/);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.forward') }));
    expect(await screen.findByText(t('error.CPF-422-ELIGIBILITY-DATA', ['uan_number']))).toBeTruthy();
    // A return asks for the reason.
    answer = sanction({ stage: 'RETURNED' });
    await userEvent.click(screen.getByRole('button', { name: t('i04.returnToMember') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Certificate unreadable');
    await userEvent.click(within(dialog).getByRole('button', { name: t('i04.return') }));
    expect(await screen.findByText(t('i04.done', [41, t('i04.stage.RETURNED')]))).toBeTruthy();
    expect(sent(calls, 'POST', '/verify').at(-1)).toEqual({
      decision: 'RETURN',
      remark: 'Certificate unreadable',
    });
    // A cancelled return sends nothing.
    await userEvent.click(screen.getByRole('button', { name: t('i04.returnToMember') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(sent(calls, 'POST', '/verify')).toHaveLength(2);
  });

  it('SRS_6_14_theUnitAttachesTheNoCasteDisputeCertificateThePurposeNeeds', async () => {
    const needs = sanction({ noCasteDisputeRequired: true });
    let current = needs;
    const calls = backend({
      'GET /v1/cpf/sanctions/41': () => current,
      'GET /v1/cpf/sanctions?': (url) => paged([needs], undefined, url),
      'POST /v1/cpf/sanctions/41/documents': () => {
        current = sanction({
          noCasteDisputeRequired: true,
          documents: [
            ...needs.documents,
            {
              id: 8,
              documentTypeCode: 'NO_CASTE_DISPUTE',
              documentTypeName: 'No Caste Dispute Certificate',
              referenceNumber: null,
              orderDate: '2026-09-01',
              issuingAuthority: 'Tehsildar',
              fileName: 'ncd.pdf',
            },
          ],
        });
        return current;
      },
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    const form = await screen.findByRole('group', { name: t('i04.ncd.title') });
    const attach = within(form).getByRole('button', { name: t('i04.ncd.attach') });
    expect(attach).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(t('i04.ncd.date')), '2026-09-01');
    await userEvent.type(within(form).getByLabelText(t('i04.ncd.authority')), 'Tehsildar');
    await userEvent.upload(
      within(form).getByLabelText(t('i04.ncd.file')),
      new File(['%PDF-1.7'], 'ncd.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(attach);
    expect(await screen.findByText(t('i04.ncd.attached'))).toBeTruthy();
    expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/v1/cpf/sanctions/41/documents'))).toBe(
      true,
    );
    // Attached: the form gives way to the document in the list.
    await waitFor(() => expect(screen.queryByRole('group', { name: t('i04.ncd.title') })).toBeNull());
    expect(screen.getByText('ncd.pdf')).toBeTruthy();
  });
  it('SRS_6_14_noCertificateFormWhereThePurposeDoesNotNeedIt', async () => {
    backend({
      'GET /v1/cpf/sanctions/41': () => sanction(),
      'GET /v1/cpf/sanctions?': (url) => paged([sanction()], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    await screen.findByRole('button', { name: t('i04.forward') });
    expect(screen.queryByRole('group', { name: t('i04.ncd.title') })).toBeNull();
  });
  it('SRS_6_4_aMemberWithoutUanOrKycOnRecordIsShownAsNotOnRecord', async () => {
    const blank = sanction({ uan: null, aadhaarMasked: null });
    backend({
      'GET /v1/cpf/sanctions/41': () => blank,
      'GET /v1/cpf/sanctions?': (url) => paged([blank], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    expect(await screen.findAllByText(t('i04.notOnRecord'))).toHaveLength(2);
  });

  it('SRS_6_26_theCpfDivisionRecommendsAtTheCappedAmountAndTheUnitCannotActAtTier2', async () => {
    const waiting = sanction({ stage: 'ZONAL_HQ_VALIDATION' });
    const calls = backend({
      'GET /v1/cpf/sanctions/41': () => waiting,
      'GET /v1/cpf/sanctions?': (url) => paged([waiting], undefined, url),
      'POST /v1/cpf/sanctions/41/recommend': () => ({
        status: 202,
        body: sanction({
          stage: 'COMPETENT_AUTHORITY',
          ceiling: 6000,
          sanctioned: 6000,
          capped: true,
          approvalId: 9,
        }),
      }),
    });
    wrap(<CpfSanctions />, DIVISION);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    await userEvent.click(await screen.findByRole('button', { name: t('i04.recommend') }));
    // The recommendation asks for its reason (the I04_SUBMIT approval's) before it is sent.
    const dialog = await screen.findByRole('dialog');
    expect(sent(calls, 'POST', '/recommend')).toHaveLength(0);
    await userEvent.type(within(dialog).getByRole('textbox'), 'Eligible within the limit');
    await userEvent.click(within(dialog).getByRole('button', { name: t('i04.recommend') }));
    expect(await screen.findByText(t('i04.done', [41, t('i04.stage.COMPETENT_AUTHORITY')]))).toBeTruthy();
    expect(sent(calls, 'POST', '/recommend').at(-1)).toMatchObject({
      decision: 'FORWARD',
      remark: 'Eligible within the limit',
    });
  });

  it('SRS_6_4_theCpfDivisionRejectsWithTheReasonTheMemberSeesAndTheUnitHasNoRejection (US-GAP-164)', async () => {
    const waiting = sanction({ stage: 'ZONAL_HQ_VALIDATION' });
    const calls = backend({
      'GET /v1/cpf/sanctions/41': () => waiting,
      'GET /v1/cpf/sanctions?': (url) => paged([waiting], undefined, url),
      'POST /v1/cpf/sanctions/41/recommend': () => ({ status: 202, body: sanction({ stage: 'REJECTED' }) }),
    });
    wrap(<CpfSanctions />, DIVISION);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    await userEvent.click(await screen.findByRole('button', { name: t('i04.reject') }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('i04.rejectTitle', [41]))).toBeTruthy();
    expect(sent(calls, 'POST', '/recommend')).toHaveLength(0);
    await userEvent.type(within(dialog).getByRole('textbox'), 'Fewer than 5 years of membership');
    await userEvent.click(within(dialog).getByRole('button', { name: t('i04.reject') }));
    expect(await screen.findByText(t('i04.done', [41, t('i04.stage.REJECTED')]))).toBeTruthy();
    expect(sent(calls, 'POST', '/recommend').at(-1)).toMatchObject({
      decision: 'REJECT',
      remark: 'Fewer than 5 years of membership',
    });
  });

  it('SRS_6_4_theUnitReturnsButNeverRejects (US-GAP-164)', async () => {
    backend({
      'GET /v1/cpf/sanctions/41': () => sanction(),
      'GET /v1/cpf/sanctions?': (url) => paged([sanction()], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    await screen.findByRole('button', { name: t('i04.returnToMember') });
    expect(screen.queryByRole('button', { name: t('i04.reject') })).toBeNull();
  });

  it('SRS_6_4_aRequestAtTheCompetentAuthorityShowsWhereItIsDecidedAndABlockedPaymentWhy', async () => {
    const at3 = sanction({ stage: 'COMPETENT_AUTHORITY', sanctioned: 6000, ceiling: 6000 });
    backend({
      'GET /v1/cpf/sanctions/41': () => at3,
      'GET /v1/cpf/sanctions/42': () =>
        sanction({
          id: 42,
          stage: 'PAYMENT_BLOCKED',
          documents: [],
          propertyAddress: 'Plot 4',
          propertyDescription: 'Two rooms',
        }),
      'GET /v1/cpf/sanctions?': (url) =>
        paged([at3, sanction({ id: 42, stage: 'PAYMENT_BLOCKED' })], undefined, url),
    });
    wrap(<CpfSanctions />, DIVISION);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    expect(await screen.findByText(t('i04.tier3Hint'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('i04.recommend') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('i04.openOf', [42]) }));
    expect(await screen.findByText(t('i04.blockedHint'))).toBeTruthy();
    expect(screen.getByText(t('i04.noDocuments'))).toBeTruthy();
    expect(screen.getByText(/Plot 4 — Two rooms/)).toBeTruthy();
  });
});

const PURPOSES: PurposeOption[] = [
  {
    code: 'MARRIAGE',
    name: 'Marriage',
    category: 'ADVANCE',
    propertyDetails: false,
    documents: [{ documentType: 'CERTIFICATE', mandatory: true }],
  },
  { code: 'HOUSE', name: 'House', category: 'PART_FINAL', propertyDetails: true, documents: [] },
];
const TYPES = [{ code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false }];
const MEMBER = me(['E-01:VIEW', 'E-01:CREATE'], { role: 'EMPLOYEE_ESEVA' });

describe('E-01 CPF withdrawal requests', () => {
  it('SRS_6_14_theRequestNeedsItsChecklistDocumentAndGoesWithItsParticulars', async () => {
    const calls = backend({
      'GET /v1/cpf/sanctions/purposes': () => PURPOSES,
      'GET /v1/cpf/sanctions/mine': () => [],
      'GET /v1/document-types': () => TYPES,
      'POST /v1/cpf/sanctions': () => ({ status: 201, body: sanction() }),
    });
    wrap(<CpfWithdrawals />, MEMBER);
    expect(await screen.findByText(t('e01s.none'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('e01s.new') }));
    const submit = screen.getByRole('button', { name: t('e01s.submit') });
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(t('e01s.problem.purpose'))).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('i04.purpose')), 'MARRIAGE');
    await userEvent.type(screen.getByLabelText(t('i04.requested')), '50000');
    expect(screen.getByText(t('e01s.problem.documents', ['CERTIFICATE']))).toBeTruthy();
    await userEvent.upload(
      screen.getByLabelText(t('ord.file')),
      new File(['%PDF-1.7'], 'card.pdf', { type: 'application/pdf' }),
    );
    await userEvent.type(screen.getByLabelText(t('ord.date')), '2026-09-01');
    await userEvent.type(screen.getByLabelText(t('ord.authority')), 'Registrar, Delhi');
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false));
    await userEvent.click(submit);
    expect(await screen.findByText(t('e01s.submitted', [41]))).toBeTruthy();
    const form = sent(calls, 'POST', '/v1/cpf/sanctions')[0] as FormData;
    // One JSON part, so the comma in the authority stays one value.
    expect(JSON.parse(await (form.get('request') as Blob).text())).toEqual({
      purposeCode: 'MARRIAGE',
      amount: '50000',
      propertyAddress: null,
      propertyDescription: null,
      documents: [
        {
          documentType: 'CERTIFICATE',
          reference: expect.any(String),
          documentDate: '2026-09-01',
          issuingAuthority: 'Registrar, Delhi',
        },
      ],
    });
    expect((form.get('file') as File).name).toBe('card.pdf');
  });

  it('BR_CPF_039_aHousePurposeAsksForThePropertyAndCancelClearsTheForm', async () => {
    backend({
      'GET /v1/cpf/sanctions/purposes': () => PURPOSES,
      'GET /v1/cpf/sanctions/mine': () => [],
      'GET /v1/document-types': () => TYPES,
    });
    wrap(<CpfWithdrawals />, MEMBER);
    await userEvent.click(await screen.findByRole('button', { name: t('e01s.new') }));
    await userEvent.selectOptions(screen.getByLabelText(t('i04.purpose')), 'HOUSE');
    await userEvent.type(screen.getByLabelText(t('i04.requested')), '90000');
    expect(screen.getByText(t('e01s.problem.property'))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('e01s.address')), 'Plot 4');
    await userEvent.type(screen.getByLabelText(t('e01s.description')), 'Two rooms');
    expect(screen.queryByText(t('e01s.problem.property'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('i04.purpose'))).toBeNull();
  });

  it('SRS_12_9_theMemberWithdrawsARequestAndResubmitsAReturnedOne', async () => {
    const returned = sanction({
      id: 43,
      stage: 'RETURNED',
      purposeName: 'House',
      propertyAddress: 'Plot 4',
      propertyDescription: 'Two rooms',
      tiers: [
        {
          pass: 1,
          tier: 1,
          tierCode: 'UNIT_CPF_ADMIN',
          action: 'RETURNED',
          actedBy: 'cpf.unit.delhi',
          actedAt: '2026-10-18T05:00:00Z',
          remark: 'Sale deed unreadable',
          ceiling: null,
          capped: null,
        },
      ],
    });
    const calls = backend({
      'GET /v1/cpf/sanctions/purposes': () => PURPOSES,
      'GET /v1/cpf/sanctions/mine': () => [sanction(), returned, SANCTIONED],
      'GET /v1/document-types': () => TYPES,
      'POST /v1/cpf/sanctions/41/withdraw': () => sanction({ stage: 'CANCELLED' }),
      'POST /v1/cpf/sanctions/43/resubmit': () => sanction({ id: 43 }),
    });
    wrap(<CpfWithdrawals />, MEMBER);
    const table = await screen.findByRole('table', { name: t('e01s.mine') });
    expect(within(table).getByText('Sale deed unreadable')).toBeTruthy();
    // A sanctioned request offers neither withdrawal nor resubmission.
    const sanctionedRow = within(table).getByText('10/2026/NA11/00001').closest('tr') as HTMLElement;
    expect(within(sanctionedRow).queryByRole('button')).toBeNull();
    const first = document.querySelector('tr[data-reference="41"]') as HTMLElement;
    await userEvent.click(within(first).getByRole('button', { name: t('e01s.withdraw') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'No longer needed');
    await userEvent.click(within(dialog).getByRole('button', { name: t('e01s.withdraw') }));
    expect(await screen.findByText(t('e01s.withdrawn', [41]))).toBeTruthy();
    expect(sent(calls, 'POST', '/withdraw')[0]).toEqual({ reason: 'No longer needed' });
    // Resubmit the returned one with a corrected amount.
    const back = document.querySelector('tr[data-reference="43"]') as HTMLElement;
    await userEvent.click(within(back).getByRole('button', { name: t('e01s.resubmit') }));
    const amountField = screen.getByLabelText(t('i04.requested'));
    await userEvent.clear(amountField);
    await userEvent.type(amountField, '85000');
    await userEvent.click(screen.getAllByRole('button', { name: t('e01s.resubmit') }).at(-1) as HTMLElement);
    expect(await screen.findByText(t('e01s.resubmitted', [43]))).toBeTruthy();
    expect(sent(calls, 'POST', '/resubmit')[0]).toEqual({
      amount: '85000',
      propertyAddress: 'Plot 4',
      propertyDescription: 'Two rooms',
    });
  });

  it('SRS_6_26_theMemberSeesTheCappedAmountAndMayWithdrawAndSeesARejectionReason (US-GAP-164, US-GAP-166)', async () => {
    const capped = sanction({
      id: 44,
      stage: 'COMPETENT_AUTHORITY',
      ceiling: 6000,
      sanctioned: 6000,
      capped: true,
    });
    const rejected = sanction({
      id: 45,
      stage: 'REJECTED',
      tiers: [
        {
          pass: 1,
          tier: 2,
          tierCode: 'ZONAL_HQ_CPF_DIVISION',
          action: 'REJECTED',
          actedBy: 'cpf.north',
          actedAt: '2026-10-19T05:00:00Z',
          remark: 'Fewer than 5 years of membership',
          ceiling: null,
          capped: null,
        },
      ],
    });
    backend({
      'GET /v1/cpf/sanctions/purposes': () => PURPOSES,
      'GET /v1/cpf/sanctions/mine': () => [capped, rejected],
      'GET /v1/document-types': () => TYPES,
    });
    wrap(<CpfWithdrawals />, MEMBER);
    const table = await screen.findByRole('table', { name: t('e01s.mine') });
    const cappedRow = document.querySelector('tr[data-reference="44"]') as HTMLElement;
    expect(within(cappedRow).getByText(t('e01s.capped'))).toBeTruthy();
    expect(within(cappedRow).getByRole('button', { name: t('e01s.withdraw') })).toBeTruthy();
    const rejectedRow = document.querySelector('tr[data-reference="45"]') as HTMLElement;
    expect(within(rejectedRow).getByText('Fewer than 5 years of membership')).toBeTruthy();
    expect(within(rejectedRow).queryByRole('button')).toBeNull();
    expect(within(table).queryAllByText(t('e01s.capped'))).toHaveLength(1);
  });

  it('SRS_6_4_aMemberWithoutCreateOnlyReadsAndARefusedListIsShown', async () => {
    backend({
      'GET /v1/cpf/sanctions/purposes': () => PURPOSES,
      'GET /v1/cpf/sanctions/mine': () => problem(403, 'AUTHZ-403-PERMISSION', ['E-01', 'VIEW']),
      'GET /v1/document-types': () => TYPES,
    });
    wrap(<CpfWithdrawals />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('error.AUTHZ-403-PERMISSION', ['E-01', 'VIEW']))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('e01s.new') })).toBeNull();
  });

  it('SRS_6_14_theFormCopesWithACatalogueThatFailedAndNamesAnOptionalDocument', async () => {
    backend({
      'GET /v1/cpf/sanctions/purposes': () => [
        {
          ...PURPOSES[0],
          documents: [
            { documentType: 'CERTIFICATE', mandatory: true },
            { documentType: 'PHOTO', mandatory: false },
          ],
        },
      ],
      'GET /v1/cpf/sanctions/mine': () => [],
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<CpfWithdrawals />, MEMBER);
    await userEvent.click(await screen.findByRole('button', { name: t('e01s.new') }));
    // The empty hint makes way for the form.
    expect(screen.queryByText(t('e01s.none'))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('i04.purpose')), 'MARRIAGE');
    // Without the document-type catalogue the checklist names the type codes; only a mandatory
    // document is marked as such.
    expect(screen.getByText(`CERTIFICATE · ${t('e01s.mandatory')}`, { selector: 'legend' })).toBeTruthy();
    expect(screen.getByText('PHOTO', { selector: 'legend' })).toBeTruthy();
  });

  it('a purpose catalogue that failed leaves only the empty choice', async () => {
    backend({
      'GET /v1/cpf/sanctions/purposes': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/cpf/sanctions/mine': () => [],
      'GET /v1/document-types': () => TYPES,
    });
    wrap(<CpfWithdrawals />, MEMBER);
    await userEvent.click(await screen.findByRole('button', { name: t('e01s.new') }));
    expect(within(screen.getByLabelText(t('i04.purpose'))).getAllByRole('option')).toHaveLength(1);
  });

  it('SRS_12_9_aReturnedRequestWithoutParticularsResubmitsFromEmptyFieldsAndARefusedWithdrawalSaysWhy', async () => {
    const bare = sanction({ id: 44, stage: 'RETURNED', purposeName: null, requested: null, tiers: [] });
    backend({
      'GET /v1/cpf/sanctions/purposes': () => PURPOSES,
      'GET /v1/cpf/sanctions/mine': () => [bare],
      'GET /v1/document-types': () => TYPES,
      'POST /v1/cpf/sanctions/44/withdraw': () => problem(409, 'CPF-409-STAGE', ['RETURNED']),
    });
    wrap(<CpfWithdrawals />, MEMBER);
    await screen.findByRole('table', { name: t('e01s.mine') });
    const row = document.querySelector('tr[data-reference="44"]') as HTMLElement;
    // No purpose name, nothing sanctioned, no number yet: dashes, never "null".
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(3);
    expect(row.textContent).not.toContain('null');
    await userEvent.click(within(row).getByRole('button', { name: t('e01s.resubmit') }));
    expect((screen.getByLabelText(t('i04.requested')) as HTMLInputElement).value).toBe('');

    await userEvent.click(within(row).getByRole('button', { name: t('e01s.withdraw') }));
    const first = screen.getByRole('dialog');
    await userEvent.type(within(first).getByRole('textbox'), 'No longer needed');
    await userEvent.click(within(first).getByRole('button', { name: t('e01s.withdraw') }));
    expect((await screen.findByRole('alert')).textContent).toContain(t('error.CPF-409-STAGE', ['RETURNED']));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('I-04 CPF sanctions without every particular', () => {
  it('SRS_6_55_aRequestWithoutAPurposeNameShowsItsCodeOrADashAndADocumentOnlyWhatItHas', async () => {
    const coded = sanction({ id: 45, purposeName: null, purposeCode: 'MISC' });
    const nameless = sanction({
      id: 46,
      purposeName: null,
      purposeCode: null,
      documents: [
        {
          id: 8,
          documentTypeCode: 'CERTIFICATE',
          documentTypeName: 'Certificate',
          referenceNumber: null,
          orderDate: null,
          issuingAuthority: null,
          fileName: 'plain.pdf',
        },
      ],
    });
    backend({
      'GET /v1/cpf/sanctions/46': () => nameless,
      'GET /v1/cpf/sanctions?': (url) => paged([coded, nameless], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    const first = (await screen.findByRole('button', { name: t('i04.openOf', [45]) })).closest(
      'tr',
    ) as HTMLElement;
    expect(within(first).getByText('MISC')).toBeTruthy();
    const second = screen.getByRole('button', { name: t('i04.openOf', [46]) }).closest('tr') as HTMLElement;
    expect(within(second).getAllByText('—').length).toBeGreaterThanOrEqual(1);
    await userEvent.click(within(second).getByRole('button', { name: t('i04.openOf', [46]) }));
    const item = (await screen.findByRole('link', { name: 'plain.pdf' })).closest('li') as HTMLElement;
    expect(item.textContent).toBe('plain.pdf · Certificate');
  });

  it('SRS_G_48_aCancelledOtpOnATierActionClosesTheDialogWithoutAnError', async () => {
    const waiting = sanction({ stage: 'ZONAL_HQ_VALIDATION' });
    backend({
      'GET /v1/cpf/sanctions/41': () => waiting,
      'GET /v1/cpf/sanctions?': (url) => paged([waiting], undefined, url),
      'POST /v1/cpf/sanctions/41/recommend': () => problem(428, 'AUTH-428-OTP', ['7']),
    });
    wrap(<CpfSanctions />, DIVISION);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    await userEvent.click(await screen.findByRole('button', { name: t('i04.recommend') }));
    const reason = await screen.findByRole('dialog');
    await userEvent.type(within(reason).getByRole('textbox'), 'Eligible within the limit');
    await userEvent.click(within(reason).getByRole('button', { name: t('i04.recommend') }));
    const otp = await screen.findByRole('dialog', { name: t('otp.title') });
    await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('E-01 refusals', () => {
  it('SRS_12_9_aRefusedRequestOrResubmissionKeepsItsFormAndACancelledReasonOrCorrectionSendsNothing', async () => {
    const returned = sanction({
      id: 43,
      stage: 'RETURNED',
      purposeName: 'House',
      propertyAddress: 'Plot 4',
      propertyDescription: 'Two rooms',
      tiers: [],
    });
    const calls = backend({
      'GET /v1/cpf/sanctions/purposes': () => PURPOSES,
      'GET /v1/cpf/sanctions/mine': () => [returned],
      'GET /v1/document-types': () => TYPES,
      'POST /v1/cpf/sanctions/43/resubmit': () => problem(422, 'CPF-422-AMOUNT', ['85000']),
      'POST /v1/cpf/sanctions': () => problem(409, 'CPF-409-OPEN', ['MARRIAGE']),
    });
    wrap(<CpfWithdrawals />, MEMBER);
    await screen.findByRole('table', { name: t('e01s.mine') });
    const row = document.querySelector('tr[data-reference="43"]') as HTMLElement;

    await userEvent.click(within(row).getByRole('button', { name: t('e01s.withdraw') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();

    await userEvent.click(within(row).getByRole('button', { name: t('e01s.resubmit') }));
    const address = screen.getByLabelText(t('e01s.address'));
    await userEvent.clear(address);
    await userEvent.type(address, 'Plot 4A');
    await userEvent.click(screen.getAllByRole('button', { name: t('e01s.resubmit') }).at(-1) as HTMLElement);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/resubmit')[0]).toMatchObject({ propertyAddress: 'Plot 4A' });
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('e01s.address'))).toBeNull();
    expect(sent(calls, 'POST', '/withdraw')).toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: t('e01s.new') }));
    await userEvent.selectOptions(screen.getByLabelText(t('i04.purpose')), 'MARRIAGE');
    await userEvent.type(screen.getByLabelText(t('i04.requested')), '50000');
    await userEvent.upload(
      screen.getByLabelText(t('ord.file')),
      new File(['%PDF-1.7'], 'card.pdf', { type: 'application/pdf' }),
    );
    await userEvent.type(screen.getByLabelText(t('ord.date')), '2026-09-01');
    await userEvent.type(screen.getByLabelText(t('ord.authority')), 'Registrar');
    const submit = screen.getByRole('button', { name: t('e01s.submit') });
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false));
    await userEvent.click(submit);
    expect((await screen.findByRole('alert')).textContent).toContain(t('error.CPF-409-OPEN', ['MARRIAGE']));
    expect(screen.getByLabelText(t('i04.purpose'))).toBeTruthy();
  });
});
