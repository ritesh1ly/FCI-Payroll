import { describe, expect, it } from 'vitest';
import {
  FLOOR_BASES,
  LABEL_SCOPES,
  LABEL_TYPES,
  MEMBER_SCOPES,
  NORM_TYPES,
  PAYROLL_TYPES,
  PERIODICITIES,
  RATE_NORM_LINKS,
} from '@/modules/configuration/business/mdmRules';
import { OFFICE_LEVELS, OTP_ACTIONS } from '@/modules/workflow/business/routeRules';
import { ddlCheckValues } from './support/ddl';

/**
 * The fixed choices of the master and workflow forms are exactly the CHECK lists of the target DDL
 * v4.0 (schema of record): a value the database refuses is never offered, and no allowed value is
 * missing. Compared as sets where a form orders its choices for the user (the default first).
 */
const sorted = (values: readonly string[]) => [...values].sort();

describe('master form vocabularies = DDL v4.0 CHECK lists', () => {
  it('SRS-G-38: the payroll types of a circular scope', () => {
    expect([...PAYROLL_TYPES]).toEqual(ddlCheckValues('circular_order_scope', 'payroll_type'));
  });

  it('SRS-16-13: the net-pay floor bases (ABSOLUTE_AMOUNT is the SRS Floor Amount)', () => {
    expect(sorted(FLOOR_BASES)).toEqual(sorted(ddlCheckValues('net_pay_floor_rule', 'floor_basis')));
  });

  it('SRS-16-14: the allowance ceiling periodicities and member scopes', () => {
    expect([...PERIODICITIES]).toEqual(ddlCheckValues('allowance_ceiling_norm', 'periodicity'));
    expect(sorted(MEMBER_SCOPES)).toEqual(sorted(ddlCheckValues('allowance_ceiling_norm', 'member_scope')));
    // BOTH is the DDL default, offered first.
    expect(MEMBER_SCOPES[0]).toBe('BOTH');
  });

  it('SRS-G-39 / SRS-G-40: the label types and scope levels of the Label Master', () => {
    expect([...LABEL_TYPES]).toEqual(ddlCheckValues('label_master', 'label_type'));
    expect([...LABEL_SCOPES]).toEqual(ddlCheckValues('label_master', 'scope_level'));
  });

  it('SRS-G-41: the norm types and rate-norm links of the Operation Master', () => {
    expect([...NORM_TYPES]).toEqual(ddlCheckValues('operation_master', 'norm_type'));
    expect([...RATE_NORM_LINKS]).toEqual(ddlCheckValues('operation_master', 'rate_norm_link'));
  });
});

describe('A-08 route vocabularies = DDL v4.0 CHECK lists', () => {
  it('SRS-22-16: the office levels of an approval action', () => {
    expect([...OFFICE_LEVELS]).toEqual(ddlCheckValues('approval_workflow_action', 'office_level'));
  });

  it('SRS-G-21: exactly the 18 OTP-gated actions, no more and no fewer (R-OTP)', () => {
    const gated = ddlCheckValues('otp_challenge', 'gated_action');
    expect(gated).toHaveLength(18);
    expect([...OTP_ACTIONS]).toEqual(gated);
    expect(new Set(OTP_ACTIONS).size).toBe(OTP_ACTIONS.length);
  });

  it('the DDL reader refuses an unknown table or column instead of returning nothing', () => {
    expect(() => ddlCheckValues('no_such_table', 'x')).toThrow('no table no_such_table');
    expect(() => ddlCheckValues('otp_challenge', 'no_such_column')).toThrow('no CHECK list');
  });
});
