package in.gov.fci.payroll.arrear.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The lock of a staff cycle (SRS-8-8, SRS-8-17; plan 13 D13-2, D13-3): a recovery the cycle did not
 * take in full is carried as a new detail for the rest; without the recovery pay code (M-16
 * ARREAR_PAY_CODE) nothing counts as recovered and the whole recovery is carried; one taken in full
 * leaves nothing to carry.
 */
class ArrearLockEffectTest {

    private static final CycleLocked LOCKED =
            new CycleLocked(9L, 40L, "202610", "ddo@x", Instant.parse("2026-10-29T06:00:00Z"));

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ArrearLockEffect effect = new ArrearLockEffect(db.jdbc);

    ArrearLockEffectTest() {
        db.rows("SELECT DISTINCT r.employee_id", cols(7L))
                .rows(
                        "d.arrear_amount, c.legacy_pay_code FROM",
                        row(
                                "arrear_detail_id", 1L,
                                "arrear_event_id", 3L,
                                "component_id", 11L,
                                "arrear_amount", new BigDecimal("1200"),
                                "legacy_pay_code", 101),
                        row(
                                "arrear_detail_id", 2L,
                                "arrear_event_id", 3L,
                                "component_id", 12L,
                                "arrear_amount", new BigDecimal("-500"),
                                "legacy_pay_code", 111));
    }

    @Test
    void SRS_8_17_withoutTheRecoveryPayCodeTheWholeRecoveryIsCarried() {
        effect.on(LOCKED);
        assertThat(db.updates("INSERT INTO arrear_detail"))
                .singleElement()
                .satisfies(
                        u -> {
                            assertThat(u.args().get(2)).isEqualTo(12L);
                            assertThat((BigDecimal) u.args().get(4)).isEqualByComparingTo("-500");
                        });
        assertThat(db.updates("SET arrear_status = 'POSTED'")).hasSize(2);
    }

    @Test
    void SRS_8_17_aRecoveryTakenInFullLeavesNothingToCarryAndPartOfItTheRest() {
        db.rows("l.list_code = 'ARREAR_PAY_CODE'", cols(new BigDecimal("925")))
                .value("COALESCE(SUM(rc.amount), 0)", new BigDecimal("-500"));
        effect.on(LOCKED);
        assertThat(db.updates("INSERT INTO arrear_detail")).isEmpty();

        db.value("COALESCE(SUM(rc.amount), 0)", new BigDecimal("-200"));
        effect.on(LOCKED);
        assertThat(db.updates("INSERT INTO arrear_detail"))
                .singleElement()
                .satisfies(
                        u -> assertThat((BigDecimal) u.args().get(4)).isEqualByComparingTo("-300"));
    }

    /**
     * SRS-LV-19 / BR-LEAVE-011 (BMAD TR-044): a leave recovery (an arrear event of source LEAVE)
     * not taken in full at the floor is carried as an APPROVED detail of the rest for the next open
     * cycle - the status the Final Settlement sums (SettlementWork: arrear_detail CALCULATED /
     * APPROVED), so an outstanding leave recovery appears in F&F.
     */
    @Test
    void SRS_LV_19_aLeaveRecoveryNotTakenInFullIsCarriedApprovedForTheNextCycleAndTheSettlement() {
        db.rows("l.list_code = 'ARREAR_PAY_CODE'", cols(new BigDecimal("925")))
                .value("COALESCE(SUM(rc.amount), 0)", new BigDecimal("-200"));
        effect.on(LOCKED);
        assertThat(db.updates("INSERT INTO arrear_detail"))
                .singleElement()
                .satisfies(
                        u -> {
                            assertThat(u.sql()).contains("'APPROVED'");
                            assertThat(u.args().get(0)).as("the same event").isEqualTo(3L);
                            assertThat(u.args().get(1)).isEqualTo(7L);
                            assertThat(u.args().get(3)).isEqualTo("202610");
                            assertThat((BigDecimal) u.args().get(4))
                                    .as("due 500, 200 taken: 300 outstanding")
                                    .isEqualByComparingTo("-300");
                        });
    }
}
