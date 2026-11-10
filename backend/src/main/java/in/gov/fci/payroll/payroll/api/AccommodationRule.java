package in.gov.fci.payroll.payroll.api;

import in.gov.fci.payroll.payroll.business.PayRules;

/**
 * The engine's accommodation test for the modules that show it (SRS-1-3, SRS-11-17): I-03 reads the
 * same answer the run pays on, never a second copy of the rule.
 */
public final class AccommodationRule {

    private AccommodationRule() {}

    /** Whether an accommodation record stops HRA ({@link PayRules#hraSuppressed}). */
    public static boolean hraSuppressed(String accommodationType, boolean suppressedFlag) {
        return PayRules.hraSuppressed(accommodationType, suppressedFlag);
    }
}
