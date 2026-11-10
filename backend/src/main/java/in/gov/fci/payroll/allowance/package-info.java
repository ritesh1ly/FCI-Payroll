/**
 * Allowances &amp; Reimbursements module (KB Module 10; SRS v0.5 §6.12; plan {@code
 * docs/plan/10-allowances-reimbursements.md}): reimbursement claims (labour claims entered on I-03
 * under maker-checker; employee claims received from HRMS through I-21), their adjudication against
 * the M-15 norms (the lesser of claimed and entitlement, capped not rejected), their payment in the
 * next open staff cycle, and the cafeteria allowance selections received from HRMS (I-16).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}; the
 * payroll engine and the cycle reach this module only through their ports ({@code payroll::api});
 * I-21 through {@code employee::api}. Every norm and pay code is configuration; an unset one
 * answers "not configured" (R-RULE).
 */
@ApplicationModule(
        displayName = "Allowances & reimbursements (Module 10)",
        allowedDependencies = {
            "platform",
            "audit::api",
            "workflow::api",
            "organisation::api",
            "organisation::model",
            "employee::api",
            "general::api",
            "payroll::api",
            "arrear::api",
            "configuration::api",
            "document::api"
        })
package in.gov.fci.payroll.allowance;

import org.springframework.modulith.ApplicationModule;
