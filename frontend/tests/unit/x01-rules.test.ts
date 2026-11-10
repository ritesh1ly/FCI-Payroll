import { describe, expect, it } from 'vitest';
import type { RouteAction, RouteVersion } from '@/modules/workflow/api/workflowMasterApi';
import { editBlock, userFlag } from '@/modules/workflow/business/x01Rules';

/** X-01: the Flag of the Active User Report (SRS-G-50) and why a route cannot be edited (SRS-22-19). */

const NOW = '2026-10-01T04:30:00Z';

describe('X-01 Active User Report flag', () => {
  it('SRS-G-50: never logged in comes first, whatever the inactivity says', () => {
    expect(userFlag({ lastLoginAt: null, inactive: null })).toBe('NEVER');
    expect(userFlag({ lastLoginAt: null, inactive: true })).toBe('NEVER');
    expect(userFlag({ lastLoginAt: null, inactive: false })).toBe('NEVER');
  });

  it('SRS-G-50: dormant only when the server flags it; an unset threshold flags nobody', () => {
    expect(userFlag({ lastLoginAt: NOW, inactive: true })).toBe('DORMANT');
    expect(userFlag({ lastLoginAt: NOW, inactive: false })).toBe('STATUS');
    expect(userFlag({ lastLoginAt: NOW, inactive: null })).toBe('STATUS');
  });
});

describe('X-01 row Edit', () => {
  const version = { id: 1 } as RouteVersion;
  const free: RouteAction = {
    actionCode: 'A',
    officeLevel: 'HQ',
    current: version,
    pending: null,
    history: [],
  };
  const waiting: RouteAction = { ...free, pending: version };

  it('SRS-22-19: only the HQ Administrator edits; the role reason comes before any other', () => {
    expect(editBlock(free, false, null)).toBe('x01.why.hqAdmin');
    expect(editBlock(waiting, false, 'error.AUTHZ-403-HQ-ONLY')).toBe('x01.why.hqAdmin');
  });

  it('SRS-22-19: outside Headquarters the HQ reason, also with a version waiting', () => {
    expect(editBlock(free, true, 'error.AUTHZ-403-HQ-ONLY')).toBe('error.AUTHZ-403-HQ-ONLY');
    expect(editBlock(waiting, true, 'error.AUTHZ-403-HQ-ONLY')).toBe('error.AUTHZ-403-HQ-ONLY');
  });

  it('SRS-22-16: a version waiting for approval closes Edit; otherwise it is open', () => {
    expect(editBlock(waiting, true, null)).toBe('x01.why.pending');
    expect(editBlock(free, true, null)).toBeNull();
  });
});
