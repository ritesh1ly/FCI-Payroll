package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.employee.api.HrmsRecords;
import in.gov.fci.payroll.employee.business.HrmsChangeRules;
import in.gov.fci.payroll.employee.business.HrmsUpdateRules;
import in.gov.fci.payroll.employee.business.HrmsUpdateRules.Channel;
import in.gov.fci.payroll.employee.business.HrmsUpdateRules.FetchResult;
import in.gov.fci.payroll.employee.business.HrmsUpdateRules.Status;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HRMS Update Request (plan 16 phase 16c; SRS-1-70; DDL {@code hrms_update_request}): raised by the
 * employee in eSeva (E-01) or by the Payroll Maker on I-21 when a change made in HRMS has not
 * reached Payroll. The request never changes a value by itself:
 *
 * <ol>
 *   <li>a change HRMS already sent for that employee and field, still pending on I-21, is linked
 *       (LINKED_EXISTING), never opened twice;
 *   <li>otherwise the employee's current record is fetched from HRMS ({@link HrmsRecords}): a value
 *       that differs from Payroll opens the I-21 task (TASK_OPENED, source UPDATE_REQUEST_FETCH);
 *       the same value or none closes the request as NOT_YET_IN_HRMS with the message to have it
 *       updated in HRMS first;
 *   <li>a fetch that fails, or no HRMS interface yet (module 23, D16-5), records FETCH_FAILED; the
 *       Maker may fetch again.
 * </ol>
 *
 * <p>OPEN-QUESTION (PENDING 16-upd-attachment): the optional order copy (a document_attachment on
 * HRMS_UPDATE_REQUEST) and the eSeva submission link (ess_submission, module 14) are not built yet.
 */
@Service
@Loggable
public class HrmsUpdateRequestService {

    /** Audit entity of a request (key: request_no). */
    public static final String ENTITY = "hrms_update_request";

    static final String ESEVA = "E-01";

    private static final Logger LOG = LoggerFactory.getLogger(HrmsUpdateRequestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** A field the request may name (the fields with pay effect in force). */
    public record FieldOption(String code, String label, String group) {}

    /**
     * What the requester gives (the Maker names the employee by EIS; the employee is the
     * session's).
     */
    public record NewRequest(
            String eis, String fieldCode, String hrmsReference, LocalDate effectiveDate) {}

    /** A request as listed, with its outcome. */
    public record RequestView(
            long id,
            String requestNo,
            String eis,
            String employee,
            String fieldCode,
            String fieldLabel,
            String channel,
            String raisedBy,
            Instant raisedAt,
            String hrmsReference,
            LocalDate effectiveDate,
            String fetchResult,
            String status,
            String messageCode,
            String taskNo,
            // E-01 l.3308 (US-GAP-258): the linked I-21 task's status, so the outcome reads
            // "Approved and applied" once the task is approved.
            String taskStatus) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;
    private final AuditService audit;
    private final HrmsChangeService changes;
    private final HrmsChangeApplier applier;
    private final ObjectProvider<HrmsRecords> hrms;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public HrmsUpdateRequestService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            OrgDirectory org,
            Authorization policy,
            AuditService audit,
            HrmsChangeService changes,
            HrmsChangeApplier applier,
            ObjectProvider<HrmsRecords> hrms,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.named = named;
        this.org = org;
        this.policy = policy;
        this.audit = audit;
        this.changes = changes;
        this.applier = applier;
        this.hrms = hrms;
        this.events = events;
        this.clock = clock;
    }

    /** SRS-1-70: the fields with pay effect the requester selects from. */
    @Transactional(readOnly = true)
    public List<FieldOption> fields() {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        return jdbc.query(
                "SELECT field_code, field_label, field_group FROM hrms_pay_effect_field f WHERE"
                        + " has_pay_effect = 'Y' AND effective_from <= ? AND (effective_to IS NULL"
                        + " OR effective_to >= ?) AND version_no = (SELECT MAX(g.version_no) FROM"
                        + " hrms_pay_effect_field g WHERE g.field_code = f.field_code AND"
                        + " g.effective_from <= ?) ORDER BY field_group, field_label",
                (rs, i) ->
                        new FieldOption(
                                rs.getString("field_code"),
                                rs.getString("field_label"),
                                rs.getString("field_group")),
                java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today));
    }

    /**
     * SRS-1-70: the Payroll Maker raises the request for an employee of the unit (I-21 REQUEST).
     */
    @Transactional
    public RequestView raiseForEmployee(AccessContext context, NewRequest request) {
        if (request.eis() == null || request.eis().isBlank()) {
            throw invalid("NO_EMPLOYEE");
        }
        Map<String, Object> employee =
                jdbc
                        .queryForList(
                                "SELECT employee_id, location_id FROM employee WHERE emp_num = ?",
                                request.eis().strip())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> invalid("UNKNOWN_EMPLOYEE"));
        long employeeId = ((Number) employee.get("EMPLOYEE_ID")).longValue();
        policy.requireAt(
                context,
                HrmsChangeService.SCREEN,
                "REQUEST",
                unitOf(((Number) employee.get("LOCATION_ID")).longValue()));
        return raise(context, employeeId, request, Channel.PAYROLL_MAKER);
    }

    /** SRS-1-70: the employee raises the request in eSeva for their own record (E-01 CREATE). */
    @Transactional
    public RequestView raiseMine(AccessContext context, NewRequest request) {
        return raise(context, own(context, "CREATE"), request, Channel.ESEVA);
    }

    /** SRS-1-70: the Maker fetches HRMS again for a request whose fetch failed. */
    @Transactional
    public RequestView refetch(AccessContext context, long id) {
        Map<String, Object> row = row(id);
        policy.requireAt(
                context,
                HrmsChangeService.SCREEN,
                "REQUEST",
                unitOf(((Number) row.get("LOCATION_ID")).longValue()));
        Status status = Status.valueOf((String) row.get("REQUEST_STATUS"));
        if (!HrmsUpdateRules.refetchable(status)) {
            throw new FciException(
                    ApiErrorCode.I21_STATE,
                    new Object[] {row.get("REQUEST_NO") + " " + status},
                    List.of(),
                    null);
        }
        resolve(
                id,
                (String) row.get("REQUEST_NO"),
                ((Number) row.get("EMPLOYEE_ID")).longValue(),
                (String) row.get("FIELD_CODE"),
                (String) row.get("HRMS_REFERENCE"),
                HrmsChangeService.date(row.get("CLAIMED_EFFECTIVE_DATE")),
                login(context));
        return view(id);
    }

    /** I-21: the requests of the unit (all units at HQ), newest first, paged. */
    @Transactional(readOnly = true)
    public Paged<RequestView> page(AccessContext context, PageSpec spec) {
        Long unit = changes.scopeUnit(context, "VIEW");
        Set<Long> locations = unit == null ? Set.of(-1L) : org.subtree(unit);
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("all", unit == null ? "Y" : "N", Types.VARCHAR)
                        .addValue("locations", locations);
        return paged(" WHERE (:all = 'Y' OR e.location_id IN (:locations))", params, spec);
    }

    /** E-01: the employee's own requests, newest first, paged. */
    @Transactional(readOnly = true)
    public Paged<RequestView> mine(AccessContext context, PageSpec spec) {
        long employeeId = own(context, "SELF");
        return paged(
                " WHERE r.employee_id = :employee",
                new MapSqlParameterSource("employee", employeeId),
                spec);
    }

    // ---------------------------------------------------------------- the request

    private RequestView raise(
            AccessContext context, long employeeId, NewRequest request, Channel channel) {
        Map<String, Object> field = field(request.fieldCode());
        HrmsUpdateRules.defect(
                        "Y".equals(field.get("HAS_PAY_EFFECT")),
                        request.effectiveDate() != null,
                        request.hrmsReference())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        String reference =
                request.hrmsReference() == null || request.hrmsReference().isBlank()
                        ? null
                        : request.hrmsReference().strip();
        String me = login(context);
        Instant now = clock.instant();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO hrms_update_request (request_no, employee_id,"
                                            + " pay_effect_field_id, raised_channel, raised_by,"
                                            + " raised_at, hrms_reference, claimed_effective_date,"
                                            + " request_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                                            + " 'SUBMITTED')",
                                    new String[] {"hrms_update_request_id"});
                    // request_no is unique: a per-insert placeholder until the id gives the number
                    ps.setString(1, "TMP-" + UUID.randomUUID().toString().substring(0, 26));
                    ps.setLong(2, employeeId);
                    ps.setLong(3, ((Number) field.get("PAY_EFFECT_FIELD_ID")).longValue());
                    ps.setString(4, channel.name());
                    ps.setString(5, me);
                    ps.setTimestamp(6, Timestamp.from(now));
                    ps.setString(7, reference);
                    ps.setObject(8, request.effectiveDate());
                    return ps;
                },
                key);
        long id = GeneratedKeys.id(key);
        String requestNo = HrmsUpdateRules.requestNo(LocalDate.ofInstant(now, IST).getYear(), id);
        jdbc.update(
                "UPDATE hrms_update_request SET request_no = ? WHERE hrms_update_request_id = ?",
                requestNo,
                id);
        audit.record(
                me,
                AuditService.Entry.change(
                                ENTITY,
                                requestNo,
                                "request_status",
                                null,
                                Status.SUBMITTED.name(),
                                channel + " " + field.get("FIELD_CODE"))
                        .effective(request.effectiveDate())
                        .module(HrmsChangeService.SCREEN));
        if (channel == Channel.ESEVA) {
            // SRS-17-3 / SRS-17-18 (plan 14 phase 14a): the member's request in the eSeva log.
            events.publishEvent(
                    new SelfServiceEvents.Submitted(
                            employeeId,
                            SelfServiceEvents.Type.HRMS_UPDATE_REQUEST,
                            ENTITY,
                            id,
                            null,
                            null,
                            id,
                            request.fieldCode()
                                    + (request.effectiveDate() == null
                                            ? ""
                                            : " from " + request.effectiveDate())
                                    + (reference == null ? "" : " (" + reference + ")"),
                            null,
                            me));
        }
        resolve(
                id,
                requestNo,
                employeeId,
                (String) field.get("FIELD_CODE"),
                reference,
                request.effectiveDate(),
                me);
        return view(id);
    }

    /** Links a pending task, or fetches HRMS and opens the task / closes the request. */
    private void resolve(
            long id,
            String requestNo,
            long employeeId,
            String fieldCode,
            String reference,
            LocalDate effective,
            String actor) {
        Optional<Long> pending = pendingTask(employeeId, fieldCode);
        if (pending.isPresent()) {
            finish(id, requestNo, Status.LINKED_EXISTING, null, null, pending.get(), null, actor);
            return;
        }
        HrmsRecords records = hrms.getIfAvailable();
        if (records == null) {
            finish(
                    id,
                    requestNo,
                    Status.FETCH_FAILED,
                    FetchResult.FETCH_FAILED,
                    null,
                    null,
                    HrmsUpdateRules.FETCH_NOT_CONFIGURED,
                    actor);
            return;
        }
        Optional<HrmsRecords.Fetched> fetched;
        try {
            fetched = records.current(employeeId, fieldCode);
        } catch (HrmsRecords.Unavailable failed) {
            LOG.warn("HRMS fetch failed for request {}: {}", requestNo, failed.getMessage());
            finish(
                    id,
                    requestNo,
                    Status.FETCH_FAILED,
                    FetchResult.FETCH_FAILED,
                    null,
                    null,
                    HrmsUpdateRules.FETCH_FAILED,
                    actor);
            return;
        }
        Map<String, Object> field = field(fieldCode);
        String value = fetched.map(HrmsRecords.Fetched::value).orElse(null);
        FetchResult result =
                HrmsUpdateRules.compare(
                        Optional.ofNullable(value), applier.current(field, employeeId));
        if (result == FetchResult.NO_CHANGE) {
            finish(
                    id,
                    requestNo,
                    Status.NOT_YET_IN_HRMS,
                    result,
                    value,
                    null,
                    HrmsUpdateRules.NOT_YET_IN_HRMS,
                    actor);
            return;
        }
        LocalDate from = fetched.map(HrmsRecords.Fetched::effectiveDate).orElse(effective);
        HrmsChanges.Received task =
                changes.receive(
                        new HrmsChanges.Change(
                                employeeId,
                                fieldCode,
                                value,
                                from,
                                reference == null ? requestNo : reference,
                                HrmsChanges.Source.UPDATE_REQUEST_FETCH));
        finish(
                id,
                requestNo,
                task.duplicate() ? Status.LINKED_EXISTING : Status.TASK_OPENED,
                result,
                value,
                task.taskId(),
                null,
                actor);
    }

    private void finish(
            long id,
            String requestNo,
            Status status,
            FetchResult result,
            String fetchedValue,
            Long taskId,
            String message,
            String actor) {
        boolean closed = status == Status.NOT_YET_IN_HRMS || status == Status.LINKED_EXISTING;
        events.publishEvent(
                new SelfServiceEvents.StatusChanged(ENTITY, id, logStatus(status), actor));
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                "UPDATE hrms_update_request SET request_status = ?, fetch_result = ?, fetched_at ="
                    + " ?, hrms_value_fetched = ?, linked_task_id = ?, closure_message_code = ?,"
                    + " closed_at = ?, row_version = row_version + 1 WHERE hrms_update_request_id ="
                    + " ?",
                status.name(),
                result == null ? null : result.name(),
                result == null ? null : now,
                fetchedValue,
                taskId,
                message,
                closed ? now : null,
                id);
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY,
                                requestNo,
                                "request_status",
                                Status.SUBMITTED.name(),
                                status.name(),
                                message == null ? status.name() : message)
                        .module(HrmsChangeService.SCREEN));
    }

    /**
     * SRS-17-15 in the eSeva log: a task opened or linked is in the I-21 maker-checker; a request
     * closed (not yet in HRMS) has its outcome; a failed fetch waits for the Maker's new fetch.
     */
    static SelfServiceEvents.Status logStatus(Status status) {
        return switch (status) {
            case TASK_OPENED, LINKED_EXISTING -> SelfServiceEvents.Status.ROUTED;
            case NOT_YET_IN_HRMS, CLOSED -> SelfServiceEvents.Status.ACTIONED;
            case SUBMITTED, FETCH_FAILED -> SelfServiceEvents.Status.SUBMITTED;
        };
    }

    private Optional<Long> pendingTask(long employeeId, String fieldCode) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("employee", employeeId)
                        .addValue("field", fieldCode)
                        .addValue(
                                "pending",
                                HrmsChangeRules.PENDING.stream().map(Enum::name).toList());
        return named
                .queryForList(
                        "SELECT t.hrms_change_task_id FROM hrms_change_task t JOIN"
                                + " hrms_pay_effect_field f ON f.pay_effect_field_id ="
                                + " t.pay_effect_field_id WHERE t.employee_id = :employee AND"
                                + " f.field_code = :field AND t.task_status IN (:pending) ORDER BY"
                                + " t.hrms_change_task_id DESC",
                        params,
                        Long.class)
                .stream()
                .findFirst();
    }

    // ---------------------------------------------------------------- reads

    private static final String SELECT =
            "SELECT r.hrms_update_request_id, r.request_no, e.emp_num, e.first_name, e.last_name,"
                    + " f.field_code, f.field_label, r.raised_channel, r.raised_by, r.raised_at,"
                    + " r.hrms_reference, r.claimed_effective_date, r.fetch_result,"
                    + " r.request_status, r.closure_message_code, t.task_no, t.task_status";

    private static final String FROM =
            " FROM hrms_update_request r JOIN employee e ON e.employee_id = r.employee_id JOIN"
                    + " hrms_pay_effect_field f ON f.pay_effect_field_id = r.pay_effect_field_id"
                    + " LEFT JOIN hrms_change_task t ON t.hrms_change_task_id = r.linked_task_id";

    private Paged<RequestView> paged(String where, MapSqlParameterSource params, PageSpec spec) {
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + where, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        List<RequestView> rows =
                named.query(
                        SELECT
                                + FROM
                                + where
                                + " ORDER BY r.raised_at DESC, r.hrms_update_request_id DESC"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        params,
                        (rs, i) -> view(rs));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    private RequestView view(long id) {
        return named.query(
                        SELECT + FROM + " WHERE r.hrms_update_request_id = :id",
                        new MapSqlParameterSource("id", id),
                        (rs, i) -> view(rs))
                .get(0);
    }

    private static RequestView view(ResultSet rs) throws SQLException {
        Timestamp raised = rs.getTimestamp("raised_at");
        String last = rs.getString("last_name");
        String first = rs.getString("first_name");
        return new RequestView(
                rs.getLong("hrms_update_request_id"),
                rs.getString("request_no"),
                rs.getString("emp_num"),
                last == null || last.isBlank() ? first : first + " " + last,
                rs.getString("field_code"),
                rs.getString("field_label"),
                rs.getString("raised_channel"),
                rs.getString("raised_by"),
                raised == null ? null : raised.toInstant(),
                rs.getString("hrms_reference"),
                HrmsChangeService.date(rs.getObject("claimed_effective_date")),
                rs.getString("fetch_result"),
                rs.getString("request_status"),
                rs.getString("closure_message_code"),
                rs.getString("task_no"),
                rs.getString("task_status"));
    }

    private Map<String, Object> row(long id) {
        return jdbc
                .queryForList(
                        "SELECT r.*, e.location_id, f.field_code FROM hrms_update_request r JOIN"
                                + " employee e ON e.employee_id = r.employee_id JOIN"
                                + " hrms_pay_effect_field f ON f.pay_effect_field_id ="
                                + " r.pay_effect_field_id WHERE r.hrms_update_request_id = ?",
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private Map<String, Object> field(String code) {
        if (code == null || code.isBlank()) {
            throw invalid("NO_FIELD");
        }
        return changes.field(code.strip());
    }

    private long unitOf(long locationId) {
        OrgLocation unit =
                org.cycleUnitOf(locationId)
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
        return unit.getId();
    }

    /** The session's own employee record (eSeva); a user without one has no eSeva request. */
    private long own(AccessContext context, String action) {
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {ESEVA, action}, List.of(), null);
        }
        return context.employeeId();
    }

    private static FciException invalid(String defect) {
        return new FciException(ApiErrorCode.I21_REQUEST, new Object[] {defect}, List.of(), null);
    }

    private static String login(AccessContext context) {
        String login = context.loginId();
        return login.substring(0, Math.min(50, login.length()));
    }
}
