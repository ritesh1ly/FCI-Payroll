package in.gov.fci.payroll.workflow.api;

import java.time.LocalDate;

/**
 * SRS-22-12: "Apply a third level only where the matrix names one: CPF sanctions, opening-balance
 * corrections at Headquarters, net-recovery settlements, and recoveries against gratuity" (FRS V0.5
 * §6.6.4 matrix). Which approval actions the matrix names a third level for is configuration (the
 * M-16 reference list {@code THIRD_LEVEL_ACTION}, implemented by the configuration module), so a
 * later change of delegation is a configuration change under maker-checker, not a code change.
 */
public interface ThirdLevelActions {

    /** The reference list of the approval actions that may have a third level. */
    String LIST = "THIRD_LEVEL_ACTION";

    /** True if the matrix names a third level for {@code actionCode}, in force on {@code day}. */
    boolean named(String actionCode, LocalDate day);
}
