'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/shared/api';
import type { Locale } from '@/generated/messages';
import { translate } from '@/shared/i18n';

/** /api/auth/login-options: sign-in is user ID + password; nothing is chosen before it. */
export interface LoginOptions {
  demo: boolean;
  /** SRS-G-48 (OD-36): the password is followed by an OTP to the registered mobile. */
  otpAtLogin?: boolean;
  /** OD-41: "Sign in with OTP on registered mobile" (the number and its code, no password). */
  mobileOtp?: boolean;
}

/** The live persona ids (/api/auth/me persona): the grouping that admits the acting role. */
export type PersonaId = 'employee' | 'payroll' | 'manager' | 'agm' | 'superuser' | 'sysadmin' | 'auditor';

export interface Group {
  id: number;
  code: string;
  name: string;
  roleCode: string;
  roleName: string;
  consolidated: boolean;
}

/** /api/auth/me: the only source of route guards and the menu. */
export interface Me {
  userId: number;
  loginId: string;
  displayName: string;
  persona: PersonaId;
  route: string;
  landingScreen: string | null;
  /** The acting role of the session (one role at a time; switched in the top bar). */
  role: string | null;
  roleName: string | null;
  /** The office the session acts at; null for Employee (eSeva). */
  location: { code: string; label: string } | null;
  period: string;
  periodStatus: string;
  roles: string[];
  groups: Group[];
  permissions: string[];
  readOnlyConsolidated: boolean;
  passwordChangeRequired: boolean;
  /** The acting role has sensitive screens and no valid OTP confirmation (SRS-G-48, Module 02 D-2). */
  stepUpRequired: boolean;
  /** Screens on which the acting role holds a sensitive action: they need a recent OTP step-up. */
  sensitiveScreens: string[];
  /** Until when the last OTP confirmation lasts (ISO instant), or null. */
  stepUpValidUntil: string | null;
  /** Screens whose changes are proposed at Headquarters only (their approval routes, A-08). */
  hqOnlyScreens: string[];
  /** The session acts at Headquarters. */
  atHeadquarters: boolean;
  /**
   * Critical activities ask for an OTP (fci.api.otp.required = Y, as in UAT and production); false
   * only on development and local machines, where they are confirmed without a code.
   */
  otpRequired: boolean;
  /** The page sizes every list offers and the one it starts with (A-07 settings; X-pagination). */
  paging?: { sizes: number[]; defaultSize: number };
  locale: string;
  lastLoginAt: string | null;
  /** The session's timers (idle restarts with every call to the server). */
  session: { idleExpiresAt: string; absoluteExpiresAt: string | null; idleTimeoutSeconds: number };
  demo: boolean;
}

export interface MenuItem {
  /** The wireframe's entry id (m2d, i4b ...): unique even when one screen appears in two groups. */
  id: string;
  code: string;
  labelKey: string;
  /** /payroll?s=<code> plus the entry's view (&engine=DPS) where the wireframe draws one. */
  route: string;
  requires: string;
}

export interface Menu {
  sections: { id: string; labelKey: string; items: MenuItem[] }[];
  hiddenCount: number;
}

/**
 * /api/auth/scopes: one (office, acting role) of the user's OWN RBAC (active access groups and the
 * eSeva grant): the options of the top-bar 🏢 office and 👤 role selectors. `location` is null for
 * Employee (eSeva).
 */
export interface ScopeOption {
  role: string;
  roleName: string;
  persona: PersonaId | null;
  location: string | null;
  locationType: string | null;
  label: string | null;
  current: boolean;
}

export const meKey = ['me'] as const;

/** The signed-in session (401 when there is none). */
export function fetchMe(): Promise<Me> {
  return api<Me>('/auth/me', { silent: true });
}

/** OD-130: the address bar reached a screen this session may not view (a log line, nothing more). */
export function reportDeniedScreen(screen: string): void {
  void api('/auth/denied-screen', { method: 'POST', body: { screen }, silent: true }).catch(() => undefined);
}

export function useMe() {
  return useQuery({ queryKey: meKey, queryFn: fetchMe, retry: false });
}

export function useMenu(enabled: boolean) {
  return useQuery({ queryKey: ['menu'], queryFn: () => api<Menu>('/menu'), enabled });
}

export function useScopes(enabled: boolean) {
  return useQuery({ queryKey: ['scopes'], queryFn: () => api<ScopeOption[]>('/auth/scopes'), enabled });
}

/**
 * Why the session cannot propose a change on {@code screen} although its role holds the action:
 * the screen's approval routes are maintained at Headquarters and the session acts at another
 * office (the server refuses with AUTHZ-403-HQ-ONLY). The message key of the reason, or null.
 */
export function hqOnlyBlock(me: Me | undefined, screen: string): string | null {
  return me && !me.atHeadquarters && me.hqOnlyScreens.includes(screen) ? 'error.AUTHZ-403-HQ-ONLY' : null;
}

export function can(me: Me | undefined, screen: string, action = 'VIEW'): boolean {
  // No profile, or one without permissions (still loading, refetch in flight): no permission.
  return Boolean(me?.permissions?.includes(`${screen}:${action}`));
}

/** "2026-07" -> "JUL 2026" (a data value: month names stay in English, as on the live site). */
/**
 * "OCT 2026" for a period "2026-10": the month name from the language bundle (month.short.N, en and
 * hi; OD-123: never hard-coded), the year as the server gives it.
 */
export function periodLabel(period: string, locale: Locale = 'en'): string {
  const [year, month] = period.split('-');
  const index = Number(month);
  if (!(index >= 1 && index <= 12)) return period;
  return `${translate(locale, `month.short.${index}`)} ${year}`;
}
