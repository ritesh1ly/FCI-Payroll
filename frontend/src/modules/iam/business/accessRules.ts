import type { Group, ScopeInput, UserRow } from '../api/adminApi';

/** A-03 / A-04 rules the forms apply (pure functions; the server enforces the same). */

/** The location type a location-scoped group of each scope type targets. */
export const LOCATION_TYPE: Record<string, string> = {
  HEADQUARTERS: 'HQ',
  REGION: 'RO',
  DIVISION: 'DO',
  DEPOT: 'DEPOT',
};

/** The scope rows of a new group: none for GLOBAL, the zone for ZONE, else the location. */
export function scopesFor(scopeType: string, target: number): ScopeInput[] {
  if (scopeType === 'GLOBAL') return [];
  return scopeType === 'ZONE' ? [{ zoneId: target }] : [{ locationId: target }];
}

/**
 * Groups a user may be assigned to on {@code today} (YYYY-MM-DD): approved and in force that day,
 * both ends included, as the server checks it (an assignment to any other group is refused with
 * GROUP_NOT_APPROVED). A group with a later end date is still in force and may be assigned.
 */
export function assignableGroups(groups: Group[], today: string): Group[] {
  return groups.filter(
    (g) =>
      // OD-130: never one outside the administrator's reach: the server would log them out.
      g.assignable !== false &&
      g.approvalStatus === 'APPROVED' &&
      g.effectiveFrom <= today &&
      (!g.effectiveTo || g.effectiveTo >= today),
  );
}

/** Users the session may assign: never itself (no self-assignment). */
export function assignableUsers(users: UserRow[], selfId: number | undefined): UserRow[] {
  return users.filter((u) => u.id !== selfId);
}
