/**
 * Workflow module. Maker-checker: approval authority, approval queue, inbox (W-01), submissions
 * (W-02), governed actions. Implements platform.approval; publishes ApprovalDecided.
 *
 * <p>Layers: {@code web} (REST adapters) &rarr; {@code orchestration} (use cases, transactions)
 * &rarr; {@code business} (domain model and rules) and {@code persistence} (repositories). Other
 * modules may use only its named interfaces ({@code api}, …); the allowed dependencies below are
 * enforced by {@code ModularStructureTest}.
 */
@ApplicationModule(
        displayName = "Workflow",
        allowedDependencies = {
            "platform",
            "audit::api",
            "organisation::api",
            "organisation::model"
        })
package in.gov.fci.payroll.workflow;

import org.springframework.modulith.ApplicationModule;
