/**
 * Full &amp; Final Settlement module (KB Module 11; SRS v0.5 §6.13; plan {@code
 * docs/plan/11-full-final-settlement.md}): one settlement case per separation, opened on the
 * approved separation, its clearances, the computation (pending salary, leave encashment, arrears,
 * ex-gratia, gratuity, itemised recoveries), the approvals with OTP, the release and the closure,
 * screen O-05 and the Final Settlement Report. The CPF final payment is linked for information only
 * (CF-02: no pension).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}. The
 * owning modules answer the clearances through their published ports; this module never writes
 * another module's tables.
 */
@ApplicationModule(
        displayName = "Full & final settlement (Module 11)",
        allowedDependencies = {
            "platform",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "employee::api",
            "general::api",
            "payroll::api",
            "leave::api",
            "loans::api",
            "penalty::api",
            "allowance::api",
            "arrear::api",
            "cpf::api",
            "document::api",
            "workflow::api"
        })
package in.gov.fci.payroll.settlement;

import org.springframework.modulith.ApplicationModule;
