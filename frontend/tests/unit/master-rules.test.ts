import { describe, expect, it } from 'vitest';
import {
  emptyLine,
  formatRate,
  lineKey,
  linesProblem,
  linesValid,
  parameterValues,
  summarise,
} from '@/modules/configuration/business/masterRules';
import type { ParameterVersion, RateLine } from '@/modules/configuration/api/mastersApi';
import type { RateDraftLine } from '@/modules/configuration/business/masterRules';

/** Pure rules of the master screens (M-04, M-22, M-23); the server checks the same (RateMasterService). */

const line = (over: Partial<RateLine> = {}): RateLine => ({
  ...emptyLine(true),
  rateCode: 'DA',
  rateValue: 50,
  ...over,
});

describe('master rules (M-04, M-22, M-23)', () => {
  it('formats a rate in its unit', () => {
    expect(formatRate(50, 'PERCENT')).toBe('50%');
    expect(formatRate(1800, 'INR')).toBe('₹ 1,800');
    expect(formatRate(3, null)).toBe('3');
    expect(formatRate(12.345, 'PERCENT')).toBe('12.35%');
  });

  it('names what a line applies to, with open slab ends', () => {
    expect(lineKey(line())).toBe('');
    expect(lineKey(line({ payPattern: 'IDA', cityClass: 'X', categoryCode: 'STAFF' }))).toBe(
      'IDA · X · STAFF',
    );
    expect(lineKey(line({ basicSlabFrom: 1000, basicSlabTo: null }))).toBe('1000–∞');
    expect(lineKey(line({ basicSlabFrom: null, basicSlabTo: 5000 }))).toBe('0–5000');
    // A blank key value is no key value (the server treats a blank as none).
    expect(lineKey(line({ payPattern: '', cityClass: '', categoryCode: 'STAFF' }))).toBe('STAFF');
  });

  it('summarises a version', () => {
    expect(summarise([line({ cityClass: 'X', rateValue: 30 }), line({ rateValue: 10, unit: 'INR' })])).toBe(
      'X 30% · ₹ 10',
    );
    expect(summarise([])).toBe('');
  });

  it('lists the values of an other parameter in screen order', () => {
    const p = {
      percentValue: 10,
      numericValue: 2,
      ceilingValue: 5000,
      windowFromTime: '09:00',
      windowToTime: null,
      instalmentCount: 12,
    } as unknown as ParameterVersion;
    expect(parameterValues(p)).toEqual(['10%', '2', '≤ ₹ 5,000', '09:00–', '× 12']);
    expect(
      parameterValues({
        percentValue: null,
        numericValue: null,
        ceilingValue: null,
        windowFromTime: null,
        windowToTime: null,
        instalmentCount: null,
      } as unknown as ParameterVersion),
    ).toEqual([]);
  });

  it('starts an empty line as a percentage or an amount that applies to everyone, with no code yet', () => {
    expect(emptyLine(true)).toEqual({
      designationId: null,
      categoryCode: null,
      payPattern: null,
      basicSlabFrom: null,
      basicSlabTo: null,
      cityClass: null,
      rateCode: '',
      rateValue: null,
      unit: 'PERCENT',
    });
    expect(emptyLine(false).unit).toBe('INR');
  });

  it('accepts only complete lines: code, value 0..100 for a percentage, a rising slab', () => {
    expect(linesValid([])).toBe(false);
    expect(linesValid([line()])).toBe(true);
    expect(linesValid([line({ rateValue: 0 })])).toBe(true);
    expect(linesValid([line({ rateValue: 100 })])).toBe(true);
    expect(linesValid([line({ rateValue: 100.01 })])).toBe(false);
    expect(linesValid([line({ rateValue: 250, unit: 'INR' })])).toBe(true);
    expect(linesValid([line({ rateValue: -1 })])).toBe(false);
    expect(linesValid([line({ rateValue: Number.NaN })])).toBe(false);
    expect(linesValid([line({ rateCode: 'da' })])).toBe(false);
    expect(linesValid([line({ rateCode: 'D' })])).toBe(false);
    expect(linesValid([line({ basicSlabFrom: 100, basicSlabTo: 100 })])).toBe(false);
    expect(linesValid([line({ basicSlabFrom: 100, basicSlabTo: 101 })])).toBe(true);
  });

  it('M-04 field table (SRS l.3651, l.3653): a blank value is never zero; M-04 asks the pay pattern on every line', () => {
    const blank: RateDraftLine = { ...line(), rateValue: null };
    expect(linesValid([blank])).toBe(false);
    expect(linesProblem([blank])).toBe('mst.why.valueRequired');
    expect(linesProblem([line({ rateValue: Number.NaN })])).toBe('mst.why.valueRequired');
    expect(linesProblem([])).toBe('mst.why.noLines');
    // M-22 leaves the pattern optional; M-04 refuses a line without it, even one of several.
    expect(linesProblem([line()])).toBeNull();
    expect(linesValid([line()], true)).toBe(false);
    expect(linesProblem([line({ payPattern: 'IDA' }), line({ payPattern: null })], true)).toBe(
      'mst.why.patternRequired',
    );
    expect(linesProblem([line({ payPattern: '' })], true)).toBe('mst.why.patternRequired');
    expect(linesProblem([line({ payPattern: 'CDA' })], true)).toBeNull();
    expect(linesValid([line({ payPattern: 'CDA' })], true)).toBe(true);
    // A value of zero is entered, not blank: allowed.
    expect(linesProblem([line({ payPattern: 'IDA', rateValue: 0 })], true)).toBeNull();
    // The value is checked first, then the pattern, then the other rules.
    expect(linesProblem([{ ...line({ payPattern: null }), rateValue: null }], true)).toBe(
      'mst.why.valueRequired',
    );
    expect(linesProblem([line({ payPattern: 'IDA', rateCode: 'da' })], true)).toBe('mst.why.linesInvalid');
  });

  it('every line must be complete, not one of them', () => {
    expect(linesValid([line(), line({ rateCode: 'da' })])).toBe(false);
  });

  it('a rate code is a capital letter and 1 to 39 capitals, digits or underscores (rate_code VARCHAR2(40))', () => {
    expect(linesValid([line({ rateCode: 'HRA_X1' })])).toBe(true);
    expect(linesValid([line({ rateCode: `D${'A'.repeat(39)}` })])).toBe(true);
    expect(linesValid([line({ rateCode: `D${'A'.repeat(40)}` })])).toBe(false);
    expect(linesValid([line({ rateCode: '1DA' })])).toBe(false);
    expect(linesValid([line({ rateCode: 'DA-1' })])).toBe(false);
  });

  it('a slab with one end given is open on the other side: the order is checked only when both are given', () => {
    // As the server checks (RateMasterService.validate); the DDL sets no bound on basic_slab_from / _to.
    expect(linesValid([line({ basicSlabFrom: 100, basicSlabTo: null })])).toBe(true);
    expect(linesValid([line({ basicSlabFrom: null, basicSlabTo: 0 })])).toBe(true);
  });
});
