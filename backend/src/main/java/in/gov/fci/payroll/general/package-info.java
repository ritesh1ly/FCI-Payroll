/**
 * General module: the shared, cross-module transactions of KB Module 01 "Platform General" (SRS
 * v0.5 §6 General; plan {@code docs/plan/01-platform-general.md}):
 *
 * <ul>
 *   <li>X-06 Payroll Query Management (SRS-G-11);
 *   <li>X-05 Exceptions &amp; Holds Worklist (SRS-G-9);
 *   <li>X-07 HRMS Fallback Upload (SRS-G-46, SRS-G-47);
 *   <li>I-20 Off-Cycle / Exceptional Salary (SRS-G-10);
 *   <li>I-23 Supplementary Payment, post-lock (SRS-G-33, SRS-G-34).
 * </ul>
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}, with
 * {@code persistence} beside orchestration. Attachments go through the document module's API,
 * member and payroll-record lookups through the employee and payroll APIs.
 */
@ApplicationModule(
        displayName = "General (Module 01)",
        allowedDependencies = {
            "platform",
            "audit::api",
            "workflow::api",
            "organisation::api",
            "organisation::model",
            "employee::api",
            "payroll::api",
            "configuration::api",
            "document::api"
        })
package in.gov.fci.payroll.general;

import org.springframework.modulith.ApplicationModule;
