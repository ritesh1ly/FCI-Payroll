package in.gov.fci.payroll.general.api;

/**
 * The X-05 Exceptions &amp; Holds worklist for the modules that find exceptions (the computation,
 * pre-payroll checks, interfaces, the rule engine). SRS-G-9: "one cross-module worklist for every
 * open exception across all engines and periods"; C-08 and gate G2 read the same store.
 *
 * <p>The finding module owns the condition, so it also clears the entry when the condition no
 * longer holds - the worklist itself never "resolves" anything (SRS X-05 use case: "The system
 * closes the exception when the condition clears, and records who cleared it and when").
 */
public interface ExceptionWorklist {

    /**
     * An exception found by a module.
     *
     * @param cycleInstanceId the cycle instance it concerns, if any
     * @param employeeId the member it concerns, if any (a cycle-level exception has none)
     * @param moduleCode the finding module, e.g. {@code C-02}, {@code I-21}, {@code HRMS}
     * @param exceptionCode stable code of the condition, e.g. {@code MISSING_BANK_ACCOUNT}
     * @param severity BLOCKING, WARNING, INFORMATIONAL or SECURITY (DDL vocabulary; Q-016)
     * @param blocksGate G0-G6, or null if it holds no gate
     * @param hrmsChangeTaskId the I-21 task deciding an HRMS value, if any
     * @param entityName table of the failed value
     * @param fieldName column of the failed value
     * @param fieldSource ENT, CALC, HRMS, RULE, SYS, ESS or INTF
     * @param correctionScreen where it must be corrected ("Fix Where"), e.g. {@code M-01}
     * @param detail what failed; never PAN, bank account or Aadhaar in clear (SRS-SEC-4)
     */
    record NewEntry(
            Long cycleInstanceId,
            Long employeeId,
            String moduleCode,
            String exceptionCode,
            String severity,
            String blocksGate,
            Long hrmsChangeTaskId,
            String entityName,
            String fieldName,
            String fieldSource,
            String correctionScreen,
            String detail) {}

    /**
     * Records the exception, or returns the unsettled entry that already records the same condition
     * (same code, cycle, member and field): a re-run does not duplicate the worklist.
     *
     * @return the exception id
     */
    Long raise(String actor, NewEntry exception);

    /**
     * Closes every unsettled entry of this condition, recording who cleared it and why in the audit
     * trail.
     *
     * @param cause why the condition no longer holds, e.g. "bank account corrected on M-01"
     * @return how many entries were closed
     */
    int clear(
            String actor,
            String exceptionCode,
            Long cycleInstanceId,
            Long employeeId,
            String fieldName,
            String cause);
}
