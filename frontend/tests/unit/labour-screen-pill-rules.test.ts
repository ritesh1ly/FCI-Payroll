import { describe, expect, it } from 'vitest';
import type { DayRate, RateSet } from '@/modules/labour/api/labourApi';
import type { GangLine, MemberLine } from '@/modules/labour/api/labourScreenApi';
import {
  asorEntryReady,
  byRole,
  figure,
  gangPill,
  memberPill,
  normEntryReady,
  rangeText,
  rupees,
  setInForce,
  versionPill,
  type AsorEntry,
  type NormEntry,
} from '@/modules/labour/business/labourScreenRules';

/**
 * Pills and figures of the labour master screens as drawn (wireframe v6.1, OD-121): M-02 member status,
 * M-09 gang status (BR-LABM-001 / BR-LABM-006), the SC-19 version vocabulary of M-05 / M-06, the set in
 * force, the drawn designation order and the mandatory fields of the M-06 / NWNP ASOR entry forms.
 */

const TODAY = '2026-11-08';

function member(over: Partial<MemberLine> = {}): MemberLine {
  return {
    employeeId: 1,
    labourCode: 'L0001',
    name: 'Ram Kumar',
    payrollType: 'DPS_LABOUR',
    designation: 'Handling Labour',
    gangRole: 'HANDLING',
    gang: 'G-01',
    depot: 'FSD Test',
    aadhaarMasked: 'XXXX-XXXX-1234',
    basicWage: 500,
    cpfCode: 'CPF0001',
    status: 'ACTIVE',
    transferPending: false,
    ...over,
  };
}

function gang(over: Partial<GangLine> = {}): GangLine {
  return {
    gangId: 1,
    gangCode: 'G-01',
    gangName: 'Gang One',
    payrollType: 'DPS_LABOUR',
    gangType: 'HANDLING',
    status: 'ACTIVE',
    depot: 'FSD Test',
    shed: null,
    sardar: 'Sardar One',
    mandal: 'Mandal One',
    labourCount: 10,
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    endBlocked: false,
    pending: false,
    ...over,
  };
}

function set(over: Partial<RateSet> = {}): RateSet {
  return {
    id: 1,
    payrollType: 'DPS_LABOUR',
    depotId: null,
    depot: null,
    versionNo: 1,
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    interimRelief: null,
    goiRate: null,
    goiRateSource: null,
    comparisonGrain: null,
    approvalState: 'APPROVED',
    ...over,
  } as RateSet;
}

function rate(gangRole: string | null, id: number): DayRate {
  return { designationId: id, designation: `D${id}`, gangRole, mgw: 100, basicWage: null, da: null };
}

describe('M-02 member pill', () => {
  it('M-02: a transfer waiting for the Checker shows first, even on an inactive record without CPF', () => {
    expect(memberPill(member({ transferPending: true, status: 'SEPARATED', cpfCode: null }))).toEqual({
      key: 'm02w.state.TRANSFER',
      tone: 'p-warn',
    });
  });

  it('M-02: a record not ACTIVE shows its labour_status in grey before any missing identifier', () => {
    expect(memberPill(member({ status: 'SEPARATED', cpfCode: null, aadhaarMasked: null }))).toEqual({
      key: 'labour.status.SEPARATED',
      tone: 'p-gray',
    });
  });

  it('M-02: an active record without Aadhaar and CPF code, then without CPF code only', () => {
    expect(memberPill(member({ cpfCode: null, aadhaarMasked: null }))).toEqual({
      key: 'm02w.state.AADHAAR_CPF',
      tone: 'p-bad',
    });
    expect(memberPill(member({ cpfCode: null }))).toEqual({ key: 'm02w.state.CPF', tone: 'p-bad' });
  });

  it('M-02: a missing Aadhaar alone does not block an active record that has its CPF code', () => {
    expect(memberPill(member({ aadhaarMasked: null }))).toEqual({
      key: 'labour.status.ACTIVE',
      tone: 'p-ok',
    });
    expect(memberPill(member())).toEqual({ key: 'labour.status.ACTIVE', tone: 'p-ok' });
  });
});

describe('M-09 gang pill', () => {
  it('BR-LABM-006: a gang whose end is blocked shows it first, even when end-dated', () => {
    expect(gangPill(gang({ endBlocked: true, status: 'END_DATED', sardar: null }))).toEqual({
      key: 'm09w.state.END_BLOCKED',
      tone: 'p-bad',
    });
  });

  it('BR-LABM-006: an end-dated gang is grey and its leaders are no longer checked', () => {
    expect(gangPill(gang({ status: 'END_DATED', sardar: null, mandal: null }))).toEqual({
      key: 'm09.status.END_DATED',
      tone: 'p-gray',
    });
  });

  it('BR-LABM-001: a handling gang without its Sardar, then without its Mandal', () => {
    expect(gangPill(gang({ sardar: null, mandal: null }))).toEqual({
      key: 'm09w.state.NO_SARDAR',
      tone: 'p-bad',
    });
    expect(gangPill(gang({ sardar: null }))).toEqual({ key: 'm09w.state.NO_SARDAR', tone: 'p-bad' });
    expect(gangPill(gang({ mandal: null }))).toEqual({ key: 'm09w.state.NO_MANDAL', tone: 'p-bad' });
  });

  it('BR-LABM-001: an ancillary pool needs no Sardar or Mandal; a complete handling gang is active', () => {
    expect(gangPill(gang({ gangType: 'ANCILLARY_POOL', sardar: null, mandal: null }))).toEqual({
      key: 'm09.status.ACTIVE',
      tone: 'p-ok',
    });
    expect(gangPill(gang({ gangType: 'LEGACY_DUMMY_ANCILLARY', mandal: null }))).toEqual({
      key: 'm09.status.ACTIVE',
      tone: 'p-ok',
    });
    expect(gangPill(gang())).toEqual({ key: 'm09.status.ACTIVE', tone: 'p-ok' });
  });
});

describe('SC-19 version pill', () => {
  it('SC-19: pending and rejected versions whatever their dates', () => {
    expect(versionPill('PENDING_APPROVAL', '2020-01-01', TODAY)).toEqual({
      key: 'lw.state.PENDING',
      tone: 'p-warn',
    });
    expect(versionPill('REJECTED', '2020-01-01', TODAY)).toEqual({ key: 'lw.state.REJECTED', tone: 'p-bad' });
  });

  it('SC-19: an approved version ended before today is superseded; ending today or open it is active', () => {
    expect(versionPill('APPROVED', '2026-11-07', TODAY)).toEqual({
      key: 'lw.state.SUPERSEDED',
      tone: 'p-gray',
    });
    expect(versionPill('APPROVED', TODAY, TODAY)).toEqual({ key: 'lw.state.ACTIVE', tone: 'p-ok' });
    expect(versionPill('APPROVED', '2026-12-31', TODAY)).toEqual({ key: 'lw.state.ACTIVE', tone: 'p-ok' });
    expect(versionPill('APPROVED', null, TODAY)).toEqual({ key: 'lw.state.ACTIVE', tone: 'p-ok' });
  });
});

describe('M-05 set in force', () => {
  it('M-05: no sets, or none approved and begun, gives none', () => {
    expect(setInForce(undefined, TODAY)).toBeNull();
    expect(setInForce([], TODAY)).toBeNull();
    expect(
      setInForce(
        [
          set({ id: 1, approvalState: 'PENDING_APPROVAL' }),
          set({ id: 2, effectiveFrom: '2026-11-09' }),
          set({ id: 3, effectiveTo: '2026-11-07' }),
        ],
        TODAY,
      ),
    ).toBeNull();
  });

  it('M-05: a set begun today, or ending today, is in force; the first listed (newest) wins', () => {
    expect(setInForce([set({ id: 4, effectiveFrom: TODAY })], TODAY)?.id).toBe(4);
    expect(setInForce([set({ id: 5, effectiveTo: TODAY })], TODAY)?.id).toBe(5);
    expect(
      setInForce([set({ id: 6, approvalState: 'REJECTED' }), set({ id: 7 }), set({ id: 8 })], TODAY)?.id,
    ).toBe(7);
  });
});

describe('M-05 designation order', () => {
  it('M-05: Sardar, Mandal, Handling, Ancillary, then a designation without a known role', () => {
    const input = [
      rate(null, 5),
      rate('ANCILLARY', 4),
      rate('HANDLING', 3),
      rate('OTHER', 6),
      rate('MANDAL', 2),
      rate('SARDAR', 1),
    ];
    const sorted = byRole(input);
    expect(sorted.map((r) => r.designationId)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(input.map((r) => r.designationId)).toEqual([5, 4, 3, 6, 2, 1]);
  });

  it('M-05: an unknown role ranks after Ancillary, Sardar ranks first', () => {
    expect(byRole([rate('SARDAR', 1), rate(null, 2)]).map((r) => r.designationId)).toEqual([1, 2]);
    expect(byRole([rate(null, 2), rate('SARDAR', 1)]).map((r) => r.designationId)).toEqual([1, 2]);
    expect(byRole([rate(null, 2), rate('ANCILLARY', 1)]).map((r) => r.designationId)).toEqual([1, 2]);
    expect(byRole([])).toEqual([]);
  });
});

describe('figures as drawn', () => {
  it('M-05: rupees with Indian grouping, or the not-configured text; zero is a figure', () => {
    expect(rupees(123456.5, 'none')).toBe('₹ 1,23,456.5');
    expect(rupees(0, 'none')).toBe('₹ 0');
    expect(rupees(null, 'none')).toBe('none');
    expect(rupees(undefined, 'none')).toBe('none');
  });

  it('M-05: a table figure with Indian grouping, a dash when the record has none', () => {
    expect(figure(1234567)).toBe('12,34,567');
    expect(figure(0)).toBe('0');
    expect(figure(null)).toBe('—');
    expect(figure(undefined)).toBe('—');
  });

  it('M-06: the above-norm range reads "from – to" or "from and above"', () => {
    expect(rangeText(1, 30, 'and above')).toBe('1 – 30');
    expect(rangeText(91, null, 'and above')).toBe('91 and above');
    expect(rangeText(0, 0, 'x')).toBe('0 – 0');
  });
});

describe('M-06 / M-05 entry forms', () => {
  const norm: NormEntry = {
    depot: '1',
    mou: '2',
    mln: '3',
    mlsw: '4',
    height: '5',
    lead: '6',
    effective: '2026-12-01',
  };

  it('M-06: every drawn field of the New Depot Norm Set is mandatory; blanks count as empty', () => {
    expect(normEntryReady(norm)).toBe(true);
    for (const field of Object.keys(norm) as (keyof NormEntry)[]) {
      expect(normEntryReady({ ...norm, [field]: '' })).toBe(false);
      expect(normEntryReady({ ...norm, [field]: '   ' })).toBe(false);
    }
  });

  const asor: AsorEntry = {
    effective: '2026-12-01',
    piece: '10.5',
    sardar: '600',
    mandal: '550',
    handling: '500',
    ancillary: '0',
  };

  it('SRS-5-3: the New NWNP ASOR Rate Set needs its date and every amount, numeric and not negative', () => {
    expect(asorEntryReady(asor)).toBe(true);
    expect(asorEntryReady({ ...asor, effective: '' })).toBe(false);
    for (const field of ['piece', 'sardar', 'mandal', 'handling', 'ancillary'] as const) {
      expect(asorEntryReady({ ...asor, [field]: '' })).toBe(false);
      expect(asorEntryReady({ ...asor, [field]: '  ' })).toBe(false);
      expect(asorEntryReady({ ...asor, [field]: 'abc' })).toBe(false);
      expect(asorEntryReady({ ...asor, [field]: '-1' })).toBe(false);
      expect(asorEntryReady({ ...asor, [field]: '-0.01' })).toBe(false);
      expect(asorEntryReady({ ...asor, [field]: '0' })).toBe(true);
    }
  });
});
