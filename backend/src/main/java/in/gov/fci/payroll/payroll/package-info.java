/**
 * Payroll module. Pay rules (business) and the payroll / DA arrear Spring Batch jobs that apply
 * them.
 *
 * <p>Layers: {@code web} (REST adapters) &rarr; {@code orchestration} (use cases, transactions)
 * &rarr; {@code business} (domain model and rules) and {@code persistence} (repositories). Other
 * modules may use only its named interfaces ({@code api}, …); the allowed dependencies below are
 * enforced by {@code ModularStructureTest}.
 */
@ApplicationModule(
        displayName = "Payroll",
        allowedDependencies = {
            "platform",
            "jobs::batch",
            "jobs::api",
            "organisation::api",
            "organisation::model",
            "configuration::api",
            "workflow::api",
            "audit::api",
            "document::api"
        })
package in.gov.fci.payroll.payroll;

import org.springframework.modulith.ApplicationModule;
