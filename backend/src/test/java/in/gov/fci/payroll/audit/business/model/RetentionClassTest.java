package in.gov.fci.payroll.audit.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

/**
 * SRS-20-5: a retention period in months or indefinite, confirmed once set, as its audit rows name
 * it.
 */
class RetentionClassTest {

    @Test
    void SRS_20_5_aPeriodInMonthsIsKeptAndConfirmed() {
        RetentionClass c = BeanUtils.instantiateClass(RetentionClass.class);
        // Not set yet: no months, not indefinite, not confirmed.
        assertThat(c.image())
                .containsEntry("retention_months", null)
                .containsEntry("is_indefinite", "N")
                .containsEntry("is_confirmed", "N");
        c.setPeriod(96, false, "Order 12");
        assertThat(c.image())
                .containsExactly(
                        Map.entry("retention_months", "96"),
                        Map.entry("is_indefinite", "N"),
                        Map.entry("retention_basis", "Order 12"),
                        Map.entry("is_confirmed", "Y"));
    }

    @Test
    void SRS_20_5_anIndefinitePeriodDropsAnyMonths() {
        RetentionClass c = BeanUtils.instantiateClass(RetentionClass.class);
        c.setPeriod(96, true, "Statute");
        Map<String, String> image = c.image();
        assertThat(image.get("retention_months")).isNull();
        assertThat(image.get("is_indefinite")).isEqualTo("Y");
        assertThat(image.get("is_confirmed")).isEqualTo("Y");
    }
}
