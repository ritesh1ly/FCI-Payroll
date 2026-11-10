/**
 * Labour common rules and Departmental Labour payroll (KB Module 20; SRS v0.5 SRS-2-1 … 2-59,
 * SRS-3-1 … 3-16; plan {@code docs/plan/20-labour-common-departmental.md}): the Labour Master
 * (M-02) and Gang / Shed Structure (M-09) of DDL Section 4, kept in Payroll because labour are not
 * in HRMS (SRS-2-27); attendance, work-slips, norms and the Departmental engine follow in phases
 * 20b to 20e. The person record is written through {@code employee::api} LabourPersons.
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}.
 */
@ApplicationModule(
        displayName = "Labour common + Departmental (Module 20)",
        allowedDependencies = {
            "platform",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "configuration::api",
            "document::api",
            "employee::api",
            "payroll::api",
            "general::api"
        })
package in.gov.fci.payroll.labour;

import org.springframework.modulith.ApplicationModule;
