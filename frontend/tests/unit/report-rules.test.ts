import { describe, expect, it } from 'vitest';
import type { VersionProposal } from '@/modules/reporting/api/reportApi';
import {
  BINDINGS,
  CHANNELS,
  CONSUMERS,
  LEVELS,
  RECONCILES,
  exportBlockedWhy,
  familiesOf,
  isYymm,
  modelBlocked,
  proposalBlocked,
  toVersionRequest,
} from '@/modules/reporting/business/reportRules';

/** Report screen rules (plan 15; SRS-18-10, SRS-18-28). */
describe('report rules', () => {
  it('SRS-18-28: the levels and families the screens offer; a month is YYYYMM', () => {
    expect(LEVELS).toEqual(['UNIT', 'REGION', 'ZONE', 'ALL']);
    // OD-123: the families are those of the catalogue the API returned, each once and in order.
    expect(
      familiesOf([{ family: 'Operational' }, { family: 'Control-Audit-MIS' }, { family: 'Operational' }]),
    ).toEqual(['Control-Audit-MIS', 'Operational']);
    expect(familiesOf([])).toEqual([]);
    expect(isYymm('202610')).toBe(true);
    expect(isYymm('202613')).toBe(false);
    expect(isYymm('202600')).toBe(false);
    expect(isYymm('2026-10')).toBe(false);
  });

  it('SRS-18-10: a version starts today or later and a blocked one names a printable reason', () => {
    const base = {
      layoutFormat: null,
      periodLockRequired: false,
      storeMode: 'REGENERATE' as const,
      blocked: false,
      blockedReason: null,
      effectiveFrom: '2026-10-28',
    };
    expect(proposalBlocked(base, '2026-10-28')).toBeNull();
    expect(proposalBlocked({ ...base, effectiveFrom: '2026-10-27' }, '2026-10-28')).toBe('m12.why.effective');
    expect(proposalBlocked({ ...base, effectiveFrom: '28-10-2026' }, '2026-10-28')).toBe('m12.why.effective');
    expect(proposalBlocked({ ...base, blocked: true }, '2026-10-28')).toBe('m12.why.reason');
    expect(proposalBlocked({ ...base, blocked: true, blockedReason: 'x'.repeat(501) }, '2026-10-28')).toBe(
      'm12.why.reason',
    );
    expect(proposalBlocked({ ...base, blocked: true, blockedReason: 'lé' }, '2026-10-28')).toBe(
      'm12.why.reason',
    );
    expect(
      proposalBlocked({ ...base, blocked: true, blockedReason: 'Layout awaited' }, '2026-10-28'),
    ).toBeNull();
  });
});

describe('M-12 Report Definition Model (SRS-18-9, US-GAP-34)', () => {
  const base: VersionProposal = {
    layoutFormat: 'EXCEL',
    periodLockRequired: true,
    storeMode: 'STORE',
    blocked: false,
    blockedReason: null,
    effectiveFrom: '2026-11-01',
  };
  const field = { label: 'Basic', binding: 'COMPONENT' as const, reference: '101', formatMask: null };
  const control = {
    label: 'Gross',
    reconcilesAgainst: 'POSTED_PAYROLL',
    tolerance: '0.50',
    releaseGate: true,
  };
  const parameter = {
    code: 'YYMM',
    label: null,
    dataType: 'MONTH',
    mandatory: true,
    roleScoped: false,
    defaultExpression: null,
    validationRule: 'A locked month',
  };

  it('SRS-18-9: the DDL vocabularies of bindings, reconciliations, consumers and channels', () => {
    expect(BINDINGS).toEqual(['COMPONENT', 'FIELD_SOURCE', 'AGGREGATE', 'LITERAL', 'DERIVED']);
    expect(RECONCILES).toEqual(['POSTED_PAYROLL', 'GL_POSTING_STATEMENT', 'BANK_ADVICE', 'CHALLAN']);
    expect(CONSUMERS).toEqual(['INTERNAL_ROLE', 'ORG_UNIT', 'EXTERNAL_AUTHORITY', 'MEMBER']);
    expect(CHANNELS).toEqual(['SCREEN', 'EMAIL', 'FILE_DROP', 'PORTAL', 'PRINT']);
  });

  it('SRS-18-9: a version without a model, or with a whole one, may be sent', () => {
    expect(modelBlocked(base)).toBeNull();
    expect(
      modelBlocked({
        ...base,
        parameters: [parameter],
        fields: [field],
        controls: [control],
        distribution: [
          { consumerName: 'Finance', consumerType: 'INTERNAL_ROLE', channel: 'SCREEN', schedule: null },
        ],
      }),
    ).toBeNull();
    expect(proposalBlocked({ ...base, fields: [{ ...field, reference: ' ' }] }, '2026-10-28')).toBe(
      'm12.why.field',
    );
  });

  it('SRS-18-9: each part needs what makes it whole', () => {
    expect(modelBlocked({ ...base, parameters: [{ ...parameter, code: ' ' }] })).toBe('m12.why.parameter');
    expect(modelBlocked({ ...base, parameters: [{ ...parameter, dataType: '' }] })).toBe('m12.why.parameter');
    expect(modelBlocked({ ...base, parameters: [{ ...parameter, validationRule: ' ' }] })).toBe(
      'm12.why.parameter',
    );
    expect(modelBlocked({ ...base, fields: [{ ...field, label: '' }] })).toBe('m12.why.field');
    expect(modelBlocked({ ...base, fields: [field], controls: [{ ...control, label: ' ' }] })).toBe(
      'm12.why.control',
    );
    for (const tolerance of ['-1', '1.234', 'x', '', '1234567890123']) {
      expect(modelBlocked({ ...base, fields: [field], controls: [{ ...control, tolerance }] })).toBe(
        'm12.why.control',
      );
    }
    expect(modelBlocked({ ...base, fields: [field], controls: [{ ...control, tolerance: 0 }] })).toBeNull();
    expect(
      modelBlocked({
        ...base,
        distribution: [{ consumerName: ' ', consumerType: 'MEMBER', channel: 'PORTAL', schedule: null }],
      }),
    ).toBe('m12.why.consumer');
  });

  it('SRS-18-9 / SRS-18-8: a control total needs an amount field to be computed from', () => {
    expect(modelBlocked({ ...base, controls: [control] })).toBe('m12.why.computable');
    expect(
      modelBlocked({
        ...base,
        fields: [{ ...field, binding: 'LITERAL', reference: 'x' }],
        controls: [control],
      }),
    ).toBe('m12.why.computable');
    for (const binding of ['COMPONENT', 'AGGREGATE', 'DERIVED'] as const) {
      expect(modelBlocked({ ...base, fields: [{ ...field, binding }], controls: [control] })).toBeNull();
    }
    expect(
      modelBlocked({ ...base, fields: [{ ...field, binding: 'FIELD_SOURCE' }], controls: [control] }),
    ).toBe('m12.why.computable');
  });

  it('SRS-18-9: the tolerances are sent as numbers', () => {
    expect(toVersionRequest({ ...base, controls: [{ ...control, tolerance: ' 2.5 ' }] }).controls).toEqual([
      { ...control, tolerance: 2.5 },
    ]);
    expect(toVersionRequest(base).controls).toEqual([]);
  });
});

describe('report rules, boundaries', () => {
  const base: VersionProposal = {
    layoutFormat: 'EXCEL',
    periodLockRequired: true,
    storeMode: 'STORE',
    blocked: false,
    blockedReason: null,
    effectiveFrom: '2026-11-01',
  };
  const field = { label: 'Basic', binding: 'COMPONENT' as const, reference: '101', formatMask: null };
  const control = {
    label: 'Gross',
    reconcilesAgainst: 'POSTED_PAYROLL',
    tolerance: '0.50',
    releaseGate: true,
  };
  const parameter = {
    code: 'YYMM',
    label: null,
    dataType: 'MONTH',
    mandatory: true,
    roleScoped: false,
    defaultExpression: null,
    validationRule: 'A locked month',
  };

  it('SRS-18-28: a month is exactly six digits with a month of 01 to 12', () => {
    expect(isYymm('202601')).toBe(true);
    expect(isYymm('202612')).toBe(true);
    expect(isYymm('20261')).toBe(false);
    expect(isYymm('2026011')).toBe(false);
    expect(isYymm('x000001')).toBe(false);
    expect(isYymm('')).toBe(false);
  });

  it('SRS-18-10: the effective date is a whole ISO date', () => {
    expect(proposalBlocked({ ...base, effectiveFrom: 'x2026-10-28' }, '2026-10-28')).toBe(
      'm12.why.effective',
    );
    expect(proposalBlocked({ ...base, effectiveFrom: '2026-10-28x' }, '2026-10-28')).toBe(
      'm12.why.effective',
    );
  });

  it('SRS-18-10: a blocked reason is measured trimmed, 1 to 500 characters', () => {
    const blocked = { ...base, blocked: true };
    expect(proposalBlocked({ ...blocked, blockedReason: '   ' }, '2026-10-28')).toBe('m12.why.reason');
    expect(proposalBlocked({ ...blocked, blockedReason: 'x'.repeat(500) }, '2026-10-28')).toBeNull();
    expect(proposalBlocked({ ...blocked, blockedReason: ` ${'x'.repeat(500)} ` }, '2026-10-28')).toBeNull();
    expect(proposalBlocked({ ...base, blockedReason: '   ' }, '2026-10-28')).toBeNull();
  });

  it('SRS-18-9: a rounding rule key is a capital then up to 99 capitals, digits, _ or .', () => {
    for (const key of ['R', 'ROUND_HALF_UP', 'TAX.R10', `R${'A'.repeat(99)}`, '']) {
      expect(modelBlocked({ ...base, roundingRuleKey: key })).toBeNull();
    }
    for (const key of ['round', '1R', 'R-', 'xR', 'R x', `R${'A'.repeat(100)}`, '_R']) {
      expect(modelBlocked({ ...base, roundingRuleKey: key })).toBe('m12.why.rounding');
    }
  });

  it('SRS-18-9: a data type is a capital then up to 19 capitals, digits or _', () => {
    for (const dataType of ['M', 'MONTH', 'DATE_2', `D${'A'.repeat(19)}`]) {
      expect(modelBlocked({ ...base, parameters: [{ ...parameter, dataType }] })).toBeNull();
    }
    for (const dataType of ['month', 'mMONTH', 'MONTH-', 'MON TH', `D${'A'.repeat(20)}`, ' MONTH ']) {
      expect(modelBlocked({ ...base, parameters: [{ ...parameter, dataType }] })).toBe('m12.why.parameter');
    }
  });

  it('SRS-18-9: a field label of blanks is missing', () => {
    expect(modelBlocked({ ...base, fields: [{ ...field, label: '  ' }] })).toBe('m12.why.field');
  });

  it('SRS-18-9: a tolerance is trimmed, up to twelve whole digits and two decimals', () => {
    const tol = (tolerance: string) =>
      modelBlocked({ ...base, fields: [field], controls: [{ ...control, tolerance }] });
    for (const t of [' 2.5 ', '10', '123456789012', '0.01', '1.5']) expect(tol(t)).toBeNull();
    for (const t of ['1.2.3', '1.', '.5', '1.x']) expect(tol(t)).toBe('m12.why.control');
  });

  it('SRS-18-8: why the O-04 export waits: the month, a load error, loading, else the totals', () => {
    const s = (isPending: boolean, isError: boolean) => ({ isPending, isError });
    expect(exportBlockedWhy(false, s(true, true))).toBe('o04.why.month');
    expect(exportBlockedWhy(true, s(true, true))).toBe('o04.why.error');
    expect(exportBlockedWhy(true, s(false, true))).toBe('o04.why.error');
    expect(exportBlockedWhy(true, s(true, false))).toBe('o04.why.loading');
    expect(exportBlockedWhy(true, s(false, false))).toBe('o04.why.mismatch');
  });
});
