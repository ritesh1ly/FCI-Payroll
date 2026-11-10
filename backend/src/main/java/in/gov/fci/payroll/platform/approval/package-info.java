/**
 * The maker-checker contract every module uses: submit ({@link
 * in.gov.fci.payroll.platform.approval.MakerChecker}), read statuses ({@link
 * in.gov.fci.payroll.platform.approval.ApprovalStatuses}) and apply decisions ({@link
 * in.gov.fci.payroll.platform.approval.ApprovalDecided}). The workflow module implements it, so no
 * module depends on workflow's internals and workflow depends on none of theirs.
 */
package in.gov.fci.payroll.platform.approval;
