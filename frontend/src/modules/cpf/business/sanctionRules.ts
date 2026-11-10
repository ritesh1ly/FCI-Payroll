import type { NewSanction, PurposeOption, Sanction, SanctionStage } from '../api/cpfApi';

/**
 * E-01 / I-04 CPF withdrawal chain rules (SRS-6-4, SRS-6-14, SRS-6-26, BR-CPF-039): who acts at
 * which stage and what the member's form still needs. The server checks everything again
 * (CpfSanctionService); these only say early what is missing. Pure functions.
 */

/** The pill of each stage (SRS-6-4; UC-05). */
export const STAGE_PILL: Record<SanctionStage, string> = {
  UNIT_VERIFICATION: 'p-info',
  ZONAL_HQ_VALIDATION: 'p-info',
  COMPETENT_AUTHORITY: 'p-warn',
  SANCTIONED: 'p-ok',
  PAYMENT_BLOCKED: 'p-bad',
  PAID: 'p-ok',
  RETURNED: 'p-warn',
  REJECTED: 'p-bad',
  CANCELLED: 'p-gray',
};

/** The tier waiting to act (1 unit, 2 CPF Division, 3 Competent Authority), or none. */
export function waitingTier(stage: SanctionStage): 1 | 2 | 3 | null {
  switch (stage) {
    case 'UNIT_VERIFICATION':
      return 1;
    case 'ZONAL_HQ_VALIDATION':
      return 2;
    case 'COMPETENT_AUTHORITY':
      return 3;
    default:
      return null;
  }
}

/**
 * The tier action this viewer may take on I-04: tier 1 with I-04 EDIT, tier 2 with I-04 SUBMIT;
 * tier 3 is decided in the Approvals Inbox, never here.
 */
export function tierAction(
  stage: SanctionStage,
  may: (action: string) => boolean,
): 'verify' | 'recommend' | null {
  const tier = waitingTier(stage);
  if (tier === 1 && may('EDIT')) return 'verify';
  if (tier === 2 && may('SUBMIT')) return 'recommend';
  return null;
}

/** The tier the card draws and its pill: the tier waiting, or the third for a sanctioned request. */
export function tierCell(s: Pick<Sanction, 'stage' | 'type'>): { key: string; pill: string } | null {
  const tier = waitingTier(s.stage);
  if (tier === 1) return { key: 'i04c.tier.unit', pill: 'p-gray' };
  if (tier === 2) return { key: 'i04c.tier.division', pill: 'p-warn' };
  const done = s.stage === 'SANCTIONED' || s.stage === 'PAID' || s.stage === 'PAYMENT_BLOCKED';
  if (tier !== 3 && !done) return null;
  return {
    key: s.type === 'FINAL' ? 'i04c.tier.authorityFinal' : 'i04c.tier.authority',
    pill: tier === 3 && s.type === 'FINAL' ? 'p-warn' : done ? 'p-ok' : 'p-info',
  };
}

/** The status the card draws: what the tier is checking, a capped amount, else the stage. */
export function statusKey(s: Pick<Sanction, 'stage' | 'type' | 'capped'>): string {
  if (s.capped) return 'i04c.status.capped';
  if (s.stage === 'UNIT_VERIFICATION') return 'i04c.status.kyc';
  if (s.stage === 'ZONAL_HQ_VALIDATION') return 'i04c.status.limit';
  if (s.stage === 'COMPETENT_AUTHORITY')
    return s.type === 'FINAL' ? 'i04c.status.final' : 'i04c.status.authority';
  return `i04.stage.${s.stage}`;
}

/** The Limit Rule cell: final payment has its own basis; a purpose without a rule is configured. */
export function limitRuleKey(s: Pick<Sanction, 'type' | 'limitRule'>): string | null {
  if (s.type === 'FINAL') return 'i04c.rule.final';
  return s.limitRule ? null : 'i04c.rule.configured';
}

/** BR-CPF-029: what the stored ceiling says about the request; the server computes it at tier 2. */
export function limitCheck(s: Pick<Sanction, 'requested' | 'ceiling'>): 'pending' | 'within' | 'over' {
  if (s.ceiling === null || s.requested === null) return 'pending';
  return s.requested > s.ceiling ? 'over' : 'within';
}

/** UC-05: the member may withdraw a request until it is sanctioned. */
export function withdrawable(stage: SanctionStage): boolean {
  return waitingTier(stage) !== null || stage === 'RETURNED';
}

/** SRS-12-9: a request returned to the member may be corrected and resubmitted. */
export function resubmittable(stage: SanctionStage): boolean {
  return stage === 'RETURNED';
}

/** A request amount: more than zero, at most twelve digits and two decimals (SRS I-04 field table). */
export function validAmount(amount: string): boolean {
  // Split, then simple patterns (a nested optional group is flagged by security/detect-unsafe-regex).
  // A second dot leaves Number(amount) NaN, which the last condition refuses.
  const [whole = '', decimals] = amount.trim().split('.');
  return (
    /^\d{1,12}$/.test(whole) && (decimals === undefined || /^\d{1,2}$/.test(decimals)) && Number(amount) > 0
  );
}

/** SRS-6-14: the mandatory checklist documents not yet attached, in checklist order. */
export function missingDocuments(purpose: PurposeOption | undefined, draft: NewSanction): string[] {
  if (!purpose) return [];
  const attached = new Set(draft.documents.map((d) => d.documentType));
  return purpose.documents
    .filter((d) => d.mandatory && !attached.has(d.documentType))
    .map((d) => d.documentType);
}

/** SRS I-04 field table "Supporting Documents": File (PDF / JPG / PNG, up to 5 files). */
export const MAX_DOCUMENTS = 5;

/** What still stops the member's request (message keys; arguments where a key takes one). */
export function requestProblems(
  draft: NewSanction,
  purpose: PurposeOption | undefined,
  today: string,
): { key: string; args?: string[] }[] {
  const out: { key: string; args?: string[] }[] = [];
  if (!purpose) out.push({ key: 'e01s.problem.purpose' });
  if (!validAmount(draft.amount)) out.push({ key: 'e01s.problem.amount' });
  if (purpose?.propertyDetails && (!draft.propertyAddress.trim() || !draft.propertyDescription.trim())) {
    out.push({ key: 'e01s.problem.property' });
  }
  const missing = missingDocuments(purpose, draft);
  if (missing.length > 0) out.push({ key: 'e01s.problem.documents', args: [missing.join(', ')] });
  if (draft.documents.length > MAX_DOCUMENTS) {
    out.push({ key: 'error.CPF-422-DOCUMENT-COUNT', args: [String(MAX_DOCUMENTS)] });
  }
  if (
    draft.documents.some(
      (d) => !d.documentDate || d.documentDate > today || !d.issuingAuthority.trim() || !d.documentType,
    )
  ) {
    out.push({ key: 'e01s.problem.particulars' });
  }
  return out;
}
