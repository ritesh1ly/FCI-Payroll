/**
 * MIS &amp; Statutory Reporting (KB Module 15; SRS v0.5 §6.17, SRS-18-1 … 18-28; plan {@code
 * docs/plan/15-mis-reporting.md}): the report catalogue on the definition tables of DDL Section 24
 * (M-12 versions under maker-checker), the MIS &amp; Statutory Reports screen O-04 with the status
 * of every catalogue entry (produced here, produced on its own screen, or Blocked with the reason)
 * and the outputs produced here, each run recorded in {@code report_output} and each export in
 * {@code report_export_log}. Reports read committed ACTUAL payroll results only (SRS-18-5).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}.
 */
@ApplicationModule(
        displayName = "MIS & reporting (Module 15)",
        allowedDependencies = {
            "platform",
            "audit::api",
            // SRS-18-9: a field binding resolves to the component catalogue (M-07), the
            // field-source model and the formula grammar of the rule engine (US-GAP-34).
            "configuration::api",
            "organisation::api",
            "organisation::model"
        })
package in.gov.fci.payroll.reporting;

import org.springframework.modulith.ApplicationModule;
