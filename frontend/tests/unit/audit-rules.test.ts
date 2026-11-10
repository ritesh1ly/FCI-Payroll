import { describe, expect, it } from 'vitest';
import { businessToday } from '@/shared/time';
import { auditQuery, defaultAuditFilter, isoDay, rangeValid } from '@/modules/audit/business/auditFilter';
import {
  fieldRows,
  outcomeClass,
  personLabel,
  snapshotPairs,
  validRecordKey,
} from '@/modules/audit/business/recordHistory';
import {
  isPrintableAscii,
  MAX_RETENTION_MONTHS,
  validRetention,
} from '@/modules/configuration/business/retention';

describe('X-04 presentation rules', () => {
  it('SRS-G-2: each FAP-13 outcome has its colour; an unknown decision kind is neutral', () => {
    expect(outcomeClass('APPROVED')).toBe('pill p-ok');
    expect(outcomeClass('POSTED')).toBe('pill p-ok');
    expect(outcomeClass('SUBMITTED')).toBe('pill p-info');
    expect(outcomeClass('RETURNED')).toBe('pill p-warn');
    expect(outcomeClass('REJECTED')).toBe('pill p-bad');
    expect(outcomeClass('FAILED')).toBe('pill p-bad');
    expect(outcomeClass('WITHDRAWN')).toBe('pill p-gray');
  });

  it('SC-03-0027: maker and checker as name with Employee No., the login id without a name, a dash for nobody', () => {
    expect(personLabel({ loginId: 'a@x', name: 'Asha Rao', employeeNumber: '104882' })).toBe(
      'Asha Rao (104882)',
    );
    expect(personLabel({ loginId: 'a@x', name: 'Asha Rao', employeeNumber: null })).toBe('Asha Rao');
    expect(personLabel({ loginId: 'system', name: null, employeeNumber: null })).toBe('system');
    expect(personLabel(null)).toBe('—');
    expect(personLabel(undefined)).toBe('—');
  });

  it('SC-03-0039: a legacy row image is shown whole as held - pairs for a JSON object, else the text as held', () => {
    expect(snapshotPairs('{"EMP_NUM":"104882","BASIC":49500,"NOTE":null}')).toEqual([
      'EMP_NUM: 104882',
      'BASIC: 49500',
      'NOTE: —',
    ]);
    expect(snapshotPairs('EMP_NUM=104882;BASIC=49500')).toEqual(['EMP_NUM=104882;BASIC=49500']);
    expect(snapshotPairs('[1,2]')).toEqual(['[1,2]']);
    expect(snapshotPairs('null')).toEqual(['null']);
    // A JSON number, text or flag is not an object: shown as held, never split into characters.
    expect(snapshotPairs('49500')).toEqual(['49500']);
    expect(snapshotPairs('"ACTIVE"')).toEqual(['"ACTIVE"']);
    expect(snapshotPairs('true')).toEqual(['true']);
    expect(snapshotPairs('{}')).toEqual([]);
    expect(snapshotPairs(null)).toEqual([]);
    expect(snapshotPairs('')).toEqual([]);
  });

  it('fields keep the server order and an empty value reads as a dash', () => {
    expect(fieldRows({ b: '2', a: null })).toEqual([
      ['b', '2'],
      ['a', '—'],
    ]);
  });

  it('SC-03-0016: the record key is required, trimmed, at most 100 characters', () => {
    expect(validRecordKey('104882')).toBe(true);
    expect(validRecordKey('  ISSUING_AUTHORITY.ZO  ')).toBe(true);
    expect(validRecordKey('')).toBe(false);
    expect(validRecordKey('   ')).toBe(false);
    expect(validRecordKey('x'.repeat(100))).toBe(true);
    expect(validRecordKey('x'.repeat(101))).toBe(false);
  });
});

describe('SRS-20-5 retention period rules', () => {
  const ok = { months: '120', indefinite: false, basis: 'FCI order 12/2026', reason: 'Policy' };

  it('a number of months or indefinite - not both, not neither', () => {
    expect(validRetention(ok)).toBe(true);
    expect(validRetention({ ...ok, months: '', indefinite: true })).toBe(true);
    expect(validRetention({ ...ok, indefinite: true })).toBe(false);
    expect(validRetention({ ...ok, months: '' })).toBe(false);
    expect(validRetention({ ...ok, months: '   ' })).toBe(false);
  });

  it('months are whole, from 1 to the column limit', () => {
    expect(validRetention({ ...ok, months: '1' })).toBe(true);
    expect(validRetention({ ...ok, months: ' 84 ' })).toBe(true);
    expect(validRetention({ ...ok, months: String(MAX_RETENTION_MONTHS) })).toBe(true);
    expect(validRetention({ ...ok, months: '0' })).toBe(false);
    expect(validRetention({ ...ok, months: String(MAX_RETENTION_MONTHS + 1) })).toBe(false);
    expect(validRetention({ ...ok, months: '12.5' })).toBe(false);
    expect(validRetention({ ...ok, months: '-3' })).toBe(false);
  });

  it('R-ASCII: the instrument is printable ASCII, 1 to 200 characters; a reason is given', () => {
    expect(validRetention({ ...ok, basis: '' })).toBe(false);
    expect(validRetention({ ...ok, basis: '   ' })).toBe(false);
    // The limit applies to the instrument as typed without the spaces around it.
    expect(validRetention({ ...ok, basis: ` ${'x'.repeat(200)} ` })).toBe(true);
    expect(validRetention({ ...ok, basis: 'x'.repeat(200) })).toBe(true);
    expect(validRetention({ ...ok, basis: 'x'.repeat(201) })).toBe(false);
    expect(validRetention({ ...ok, basis: 'आदेश' })).toBe(false);
    expect(validRetention({ ...ok, reason: ' ' })).toBe(false);
    expect(isPrintableAscii('Order 12/2026 ~ ok')).toBe(true);
    expect(isPrintableAscii('tab\there')).toBe(false);
    expect(isPrintableAscii('')).toBe(true);
  });
});

describe('A-05 audit trail search filter', () => {
  // 23 Oct 2026, 23:30 in India = 18:00 UTC.
  const NOW = Date.parse('2026-10-23T18:00:00Z');

  it('SRS-G-13: days are India business days, across midnight IST, month, year and leap-day boundaries', () => {
    expect(isoDay(0, NOW)).toBe('2026-10-23');
    expect(isoDay(-1, NOW)).toBe('2026-10-22');
    expect(isoDay(1, NOW)).toBe('2026-10-24');
    // 18:30 UTC is midnight in India: the business day has turned while the UTC day has not.
    expect(isoDay(0, Date.parse('2026-10-23T18:30:00Z'))).toBe('2026-10-24');
    expect(isoDay(-1, Date.parse('2026-01-01T00:30:00Z'))).toBe('2025-12-31');
    expect(isoDay(1, Date.parse('2028-02-28T12:00:00Z'))).toBe('2028-02-29');
    expect(isoDay(1, Date.parse('2027-02-28T12:00:00Z'))).toBe('2027-03-01');
    expect(isoDay(0)).toBe(businessToday());
  });

  it('SRS-G-13: the search opens on yesterday and today, both included, with no other criterion', () => {
    expect(defaultAuditFilter(NOW)).toEqual({
      from: '2026-10-22',
      to: '2026-10-23',
      entity: '',
      actor: '',
      field: '',
      objectGroup: '',
      userGroup: '',
      q: '',
    });
    expect(defaultAuditFilter().to).toBe(businessToday());
  });

  it('SRS-G-13: one day is a range; To before From, or a missing date, is not', () => {
    expect(rangeValid({ from: '2026-10-23', to: '2026-10-23' })).toBe(true);
    expect(rangeValid({ from: '2026-10-01', to: '2026-10-31' })).toBe(true);
    expect(rangeValid({ from: '2026-10-24', to: '2026-10-23' })).toBe(false);
    expect(rangeValid({ from: '', to: '2026-10-23' })).toBe(false);
    expect(rangeValid({ from: '2026-10-23', to: '' })).toBe(false);
  });

  it('SRS-G-13: the query sends midnight of From to the midnight ending To, India time, as UTC instants', () => {
    const base = { from: '2026-10-01', to: '2026-10-31', entity: '', actor: '', field: '' };
    expect(auditQuery(base)).toBe('from=2026-09-30T18%3A30%3A00.000Z&to=2026-10-31T18%3A30%3A00.000Z');
    // One day: its own 24 hours in India.
    const oneDay = new URLSearchParams(auditQuery({ ...base, from: '2026-10-23', to: '2026-10-23' }));
    expect(oneDay.get('from')).toBe('2026-10-22T18:30:00.000Z');
    expect(oneDay.get('to')).toBe('2026-10-23T18:30:00.000Z');
    const params = new URLSearchParams(
      auditQuery({ ...base, entity: 'rate_set', actor: 'maker.delhi@fci-demo.test', field: 'basic pay' }),
    );
    expect([...params.keys()]).toEqual(['from', 'to', 'entity', 'actor', 'field']);
    expect(params.get('entity')).toBe('rate_set');
    expect(params.get('actor')).toBe('maker.delhi@fci-demo.test');
    expect(params.get('field')).toBe('basic pay');
    // Each criterion is independent: one given, the others left out.
    expect([...new URLSearchParams(auditQuery({ ...base, actor: 'checker' })).keys()]).toEqual([
      'from',
      'to',
      'actor',
    ]);
    expect([...new URLSearchParams(auditQuery({ ...base, field: 'pan' })).keys()]).toEqual([
      'from',
      'to',
      'field',
    ]);
  });
});
