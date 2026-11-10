import type { ActiveUser, RouteAction } from '../api/workflowMasterApi';

/** The Flag column of the Active User Report (SRS-G-50). */
export type UserFlag = 'NEVER' | 'DORMANT' | 'STATUS';

/**
 * Never logged in, dormant beyond the A-07 setting, else the account's status. Nothing is guessed: while the setting
 * is not configured the server answers no inactivity, so no account is flagged dormant.
 */
export function userFlag(user: Pick<ActiveUser, 'lastLoginAt' | 'inactive'>): UserFlag {
  if (user.lastLoginAt === null) return 'NEVER';
  return user.inactive === true ? 'DORMANT' : 'STATUS';
}

/** Why the row's Edit (a new version) is closed, as a message key; null when it is open. SRS-22-19. */
export function editBlock(action: RouteAction, mayEdit: boolean, hqBlock: string | null): string | null {
  if (!mayEdit) return 'x01.why.hqAdmin';
  if (hqBlock !== null) return hqBlock;
  return action.pending ? 'x01.why.pending' : null;
}
