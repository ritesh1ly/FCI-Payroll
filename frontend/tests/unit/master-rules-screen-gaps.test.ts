import { describe, expect, it } from 'vitest';
import {
  arrearImpact,
  daFormProblem,
  drawnParameterRows,
  masterBase,
  versionTone,
} from '@/modules/configuration/business/masterRules';

/**
 * The drawn M-04 / M-23 / special-rate master screens (OD-121; SRS-11-15, SRS-G-8, SRS l.3651):
 * a rate and its ceiling drawn as one row, the catalogue code without its pay pattern, the arrear
 * event a DA revision raised, the version pill and the DA form validation.
 */

const code = (c: string) => ({ code: c });

describe('M-23 drawn parameter rows (SRS-11-15, OD-121)', () => {
  it('SRS_11_15_aRateAndItsCeilingAreDrawnAsOneRow', () => {
    const [ota, otaCeiling, other] = [code('OTA_RATE'), code('OTA_CEILING'), code('MHS_CONTRIBUTION')];
    expect(drawnParameterRows([ota, otaCeiling, other])).toEqual([
      { row: ota, ceiling: otaCeiling },
      { row: other, ceiling: null },
    ]);
  });

  it('SRS_11_15_aCeilingBeforeItsRateStillJoinsTheRate', () => {
    const [npaCeiling, npa] = [code('NPA_CEILING'), code('NPA_PCT')];
    expect(drawnParameterRows([npaCeiling, npa])).toEqual([{ row: npa, ceiling: npaCeiling }]);
  });

  it('SRS_11_15_aCeilingWithoutItsRateOnThePageIsItsOwnRow', () => {
    const [npaCeiling, ota, otaCeiling] = [code('NPA_CEILING'), code('OTA_RATE'), code('OTA_CEILING')];
    expect(drawnParameterRows([npaCeiling, ota, otaCeiling])).toEqual([
      { row: npaCeiling, ceiling: null },
      { row: ota, ceiling: otaCeiling },
    ]);
  });

  it('SRS_11_15_aRateWithoutItsCeilingHasNoCeiling', () => {
    const [x, ota] = [code('CSSS_AMOUNT'), code('OTA_RATE')];
    expect(drawnParameterRows([x, ota])).toEqual([
      { row: x, ceiling: null },
      { row: ota, ceiling: null },
    ]);
  });

  it('SRS_11_15_anEmptyPageDrawsNoRow', () => {
    expect(drawnParameterRows([])).toEqual([]);
  });
});

describe('Special rate tables: master code (SRS-G-38)', () => {
  it.each([
    ['DA_IDA', 'DA'],
    ['HRA_CDA', 'HRA'],
    ['HRA', 'HRA'],
    ['DA_IDA_OLD', 'DA_IDA_OLD'],
    ['DAIDA', 'DAIDA'],
  ])('SRS_G_38_master %s readsAs %s', (master, base) => {
    expect(masterBase(master)).toBe(base);
  });
});

describe('M-04 arrear impact and version pill (SRS-11-15, OD-121)', () => {
  it('SRS_11_15_noArrearEventShowsADash', () => {
    expect(arrearImpact(null, 536000)).toBe('—');
    expect(arrearImpact('', null)).toBe('—');
  });

  it('SRS_11_15_anArrearEventWithoutAmountShowsItsReference', () => {
    expect(arrearImpact('AE-131', null)).toBe('AE-131');
  });

  it('SRS_11_15_anArrearEventShowsItsAmountInIndianGrouping', () => {
    expect(arrearImpact('AE-131', 536000)).toBe('AE-131 · ₹ 5,36,000');
    expect(arrearImpact('AE-132', 0)).toBe('AE-132 · ₹ 0');
  });

  it.each([
    ['ACTIVE', 'p-ok'],
    ['AWAITING_APPROVAL', 'p-warn'],
    ['SCHEDULED', 'p-warn'],
    ['REJECTED', 'p-bad'],
    ['SUPERSEDED', 'p-gray'],
    ['', 'p-gray'],
  ])('OD_121_versionStatus %s showsPill %s', (status, pill) => {
    expect(versionTone(status)).toBe(pill);
  });
});

describe('M-04 DA form validation (SRS-11-15, SRS l.3651)', () => {
  const DRAFT = { pattern: 'IDA', rate: '50', basis: 'Basic + DP', from: '2026-07-01' };

  it('SRS_11_15_aCompleteFormHasNoProblem', () => {
    expect(daFormProblem(DRAFT)).toBeNull();
    expect(daFormProblem({ ...DRAFT, basis: 'Basic + DP (50%), A/B.-x' })).toBeNull();
  });

  it('SRS_11_15_thePatternIsAskedFirst', () => {
    expect(daFormProblem({ pattern: '', rate: '', basis: '', from: '' })).toBe('m04.err.pattern');
  });

  it.each(['', 'abc', 'Infinity', '-1', '-0.01', '100.01', '1e3'])('SRS_11_15_rate %s isRefused', (rate) => {
    expect(daFormProblem({ ...DRAFT, rate })).toBe('m04.err.rate');
  });

  it.each(['0', '100', '0.5', '99.99'])('SRS_11_15_rate %s fromZeroToHundredIsAccepted', (rate) => {
    expect(daFormProblem({ ...DRAFT, rate })).toBeNull();
  });

  it.each(['', '   ', '#Basic', 'Basic#', 'Basic_DP', 'A'.repeat(201)])(
    'SRS_11_15_computedOn %j isRefused',
    (basis) => {
      expect(daFormProblem({ ...DRAFT, basis })).toBe('m04.err.basis');
    },
  );

  it('SRS_11_15_computedOnIsCheckedTrimmedUpTo200Characters', () => {
    expect(daFormProblem({ ...DRAFT, basis: 'B' })).toBeNull();
    expect(daFormProblem({ ...DRAFT, basis: `  ${'A'.repeat(200)}  ` })).toBeNull();
  });

  it('SRS_11_15_theEffectiveFromIsAskedLast', () => {
    expect(daFormProblem({ ...DRAFT, from: '' })).toBe('m04.err.from');
  });
});
