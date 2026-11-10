import type { ScopeOption } from '@/shared/session';

/**
 * Rules of the top-bar 🏢 office and 👤 role selectors (wireframe v6.1): pure functions over the
 * user's own scopes (/api/auth/scopes). The server re-checks every switch.
 */

/** Office types in hierarchy order: the optgroups of the office selector. */
export const OFFICE_TYPES = ['HQ', 'ZO', 'RO', 'DO', 'DEPOT', 'FSD', 'SHED'] as const;

export interface Office {
  code: string;
  type: string;
  label: string;
}

export function currentScope(scopes: ScopeOption[]): ScopeOption | undefined {
  return scopes.find((s) => s.current);
}

export function isEseva(scope: Pick<ScopeOption, 'location'>): boolean {
  return scope.location === null;
}

/** The staff offices the user may act at, each once, in server (hierarchy) order. */
export function offices(scopes: ScopeOption[]): Office[] {
  const seen = new Map<string, Office>();
  for (const s of scopes) {
    if (s.location !== null && !seen.has(s.location)) {
      seen.set(s.location, { code: s.location, type: s.locationType ?? '', label: s.label ?? s.location });
    }
  }
  return [...seen.values()];
}

/** Offices grouped by type, in hierarchy order (types without an office are left out). */
export function officesByType(scopes: ScopeOption[]): { type: string; offices: Office[] }[] {
  const all = offices(scopes);
  const known = new Set<string>(OFFICE_TYPES);
  const types = [...OFFICE_TYPES, ...new Set(all.map((o) => o.type).filter((t) => !known.has(t)))];
  return types
    .map((type) => ({ type, offices: all.filter((o) => o.type === type) }))
    .filter((g) => g.offices.length > 0);
}

/**
 * The role selector's options: the roles held at the current office (or, while in eSeva, every
 * staff role the user holds anywhere), then Employee (eSeva) if the user holds it. Each option
 * carries the scope a pick switches to.
 */
export function roleOptions(scopes: ScopeOption[]): ScopeOption[] {
  const current = currentScope(scopes);
  const staff = scopes.filter((s) => !isEseva(s));
  const atOffice =
    current && !isEseva(current)
      ? staff.filter((s) => s.location === current.location)
      : staff.filter((s, i) => staff.findIndex((o) => o.role === s.role) === i);
  return [...atOffice, ...scopes.filter(isEseva)];
}

/**
 * The scope an office pick switches to: the same acting role if the user holds it there, else the
 * first role the user holds at that office.
 */
export function scopeForOffice(scopes: ScopeOption[], office: string): ScopeOption | undefined {
  const current = currentScope(scopes);
  const there = scopes.filter((s) => s.location === office);
  return there.find((s) => s.role === current?.role) ?? there[0];
}

/** The select value of a scope: role, plus the office for staff scopes. */
export function scopeKey(scope: Pick<ScopeOption, 'role' | 'location'>): string {
  return scope.location === null ? scope.role : `${scope.role}@${scope.location}`;
}

/** "Payroll Checker (Verifier)" -> "PC": the avatar of the acting role (wireframe .me). */
export function roleInitials(name: string | null | undefined): string {
  return (name ?? '')
    .split(/\(.*?\)|[\s/&-]/) // a bracketed note or a separator ends a word
    .filter((w) => /^\p{L}/u.test(w)) // words start with a letter (empty pieces drop out)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}
