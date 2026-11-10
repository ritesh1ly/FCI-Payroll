package in.gov.fci.payroll.platform.persistence;

import java.sql.Date;
import java.time.YearMonth;

/**
 * SRS-16-7, UC Staff Transfer (M-11) postcondition l.1333: "Historical outputs stay retrievable
 * under the old staff number. New outputs use the new staff number, cross-referenced by the
 * staff-number history record." The staff number in force on a day is the {@code old_emp_num} of
 * the member's first {@code employee_number_history} row dated after that day (the number the
 * member held until it was reissued), else the current {@code employee.emp_num}.
 *
 * <p>An output of a period takes the number in force on the period's last day (OD-67): a month that
 * ended before the transfer prints the old number; the month of the transfer and every later one
 * print the new number (SRS-16-4: the new number is issued on the transfer).
 *
 * <p>Payroll results keep no staff number of their own (DDL v4.0 {@code payroll_result}), so every
 * output that prints a member's staff number for a period uses {@link #OF_EMPLOYEE_E}: a scalar
 * subquery with one bind (the day), Oracle 19c and H2 alike, served by {@code
 * employee_number_history}'s employee key.
 */
public final class StaffNumberInForce {

    private StaffNumberInForce() {}

    /**
     * The staff number in force on a bound day ({@code ?}, a {@link Date}) of the member {@code e}
     * ({@code employee e}): a compile-time constant, so a statement that embeds it stays a constant
     * text with binds only (no SQL built at run time).
     */
    public static final String OF_EMPLOYEE_E =
            "COALESCE((SELECT h.old_emp_num FROM employee_number_history h WHERE h.employee_id ="
                    + " e.employee_id AND h.change_date > ? ORDER BY h.change_date,"
                    + " h.emp_num_hist_id FETCH FIRST 1 ROWS ONLY), e.emp_num)";

    /** The day an output of {@code period} takes its staff number on: the period's last day. */
    public static Date dayOf(YearMonth period) {
        return Date.valueOf(period.atEndOfMonth());
    }
}
