package in.gov.fci.payroll.configuration.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * M-23 other parameters, M-24 GL mappings and A-07 setting bounds (R-RULE): a version covers its
 * window with both ends included and is ended the day before its successor; a setting admits values
 * inside its bounds, both bounds included.
 */
class MasterWindowsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);

    @Test
    void M_23_aParameterVersionHoldsItsValuesAndCoversItsWindow() {
        OtherParameter p =
                OtherParameter.propose(
                        "DEPUTATION_PCT_WITHIN",
                        "Deputation within",
                        new BigDecimal("1"),
                        new BigDecimal("2"),
                        new BigDecimal("10"),
                        "09:00",
                        "17:30",
                        12,
                        "STAFF",
                        4,
                        FROM,
                        true,
                        77L);
        assertThat(p.getParameterCode()).isEqualTo("DEPUTATION_PCT_WITHIN");
        assertThat(p.getParameterName()).isEqualTo("Deputation within");
        assertThat(p.getNumericValue()).isEqualByComparingTo("1");
        assertThat(p.getCeilingValue()).isEqualByComparingTo("2");
        assertThat(p.getPercentValue()).isEqualByComparingTo("10");
        assertThat(p.getWindowFromTime()).isEqualTo("09:00");
        assertThat(p.getWindowToTime()).isEqualTo("17:30");
        assertThat(p.getInstalmentCount()).isEqualTo(12);
        assertThat(p.getPayrollTypeScope()).isEqualTo("STAFF");
        assertThat(p.getVersionNo()).isEqualTo(4);
        assertThat(p.isIsConfirmed()).isTrue();
        assertThat(p.getApprovalId()).isEqualTo(77L);
        assertThat(p.coversDay(FROM.minusDays(1))).isFalse();
        assertThat(p.coversDay(FROM)).isTrue();
        assertThat(p.coversDay(FROM.plusYears(20))).isTrue();
        p.endBefore(FROM.plusMonths(1));
        assertThat(p.getEffectiveTo()).isEqualTo(FROM.plusMonths(1).minusDays(1));
        assertThat(p.coversDay(FROM.plusMonths(1).minusDays(1))).isTrue();
        assertThat(p.coversDay(FROM.plusMonths(1))).isFalse();
        OtherParameter unconfirmed =
                OtherParameter.propose(
                        "X", "X", null, null, null, null, null, null, null, 1, FROM, false, null);
        assertThat(unconfirmed.isIsConfirmed()).isFalse();
    }

    @Test
    void M_24_aGlMappingCoversItsWindowAndIsEndedTheDayBeforeItsSuccessor() {
        GlAccountMapping m = GlAccountMapping.propose(3L, 4L, 5L, "4101-01", 2, FROM, 9L);
        assertThat(m.getComponentId()).isEqualTo(3L);
        assertThat(m.getOrgUnitId()).isEqualTo(4L);
        assertThat(m.getCostCentreId()).isEqualTo(5L);
        assertThat(m.getNaturalAccount()).isEqualTo("4101-01");
        assertThat(m.getVersionNo()).isEqualTo(2);
        assertThat(m.getEffectiveFrom()).isEqualTo(FROM);
        assertThat(m.getApprovalId()).isEqualTo(9L);
        assertThat(m.coversDay(FROM.minusDays(1))).isFalse();
        assertThat(m.coversDay(FROM)).isTrue();
        m.endBefore(FROM.plusDays(10));
        assertThat(m.coversDay(FROM.plusDays(9))).isTrue();
        assertThat(m.coversDay(FROM.plusDays(10))).isFalse();
    }

    @Test
    void A_07_aSettingAdmitsValuesWithinItsBoundsBothIncluded() {
        RuleSettingBound b = BeanUtils.instantiateClass(RuleSettingBound.class);
        ReflectionTestUtils.setField(b, "minValue", 5L);
        ReflectionTestUtils.setField(b, "maxValue", 10L);
        assertThat(b.admits(4)).isFalse();
        assertThat(b.admits(5)).isTrue();
        assertThat(b.admits(10)).isTrue();
        assertThat(b.admits(11)).isFalse();
    }
}
