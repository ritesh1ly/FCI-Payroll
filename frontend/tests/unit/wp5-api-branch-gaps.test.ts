import { afterEach, describe, expect, it, vi } from 'vitest';
import { cpfApi } from '@/modules/cpf/api/cpfApi';
import { controlFlagApi } from '@/modules/employee/api/controlFlagApi';
import { eventsApi } from '@/modules/employee/api/eventsApi';
import { flagApi } from '@/modules/employee/api/flagApi';
import { hrmsChangeApi } from '@/modules/employee/api/hrmsChangeApi';
import { hrmsUpdateApi } from '@/modules/employee/api/hrmsUpdateApi';
import { incrementApi } from '@/modules/increment/api/incrementApi';
import { backend } from './support/harness';

/**
 * SRS-G-31: an order copy's reference is optional (the order is identified by its type, date and
 * issuing authority); every multipart upload of the employee, CPF and increment modules sends the
 * reference only when one is given, never an empty field.
 */

afterEach(() => vi.unstubAllGlobals());

const copy = (reference: string) => ({
  documentType: 'OTHER',
  reference,
  orderDate: '2026-10-01',
  issuingAuthority: 'GM (P)',
  file: new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }),
});

function forms(calls: { body: unknown }[]): FormData[] {
  return calls.map((c) => c.body as FormData);
}

/** The two uploads (without, then with the reference) carry it only the second time. */
function referenceOnlyWhenGiven(calls: { body: unknown }[], reference: string) {
  const [without, given] = forms(calls);
  expect(without?.has('reference')).toBe(false);
  expect(without?.get('documentType')).toBe('OTHER');
  expect(given?.get('reference')).toBe(reference);
}

describe('SRS-G-31 optional order reference on every WP5 upload', () => {
  it('SRS_G_31_anI21OrderCopyAndAStoredBulkOrderCopySendTheReferenceOnlyWhenGiven', async () => {
    const attached = backend({});
    await hrmsChangeApi.attach(5, copy(''));
    await hrmsChangeApi.attach(5, copy('HCO/1'));
    referenceOnlyWhenGiven(attached, 'HCO/1');
    vi.unstubAllGlobals();
    const stored = backend({});
    await hrmsChangeApi.storeOrderCopy(copy(''));
    await hrmsChangeApi.storeOrderCopy(copy('HCO/2'));
    referenceOnlyWhenGiven(stored, 'HCO/2');
  });

  it('SRS_G_31_aControlFlagOrAllowanceFlagDocumentSendsTheReferenceOnlyWhenGiven', async () => {
    const control = backend({});
    const hold = { flagCode: 'HOLD' as const, value: 'Y' as const, effectiveFrom: '2026-10-01', reason: 'r' };
    await controlFlagApi.propose('100041', hold, copy(''));
    await controlFlagApi.propose('100041', hold, copy('EST/4'));
    referenceOnlyWhenGiven(control, 'EST/4');
    vi.unstubAllGlobals();
    const allowance = backend({});
    const cca = { flagCode: 'CCA', value: 'N' as const, effectiveFrom: '2026-10-01', reason: 'r' };
    await flagApi.propose('100041', cca, copy(''));
    await flagApi.propose('100041', cca, copy('EST/5'));
    referenceOnlyWhenGiven(allowance, 'EST/5');
  });

  it('SRS_16_18_aTransferOrderCopySendsTheReferenceOnlyWhenGiven', async () => {
    const calls = backend({});
    await eventsApi.storeTransferOrder('100041', copy(''));
    await eventsApi.storeTransferOrder('100041', copy('TO/7'));
    referenceOnlyWhenGiven(calls, 'TO/7');
  });

  it('SRS_17_15_anHrmsUpdateRequestOrderSendsTheReferenceOnlyWhenGiven', async () => {
    const calls = backend({});
    await hrmsUpdateApi.attach(41, true, copy(''));
    await hrmsUpdateApi.attach(41, false, copy('UR/1'));
    referenceOnlyWhenGiven(calls, 'UR/1');
    expect(calls[0]?.url).toContain('/mine');
  });

  it('SRS_13_18_aPayFixationOrderSendsTheReferenceOnlyWhenGiven', async () => {
    const calls = backend({});
    await incrementApi.storeOrder({ eis: '109991', ...copy('') });
    await incrementApi.storeOrder({ eis: '109991', ...copy('EST/17') });
    referenceOnlyWhenGiven(calls, 'EST/17');
    expect(forms(calls)[0]?.get('eis')).toBe('109991');
  });

  it('SRS_6_28_deputationEvidenceAddedLaterSendsTheReferenceOnlyWhenGiven', async () => {
    const calls = backend({});
    await cpfApi.addDeputationEvidence(3, copy(''));
    await cpfApi.addDeputationEvidence(3, copy('UTR-9'));
    referenceOnlyWhenGiven(calls, 'UTR-9');
    expect(calls[0]?.url).toContain('/v1/cpf/deputation-entries/3/evidence');
  });
});
