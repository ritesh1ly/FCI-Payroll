/**
 * Organisation module. The organisation master: zones, locations, departments, designations, pay
 * bands, banks.
 *
 * <p>Layers: {@code web} (REST adapters) &rarr; {@code orchestration} (use cases, transactions)
 * &rarr; {@code business} (domain model and rules) and {@code persistence} (repositories). Other
 * modules may use only its named interfaces ({@code api}, …); the allowed dependencies below are
 * enforced by {@code ModularStructureTest}.
 */
@ApplicationModule(
        displayName = "Organisation",
        allowedDependencies = {"platform"})
package in.gov.fci.payroll.organisation;

import org.springframework.modulith.ApplicationModule;
