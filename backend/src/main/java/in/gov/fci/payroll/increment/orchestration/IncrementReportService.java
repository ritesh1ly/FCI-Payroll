package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.increment.business.IncrementRules;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Basic history and the increment reports (plan 17 phase 17f; SRS-13-26, SRS-13-21, SRS-13-15):
 * a member's Basic Pay history from joining, every event with its date, amount, old amount, event
 * name, order and approver (the I-05 panel and the Pay Fixation / Basic History Report); the
 * Increment Tracking Report of a month - each member of the session's offices whose increment fell
 * due, with its due date and whether it was processed (and how) or is pending.
 */
@Service
public class IncrementReportService {

    static final String SCREEN = "I-05";

    /** One Basic event; {@code corrected}: the old entry of an approved correction (SRS-13-24). */
    public record HistoryRow(
            LocalDate effectiveFrom,
            int sequence,
            LocalDate effectiveTo,
            String event,
            BigDecimal basic,
            BigDecimal oldBasic,
            String source,
            String reason,
            String order,
            String by,
            String maker,
            boolean corrected) {

        /** The same row, marked as the old entry of an approved correction (SRS-13-24). */
        HistoryRow asCorrected() {
            return new HistoryRow(
                    effectiveFrom,
                    sequence,
                    effectiveTo,
                    event,
                    basic,
                    oldBasic,
                    source,
                    reason,
                    order,
                    by,
                    maker,
                    true);
        }
    }

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;

    public IncrementReportService(NamedParameterJdbcTemplate named, Authorization policy) {
        this.named = named;
        this.policy = policy;
    }

    private static final String HISTORY =
            " FROM employee_basic_history h JOIN employee e ON e.employee_id = h.employee_id LEFT"
                    + " JOIN approval_queue q ON q.approval_id = h.approval_id WHERE"
                    + " e.emp_num = :eis AND (:all = 'Y' OR e.location_id IN (:locations))";

    /** SRS-13-26: the member's Basic Pay history, newest first. */
    @Transactional(readOnly = true)
    public List<HistoryRow> history(AccessContext context, String eis) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = scoped(context).addValue("eis", eis);
        Long n = named.queryForObject("SELECT COUNT(*)" + HISTORY, p, Long.class);
        if (RowCounts.of(n) == 0) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        List<Read> rows =
                named.query(
                        COLUMNS
                                + HISTORY
                                + " ORDER BY h.effective_from DESC, h.event_sequence DESC",
                        p,
                        (rs, i) -> new Read(rs.getLong(12), rs.getLong(13), row(rs)));
        // SRS-13-24: the old entry of an approved correction stays, marked as corrected.
        java.util.Set<Long> corrected =
                IncrementRules.corrected(
                        rows.stream()
                                .map(
                                        r ->
                                                new IncrementRules.Entry(
                                                        r.id(),
                                                        r.row().effectiveFrom(),
                                                        r.row().sequence()))
                                .toList(),
                        named.query(
                                "SELECT ic.correction_type, ic.basic_history_id,"
                                        + " c.basic_history_id FROM increment_correction ic LEFT"
                                        + " JOIN pay_increment_cycle c ON c.increment_id ="
                                        + " ic.increment_id WHERE ic.employee_id = :employee AND"
                                        + " ic.correction_status = 'APPROVED'",
                                new MapSqlParameterSource("employee", rows.getFirst().employee()),
                                (rs, i) ->
                                        new IncrementRules.Correction(
                                                rs.getString(1),
                                                rs.getObject(2) == null ? null : rs.getLong(2),
                                                rs.getObject(3) == null ? null : rs.getLong(3))));
        return rows.stream()
                .map(r -> corrected.contains(r.id()) ? r.row().asCorrected() : r.row())
                .toList();
    }

    /** A history row with its id and member, before the corrections are marked. */
    private record Read(long id, long employee, HistoryRow row) {}

    @Transactional(readOnly = true)
    public long historySize(AccessContext context, String eis) {
        policy.require(context, SCREEN, "EXPORT");
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*)" + HISTORY,
                        scoped(context).addValue("eis", eis),
                        Long.class);
        return RowCounts.of(n);
    }

    /** SRS-13-26: the Pay Fixation / Basic History Report, oldest first (from joining). */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void historyReport(AccessContext context, String eis, ExportSink sink)
            throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        MapSqlParameterSource p = scoped(context).addValue("eis", eis);
        Long expected = named.queryForObject("SELECT COUNT(*)" + HISTORY, p, Long.class);
        sink.header(
                "effective_from",
                "sequence",
                "effective_to",
                "event",
                "basic",
                "old_basic",
                "source",
                "reason",
                "order",
                "changed_by",
                "maker");
        named.query(
                COLUMNS + HISTORY + " ORDER BY h.effective_from, h.event_sequence",
                p,
                rs -> {
                    HistoryRow r = row(rs);
                    try {
                        sink.row(
                                r.effectiveFrom().toString(),
                                r.sequence(),
                                r.effectiveTo() == null ? "" : r.effectiveTo().toString(),
                                r.event(),
                                r.basic(),
                                r.oldBasic(),
                                r.source(),
                                r.reason(),
                                r.order() == null ? "" : r.order(),
                                r.by(),
                                r.maker() == null ? "" : r.maker());
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private static final String TRACKING =
            " FROM employee e JOIN org_location l ON l.location_id = e.location_id LEFT JOIN"
                    + " employee_increment_due d ON d.employee_id = e.employee_id LEFT JOIN"
                    + " pay_increment_cycle c ON c.employee_id = e.employee_id AND"
                    + " c.increment_due_date BETWEEN :from AND :to LEFT JOIN approval_queue q ON"
                    + " q.approval_id = c.approval_id WHERE e.emp_lbr_flag = 'N' AND"
                    + " e.record_status = 'ACTIVE' AND (c.increment_id IS NOT NULL OR"
                    // UI audit #29 (SRS-13-21): the members the I-05 due list shows for the month -
                    // every one due up to its end, overdue included - not only those due within it.
                    + " (d.next_due_date <= :to AND e.employment_status = 'IN_SERVICE')) AND (:all"
                    + " = 'Y' OR e.location_id IN (:locations))";

    @Transactional(readOnly = true)
    public long trackingSize(AccessContext context, YearMonth month) {
        policy.require(context, SCREEN, "EXPORT");
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*)" + TRACKING, month(context, month), Long.class);
        return RowCounts.of(n);
    }

    /**
     * SRS-13-21 / 13-15: the Increment Tracking Report of the month (due, processed, pending); each
     * processed increment, a stagnation increment included, with its approving users, the Maker who
     * submitted it and the Checker who approved it (BR-INCR-STAG-008).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void tracking(AccessContext context, YearMonth month, ExportSink sink)
            throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        MapSqlParameterSource p = month(context, month);
        Long expected = named.queryForObject("SELECT COUNT(*)" + TRACKING, p, Long.class);
        sink.header(
                "eis",
                "member",
                "office",
                "due_date",
                "status",
                "type",
                "old_basic",
                "revised_basic",
                "maker",
                "approved_by");
        named.query(
                "SELECT e.emp_num, e.first_name, e.last_name, l.location_code, l.location_name,"
                        + " COALESCE(c.increment_due_date, d.next_due_date), c.increment_type,"
                        + " c.old_basic, c.revised_basic, e.employee_id, q.submitted_by,"
                        + " q.decided_by"
                        + TRACKING
                        + " ORDER BY e.emp_num, e.employee_id",
                p,
                rs -> {
                    String first = rs.getString(2);
                    String last = rs.getString(3);
                    Date due = rs.getDate(6);
                    String type = rs.getString(7);
                    try {
                        sink.row(
                                rs.getString(1),
                                ((first == null ? "" : first) + " " + (last == null ? "" : last))
                                        .strip(),
                                rs.getString(4) + " - " + rs.getString(5),
                                due == null ? "" : due.toLocalDate().toString(),
                                type == null ? "PENDING" : "PROCESSED",
                                type == null ? "" : type,
                                rs.getBigDecimal(8),
                                rs.getBigDecimal(9),
                                nz(rs.getString(11)),
                                nz(rs.getString(12)));
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private static final String COLUMNS =
            "SELECT h.effective_from, h.event_sequence, h.effective_to, h.event_type,"
                    + " h.basic_amount, h.old_basic_amount, h.source_code, h.reason,"
                    + " h.order_reference, h.changed_by, q.submitted_by, h.history_id,"
                    + " h.employee_id";

    private static HistoryRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date to = rs.getDate(3);
        return new HistoryRow(
                rs.getDate(1).toLocalDate(),
                rs.getInt(2),
                to == null ? null : to.toLocalDate(),
                rs.getString(4),
                rs.getBigDecimal(5),
                rs.getBigDecimal(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10),
                // US-GAP-55 (SRS-13-26: "Maker and Checker"): the approval item's submitter.
                rs.getString(11),
                false);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private MapSqlParameterSource month(AccessContext context, YearMonth month) {
        return scoped(context)
                .addValue("from", Date.valueOf(month.atDay(1)))
                .addValue("to", Date.valueOf(month.atEndOfMonth()));
    }

    private MapSqlParameterSource scoped(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }
}
