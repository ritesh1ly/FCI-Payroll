/**
 * HRMS Update Request screen rules (plan 16 phase 16c; SRS-1-70). The server decides; these only
 * say which actions to offer.
 */

const PLAIN_TEXT = /^[\x20-\x7E]*$/;

/** SRS-1-70: only a request whose HRMS fetch failed may be fetched again. */
export function refetchable(status: string): boolean {
  return status === 'FETCH_FAILED';
}

/**
 * SRS-1-70: a request names the field and the effective date; the HRMS reference is optional
 * (up to 60 plain characters); the Maker also names the employee.
 */
export function requestComplete(input: {
  eis?: string;
  needsEmployee: boolean;
  fieldCode: string;
  effectiveDate: string;
  hrmsReference: string;
}): boolean {
  if (input.needsEmployee && (input.eis ?? '').trim() === '') return false;
  if (input.fieldCode === '' || input.effectiveDate === '') return false;
  return input.hrmsReference.trim().length <= 60 && PLAIN_TEXT.test(input.hrmsReference);
}
