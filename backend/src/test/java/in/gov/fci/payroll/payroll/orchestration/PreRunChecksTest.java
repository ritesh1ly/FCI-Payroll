package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.payroll.orchestration.PreRunChecks.Check;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The initiation checks P1-P5 (SRS-15-23; plan 05 D-22): P1 names the members retired before the
 * period, P2 passes without a shared FPS number; an inactive check is "not available" and does not
 * block; an active check without an evaluator, or without a rule key, fails and blocks.
 */
class PreRunChecksTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final SimulationEngine population = mock(SimulationEngine.class);
    private final PreRunChecks checks = new PreRunChecks(db.named, population);

    private static Map<String, Object> check(String code, String active, String key) {
        return row(
                "check_code", code,
                "check_name", code + " check",
                "is_active", active,
                "evaluation_rule_key", key,
                "correction_screen", "M-01");
    }

    @Test
    void SRS_15_23_activeChecksAreEvaluatedAndOnlyFailuresBlock() {
        when(population.locations(40L)).thenReturn(List.of(40L, 41L));
        db.rows(
                        "FROM pre_payroll_check WHERE check_stage",
                        check("P1", "Y", "P1_RETIREMENT"),
                        check("P2", "Y", "P2_DUPLICATE_FPS"),
                        check("P3", "N", "P3_BANK"),
                        check("P4", "Y", "P4_NOT_BUILT"),
                        check("P5", "Y", null))
                .rows("e.retirement_date < :start", cols("E0101"), cols("E0102"));
        List<Check> out = checks.evaluate(40L, YearMonth.of(2026, 11));
        assertThat(out)
                .extracting(Check::code, Check::status, Check::failures)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("P1", PreRunChecks.FAIL, 2L),
                        org.assertj.core.groups.Tuple.tuple("P2", PreRunChecks.PASS, 0L),
                        org.assertj.core.groups.Tuple.tuple("P3", PreRunChecks.NOT_AVAILABLE, 0L),
                        org.assertj.core.groups.Tuple.tuple("P4", PreRunChecks.FAIL, 1L),
                        org.assertj.core.groups.Tuple.tuple("P5", PreRunChecks.FAIL, 1L));
        assertThat(out.get(0).members()).containsExactly("E0101", "E0102");
        assertThat(PreRunChecks.blocking(out)).containsExactly("P1", "P4", "P5");
    }
}
