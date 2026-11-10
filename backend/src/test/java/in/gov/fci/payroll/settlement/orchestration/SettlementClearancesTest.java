package in.gov.fci.payroll.settlement.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.allowance.api.AllowanceClearance;
import in.gov.fci.payroll.cpf.api.CpfFinalPayment;
import in.gov.fci.payroll.loans.api.LoanClearance;
import in.gov.fci.payroll.penalty.api.PenaltyClearance;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F&F clearances (plan 11 phase 11b; SRS-10-27, SRS-10-9, BR-CPF-019): outstanding penalty orders
 * are answered with their count and total; a member who applied for the CPF final payment has the
 * application's stage (and sanction number, when one is given) on the case and the other benefits
 * are not withheld.
 */
class SettlementClearancesTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PenaltyClearance penalties = mock(PenaltyClearance.class);
    private final CpfFinalPayment cpf = mock(CpfFinalPayment.class);
    private final SettlementClearances clearances =
            new SettlementClearances(
                    jdbc,
                    mock(LoanClearance.class),
                    penalties,
                    mock(AllowanceClearance.class),
                    cpf,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private void answered(String type, String remark) {
        verify(jdbc)
                .update(
                        contains("UPDATE settlement_clearance"),
                        any(Timestamp.class),
                        eq(remark),
                        eq(remark),
                        eq(5L),
                        eq(type));
    }

    @Test
    void SRS_10_27_outstandingPenaltiesAndAnAppliedCpfFinalPaymentAreAnswered() {
        when(penalties.outstanding(9L))
                .thenReturn(
                        List.of(
                                new PenaltyClearance.Outstanding(
                                        1L, "PN-1", "ORD-1", new BigDecimal("1200.50")),
                                new PenaltyClearance.Outstanding(
                                        2L, "PN-2", "ORD-2", new BigDecimal("799.50"))));
        when(cpf.of(9L))
                .thenReturn(
                        Optional.of(
                                new CpfFinalPayment.Status(
                                        44L, "CPF/FP/2026/12", new BigDecimal("650000"), "PAID")));
        clearances.refresh(5L, 9L);
        answered("PENALTY_RECOVERY", "2 outstanding, total 2000");
        answered("LOANS_ADVANCES", "Nil");
        answered("CPF_FINAL_STATUS", "CPF final payment PAID (CPF/FP/2026/12)");
        verify(jdbc)
                .update(
                        contains("cpf_final_status_info"),
                        eq(44L),
                        eq(new BigDecimal("650000")),
                        eq("PAID"),
                        any(Timestamp.class),
                        eq("N"),
                        eq(5L));
    }

    @Test
    void
            BR_CPF_019_SRS_10_17_BR_FNF_003_BR_FNF_010_anApplicationWithoutASanctionNumberShowsItsStageOnly() {
        when(cpf.of(9L))
                .thenReturn(
                        Optional.of(
                                new CpfFinalPayment.Status(44L, null, null, "UNIT_VERIFICATION")));
        clearances.refresh(5L, 9L);
        answered("CPF_FINAL_STATUS", "CPF final payment UNIT_VERIFICATION");
    }
}
