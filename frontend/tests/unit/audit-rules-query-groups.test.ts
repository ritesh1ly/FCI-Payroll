import { describe, expect, it } from 'vitest';
import { auditQuery } from '@/modules/audit/business/auditFilter';

/** X-05 audit query: the drawn object, user and search filters (SRS-G-13). */
describe('X-05 audit query filters', () => {
  const base = { from: '2026-10-01', to: '2026-10-31', entity: '', actor: '', field: '' };
  const keys = (q: string) => [...new URLSearchParams(q).keys()];

  it('SRS-G-13: the object group, user group and search text are sent when given, each on its own', () => {
    const all = new URLSearchParams(
      auditQuery({ ...base, objectGroup: 'PAY', userGroup: 'MAKERS', q: 'basic' }),
    );
    expect([...all.keys()]).toEqual(['from', 'to', 'objectGroup', 'userGroup', 'q']);
    expect(all.get('objectGroup')).toBe('PAY');
    expect(all.get('userGroup')).toBe('MAKERS');
    expect(all.get('q')).toBe('basic');
    expect(keys(auditQuery({ ...base, objectGroup: 'PAY' }))).toEqual(['from', 'to', 'objectGroup']);
    expect(keys(auditQuery({ ...base, userGroup: 'MAKERS' }))).toEqual(['from', 'to', 'userGroup']);
    expect(keys(auditQuery({ ...base, q: 'x' }))).toEqual(['from', 'to', 'q']);
    expect(keys(auditQuery({ ...base, objectGroup: '', userGroup: '', q: '' }))).toEqual(['from', 'to']);
  });
});
