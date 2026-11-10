package in.gov.fci.payroll.general.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.general.business.FallbackReconciliation.Comparison;
import in.gov.fci.payroll.general.business.FallbackReconciliation.Facts;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** SRS-G-47, Annexure A.11: how a reconciled line is laid out on X-07. */
class FallbackReconciliationTest {

    private static final String ATTENDANCE = "ATTENDANCE_LEAVE";

    private static Facts days(String summary) {
        return new Facts(
                ATTENDANCE,
                summary,
                new BigDecimal("28.00"),
                new BigDecimal("2.00"),
                new BigDecimal("4.50"),
                new BigDecimal("1"),
                null);
    }

    @Test
    void SRS_G_47_aDayItemComparesTheUploadedDaysWithHrmsAndGivesTheSize() {
        assertThat(FallbackReconciliation.compare(days("LWP_DAYS: 3")))
                .isEqualTo(new Comparison("LWP_DAYS", "2", "3", "1"));
        assertThat(FallbackReconciliation.compare(days("HALF_PAY_DAYS: 2")))
                .isEqualTo(new Comparison("HALF_PAY_DAYS", "4.5", "2", "2.5"));
        assertThat(FallbackReconciliation.compare(days("PAY_DAYS: 28")))
                .isEqualTo(new Comparison("PAY_DAYS", "28", "28", "0"));
        assertThat(FallbackReconciliation.compare(days("OTHER_LEAVE_DAYS: 0")))
                .isEqualTo(new Comparison("OTHER_LEAVE_DAYS", "1", "0", "1"));
    }

    @Test
    void SRS_G_47_aLineWithoutAKnownItemComparesAllItems() {
        assertThat(FallbackReconciliation.compare(days(null)))
                .isEqualTo(new Comparison(FallbackReconciliation.ALL_ITEMS, null, null, null));
        assertThat(FallbackReconciliation.compare(days("Match")))
                .isEqualTo(new Comparison(FallbackReconciliation.ALL_ITEMS, null, "Match", null));
        assertThat(FallbackReconciliation.compare(days("ALL: Match")))
                .isEqualTo(new Comparison(FallbackReconciliation.ALL_ITEMS, null, "Match", null));
    }

    @Test
    void SRS_G_47_aValueThatIsNoNumberStaysUnmeasured() {
        assertThat(FallbackReconciliation.compare(days("LWP_DAYS: three")))
                .isEqualTo(new Comparison("LWP_DAYS", "2", null, null));
        Facts none = new Facts(ATTENDANCE, "LWP_DAYS: 3", null, null, null, null, null);
        assertThat(FallbackReconciliation.compare(none))
                .isEqualTo(new Comparison("LWP_DAYS", null, "3", null));
    }

    @Test
    void BR_INT_011_anEventLineComparesItsEffectiveDate() {
        LocalDate date = LocalDate.of(2026, 7, 1);
        Facts dated =
                new Facts("REVOCATION", "REVOCATION: 2026-07-02", null, null, null, null, date);
        assertThat(FallbackReconciliation.compare(dated))
                .isEqualTo(new Comparison("REVOCATION", "2026-07-01", "2026-07-02", null));
        Facts bare = new Facts("SUSPENSION", "2026-07-01", null, null, null, null, date);
        assertThat(FallbackReconciliation.compare(bare).hrms()).isEqualTo("2026-07-01");
        Facts missing = new Facts("ABSCONDING", null, null, null, null, null, null);
        assertThat(FallbackReconciliation.compare(missing))
                .isEqualTo(new Comparison("ABSCONDING", null, null, null));
    }
}
