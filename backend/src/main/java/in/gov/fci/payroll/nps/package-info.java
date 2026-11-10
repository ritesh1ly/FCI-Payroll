/**
 * NPS deduction as received from HRMS (KB Module 18; SRS v0.5 §7.5; plan {@code
 * docs/plan/18-nps-deduction.md}): the monthly NPS record HRMS sends ({@code nps_deduction}), its
 * flag / amount check that holds the lock, the employee NPS deducted as received and the employer
 * NPS on both sides of the payslip, screen I-14 and the NPS Deduction Report. Payroll never
 * computes an NPS amount, holds no NPS balance and has no NPS CRA interface (CF-02, SRS-INT-41).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}; {@code
 * api} is the intake the HRMS interface (module 23) calls. The payroll engine and the cycle reach
 * this module only through their own ports ({@code payroll::api} StaffDeductions, PreLockCheck,
 * CycleLocked).
 */
@ApplicationModule(
        displayName = "NPS deduction (Module 18)",
        allowedDependencies = {
            "platform",
            "arrear::api",
            "employee::api",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "general::api",
            "payroll::api"
        })
package in.gov.fci.payroll.nps;

import org.springframework.modulith.ApplicationModule;
