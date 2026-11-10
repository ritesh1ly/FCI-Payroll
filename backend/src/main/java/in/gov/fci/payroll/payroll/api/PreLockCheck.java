package in.gov.fci.payroll.payroll.api;

/**
 * A configured pre-lock check (DDL {@code pre_payroll_check}, SRS-15-39) evaluated by the module
 * that owns its data (plan 18 phase 18a). The cycle owns this port; a module registers a bean for
 * its rule key, and a key without a bean stays "not available" (plan 05 D-2). Never a default: a
 * check that cannot be evaluated fails.
 */
public interface PreLockCheck {

    /** The cycle instance being checked: its id, cycle unit and month (YYYYMM). */
    record Cycle(long cycleInstanceId, long orgUnitId, String payrollYymm) {}

    /**
     * The outcome of one evaluation.
     *
     * @param failures the failing record count (0 = passed)
     * @param detail what to show on C-01 (never PAN, bank account or Aadhaar in clear)
     */
    record Outcome(long failures, String detail) {}

    /** The {@code pre_payroll_check.rule_key} this bean evaluates. */
    String ruleKey();

    /** Evaluates the check for the instance. */
    Outcome evaluate(Cycle cycle);
}
