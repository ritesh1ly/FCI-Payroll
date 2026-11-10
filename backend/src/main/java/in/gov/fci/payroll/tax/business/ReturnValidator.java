package in.gov.fci.payroll.tax.business;

import java.util.List;
import java.util.Optional;

/**
 * The prescribed validation utility of the tax department (SRS-7-8, SRS C-11): every generated
 * return passes it before release. A port: the utility is external; until it is installed the
 * shipped implementation answers empty and a return is never VALIDATED (plan 08 §0).
 */
public interface ReturnValidator {

    /** The utility's verdict: passed, or each rejection it listed. */
    record Verdict(String utility, boolean passed, List<String> rejections) {}

    /** The verdict on the return; empty while no utility is configured. */
    Optional<Verdict> validate(Long returnId);
}
