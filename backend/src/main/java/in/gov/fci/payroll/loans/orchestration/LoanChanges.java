package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.loans.business.LoanRules;
import in.gov.fci.payroll.loans.business.LoanRules.Terms;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A foreclosure or reschedule taking effect, whoever set it up (plan 09 phases 09c / 09e; SRS-12-4,
 * SRS-12-16, SRS-12-20, SRS-12-21, SRS-12-22; D09-13): the foreclosure clears the outstanding
 * principal and interest in one event; the reschedule recomputes the schedule on the balance
 * remaining at approval; each writes a new schedule version (the old one kept) and an event.
 */
@Component
public class LoanChanges {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final LoanWriter writer;
    private final OrgDirectory org;
    private final PayrollRecords payroll;

    public LoanChanges(
            JdbcTemplate jdbc, LoanWriter writer, OrgDirectory org, PayrollRecords payroll) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.org = org;
        this.payroll = payroll;
    }

    /** SRS-12-16: the settlement amount, cleared in one event. */
    public BigDecimal foreclose(
            Map<String, Object> loan,
            String why,
            String hrmsRequestId,
            Long attachmentId,
            Long otpChallengeId,
            long approvalId,
            String maker,
            String checker,
            Instant at) {
        long id = ((Number) loan.get("LOAN_ID")).longValue();
        BigDecimal amount =
                LoanRules.foreclosureAmount(
                        (BigDecimal) loan.get("PRINCIPAL_OUTSTANDING"),
                        (BigDecimal) loan.get("INTEREST_OUTSTANDING"));
        writer.event(
                id,
                "FORECLOSURE",
                LocalDate.ofInstant(at, IST),
                amount,
                why,
                hrmsRequestId,
                attachmentId,
                otpChallengeId,
                approvalId);
        writer.version(id, "FORECLOSURE", List.of(), approvalId, maker, at);
        writer.status(
                id,
                (String) loan.get("LOAN_NUMBER"),
                (String) loan.get("LOAN_STATUS"),
                "FORECLOSED",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "Foreclosed (" + why + "): " + amount.toPlainString(),
                checker,
                approvalId);
        return amount;
    }

    /** SRS-12-4: the remaining balance as the terms of the new schedule. */
    public static Terms remaining(
            Map<String, Object> loan,
            int principalInstalments,
            BigDecimal instalment,
            int interestInstalments,
            BigDecimal interestInstalment,
            String startYymm) {
        BigDecimal interest = (BigDecimal) loan.get("INTEREST_OUTSTANDING");
        return new Terms(
                (BigDecimal) loan.get("PRINCIPAL_OUTSTANDING"),
                principalInstalments,
                instalment,
                null,
                interest == null || interest.signum() == 0 ? null : interest,
                interestInstalments,
                interestInstalment,
                startYymm);
    }

    /** SRS-12-4 / SRS-12-20: the new schedule on the remaining balance, the old one kept. */
    public void reschedule(
            Map<String, Object> loan,
            Terms t,
            long employeeId,
            String why,
            String hrmsRequestId,
            Long attachmentId,
            long approvalId,
            String maker,
            String checker,
            Instant at) {
        long id = ((Number) loan.get("LOAN_ID")).longValue();
        LocalDate today = LocalDate.ofInstant(at, IST);
        writer.event(
                id, "RESCHEDULE", today, null, why, hrmsRequestId, attachmentId, null, approvalId);
        writer.version(
                id,
                "RESCHEDULE",
                open(employeeId, LoanRules.schedule(t), today),
                approvalId,
                maker,
                at);
        jdbc.update(
                "UPDATE loan_header SET instalment_amount = ?, balance_instalments = ?,"
                        + " interest_instalment_amount = ? WHERE loan_id = ?",
                t.instalment(),
                t.principalInstalments() + t.interestInstalments(),
                t.interestInstalment(),
                id);
        writer.status(
                id,
                (String) loan.get("LOAN_NUMBER"),
                (String) loan.get("LOAN_STATUS"),
                "RESCHEDULED",
                (BigDecimal) loan.get("PRINCIPAL_OUTSTANDING"),
                (BigDecimal) loan.get("INTEREST_OUTSTANDING"),
                "Rescheduled ("
                        + why
                        + "): "
                        + t.principalInstalments()
                        + " x "
                        + t.instalment().toPlainString(),
                checker,
                approvalId);
    }

    /** D09-13: instalments in a month already locked follow from the member's next open month. */
    List<LoanRules.Line> open(long employeeId, List<LoanRules.Line> lines, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        Long location =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        YearMonth open = current;
        if (location != null) {
            String yymm = String.format("%04d%02d", current.getYear(), current.getMonthValue());
            boolean locked =
                    org.cycleUnitOf(location)
                            .flatMap(u -> payroll.cycle(u.getId(), yymm))
                            .filter(PayrollRecords.Cycle::isLocked)
                            .isPresent();
            open = locked ? current.plusMonths(1) : current;
        }
        YearMonth first = LoanRules.month(lines.get(0).payrollYymm()).orElseThrow();
        if (!first.isBefore(open)) {
            return lines;
        }
        return LoanRules.resume(
                lines, String.format("%04d%02d", open.getYear(), open.getMonthValue()));
    }

    /** The member's open loan with this id; the columns the changes read. */
    public Map<String, Object> openLoan(long loanId) {
        return jdbc
                .queryForList(
                        "SELECT loan_id, loan_number, loan_status, loan_source, employee_id,"
                            + " principal_outstanding, interest_outstanding FROM loan_header WHERE"
                            + " loan_id = ? AND loan_status IN "
                                + LoanSanctionService.OPEN_STATUSES,
                        loanId)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
