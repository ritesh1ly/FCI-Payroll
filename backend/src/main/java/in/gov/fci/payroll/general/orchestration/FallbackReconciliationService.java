package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.general.business.FallbackReconciliation;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-07 read side drawn by Wireframes v6.1 (OD-121; SRS-G-47, BR-INT-011): the "HRMS Fallback Upload
 * Reconciliation (Annexure A.11)" table and its export over the session's offices, and the tile
 * "HRMS interface". The API has no source contract (PENDING API-01-1): this class is its one place.
 *
 * <p>The table reads what the reconciliation stored on the lines. Each difference is a row, each
 * suspension, absconding or revocation line is a row, and the matched attendance lines of an upload
 * fold into one row, as drawn ("4,086 others, All items, Match, Match, None, Closed").
 *
 * <p>HRMS is down while an HRMS interface's breaker is open (interface_breaker_state) or while a
 * live enablement records the outage Headquarters declared (hrms_fallback_enablement.
 * hrms_down_since); the general module may not call the integration module (it depends on us).
 */
@Service
@Loggable
public class FallbackReconciliationService {

    static final String SCREEN = "X-07";

    /** The tile: whether HRMS is down, and since when. */
    public record InterfaceStatus(boolean down, Instant since) {}

    /** One row of the drawn table; {@code others} above 0 is the folded row of matched lines. */
    public record Row(
            String reference,
            String payrollYymm,
            String employeeNumber,
            String employeeName,
            long others,
            String item,
            String uploaded,
            String hrms,
            String effect,
            String difference,
            String nextCycleYymm,
            String status) {}

    private static final String LINE_FROM =
            " FROM hrms_fallback_upload_line l JOIN hrms_fallback_upload u ON u.fallback_upload_id"
                    + " = l.fallback_upload_id JOIN hrms_fallback_enablement e ON e.enablement_id ="
                    + " u.enablement_id";

    private static final String ROWS =
            "SELECT e.enablement_ref reference_no, u.payroll_yymm yymm, u.fallback_upload_id"
                + " upload_id, 0 group_rank, l.line_no line_no, l.employee_number_raw emp_no,"
                + " m.first_name first_name, m.last_name last_name, 0 others, l.record_type"
                + " record_type, l.hrms_value_summary hrms_summary, l.pay_days pay_days, l.lwp_days"
                + " lwp_days, l.half_pay_days half_pay_days, l.other_leave_days other_leave_days,"
                + " l.event_effective_date event_date, l.recon_effect effect, l.recon_cycle_yymm"
                + " next_cycle, l.recon_status status"
                    + LINE_FROM
                    + " LEFT JOIN employee m ON m.employee_id = l.employee_id WHERE u.org_unit_id"
                    + " IN (:scope) AND (l.recon_status IN ('DIFFERENCE', 'NOT_IN_HRMS') OR"
                    + " (l.recon_status = 'MATCHED' AND l.record_type <> 'ATTENDANCE_LEAVE')) UNION"
                    + " ALL SELECT e.enablement_ref, u.payroll_yymm, u.fallback_upload_id, 1, 0,"
                    + " CAST(NULL AS VARCHAR2(30)), CAST(NULL AS VARCHAR2(100)), CAST(NULL AS"
                    + " VARCHAR2(100)), COUNT(*), 'ATTENDANCE_LEAVE', CAST(NULL AS VARCHAR2(1000)),"
                    + " CAST(NULL AS NUMBER), CAST(NULL AS NUMBER), CAST(NULL AS NUMBER), CAST(NULL"
                    + " AS NUMBER), CAST(NULL AS DATE), 'NONE', CAST(NULL AS VARCHAR2(6)), 'CLOSED'"
                    + LINE_FROM
                    + " WHERE u.org_unit_id IN (:scope) AND l.recon_status = 'MATCHED' AND"
                    + " l.record_type = 'ATTENDANCE_LEAVE' GROUP BY e.enablement_ref,"
                    + " u.payroll_yymm, u.fallback_upload_id";

    private static final String BREAKER_SINCE =
            "SELECT MIN(b.opened_at) FROM interface_breaker_state b JOIN interface_registry r ON"
                    + " r.interface_id = b.interface_id WHERE r.counterparty_system = 'HRMS' AND"
                    + " b.breaker_state = 'OPEN'";

    private static final String DECLARED_SINCE =
            "SELECT MIN(e.hrms_down_since) FROM hrms_fallback_enablement e WHERE"
                    + " e.enablement_status IN ('OPEN', 'UPLOADED')";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final ExportReader exportReader;

    public FallbackReconciliationService(
            NamedParameterJdbcTemplate named, Authorization policy, ExportReader exportReader) {
        this.named = named;
        this.policy = policy;
        this.exportReader = exportReader;
    }

    /** The reconciliation table: newest cycle first, each upload's differences before its rest. */
    @Transactional(readOnly = true)
    public Paged<Row> page(AccessContext context, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        Set<Long> scope = Scopes.of(context);
        return new Paged<>(
                rows(scope, (long) spec.page() * spec.size(), spec.size()),
                spec.page(),
                spec.size(),
                total(scope));
    }

    /** Rows {@link #export} would write (the size check before streaming, SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context) {
        policy.require(context, SCREEN, "EXPORT");
        return total(Scopes.of(context));
    }

    /**
     * The table as an export (SRS-G-13): one snapshot, batch by batch, checked against its count.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        sink.header(
                "Upload",
                "Payroll month",
                "Employee",
                "Name",
                "Others",
                "Item",
                "Uploaded",
                "HRMS (after restore)",
                "Difference effect",
                "Difference",
                "Next cycle",
                "Status");
        long expected =
                exportReader.readAll(
                        page -> batch(scope, page),
                        rows -> {
                            for (Row r : rows) {
                                sink.row(
                                        r.reference(),
                                        r.payrollYymm(),
                                        r.employeeNumber(),
                                        r.employeeName(),
                                        r.others(),
                                        r.item(),
                                        r.uploaded(),
                                        r.hrms(),
                                        r.effect(),
                                        r.difference(),
                                        r.nextCycleYymm(),
                                        r.status());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    /** The tile "HRMS interface" (any X-07 viewer; the interface is one for every office). */
    @Transactional(readOnly = true)
    public InterfaceStatus interfaceStatus(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource none = new MapSqlParameterSource();
        Instant breaker = instant(named.queryForObject(BREAKER_SINCE, none, Timestamp.class));
        Instant declared = instant(named.queryForObject(DECLARED_SINCE, none, Timestamp.class));
        Instant since =
                breaker == null || (declared != null && declared.isBefore(breaker))
                        ? declared
                        : breaker;
        return new InterfaceStatus(since != null, since);
    }

    private Page<Row> batch(Set<Long> scope, Pageable page) {
        return new PageImpl<>(
                rows(scope, page.getOffset(), page.getPageSize()), page, total(scope));
    }

    private long total(Set<Long> scope) {
        return RowCounts.of(
                named.queryForObject(
                        "SELECT COUNT(*) FROM (" + ROWS + ") r",
                        new MapSqlParameterSource("scope", scope),
                        Long.class));
    }

    private List<Row> rows(Set<Long> scope, long offset, int size) {
        return named.query(
                "SELECT * FROM ("
                        + ROWS
                        + ") r ORDER BY r.yymm DESC, r.upload_id DESC, r.group_rank, r.line_no"
                        + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                new MapSqlParameterSource("scope", scope)
                        .addValue("offset", offset)
                        .addValue("size", size),
                (rs, i) -> row(rs));
    }

    private static Row row(ResultSet rs) throws SQLException {
        FallbackReconciliation.Comparison line =
                FallbackReconciliation.compare(
                        new FallbackReconciliation.Facts(
                                rs.getString("record_type"),
                                rs.getString("hrms_summary"),
                                rs.getBigDecimal("pay_days"),
                                rs.getBigDecimal("lwp_days"),
                                rs.getBigDecimal("half_pay_days"),
                                rs.getBigDecimal("other_leave_days"),
                                date(rs.getDate("event_date"))));
        String effect = rs.getString("effect");
        return new Row(
                rs.getString("reference_no"),
                rs.getString("yymm"),
                rs.getString("emp_no"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getLong("others"),
                line.item(),
                line.uploaded(),
                line.hrms(),
                effect == null ? "NONE" : effect,
                line.difference(),
                rs.getString("next_cycle"),
                rs.getString("status"));
    }

    private static String name(String first, String last) {
        if (first == null) {
            return null;
        }
        return last == null ? first : first + " " + last;
    }

    private static LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
