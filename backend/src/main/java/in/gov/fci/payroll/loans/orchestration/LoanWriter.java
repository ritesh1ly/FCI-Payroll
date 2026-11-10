package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.loans.business.LoanRules;
import in.gov.fci.payroll.loans.business.LoanRules.Terms;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * Every write to a loan, in the caller's transaction (plan 09 phases 09b / 09c; SRS-12-1,
 * SRS-12-20): the loan with its ORIGINAL schedule version and lines; a new version (RESCHEDULE,
 * FORECLOSURE, CORRECTION) that supersedes the one in force, the old one kept so the ledger
 * reconciles to the schedule of any past period; a loan event. Each is audited. Never a delete
 * (R-DEL).
 */
@Component
public class LoanWriter {

    static final String ENTITY = "loan_header";

    /** Where the loan came from (DDL {@code loan_source}). */
    public enum Source {
        HRMS,
        /** Set up on I-04 by the Payroll Maker (DDL value LABOUR_ENT: entered in Payroll). */
        LABOUR_ENT
    }

    /** A loan written: its id and number. */
    public record Written(long loanId, String loanNumber) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public LoanWriter(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** SRS-12-1: the loan, ACTIVE, the principal and interest outstanding in full. */
    public Written create(
            long employeeId,
            long loanTypeId,
            String loanTypeCode,
            Terms t,
            List<LoanRules.Line> lines,
            LocalDate sanctionDate,
            Source source,
            String hrmsRequestId,
            Long hrmsTaskId,
            Long approvalId,
            String maker,
            String checker,
            Instant at) {
        BigDecimal interest = t.interestAmount() == null ? BigDecimal.ZERO : t.interestAmount();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO loan_header (employee_id, loan_type_id,"
                                        + " sanctioned_amount, sanction_date, recovery_start_yymm,"
                                        + " num_principal_instalments, first_instalment_amount,"
                                        + " instalment_amount, balance_instalments,"
                                        + " paid_instalments, principal_outstanding,"
                                        + " num_interest_instalments, interest_amount,"
                                        + " interest_instalment_amount, interest_outstanding,"
                                        + " loan_status, loan_source, hrms_request_id,"
                                        + " hrms_change_task_id, approval_id) VALUES (?, ?, ?, ?,"
                                        + " ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)",
                                    new String[] {"loan_id"});
                    ps.setLong(1, employeeId);
                    ps.setLong(2, loanTypeId);
                    ps.setBigDecimal(3, t.sanctioned());
                    ps.setDate(4, Date.valueOf(sanctionDate));
                    ps.setString(5, lines.get(0).payrollYymm());
                    ps.setInt(6, t.principalInstalments());
                    ps.setBigDecimal(7, t.firstInstalment());
                    ps.setBigDecimal(8, t.instalment());
                    ps.setInt(9, lines.size());
                    ps.setBigDecimal(10, t.sanctioned());
                    ps.setObject(
                            11,
                            t.interestInstalments() > 0 ? t.interestInstalments() : null,
                            Types.INTEGER);
                    ps.setBigDecimal(12, interest);
                    ps.setBigDecimal(13, t.interestInstalment());
                    ps.setBigDecimal(14, interest);
                    ps.setString(15, source.name());
                    ps.setString(16, hrmsRequestId);
                    ps.setObject(17, hrmsTaskId, Types.BIGINT);
                    ps.setObject(18, approvalId, Types.BIGINT);
                    return ps;
                },
                key);
        long loanId = GeneratedKeys.id(key);
        String number = "LN-" + sanctionDate.getYear() + "-" + String.format("%06d", loanId);
        jdbc.update("UPDATE loan_header SET loan_number = ? WHERE loan_id = ?", number, loanId);
        version(loanId, "ORIGINAL", lines, approvalId, maker, at);
        AuditService.Entry created =
                AuditService.Entry.change(
                                ENTITY,
                                number,
                                "loan_status",
                                null,
                                "ACTIVE",
                                loanTypeCode
                                        + " sanctioned ("
                                        + source.name()
                                        + "): "
                                        + t.sanctioned().toPlainString()
                                        + " in "
                                        + lines.size()
                                        + " instalments from "
                                        + lines.get(0).payrollYymm())
                        .module(LoanSanctionService.SCREEN);
        audit.record(checker, approvalId == null ? created : created.approval(approvalId));
        return new Written(loanId, number);
    }

    /**
     * SRS-12-20: a new schedule version superseding the one in force (kept), with its lines (none
     * for a foreclosure).
     */
    public long version(
            long loanId,
            String reason,
            List<LoanRules.Line> lines,
            Long approvalId,
            String actor,
            Instant at) {
        Integer last =
                jdbc.queryForObject(
                        "SELECT MAX(version_no) FROM loan_schedule_version WHERE loan_id = ?",
                        Integer.class,
                        loanId);
        jdbc.update(
                "UPDATE loan_schedule_version SET superseded_at = ? WHERE loan_id = ? AND"
                        + " superseded_at IS NULL",
                Timestamp.from(at),
                loanId);
        int versionNo = last == null ? 1 : last + 1;
        LocalDate from =
                lines.isEmpty()
                        ? LocalDate.ofInstant(at, java.time.ZoneId.of("Asia/Kolkata"))
                        : LoanRules.month(lines.get(0).payrollYymm()).orElseThrow().atDay(1);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO loan_schedule_version (loan_id, version_no,"
                                            + " reason, effective_from, approval_id, created_by,"
                                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                    new String[] {"schedule_version_id"});
                    ps.setLong(1, loanId);
                    ps.setInt(2, versionNo);
                    ps.setString(3, reason);
                    ps.setDate(4, Date.valueOf(from));
                    ps.setObject(5, approvalId, Types.BIGINT);
                    ps.setString(6, actor);
                    ps.setTimestamp(7, Timestamp.from(at));
                    return ps;
                },
                key);
        long versionId = GeneratedKeys.id(key);
        if (!lines.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO loan_recovery_schedule (schedule_version_id, loan_id,"
                            + " instalment_number, payroll_yymm, principal_amount, interest_amount)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    lines.stream()
                            .map(
                                    l ->
                                            new Object[] {
                                                versionId,
                                                loanId,
                                                l.number(),
                                                l.payrollYymm(),
                                                l.principal(),
                                                l.interest()
                                            })
                            .toList());
        }
        return versionId;
    }

    /** A loan event (DDL {@code loan_event}): foreclosure, reschedule, stop, resume, transfer. */
    public void event(
            long loanId,
            String type,
            LocalDate date,
            BigDecimal settlement,
            String reason,
            String hrmsRequestId,
            Long attachmentId,
            Long otpChallengeId,
            Long approvalId) {
        jdbc.update(
                "INSERT INTO loan_event (loan_id, event_type, event_date, settlement_amount,"
                        + " reason, hrms_request_id, attachment_id, otp_challenge_id, approval_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                loanId,
                type,
                Date.valueOf(date),
                settlement,
                reason,
                hrmsRequestId,
                attachmentId,
                otpChallengeId,
                approvalId);
    }

    /** The loan's status and outstanding after an event, audited. */
    public void status(
            long loanId,
            String number,
            String before,
            String after,
            BigDecimal principal,
            BigDecimal interest,
            String why,
            String actor,
            Long approvalId) {
        jdbc.update(
                "UPDATE loan_header SET loan_status = ?, principal_outstanding = ?,"
                        + " interest_outstanding = ?, row_version = row_version + 1 WHERE loan_id"
                        + " = ?",
                after,
                principal,
                interest,
                loanId);
        AuditService.Entry entry =
                AuditService.Entry.change(ENTITY, number, "loan_status", before, after, why)
                        .module(LoanSanctionService.SCREEN);
        audit.record(actor, approvalId == null ? entry : entry.approval(approvalId));
    }
}
