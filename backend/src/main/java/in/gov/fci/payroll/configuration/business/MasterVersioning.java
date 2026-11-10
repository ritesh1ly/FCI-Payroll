package in.gov.fci.payroll.configuration.business;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Effective dating of the versioned masters (rate sets, other parameters, GL mappings; target DDL
 * v4.0): versions never overlap, so a new version starts after every approved one (it may still be
 * back-dated before today, e.g. a DA revision with arrears), and no two versions of a master share
 * a start date (the unique keys on code + effective_from). Pure rules.
 */
public final class MasterVersioning {

    private MasterVersioning() {}

    /**
     * @param from the proposed start date
     * @param approvedStarts start dates of the master's approved versions
     * @param allStarts start dates of every version (pending, rejected included)
     */
    public static void requireFreeDate(
            LocalDate from, Collection<LocalDate> approvedStarts, Collection<LocalDate> allStarts) {
        LocalDate latest = approvedStarts.stream().max(LocalDate::compareTo).orElse(null);
        if (latest != null && !from.isAfter(latest)) {
            throw new FciException(
                    ApiErrorCode.MASTER_DATE_NOT_AFTER, new Object[] {latest}, List.of(), null);
        }
        if (allStarts.contains(from)) {
            throw new FciException(
                    ApiErrorCode.MASTER_DATE_TAKEN, new Object[] {from}, List.of(), null);
        }
    }
}
