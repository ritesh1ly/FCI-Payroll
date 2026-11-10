import { describe, expect, it } from 'vitest';
import { BUSINESS_TIME_ZONE, businessToday } from '@/shared/time';

/**
 * The business calendar is India's: the server dates effective dates and payroll months in
 * Asia/Kolkata, so a screen's "today" must not follow the browser's time zone.
 */
describe('businessToday', () => {
  it('is the date in India, whatever the browser time zone: IST midnight is 18:30 UTC', () => {
    expect(BUSINESS_TIME_ZONE).toBe('Asia/Kolkata');
    expect(businessToday(new Date('2026-10-22T18:29:59Z'))).toBe('2026-10-22');
    expect(businessToday(new Date('2026-10-22T18:30:00Z'))).toBe('2026-10-23');
    expect(businessToday(new Date('2026-12-31T18:30:00Z'))).toBe('2027-01-01');
  });

  it('is written YYYY-MM-DD with two-digit months and days', () => {
    expect(businessToday(new Date('2026-01-04T23:00:00Z'))).toBe('2026-01-05');
    expect(businessToday()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
