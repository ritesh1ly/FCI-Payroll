/**
 * The workflow module's published interface: web inputs of maker-checker endpoints ({@link
 * in.gov.fci.payroll.workflow.api.Otp}, {@link in.gov.fci.payroll.workflow.api.Submitted}), the
 * SPIs it needs from other modules ({@link in.gov.fci.payroll.workflow.api.SecondFactor}, {@link
 * in.gov.fci.payroll.workflow.api.ApprovalSubjects}), the governed-action catalogue and the inbox
 * maintenance. Submission itself is the platform contract {@code platform.approval.MakerChecker}.
 */
@NamedInterface("api")
package in.gov.fci.payroll.workflow.api;

import org.springframework.modulith.NamedInterface;
