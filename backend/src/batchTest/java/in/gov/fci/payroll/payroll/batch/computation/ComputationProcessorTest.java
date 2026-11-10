package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.support.InvalidRecordException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Spring Batch adapter of PayRules: stop / hold filtered, data gaps skippable, computed members
 * passed on.
 */
class ComputationProcessorTest {

    private final ComputationProcessor processor =
            new ComputationProcessor(
                    new PayConfiguration.Snapshot(
                            LocalDate.of(2026, 7, 1),
                            Map.of("IDA", BigDecimal.TEN),
                            Map.of("X", BigDecimal.TEN),
                            Map.of(),
                            true,
                            Map.of(101, 1L, 111, 2L, 121, 3L),
                            Map.of(),
                            "h"));

    private static MemberReader.MemberRow row(BigDecimal basic, String lastPayEvent) {
        return new MemberReader.MemberRow(
                7L, 3L, basic, "IDA", "X", "IN_SERVICE", null, lastPayEvent, "CCA", null);
    }

    @Test
    void computedMemberIsPassedToTheWriter() {
        ComputationProcessor.Computed c = processor.process(row(new BigDecimal("1000"), null));
        assertThat(c).isNotNull();
        assertThat(c.outcome().gross()).isEqualByComparingTo("1200");
        assertThat(c.member().itemKey()).isEqualTo("employee:7");
    }

    @Test
    void salaryStopAndHoldAreFilteredButReleaseIsPaid() {
        assertThat(processor.process(row(new BigDecimal("1000"), "SALARY_STOP"))).isNull();
        // SRS-1-41: a HOLD is Salary Withheld: computed, its payment held.
        ComputationProcessor.Computed withheld =
                processor.process(row(new BigDecimal("1000"), "HOLD"));
        assertThat(withheld).isNotNull();
        assertThat(withheld.outcome().paymentHold()).isEqualTo("SALARY_WITHHELD");
        // SRS-1-1: a released salary is computed in full and its payment is not held.
        ComputationProcessor.Computed released =
                processor.process(row(new BigDecimal("1000"), "SALARY_RELEASE"));
        assertThat(released).isNotNull();
        assertThat(released.outcome().gross()).isEqualByComparingTo("1200");
        assertThat(released.outcome().paymentHold()).isNull();
        assertThat(processor.process(row(new BigDecimal("1000"), null)).outcome().paymentHold())
                .isNull();
    }

    @Test
    void aDataGapBecomesASkippableInvalidRecord() {
        assertThatThrownBy(() -> processor.process(row(new BigDecimal("-1"), null)))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("employee 7");
    }

    @Test
    void SRS_1_14_aMemberWithoutAPayScaleInForceIsBlockedNotComputed() {
        MemberReader.MemberRow noScale =
                new MemberReader.MemberRow(
                        7L,
                        3L,
                        new BigDecimal("1000"),
                        "IDA",
                        "X",
                        "IN_SERVICE",
                        null,
                        null,
                        "CCA",
                        null,
                        false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.process(noScale))
                .isInstanceOf(MemberBlockedException.class)
                .hasMessageContaining("No pay scale in force");
        // A stopped salary is not computed, so its scale is not checked.
        MemberReader.MemberRow stopped =
                new MemberReader.MemberRow(
                        7L,
                        3L,
                        new BigDecimal("1000"),
                        "IDA",
                        "X",
                        "IN_SERVICE",
                        null,
                        "SALARY_STOP",
                        "CCA",
                        null,
                        false);
        assertThat(processor.process(stopped)).isNull();
    }
}
