package in.gov.fci.payroll.workflow.api;

/** The answer to a maker's submission: the approval item and its status. */
public record Submitted(Long approvalId, String status) {}
