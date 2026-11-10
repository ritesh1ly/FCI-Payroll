package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** SRS-16-7, OD-67: the staff number an output of a period prints. */
class StaffNumberInForceTest {

    @Test
    void SRS_16_7_theNumberIsTheOldOneOfTheFirstReissueAfterTheDayElseTheCurrentOne() {
        String sql = StaffNumberInForce.OF_EMPLOYEE_E;
        assertThat(sql)
                .startsWith("COALESCE((SELECT h.old_emp_num FROM employee_number_history h")
                .contains("h.employee_id = e.employee_id")
                .contains("h.change_date > ?")
                .contains("ORDER BY h.change_date, h.emp_num_hist_id FETCH FIRST 1 ROWS ONLY")
                .endsWith(", e.emp_num)");
        assertThat(sql.chars().filter(c -> c == '?').count()).as("one bind: the day").isEqualTo(1);
    }

    @Test
    void SRS_16_7_aPeriodTakesItsLastDayIncludingFebruaryOfALeapYear() {
        assertThat(StaffNumberInForce.dayOf(YearMonth.of(2026, 6)))
                .isEqualTo(Date.valueOf("2026-06-30"));
        assertThat(StaffNumberInForce.dayOf(YearMonth.of(2028, 2)))
                .isEqualTo(Date.valueOf("2028-02-29"));
        assertThat(StaffNumberInForce.dayOf(YearMonth.of(2027, 2)))
                .isEqualTo(Date.valueOf("2027-02-28"));
    }
}
