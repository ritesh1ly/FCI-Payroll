/**
 * CPF / PF &amp; VPF module (KB Module 07, incl. the CPF Compliance Extension; SRS v0.5 §6.9, FRS
 * V0.5 §6.13; plan {@code docs/plan/07-cpf.md}): PF interest rates and CPF parameters (M-19, M-18),
 * the member ledger, opening-balance corrections (I-22), the interest run and freeze (C-12),
 * withdrawals and sanctions (I-04), deputation (I-09), CPF supplementary payments (I-12), the
 * parent-zone change (I-13) and the CPF reports (O-07, O-08).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}, with
 * {@code persistence} beside orchestration; {@code api} is what the payroll engines and other
 * modules may call. Rates and parameters come from the rule engine ({@code configuration::api});
 * modules not built yet are reached through ports that answer "not configured" (plan D-3).
 */
@ApplicationModule(
        displayName = "CPF / PF & VPF (Module 07)",
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
package in.gov.fci.payroll.cpf;

import org.springframework.modulith.ApplicationModule;
