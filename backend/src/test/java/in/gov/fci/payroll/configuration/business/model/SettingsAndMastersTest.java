package in.gov.fci.payroll.configuration.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.business.MasterVersioning;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A-07 settings as read by the platform and the dated-master rule shared by M-04 / M-22 / M-23 /
 * M-24: a new version starts after the approved one in force and never on a date another version
 * already uses.
 */
class SettingsAndMastersTest {

    private static final LocalDate D = LocalDate.of(2026, 4, 1);

    @Test
    void A_07_aSettingCombinesItsRuleBoundsAndVersion() {
        RuleDefinition rule = BeanUtils.instantiateClass(RuleDefinition.class);
        ReflectionTestUtils.setField(rule, "id", 1L);
        ReflectionTestUtils.setField(rule, "key", "LOGIN_MAX_ATTEMPTS");
        RuleSettingBound bound = BeanUtils.instantiateClass(RuleSettingBound.class);
        ReflectionTestUtils.setField(bound, "group", "SECURITY");
        ReflectionTestUtils.setField(bound, "minValue", 3L);
        ReflectionTestUtils.setField(bound, "maxValue", 10L);
        ReflectionTestUtils.setField(bound, "unit", "attempts");
        ReflectionTestUtils.setField(bound, "enforced", true);
        RuleVersion version =
                RuleVersion.propose(1L, 2, D, new BigDecimal("5"), "admin", 4L, Instant.EPOCH);
        ReflectionTestUtils.setField(version, "id", 8L);
        SystemParameter p = SystemParameter.of(rule, bound, version);
        assertThat(p.ruleId()).isEqualTo(1L);
        assertThat(p.versionId()).isEqualTo(8L);
        assertThat(p.code()).isEqualTo("LOGIN_MAX_ATTEMPTS");
        assertThat(p.group()).isEqualTo("SECURITY");
        assertThat(p.value()).isEqualTo(5L);
        assertThat(p.minValue()).isEqualTo(3L);
        assertThat(p.maxValue()).isEqualTo(10L);
        assertThat(p.unit()).isEqualTo("attempts");
        assertThat(p.enforced()).isTrue();
        assertThat(p.versionNo()).isEqualTo(2);
        assertThat(p.effectiveFrom()).isEqualTo(D);
        assertThat(p.changedBy()).isEqualTo("admin");
        RuleVersion empty = RuleVersion.propose(1L, 3, D, null, "admin", 4L, Instant.EPOCH);
        assertThat(SystemParameter.of(rule, bound, empty).value())
                .as("no value reads as 0")
                .isZero();
    }

    private static void free(LocalDate from, List<LocalDate> approved, List<LocalDate> all) {
        MasterVersioning.requireFreeDate(from, approved, all);
    }

    @Test
    void R_RULE_aNewVersionStartsAfterTheApprovedOneInForce() {
        assertThatCode(() -> free(D, List.of(), List.of())).doesNotThrowAnyException();
        assertThatCode(() -> free(D.plusDays(1), List.of(D), List.of(D)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> free(D, List.of(D.minusDays(5), D), List.of(D)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ApiErrorCode.MASTER_DATE_NOT_AFTER);
                            assertThat(e.getMessageArgs()).containsExactly(D);
                        });
        assertThatThrownBy(() -> free(D.minusDays(1), List.of(D), List.of(D)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.MASTER_DATE_NOT_AFTER));
    }

    @Test
    void R_RULE_aDateAlreadyUsedByAPendingOrRejectedVersionIsTaken() {
        assertThatThrownBy(() -> free(D.plusDays(5), List.of(D), List.of(D, D.plusDays(5))))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.MASTER_DATE_TAKEN);
                            assertThat(e.getMessageArgs()).containsExactly(D.plusDays(5));
                        });
        assertThatCode(() -> free(D.plusDays(6), List.of(D), List.of(D, D.plusDays(5))))
                .doesNotThrowAnyException();
    }
}
