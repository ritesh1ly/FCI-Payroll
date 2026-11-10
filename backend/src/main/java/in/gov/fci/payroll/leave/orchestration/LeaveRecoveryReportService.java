package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave Recovery Report (SRS-LV-23; FRS V0.5 §7.15.7.2; screen I-11, I-11 EXPORT): the leave-driven
 * recoveries and arrears of locked cycles only, one row per event and component, with totals by
 * unit and by leave type. A row exists once the event's arrear was posted at a cycle lock
 * (BR-LEAVE-006, BR-LEAVE-010, BR-LEAVE-011): the cycle in which it was recovered is the event's
 * {@code posted_to_yymm}; what the net-pay floor held back is the residual carried forward (the
 * detail the lock wrote, still open; SRS-LV-19).
 *
 * <p>Not produced, and in the file as empty cells: the CPF / TDS / PT effect (no ledger holds it
 * per event: PENDING I11-REPORT). A month's own LWP reduction is not a recovery and is not listed;
 * PDF waits for the library (PENDING LIB-1). Excel and CSV are produced.
 */
@Service
@Loggable
public class LeaveRecoveryReportService {

    private static final String SCREEN = "I-11";

    /** BR-LEAVE-010 events as the FRS filter names them, by {@code leave_event_type}. */
    private static final String EVENT_NAME =
            "CASE x.leave_event_type WHEN 'CANCELLATION' THEN 'CANCELLATION' WHEN 'CONVERSION' THEN"
                    + " 'CONVERSION' ELSE 'LWP' END";

    private static final String FROM =
            " FROM leave_transaction x JOIN employee e ON e.employee_id = x.employee_id JOIN"
                + " hrms_change_task t ON t.hrms_change_task_id = x.hrms_change_task_id JOIN"
                + " arrear_event ev ON ev.source_module = 'LEAVE' AND ev.source_entity ="
                + " 'leave_transaction' AND ev.source_key = t.task_no JOIN arrear_detail d ON"
                + " d.arrear_event_id = ev.arrear_event_id AND d.employee_id = x.employee_id JOIN"
                + " pay_component c ON c.component_id = d.component_id LEFT JOIN org_location l ON"
                + " l.location_id = e.location_id WHERE ev.event_status IN ('APPROVED', 'POSTED')"
                + " AND ev.posted_to_yymm BETWEEN :fromMonth AND :toMonth AND d.arrear_status <>"
                + " 'REVERSED' AND (:all = 'Y' OR e.location_id IN (:locations)) AND (:office = 'N'"
                + " OR e.location_id IN (:offices)) AND (:eis IS NULL OR e.emp_num = :eis) AND"
                + " (:leaveType IS NULL OR x.leave_type_code = :leaveType) AND (:eventType IS NULL"
                + " OR "
                    + EVENT_NAME
                    + " = :eventType) AND (:status IS NULL OR ev.event_status = :status)";

    private static final String GROUP =
            " GROUP BY e.emp_num, e.first_name, e.last_name, l.location_code, l.location_name,"
                    + " x.leave_type_code, x.leave_event_type, x.hrms_sanction_ref, t.task_no,"
                    + " x.period_from, x.period_to, x.days, c.legacy_pay_code, c.component_name,"
                    + " ev.posted_to_yymm, ev.event_status, t.maker_user, t.checker_user";

    /**
     * Only an event a lock has posted from: a differential still APPROVED and nothing posted is not
     * yet of a locked cycle.
     */
    private static final String HAVING =
            " HAVING SUM(CASE WHEN d.arrear_status IN ('POSTED', 'INVOICED', 'PAID') THEN 1 ELSE 0"
                    + " END) > 0";

    /**
     * The report's filters.
     *
     * @param fromMonth first payroll month (YYYYMM), required
     * @param toMonth last payroll month (YYYYMM), required
     * @param officeId a Zone, Region, Division or Depot/Unit: that office and those under it
     * @param eventType LWP, CANCELLATION or CONVERSION
     * @param status APPROVED (a residual still carried) or POSTED
     */
    public record Filter(
            String fromMonth,
            String toMonth,
            Long officeId,
            String eis,
            String leaveType,
            String eventType,
            String status) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final OrgDirectory org;

    public LeaveRecoveryReportService(
            NamedParameterJdbcTemplate named, Authorization policy, OrgDirectory org) {
        this.named = named;
        this.policy = policy;
        this.org = org;
    }

    /** The rows the file will carry (checked against the export limit first). */
    @Transactional(readOnly = true)
    public long size(AccessContext context, Filter filter) {
        return RowCounts.of(
                named.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT e.emp_num"
                                + FROM
                                + GROUP
                                + HAVING
                                + ") counted",
                        params(context, filter),
                        Long.class));
    }

    /** SRS-LV-23: the report, in one read-only snapshot checked against its count. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void report(AccessContext context, Filter filter, ExportSink sink) throws IOException {
        long expected = size(context, filter);
        sink.title(
                "Leave Recovery Report",
                "Payroll months " + filter.fromMonth() + " to " + filter.toMonth(),
                "Run by " + context.loginId());
        sink.header(
                "employee_code",
                "name",
                "unit",
                "leave_type",
                "event_type",
                "event_reference",
                "event_date",
                "period_from",
                "period_to",
                "days",
                "pay_code",
                "component",
                "recovery_or_arrear",
                "cpf_tds_pt_effect",
                "cycle_recovered",
                "amount_recovered",
                "residual_carried_forward",
                "status",
                "maker",
                "checker");
        Map<String, BigDecimal> byUnit = new TreeMap<>();
        Map<String, BigDecimal> byType = new TreeMap<>();
        try {
            named.query(
                    "SELECT e.emp_num, e.first_name, e.last_name, l.location_code,"
                            + " l.location_name, x.leave_type_code, "
                            + EVENT_NAME
                            + " AS event_name, x.hrms_sanction_ref, t.task_no, MIN(t.applied_at) AS"
                            + " event_date, x.period_from, x.period_to, x.days, c.legacy_pay_code,"
                            + " c.component_name, SUM(d.arrear_amount) AS total_amount,"
                            + " ev.posted_to_yymm, SUM(CASE WHEN d.arrear_status IN ('POSTED',"
                            + " 'INVOICED', 'PAID') THEN d.arrear_amount ELSE 0 END) AS recovered,"
                            + " SUM(CASE WHEN d.arrear_status IN ('CALCULATED', 'APPROVED') THEN"
                            + " d.arrear_amount ELSE 0 END) AS residual, ev.event_status,"
                            + " t.maker_user, t.checker_user"
                            + FROM
                            + GROUP
                            + HAVING
                            + " ORDER BY l.location_code, e.emp_num, x.period_from,"
                            + " c.legacy_pay_code",
                    params(context, filter),
                    rs -> {
                        try {
                            write(rs, sink, byUnit, byType);
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        ExportSink.requireComplete(expected, sink);
        // Totals by unit and leave type, after the rows the count holds (FRS §7.15.7.2).
        for (Map.Entry<String, BigDecimal> u : byUnit.entrySet()) {
            sink.row(
                    "TOTAL UNIT",
                    u.getKey(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    u.getValue());
        }
        for (Map.Entry<String, BigDecimal> t : byType.entrySet()) {
            sink.row(
                    "TOTAL LEAVE TYPE",
                    t.getKey(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    t.getValue());
        }
    }

    private static void write(
            ResultSet rs,
            ExportSink sink,
            Map<String, BigDecimal> byUnit,
            Map<String, BigDecimal> byType)
            throws SQLException, IOException {
        String last = rs.getString("last_name");
        String first = rs.getString("first_name");
        String name = last == null ? first : first + " " + last;
        String code = rs.getString("location_code");
        String unit = code == null ? "" : code + " - " + rs.getString("location_name");
        String type = rs.getString("leave_type_code");
        BigDecimal amount = rs.getBigDecimal("total_amount");
        byUnit.merge(unit, amount, BigDecimal::add);
        byType.merge(type, amount, BigDecimal::add);
        String reference = rs.getString("hrms_sanction_ref");
        sink.row(
                rs.getString("emp_num"),
                name,
                unit,
                type,
                rs.getString("event_name"),
                reference == null ? rs.getString("task_no") : reference,
                rs.getTimestamp("event_date"),
                date(rs.getDate("period_from")),
                date(rs.getDate("period_to")),
                rs.getBigDecimal("days"),
                rs.getInt("legacy_pay_code"),
                rs.getString("component_name"),
                amount,
                null,
                rs.getString("posted_to_yymm"),
                rs.getBigDecimal("recovered"),
                rs.getBigDecimal("residual"),
                rs.getString("event_status"),
                rs.getString("maker_user"),
                rs.getString("checker_user"));
    }

    private static LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private MapSqlParameterSource params(AccessContext context, Filter f) {
        policy.require(context, SCREEN, "EXPORT");
        if (f.fromMonth().compareTo(f.toMonth()) > 0) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("fromMonth", f.fromMonth(), Types.VARCHAR)
                .addValue("toMonth", f.toMonth(), Types.VARCHAR)
                .addValue("office", f.officeId() == null ? "N" : "Y", Types.VARCHAR)
                .addValue(
                        "offices",
                        f.officeId() == null ? java.util.Set.of(-1L) : org.subtree(f.officeId()))
                .addValue("eis", f.eis(), Types.VARCHAR)
                .addValue("leaveType", f.leaveType(), Types.VARCHAR)
                .addValue("eventType", f.eventType(), Types.VARCHAR)
                .addValue("status", f.status(), Types.VARCHAR);
    }
}
