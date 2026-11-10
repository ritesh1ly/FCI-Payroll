/**
 * Leave module (KB Module 19; SRS v0.5 §7.6; plan {@code docs/plan/19-leave.md}): the pay effects
 * of employee leave sanctioned in HRMS and received through the interface (leave types for M-13,
 * encashable balances, leave events, encashment sanctions), each event applied through an I-21 task
 * after Payroll maker-checker, the leave pay parameters of M-20 and the I-11 Leave Data View.
 * Payroll never computes an employee's entitlement, accrual or balance and has no application or
 * sanction function (SRS-LV-28). Labour leave (SRS-LV-37) is recorded with the labour modules.
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}; {@code
 * api} is the intake the HRMS interface (module 23) calls. The I-21 task is the employee module's
 * ({@code employee::api} HrmsChanges / HrmsTaskEffects); the cycle reaches this module only through
 * its own ports ({@code payroll::api}).
 */
@ApplicationModule(
        displayName = "Leave (Module 19)",
        allowedDependencies = {
            "platform",
            "arrear::api",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "employee::api",
            "general::api",
            "payroll::api",
            "configuration::api",
            "document::api",
            "workflow::api"
        })
package in.gov.fci.payroll.leave;

import org.springframework.modulith.ApplicationModule;
