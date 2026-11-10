import { afterEach, describe, expect, it, vi } from 'vitest';
import { cpfApi } from '@/modules/cpf/api/cpfApi';
import { hrmsChangeApi } from '@/modules/employee/api/hrmsChangeApi';
import { labourApi } from '@/modules/labour/api/labourApi';
import { taxApi } from '@/modules/tax/api/taxApi';
import { backend } from './support/harness';

/**
 * The wire form of optional fields (backend controllers of I-09, C-12, L-02 … L-05, I-07, I-18 and
 * I-21): an optional reference, last name or document is sent only when given; an OTP proof only
 * when the user entered one; blank filters are left out of the query.
 */

afterEach(() => vi.unstubAllGlobals());

const evidence = (reference: string) => ({
  documentType: 'ORDER',
  reference,
  orderDate: '2026-10-01',
  issuingAuthority: 'GM (CPF)',
  file: new File(['%PDF'], 'o.pdf'),
});

function formOf(calls: { body: unknown }[], index: number): FormData {
  return calls[index]?.body as FormData;
}

describe('optional fields on the wire', () => {
  it('SRS_6_28_anEvidenceReferenceIsSentOnlyWhenGiven', async () => {
    const calls = backend({});
    const correction = {
      eis: '104882',
      asAtDate: '2026-03-31',
      employee: '100',
      employer: null,
      vpf: '',
      reasonCode: 'MIGRATION',
      interestEffectiveDate: '2026-04-01',
      remarks: 'r',
    };
    await cpfApi.raiseCorrection(correction, evidence(''));
    await cpfApi.raiseCorrection(correction, evidence('CPF/2026/7'));
    expect(formOf(calls, 0).has('reference')).toBe(false);
    expect(formOf(calls, 0).has('employer')).toBe(false);
    expect(formOf(calls, 1).get('reference')).toBe('CPF/2026/7');
    const entry = {
      eis: '104882',
      borrowingOrgId: '3',
      type: 'CONTRIBUTION_RECEIPT' as const,
      sanctionId: '',
      payrollYymm: '202610',
      instalment: '',
      employee: '100',
      employer: '100',
      vpf: '',
    };
    await cpfApi.recordDeputation(entry, evidence(''));
    await cpfApi.recordDeputation(entry, evidence('DEP/1'));
    expect(formOf(calls, 2).has('reference')).toBe(false);
    expect(formOf(calls, 2).has('sanctionId')).toBe(false);
    expect(formOf(calls, 3).get('reference')).toBe('DEP/1');
  });

  it('SRS_G_21_anOtpProofIsSentOnlyWhenEntered', async () => {
    const calls = backend({});
    const rate = {
      finYear: '2026-27',
      fundType: 'CPF' as const,
      rateSource: 'FCI_TRUST' as const,
      ratePercent: '8.25',
      appliesFrom: '2026-04-01',
      appliesTo: null,
      applicabilityNote: null,
      bonusInterestPct: null,
    };
    await cpfApi.recordRate(rate as never);
    await cpfApi.recordRate(rate as never, { challengeId: 4, code: '123456' } as never);
    expect(calls[0]?.body).not.toHaveProperty('otp');
    expect(calls[1]?.body).toHaveProperty('otp');
    await taxApi.createState({ code: 'MH', reason: 'r' } as never);
    await taxApi.createState(
      { code: 'MH', reason: 'r' } as never,
      { challengeId: 5, code: '654321' } as never,
    );
    expect(calls[2]?.body).not.toHaveProperty('otp');
    expect(calls[3]?.body).toHaveProperty('otp');
  });

  it('SRS_2_59_aLabourCorrectionAndReinstatementSendOnlyWhatIsGiven', async () => {
    const calls = backend({});
    const base = {
      type: 'NAME' as const,
      firstName: ' Ram ',
      lastName: ' ',
      birthDate: '',
      reasonCode: 'AS_PER_ORDER' as const,
      reasonText: '',
      orderReference: '',
      orderDate: '2026-10-01',
      issuingAuthority: 'AM',
      effectiveDate: '2026-10-01',
      document: null,
    };
    await labourApi.correct(7, base);
    await labourApi.correct(7, { ...base, lastName: 'Kumar', document: new File(['x'], 'd.pdf') });
    expect(formOf(calls, 0).has('lastName')).toBe(false);
    expect(formOf(calls, 0).has('document')).toBe(false);
    expect(formOf(calls, 1).get('lastName')).toBe('Kumar');
    expect(formOf(calls, 1).has('document')).toBe(true);
    const reinstatement = {
      date: '2026-10-01',
      orderReference: 'R/1',
      orderDate: '2026-09-30',
      issuingAuthority: 'AM',
      reason: 'back',
      document: null,
    };
    await labourApi.reinstate(7, reinstatement);
    await labourApi.reinstate(7, { ...reinstatement, document: new File(['x'], 'r.pdf') });
    expect(formOf(calls, 2).has('document')).toBe(false);
    expect(formOf(calls, 3).has('document')).toBe(true);
  });

  it('SRS_1_67_blankFiltersAreLeftOutOfTheQuery', async () => {
    const calls = backend({});
    await hrmsChangeApi.list({ field: 'EPS_NUMBER', eis: '104882' }, 0, 20);
    await hrmsChangeApi.list({}, 0, 20);
    await taxApi.declarations('2026-27', '', 0, 20);
    await taxApi.declarations('2026-27', 'SUBMITTED', 0, 20);
    await taxApi.savingsEntries('2026-27', '', 0, 20);
    await taxApi.savingsEntries('2026-27', 'APPROVED', 0, 20);
    const urls = calls.map((c) => c.url);
    expect(urls[0]).toContain('field=EPS_NUMBER');
    expect(urls[0]).toContain('eis=104882');
    expect(urls[1]).not.toContain('field=');
    expect(urls[2]).not.toContain('status=');
    expect(urls[3]).toContain('status=SUBMITTED');
    expect(urls[4]).not.toContain('status=');
    expect(urls[5]).toContain('status=APPROVED');
  });
});
