/**
 * Arrears and retrospective processing (KB Module 13; SRS v0.5 §6.15; plan {@code
 * docs/plan/13-arrears-retro-wage-revision.md}): the I-08 register of arrear events with their
 * differentials per member, component and month, the Maker's submission and the Payroll Checker's
 * Approve &amp; Merge, the payment in the next open cycle (component-wise arrear pay codes, a net
 * recovery within the net-pay floor), the posting at the lock, the controlled undo and the Arrears
 * Register. The DA arrear job that raises events stays in the payroll module (C-13).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}; the
 * payroll engine and the cycle reach this module only through their ports ({@code payroll::api}).
 */
@ApplicationModule(
        displayName = "Arrears & retrospective processing (Module 13)",
        allowedDependencies = {
            "platform",
            "audit::api",
            "organisation::api",
            "organisation::model",
            "payroll::api",
            "configuration::api",
            "document::api",
            "workflow::api"
        })
package in.gov.fci.payroll.arrear;

import org.springframework.modulith.ApplicationModule;
