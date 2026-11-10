/**
 * OD-141 (owner 2026-11-09): the cycle specs start, compute and lock months in an office of their own,
 * never in DH001 (whose months are the evaluators'). The demo-data seed of OD-141 creates the office,
 * its Payroll Maker and Payroll Checker and members whose Actual run computes; it fills
 * {@link SEEDED} below (or a run sets E2E_CYCLE_OFFICE / E2E_CYCLE_MAKER / E2E_CYCLE_CHECKER).
 * Until then the specs that lock a month skip, naming this file.
 */
export interface CycleOffice {
  /** org_location.location_code of the office (a cycle unit). */
  code: string;
  /** Login of the office's Payroll Maker. */
  maker: string;
  /** Login of the office's Payroll Checker (not the Maker). */
  checker: string;
}

/**
 * Set by the OD-141 seed (db/seed/V082_20__demo_e2e_test_office.sql): DT001 "Delhi Test Office (E2E
 * only)" under North Zone, its users copied from the Delhi ones (same password and roles).
 */
const SEEDED: CycleOffice | null = {
  code: 'DT001',
  maker: 'maker.e2e@fci-demo.test',
  checker: 'ddo.e2e@fci-demo.test',
};

export const CYCLE_OFFICE: CycleOffice | null =
  process.env.E2E_CYCLE_OFFICE && process.env.E2E_CYCLE_MAKER && process.env.E2E_CYCLE_CHECKER
    ? {
        code: process.env.E2E_CYCLE_OFFICE,
        maker: process.env.E2E_CYCLE_MAKER,
        checker: process.env.E2E_CYCLE_CHECKER,
      }
    : SEEDED;

/**
 * The office of the month-end lock spec (16-month-end-lock): it locks a month through the application,
 * so it cannot share {@link CYCLE_OFFICE}, whose current month 03-payroll-cycle drives and locks by
 * fixture. Seeded by db/seed/V082_27__demo_e2e_test_office_lock.sql: DT002 "Delhi Test Office 2 (E2E
 * only)" under North Zone, its users copied from the Delhi ones, its members the complete CPF members
 * of DH001 renumbered 6xxxxx. A run may point it elsewhere with E2E_LOCK_OFFICE / E2E_LOCK_MAKER /
 * E2E_LOCK_CHECKER.
 */
const LOCK_SEEDED: CycleOffice = {
  code: 'DT002',
  maker: 'maker.e2e2@fci-demo.test',
  checker: 'ddo.e2e2@fci-demo.test',
};

export const LOCK_OFFICE: CycleOffice =
  process.env.E2E_LOCK_OFFICE && process.env.E2E_LOCK_MAKER && process.env.E2E_LOCK_CHECKER
    ? {
        code: process.env.E2E_LOCK_OFFICE,
        maker: process.env.E2E_LOCK_MAKER,
        checker: process.env.E2E_LOCK_CHECKER,
      }
    : LOCK_SEEDED;

/** The lock office's copy of a DH001 member: 107001 -> 607001 (complete CPF members only). */
export function lockEis(eis: string): string {
  return `6${eis.slice(1)}`;
}

export const NO_CYCLE_OFFICE =
  'Needs the OD-141 cycle test office (frontend/e2e/support/cycle-office.ts): no DH001 month is locked by a test.';

/**
 * The test office of the cycle specs (OD-141) and its logins, for the specs that initiate, freeze,
 * compute, verify or lock a cycle, or need a month of it open or locked by those specs. Its members
 * are every ACTIVE member of DH001 with the EIS number's first digit set to 9 ({@link testEis}); they
 * are not in the HRMS mock.
 */
export const TEST_OFFICE = 'DT001';
export const TEST_MAKER = 'maker.e2e@fci-demo.test';
export const TEST_CHECKER = 'ddo.e2e@fci-demo.test';
export const TEST_HR = 'hr.e2e@fci-demo.test';
export const TEST_FINANCE = 'finance.e2e@fci-demo.test';
export const TEST_UNIT_CPF = 'cpf.unit.e2e@fci-demo.test';
export const testMaker = { role: 'PAYROLL_MAKER', office: TEST_OFFICE };
export const testChecker = { role: 'PAYROLL_CHECKER', office: TEST_OFFICE };

/** The test office's copy of a DH001 member: 104882 -> 904882. */
export function testEis(eis: string): string {
  return `9${eis.slice(1)}`;
}
