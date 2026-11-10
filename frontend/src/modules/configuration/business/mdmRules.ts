import type { FloorBasis, PayrollType } from '../api/mdmApi';

/*
 * The fixed vocabularies of the Module 04 master screens, as the target DDL v4.0 CHECK constraints name
 * them (values of M-16 lists come from the reference API instead). The server checks the same lists.
 */

/** circular_order_scope.payroll_type; also net_pay_floor_rule.payroll_type (plan 04 D-4). */
export const PAYROLL_TYPES: readonly PayrollType[] = ['STAFF', 'DEPT_LABOUR', 'DPS_LABOUR', 'NWNP_LABOUR'];

/** net_pay_floor_rule.floor_basis; ABSOLUTE_AMOUNT is the SRS "Floor Amount". */
export const FLOOR_BASES: readonly FloorBasis[] = ['ABSOLUTE_AMOUNT', 'PERCENT_OF_GROSS', 'NONE'];

/** allowance_ceiling_norm.periodicity. */
// OPEN-QUESTION: Q-04-9 - SRS M-15 sources periodicity from M-16 (three values); the DDL CHECK allows these five.
export const PERIODICITIES = ['MONTHLY', 'QUARTERLY', 'ANNUAL', 'BLOCK_YEAR_4', 'ONE_TIME_PER_EVENT'];

/** allowance_ceiling_norm.member_scope (BOTH is the DDL default). */
export const MEMBER_SCOPES = ['BOTH', 'EMPLOYEE', 'LABOUR'];

/** label_master.label_type and scope_level. */
export const LABEL_TYPES = ['UI_LABEL', 'HELP_TEXT', 'MASTER_VALUE'];
export const LABEL_SCOPES = ['GLOBAL', 'REGION', 'OFFICE'];

/** operation_master.norm_type and rate_norm_link. */
export const NORM_TYPES = ['HANDLING', 'HEIGHT', 'LEAD', 'PIECE_RATE', 'NONE'];
export const RATE_NORM_LINKS = ['NORM_SLAB', 'SOR_ASOR_RATE', 'ALLOWANCE', 'TIME_BASED', 'NONE'];
