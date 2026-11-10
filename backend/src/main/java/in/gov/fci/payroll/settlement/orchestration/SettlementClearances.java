package in.gov.fci.payroll.settlement.orchestration;

import in.gov.fci.payroll.allowance.api.AllowanceClearance;
import in.gov.fci.payroll.cpf.api.CpfFinalPayment;
import in.gov.fci.payroll.loans.api.LoanClearance;
import in.gov.fci.payroll.penalty.api.PenaltyClearance;
import in.gov.fci.payroll.settlement.business.SettlementRules;
import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The clearances of a settlement case (plan 11 phase 11b; SRS-10-27, SRS-10-9, SRS-10-3, SRS-10-17;
 * D11-2): the loans and advances, penalty and recovery, allowances (PLI advance) clearances and the
 * CPF final payment status are answered by their modules' ports and linked to the case (a balance
 * or nil); the CPF information goes on the case and, when the member has not applied, the other
 * retirement benefits are withheld (BR-CPF-019). The establishment no-dues waits for Establishment
 * / HR. The answers are read again at computation, so a settlement never runs on a stale one; once
 * every clearance is settled the case moves to Clearances received.
 */
@Component
public class SettlementClearances {

    private final JdbcTemplate jdbc;
    private final LoanClearance loans;
    private final PenaltyClearance penalties;
    private final AllowanceClearance allowances;
    private final CpfFinalPayment cpf;
    private final Clock clock;

    public SettlementClearances(
            JdbcTemplate jdbc,
            LoanClearance loans,
            PenaltyClearance penalties,
            AllowanceClearance allowances,
            CpfFinalPayment cpf,
            Clock clock) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.penalties = penalties;
        this.allowances = allowances;
        this.cpf = cpf;
        this.clock = clock;
    }

    /** Answers the system clearances of the case and moves it on when all are settled. */
    public void refresh(long caseId, long employeeId) {
        Timestamp now = Timestamp.from(clock.instant());
        List<LoanClearance.Outstanding> owed = loans.outstanding(employeeId);
        answer(
                caseId,
                "LOANS_ADVANCES",
                summary(owed.size(), owed.stream().map(LoanClearance.Outstanding::amount).toList()),
                now);
        List<PenaltyClearance.Outstanding> orders = penalties.outstanding(employeeId);
        answer(
                caseId,
                "PENALTY_RECOVERY",
                summary(
                        orders.size(),
                        orders.stream().map(PenaltyClearance.Outstanding::amount).toList()),
                now);
        List<AllowanceClearance.Outstanding> pli = allowances.outstanding(employeeId);
        answer(
                caseId,
                "ALLOWANCES",
                summary(
                        pli.size(),
                        pli.stream().map(AllowanceClearance.Outstanding::amount).toList()),
                now);
        Optional<CpfFinalPayment.Status> status = cpf.of(employeeId);
        jdbc.update(
                "UPDATE settlement_case SET cpf_sanction_id = ?, cpf_final_amount_info = ?,"
                        + " cpf_final_status_info = ?, cpf_status_received_at = ?,"
                        + " benefits_held_flag = ? WHERE settlement_case_id = ?",
                status.map(CpfFinalPayment.Status::sanctionId).orElse(null),
                status.map(CpfFinalPayment.Status::amount).orElse(null),
                status.map(CpfFinalPayment.Status::stage).orElse("NOT_APPLIED"),
                now,
                status.isPresent() ? "N" : "Y",
                caseId);
        answer(
                caseId,
                "CPF_FINAL_STATUS",
                status.map(
                                s ->
                                        "CPF final payment "
                                                + s.stage()
                                                + (s.sanctionNumber() == null
                                                        ? ""
                                                        : " (" + s.sanctionNumber() + ")"))
                        .orElse("Not applied for: other retirement benefits withheld (BR-CPF-019)"),
                now);
        advance(caseId);
    }

    /** Moves a case at Clearances raised to Clearances received once every clearance is settled. */
    public void advance(long caseId) {
        List<String> statuses =
                jdbc.queryForList(
                        "SELECT clearance_status FROM settlement_clearance WHERE settlement_case_id"
                                + " = ?",
                        String.class,
                        caseId);
        if (SettlementRules.clearancesSettled(statuses)) {
            jdbc.update(
                    "UPDATE settlement_case SET case_stage = ? WHERE settlement_case_id = ? AND"
                            + " case_stage = ?",
                    Stage.CLEARANCES_RECEIVED.number(),
                    caseId,
                    Stage.CLEARANCES_RAISED.number());
        }
    }

    /**
     * Records the clearance's answer. One the Checker excluded stays EXCLUDED with the exclusion's
     * reason as its remark; the answer's remark is kept apart ({@code answer_remark}, Wave C
     * review) so that a returned or rejected settlement restores it as RECEIVED with that remark.
     */
    private void answer(long caseId, String type, String remark, Timestamp now) {
        jdbc.update(
                "UPDATE settlement_clearance SET clearance_status = CASE WHEN clearance_status ="
                    + " 'EXCLUDED' THEN clearance_status ELSE 'RECEIVED' END, received_at = ?,"
                    + " remark = CASE WHEN clearance_status = 'EXCLUDED' THEN remark ELSE ? END,"
                    + " answer_remark = CASE WHEN clearance_status = 'EXCLUDED' THEN ? ELSE NULL"
                    + " END WHERE settlement_case_id = ? AND clearance_type = ?",
                now,
                remark,
                remark,
                caseId,
                type);
    }

    private static String summary(int count, List<BigDecimal> amounts) {
        if (count == 0) {
            return "Nil";
        }
        BigDecimal total = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return count + " outstanding, total " + total.stripTrailingZeros().toPlainString();
    }
}
