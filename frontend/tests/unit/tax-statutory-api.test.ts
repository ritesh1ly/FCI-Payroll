import { afterEach, describe, expect, it, vi } from 'vitest';
import { statutoryApi } from '@/modules/tax/api/statutoryApi';
import { rupees } from '@/modules/tax/components/money';
import { backend, sent } from './support/harness';

/**
 * The wire form of C-07, M-17, C-10 and C-11 requests (backend StatutoryOutputController,
 * OutputDefinitionController, TaxComputationController): blank optional fields travel as null,
 * the year and filters as query parameters, documents as a JSON part beside the file.
 */

afterEach(() => vi.unstubAllGlobals());

async function request(form: FormData) {
  return JSON.parse(await (form.get('request') as Blob).text());
}

describe('statutory API', () => {
  it('BR_MIS_002_blankOptionalVersionFieldsAreSentAsNull', async () => {
    const calls = backend({ 'POST /v1/tax/output-definitions/FORM_24Q/versions': () => ({ approvalId: 1 }) });
    const base = {
      effectiveFrom: '2027-04-01',
      layoutFormat: 'CSV' as const,
      periodLockRequired: false,
      controls: [],
      reason: 'r',
    };
    await statutoryApi.proposeVersion('FORM_24Q', {
      ...base,
      layoutNote: '',
      roundingRuleKey: '',
      fields: [{ label: 'A', binding: 'LITERAL', reference: 'x', formatMask: '' }],
    });
    await statutoryApi.proposeVersion('FORM_24Q', {
      ...base,
      layoutNote: 'n',
      roundingRuleKey: 'R.K',
      fields: [{ label: 'A', binding: 'LITERAL', reference: 'x', formatMask: '99' }],
    });
    const [blank, full] = sent(calls, 'POST', '/versions') as Record<string, unknown>[];
    expect(blank).toMatchObject({ layoutNote: null, roundingRuleKey: null, fields: [{ formatMask: null }] });
    expect(full).toMatchObject({ layoutNote: 'n', roundingRuleKey: 'R.K', fields: [{ formatMask: '99' }] });
  });

  it('SRS_7_9_theCertificateQueriesAndPartAReference', async () => {
    const calls = backend({
      'GET /v1/tax/form16-certificates?': () => ({ items: [], page: 0, size: 20, total: 0 }),
      'POST /v1/tax/form16-certificates/3/part-a': () => ({}),
    });
    await statutoryApi.certificates('2025-26', '', 0, 20);
    await statutoryApi.certificates('2025-26', 'MERGED', 1, 50);
    const urls = calls.filter((c) => c.method === 'GET').map((c) => c.url);
    expect(urls[0]).toContain('/v1/tax/form16-certificates?finYear=2025-26&page=0&size=20');
    expect(urls[1]).toContain('finYear=2025-26&status=MERGED&page=1&size=50');
    const file = new File(['%PDF'], 'a.pdf');
    const particulars = {
      documentType: 'CERTIFICATE',
      reference: '',
      documentDate: '2026-06-01',
      issuingAuthority: 'ITD',
    };
    await statutoryApi.uploadPartA(3, particulars, file);
    await statutoryApi.uploadPartA(3, { ...particulars, reference: 'T-1' }, file);
    const forms = sent(calls, 'POST', '/part-a') as FormData[];
    expect((await request(forms[0] as FormData)).reference).toBeNull();
    expect((await request(forms[1] as FormData)).reference).toBe('T-1');
    expect(statutoryApi.partAUrl(3)).toContain('/v1/tax/form16-certificates/3/part-a');
  });

  it('SRS_7_31_aFilingWithoutEvidenceSendsNoFileAndNoParticulars', async () => {
    const calls = backend({ 'POST /v1/tax/returns/5/filings': () => ({}) });
    const filing = {
      filingDate: '2026-07-20',
      signatoryName: 'S',
      signatoryDesignation: 'D',
      acknowledgementToken: '',
      evidence: {
        documentType: 'CERTIFICATE',
        reference: '',
        documentDate: '2026-07-20',
        issuingAuthority: 'X',
      },
      file: null,
    };
    await statutoryApi.recordFiling(5, filing);
    await statutoryApi.recordFiling(5, {
      ...filing,
      acknowledgementToken: 'T',
      file: new File(['%PDF'], 'ack.pdf'),
    });
    const [bare, withFile] = sent(calls, 'POST', '/filings') as FormData[];
    expect(await request(bare as FormData)).toMatchObject({ acknowledgementToken: null, evidence: null });
    expect((bare as FormData).get('file')).toBeNull();
    expect(await request(withFile as FormData)).toMatchObject({
      acknowledgementToken: 'T',
      evidence: { reference: null },
    });
    expect(((withFile as FormData).get('file') as File).name).toBe('ack.pdf');
    expect(statutoryApi.evidenceUrl(5, 9)).toContain('/v1/tax/returns/5/documents/9');
    expect(statutoryApi.forecastExportUrl('870001', '2025-26', 'CSV')).toContain(
      '/v1/tax/forecasts/870001/export?finYear=2025-26&format=CSV',
    );
  });

  it('SRS_7_1_rupeesWithIndianGroupingOrADash', () => {
    expect(rupees(null)).toBe('—');
    expect(rupees(undefined)).toBe('—');
    expect(rupees(150000)).toBe('₹1,50,000.00');
    expect(rupees(0)).toBe('₹0.00');
  });
});
