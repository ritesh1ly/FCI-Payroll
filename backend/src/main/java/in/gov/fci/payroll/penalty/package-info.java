/**
 * Penalty, Recovery &amp; Over-and-Above module (KB Module 12; SRS v0.5 §6.14; plan {@code
 * docs/plan/12-penalty-recovery.md}): penalty and recovery orders with their Penalty ID and
 * recovery schedule, the monthly recovery through the staff payroll within the loans' deduction
 * ceiling and the net-pay floor, Penalty Revoke and cancellation, and over-and-above payments.
 * Employee penalties come from HRMS through I-21 (SRS-14-1, CF-17).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}; the
 * payroll engine and the cycle reach this module only through their ports ({@code payroll::api});
 * the deduction ceiling is the loans' rule ({@code loans::api}, SRS-14-7). Every limit is a
 * rule-engine value; an unset one answers "not configured" (R-RULE).
 */
@ApplicationModule(
        displayName = "Penalty, recovery & over-and-above (Module 12)",
        allowedDependencies = {
            "platform",
            "arrear::api",
            "audit::api",
            "workflow::api",
            "organisation::api",
            "organisation::model",
            "employee::api",
            "general::api",
            "payroll::api",
            "configuration::api",
            "document::api",
            "loans::api"
        })
package in.gov.fci.payroll.penalty;

import org.springframework.modulith.ApplicationModule;
