/**
 * Configuration module. DB-backed system parameters: the typed reader and their maker-checker
 * administration (A-07), and the masters of Module 04 (M-14, M-15, M-25, M-26, M-27): the circular
 * master stores its order copy through {@code document::api} and scopes a circular to offices of
 * {@code organisation::api}.
 *
 * <p>Layers: {@code web} (REST adapters) &rarr; {@code orchestration} (use cases, transactions)
 * &rarr; {@code business} (domain model and rules) and {@code persistence} (repositories). Other
 * modules may use only its named interfaces ({@code api}, …); the allowed dependencies below are
 * enforced by {@code ModularStructureTest}.
 */
@ApplicationModule(
        displayName = "Configuration",
        allowedDependencies = {
            "platform",
            "workflow::api",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "document::api"
        })
package in.gov.fci.payroll.configuration;

import org.springframework.modulith.ApplicationModule;
