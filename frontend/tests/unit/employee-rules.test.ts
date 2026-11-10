import { describe, expect, it } from 'vitest';
import {
  EXCEPTION_STATUSES,
  HEADCOUNT_STATUSES,
  statusCounts,
} from '@/modules/employee/business/employeeRules';
import { statusPill } from '@/modules/employee/business/eventRules';

describe('M-01 Employee Payroll Master rules', () => {
  // The Status filter is the server's (SRS-NF-3: applied before paging; EmployeeListIT proves it).
  it('SRS-1-11: the tiles count set-up pending, active, and the records needing the Payroll section', () => {
    // Exceptions: PAN or CPF missing, bank invalid, a change awaiting approval.
    expect(EXCEPTION_STATUSES).toEqual(['PAN_CPF_MISSING', 'BANK_INVALID', 'CHANGE_PENDING']);
    const counts = {
      total: 21,
      payrollSetupPending: 1,
      changePending: 2,
      panCpfMissing: 3,
      bankInvalid: 4,
      active: 11,
    };
    expect(statusCounts(counts)).toEqual({ setup: 1, active: 11, exceptions: 9 });
    expect(statusCounts({ ...counts, changePending: 0, panCpfMissing: 0 })).toEqual({
      setup: 1,
      active: 11,
      exceptions: 4,
    });
    // Before the server answers: no counts, every tile zero.
    expect(statusCounts(undefined)).toEqual({ setup: 0, active: 0, exceptions: 0 });
  });
});

describe('R-01 Consolidated MIS rules', () => {
  // The column totals over every office are the server's (X-pagination: a page holds some offices).
  it('SRS-22-3: the columns are the statuses the MIS counts', () => {
    expect([...HEADCOUNT_STATUSES]).toEqual(['ACTIVE', 'CHANGE_PENDING', 'PAYROLL_SETUP_PENDING']);
  });
});

describe('I-17 life-cycle event rules', () => {
  it('the approval status of an event or a transfer: approved green, rejected or returned red, else pending amber', () => {
    expect(statusPill('APPROVED')).toBe('p-ok');
    expect(statusPill('REJECTED')).toBe('p-bad');
    expect(statusPill('RETURNED')).toBe('p-bad');
    expect(statusPill('PENDING')).toBe('p-warn');
    expect(statusPill('SUBMITTED')).toBe('p-warn');
    expect(statusPill('')).toBe('p-warn');
  });
});
