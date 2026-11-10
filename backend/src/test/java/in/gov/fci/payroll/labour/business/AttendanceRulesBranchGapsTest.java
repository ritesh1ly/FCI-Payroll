package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SRS-2-35: a member's day left without a code is an unknown code, not a valid mark. */
class AttendanceRulesBranchGapsTest {

    @Test
    void SRS_2_35_aMarkWithoutACodeIsAnUnknownCode() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        Map<Long, String> marks = new HashMap<>();
        marks.put(1L, null);
        assertThat(
                        AttendanceRules.dayDefect(
                                today.minusDays(1), today, Set.of(1L), marks, Set.of("W", "A")))
                .contains("UNKNOWN_CODE null");
    }
}
