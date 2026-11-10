package in.gov.fci.payroll.configuration.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Who changed a label (SRS-G-39), a circular (SRS-G-38) or an operation (SRS-G-41) last, and an
 * operation's optional norm links.
 */
class VersionStampsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final Instant MADE = Instant.parse("2026-10-23T05:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-24T05:00:00Z");

    private static void stamped(Object version) {
        assertThat(ReflectionTestUtils.getField(version, "changedBy")).isEqualTo("checker");
        assertThat(ReflectionTestUtils.getField(version, "changedAt")).isEqualTo(LATER);
    }

    private static LabelMaster label() {
        return LabelMaster.propose(
                new LabelMaster.Particulars(
                        "screen.title",
                        LabelMaster.Type.UI_LABEL,
                        null,
                        null,
                        "en",
                        LabelMaster.Scope.GLOBAL,
                        null,
                        "Title",
                        true,
                        DAY),
                1,
                "maker",
                "new",
                MADE);
    }

    private static CircularOrder circular() {
        return CircularOrder.record(
                new CircularOrder.Particulars(
                        "HQ/DA/2026/3", DAY, "HQ_PERSONNEL", 1L, "DA", "DA", true, DAY, null, null),
                "maker",
                MADE);
    }

    @Test
    void SRS_G_39_eachLabelDecisionRecordsWhoMadeIt() {
        LabelMaster decided = label();
        decided.decided(true, "checker", LATER);
        stamped(decided);
        LabelMaster superseded = label();
        superseded.supersede(DAY.plusDays(5), "checker", LATER);
        stamped(superseded);
        assertThat(superseded.getEffectiveTo()).isEqualTo(DAY.plusDays(4));
        LabelMaster withdrawn = label();
        withdrawn.withdraw(DAY.plusDays(2), "checker", "obsolete", LATER);
        stamped(withdrawn);
    }

    @Test
    void SRS_G_38_eachCircularDecisionRecordsWhoMadeIt() {
        CircularOrder decided = circular();
        decided.decided(true, null, "checker", LATER);
        stamped(decided);
        CircularOrder superseded = circular();
        superseded.supersede("checker", LATER);
        stamped(superseded);
        CircularOrder withdrawn = circular();
        withdrawn.withdraw("obsolete", "checker", LATER);
        stamped(withdrawn);
    }

    @Test
    void SRS_G_41_anOperationKeepsItsOptionalNormLinks() {
        OperationMaster linked =
                OperationMaster.propose(
                        "OP1",
                        new OperationMaster.Particulars(
                                "Loading",
                                true,
                                false,
                                false,
                                true,
                                OperationMaster.NormType.HANDLING,
                                OperationMaster.RateNormLink.NORM_SLAB,
                                false,
                                null,
                                false,
                                null),
                        1,
                        DAY,
                        5L);
        assertThat(linked).isNotNull();
        assertThat(linked.getNormType()).isEqualTo("HANDLING");
        assertThat(linked.getRateNormLink()).isEqualTo("NORM_SLAB");
        OperationMaster bare =
                OperationMaster.propose(
                        "OP2",
                        new OperationMaster.Particulars(
                                "Sweeping",
                                true,
                                false,
                                false,
                                false,
                                null,
                                null,
                                false,
                                null,
                                false,
                                null),
                        1,
                        DAY,
                        6L);
        assertThat(bare.getNormType()).isNull();
        assertThat(bare.getRateNormLink()).isNull();
    }
}
