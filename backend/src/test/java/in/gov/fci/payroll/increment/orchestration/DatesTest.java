package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** A DATE column as the drivers return it (H2, Oracle): read as the calendar day it holds. */
class DatesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void everyDateShapeADriverReturnsIsReadAsItsDay() {
        assertThat(Dates.local(null)).isNull();
        assertThat(Dates.local(DAY)).isEqualTo(DAY);
        assertThat(Dates.local(LocalDateTime.of(2026, 10, 1, 23, 59))).isEqualTo(DAY);
        assertThat(Dates.local(Date.valueOf(DAY))).isEqualTo(DAY);
        // Oracle's DATE carries a time: the day is the one it holds, not the next.
        assertThat(Dates.local(Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 23, 59))))
                .isEqualTo(DAY);
    }
}
