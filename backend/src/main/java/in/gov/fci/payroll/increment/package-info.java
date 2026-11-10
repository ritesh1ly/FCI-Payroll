/**
 * Increment and pay fixation (KB Module 17; SRS v0.5 §7.4; plan {@code
 * docs/plan/17-increment-pay-fixation.md}): the Basic Pay history and its one writer, the M-03 pay
 * scales, the I-05 annual increment run and individual cases worked out in Payroll, the stagnation
 * increment, the increment-date / Basic corrections, the I-21 Basic Pay and increment-date fields,
 * and the bulk fixation of an approved I-25 wage revision.
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}. A
 * retrospective Basic reaches the arrears through {@code arrear::api} RetroEvents; the I-06 bar
 * through {@code penalty::api} IncrementStops.
 */
@ApplicationModule(
        displayName = "Increment & pay fixation (Module 17)",
        allowedDependencies = {
            "platform",
            "arrear::api",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "employee::api",
            "configuration::api",
            "document::api",
            "payroll::api",
            "penalty::api",
            "workflow::api"
        })
package in.gov.fci.payroll.increment;

import org.springframework.modulith.ApplicationModule;
