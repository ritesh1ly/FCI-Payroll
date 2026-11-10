/**
 * Income Tax, TDS &amp; Professional Tax module (KB Module 08; SRS v0.5 §6.10, FRS V0.5 §14; plan
 * {@code docs/plan/08-income-tax.md}): the tax configuration (section master, PT state master and
 * the yearly rates on M-18), the member's regime and declarations (I-07, E-01), savings and other
 * income (I-18), the tax computation (C-07), the statutory output definitions (M-17), Form 16
 * (C-10) and the quarterly return (C-11).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}, with
 * {@code persistence} beside orchestration; {@code api} is what the payroll engines and other
 * modules may call. Rates come from the rule engine ({@code configuration::api}); modules not built
 * yet are reached through ports that answer "not configured" (plan D-4).
 */
@ApplicationModule(
        displayName = "Income Tax, TDS & PT (Module 08)",
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
            "document::api",
            "jobs::batch",
            "jobs::api"
        })
package in.gov.fci.payroll.tax;

import org.springframework.modulith.ApplicationModule;
