import { describe, expect, it } from 'vitest';
import {
  changeable,
  hasInterestOutstanding,
  isOpen,
  isYymm,
  rescheduleReady,
  sanctionReady,
  tracker,
} from '@/modules/loans/business/loanRules';

/** I-04 Loans & Advances screen rules (plan 09 phase 09f; SRS-12-17, SRS-12-21 … 12-23). */
describe('I-04 loan rules', () => {
  it('SRS-12-17: an HRMS loan is never changed here; an I-04 loan being recovered is', () => {
    expect(changeable({ status: 'ACTIVE', source: 'LABOUR_ENT' })).toBe(true);
    expect(changeable({ status: 'RESCHEDULED', source: 'LABOUR_ENT' })).toBe(true);
    expect(changeable({ status: 'ACTIVE', source: 'HRMS' })).toBe(false);
    expect(changeable({ status: 'STOPPED', source: 'LABOUR_ENT' })).toBe(false);
    expect(changeable({ status: 'CLOSED', source: 'LABOUR_ENT' })).toBe(false);
    expect(changeable({ status: 'ACTIVE', source: 'MIGRATION' })).toBe(true);
    expect(isOpen({ status: 'STOPPED' })).toBe(true);
    expect(isOpen({ status: 'ACTIVE' })).toBe(true);
    expect(isOpen({ status: 'RESCHEDULED' })).toBe(true);
    expect(isOpen({ status: 'FORECLOSED' })).toBe(false);
    expect(isOpen({ status: 'SETTLED_IN_FNF' })).toBe(false);
  });

  it('SRS-12-23: the tracker shows recovered and outstanding with interest', () => {
    expect(
      tracker({
        sanctioned: 10000,
        principalOutstanding: 6000,
        interestAmount: 500,
        interestOutstanding: 500,
      }),
    ).toEqual({
      total: 10500,
      recovered: 4000,
      outstanding: 6500,
      percent: 38,
    });
    expect(
      tracker({ sanctioned: 0, principalOutstanding: 0, interestAmount: 0, interestOutstanding: 0 }).percent,
    ).toBe(0);
    expect(
      tracker({ sanctioned: 1000, principalOutstanding: 2000, interestAmount: 0, interestOutstanding: 0 })
        .recovered,
    ).toBe(0);
    // A loan without interest, or not yet scheduled: the API sends these BigDecimals as null.
    const none = null as unknown as number;
    expect(
      tracker({
        sanctioned: 1000,
        principalOutstanding: none,
        interestAmount: none,
        interestOutstanding: none,
      }),
    ).toEqual({ total: 1000, recovered: 1000, outstanding: 0, percent: 100 });
  });

  it('SRS-12-1: the sanction form is ready only with complete terms', () => {
    const f = {
      eis: '104882',
      loanType: 'FESTIVAL_ADVANCE',
      sanctioned: '20000',
      principalInstalments: '4',
      instalment: '5000',
      interestAmount: '',
      interestInstalments: '',
      interestInstalment: '',
      startYymm: '202611',
    };
    expect(sanctionReady(f)).toBe(true);
    expect(sanctionReady({ ...f, eis: ' ' })).toBe(false);
    expect(sanctionReady({ ...f, loanType: '' })).toBe(false);
    expect(sanctionReady({ ...f, sanctioned: '0' })).toBe(false);
    expect(sanctionReady({ ...f, sanctioned: '' })).toBe(false);
    expect(sanctionReady({ ...f, principalInstalments: '2.5' })).toBe(false);
    expect(sanctionReady({ ...f, instalment: '-1' })).toBe(false);
    expect(sanctionReady({ ...f, startYymm: '202613' })).toBe(false);
    expect(sanctionReady({ ...f, interestInstalments: '0' })).toBe(true);
    expect(sanctionReady({ ...f, interestInstalments: '2' })).toBe(false);
    expect(sanctionReady({ ...f, interestAmount: '500' })).toBe(false);
    expect(sanctionReady({ ...f, interestAmount: '500', interestInstalments: '2' })).toBe(false);
    expect(
      sanctionReady({ ...f, interestAmount: '500', interestInstalments: '2', interestInstalment: '250' }),
    ).toBe(true);
    expect(sanctionReady({ ...f, interestAmount: '0', interestInstalments: '' })).toBe(true);
    expect(isYymm('202601')).toBe(true);
    expect(isYymm('2026-01')).toBe(false);
  });

  it('SRS-12-4 / SRS-12-1: a reschedule spreads the interest outstanding over its own instalments, and only then', () => {
    const terms = {
      instalments: '3',
      instalment: '5000',
      interestInstalments: '',
      interestInstalment: '',
      start: '202612',
    };
    const none = { interestOutstanding: 0 };
    const owed = { interestOutstanding: 1200 };
    expect(hasInterestOutstanding(none)).toBe(false);
    expect(hasInterestOutstanding({ interestOutstanding: 0.01 })).toBe(true);
    expect(hasInterestOutstanding({} as { interestOutstanding: number })).toBe(false);
    expect(rescheduleReady(none, terms)).toBe(true);
    expect(rescheduleReady(none, { ...terms, interestInstalments: '0' })).toBe(true);
    // Interest terms on a loan that owes none are refused (the server's INTEREST defect).
    expect(rescheduleReady(none, { ...terms, interestInstalments: '2', interestInstalment: '100' })).toBe(
      false,
    );
    expect(rescheduleReady(owed, terms)).toBe(false);
    expect(rescheduleReady(owed, { ...terms, interestInstalments: '2', interestInstalment: '' })).toBe(false);
    expect(rescheduleReady(owed, { ...terms, interestInstalments: '2', interestInstalment: '0' })).toBe(
      false,
    );
    expect(rescheduleReady(owed, { ...terms, interestInstalments: '1.5', interestInstalment: '600' })).toBe(
      false,
    );
    expect(rescheduleReady(owed, { ...terms, interestInstalments: '0', interestInstalment: '600' })).toBe(
      false,
    );
    expect(rescheduleReady(owed, { ...terms, interestInstalments: '2', interestInstalment: '600' })).toBe(
      true,
    );
    expect(rescheduleReady(owed, { ...terms, interestInstalments: '1', interestInstalment: '1200' })).toBe(
      true,
    );
    // The principal terms as before.
    const ok = { ...terms };
    expect(rescheduleReady(none, { ...ok, instalments: '0' })).toBe(false);
    expect(rescheduleReady(none, { ...ok, instalments: '2.5' })).toBe(false);
    expect(rescheduleReady(none, { ...ok, instalment: '' })).toBe(false);
    expect(rescheduleReady(none, { ...ok, start: '202613' })).toBe(false);
  });
});
