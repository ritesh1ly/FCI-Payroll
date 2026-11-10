package in.gov.fci.payroll.payroll.business;

import in.gov.fci.payroll.organisation.business.model.LocationType;
import java.time.YearMonth;

/**
 * The keys of a payroll cycle instance (SRS-G-10; target DDL v4.0 {@code payroll_cycle_instance}):
 * the month as YYYYMM, the financial year (April to March) and the level of the unit that runs it.
 * One definition for the online cycle (C-02) and the batch runs, so the two can never key the same
 * cycle differently.
 */
public final class CycleKeys {

    private CycleKeys() {}

    /** {@code payroll_yymm}: 2026-04 is "202604". */
    public static String yymm(YearMonth period) {
        return String.format("%04d%02d", period.getYear(), period.getMonthValue());
    }

    /** {@code fin_year}: April to March, so 2026-04 and 2027-03 are both "2026-27". */
    public static String finYear(YearMonth period) {
        int start = period.getMonthValue() >= 4 ? period.getYear() : period.getYear() - 1;
        return start + "-" + String.valueOf(start + 1).substring(2);
    }

    /**
     * {@code org_unit_level} (DDL CHECK HQ, ZO, RO, DO): Headquarters, a Zone and a Region run at
     * their own level; any other cycle unit runs as a Division.
     */
    public static String level(LocationType type) {
        return switch (type) {
            case HQ, ZO, RO -> type.name();
            default -> "DO";
        };
    }

    /**
     * A login as the 50-character *_by columns of the cycle tables take it (an app_user login is up
     * to 150); null for null.
     */
    public static String truncate(String login) {
        return login == null ? null : login.substring(0, Math.min(50, login.length()));
    }
}
