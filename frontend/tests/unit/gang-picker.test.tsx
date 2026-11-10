import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AttendanceCapture } from '@/modules/labour';
import { dayStrength } from '@/modules/labour/business/labourRules';
import type { AttendanceGrid } from '@/modules/labour/api/labourApi';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * The gang picker searched on the server (I-01 l.4541, I-02 Gang Name, M-09 target gang; SRS-2-46)
 * and the I-01 Gang Strength Check (l.4545, SRS-2-48).
 */

afterEach(() => vi.unstubAllGlobals());

function gangs(n: number, from = 1) {
  return Array.from({ length: n }, (_, i) => ({
    gangId: from + i,
    gangCode: `DP-DL01-G${String(from + i).padStart(3, '0')}`,
    gangName: `Gang ${from + i}`,
    depot: 'DP-DL01 - Depot Okhla',
    payrollType: 'DEPT_LABOUR',
    gangType: 'HANDLING',
    status: 'ACTIVE',
    sanctionedStrength: 6,
    members: 4,
    effectiveFrom: '2025-07-01',
  }));
}

describe('Gang picker', () => {
  it('I-01: past the first 20 matches the picker says so and the text narrows on the server', async () => {
    const calls = backend({
      'GET /v1/labour/gangs?': (url) =>
        url.includes('text=G150')
          ? paged(gangs(1, 150), 1, url)
          : { items: gangs(20), page: 0, size: 20, total: 180 },
      'GET /v1/labour/gangs/150/attendance?': () => ({ ...GRID, gangId: 150 }),
    });
    wrap(<AttendanceCapture />, me(['I-01:VIEW', 'I-01:CREATE']));
    const gang = screen.getByLabelText(new RegExp(`^${t('m09.gang')}`), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).getAllByRole('option')).toHaveLength(21));
    expect(screen.getByRole('note').textContent).toBe(t('m09.gangMore', [20, 180]));
    await userEvent.type(screen.getByLabelText(t('m09.gangSearch')), 'G150');
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G150 - Gang 150')).toBeTruthy());
    // Gang 150 of 180 is reachable: no 100-gang cap, no browser-side filter.
    expect(calls.every((c) => !c.url.includes('size=100'))).toBe(true);
    expect(screen.queryByRole('note')).toBeNull();
    await userEvent.selectOptions(gang, '150');
    // The chosen gang stays listed while the text changes.
    await userEvent.clear(screen.getByLabelText(t('m09.gangSearch')));
    await userEvent.type(screen.getByLabelText(t('m09.gangSearch')), 'NOSUCH');
    expect((gang as HTMLSelectElement).value).toBe('150');
  });
});

const GRID: AttendanceGrid = {
  gangId: 5,
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DEPT_LABOUR',
  yymm: '202609',
  lastMarkable: '2026-09-30',
  batchState: 'OPEN',
  approvalId: null,
  sanctionedStrength: 3,
  codes: [
    { code: 'W', meaning: 'On work', weight: 1, leave: false, confirmed: true },
    { code: 'H', meaning: 'Half day', weight: 0.5, leave: false, confirmed: true },
    { code: 'A', meaning: 'Absent', weight: 0, leave: false, confirmed: true },
    { code: 'CL', meaning: 'Casual leave', weight: 1, leave: true, confirmed: true },
  ],
  members: [],
};

describe('I-01 Gang Strength Check', () => {
  it('SRS-2-48: present is counted from the day marks with a present weight, never leave or absence', () => {
    expect(dayStrength(GRID, {})).toEqual({ sanctioned: 3, present: 0, short: 3 });
    expect(dayStrength(GRID, { 1: 'W', 2: 'A', 3: 'CL' })).toEqual({ sanctioned: 3, present: 1, short: 2 });
    expect(dayStrength(GRID, { 1: 'W', 2: 'H', 3: 'W' })).toEqual({ sanctioned: 3, present: 3, short: 0 });
    // More present than sanctioned is no shortfall.
    expect(dayStrength({ ...GRID, sanctionedStrength: 1 }, { 1: 'W', 2: 'W' })).toEqual({
      sanctioned: 1,
      present: 2,
      short: 0,
    });
    // An unknown code counts nobody present.
    expect(dayStrength(GRID, { 1: 'ZZ' })?.present).toBe(0);
    // SRS-2-46: the Ancillary Pool has no Sanctioned Strength, so no check.
    expect(dayStrength({ ...GRID, sanctionedStrength: null }, { 1: 'W' })).toBeNull();
  });
});
