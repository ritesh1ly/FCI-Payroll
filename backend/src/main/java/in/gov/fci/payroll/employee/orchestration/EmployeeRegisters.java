package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.HrmsRecords;
import in.gov.fci.payroll.employee.business.HrmsChangeRules;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.logging.PiiMasks;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.Likes;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads Wireframes v6.1 draws on the Employee screens (OD-121; PENDING API-16-8), beside the
 * use cases of {@link EmployeeService}, {@link EmployeeEventService} and {@link HrmsChangeService}:
 * M-01 office filter, export, CPF Member ID history (SRS-1-76, DDL cpf_member_id_history),
 * staff-number history (SRS-16-7) and the live HRMS read (SRS-1-70); I-17 received events
 * (SRS-1-63); the M-11 Employee Transfer Register (SRS l.1343); the I-21 tiles, register, DOB
 * tasks, bulk orders and field list (SRS-1-28, SRS-1-67, SRS-1-71). Every read is bounded by the
 * session's data scope in the query; restricted values stay masked (SRS-1-56). Nothing here writes
 * to a business record but the M-01 order copy (SRS-G-30).
 */
@Service
@Loggable
public class EmployeeRegisters {

    /** The attachment entity of an M-01 correction / salary release order (key: EIS number). */
    public static final String EMPLOYEE_ORDERS = "employee";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> OFFICE_ORDER_TYPES = Set.of("HQ", "ZO", "RO", "DO");

    /** One office of the M-01 filter. */
    public record Office(String code, String label) {}

    /** One row of the M-01 Member ID History (SRS-1-76). */
    public record MemberIdRow(
            String idType,
            String memberId,
            String category,
            String zone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            String reason) {}

    /** One row of the M-01 Staff Number History (SRS-16-7); the first is the original allotment. */
    public record StaffNumberRow(
            String oldNumber,
            String newNumber,
            String origin,
            String destination,
            LocalDate effectiveDate,
            String event) {}

    /**
     * What M-01's Record Detail adds to the record: the fields it draws that the record view does
     * not carry, and what HRMS holds now through the live read.
     *
     * @param hrmsAvailable the live read answered
     */
    public record RecordExtras(
            String cadre,
            String payScaleCode,
            BigDecimal oldBasic,
            String bankCode,
            String legacyEmpNum,
            String recordOrigin,
            boolean hrmsAvailable,
            BigDecimal hrmsBasic,
            String hrmsStatus) {}

    /** One row of I-17 Life-cycle events received. */
    public record ReceivedEvent(
            long id,
            String eventType,
            String eis,
            String employee,
            LocalDate effective,
            LocalDate endDate,
            BigDecimal subsistencePercent,
            String releaseFrom,
            String releaseTo,
            String interveningPeriod,
            String reason,
            String source,
            String hrmsRequestId,
            String orderReference,
            Long taskId,
            String taskNo,
            String taskStatus) {}

    /** One row of the M-11 Employee Transfer Register. */
    public record TransferRow(
            long id,
            String panMasked,
            String eis,
            String employee,
            String oldStaffNo,
            String newStaffNo,
            String origin,
            String destination,
            String cpfMemberId,
            LocalDate effectiveDate,
            Long taskId,
            String taskNo,
            String taskStatus,
            String approvalStatus) {}

    /** The M-11 tiles of the current month. */
    public record TransferSummary(String period, long received, long reissued, long withChecker) {}

    /** The I-21 tiles over the whole scope. */
    public record TaskSummary(
            long pending,
            long beforeFreeze,
            long afterFreeze,
            long withChecker,
            long ageing,
            long blocking,
            long autoApplied) {}

    /** One row of the HRMS Change Register tab: a task, or an HRMS Update Request. */
    public record RegisterRow(
            String kind,
            String reference,
            Instant received,
            String eis,
            String employee,
            String field,
            String oldValue,
            String newValue,
            String hrmsRequestId,
            LocalDate effective,
            String order,
            String maker,
            String checker,
            String status,
            Long daysPending) {}

    /** One group of the Fields with pay effect tab. */
    public record FieldGroup(String group, List<String> fields, List<String> payEffects) {}

    /** One row of DOB tasks needing extra attention. */
    public record DobTask(
            long id,
            String taskNo,
            String eis,
            String employee,
            String currentDob,
            String newDob,
            LocalDate newRetirement,
            boolean retirementPast,
            boolean retiresSoon,
            String status,
            Long approvalId,
            String rejectionReason) {}

    /** One bulk order. */
    public record BulkOrder(
            long id,
            String orderRef,
            String orderNo,
            LocalDate orderDate,
            String authority,
            String subject,
            int taskCount,
            String status,
            Long approvalId,
            long ready,
            long held) {}

    /** One task under a bulk order. */
    public record OrderTask(
            long id,
            String taskNo,
            String eis,
            String employee,
            String fromValue,
            String toValue,
            String status,
            String fieldCode) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final OrgDirectory org;
    private final EmployeeService employees;
    private final EmployeeRepository repository;
    private final HrmsChangeService changes;
    private final DocumentStore documents;
    private final ObjectProvider<HrmsRecords> hrms;
    private final Clock clock;

    public EmployeeRegisters(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            OrgDirectory org,
            EmployeeService employees,
            EmployeeRepository repository,
            HrmsChangeService changes,
            DocumentStore documents,
            ObjectProvider<HrmsRecords> hrms,
            Clock clock) {
        this.named = named;
        this.policy = policy;
        this.org = org;
        this.employees = employees;
        this.repository = repository;
        this.changes = changes;
        this.documents = documents;
        this.hrms = hrms;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- M-01

    /** The offices of the session's scope down to the division (the M-01 office filter). */
    @Transactional(readOnly = true)
    public List<Office> offices(AccessContext context) {
        policy.require(context, "M-01", "VIEW");
        return org.locations(context.dataScope()).stream()
                .filter(l -> OFFICE_ORDER_TYPES.contains(String.valueOf(l.getType())))
                .sorted((a, b) -> a.getCode().compareTo(b.getCode()))
                .map(l -> new Office(l.getCode(), l.displayLabel()))
                .toList();
    }

    /** SRS-1-76: every CPF / EPS Member ID of the member, newest first; the old one is kept. */
    @Transactional(readOnly = true)
    public List<MemberIdRow> memberIds(AccessContext context, String eis) {
        long id = readable(context, eis).getId();
        return named.query(
                "SELECT id_type, identifier_value, category_code, zone_id, effective_from,"
                        + " effective_to, id_status, supersede_reason FROM cpf_member_id_history"
                        + " WHERE employee_id = :id AND id_type IN ('CPF', 'EPS') ORDER BY"
                        + " effective_from, member_id_hist_id",
                new MapSqlParameterSource("id", id),
                (rs, i) ->
                        new MemberIdRow(
                                rs.getString("id_type"),
                                rs.getString("identifier_value"),
                                rs.getString("category_code"),
                                zone(rs.getObject("zone_id", Long.class)),
                                HrmsChangeService.date(rs.getObject("effective_from")),
                                HrmsChangeService.date(rs.getObject("effective_to")),
                                rs.getString("id_status"),
                                rs.getString("supersede_reason")));
    }

    /**
     * SRS-16-7: the staff numbers of the person, PAN-anchored: the original allotment, then each
     * reissue with the number's end date; an old number stays a valid key.
     */
    @Transactional(readOnly = true)
    public List<StaffNumberRow> staffNumbers(AccessContext context, String eis) {
        Employee employee = readable(context, eis);
        List<StaffNumberRow> changed =
                named.query(
                        "SELECT old_emp_num, new_emp_num, from_location_id, to_location_id,"
                                + " change_date, change_event FROM employee_number_history WHERE"
                                + " employee_id = :id ORDER BY change_date, emp_num_hist_id",
                        new MapSqlParameterSource("id", employee.getId()),
                        (rs, i) ->
                                new StaffNumberRow(
                                        rs.getString("old_emp_num"),
                                        rs.getString("new_emp_num"),
                                        location(rs.getObject("from_location_id", Long.class)),
                                        location(rs.getObject("to_location_id", Long.class)),
                                        HrmsChangeService.date(rs.getObject("change_date")),
                                        rs.getString("change_event")));
        List<StaffNumberRow> rows = new ArrayList<>();
        rows.add(
                new StaffNumberRow(
                        null,
                        changed.isEmpty() ? employee.getEmpNum() : changed.get(0).oldNumber(),
                        null,
                        changed.isEmpty()
                                ? location(employee.getLocationId())
                                : changed.get(0).origin(),
                        employee.getJoiningDate(),
                        "ORIGINAL"));
        rows.addAll(changed);
        return rows;
    }

    /**
     * The fields of Record Detail outside the record view and the live HRMS read (SRS-1-70, owner
     * 2026-11-06). The HRMS call is outside any transaction: a slow HRMS holds no connection.
     */
    public RecordExtras recordExtras(AccessContext context, String eis) {
        Employee employee = readable(context, eis);
        Map<String, Object> row =
                named.queryForMap(
                        "SELECT g.grade_name, b.band_code, m.bank_code, e.legacy_emp_num,"
                                + " e.record_origin, (SELECT MAX(h.old_basic_amount) FROM"
                                + " employee_basic_history h WHERE h.employee_id = e.employee_id"
                                + " AND h.effective_to IS NULL) AS old_basic FROM employee e LEFT"
                                + " JOIN org_grade g ON g.grade_id = e.grade_id LEFT JOIN"
                                + " pay_scale_band b ON b.band_id = e.band_id LEFT JOIN bank_branch"
                                + " r ON r.branch_id = e.bank_branch_id LEFT JOIN bank_master m ON"
                                + " m.bank_id = r.bank_id WHERE e.employee_id = :id",
                        new MapSqlParameterSource("id", employee.getId()));
        HrmsRecords reader = hrms.getIfAvailable();
        HrmsRecords.Snapshot live =
                reader == null ? HrmsRecords.UNAVAILABLE : reader.snapshot(employee.getEmpNum());
        return new RecordExtras(
                (String) value(row, "grade_name"),
                (String) value(row, "band_code"),
                (BigDecimal) value(row, "old_basic"),
                (String) value(row, "bank_code"),
                (String) value(row, "legacy_emp_num"),
                (String) value(row, "record_origin"),
                live.available(),
                live.basic(),
                live.status());
    }

    /** SRS-G-30: an order stored with the member's record (Establishment / HR, in scope). */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context, String eis, HrmsChangeService.OrderCopy copy) {
        Employee employee = find(eis);
        policy.requireEmployeeRecord(
                context, "M-01", "FLAG", employee.getId(), employee.getLocationId());
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        EMPLOYEE_ORDERS,
                        employee.getEmpNum(),
                        copy.documentType(),
                        copy.reference(),
                        copy.orderDate(),
                        copy.issuingAuthority(),
                        copy.fileName(),
                        copy.content(),
                        true));
    }

    /** The orders stored with the record, oldest first. */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, String eis) {
        return documents.active(EMPLOYEE_ORDERS, readable(context, eis).getEmpNum());
    }

    /** One stored order; a file of another record reads as not found. */
    @Transactional
    public DocumentStore.Content order(
            AccessContext context, String sourceIp, String eis, long fileId) {
        return documents.read(
                context.loginId(),
                sourceIp,
                EMPLOYEE_ORDERS,
                readable(context, eis).getEmpNum(),
                fileId);
    }

    /** M-01 Export: the list as filtered, page by page; PAN and bank stay masked (SRS-1-56). */
    public void exportMaster(
            AccessContext context,
            String text,
            String pattern,
            String status,
            String office,
            ExportSink sink)
            throws IOException {
        sink.header(
                "eis",
                "name",
                "pattern",
                "pay_scale",
                "basic",
                "city",
                "cpf_code",
                "bank",
                "status");
        int page = 0;
        Paged<EmployeeService.EmployeeRow> rows;
        do {
            rows = employees.list(context, text, pattern, status, office, new PageSpec(page, 500));
            for (EmployeeService.EmployeeRow r : rows.items()) {
                sink.row(
                        r.eis(),
                        r.name(),
                        nz(r.payPattern()),
                        nz(r.payScale()),
                        nz(r.basic()),
                        nz(r.city()),
                        nz(r.cpfCode()),
                        nz(r.bank()),
                        r.status());
            }
            page++;
        } while ((long) page * 500 < rows.total());
    }

    // ---------------------------------------------------------------- I-17

    private static final String EVENTS_FROM =
            " FROM employee_lifecycle_event v JOIN employee e ON e.employee_id = v.employee_id"
                    + " LEFT JOIN hrms_change_task t ON t.hrms_change_task_id ="
                    + " v.hrms_change_task_id WHERE e.location_id IN (:scope) AND (:report = 'N'"
                    + " OR v.event_type IN ('SUSPENSION', 'REVOCATION'))";

    /** SRS-1-63: the events received from HRMS, newest effective date first. */
    @Transactional(readOnly = true)
    public Paged<ReceivedEvent> events(AccessContext context, boolean report, PageSpec spec) {
        policy.require(context, "I-17", "VIEW");
        MapSqlParameterSource params = eventParams(context, report);
        Long total = named.queryForObject("SELECT COUNT(*)" + EVENTS_FROM, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        return new Paged<>(
                named.query(
                        EVENTS_SELECT
                                + EVENTS_FROM
                                + " ORDER BY v.event_date DESC, v.event_id DESC OFFSET :offset"
                                + " ROWS FETCH NEXT :size ROWS ONLY",
                        params,
                        (rs, i) -> event(rs)),
                spec.page(),
                spec.size(),
                RowCounts.of(total));
    }

    /** I-17 Export and the Suspension / Revocation Report (SRS l.3869). */
    @Transactional(readOnly = true)
    public void exportEvents(AccessContext context, boolean report, ExportSink sink)
            throws IOException {
        policy.require(context, "I-17", "EXPORT");
        sink.header(
                "event",
                "eis",
                "member",
                "effective",
                "end",
                "details",
                "source",
                "i21_task",
                "status");
        for (ReceivedEvent e :
                named.query(
                        EVENTS_SELECT
                                + EVENTS_FROM
                                + " ORDER BY v.event_date DESC, v.event_id DESC",
                        eventParams(context, report),
                        (rs, i) -> event(rs))) {
            sink.row(
                    e.eventType(),
                    nz(e.eis()),
                    nz(e.employee()),
                    e.effective(),
                    nz(e.endDate()),
                    nz(e.reason()),
                    nz(e.source()),
                    nz(e.taskNo()),
                    nz(e.taskStatus()));
        }
    }

    private static final String EVENTS_SELECT =
            "SELECT v.event_id, v.event_type, e.emp_num, e.first_name, e.last_name, v.event_date,"
                    + " v.end_date, v.subsistence_percent, v.release_from_yymm, v.release_to_yymm,"
                    + " v.intervening_period_treatment, v.reason, v.event_source,"
                    + " v.hrms_request_id, v.order_reference, t.hrms_change_task_id, t.task_no,"
                    + " t.task_status";

    private MapSqlParameterSource eventParams(AccessContext context, boolean report) {
        return new MapSqlParameterSource()
                .addValue("scope", scope(context))
                .addValue("report", report ? "Y" : "N", Types.VARCHAR);
    }

    private static ReceivedEvent event(ResultSet rs) throws SQLException {
        return new ReceivedEvent(
                rs.getLong("event_id"),
                rs.getString("event_type"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                HrmsChangeService.date(rs.getObject("event_date")),
                HrmsChangeService.date(rs.getObject("end_date")),
                rs.getBigDecimal("subsistence_percent"),
                rs.getString("release_from_yymm"),
                rs.getString("release_to_yymm"),
                rs.getString("intervening_period_treatment"),
                rs.getString("reason"),
                rs.getString("event_source"),
                rs.getString("hrms_request_id"),
                rs.getString("order_reference"),
                rs.getObject("hrms_change_task_id", Long.class),
                rs.getString("task_no"),
                rs.getString("task_status"));
    }

    // ---------------------------------------------------------------- M-11

    private static final String TRANSFERS_FROM =
            " FROM employee_transfer x JOIN employee e ON e.employee_id = x.employee_id LEFT JOIN"
                    + " hrms_change_task t ON t.hrms_change_task_id = x.hrms_change_task_id LEFT"
                    + " JOIN approval_queue q ON q.approval_id = x.approval_id WHERE"
                    + " (x.from_location_id IN (:scope) OR x.to_location_id IN (:scope))";

    private static final String TRANSFERS_SELECT =
            "SELECT x.transfer_id, e.pan_number, e.emp_num, e.first_name, e.last_name, e.cpf_code,"
                    + " x.from_location_id, x.to_location_id, x.transfer_date,"
                    + " t.hrms_change_task_id, t.task_no, t.task_status, q.status AS approval,"
                    + " (SELECT MAX(h.old_emp_num) FROM employee_number_history h WHERE"
                    + " h.employee_id = x.employee_id AND h.change_date = x.transfer_date) AS"
                    + " old_no, (SELECT MAX(h.new_emp_num) FROM employee_number_history h WHERE"
                    + " h.employee_id = x.employee_id AND h.change_date = x.transfer_date) AS"
                    + " new_no";

    /** M-11 Employee Transfer Register: PAN is the anchor; the CPF Member ID never changes. */
    @Transactional(readOnly = true)
    public Paged<TransferRow> transfers(AccessContext context, PageSpec spec) {
        policy.require(context, "M-11", "VIEW");
        MapSqlParameterSource params = new MapSqlParameterSource("scope", scope(context));
        Long total = named.queryForObject("SELECT COUNT(*)" + TRANSFERS_FROM, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        return new Paged<>(
                named.query(
                        TRANSFERS_SELECT
                                + TRANSFERS_FROM
                                + " ORDER BY x.transfer_date DESC, x.transfer_id DESC OFFSET"
                                + " :offset ROWS FETCH NEXT :size ROWS ONLY",
                        params,
                        (rs, i) -> transfer(rs)),
                spec.page(),
                spec.size(),
                RowCounts.of(total));
    }

    /** M-11 Export: the register as listed, PAN masked. */
    @Transactional(readOnly = true)
    public void exportTransfers(AccessContext context, ExportSink sink) throws IOException {
        policy.require(context, "M-11", "EXPORT");
        sink.header(
                "pan",
                "old_staff_no",
                "new_staff_no",
                "origin",
                "destination",
                "cpf_member_id",
                "effective_date",
                "i21_task",
                "status");
        for (TransferRow r :
                named.query(
                        TRANSFERS_SELECT
                                + TRANSFERS_FROM
                                + " ORDER BY x.transfer_date DESC, x.transfer_id DESC",
                        new MapSqlParameterSource("scope", scope(context)),
                        (rs, i) -> transfer(rs))) {
            sink.row(
                    nz(r.panMasked()),
                    nz(r.oldStaffNo()),
                    nz(r.newStaffNo()),
                    nz(r.origin()),
                    nz(r.destination()),
                    nz(r.cpfMemberId()),
                    r.effectiveDate(),
                    nz(r.taskNo()),
                    nz(r.taskStatus()));
        }
    }

    /**
     * The M-11 tiles: the month's transfers, the numbers reissued and the tasks with the Checker.
     */
    @Transactional(readOnly = true)
    public TransferSummary transferSummary(AccessContext context) {
        policy.require(context, "M-11", "VIEW");
        YearMonth month = YearMonth.now(clock.withZone(IST));
        Map<String, Object> row =
                named.queryForMap(
                        "SELECT COUNT(CASE WHEN x.transfer_date BETWEEN :from AND :to THEN 1 END)"
                                + " AS received, COUNT(CASE WHEN x.transfer_date BETWEEN :from AND"
                                + " :to AND EXISTS (SELECT 1 FROM employee_number_history h WHERE"
                                + " h.employee_id = x.employee_id AND h.change_date ="
                                + " x.transfer_date) THEN 1 END) AS reissued, COUNT(CASE WHEN"
                                + " t.task_status = 'PENDING_CHECKER' THEN 1 END) AS with_checker"
                                + TRANSFERS_FROM,
                        new MapSqlParameterSource()
                                .addValue("scope", scope(context))
                                .addValue("from", java.sql.Date.valueOf(month.atDay(1)))
                                .addValue("to", java.sql.Date.valueOf(month.atEndOfMonth())));
        return new TransferSummary(
                month.toString(),
                number(value(row, "received")),
                number(value(row, "reissued")),
                number(value(row, "with_checker")));
    }

    private TransferRow transfer(ResultSet rs) throws SQLException {
        String old = rs.getString("old_no");
        return new TransferRow(
                rs.getLong("transfer_id"),
                PiiMasks.pan(rs.getString("pan_number")),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                old == null ? rs.getString("emp_num") : old,
                rs.getString("new_no"),
                location(rs.getObject("from_location_id", Long.class)),
                location(rs.getObject("to_location_id", Long.class)),
                rs.getString("cpf_code"),
                HrmsChangeService.date(rs.getObject("transfer_date")),
                rs.getObject("hrms_change_task_id", Long.class),
                rs.getString("task_no"),
                rs.getString("task_status"),
                rs.getString("approval"));
    }

    // ---------------------------------------------------------------- I-21

    /**
     * The I-21 tiles over the scope: tasks waiting, split by the freeze (BR-INT-002), those with
     * the Checker, beyond the ageing setting (SRS-1-68), those holding the month-end lock (received
     * before the freeze, SRS-1-69) and the changes applied without a task (SRS-1-19).
     */
    @Transactional(readOnly = true)
    public TaskSummary taskSummary(AccessContext context) {
        Long unit = changes.scopeUnit(context, "VIEW");
        MapSqlParameterSource params = changes.params(unit, HrmsChangeService.Filter.none());
        OptionalLong ageing = changes.ageing();
        // SRS-1-68: overdue when strictly more whole days than the setting have passed.
        Instant cutoff = clock.instant().minus(java.time.Duration.ofDays(ageing.orElse(0L) + 1L));
        params.addValue("cutoff", Timestamp.from(cutoff));
        String waiting =
                "t.task_status IN ('PENDING_ORDER_DETAILS', 'PENDING_CHECKER',"
                        + " 'RETURNED_TO_MAKER')";
        Map<String, Object> row =
                named.queryForMap(
                        "SELECT COUNT(CASE WHEN "
                                + waiting
                                + " THEN 1 END) AS pending, COUNT(CASE WHEN "
                                + waiting
                                + " AND t.received_before_freeze = 'Y' THEN 1 END) AS before_f,"
                                + " COUNT(CASE WHEN t.task_status = 'PENDING_CHECKER' THEN 1 END)"
                                + " AS checker, COUNT(CASE WHEN "
                                + waiting
                                + " AND t.received_at < :cutoff THEN 1 END) AS aged, COUNT(CASE"
                                + " WHEN t.task_status = 'AUTO_APPLIED' THEN 1 END) AS auto"
                                + HrmsChangeService.FROM
                                + HrmsChangeService.WHERE,
                        params);
        long pending = number(value(row, "pending"));
        long before = number(value(row, "before_f"));
        return new TaskSummary(
                pending,
                before,
                pending - before,
                number(value(row, "checker")),
                ageing.isPresent() ? number(value(row, "aged")) : 0,
                before,
                number(value(row, "auto")));
    }

    private static final String REGISTER_TASKS =
            "SELECT 'TASK' AS kind, t.task_no AS reference, t.received_at AS received, e.emp_num,"
                    + " e.first_name, e.last_name, f.field_code, f.field_label, t.current_value,"
                    + " t.new_value, t.hrms_request_id, t.effective_date, COALESCE(t.order_no,"
                    + " o.order_no) AS order_ref, t.maker_user, t.checker_user, t.task_status AS"
                    + " status FROM hrms_change_task t JOIN employee e ON e.employee_id ="
                    + " t.employee_id JOIN hrms_pay_effect_field f ON f.pay_effect_field_id ="
                    + " t.pay_effect_field_id LEFT JOIN hrms_change_task_order o ON"
                    + " o.task_order_id = t.task_order_id WHERE (:all = 'Y' OR t.paying_unit_id"
                    + " IN (:units)) AND (:q IS NULL OR LOWER(e.emp_num || ' ' || e.first_name ||"
                    + " ' ' || COALESCE(e.last_name, '') || ' ' || f.field_label || ' ' ||"
                    + " COALESCE(t.hrms_request_id, '')) LIKE :q ESCAPE '\\')";

    private static final String REGISTER_REQUESTS =
            " UNION ALL SELECT 'REQUEST', r.request_no, r.raised_at, e.emp_num, e.first_name,"
                    + " e.last_name, f.field_code, f.field_label, NULL, NULL, r.hrms_reference,"
                    + " r.claimed_effective_date, NULL, r.raised_by, NULL, r.request_status FROM"
                    + " hrms_update_request r JOIN employee e ON e.employee_id = r.employee_id JOIN"
                    + " hrms_pay_effect_field f ON f.pay_effect_field_id = r.pay_effect_field_id"
                    + " WHERE (:all = 'Y' OR e.location_id IN (:units)) AND (:q IS NULL OR"
                    + " LOWER(e.emp_num || ' ' || e.first_name || ' ' || COALESCE(e.last_name, '')"
                    + " || ' ' || f.field_label || ' ' || COALESCE(r.hrms_reference, '')) LIKE :q"
                    + " ESCAPE '\\')";

    /**
     * SRS-1-71, SRS-17-15: the HRMS Change Register as drawn on its tab: every change received and
     * every HRMS Update Request, newest first; restricted values masked (SRS-1-56).
     */
    @Transactional(readOnly = true)
    public Paged<RegisterRow> register(AccessContext context, String text, PageSpec spec) {
        Long unit = changes.scopeUnit(context, "VIEW");
        Set<Long> units = unit == null ? Set.of(-1L) : org.subtree(unit);
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("all", unit == null ? "Y" : "N", Types.VARCHAR)
                        .addValue("units", units.isEmpty() ? Set.of(-1L) : units)
                        .addValue("q", contains(text), Types.VARCHAR);
        String union = REGISTER_TASKS + REGISTER_REQUESTS;
        Long total =
                named.queryForObject("SELECT COUNT(*) FROM (" + union + ") r", params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        Instant now = clock.instant();
        return new Paged<>(
                named.query(
                        "SELECT * FROM ("
                                + union
                                + ") r ORDER BY r.received DESC, r.reference DESC OFFSET :offset"
                                + " ROWS FETCH NEXT :size ROWS ONLY",
                        params,
                        (rs, i) -> registerRow(rs, now)),
                spec.page(),
                spec.size(),
                RowCounts.of(total));
    }

    private static RegisterRow registerRow(ResultSet rs, Instant now) throws SQLException {
        String field = rs.getString("field_code");
        String kind = rs.getString("kind");
        String status = rs.getString("status");
        Timestamp at = rs.getTimestamp("received");
        Instant received = at == null ? null : at.toInstant();
        boolean task = "TASK".equals(kind);
        boolean waiting =
                task
                        && received != null
                        && HrmsChangeRules.PENDING.contains(HrmsChangeRules.Status.valueOf(status));
        return new RegisterRow(
                kind,
                rs.getString("reference"),
                received,
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("field_label"),
                HrmsChangeRules.mask(field, rs.getString("current_value")),
                HrmsChangeRules.mask(field, rs.getString("new_value")),
                rs.getString("hrms_request_id"),
                HrmsChangeService.date(rs.getObject("effective_date")),
                rs.getString("order_ref"),
                rs.getString("maker_user"),
                rs.getString("checker_user"),
                status,
                waiting ? java.time.Duration.between(received, now).toDays() : null);
    }

    /** SRS-1-28: the fields with pay effect in force, by group, as FRS 11.11 lists them. */
    @Transactional(readOnly = true)
    public List<FieldGroup> fieldGroups(AccessContext context) {
        changes.scopeUnit(context, "VIEW");
        Map<String, FieldGroup> groups = new LinkedHashMap<>();
        named.query(
                "SELECT field_group, field_label, pay_effect_note FROM hrms_pay_effect_field WHERE"
                        + " effective_to IS NULL ORDER BY CASE field_group WHEN"
                        + " 'IDENTITY_STATUTORY' THEN 1 WHEN 'SERVICE_PAY' THEN 2 WHEN"
                        + " 'PLACE_OF_POSTING' THEN 3 WHEN 'LEAVE' THEN 4 WHEN 'DISCIPLINE' THEN 5"
                        + " WHEN 'CLAIMS_LOANS' THEN 6 WHEN 'EXIT' THEN 7 ELSE 8 END, field_label",
                rs -> {
                    FieldGroup group =
                            groups.computeIfAbsent(
                                    rs.getString("field_group"),
                                    g -> new FieldGroup(g, new ArrayList<>(), new ArrayList<>()));
                    group.fields().add(rs.getString("field_label"));
                    String note = rs.getString("pay_effect_note");
                    if (note != null && !group.payEffects().contains(note)) {
                        group.payEffects().add(note);
                    }
                });
        return List.copyOf(groups.values());
    }

    /** SRS-1-21 to 1-23: the DOB tasks with the retirement date each would give, newest first. */
    @Transactional(readOnly = true)
    public Paged<DobTask> dobTasks(AccessContext context, PageSpec spec) {
        Paged<HrmsChangeService.TaskView> page =
                changes.page(
                        context,
                        new HrmsChangeService.Filter(null, "DATE_OF_BIRTH", null, false),
                        spec);
        return new Paged<>(
                page.items().stream().map(v -> dobTask(context, v)).toList(),
                page.page(),
                page.size(),
                page.total());
    }

    private DobTask dobTask(AccessContext context, HrmsChangeService.TaskView v) {
        HrmsChangeService.DobPreview dob = changes.detail(context, v.id()).dob();
        return new DobTask(
                v.id(),
                v.taskNo(),
                v.eis(),
                v.employee(),
                v.currentValue(),
                v.newValue(),
                dob == null ? null : dob.newRetirement(),
                dob != null && dob.retirementPast(),
                dob != null && dob.retiresSoon(),
                v.status(),
                v.approvalId(),
                v.rejectionReason());
    }

    /** SRS-1-67: the bulk orders of the scope, newest first, with their ready and held tasks. */
    @Transactional(readOnly = true)
    public Paged<BulkOrder> orders(AccessContext context, PageSpec spec) {
        Long unit = changes.scopeUnit(context, "VIEW");
        MapSqlParameterSource params = changes.params(unit, HrmsChangeService.Filter.none());
        String scoped =
                " FROM hrms_change_task_order o WHERE EXISTS (SELECT 1 FROM hrms_change_task t"
                        + " WHERE t.task_order_id = o.task_order_id AND (:all = 'Y' OR"
                        + " t.paying_unit_id IN (:units)))";
        Long total = named.queryForObject("SELECT COUNT(*)" + scoped, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        return new Paged<>(
                named.query(
                        "SELECT o.task_order_id, o.order_ref, o.order_no, o.order_date,"
                                + " o.issuing_authority, o.order_subject, o.task_count,"
                                + " o.order_status, o.approval_id, (SELECT COUNT(*) FROM"
                                + " hrms_change_task t WHERE t.task_order_id = o.task_order_id AND"
                                + " t.task_status = 'PENDING_CHECKER') AS ready"
                                + scoped
                                + " ORDER BY o.entered_at DESC, o.task_order_id DESC OFFSET"
                                + " :offset ROWS FETCH NEXT :size ROWS ONLY",
                        params,
                        (rs, i) -> {
                            long ready = rs.getLong("ready");
                            int count = rs.getInt("task_count");
                            return new BulkOrder(
                                    rs.getLong("task_order_id"),
                                    rs.getString("order_ref"),
                                    rs.getString("order_no"),
                                    HrmsChangeService.date(rs.getObject("order_date")),
                                    rs.getString("issuing_authority"),
                                    rs.getString("order_subject"),
                                    count,
                                    rs.getString("order_status"),
                                    rs.getObject("approval_id", Long.class),
                                    ready,
                                    Math.max(0, count - ready));
                        }),
                spec.page(),
                spec.size(),
                RowCounts.of(total));
    }

    /** SRS-1-67: the tasks under an order, in the scope; restricted values masked. */
    @Transactional(readOnly = true)
    public Paged<OrderTask> orderTasks(AccessContext context, long orderId, PageSpec spec) {
        Long unit = changes.scopeUnit(context, "VIEW");
        MapSqlParameterSource params =
                changes.params(unit, HrmsChangeService.Filter.none()).addValue("order", orderId);
        String from =
                " FROM hrms_change_task t JOIN employee e ON e.employee_id = t.employee_id JOIN"
                        + " hrms_pay_effect_field f ON f.pay_effect_field_id ="
                        + " t.pay_effect_field_id WHERE t.task_order_id = :order AND (:all = 'Y'"
                        + " OR t.paying_unit_id IN (:units))";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        return new Paged<>(
                named.query(
                        "SELECT t.hrms_change_task_id, t.task_no, e.emp_num, e.first_name,"
                                + " e.last_name, t.current_value, t.new_value, t.task_status,"
                                + " f.field_code"
                                + from
                                + " ORDER BY t.task_no OFFSET :offset ROWS FETCH NEXT :size ROWS"
                                + " ONLY",
                        params,
                        (rs, i) -> {
                            String field = rs.getString("field_code");
                            return new OrderTask(
                                    rs.getLong("hrms_change_task_id"),
                                    rs.getString("task_no"),
                                    rs.getString("emp_num"),
                                    name(rs.getString("first_name"), rs.getString("last_name")),
                                    HrmsChangeRules.mask(field, rs.getString("current_value")),
                                    HrmsChangeRules.mask(field, rs.getString("new_value")),
                                    rs.getString("task_status"),
                                    field);
                        }),
                spec.page(),
                spec.size(),
                RowCounts.of(total));
    }

    // ---------------------------------------------------------------- helpers

    /** The record, after the M-01 read check of {@link EmployeeService#get} (previous unit too). */
    private Employee readable(AccessContext context, String eis) {
        employees.get(context, eis);
        return find(eis);
    }

    private Employee find(String eis) {
        return repository
                .findByEmpNum(eis == null ? "" : eis.strip())
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private static Set<Long> scope(AccessContext context) {
        return context.dataScope().isEmpty() ? Set.of(-1L) : context.dataScope();
    }

    private String location(Long id) {
        return id == null ? null : org.location(id).map(OrgLocation::displayLabel).orElse(null);
    }

    private String zone(Long id) {
        return id == null ? null : org.zone(id).map(OrgZone::getName).orElse(null);
    }

    /** A column of a queryForMap row: the key's case differs between H2 and Oracle. */
    private static Object value(Map<String, Object> row, String column) {
        Object upper = row.get(column.toUpperCase(Locale.ROOT));
        return upper != null ? upper : row.get(column);
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String contains(String text) {
        return text == null || text.isBlank()
                ? null
                : "%" + Likes.escape(text.strip().toLowerCase(Locale.ROOT)) + "%";
    }

    private static String name(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    private static Object nz(Object value) {
        return value == null ? "" : value;
    }
}
