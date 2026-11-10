package in.gov.fci.payroll.configuration.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-39 (M-26 labels): a label covers the days from its start to its last day, both included; an
 * open label covers every later day; a withdrawn label stops after its last day.
 */
class LabelMasterBranchGapsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);

    private static LabelMaster label() {
        return LabelMaster.propose(
                new LabelMaster.Particulars(
                        "m27.op.OP1",
                        LabelMaster.Type.MASTER_VALUE,
                        "OPERATION",
                        "OP1",
                        "HI",
                        LabelMaster.Scope.GLOBAL,
                        null,
                        "Label",
                        true,
                        FROM),
                1,
                "label.maker@x",
                "New label",
                Instant.parse("2026-03-20T05:00:00Z"));
    }

    @Test
    void SRS_G_39_aLabelCoversItsStartToItsLastDayBothIncluded() {
        LabelMaster open = label();
        assertThat(open.coversDay(FROM.minusDays(1))).isFalse();
        assertThat(open.coversDay(FROM)).isTrue();
        assertThat(open.coversDay(FROM.plusYears(30))).isTrue();
        LabelMaster withdrawn = label();
        withdrawn.withdraw(
                FROM.plusDays(9),
                "label.checker@x",
                "Withdrawn",
                Instant.parse("2026-04-05T05:00:00Z"));
        assertThat(withdrawn.coversDay(FROM.plusDays(9))).isTrue();
        assertThat(withdrawn.coversDay(FROM.plusDays(10))).isFalse();
    }
}
