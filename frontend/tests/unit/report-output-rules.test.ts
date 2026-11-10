import { describe, expect, it } from 'vitest';
import type { PayslipRow } from '@/modules/reporting/api/outputsApi';
import {
  PAYSLIP_LEVELS,
  closingYear,
  firstOpenRow,
  firstPeriod,
  invoiceRange,
  monthOf,
  noSlipKey,
  slipRows,
  statusPill,
} from '@/modules/reporting/business/outputRules';

/**
 * SRS-17-1, OD-121, OD-123: what the drawn O-01 payslip and remittance screens read from a record
 * (levels, pills, the row and month opened first, the four-column slip, the invoice range).
 */

function row(eis: string, status: string): PayslipRow {
  return {
    eis,
    name: `Member ${eis}`,
    payrollType: 'IDA',
    payPattern: null,
    net: 100,
    status,
    published: false,
  };
}

describe('O-01 levels and pills (OD-121)', () => {
  it('OD_121_theDrawnLevelsInDrawnOrder', () => {
    expect(PAYSLIP_LEVELS).toEqual(['STAFF', 'LABOUR_DEPOT', 'LABOUR_GANG']);
  });

  it.each([
    ['READY', 'p-ok'],
    ['HELD', 'p-bad'],
    ['SUPPRESSED', 'p-bad'],
    ['PROVISIONAL', 'p-warn'],
    ['APPROVED', 'p-ok'],
    ['POSTED_TO_AP', 'p-ok'],
    ['SENT', 'p-ok'],
    ['GENERATED', 'p-info'],
    ['CANCELLED', 'p-bad'],
    ['UNKNOWN', 'p-gray'],
    ['', 'p-gray'],
  ])('OD_121_status %s showsPill %s', (status, pill) => {
    expect(statusPill(status)).toBe(pill);
  });
});

describe('O-01 month text (OD-123)', () => {
  it('OD_123_aYyyymmReadsAsThePeriodText', () => {
    expect(monthOf('202611')).toBe('2026-11');
    expect(monthOf('202501')).toBe('2025-01');
  });

  it('OD_123_aFinancialYearClosesInMarchOfItsSecondYear', () => {
    expect(closingYear('2025-26')).toBe(2026);
    expect(closingYear('2026-27')).toBe(2027);
  });
});

describe('O-01 payslip to show (SRS-17-1)', () => {
  it('SRS_17_1_noRowSelectedAsksForARow', () => {
    expect(noSlipKey(null)).toBe('o01.why.selectRow');
  });

  it('SRS_17_1_aHeldMemberHasNoPayslip', () => {
    expect(noSlipKey({ status: 'HELD' })).toBe('o01.why.held');
  });

  it('SRS_17_1_aSuppressedMemberHasNoPayslip', () => {
    expect(noSlipKey({ status: 'SUPPRESSED' })).toBe('o01.why.suppressed');
  });

  it.each(['READY', 'PROVISIONAL', ''])('SRS_17_1_status %s hasAPayslip', (status) => {
    expect(noSlipKey({ status })).toBeNull();
  });

  it('SRS_17_1_theFirstRowWithAPayslipOpensFirst', () => {
    const rows = [row('E1', 'HELD'), row('E2', 'SUPPRESSED'), row('E3', 'READY'), row('E4', 'READY')];
    expect(firstOpenRow(rows)?.eis).toBe('E3');
  });

  it('SRS_17_1_withoutAnyPayslipTheFirstRowOpens', () => {
    expect(firstOpenRow([row('E1', 'HELD'), row('E2', 'SUPPRESSED')])?.eis).toBe('E1');
  });

  it('SRS_17_1_noRowsOpenNothing', () => {
    expect(firstOpenRow([])).toBeNull();
  });
});

describe('O-01 month opened first (OD-123)', () => {
  it('OD_123_noMonthAtAllIsEmpty', () => {
    expect(firstPeriod(undefined)).toBe('');
    expect(firstPeriod([])).toBe('');
  });

  it('OD_123_theNewestMonthWithResultsOpens', () => {
    expect(
      firstPeriod([
        { yymm: '202611', locked: false },
        { yymm: '202610', locked: false, results: false },
        { yymm: '202609', locked: true, results: true },
        { yymm: '202608', locked: true, results: true },
      ]),
    ).toBe('202609');
  });

  it('OD_123_withoutResultsTheNewestMonthOpens', () => {
    expect(
      firstPeriod([
        { yymm: '202611', locked: false },
        { yymm: '202610', locked: false, results: false },
      ]),
    ).toBe('202611');
  });
});

describe('O-01 four-column slip (OD-121)', () => {
  it('OD_121_moreEarningsThanDeductionsLeaveTheDeductionSideEmpty', () => {
    expect(slipRows(['a', 'b', 'c'], ['x'])).toEqual([
      ['a', 'x'],
      ['b', undefined],
      ['c', undefined],
    ]);
  });

  it('OD_121_moreDeductionsThanEarningsLeaveTheEarningSideEmpty', () => {
    expect(slipRows(['a'], ['x', 'y'])).toEqual([
      ['a', 'x'],
      [undefined, 'y'],
    ]);
  });

  it('OD_121_noLinesNoRows', () => {
    expect(slipRows([], [])).toEqual([]);
  });
});

describe('Remittance invoice range (OD-121)', () => {
  it('OD_121_noFirstInvoiceNoRange', () => {
    expect(invoiceRange({ invoiceFrom: null, invoiceTo: 'INV202611000009' })).toBeNull();
    expect(invoiceRange({ invoiceFrom: '', invoiceTo: 'INV202611000009' })).toBeNull();
  });

  it('OD_121_aSingleInvoiceShowsAlone', () => {
    expect(invoiceRange({ invoiceFrom: 'INV202611000001', invoiceTo: null })).toBe('INV202611000001');
    expect(invoiceRange({ invoiceFrom: 'INV202611000001', invoiceTo: '' })).toBe('INV202611000001');
    expect(invoiceRange({ invoiceFrom: 'INV202611000001', invoiceTo: 'INV202611000001' })).toBe(
      'INV202611000001',
    );
  });

  it('OD_121_aRepeatedMonthPrefixShortensTheSecondToItsSerial', () => {
    expect(invoiceRange({ invoiceFrom: 'INV202611000001', invoiceTo: 'INV202611000042' })).toBe(
      'INV202611000001 – 000042',
    );
  });

  it('OD_121_aDifferentPrefixShowsBothInFull', () => {
    expect(invoiceRange({ invoiceFrom: 'INV202611000001', invoiceTo: 'INV202612000042' })).toBe(
      'INV202611000001 – INV202612000042',
    );
  });

  it('OD_121_aDifferentLengthShowsBothInFull', () => {
    expect(invoiceRange({ invoiceFrom: 'INV202611000001', invoiceTo: 'INV2026110000042' })).toBe(
      'INV202611000001 – INV2026110000042',
    );
  });
});
