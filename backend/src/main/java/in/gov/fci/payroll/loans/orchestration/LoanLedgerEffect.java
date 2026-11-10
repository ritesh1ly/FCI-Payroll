package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The loan ledger (plan 09 phase 09d; SRS-12-3, SRS-12-5, SRS-12-23, SRS-12-26; D09-16): when a
 * staff cycle locks, each open loan of a member of the unit with an instalment due that month and a
 * current Actual result in the cycle is recovered: its ledger row (opening, recovered principal and
 * interest, closing) is written and its outstanding reduced; a loan fully recovered is CLOSED
 * (auto-close). A member separated before the month stops the loan (STOPPED, event STOP): the
 * balance is recovered in Full &amp; Final (module 11). On a transfer the outstanding moves to the
 * new unit through IOGA: a UNIT_TRANSFER event, and the next ledger row carries the narration
 * (previous unit, date, amount).
 *
 * <p>OPEN-QUESTION (CLR-62, US-GAP-208): a loan foreclosed after the month was computed and before
 * its lock is not recovered again here (it is no longer open); the instalment the computed result
 * already carries is left as it is until FCI says whether the month is recomputed, the instalment
 * refunded or the foreclosure amount reduced.
 */
@Component
public class LoanLedgerEffect {

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final LoanWriter writer;

    public LoanLedgerEffect(JdbcTemplate jdbc, OrgDirectory org, LoanWriter writer) {
        this.jdbc = jdbc;
        this.org = org;
        this.writer = writer;
    }

    @EventListener
    public void on(CycleLocked locked) {
        String yymm = locked.payrollYymm();
        YearMonth month =
                YearMonth.of(
                        Integer.parseInt(yymm.substring(0, 4)),
                        Integer.parseInt(yymm.substring(4)));
        Date period = Date.valueOf(month.atDay(1));
        List<Map<String, Object>> due =
                jdbc.queryForList(
                        "SELECT h.loan_id, h.loan_number, h.loan_status, h.employee_id,"
                                + " h.principal_outstanding, h.interest_outstanding,"
                                + " h.paid_instalments, h.balance_instalments, e.location_id,"
                                + " e.separation_date, l.principal_amount, l.interest_amount,"
                                + " (SELECT COUNT(*) FROM payroll_result r WHERE r.employee_id ="
                                + " h.employee_id AND r.cycle_instance_id = ? AND r.run_type ="
                                + " 'ACTUAL' AND r.result_status = 'CURRENT') AS computed FROM"
                                + " loan_header h JOIN employee e ON e.employee_id = h.employee_id"
                                + " JOIN loan_schedule_version v ON v.loan_id = h.loan_id AND"
                                + " v.superseded_at IS NULL JOIN loan_recovery_schedule l ON"
                                + " l.schedule_version_id = v.schedule_version_id AND"
                                + " l.payroll_yymm = ? WHERE h.loan_status IN ('ACTIVE',"
                                + " 'RESCHEDULED') AND NOT EXISTS (SELECT 1 FROM"
                                + " loan_monthly_balance b WHERE b.loan_id = h.loan_id AND"
                                + " b.payroll_yymm = ?)",
                        locked.cycleInstanceId(),
                        yymm,
                        yymm);
        for (Map<String, Object> d : due) {
            Object location = d.get("LOCATION_ID");
            if (location == null || !inUnit(((Number) location).longValue(), locked.orgUnitId())) {
                continue;
            }
            long loanId = ((Number) d.get("LOAN_ID")).longValue();
            BigDecimal principal = nz(d.get("PRINCIPAL_OUTSTANDING"));
            BigDecimal interest = nz(d.get("INTEREST_OUTSTANDING"));
            LocalDate separated = day(d.get("SEPARATION_DATE"));
            if (separated != null && separated.isBefore(month.atDay(1))) {
                writer.event(
                        loanId,
                        "STOP",
                        month.atDay(1),
                        principal.add(interest),
                        "Separated on " + separated + ": balance to Full & Final",
                        null,
                        null,
                        null,
                        null);
                writer.status(
                        loanId,
                        (String) d.get("LOAN_NUMBER"),
                        (String) d.get("LOAN_STATUS"),
                        "STOPPED",
                        principal,
                        interest,
                        "Recovery stopped on separation (SRS-12-5)",
                        locked.lockedBy(),
                        null);
                continue;
            }
            if (((Number) d.get("COMPUTED")).longValue() == 0) {
                continue;
            }
            BigDecimal paidPrincipal = nz(d.get("PRINCIPAL_AMOUNT")).min(principal);
            BigDecimal paidInterest = nz(d.get("INTEREST_AMOUNT")).min(interest);
            BigDecimal closingPrincipal = principal.subtract(paidPrincipal);
            BigDecimal closingInterest = interest.subtract(paidInterest);
            jdbc.update(
                    "INSERT INTO loan_monthly_balance (loan_id, employee_id, payroll_yymm,"
                        + " location_id, transfer_narration, opening_principal, disbursed_amount,"
                        + " principal_paid, interest_paid, closing_principal, closing_interest,"
                        + " period_date) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)",
                    loanId,
                    d.get("EMPLOYEE_ID"),
                    yymm,
                    location,
                    narration(loanId),
                    principal,
                    paidPrincipal,
                    paidInterest,
                    closingPrincipal,
                    closingInterest,
                    period);
            int paid = ((Number) d.get("PAID_INSTALMENTS")).intValue() + 1;
            Object balance = d.get("BALANCE_INSTALMENTS");
            jdbc.update(
                    "UPDATE loan_header SET paid_instalments = ?, balance_instalments = ? WHERE"
                            + " loan_id = ?",
                    paid,
                    balance == null ? null : Math.max(0, ((Number) balance).intValue() - 1),
                    loanId);
            boolean cleared = closingPrincipal.signum() == 0 && closingInterest.signum() == 0;
            writer.status(
                    loanId,
                    (String) d.get("LOAN_NUMBER"),
                    (String) d.get("LOAN_STATUS"),
                    cleared ? "CLOSED" : (String) d.get("LOAN_STATUS"),
                    closingPrincipal,
                    closingInterest,
                    cleared
                            ? "Fully recovered in " + yymm + " (SRS-12-23)"
                            : "Recovered "
                                    + paidPrincipal.add(paidInterest).toPlainString()
                                    + " in "
                                    + yymm,
                    locked.lockedBy(),
                    null);
        }
    }

    /** SRS-12-26: the outstanding moves to the new unit through IOGA; recorded as an event. */
    @EventListener
    public void on(EmployeeEvents.EmployeeTransferred transferred) {
        if (transferred.fromLocationId() == null || transferred.toLocationId() == null) {
            return;
        }
        for (Map<String, Object> loan :
                jdbc.queryForList(
                        "SELECT loan_id, principal_outstanding, interest_outstanding FROM"
                                + " loan_header WHERE employee_id = ? AND loan_status IN "
                                + LoanSanctionService.OPEN_STATUSES,
                        transferred.employeeId())) {
            long loanId = ((Number) loan.get("LOAN_ID")).longValue();
            BigDecimal amount =
                    nz(loan.get("PRINCIPAL_OUTSTANDING")).add(nz(loan.get("INTEREST_OUTSTANDING")));
            jdbc.update(
                    "INSERT INTO loan_event (loan_id, event_type, event_date, settlement_amount,"
                        + " from_location_id, to_location_id, reason) VALUES (?, 'UNIT_TRANSFER',"
                        + " ?, ?, ?, ?, ?)",
                    loanId,
                    Date.valueOf(
                            transferred.transferDate() == null
                                    ? LocalDate.now()
                                    : transferred.transferDate()),
                    amount,
                    transferred.fromLocationId(),
                    transferred.toLocationId(),
                    "IOGA: outstanding moved with the transfer"
                            + (transferred.orderReference() == null
                                    ? ""
                                    : " (" + transferred.orderReference() + ")"));
        }
    }

    /** The narration of the latest transfer not yet shown on a ledger row (SRS-12-26). */
    private String narration(long loanId) {
        return jdbc
                .queryForList(
                        "SELECT o.location_name AS from_unit, x.event_date, x.settlement_amount"
                            + " FROM loan_event x JOIN org_location o ON o.location_id ="
                            + " x.from_location_id WHERE x.loan_id = ? AND x.event_type ="
                            + " 'UNIT_TRANSFER' AND NOT EXISTS (SELECT 1 FROM loan_monthly_balance"
                            + " b WHERE b.loan_id = x.loan_id AND b.transfer_narration IS NOT NULL"
                            + " AND b.period_date >= x.event_date) ORDER BY x.event_date DESC",
                        loanId)
                .stream()
                .findFirst()
                .map(
                        t ->
                                "IOGA from "
                                        + t.get("FROM_UNIT")
                                        + " on "
                                        + day(t.get("EVENT_DATE"))
                                        + ": "
                                        + nz(t.get("SETTLEMENT_AMOUNT")).toPlainString())
                .orElse(null);
    }

    private boolean inUnit(long location, long unit) {
        return org.cycleUnitOf(location)
                .map(OrgLocation::getId)
                .filter(id -> id == unit)
                .isPresent();
    }

    /** A DATE column reads as java.sql.Date or Timestamp by driver: both are dates here. */
    private static LocalDate day(Object v) {
        return v instanceof java.util.Date d ? new Date(d.getTime()).toLocalDate() : null;
    }

    private static BigDecimal nz(Object v) {
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
    }
}
