/**
 * Iam module. Identity and access management: user accounts (A-01), login, passwords, OTP
 * challenges, sessions and the security filter chain; RBAC roles and permissions (A-02), access
 * groups (A-03), assignments (A-04), data scope, the session access context, the authorisation
 * policy and the menu.
 *
 * <p>Layers: {@code web} (REST adapters) &rarr; {@code orchestration} (use cases, transactions)
 * &rarr; {@code business} (domain model and rules) and {@code persistence} (repositories). Other
 * modules may use only its named interfaces ({@code api}, …); the allowed dependencies below are
 * enforced by {@code ModularStructureTest}.
 */
@ApplicationModule(
        displayName = "Iam",
        allowedDependencies = {
            "platform",
            "workflow::api",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "configuration::api",
            "employee::api"
        })
package in.gov.fci.payroll.iam;

import org.springframework.modulith.ApplicationModule;
