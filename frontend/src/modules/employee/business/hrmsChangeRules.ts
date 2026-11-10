/**
 * I-21 screen rules (plan 16 phases 16a, 16b; SRS-1-28, SRS-1-67, SRS-G-31). The server decides; these only say
 * which actions to offer and why not.
 */

/** SRS-1-28: the Maker may submit a task waiting for its order details or returned. */
export function submittable(status: string): boolean {
  return status === 'PENDING_ORDER_DETAILS' || status === 'RETURNED_TO_MAKER';
}

/** SRS-1-69: the states in which a task still waits (and may hold the month-end lock). */
export function pending(status: string): boolean {
  return [
    'PENDING_ORDER_DETAILS',
    'PENDING_CHECKER',
    'PENDING_HQ_ESTABLISHMENT',
    'RETURNED_TO_MAKER',
  ].includes(status);
}

/** SRS-G-31: the order details are complete (or the order is held in HRMS). */
export function orderComplete(order: {
  orderNo: string;
  orderDate: string;
  authority: string;
  held: boolean;
}): boolean {
  if (order.held) return true;
  return order.orderNo.trim() !== '' && order.orderDate !== '' && order.authority.trim() !== '';
}

/** SRS-1-67: at most this many tasks go in one order (as the server). */
export const MAX_ORDER_TASKS = 200;

/** SRS-1-67: one order covers two or more tasks, up to the server's limit. */
export function orderable(selected: number): boolean {
  return selected >= 2 && selected <= MAX_ORDER_TASKS;
}

/** I-21 Order copy: attached, linked to a bulk order, held in HRMS or still missing. */
export function copyState(task: {
  orderHeldInHrms: boolean;
  orderRef: string | null;
  orderNo: string | null;
}): 'held' | 'linked' | 'attached' | 'missing' {
  if (task.orderHeldInHrms) return 'held';
  if (task.orderRef) return 'linked';
  return task.orderNo ? 'attached' : 'missing';
}

/** The pill of an order copy's state. */
export function copyPill(task: Parameters<typeof copyState>[0]): string {
  const state = copyState(task);
  if (state === 'held') return 'p-info';
  return state === 'missing' ? 'p-bad' : 'p-ok';
}

/** The DOB tab's rule line (SRS-1-22, SRS-1-23, UC-04): the first that applies. */
export function dobRule(task: {
  retirementPast: boolean;
  retiresSoon: boolean;
  status: string;
}): 'rejected' | 'hq' | 'soon' | 'checker' {
  if (task.status === 'REJECTED_RETURNED_TO_HRMS') return 'rejected';
  if (task.retirementPast) return 'hq';
  return task.retiresSoon ? 'soon' : 'checker';
}
