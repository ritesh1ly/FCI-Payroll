/**
 * Audit module. The append-only audit trail and its search and export (A-05).
 *
 * <p>Layers: {@code web} (REST adapters) &rarr; {@code orchestration} (use cases, transactions)
 * &rarr; {@code business} (domain model and rules) and {@code persistence} (repositories). Other
 * modules may use only its named interfaces ({@code api}, …); the allowed dependencies below are
 * enforced by {@code ModularStructureTest}.
 */
@ApplicationModule(
        displayName = "Audit",
        allowedDependencies = {"platform"})
package in.gov.fci.payroll.audit;

import org.springframework.modulith.ApplicationModule;
