import { describe, expect, it } from 'vitest';
import {
  EMPTY_BAND,
  MAX_BANDS,
  NO_FILTER,
  RULE_STATUS_PILL,
  appendBand,
  canAddBand,
  draftProblems,
  emptyDraft,
  fitsAmount,
  hasPendingChange,
  inRegister,
  isRetrospective,
  matches,
  mayEnd,
  mayPropose,
  notConfigured,
  proposalAction,
  registerSummary,
  toProposal,
  type VersionDraft,
} from '@/modules/configuration/business/ruleRules';
import { RULES, TODAY, rule, version } from './support/ruleFixtures';

/** M-08 rules (SRS-19-1, SRS-19-4 … 6, SRS-19-11, SRS-19-12, SRS-19-16, SRS-19-21): pure functions of the catalogue form. */

describe('M-08 rules', () => {
  const can = (held: string[]) => (action: string) => held.includes(action);

  it('SRS-19-11: a rule in use with no version in force is not configured; a pending change is either kind', () => {
    expect(notConfigured(rule({ inForce: null }))).toBe(true);
    expect(notConfigured(rule({ inForce: null, active: false }))).toBe(false);
    expect(notConfigured(rule())).toBe(false);
    expect(hasPendingChange(rule())).toBe(false);
    expect(hasPendingChange(rule({ pending: version() }))).toBe(true);
    expect(hasPendingChange(rule({ pendingEnd: RULES[4]?.pendingEnd ?? null }))).toBe(true);
  });

  // OPEN-QUESTION CLR-127 (BMAD TR-013, KB CF-28): SRS-19-11 gives four statuses; APPROVED_FUTURE and
  // REJECTED keep their pills as built until FCI gives their display within the four.
  it('SRS-19-11: each derived status has its pill: in force, approved for later, waiting, refused; ended or draft neutral', () => {
    expect(RULE_STATUS_PILL).toEqual({
      ACTIVE: 'p-ok',
      APPROVED_FUTURE: 'p-info',
      PENDING_APPROVAL: 'p-warn',
      SUPERSEDED: 'p-gray',
      REJECTED: 'p-bad',
      DRAFT: 'p-gray',
    });
  });

  it('SRS-19-21: no filter is the empty choice of every control', () => {
    expect(NO_FILTER).toEqual({ text: '', type: '', domain: '', status: '' });
  });

  it('SRS-19-21: the catalogue filters by key or name, type, domain and status', () => {
    const keys = (f: Partial<typeof NO_FILTER>) =>
      RULES.filter((r) => matches(r, { ...NO_FILTER, ...f })).map((r) => r.key);
    expect(keys({})).toHaveLength(RULES.length);
    expect(keys({ text: ' da.ida ' })).toEqual(['DA.IDA.RATE']);
    expect(keys({ text: 'sanction LIMIT' })).toEqual(['CPF.SANCTION.LIMIT']);
    expect(keys({ type: 'SLAB' })).toEqual(['INCENTIVE.SLAB']);
    expect(keys({ domain: 'STATUTORY_OUTPUT' })).toEqual(['FORM16.DEFINITION']);
    expect(keys({ status: 'ACTIVE' })).not.toContain('CPF.SANCTION.LIMIT');
    expect(keys({ status: 'NOT_CONFIGURED' })).toEqual(['CPF.SANCTION.LIMIT']);
    expect(keys({ status: 'PENDING' })).toEqual(['TA.FORMULA', 'LWF.348.APPLICABILITY']);
    expect(keys({ status: 'READ_ONLY' })).toEqual(['FORM16.DEFINITION', 'OLD.RATE']);
    expect(keys({ type: 'RATE', status: 'ACTIVE' })).toEqual(['DA.IDA.RATE']);
  });

  it('SRS-19-4: a first version needs CREATE, a later one EDIT; nothing is proposed while a change is pending or off M-08', () => {
    expect(proposalAction(rule({ versions: 0 }))).toBe('CREATE');
    expect(proposalAction(rule({ versions: 1 }))).toBe('EDIT');
    expect(mayPropose(rule({ versions: 0 }), can(['CREATE']))).toBe(true);
    expect(mayPropose(rule({ versions: 0 }), can(['EDIT']))).toBe(false);
    expect(mayPropose(rule(), can(['EDIT']))).toBe(true);
    expect(mayPropose(rule(), can(['CREATE']))).toBe(false);
    expect(mayPropose(rule({ pending: version() }), can(['EDIT']))).toBe(false);
    expect(mayPropose(rule({ maintained: false }), can(['CREATE', 'EDIT']))).toBe(false);
    // SRS-19-6: an end needs a version to end, EDIT, and nothing pending.
    expect(mayEnd(rule(), can(['EDIT']))).toBe(true);
    expect(mayEnd(rule({ versions: 0 }), can(['EDIT']))).toBe(false);
    expect(mayEnd(rule(), can(['CREATE']))).toBe(false);
    expect(mayEnd(rule({ pendingEnd: RULES[4]?.pendingEnd ?? null }), can(['EDIT']))).toBe(false);
    expect(mayEnd(rule({ maintained: false }), can(['EDIT']))).toBe(false);
  });

  it('SRS-19-16: a start before today is retrospective; today or later is not', () => {
    expect(isRetrospective('2026-10-22', TODAY)).toBe(true);
    expect(isRetrospective(TODAY, TODAY)).toBe(false);
    expect(isRetrospective('2026-10-24', TODAY)).toBe(false);
    expect(isRetrospective('', TODAY)).toBe(false);
  });

  it.each([
    ['0', true],
    ['12.5', true],
    ['12.50', true],
    [' 9999999999.99 ', true],
    ['-9999999999.99', true],
    ['12.345', false],
    ['10000000000', false],
    ['1.', false],
    ['.5', false],
    ['1.2.3', false],
    ['1e5', false],
    ['--1', false],
    ['', false],
    ['abc', false],
  ])('SRS-19-1: %s fits Numeric (12,2): %s', (text, fits) => {
    expect(fitsAmount(text)).toBe(fits);
  });

  const draft = (over: Partial<VersionDraft> = {}): VersionDraft => ({
    ...emptyDraft(TODAY),
    circularId: 7,
    reason: 'Order 3',
    ...over,
  });
  const bandOf = (from: string, to: string, rateOrAmount: string, step = '', qualifier = '') => ({
    from,
    to,
    rateOrAmount,
    step,
    qualifier,
  });

  it('SRS-19-12: a new version starts on the given day, open-ended, with one blank band and nothing else filled in', () => {
    expect(EMPTY_BAND).toEqual(bandOf('', '', ''));
    expect(emptyDraft(TODAY)).toEqual({
      effectiveFrom: TODAY,
      effectiveTo: '',
      value: '',
      formula: '',
      bands: [bandOf('', '', '')],
      roundingRuleKey: '',
      circularId: null,
      reason: '',
    });
    // Each draft holds its own band: typing into it never changes the template.
    expect(emptyDraft(TODAY).bands[0]).not.toBe(EMPTY_BAND);
  });

  it('SRS-19-5: bands can be added up to the API limit of 200 per version', () => {
    const bands = (n: number) => Array.from({ length: n }, () => ({ ...EMPTY_BAND }));
    expect(MAX_BANDS).toBe(200);
    expect(canAddBand([])).toBe(true);
    expect(canAddBand(bands(199))).toBe(true);
    expect(canAddBand(bands(200))).toBe(false);
  });

  it('SRS-19-12: text of spaces only is no value, formula, band start or rate; an end of spaces leaves the band open', () => {
    expect(draftProblems('SCALAR', draft({ value: '   ' }))).toEqual(['m08.need.value']);
    expect(draftProblems('NUMBER_FORMULA', draft({ formula: '   ' }))).toEqual(['m08.need.formula']);
    expect(draftProblems('SLAB', draft({ bands: [bandOf('  ', '100', '1')] }))).toEqual(['m08.need.bands']);
    expect(draftProblems('SLAB', draft({ bands: [bandOf('0', '100', '  ')] }))).toEqual(['m08.need.bands']);
    expect(draftProblems('SLAB', draft({ bands: [bandOf('0', '  ', '1')] }))).toEqual([]);
    expect(draftProblems('SLAB', draft({ bands: [bandOf('100', '  ', '1', '  ')] }))).toEqual([]);
  });

  it('SRS-19-12: the form names what is missing for each shape', () => {
    expect(draftProblems('SCALAR', draft({ value: '53' }))).toEqual([]);
    expect(draftProblems('SCALAR', draft())).toEqual(['m08.need.value']);
    expect(draftProblems('SCALAR', draft({ value: '5.125' }))).toEqual(['m08.need.amount']);
    expect(
      draftProblems('SCALAR', draft({ value: '1', effectiveFrom: '', circularId: null, reason: ' ' })),
    ).toEqual(['m08.need.from', 'm08.need.circular', 'm08.need.reason']);
    expect(draftProblems('SCALAR', draft({ value: '1', effectiveTo: '2026-10-22' }))).toEqual([
      'm08.need.toAfterFrom',
    ]);
    expect(draftProblems('SCALAR', draft({ value: '1', effectiveTo: TODAY }))).toEqual([]);
    expect(draftProblems('NUMBER_FORMULA', draft())).toEqual(['m08.need.formula']);
    expect(draftProblems('CONDITION_FORMULA', draft({ formula: 'BASIC >= 1' }))).toEqual([]);
    expect(draftProblems('NUMBER_FORMULA', draft({ formula: 'मूल * 2' }))).toEqual(['m08.need.ascii']);
    expect(draftProblems('SCALAR', draft({ value: '1', reason: 'आदेश' }))).toEqual(['m08.need.ascii']);
  });

  it('SRS-19-5: each band needs its from and rate, numbers that fit, and a to above its from', () => {
    const band = (from: string, to: string, rate: string, step = '') => ({
      from,
      to,
      rateOrAmount: rate,
      step,
      qualifier: '',
    });
    expect(draftProblems('SLAB', draft({ bands: [band('0', '100', '1'), band('100', '', '2')] }))).toEqual(
      [],
    );
    expect(draftProblems('SLAB', draft({ bands: [] }))).toEqual(['m08.need.bands']);
    expect(draftProblems('SLAB', draft({ bands: [band('', '100', '1')] }))).toEqual(['m08.need.bands']);
    expect(draftProblems('SLAB', draft({ bands: [band('0', '100', '')] }))).toEqual(['m08.need.bands']);
    expect(draftProblems('SLAB', draft({ bands: [band('0', '100', '1', '0.125')] }))).toEqual([
      'm08.need.amount',
    ]);
    expect(draftProblems('SLAB', draft({ bands: [band('100', '100', '1')] }))).toEqual([
      'm08.need.bandRange',
    ]);
    expect(draftProblems('SLAB', draft({ bands: [band('100', '50', '1')] }))).toEqual(['m08.need.bandRange']);
  });

  it('SRS-19-5: a new band starts where the last one ends; a first band starts blank', () => {
    const first = { from: '0', to: '1000', rateOrAmount: '1', step: '', qualifier: '' };
    expect(appendBand([first])).toEqual([
      first,
      { from: '1000', to: '', rateOrAmount: '', step: '', qualifier: '' },
    ]);
    expect(appendBand([])).toEqual([{ from: '', to: '', rateOrAmount: '', step: '', qualifier: '' }]);
  });

  it('SRS-19-1: the proposal carries only the part its shape takes', () => {
    const d = draft({
      effectiveTo: '',
      value: ' 53.5 ',
      formula: ' BASIC * 2 ',
      bands: [
        { from: '0', to: '100', rateOrAmount: '1', step: '', qualifier: ' city-x ' },
        { from: '100', to: '', rateOrAmount: '2', step: '5', qualifier: '' },
      ],
      roundingRuleKey: '',
    });
    expect(toProposal('SCALAR', d)).toEqual({
      effectiveFrom: TODAY,
      effectiveTo: null,
      value: 53.5,
      formula: null,
      bands: null,
      roundingRuleKey: null,
      circularId: 7,
      reason: 'Order 3',
    });
    expect(toProposal('NUMBER_FORMULA', { ...d, roundingRuleKey: 'COMPONENT.ROUNDING' })).toMatchObject({
      value: null,
      formula: 'BASIC * 2',
      bands: null,
      roundingRuleKey: 'COMPONENT.ROUNDING',
    });
    expect(toProposal('CONDITION_FORMULA', d).formula).toBe('BASIC * 2');
    // Text is sent trimmed; an end, step or qualifier of spaces only is none.
    expect(
      toProposal(
        'SLAB',
        draft({ reason: '  Order 3  ', bands: [bandOf(' 0 ', '  ', ' 1.5 ', '  ', '   ')] }),
      ),
    ).toMatchObject({
      reason: 'Order 3',
      bands: [{ order: 1, from: 0, to: null, rateOrAmount: 1.5, step: null, qualifier: null }],
    });
    expect(toProposal('SLAB', { ...d, effectiveTo: '2027-03-31' })).toMatchObject({
      effectiveTo: '2027-03-31',
      value: null,
      formula: null,
      bands: [
        { order: 1, from: 0, to: 100, rateOrAmount: 1, step: null, qualifier: 'CITY-X' },
        { order: 2, from: 100, to: null, rateOrAmount: 2, step: 5, qualifier: null },
      ],
    });
  });
});

describe('M-18 / M-19 registers (SRS-6-47, SRS M-19)', () => {
  it('SRS-6-47: a register shows only the rules its screen owns; without one, the whole catalogue', () => {
    const m18 = rule({ key: 'CPF.EPS.RATE', owningModule: 'M-18' });
    const m19 = rule({ key: 'CPF.VPF.MAX', owningModule: 'M-19' });
    const none = rule({ owningModule: null });
    expect(inRegister(m18, 'M-18')).toBe(true);
    expect(inRegister(m19, 'M-18')).toBe(false);
    expect(inRegister(none, 'M-18')).toBe(false);
    expect([m18, m19, none].every((r) => inRegister(r, undefined))).toBe(true);
  });

  it('SRS-6-47: the tiles of a register count its own rules, pending changes and rules not configured', () => {
    const rows = [
      rule({ key: 'A', inForce: null }),
      rule({ key: 'B', pending: version({ status: 'PENDING_APPROVAL' }) }),
      rule({
        key: 'C',
        pendingEnd: {
          approvalId: 1,
          versionNo: 1,
          endOn: '2026-12-31',
          requestedBy: 'x',
          requestedAt: '2026-10-01T00:00:00Z',
        },
      }),
      rule({ key: 'D', active: false, inForce: null }),
    ];
    expect(registerSummary(rows)).toEqual({ rules: 4, pending: 2, notConfigured: 1 });
    expect(registerSummary([])).toEqual({ rules: 0, pending: 0, notConfigured: 0 });
  });
});
