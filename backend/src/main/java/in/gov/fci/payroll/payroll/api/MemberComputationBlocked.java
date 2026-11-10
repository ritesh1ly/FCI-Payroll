package in.gov.fci.payroll.payroll.api;

/**
 * A member the Actual run could not compute (UC-01 alternate flow: "If an unresolved exception
 * blocks computation for part of the population, the system computes the rest and flags the blocked
 * records on the Exceptions &amp; Holds worklist. Gate G2 stays unmet until they are cleared").
 * Published in the chunk's transaction; the X-05 worklist raises the exception (module 01,
 * SRS-G-9). The field, its source and the screen where it is corrected are named only where the run
 * knows them (null otherwise; never guessed).
 *
 * @param detail what failed (identifiers and codes only, never PAN, bank or Aadhaar)
 */
public record MemberComputationBlocked(
        long cycleInstanceId,
        long employeeId,
        String detail,
        String entityName,
        String fieldName,
        String fieldSource,
        String correctionScreen) {}
