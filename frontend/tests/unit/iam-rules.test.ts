import { describe, expect, it } from 'vitest';
import type { Group, UserRow } from '@/modules/iam/api/adminApi';
import {
  assignableGroups,
  assignableUsers,
  LOCATION_TYPE,
  scopesFor,
} from '@/modules/iam/business/accessRules';
import { confirmationDiffers } from '@/modules/iam/business/passwordRules';
import {
  currentScope,
  isEseva,
  offices,
  officesByType,
  roleInitials,
  roleOptions,
  scopeForOffice,
  scopeKey,
} from '@/modules/iam/business/scopeRules';
import { needsStepUp } from '@/modules/iam/business/stepUpRules';
import type { ScopeOption } from '@/shared/session';

describe('A-03 / A-04 access rules', () => {
  const TODAY = '2026-10-23';
  const group = (code: string, over: Partial<Group> = {}): Group => ({
    id: code.length,
    code,
    name: code,
    roleCode: 'PAYROLL_MAKER',
    scopeType: 'DIVISION',
    consolidated: false,
    accessMode: 'FULL',
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    approvalStatus: 'APPROVED',
    scopes: [],
    ...over,
  });

  it('SRS-22-2: a new group is scoped to nothing (GLOBAL), its zone (ZONE) or its location (any other type)', () => {
    expect(scopesFor('GLOBAL', 1)).toEqual([]);
    expect(scopesFor('ZONE', 7)).toEqual([{ zoneId: 7 }]);
    for (const type of ['HEADQUARTERS', 'REGION', 'DIVISION', 'DEPOT']) {
      expect(scopesFor(type, 20)).toEqual([{ locationId: 20 }]);
    }
    // Each location-scoped type picks its own kind of office.
    expect(LOCATION_TYPE).toEqual({ HEADQUARTERS: 'HQ', REGION: 'RO', DIVISION: 'DO', DEPOT: 'DEPOT' });
  });

  it('SRS-22-2: only an approved group in force today can be assigned, both ends of its dates included', () => {
    const groups = [
      group('OPEN'),
      group('STARTS-TODAY', { effectiveFrom: TODAY }),
      group('ENDS-TODAY', { effectiveTo: TODAY }),
      group('ENDS-LATER', { effectiveTo: '2027-03-31' }),
      group('STARTS-TOMORROW', { effectiveFrom: '2026-10-24' }),
      group('ENDED-YESTERDAY', { effectiveTo: '2026-10-22' }),
      group('PENDING', { approvalStatus: 'PENDING_APPROVAL' }),
      group('REJECTED', { approvalStatus: 'REJECTED' }),
    ];
    expect(assignableGroups(groups, TODAY).map((g) => g.code)).toEqual([
      'OPEN',
      'STARTS-TODAY',
      'ENDS-TODAY',
      'ENDS-LATER',
    ]);
    expect(assignableGroups([], TODAY)).toEqual([]);
  });

  it('SRS-22-13: nobody assigns access to themselves; without a session id every user is offered', () => {
    const users = [{ id: 1 }, { id: 2 }, { id: 3 }] as UserRow[];
    expect(assignableUsers(users, 2).map((u) => u.id)).toEqual([1, 3]);
    expect(assignableUsers(users, undefined).map((u) => u.id)).toEqual([1, 2, 3]);
    expect(assignableUsers(users, 99)).toHaveLength(3);
    expect(assignableUsers([], 1)).toEqual([]);
  });
});

describe('password form rules', () => {
  it('SRS-G-48: the new password is typed twice alike; the comparison is exact', () => {
    expect(confirmationDiffers('Fci@Demo2026', 'Fci@Demo2026')).toBe(false);
    expect(confirmationDiffers('', '')).toBe(false);
    expect(confirmationDiffers('Fci@Demo2026', 'fci@demo2026')).toBe(true); // case
    expect(confirmationDiffers('Fci@Demo2026', 'Fci@Demo2026 ')).toBe(true); // a trailing space
    expect(confirmationDiffers('Fci@Demo2026', '')).toBe(true); // confirmation left empty
    expect(confirmationDiffers('', 'Fci@Demo2026')).toBe(true);
  });
});

describe('OTP step-up before a sensitive screen', () => {
  const NOW = Date.parse('2026-10-23T06:00:00Z');
  const me = (stepUpValidUntil: string | null) => ({ sensitiveScreens: ['C-02', 'A-04'], stepUpValidUntil });

  it('SRS-G-21: a screen without a sensitive action, or no screen, never asks for a step-up', () => {
    expect(needsStepUp(me(null), 'M-01', NOW)).toBe(false);
    expect(needsStepUp(me(null), null, NOW)).toBe(false);
    expect(needsStepUp(me(null), '', NOW)).toBe(false);
    expect(needsStepUp({ sensitiveScreens: [], stepUpValidUntil: null }, 'C-02', NOW)).toBe(false);
  });

  it('SRS-G-48: a sensitive screen asks until a confirmation is valid, and again from the moment it expires', () => {
    expect(needsStepUp(me(null), 'C-02', NOW)).toBe(true);
    expect(needsStepUp(me('2026-10-23T05:59:59Z'), 'C-02', NOW)).toBe(true); // expired
    expect(needsStepUp(me('2026-10-23T06:00:00Z'), 'A-04', NOW)).toBe(true); // expires now
    expect(needsStepUp(me('2026-10-23T06:00:00.001Z'), 'A-04', NOW)).toBe(false); // still valid
    expect(needsStepUp(me('2026-10-23T06:15:00Z'), 'C-02', NOW)).toBe(false);
  });

  it('SRS-G-48: an empty or unreadable expiry is no valid confirmation (fail closed)', () => {
    expect(needsStepUp(me(''), 'C-02', NOW)).toBe(true);
    expect(needsStepUp(me('not a date'), 'C-02', NOW)).toBe(true);
  });

  it('uses the current time when none is given', () => {
    expect(needsStepUp(me('2999-01-01T00:00:00Z'), 'C-02')).toBe(false);
    expect(needsStepUp(me('2000-01-01T00:00:00Z'), 'C-02')).toBe(true);
  });
});

/** The top-bar office and role selectors (ADR-0023, wireframe v6.1); the server re-checks every switch. */
describe('scope rules (office and role selectors)', () => {
  function scope(role: string, location: string | null, type: string | null, current = false): ScopeOption {
    return {
      persona: 'payroll',
      role,
      roleName: role,
      location,
      locationType: type,
      label: location ? `${type} — ${location}` : null,
      current,
      groups: [],
    } as unknown as ScopeOption;
  }

  const scopes = [
    scope('PAYROLL_CHECKER', 'DH001', 'DO', true),
    scope('PAYROLL_CHECKER', 'MR001', 'DO'),
    scope('ESTABLISHMENT_HR', 'DH001', 'DO'),
    scope('PAYROLL_CHECKER', 'NA11', 'ZO'),
    scope('PAYROLL_CHECKER', 'X1', 'CUSTOM'),
    scope('EMPLOYEE_ESEVA', null, null),
  ];

  it('finds the current scope and tells eSeva apart', () => {
    expect(currentScope(scopes)?.location).toBe('DH001');
    expect(currentScope([scope('A', 'B', 'DO')])).toBeUndefined();
    expect(isEseva({ location: null })).toBe(true);
    expect(isEseva({ location: 'DH001' })).toBe(false);
  });

  it('lists each staff office once, in server order, and groups them in hierarchy order', () => {
    expect(offices(scopes).map((o) => o.code)).toEqual(['DH001', 'MR001', 'NA11', 'X1']);
    const groups = officesByType(scopes);
    // ZO before DO (hierarchy), an unknown type last, types without an office left out.
    expect(groups.map((g) => g.type)).toEqual(['ZO', 'DO', 'CUSTOM']);
    expect(groups[1]?.offices.map((o) => o.code)).toEqual(['DH001', 'MR001']);
  });

  it('names an office by its label, else by its code; an office without a type has an empty type', () => {
    expect(offices(scopes)[0]).toEqual({ code: 'DH001', type: 'DO', label: 'DO — DH001' });
    expect(offices([{ ...scope('PAYROLL_CHECKER', 'X9', null), label: null }])).toEqual([
      { code: 'X9', type: '', label: 'X9' },
    ]);
  });

  it('offers the roles of the current office, then eSeva', () => {
    expect(roleOptions(scopes).map((s) => scopeKey(s))).toEqual([
      'PAYROLL_CHECKER@DH001',
      'ESTABLISHMENT_HR@DH001',
      'EMPLOYEE_ESEVA',
    ]);
    // At another office only the roles held there are offered.
    const atMr = scopes.map((s) => ({ ...s, current: s.location === 'MR001' }));
    expect(roleOptions(atMr).map((s) => scopeKey(s))).toEqual(['PAYROLL_CHECKER@MR001', 'EMPLOYEE_ESEVA']);
  });

  it('in eSeva offers each staff role once, wherever it is held', () => {
    const inEseva = scopes.map((s) => ({ ...s, current: s.location === null }));
    expect(roleOptions(inEseva).map((s) => s.role)).toEqual([
      'PAYROLL_CHECKER',
      'ESTABLISHMENT_HR',
      'EMPLOYEE_ESEVA',
    ]);
  });

  it('keeps the acting role on an office pick when held there, else the first role there', () => {
    expect(scopeForOffice(scopes, 'MR001')?.role).toBe('PAYROLL_CHECKER');
    const asHr = scopes.map((s) => ({ ...s, current: s.role === 'ESTABLISHMENT_HR' }));
    expect(scopeForOffice(asHr, 'NA11')?.role).toBe('PAYROLL_CHECKER');
    expect(scopeForOffice(scopes, 'NOWHERE')).toBeUndefined();
  });

  it('keeps the acting role at an office holding several roles, wherever it is listed there', () => {
    const two = [
      scope('PAYROLL_CHECKER', 'DH001', 'DO', true),
      scope('ESTABLISHMENT_HR', 'MR001', 'DO'),
      scope('PAYROLL_CHECKER', 'MR001', 'DO'),
    ];
    expect(scopeForOffice(two, 'MR001')).toMatchObject({ role: 'PAYROLL_CHECKER', location: 'MR001' });
    // With no scope current (nothing to keep), the first role there.
    const none = two.map((s) => ({ ...s, current: false }));
    expect(scopeForOffice(none, 'MR001')).toMatchObject({ role: 'ESTABLISHMENT_HR', location: 'MR001' });
  });

  it('builds the avatar initials from the role name, ignoring brackets and symbols', () => {
    expect(roleInitials('Payroll Checker (Verifier)')).toBe('PC');
    expect(roleInitials('Establishment / HR')).toBe('EH');
    expect(roleInitials('Finance')).toBe('F');
    expect(roleInitials(null)).toBe('');
    expect(roleInitials('  & / - ')).toBe('');
  });

  it('takes the first two words that start with a letter; a bracketed note is never a word', () => {
    expect(roleInitials('Labour Dealing Hand')).toBe('LD');
    expect(roleInitials('Finance (Accounts Wing)')).toBe('F');
    expect(roleInitials('Payroll(Verifier)Checker')).toBe('PC');
    expect(roleInitials('1st Level Checker')).toBe('LC');
    expect(roleInitials(' Payroll Checker')).toBe('PC');
    expect(roleInitials('payroll maker')).toBe('PM');
    // Words are separated by spaces, slashes, ampersands and hyphens.
    expect(roleInitials('Establishment/HR')).toBe('EH');
    expect(roleInitials('Finance&Accounts')).toBe('FA');
    expect(roleInitials('Payroll-Checker')).toBe('PC');
  });
});

describe('OD-130: A-04 offers only groups in reach', () => {
  it('OD_130_a group outside the reach is never offered', () => {
    const base = {
      id: 1,
      code: 'AG',
      name: 'AG',
      roleCode: null,
      scopeType: 'DIVISION',
      consolidated: false,
      accessMode: 'FULL',
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
      approvalStatus: 'APPROVED',
      scopes: [],
    };
    expect(assignableGroups([{ ...base, assignable: false }], '2026-11-07')).toEqual([]);
    expect(assignableGroups([{ ...base, assignable: true }], '2026-11-07')).toHaveLength(1);
  });
});
