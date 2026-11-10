import wireframes from '@/generated/wireframes.json';
import type { Me } from '@/shared/session';
import { mayView } from './guards';

/**
 * The design-pack body of the one screen that still shows one (the M-01 layout, around live data).
 * Served by the Next.js server only (route /payroll/wireframe/<screen>), after the same VIEW check as
 * the page guard, and only the body of the session's own role: they are never part of the browser
 * bundle, so a user cannot read a screen they may not open.
 */
export type WfNode = unknown;

interface WireframeScreen {
  id: string;
  code: string;
  name: string;
  crumb: string;
  bodies: Record<string, WfNode[]>;
}

/** Screen code -> live wireframe id (screens.json). The dynamic screens have none. */
const WIREFRAME_OF: Record<string, string> = {
  'M-01': 'm1',
};

/** Which design-pack role's body a session sees (first match wins). */
const LIVE_ROLE: [string, string][] = [
  ['ESTABLISHMENT_HR', 'hr'],
  ['PAYROLL_CHECKER', 'checker'],
  ['PAYROLL_MAKER', 'maker'],
  ['LABOUR_DEALING_HAND', 'ldh'],
  ['FINANCE', 'fin'],
  ['EMPLOYEE_ESEVA', 'emp'],
];

function liveRole(roles: readonly string[]): string {
  return LIVE_ROLE.find(([role]) => roles.includes(role))?.[1] ?? 'admin';
}

/** The body of {@code screen} for this session, or null (no wireframe, or not permitted). */
export function wireframeBody(me: Pick<Me, 'permissions' | 'roles'>, screen: string): WfNode[] | null {
  const id = WIREFRAME_OF[screen];
  if (!id || !mayView(me, screen)) return null;
  const wireframe = (wireframes as Record<string, WireframeScreen>)[id];
  if (!wireframe) return null;
  return (
    wireframe.bodies[liveRole(me.roles)] ?? wireframe.bodies.admin ?? Object.values(wireframe.bodies)[0] ?? []
  );
}
