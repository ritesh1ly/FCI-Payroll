/** A-08 form choices, as the target DDL allows them (the server enforces the same). */

/** The office levels of the workflow master (DDL approval_workflow_action.office_level). */
export const OFFICE_LEVELS = ['HQ', 'ZO', 'RO', 'DO', 'PAYING_UNIT', 'OWN_CYCLE_INSTANCE'] as const;

/** The 18 OTP-gated actions (DDL otp_challenge.gated_action; SRS-G-21). */
export const OTP_ACTIONS = [
  'AUTH_LOGIN',
  'AUTH_OWN_PASSWORD_CHANGE',
  'CYCLE_FREEZE_LOCK',
  'CYCLE_UNFREEZE_STUCK_RELEASE',
  'INCREMENT_DATE_BASIC_CORRECTION',
  'DISBURSEMENT_RELEASE',
  'APPS_TRANSMISSION',
  'YEAR_END_REMITTANCE',
  'CPF_SANCTION_FINAL_PAYMENT',
  'CPF_OPENING_BALANCE_CORRECTION',
  'WAGE_REVISION_HQ',
  'UNDO_TRANSACTION',
  'FNF_APPROVAL',
  'SUPPLEMENTARY_POST_LOCK',
  'LOAN_FORECLOSURE',
  'PLI_ADJUSTMENT',
  'ROLE_ACCESS_GROUP_CHANGE',
  'HRMS_FALLBACK_UPLOAD_APPROVAL',
] as const;
