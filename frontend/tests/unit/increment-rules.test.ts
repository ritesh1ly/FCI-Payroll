import { describe, expect, it } from 'vitest';
import type { CaseRequest, DueRow, VersionRequest } from '@/modules/increment/api/incrementApi';
import {
  BLOCKING,
  badStageRow,
  CASE_TYPES,
  caseReady,
  entersBasic,
  newScaleReady,
  parseStages,
  SCALE_TYPES,
  selectable,
  stagesOfRows,
  versionReady,
} from '@/modules/increment/business/incrementRules';

/** I-05 and M-03 screen rules (plan 17; SRS-13-5, SRS-13-18, SRS-13-21, SRS-13-23, SRS-13-24). */
describe('I-05 / M-03 increment rules', () => {
  const due = (over: Partial<DueRow> = {}) => ({ pending: false, flags: [], ...over }) as unknown as DueRow;

  it('SRS-13-21: a due member is selectable unless a blocking flag stands or it already waits', () => {
    expect(BLOCKING).toEqual(['BARRED', 'ABOVE_MAXIMUM', 'NOT_A_STAGE', 'NOT_CONFIGURED', 'NO_SCALE']);
    expect(selectable(due())).toBe(true);
    expect(selectable(due({ flags: ['STAGES_NOT_CONFIGURED'] }))).toBe(true);
    for (const flag of BLOCKING)
      expect(selectable(due({ flags: ['STAGES_NOT_CONFIGURED', flag] }))).toBe(false);
    expect(selectable(due({ pending: true }))).toBe(false);
  });

  it('SRS-13-18: only an individual revision, an additional increment and a Basic correction enter the Basic', () => {
    expect(CASE_TYPES).toHaveLength(8);
    expect(entersBasic('OBSERVATION_REFIXATION')).toBe(true);
    expect(entersBasic('REVERSE_ENTRY')).toBe(false);
    expect(entersBasic('INDIVIDUAL_REVISION')).toBe(true);
    expect(entersBasic('ADDITIONAL_INCREMENT')).toBe(true);
    expect(entersBasic('BASIC_CORRECTION')).toBe(true);
    expect(entersBasic('PROMOTION_FIXATION')).toBe(false);
    expect(entersBasic('STAGNATION_INCREMENT')).toBe(false);
    expect(entersBasic('DATE_CORRECTION')).toBe(false);
    expect(entersBasic(undefined)).toBe(false);
  });

  it('SRS-13-18 / 13-24: a case needs the member, type, date, order, authority and reason; a Basic or a corrected date when its type asks', () => {
    const ready: Partial<CaseRequest> = {
      eis: '104882',
      caseType: 'PROMOTION_FIXATION',
      effectiveDate: '2026-10-01',
      orderNumber: 'EST/1',
      orderDate: '2026-09-30',
      issuingAuthority: 'GM (P)',
      reason: 'Promotion order',
    };
    expect(caseReady(ready)).toBe(true);
    for (const field of [
      'eis',
      'effectiveDate',
      'orderNumber',
      'orderDate',
      'issuingAuthority',
      'reason',
    ] as const) {
      expect(caseReady({ ...ready, [field]: '  ' })).toBe(false);
      expect(caseReady({ ...ready, [field]: undefined })).toBe(false);
    }
    expect(caseReady({ ...ready, caseType: undefined })).toBe(false);
    const revision = { ...ready, caseType: 'INDIVIDUAL_REVISION' } as Partial<CaseRequest>;
    expect(caseReady(revision)).toBe(false);
    expect(caseReady({ ...revision, basic: 0 })).toBe(false);
    expect(caseReady({ ...revision, basic: 1 })).toBe(true);
    const date = { ...ready, caseType: 'DATE_CORRECTION' } as Partial<CaseRequest>;
    expect(caseReady(date)).toBe(false);
    expect(caseReady({ ...date, correctedDueDate: ' ' })).toBe(false);
    expect(caseReady({ ...date, correctedDueDate: '2026-11-01' })).toBe(true);
    // SRS-13-24: a reversal names the latest entry by its date; nothing more.
    expect(caseReady({ ...ready, caseType: 'REVERSE_ENTRY' })).toBe(true);
    // SRS-13-27: an observation refixation names the observation and its reference.
    const observed = { ...ready, caseType: 'OBSERVATION_REFIXATION', basic: 52000 } as Partial<CaseRequest>;
    expect(caseReady(observed)).toBe(false);
    expect(caseReady({ ...observed, observationSource: 'AUDIT' })).toBe(false);
    expect(caseReady({ ...observed, observationSource: 'AUDIT', observationReference: '  ' })).toBe(false);
    expect(
      caseReady({ ...observed, observationSource: 'AUDIT', observationReference: 'R'.repeat(101) }),
    ).toBe(false);
    expect(
      caseReady({ ...observed, observationSource: 'AUDIT', observationReference: 'R'.repeat(100) }),
    ).toBe(true);
    expect(caseReady({ ...observed, observationSource: 'ACCOUNTS', observationReference: 'ACC/9' })).toBe(
      true,
    );
    expect(
      caseReady({ ...observed, basic: undefined, observationSource: 'AUDIT', observationReference: 'A' }),
    ).toBe(false);
  });

  it('M-03: stages are positive numbers separated by commas or spaces; one bad part rejects them all', () => {
    expect(parseStages('60000, 62400  64900,')).toEqual([60000, 62400, 64900]);
    expect(parseStages('60000\n62400')).toEqual([60000, 62400]);
    expect(parseStages('')).toEqual([]);
    expect(parseStages('60000, abc')).toEqual([]);
    expect(parseStages('60000, 0')).toEqual([]);
    expect(parseStages('-5')).toEqual([]);
    expect(parseStages('Infinity')).toEqual([]);
  });

  it('SRS-13-23: a scale version has bounds in order, every stage between them, the order, the date and a reason', () => {
    const ready: Partial<VersionRequest> = {
      min: 100,
      max: 200,
      effectiveFrom: '2026-11-01',
      circularId: 7,
      stages: [100, 150, 200],
      reason: 'Scale revision',
      wageRevision: 'WR-2026-01',
    };
    expect(versionReady(ready)).toBe(true);
    expect(versionReady({ ...ready, wageRevision: undefined })).toBe(false);
    expect(versionReady({ ...ready, wageRevision: '  ' })).toBe(false);
    expect(versionReady({ ...ready, min: undefined })).toBe(false);
    expect(versionReady({ ...ready, max: undefined })).toBe(false);
    expect(versionReady({ ...ready, min: 200 })).toBe(false);
    expect(versionReady({ ...ready, min: 201 })).toBe(false);
    expect(versionReady({ ...ready, effectiveFrom: '' })).toBe(false);
    expect(versionReady({ ...ready, circularId: undefined })).toBe(false);
    expect(versionReady({ ...ready, stages: [] })).toBe(false);
    expect(versionReady({ ...ready, stages: undefined })).toBe(false);
    expect(versionReady({ ...ready, stages: [99, 150] })).toBe(false);
    expect(versionReady({ ...ready, stages: [150, 201] })).toBe(false);
    expect(versionReady({ ...ready, reason: '  ' })).toBe(false);
    expect(versionReady({ ...ready, reason: undefined })).toBe(false);
  });
});

describe('M-03 scale bound edges', () => {
  it('SRS-13-23: equal bounds are refused even with the one stage on them', () => {
    const v: Partial<VersionRequest> = {
      min: 200,
      max: 200,
      effectiveFrom: '2026-11-01',
      circularId: 7,
      stages: [200],
      reason: 'Scale revision',
      wageRevision: 'WR-2026-01',
    };
    expect(versionReady(v)).toBe(false);
    expect(versionReady({ ...v, max: 201 })).toBe(true);
  });
});

describe('M-03 new scale', () => {
  it('SC-17-0030: a code of letters, digits or a hyphen (at most 10), a name and an IDA / CDA type', () => {
    expect(SCALE_TYPES).toEqual(['IDA', 'CDA']);
    expect(newScaleReady('E-9', 'IDA E-9', 'IDA')).toBe(true);
    expect(newScaleReady(' C-1 ', ' CDA 1 ', 'CDA')).toBe(true);
    expect(newScaleReady('ABCDEFGHIJ', 'Ten', 'IDA')).toBe(true);
    expect(newScaleReady('ABCDEFGHIJK', 'Eleven', 'IDA')).toBe(false);
    expect(newScaleReady('', 'Name', 'IDA')).toBe(false);
    expect(newScaleReady('E 9', 'Name', 'IDA')).toBe(false);
    expect(newScaleReady('E-9', '  ', 'IDA')).toBe(false);
    expect(newScaleReady('E-9', 'N'.repeat(101), 'IDA')).toBe(false);
    expect(newScaleReady('E-9', 'N'.repeat(100), 'IDA')).toBe(true);
    expect(newScaleReady('E-9', 'नाम', 'IDA')).toBe(false);
    expect(newScaleReady('E-9', 'Name', 'FND')).toBe(false);
    expect(newScaleReady('E-9', 'Name', '')).toBe(false);
  });
});

describe('M-03 stage grid and edges', () => {
  it('SRS l.3949: grid rows are trimmed, empty rows ignored, one bad row rejects them all', () => {
    expect(stagesOfRows([' 60000 ', '', '  ', '62400'])).toEqual([60000, 62400]);
    expect(stagesOfRows([])).toEqual([]);
    expect(stagesOfRows(['', ' '])).toEqual([]);
    expect(stagesOfRows(['60000', 'abc'])).toEqual([]);
    expect(stagesOfRows(['60000', '0'])).toEqual([]);
    expect(stagesOfRows(['-1'])).toEqual([]);
    expect(stagesOfRows(['Infinity'])).toEqual([]);
    expect(stagesOfRows(['0.5'])).toEqual([0.5]);
  });

  it('SRS l.3949: the first grid row that is not a positive number, counted from 1', () => {
    expect(badStageRow([])).toBeNull();
    expect(badStageRow(['60000', '', ' ', ' 62400 '])).toBeNull();
    expect(badStageRow(['x'])).toBe(1);
    expect(badStageRow(['60000', '', '0', 'abc'])).toBe(3);
    expect(badStageRow(['60000', '-5'])).toBe(2);
    expect(badStageRow(['60000', 'Infinity'])).toBe(2);
    expect(badStageRow(['0.5', '1'])).toBeNull();
  });

  it('SRS-13-18: a Basic still held as text is not a Basic', () => {
    const revision: Partial<CaseRequest> = {
      eis: '104882',
      caseType: 'INDIVIDUAL_REVISION',
      effectiveDate: '2026-10-01',
      orderNumber: 'EST/1',
      orderDate: '2026-09-30',
      issuingAuthority: 'GM (P)',
      reason: 'Revision',
      basic: '52000' as unknown as number,
    };
    expect(caseReady(revision)).toBe(false);
    expect(caseReady({ ...revision, basic: 52000 })).toBe(true);
  });

  it('SRS-13-27: the observation reference is measured trimmed', () => {
    const observed: Partial<CaseRequest> = {
      eis: '104882',
      caseType: 'OBSERVATION_REFIXATION',
      effectiveDate: '2026-10-01',
      orderNumber: 'EST/1',
      orderDate: '2026-09-30',
      issuingAuthority: 'GM (P)',
      reason: 'Audit para',
      basic: 52000,
      observationSource: 'AUDIT',
      observationReference: ` ${'R'.repeat(100)} `,
    };
    expect(caseReady(observed)).toBe(true);
  });

  it('SRS-13-23: a cleared bound is no bound', () => {
    const v: Partial<VersionRequest> = {
      min: 100,
      max: 200,
      effectiveFrom: '2026-11-01',
      circularId: 7,
      stages: [150],
      reason: 'Scale revision',
      wageRevision: 'WR-2026-01',
    };
    expect(versionReady({ ...v, min: null as unknown as number })).toBe(false);
    expect(versionReady({ ...v, min: -5, max: null as unknown as number, stages: [-1] })).toBe(false);
  });

  it('SC-17-0030: the scale name is printable throughout and measured trimmed', () => {
    expect(newScaleReady('E-9', ` ${'N'.repeat(100)} `, 'IDA')).toBe(true);
    expect(newScaleReady('E-9', '	Name	', 'IDA')).toBe(true);
    expect(newScaleReady('E-9', 'Nameन', 'IDA')).toBe(false);
    expect(newScaleReady('E-9', 'नName', 'IDA')).toBe(false);
  });
});
