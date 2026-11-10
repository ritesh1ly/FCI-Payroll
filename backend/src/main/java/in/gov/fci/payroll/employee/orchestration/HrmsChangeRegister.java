package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.business.HrmsChangeRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The I-21 registers (plan 16 phase 16b): the HRMS Change Register (SRS-1-71) and the Name / DOB
 * Change Register (SRS-1-30), with their counts by status and by field, and their exports
 * (SRS-G-13). Restricted values stay masked in both (SRS-1-56). Same scope and filters as the task
 * list; the export needs I-21 EXPORT.
 *
 * <p>SRS-17-15 (US-GAP-80): the HRMS Change Register also carries every HRMS Update Request of the
 * scope with its outcome (task opened, linked, not yet in HRMS, fetch failed), one row each after
 * the tasks, with the request number and fetch result in the last two columns.
 *
 * <p>OPEN-QUESTION (PENDING 16-register-gaps): SRS-1-30 lists a "proof class" and SRS-1-71 labour
 * master changes; {@code hrms_change_task} has no proof class and labour masters are not I-21
 * tasks, so these columns are not produced yet.
 */
@Service
@Loggable
public class HrmsChangeRegister {

    /** Which register an export carries. */
    public enum Register {
        HRMS,
        NAME_DOB
    }

    /** One count of the register. */
    public record Count(String code, long count) {}

    /** SRS-1-30: counts by status and by field (correction type) for the filters. */
    public record Counts(long total, List<Count> byStatus, List<Count> byField) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final HrmsChangeService changes;
    private final SystemParameters parameters;
    private final OrgDirectory org;

    public HrmsChangeRegister(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            HrmsChangeService changes,
            SystemParameters parameters,
            OrgDirectory org) {
        this.org = org;
        this.named = named;
        this.policy = policy;
        this.changes = changes;
        this.parameters = parameters;
    }

    @Transactional(readOnly = true)
    public Counts counts(AccessContext context, HrmsChangeService.Filter filter) {
        MapSqlParameterSource params = changes.params(changes.scopeUnit(context, "VIEW"), filter);
        List<Count> byStatus = count(HrmsChangeService.STATUS, params);
        List<Count> byField = count("f.field_code", params);
        return new Counts(byStatus.stream().mapToLong(Count::count).sum(), byStatus, byField);
    }

    /** SRS-G-13: the rows the export will carry (checked against the limit first). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, HrmsChangeService.Filter filter) {
        long tasks = counts(context, filter).total();
        if (filter.nameDob()) {
            return tasks;
        }
        return tasks
                + RowCounts.of(
                        named.queryForObject(
                                "SELECT COUNT(*)" + REQUESTS_FROM + REQUESTS_WHERE,
                                requestParams(changes.scopeUnit(context, "VIEW"), filter),
                                Long.class));
    }

    /**
     * SRS-1-30 / SRS-1-71: the register of the unit (all units at HQ) in one read-only snapshot,
     * newest first as on screen; the rows written are checked against the snapshot's count.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(
            AccessContext context,
            HrmsChangeService.Filter filter,
            Register register,
            ExportSink sink)
            throws IOException {
        Long scope = changes.scopeUnit(context, "VIEW");
        policy.requireAt(context, HrmsChangeService.SCREEN, "EXPORT", exportUnit(context, scope));
        MapSqlParameterSource params = changes.params(scope, filter);
        Long expected =
                named.queryForObject(
                        "SELECT COUNT(*)" + HrmsChangeService.FROM + HrmsChangeService.WHERE,
                        params,
                        Long.class);
        OptionalLong ageing = changes.ageing();
        OptionalLong retirementAge = parameters.configured(SystemParameters.Code.RETIREMENT_AGE);
        OptionalLong epsAge = parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE);
        sink.header(register == Register.NAME_DOB ? NAME_DOB_HEADER : HRMS_HEADER);
        try {
            named.query(
                    HrmsChangeService.SELECT
                            + HrmsChangeService.FROM
                            + HrmsChangeService.WHERE
                            + " ORDER BY t.received_at DESC, t.hrms_change_task_id DESC",
                    params,
                    rs -> {
                        HrmsChangeService.TaskView v = changes.view(rs, ageing);
                        List<Object> cells = new ArrayList<>();
                        if (register == Register.NAME_DOB) {
                            boolean dob = "DATE_OF_BIRTH".equals(v.fieldCode());
                            LocalDate birth = dob ? LocalDate.parse(v.newValue()) : null;
                            cells.addAll(
                                    List.of(
                                            v.taskNo(),
                                            v.eis(),
                                            nz(v.employee()),
                                            v.fieldLabel(),
                                            nz(v.currentValue()),
                                            nz(v.newValue()),
                                            nz(reason(v))));
                            cells.addAll(order(v));
                            cells.add(v.effectiveDate());
                            cells.add(
                                    dob && retirementAge.isPresent()
                                            ? HrmsChangeRules.retirementDate(
                                                    birth, (int) retirementAge.getAsLong())
                                            : "");
                            cells.add(
                                    dob && epsAge.isPresent()
                                            ? HrmsChangeRules.epsCutoff(
                                                    birth, (int) epsAge.getAsLong())
                                            : "");
                            cells.addAll(people(v));
                            cells.add(v.status());
                            cells.add(v.receivedAt());
                            cells.add(nz(rs.getTimestamp("maker_submitted_at")));
                            cells.add(nz(rs.getTimestamp("checker_decided_at")));
                        } else {
                            cells.addAll(
                                    List.of(
                                            v.taskNo(),
                                            nz(v.hrmsRequestId()),
                                            v.eis(),
                                            nz(v.employee()),
                                            v.fieldLabel(),
                                            nz(v.currentValue()),
                                            nz(v.newValue()),
                                            v.effectiveDate()));
                            cells.addAll(order(v));
                            cells.add(nz(v.orderRef()));
                            cells.addAll(people(v));
                            cells.add(v.status());
                            cells.add(v.receivedAt());
                            cells.add(v.daysPending());
                            cells.add(v.overdue() ? "Y" : "N");
                            cells.add(nz(rs.getTimestamp("applied_at")));
                            cells.add("");
                            cells.add("");
                        }
                        try {
                            sink.row(cells.toArray());
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        long requests =
                register == Register.HRMS ? requests(requestParams(scope, filter), sink) : 0;
        ExportSink.requireComplete(RowCounts.of(expected) + requests, sink);
    }

    private static final String REQUESTS_FROM =
            " FROM hrms_update_request r JOIN employee e ON e.employee_id = r.employee_id JOIN"
                    + " hrms_pay_effect_field f ON f.pay_effect_field_id = r.pay_effect_field_id"
                    + " LEFT JOIN hrms_change_task t ON t.hrms_change_task_id = r.linked_task_id";

    /** The task filters as they apply to a request (its status is its outcome). */
    private static final String REQUESTS_WHERE =
            " WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND (:status IS NULL OR"
                    + " r.request_status = :status) AND (:field IS NULL OR f.field_code = :field)"
                    + " AND (:eis IS NULL OR e.emp_num = :eis)";

    private MapSqlParameterSource requestParams(Long unit, HrmsChangeService.Filter filter) {
        Set<Long> locations = unit == null ? Set.of(-1L) : org.subtree(unit);
        return new MapSqlParameterSource()
                .addValue("all", unit == null ? "Y" : "N", Types.VARCHAR)
                .addValue("locations", locations)
                .addValue("status", filter.status(), Types.VARCHAR)
                .addValue("field", filter.fieldCode(), Types.VARCHAR)
                .addValue("eis", filter.eis(), Types.VARCHAR);
    }

    /**
     * SRS-17-15: "Show each request and its outcome ... in the HRMS Change Register": the requests
     * after the tasks, newest first, in the register's columns (the linked task, the HRMS reference
     * the member gave, the field, the claimed effective date, who raised it, its status and when),
     * then the request number and the fetch result. A request carries no value of its own (it never
     * changes one), so the value columns stay empty. Returns the expected row count.
     */
    private long requests(MapSqlParameterSource params, ExportSink sink) throws IOException {
        Long expected =
                named.queryForObject(
                        "SELECT COUNT(*)" + REQUESTS_FROM + REQUESTS_WHERE, params, Long.class);
        try {
            named.query(
                    "SELECT r.request_no, r.hrms_reference, e.emp_num, e.first_name, e.last_name,"
                            + " f.field_label, r.claimed_effective_date, r.raised_by,"
                            + " r.request_status, r.raised_at, r.fetch_result, t.task_no"
                            + REQUESTS_FROM
                            + REQUESTS_WHERE
                            + " ORDER BY r.raised_at DESC, r.hrms_update_request_id DESC",
                    params,
                    rs -> {
                        String last = rs.getString("last_name");
                        try {
                            sink.row(
                                    nz(rs.getString("task_no")),
                                    nz(rs.getString("hrms_reference")),
                                    rs.getString("emp_num"),
                                    rs.getString("first_name") + (last == null ? "" : " " + last),
                                    rs.getString("field_label"),
                                    "",
                                    "",
                                    nz(rs.getDate("claimed_effective_date")),
                                    "",
                                    "",
                                    "",
                                    "",
                                    rs.getString("raised_by"),
                                    "",
                                    rs.getString("request_status"),
                                    rs.getTimestamp("raised_at"),
                                    "",
                                    "",
                                    "",
                                    rs.getString("request_no"),
                                    nz(rs.getString("fetch_result")));
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        return RowCounts.of(expected);
    }

    private static final String[] HRMS_HEADER = {
        "task_no",
        "hrms_request_id",
        "eis",
        "employee",
        "field",
        "old_value",
        "new_value",
        "effective_date",
        "order_no",
        "order_date",
        "issuing_authority",
        "order_ref",
        "maker",
        "checker",
        "status",
        "received_at",
        "days_pending",
        "overdue",
        "applied_at",
        "update_request_no",
        "fetch_result"
    };

    private static final String[] NAME_DOB_HEADER = {
        "request_ref",
        "eis",
        "employee",
        "correction",
        "old_value",
        "new_value",
        "reason",
        "order_no",
        "order_date",
        "issuing_authority",
        "effective_date",
        "retirement_date",
        "eps_cutoff_date",
        "maker",
        "checker",
        "status",
        "received_at",
        "submitted_at",
        "decided_at"
    };

    private List<Count> count(String expression, MapSqlParameterSource params) {
        return named.query(
                "SELECT "
                        + expression
                        + " AS code, COUNT(*) AS n"
                        + HrmsChangeService.FROM
                        + HrmsChangeService.WHERE
                        + " GROUP BY "
                        + expression
                        + " ORDER BY 1",
                params,
                (rs, i) -> new Count(rs.getString("code"), rs.getLong("n")));
    }

    /** The unit the export permission is checked at: the user's own office when HQ-wide. */
    private static long exportUnit(AccessContext context, Long scope) {
        return scope == null ? context.locationId() : scope;
    }

    private static List<Object> order(HrmsChangeService.TaskView v) {
        if (v.orderHeldInHrms()) {
            return List.of("HELD_IN_HRMS", "", "");
        }
        return List.of(nz(v.orderNo()), nz(v.orderDate()), nz(v.issuingAuthority()));
    }

    private static List<Object> people(HrmsChangeService.TaskView v) {
        return List.of(nz(v.makerUser()), nz(v.checkerUser()));
    }

    private static String reason(HrmsChangeService.TaskView v) {
        return v.rejectionReason() != null ? v.rejectionReason() : v.returnReason();
    }

    private static Object nz(Object value) {
        return value == null ? "" : value;
    }
}
