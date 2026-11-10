/**
 * Loans &amp; Advances module (KB Module 09; SRS v0.5 §6.11; plan {@code
 * docs/plan/09-loans-advances.md}): the loan-type master, sanctions with their recovery schedule
 * versions (principal and interest separately), the monthly recovery through the staff payroll, the
 * loan ledger, foreclosure, reschedule, stop on separation and the transfer of the outstanding
 * between units. Employee loans come from HRMS through I-21; the Payroll Maker sets up only the
 * advance types not held in HRMS (SRS-12-17).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}; the
 * payroll engine and the cycle reach this module only through their ports ({@code payroll::api}).
 * Every ceiling, rate and limit is a rule-engine value ({@code configuration::api}); an unset one
 * answers "not configured" (R-RULE).
 */
@ApplicationModule(
        displayName = "Loans & advances (Module 09)",
        allowedDependencies = {
            "platform",
            "audit::api",
            "workflow::api",
            "organisation::api",
            "organisation::model",
            "employee::api",
            "general::api",
            "payroll::api",
            "configuration::api",
            "document::api"
        })
package in.gov.fci.payroll.loans;

import org.springframework.modulith.ApplicationModule;
