package in.gov.fci.payroll.platform.approval;

/** The approval-queue item a submission created. */
public record SubmittedApproval(Long id, ApprovalStatus status, Long otpChallengeId) {}
