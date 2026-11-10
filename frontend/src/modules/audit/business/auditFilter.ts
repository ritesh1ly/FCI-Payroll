import { businessToday } from '@/shared/time';

/**
 * A-05 search filter: a range of India business days, From and To both included, and optional
 * entity, actor and field (pure functions). The rows show their time in India, so the range is
 * India days too: a change at 00:30 IST belongs to that IST date, not to the UTC day before.
 */
export interface AuditFilter {
  from: string;
  to: string;
  entity: string;
  actor: string;
  field: string;
  /** M-16 AUDIT_OBJECT_GROUP code: the drawn "All objects" filter. */
  objectGroup?: string;
  /** M-16 AUDIT_USER_GROUP code: the drawn "All users" filter. */
  userGroup?: string;
  /** The drawn "Search in list". */
  q?: string;
}

const DAY_MS = 86_400_000;
/** India is UTC+05:30 all year, with no daylight saving (the business calendar, `@/shared/time`). */
const INDIA_OFFSET_MS = 19_800_000;

/** The business date (YYYY-MM-DD, India) {@code offsetDays} from the instant {@code now}. */
export function isoDay(offsetDays: number, now: number = Date.now()): string {
  return businessToday(new Date(now + offsetDays * DAY_MS));
}

/** The default range: yesterday and today in India, both included. */
export function defaultAuditFilter(now: number = Date.now()): AuditFilter {
  return {
    from: isoDay(-1, now),
    to: isoDay(0, now),
    entity: '',
    actor: '',
    field: '',
    objectGroup: '',
    userGroup: '',
    q: '',
  };
}

/**
 * A range the search takes: both dates given and To not before From (one day is a range). An empty
 * To sorts before any From, so the comparison also refuses it.
 */
export function rangeValid(filter: Pick<AuditFilter, 'from' | 'to'>): boolean {
  return filter.from !== '' && filter.to >= filter.from;
}

/** The instant an India business day begins: its midnight in India, in epoch milliseconds. */
function dayStart(day: string): number {
  return Date.parse(`${day}T00:00:00Z`) - INDIA_OFFSET_MS;
}

/**
 * The query string of the search and the export: from midnight of From to the midnight that ends
 * To, India time, sent as UTC instants (the server's window is [from, to)); empty criteria are left
 * out.
 */
export function auditQuery(filter: AuditFilter): string {
  return new URLSearchParams({
    from: new Date(dayStart(filter.from)).toISOString(),
    to: new Date(dayStart(filter.to) + DAY_MS).toISOString(),
    ...(filter.entity ? { entity: filter.entity } : {}),
    ...(filter.actor ? { actor: filter.actor } : {}),
    ...(filter.field ? { field: filter.field } : {}),
    ...(filter.objectGroup ? { objectGroup: filter.objectGroup } : {}),
    ...(filter.userGroup ? { userGroup: filter.userGroup } : {}),
    ...(filter.q ? { q: filter.q } : {}),
  }).toString();
}
