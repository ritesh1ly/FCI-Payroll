import { afterEach, describe, expect, it, vi } from 'vitest';
import { claimsApi } from '@/modules/allowance/api/claimsApi';
import { arrearApi } from '@/modules/arrear/api/arrearApi';
import { mdpApi } from '@/modules/jobs/api/mdpApi';
import { backend, paged } from './support/harness';

/**
 * The wire form of optional fields on I-03, I-16, I-08 and I-19 (SRS-11-14, SRS-11-17, SRS-8-13, SRS-15-22,
 * SRS-G-30): an optional reference or issuing office is sent only when typed, a blank filter is left out of the
 * query, and the I-16 export names no filter it was not given.
 */
afterEach(() => vi.unstubAllGlobals());

const FILE = new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' });

describe('optional fields on the wire', () => {
  it('SRS_11_14_aClaimDocumentWithoutAReferenceSendsNone', async () => {
    const calls = backend({ 'POST /v1/claims/7/documents': () => ({ id: 1 }) });
    await claimsApi.attach(7, {
      documentType: 'OTHER',
      reference: '',
      orderDate: '2026-10-01',
      issuingOffice: 'Depot office',
      file: FILE,
    });
    const form = calls.find((c) => c.method === 'POST')?.body as FormData;
    expect(form.get('reference')).toBeNull();
    expect(form.get('issuingOffice')).toBe('Depot office');
    expect(form.get('documentType')).toBe('OTHER');
  });

  it('SRS_11_17_theCafeteriaExportWithoutFiltersHasNoQuery', () => {
    expect(claimsApi.cafeteriaExportUrl('', '')).toMatch(/\/v1\/cafeteria-selections\/export$/);
    expect(claimsApi.cafeteriaExportUrl('104882', '2026-27')).toMatch(
      /\/v1\/cafeteria-selections\/export\?eis=104882&finYear=2026-27$/,
    );
  });

  it('SRS_8_13_anEventFilterIsSentBeforeThePageAndABlankOneIsLeftOut', async () => {
    const calls = backend({ 'GET /v1/arrear-events?': (url: string) => paged([], 0, url) });
    await arrearApi.events({ status: 'RAISED', eventCode: 'DA_REVISION' }, 1, 50);
    await arrearApi.events({}, 0, 20);
    expect(calls.map((c) => c.url.replace(/^.*\/v1/, '/v1'))).toEqual([
      '/v1/arrear-events?status=RAISED&eventCode=DA_REVISION&page=1&size=50',
      '/v1/arrear-events?page=0&size=20',
    ]);
  });

  it('SRS_15_22_anMdpOrderSendsItsTrimmedReferenceAndOfficeOnlyWhenTyped', async () => {
    const calls = backend({ 'POST /v1/mdp-transactions/47/documents': () => ({ id: 10 }) });
    await mdpApi.attachOrder(47, {
      documentType: 'SANCTION_ORDER',
      reference: '  FCI/EST/1  ',
      orderDate: '2026-07-01',
      issuingOffice: '  RO Chennai ',
      file: FILE,
    });
    await mdpApi.attachOrder(47, {
      documentType: 'OTHER',
      reference: '   ',
      orderDate: '2026-07-01',
      issuingOffice: '',
      file: FILE,
    });
    const [typed, blank] = calls.filter((c) => c.method === 'POST').map((c) => c.body as FormData);
    expect(typed?.get('reference')).toBe('FCI/EST/1');
    expect(typed?.get('issuingOffice')).toBe('RO Chennai');
    expect(blank?.get('reference')).toBeNull();
    expect(blank?.get('issuingOffice')).toBeNull();
  });
});
