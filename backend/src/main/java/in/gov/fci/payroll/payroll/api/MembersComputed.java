package in.gov.fci.payroll.payroll.api;

import java.util.List;

/**
 * Members a later Actual run computed (one chunk): an earlier "computation blocked" exception of
 * theirs in the cycle no longer holds and is cleared, naming the run as the cause (SRS X-05 use
 * case: "The system closes the exception when the condition clears").
 */
public record MembersComputed(long cycleInstanceId, long runId, List<Long> employeeIds) {

    public MembersComputed {
        employeeIds = List.copyOf(employeeIds);
    }
}
