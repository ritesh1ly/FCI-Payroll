package in.gov.fci.payroll.penalty.api;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The penalty clearance of a Full &amp; Final Settlement (SRS-10-27, SRS-14-1): only an order with
 * a balance still to recover is outstanding; one fully recovered (nothing owed) or with no amount
 * owed is not.
 */
class PenaltyClearanceTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final PenaltyClearance clearance = new PenaltyClearance(db.jdbc);

    @Test
    void SRS_10_27_onlyAnOrderWithABalanceIsOutstanding() {
        db.rows(
                "FROM penalty_order o WHERE o.employee_id",
                row(
                        "order_id",
                        1L,
                        "penalty_ref",
                        "PEN-1",
                        "order_reference",
                        "O/1",
                        "owed",
                        new BigDecimal("2500.00")),
                row(
                        "order_id",
                        2L,
                        "penalty_ref",
                        "PEN-2",
                        "order_reference",
                        "O/2",
                        "owed",
                        BigDecimal.ZERO),
                row(
                        "order_id",
                        3L,
                        "penalty_ref",
                        "PEN-3",
                        "order_reference",
                        "O/3",
                        "owed",
                        null));
        assertThat(clearance.outstanding(7L))
                .extracting(PenaltyClearance.Outstanding::penaltyRef)
                .containsExactly("PEN-1");
    }
}
