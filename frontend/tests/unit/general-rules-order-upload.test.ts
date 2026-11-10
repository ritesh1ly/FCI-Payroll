import { describe, expect, it } from 'vitest';
import type { FallbackUpload } from '@/modules/general/api/generalApi';
import { orderUpload } from '@/modules/general/business/generalRules';

/** X-07 order card (SRS-G-30): a superseded or cancelled suspension upload is never the one read. */
describe('X-07 order card upload', () => {
  const u = (id: number, type: string, status: string) => ({ id, type, status }) as unknown as FallbackUpload;

  it('SRS_G_30: a superseded suspension upload is passed over for the live one', () => {
    const attendance = u(1, 'ATTENDANCE_LEAVE', 'VALIDATED');
    const superseded = u(2, 'SUSPENSION_ABSCONDING', 'SUPERSEDED');
    const live = u(3, 'SUSPENSION_ABSCONDING', 'APPROVED');
    expect(orderUpload(attendance, false, [attendance, superseded, live])).toBe(live);
    expect(orderUpload(attendance, false, [attendance, superseded])).toBe(attendance);
    expect(orderUpload(null, false, [superseded])).toBeNull();
  });
});
