/**
 * Jobs module. Batch job submission and control (C-13): the DB job queue, launch, lock, control,
 * recovery, listeners, housekeeping.
 *
 * <p>Layers: {@code web} (REST adapters) &rarr; {@code orchestration} (use cases, transactions)
 * &rarr; {@code business} (domain model and rules) and {@code persistence} (repositories). Other
 * modules may use only its named interfaces ({@code api}, …); the allowed dependencies below are
 * enforced by {@code ModularStructureTest}.
 */
@ApplicationModule(
        displayName = "Jobs",
        allowedDependencies = {
            "platform",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "configuration::api"
        })
package in.gov.fci.payroll.jobs;

import org.springframework.modulith.ApplicationModule;
