/**
 * Employee Self-Service, eSeva (KB Module 14; SRS v0.5 §6.16, SRS-17-1 … 17-18; plan {@code
 * docs/plan/14-eseva.md}): the Self-Service Submission Log ({@code ess_submission}) that every
 * owning module feeds through {@code platform.selfservice} events, the staff support preview
 * ({@code ess_session}, submit actions disabled) and the privacy notice of the first sign-in. The
 * member's cards themselves (payslips, declarations, CPF requests and the VPF change, HRMS Update
 * Requests) stay in their owning modules.
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}. This
 * module depends on no business module except the payroll's lock event and the loans' instalment
 * change, so every one of them can feed the log.
 */
@ApplicationModule(
        displayName = "eSeva (Module 14)",
        allowedDependencies = {
            "platform",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "payroll::api",
            "loans::api",
            "configuration::api",
            "employee::api",
            "workflow::api",
            "jobs::api",
            "integration::api"
        })
package in.gov.fci.payroll.eseva;

import org.springframework.modulith.ApplicationModule;
