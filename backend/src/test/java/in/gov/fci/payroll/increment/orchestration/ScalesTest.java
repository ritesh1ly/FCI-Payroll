package in.gov.fci.payroll.increment.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-13-23 (M-03): the scale of a member on a day is the approved version of the member's band in
 * force on that day, with its bounds, stagnation amount, rule and stages; a member without a band,
 * or a band without a version in force, has none.
 */
class ScalesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Scales scales = new Scales(db.named);

    @Test
    void SRS_13_23_aMemberWithoutABandHasNoScale() {
        assertThat(scales.ofMember(7L, DAY)).isEmpty();
        assertThat(db.queries("FROM pay_scale_band_version")).isEmpty();
    }

    @Test
    void SRS_13_23_aBandWithoutAnApprovedVersionInForceHasNoScale() {
        db.rows("SELECT band_id FROM employee", row("band_id", 2L));
        assertThat(scales.ofMember(7L, DAY)).isEmpty();
        assertThat(db.queries("FROM pay_scale_band_stage")).isEmpty();
    }

    @Test
    void SRS_13_23_theVersionInForceGivesItsBoundsRuleAndStages() {
        db.rows("SELECT band_id FROM employee", row("band_id", 2L))
                .rows(
                        "FROM pay_scale_band_version",
                        row(
                                "band_version_id",
                                12L,
                                "min_basic",
                                new BigDecimal("40000"),
                                "max_basic",
                                new BigDecimal("140000"),
                                "stagnation_increment_amt",
                                new BigDecimal("3000"),
                                "band_code",
                                "E-4",
                                "increment_rule_key",
                                "INCR.ANNUAL"))
                .rows(
                        "FROM pay_scale_band_stage",
                        row("stage_basic", new BigDecimal("40000")),
                        row("stage_basic", new BigDecimal("41200")));
        Scales.Scale scale = scales.ofMember(7L, DAY).orElseThrow();
        assertThat(scale.bandId()).isEqualTo(2L);
        assertThat(scale.bandCode()).isEqualTo("E-4");
        assertThat(scale.versionId()).isEqualTo(12L);
        assertThat(scale.min()).isEqualByComparingTo("40000");
        assertThat(scale.max()).isEqualByComparingTo("140000");
        assertThat(scale.stagnationAmount()).isEqualByComparingTo("3000");
        assertThat(scale.incrementRuleKey()).isEqualTo("INCR.ANNUAL");
        assertThat(scale.stages())
                .containsExactly(new BigDecimal("40000"), new BigDecimal("41200"));
        assertThat(List.copyOf(db.queries("FROM pay_scale_band_version"))).hasSize(1);
    }
}
