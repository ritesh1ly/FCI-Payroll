import type { ReceivedEvent } from '../api/registerApi';

/** Pure rules of the I-17 list and the M-11 form (the server enforces the same and more). */

/** A status pill class for an approval status. */
export function statusPill(status: string): string {
  if (status === 'APPROVED') return 'p-ok';
  if (status === 'REJECTED' || status === 'RETURNED') return 'p-bad';
  return 'p-warn';
}

/** A status pill class for the I-21 task behind an event or a transfer (I-17 l.3881 statuses). */
export function taskPill(status: string | null): string {
  if (status === 'APPROVED' || status === 'AUTO_APPLIED') return 'p-ok';
  if (status === 'REJECTED_RETURNED_TO_HRMS') return 'p-bad';
  if (status === null) return 'p-gray';
  return 'p-warn';
}

/** The message key of the pay effect of an event type (SRS-1-64); an unknown type reads as none. */
export function eventEffect(type: string): string {
  return [
    'SUSPENSION',
    'REVOCATION',
    'REINSTATEMENT',
    'ABSCONDING',
    'SALARY_RELEASE',
    'SALARY_STOP',
    'DISMISSAL',
    'SEPARATION',
  ].includes(type)
    ? type
    : 'NONE';
}

/** I-17 Details from HRMS: the percentage, the released month, the treatment, else the reason. */
export function eventDetails(e: ReceivedEvent): { key: string; args: (string | number)[] } {
  if (e.subsistencePercent !== null) return { key: 'evt.w.detail.subsistence', args: [e.subsistencePercent] };
  if (e.interveningPeriod)
    return { key: 'evt.w.detail.intervening', args: [e.reason ?? '', e.interveningPeriod] };
  if (e.releaseFrom)
    return { key: 'evt.w.detail.released', args: [e.releaseFrom, e.releaseTo ?? e.releaseFrom] };
  return { key: 'evt.w.detail.text', args: [e.reason ?? '—'] };
}

/** I-17 Source: the HRMS interface, or the fallback upload's reference (SRS l.3878). */
export function eventSource(e: ReceivedEvent): { key: string; args: string[] } {
  if (e.source === 'HRMS_FALLBACK_UPLOAD')
    return { key: 'evt.w.source.fallback', args: [e.hrmsRequestId ?? '—'] };
  return { key: `evt.w.source.${e.source}`, args: [] };
}
