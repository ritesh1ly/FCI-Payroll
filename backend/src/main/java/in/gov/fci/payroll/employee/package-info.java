/**
 * Employee module. The employee master (M-01), eSeva (E-01) and the MIS head count (R-01).
 *
 * <p>Layers: {@code web} (REST adapters) &rarr; {@code orchestration} (use cases, transactions)
 * &rarr; {@code business} (domain model and rules) and {@code persistence} (repositories). Other
 * modules may use only its named interfaces ({@code api}, …); the allowed dependencies below are
 * enforced by {@code ModularStructureTest}.
 */
@ApplicationModule(
        displayName = "Employee",
        allowedDependencies = {
            "platform",
            "arrear::api",
            "workflow::api",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "configuration::api",
            "payroll::api",
            "document::api"
        })
package in.gov.fci.payroll.employee;

import org.springframework.modulith.ApplicationModule;
