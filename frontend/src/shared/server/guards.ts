import type { Me } from '@/shared/session';

/** A screen code of the registry, e.g. M-04 (anything else in ?s= is refused, never echoed). */
const SCREEN_CODE = /^[A-Z]-\d{2}$/;

/**
 * Server-side route guard of /payroll?s=<screen>, DENY BY DEFAULT: a screen is rendered only if the
 * session holds its VIEW permission (as /auth/me reports it, i.e. computed by the backend from the
 * session's office and acting role). Editing the URL therefore never opens a screen the menu does
 * not offer; and every API call the screen makes is authorised again by the backend. Pure, so it
 * is unit-tested without a server.
 *
 * @returns where to send the request instead ('/payroll', the landing screen), or null to render it
 */
export function guardScreen(me: Pick<Me, 'permissions'>, screen: string | null): string | null {
  if (!screen) return null;
  if (!SCREEN_CODE.test(screen)) return '/payroll';
  return me.permissions.includes(`${screen}:VIEW`) ? null : '/payroll';
}

/** Whether the session may view {@code screen} (the same rule, for server route handlers). */
export function mayView(me: Pick<Me, 'permissions'>, screen: string): boolean {
  return SCREEN_CODE.test(screen) && me.permissions.includes(`${screen}:VIEW`);
}
