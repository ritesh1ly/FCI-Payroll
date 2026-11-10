package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The target-DDL v4.0 tables of modules 11 and 13 that hold a rule whose build waits (PENDING
 * 11-freeze, 13-statutory, LBR-20d-2): the database already keeps the invariant, so nothing written
 * later can break it. Each test reads or writes only its own rows.
 */
class SettlementArrearSchemaIT extends ApiTestSupport {

    private int columns(String table, String column) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) ="
                                + " ? AND LOWER(column_name) = ?",
                        Integer.class,
                        table,
                        column);
        return n == null ? 0 : n;
    }

    /**
     * BR-FNF-002 (SRS-10-2): a component with a deduction taken against it is frozen once per
     * settlement - fs_component_freeze keeps one row per settlement and component (the freeze
     * itself is PENDING 11-freeze).
     */
    @Test
    void BR_FNF_002_aComponentIsFrozenOncePerSettlement() {
        Long settlement =
                jdbc.queryForObject("SELECT MIN(settlement_id) FROM final_settlement", Long.class);
        Long component =
                jdbc.queryForObject("SELECT MAX(component_id) FROM pay_component", Long.class);
        assertThat(settlement).as("the demo settlement").isNotNull();
        Integer held =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fs_component_freeze WHERE settlement_id = ? AND"
                                + " component_id = ?",
                        Integer.class,
                        settlement,
                        component);
        if (held == null || held == 0) {
            jdbc.update(
                    "INSERT INTO fs_component_freeze (settlement_id, component_id, frozen_reason)"
                            + " VALUES (?, ?, 'Schema IT')",
                    settlement,
                    component);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO fs_component_freeze (settlement_id,"
                                                + " component_id, frozen_reason) VALUES (?, ?,"
                                                + " 'Schema IT again')",
                                        settlement,
                                        component))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * BR-ARR-DA-004 (SRS-8-4): the arrear engine's event carries its statutory effect through the
     * single arrear mechanism - an event links the posting cycle it merges into (posted_to_yymm),
     * and the statutory computation before the merge is PENDING 13-statutory; SRS-21-4: an
     * over-paid labour incentive on a retrospective norm revision is recomputed per gang and month
     * into an arrear event (labour_incentive_recompute.arrear_event_id; PENDING LBR-20d-2).
     */
    @Test
    void BR_ARR_DA_004_SRS_21_4_theArrearEngineHoldsTheEventItsMergeAndTheIncentiveRecompute() {
        assertThat(columns("arrear_event", "posted_to_yymm")).isEqualTo(1);
        assertThat(columns("arrear_event", "source_module")).isEqualTo(1);
        assertThat(columns("labour_incentive_recompute", "arrear_event_id")).isEqualTo(1);
        assertThat(columns("labour_incentive_recompute", "recompute_reason")).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO labour_incentive_recompute (gang_id,"
                                                + " payroll_yymm, recompute_reason) SELECT"
                                                + " MIN(gang_id), '209901', 'GUESSED' FROM"
                                                + " labour_gang"))
                .as("only the reasons the DDL names")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
