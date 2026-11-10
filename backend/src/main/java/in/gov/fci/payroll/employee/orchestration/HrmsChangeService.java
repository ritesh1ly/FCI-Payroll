package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.employee.business.HrmsChangeRules;
import in.gov.fci.payroll.employee.business.HrmsChangeRules.Status;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalWithdrawals;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-21 HRMS Change Approval (plan 16 phase 16a; SRS-1-19, SRS-1-21 to 24, SRS-1-28, SRS-1-29,
 * SRS-1-58, SRS-1-61, SRS-G-45; DDL {@code hrms_change_task}).
 *
 * <ul>
 *   <li>A change enters only through {@link #receive} (the HRMS interface, the X-07 reconcile; plan
 *       D16-2), idempotently on HRMS Request ID and field. A field without pay effect is applied at
 *       once (AUTO_APPLIED, audited); any other opens a task with the value locked, and the task
 *       shows it pending (PENDING 16-record-status).
 *   <li>The Payroll Maker records the order details and sends the task to the Checker; the route
 *       comes from the field's registry flags (OTP for Basic Pay / increment date; HQ Establishment
 *       for a DOB whose new retirement date is past). The value is never editable.
 *   <li>The decision is applied by {@link HrmsChangeEffect}.
 * </ul>
 */
@Service
@Loggable
public class HrmsChangeService implements HrmsChanges {

    /** Approval entity of a task (key: {@code hrms_change_task_id}). */
    public static final String ENTITY = "hrms_change_task";

    static final String SCREEN = "I-21";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Filters of the task register (each optional); {@code nameDob} keeps the name and DOB
     * corrections only (the Name / DOB Change Register, SRS-1-30).
     */
    public record Filter(
            String status, String fieldCode, String eis, boolean nameDob, String text) {

        /** The filters without the drawn search. */
        public Filter(String status, String fieldCode, String eis, boolean nameDob) {
            this(status, fieldCode, eis, nameDob, null);
        }

        /** No filter: the whole scope. */
        public static Filter none() {
            return new Filter(null, null, null, false, null);
        }
    }

    /** One task as I-21 lists it; Restricted values masked (SRS-1-56). */
    public record TaskView(
            long id,
            String taskNo,
            String eis,
            String employee,
            String fieldCode,
            String fieldLabel,
            String fieldGroup,
            String currentValue,
            String newValue,
            LocalDate effectiveDate,
            String hrmsRequestId,
            String source,
            String status,
            String targetPeriod,
            boolean receivedBeforeFreeze,
            Instant receivedAt,
            long daysPending,
            boolean otpRequired,
            String orderNo,
            LocalDate orderDate,
            String issuingAuthority,
            boolean orderHeldInHrms,
            String makerUser,
            String checkerUser,
            String returnReason,
            String rejectionReason,
            Long approvalId,
            String orderRef,
            boolean overdue,
            // I-21 Reason for Change (SRS l.3850): as HRMS sent it, read-only.
            String hrmsReason) {}

    /**
     * SRS-1-22: the DOB effects shown before approval; the dates are null while RETIREMENT_AGE is
     * not configured.
     */
    public record DobPreview(
            LocalDate currentBirthDate,
            LocalDate newBirthDate,
            LocalDate currentRetirement,
            LocalDate newRetirement,
            LocalDate epsCutoff,
            boolean retirementPast,
            boolean retiresSoon,
            boolean retirementAgeConfigured) {}

    /**
     * The pay impact preview of a task by month (UC I-21 l.3813, US-GAP-276), as "YYYY-MM": the
     * month the value applies from, the first month paid at it, and the months whose arrear or
     * recovery is raised through Arrears (null when none). The amounts are worked out by the
     * engines when the approved value is applied (PENDING 16f-rest).
     */
    public record PayImpact(
            String appliesFrom, String paidFrom, String arrearFrom, String arrearTo) {}

    /**
     * A task with its DOB preview (DOB tasks only).
     *
     * @param effect what approving would do, from the field's owner (US-GAP-58), or null
     * @param impact the pay impact preview of every task with pay effect; null for a change applied
     *     at once without pay effect (SRS-1-19) or without an effective date
     */
    public record Detail(
            TaskView task, DobPreview dob, boolean applicable, String effect, PayImpact impact) {}

    /** What the Maker records (SRS-1-28, SRS-G-31). */
    public record OrderDetails(
            String orderNo, LocalDate orderDate, String issuingAuthority, boolean heldInHrms) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final SystemParameters parameters;
    private final PayrollPeriods periods;
    private final PayrollRecords payroll;
    private final HrmsChangeApplier applier;
    private final Clock clock;
    private final ApprovalWithdrawals withdrawals;
    private final DocumentStore documents;

    /** The attachment entity of a task's order copy (key: the task id; SRS-G-31). */
    public static final String TASK_DOCUMENTS = "hrms_change_task";

    /** An order copy's particulars and file (SRS-G-30). */
    public record OrderCopy(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    public HrmsChangeService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            OrgDirectory org,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            SystemParameters parameters,
            PayrollPeriods periods,
            PayrollRecords payroll,
            HrmsChangeApplier applier,
            Clock clock,
            DocumentStore documents,
            ApprovalWithdrawals withdrawals) {
        this.documents = documents;
        this.withdrawals = withdrawals;
        this.jdbc = jdbc;
        this.named = named;
        this.org = org;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.parameters = parameters;
        this.periods = periods;
        this.payroll = payroll;
        this.applier = applier;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- intake

    /** X-03: the tasks not yet decided (order details, Checker, HQ Establishment, returned). */
    @Override
    @Transactional(readOnly = true)
    public Awaiting awaiting() {
        return jdbc.queryForObject(
                "SELECT COUNT(*), MIN(received_at) FROM hrms_change_task WHERE task_status IN"
                    + " ('PENDING_ORDER_DETAILS', 'PENDING_CHECKER', 'PENDING_HQ_ESTABLISHMENT',"
                    + " 'RETURNED_TO_MAKER')",
                (rs, i) -> {
                    java.sql.Timestamp oldest = rs.getTimestamp(2);
                    return new Awaiting(rs.getLong(1), oldest == null ? null : oldest.toInstant());
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Open> open(long employeeId, String fieldCode) {
        return jdbc.query(
                "SELECT t.hrms_change_task_id, t.task_no, t.task_status, t.new_value FROM"
                        + " hrms_change_task t JOIN hrms_pay_effect_field f ON"
                        + " f.pay_effect_field_id = t.pay_effect_field_id WHERE t.employee_id = ?"
                        + " AND f.field_code = ? AND t.task_status IN ('PENDING_ORDER_DETAILS',"
                        + " 'PENDING_CHECKER', 'PENDING_HQ_ESTABLISHMENT', 'RETURNED_TO_MAKER')"
                        + " ORDER BY t.received_at, t.hrms_change_task_id",
                (rs, i) ->
                        new Open(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                employeeId,
                fieldCode);
    }

    /**
     * Wave C review (SRS-9-23, SRS-1-28): a waiting task a newer HRMS record replaces is closed as
     * SUPERSEDED (R-DEL: never deleted), audited with its reason (R-AUD).
     */
    @Override
    @Transactional
    public boolean supersede(long taskId, Long supersededByTaskId, String actor, String reason) {
        Map<String, Object> row = taskRow(taskId);
        String before = (String) row.get("TASK_STATUS");
        int updated =
                jdbc.update(
                        "UPDATE hrms_change_task SET task_status = 'SUPERSEDED',"
                                + " superseded_by_task_id = ?, row_version = row_version + 1 WHERE"
                                + " hrms_change_task_id = ? AND task_status IN"
                                + " ('PENDING_ORDER_DETAILS', 'PENDING_CHECKER',"
                                + " 'PENDING_HQ_ESTABLISHMENT', 'RETURNED_TO_MAKER')",
                        supersededByTaskId,
                        taskId);
        if (updated == 0) {
            return false;
        }
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY,
                                (String) row.get("TASK_NO"),
                                "task_status",
                                before,
                                Status.SUPERSEDED.name(),
                                reason)
                        .module(SCREEN));
        // Wave D review 9: the superseded task leaves the Approvals Inbox in the same transaction,
        // so no intermediate level can still approve it onward.
        withdrawals.withdraw(ENTITY, String.valueOf(taskId), actor, reason);
        return true;
    }

    @Override
    @Transactional
    public Received receive(Change change) {
        Map<String, Object> field = field(change.fieldCode());
        long fieldId = ((Number) field.get("PAY_EFFECT_FIELD_ID")).longValue();
        // Plan 09 phase 09c: a value another module applies is checked by it before a task opens.
        applier.effectFor(change.fieldCode())
                .ifPresent(
                        owner -> {
                            try {
                                owner.check(
                                        change.fieldCode(), change.employeeId(), change.newValue());
                            } catch (FciException refused) {
                                throw refused;
                            } catch (RuntimeException bad) {
                                throw new FciException(
                                        ApiErrorCode.I21_VALUE,
                                        new Object[] {change.fieldCode()},
                                        List.of(),
                                        bad);
                            }
                        });
        if (change.hrmsRequestId() != null) {
            List<Map<String, Object>> existing =
                    jdbc.queryForList(
                            "SELECT hrms_change_task_id, task_no, task_status FROM hrms_change_task"
                                    + " WHERE hrms_request_id = ? AND pay_effect_field_id = ?",
                            change.hrmsRequestId(),
                            fieldId);
            if (!existing.isEmpty()) {
                Map<String, Object> e = existing.get(0);
                return new Received(
                        ((Number) e.get("HRMS_CHANGE_TASK_ID")).longValue(),
                        (String) e.get("TASK_NO"),
                        (String) e.get("TASK_STATUS"),
                        true);
            }
        }
        Map<String, Object> employee =
                jdbc
                        .queryForList(
                                "SELECT employee_id, emp_num, location_id FROM employee WHERE"
                                        + " employee_id = ?",
                                change.employeeId())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        long locationId = ((Number) employee.get("LOCATION_ID")).longValue();
        OrgLocation unit =
                org.cycleUnitOf(locationId)
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
        boolean auto = "Y".equals(field.get("AUTO_APPLY"));
        String current = applier.current(field, change.employeeId());
        YearMonth month = periods.current();
        HrmsChangeRules.Timing timing =
                HrmsChangeRules.timing(
                        month,
                        payroll.cycle(unit.getId(), yymm(month))
                                .map(PayrollRecords.Cycle::cycleStatus)
                                .orElse(null));
        Status status = auto ? Status.AUTO_APPLIED : Status.PENDING_ORDER_DETAILS;
        Instant now = clock.instant();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO hrms_change_task (task_no, employee_id,"
                                            + " pay_effect_field_id, task_source, hrms_request_id,"
                                            + " current_value, new_value, effective_date,"
                                            + " received_at, paying_unit_id, target_payroll_yymm,"
                                            + " received_before_freeze, task_status, otp_required,"
                                            + " applied_at, hrms_change_reason) VALUES (?, ?, ?, ?,"
                                            + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    new String[] {"hrms_change_task_id"});
                    // task_no is unique: a per-insert placeholder until the id gives the number
                    ps.setString(
                            1, "TMP-" + java.util.UUID.randomUUID().toString().substring(0, 26));
                    ps.setLong(2, change.employeeId());
                    ps.setLong(3, fieldId);
                    ps.setString(4, change.source().name());
                    ps.setString(5, change.hrmsRequestId());
                    ps.setString(6, current);
                    ps.setString(7, change.newValue());
                    ps.setObject(8, change.effectiveDate());
                    ps.setTimestamp(9, Timestamp.from(now));
                    ps.setLong(10, unit.getId());
                    ps.setString(11, yymm(timing.target()));
                    ps.setString(12, timing.beforeFreeze() ? "Y" : "N");
                    ps.setString(13, status.name());
                    ps.setString(14, (String) field.get("OTP_REQUIRED"));
                    if (auto) {
                        ps.setTimestamp(15, Timestamp.from(now));
                    } else {
                        ps.setNull(15, Types.TIMESTAMP);
                    }
                    // SRS l.3850: HRMS's reason, held as received (printable ASCII, 500 at most).
                    ps.setString(16, HrmsChangeRules.reason(change.hrmsReason()));
                    return ps;
                },
                key);
        long taskId = in.gov.fci.payroll.platform.persistence.GeneratedKeys.id(key);
        String taskNo = HrmsChangeRules.taskNo(LocalDate.ofInstant(now, IST).getYear(), taskId);
        jdbc.update(
                "UPDATE hrms_change_task SET task_no = ? WHERE hrms_change_task_id = ?",
                taskNo,
                taskId);
        if (auto) {
            // SRS-1-19: no pay effect, no task to decide: applied from the effective date, audited.
            applier.apply(field, change.employeeId(), change.newValue(), "system.hrms", null);
        }
        // OPEN-QUESTION: PENDING 16-record-status - SRS-1-28 sets the M-01 Record Status to "HRMS
        // Change
        // Pending"; employee.record_status predates DDL v4.0's value, so the task alone shows it
        // for now.
        audit.record(
                "system.hrms",
                AuditService.Entry.change(
                                ENTITY,
                                taskNo,
                                (String) field.get("FIELD_CODE"),
                                HrmsChangeRules.mask(change.fieldCode(), current),
                                HrmsChangeRules.mask(change.fieldCode(), change.newValue()),
                                change.source() + " " + nz(change.hrmsRequestId()))
                        .effective(change.effectiveDate())
                        .module(SCREEN));
        return new Received(taskId, taskNo, status.name(), false);
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Paged<TaskView> page(AccessContext context, Filter filter, PageSpec spec) {
        Long scope = scopeUnit(context, "VIEW");
        MapSqlParameterSource params = params(scope, filter);
        OptionalLong ageing = ageing();
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        List<TaskView> rows =
                named.query(
                        SELECT
                                + FROM
                                + WHERE
                                + " ORDER BY t.received_at DESC, t.hrms_change_task_id DESC OFFSET"
                                + " :offset ROWS FETCH NEXT :size ROWS ONLY",
                        params,
                        (rs, i) -> view(rs, ageing));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    @Transactional(readOnly = true)
    public Detail detail(AccessContext context, long id) {
        TaskView task = task(context, id, "VIEW");
        Map<String, Object> field = field(task.fieldCode());
        DobPreview dob = "DATE_OF_BIRTH".equals(task.fieldCode()) ? dobPreview(id) : null;
        Map<String, Object> raw = taskRow(id);
        String effect =
                applier.effectFor(task.fieldCode())
                        .flatMap(
                                owner ->
                                        owner.preview(
                                                task.fieldCode(),
                                                ((Number) raw.get("EMPLOYEE_ID")).longValue(),
                                                (String) raw.get("NEW_VALUE")))
                        .orElse(null);
        return new Detail(
                task,
                dob,
                applier.applicable(field),
                effect,
                impact(task, applier.effectFor(task.fieldCode())));
    }

    /**
     * The pay impact the task shows: none when the field's owner shows none (Wave C review: the NPS
     * record of one month, SRS-9-23), else {@link #impact(TaskView)}.
     */
    static PayImpact impact(
            TaskView task,
            java.util.Optional<in.gov.fci.payroll.employee.api.HrmsTaskEffects> owner) {
        return owner.map(o -> o.showsPayImpact(task.fieldCode())).orElse(true)
                ? impact(task)
                : null;
    }

    /** US-GAP-276: the task's pay impact by month (SRS-1-28, SRS-1-58). */
    static PayImpact impact(TaskView task) {
        if (task.effectiveDate() == null
                || task.targetPeriod() == null
                || Status.AUTO_APPLIED.name().equals(task.status())) {
            return null;
        }
        HrmsChangeRules.PayImpact i =
                HrmsChangeRules.payImpact(
                        task.effectiveDate(), YearMonth.parse(task.targetPeriod()));
        return new PayImpact(
                i.appliesFrom().toString(),
                i.paidFrom().toString(),
                i.arrearFrom() == null ? null : i.arrearFrom().toString(),
                i.arrearTo() == null ? null : i.arrearTo().toString());
    }

    // ---------------------------------------------------------------- the Maker

    /**
     * SRS-1-28: records the order details and sends the task to the Checker (or, for a DOB whose
     * new retirement date is past, to the Checker and then HQ Establishment).
     */
    @Transactional
    public long submit(AccessContext context, long id, OrderDetails order) {
        Map<String, Object> row = taskRow(id);
        Status status = Status.valueOf((String) row.get("TASK_STATUS"));
        long unitId = ((Number) row.get("PAYING_UNIT_ID")).longValue();
        Map<String, Object> field =
                fieldById(((Number) row.get("PAY_EFFECT_FIELD_ID")).longValue());
        boolean dob = "DATE_OF_BIRTH".equals(field.get("FIELD_CODE"));
        boolean retirementPast = dob && dobPreview(id).retirementPast();
        String routeName = routeName(id, field);
        policy.requireAt(context, SCREEN, routeName, unitId);
        if (!HrmsChangeRules.submittable(status)) {
            throw new FciException(
                    ApiErrorCode.I21_STATE, new Object[] {status.name()}, List.of(), null);
        }
        if (!applier.applicable(field)) {
            throw new FciException(
                    ApiErrorCode.I21_NOT_APPLICABLE,
                    new Object[] {field.get("FIELD_CODE")},
                    List.of(),
                    null);
        }
        if (dob && !dobPreview(id).retirementAgeConfigured()) {
            throw new FciException(
                    ApiErrorCode.I21_NOT_CONFIGURED,
                    new Object[] {SystemParameters.Code.RETIREMENT_AGE.name()},
                    List.of(),
                    null);
        }
        applier.requireUnique(
                field,
                ((Number) row.get("EMPLOYEE_ID")).longValue(),
                (String) row.get("NEW_VALUE"));
        HrmsChangeRules.orderDefect(
                        order.orderNo(),
                        order.orderDate(),
                        order.issuingAuthority(),
                        order.heldInHrms(),
                        LocalDate.now(clock.withZone(IST)))
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.I21_ORDER, new Object[] {d}, List.of(), null);
                        });
        String me = login(context);
        String taskNo = (String) row.get("TASK_NO");
        // SRS-1-28 / SRS-G-31: the order copy is attached, unless HRMS holds the order letter (its
        // reference shown, the letter not uploaded again) or the task is under one bulk order.
        if (!order.heldInHrms()
                && row.get("TASK_ORDER_ID") == null
                && documents.countActive(TASK_DOCUMENTS, String.valueOf(id)) == 0) {
            audit.submissionBlocked(me, ENTITY, taskNo, SCREEN, "order copy");
            throw new FciException(
                    ApiErrorCode.I21_ORDER, new Object[] {"COPY_MISSING"}, List.of(), null);
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                routeName,
                                ENTITY,
                                String.valueOf(id),
                                ActionType.UPDATE,
                                unitId,
                                "{\"taskId\":" + id + ",\"taskNo\":\"" + taskNo + "\"}",
                                date(row.get("EFFECTIVE_DATE")),
                                "HRMS change " + taskNo + " (" + field.get("FIELD_CODE") + ")"),
                        (OtpProof) null);
        jdbc.update(
                "UPDATE hrms_change_task SET task_status = 'PENDING_CHECKER', order_no = ?,"
                        + " order_date = ?, issuing_authority = ?, order_held_in_hrms = ?,"
                        + " maker_user = ?, maker_submitted_at = ?, approval_id = ?,"
                        + " retirement_date_past = ?, return_reason = NULL, row_version ="
                        + " row_version + 1 WHERE hrms_change_task_id = ?",
                order.heldInHrms() ? null : order.orderNo().strip(),
                order.heldInHrms() ? null : order.orderDate(),
                order.heldInHrms() ? null : order.issuingAuthority().strip(),
                order.heldInHrms() ? "Y" : "N",
                me,
                Timestamp.from(clock.instant()),
                item.id(),
                retirementPast ? "Y" : "N",
                id);
        audit.record(
                me,
                AuditService.Entry.change(
                                ENTITY,
                                taskNo,
                                "task_status",
                                status.name(),
                                "PENDING_CHECKER",
                                order.heldInHrms()
                                        ? "Order held in HRMS"
                                        : "Order " + order.orderNo().strip())
                        .approval(item.id())
                        .module(SCREEN));
        return item.id();
    }

    /**
     * SRS-1-61 / SRS-1-23: the Maker's route of the task. Plan 09 phase 09c: a field another module
     * owns may name its own route (a loan foreclosure: I21_SUBMIT_LOAN_FORECLOSURE, OTP
     * LOAN_FORECLOSURE, SRS-12-21).
     */
    private String routeName(long id, Map<String, Object> field) {
        boolean otp = "Y".equals(field.get("OTP_REQUIRED"));
        boolean dob = "DATE_OF_BIRTH".equals(field.get("FIELD_CODE"));
        boolean retirementPast = dob && dobPreview(id).retirementPast();
        HrmsChangeRules.Route route =
                HrmsChangeRules.route(
                        otp, "Y".equals(field.get("HQ_ESTAB_IF_RETIRE_PAST")), retirementPast);
        return applier.effectFor((String) field.get("FIELD_CODE"))
                .flatMap(owner -> owner.route((String) field.get("FIELD_CODE")))
                .orElse(route.name());
    }

    // ---------------------------------------------------------------- the order copy

    /**
     * SRS-1-28 / SRS-1-21 / SRS-G-31 (US-GAP-5): the Payroll Maker attaches the order copy to the
     * task, while it waits for its order details or after it was submitted (then only added, never
     * replaced or deleted). Personal data: shown only to the desk and the Checker of the unit.
     */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context, long id, OrderCopy copy) {
        Map<String, Object> row = taskRow(id);
        Status status = Status.valueOf((String) row.get("TASK_STATUS"));
        long unitId = ((Number) row.get("PAYING_UNIT_ID")).longValue();
        Map<String, Object> field =
                fieldById(((Number) row.get("PAY_EFFECT_FIELD_ID")).longValue());
        policy.requireAt(context, SCREEN, routeName(id, field), unitId);
        if (!HrmsChangeRules.submittable(status) && status != Status.PENDING_CHECKER) {
            throw new FciException(
                    ApiErrorCode.I21_STATE, new Object[] {status.name()}, List.of(), null);
        }
        return documents.attach(
                login(context),
                new DocumentStore.NewAttachment(
                        TASK_DOCUMENTS,
                        String.valueOf(id),
                        copy.documentType(),
                        copy.reference(),
                        copy.orderDate(),
                        copy.issuingAuthority(),
                        copy.fileName(),
                        copy.content(),
                        true));
    }

    /** The order copies of the task (the desk and the Checker of its paying unit). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orderCopies(AccessContext context, long id) {
        authoriseView(context, id);
        return documents.active(TASK_DOCUMENTS, String.valueOf(id));
    }

    /** One order copy of the task; a file of another task reads as not found. */
    @Transactional
    public DocumentStore.Content orderCopy(
            AccessContext context, String sourceIp, long id, long attachmentId) {
        authoriseView(context, id);
        return documents.read(
                login(context), sourceIp, TASK_DOCUMENTS, String.valueOf(id), attachmentId);
    }

    private void authoriseView(AccessContext context, long id) {
        Map<String, Object> row = taskRow(id);
        policy.requireRecordAt(
                context, SCREEN, "VIEW", ((Number) row.get("PAYING_UNIT_ID")).longValue());
    }

    // ---------------------------------------------------------------- helpers

    /** SRS-1-22: the DOB task's dates now and after approval. */
    DobPreview dobPreview(long id) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT t.new_value, e.birth_date, e.retirement_date FROM hrms_change_task"
                                + " t JOIN employee e ON e.employee_id = t.employee_id WHERE"
                                + " t.hrms_change_task_id = ?",
                        id);
        LocalDate current = date(row.get("BIRTH_DATE"));
        LocalDate next = LocalDate.parse((String) row.get("NEW_VALUE"));
        OptionalLong retirementAge = parameters.configured(SystemParameters.Code.RETIREMENT_AGE);
        OptionalLong epsAge = parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE);
        LocalDate retirement =
                retirementAge.isPresent()
                        ? HrmsChangeRules.retirementDate(next, (int) retirementAge.getAsLong())
                        : null;
        LocalDate today = LocalDate.now(clock.withZone(IST));
        return new DobPreview(
                current,
                next,
                date(row.get("RETIREMENT_DATE")),
                retirement,
                epsAge.isPresent()
                        ? HrmsChangeRules.epsCutoff(next, (int) epsAge.getAsLong())
                        : null,
                retirement != null && retirement.isBefore(today),
                retirement != null
                        && HrmsChangeRules.retiresSoon(retirement, YearMonth.from(today)),
                retirementAge.isPresent());
    }

    private TaskView task(AccessContext context, long id, String action) {
        Map<String, Object> row = taskRow(id);
        policy.requireAt(context, SCREEN, action, ((Number) row.get("PAYING_UNIT_ID")).longValue());
        return named.query(
                        SELECT + FROM + " WHERE t.hrms_change_task_id = :id",
                        new MapSqlParameterSource("id", id),
                        (rs, i) -> view(rs, ageing()))
                .get(0);
    }

    private Map<String, Object> taskRow(long id) {
        return jdbc
                .queryForList("SELECT * FROM hrms_change_task WHERE hrms_change_task_id = ?", id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /** The field's version in force today (SRS-1-28 registry, effective-dated). */
    Map<String, Object> field(String code) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        return jdbc
                .queryForList(
                        "SELECT * FROM hrms_pay_effect_field WHERE field_code = ? AND"
                            + " effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)"
                            + " ORDER BY version_no DESC",
                        code,
                        java.sql.Date.valueOf(today),
                        java.sql.Date.valueOf(today))
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new FciException(
                                        ApiErrorCode.I21_FIELD,
                                        new Object[] {code},
                                        List.of(),
                                        null));
    }

    private Map<String, Object> fieldById(long id) {
        return jdbc.queryForMap(
                "SELECT * FROM hrms_pay_effect_field WHERE pay_effect_field_id = ?", id);
    }

    /** The cycle unit the user's register shows (null at Headquarters: every unit). */
    Long scopeUnit(AccessContext context, String action) {
        if (context.locationId() == null) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        if (policy.isAtHeadquarters(context)) {
            policy.requireAt(context, SCREEN, action, context.locationId());
            return null;
        }
        OrgLocation unit =
                org.cycleUnitOf(context.locationId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
        policy.requireAt(context, SCREEN, action, unit.getId());
        return unit.getId();
    }

    /**
     * The status shown: a task with the Checker's approval waiting for HQ Establishment (level 3 of
     * route I21_SUBMIT_HQ) is PENDING_HQ_ESTABLISHMENT (SRS-1-22).
     */
    static final String STATUS =
            "CASE WHEN t.task_status = 'PENDING_CHECKER' AND q.current_level = 3 AND q.status ="
                    + " 'PENDING_APPROVAL' THEN 'PENDING_HQ_ESTABLISHMENT' ELSE t.task_status END";

    static final String SELECT =
            "SELECT t.hrms_change_task_id, t.task_no, e.emp_num, e.first_name, e.last_name,"
                    + " f.field_code, f.field_label, f.field_group, t.current_value, t.new_value,"
                    + " t.effective_date, t.hrms_request_id, t.task_source, t.task_status,"
                    + " t.target_payroll_yymm, t.received_before_freeze, t.received_at,"
                    + " t.otp_required, t.order_no, t.order_date, t.issuing_authority,"
                    + " t.order_held_in_hrms, t.maker_user, t.checker_user, t.return_reason,"
                    + " t.rejection_reason, t.approval_id, o.order_ref, t.maker_submitted_at,"
                    + " t.checker_decided_at, t.hq_estab_user, t.applied_at,"
                    + " t.hrms_change_reason, "
                    + STATUS
                    + " AS shown_status";

    static final String FROM =
            " FROM hrms_change_task t JOIN employee e ON e.employee_id = t.employee_id JOIN"
                    + " hrms_pay_effect_field f ON f.pay_effect_field_id = t.pay_effect_field_id"
                    + " LEFT JOIN approval_queue q ON q.approval_id = t.approval_id LEFT JOIN"
                    + " hrms_change_task_order o ON o.task_order_id = t.task_order_id";

    /** Constant filter SQL: an absent filter is a typed null bind. */
    static final String WHERE =
            " WHERE (:all = 'Y' OR t.paying_unit_id IN (:units)) AND (:status IS NULL OR "
                    + STATUS
                    + " = :status) AND (:field IS NULL OR f.field_code = :field) AND (:eis IS NULL"
                    + " OR e.emp_num = :eis) AND (:nameDob = 'N' OR f.field_code IN ('NAME',"
                    + " 'DATE_OF_BIRTH')) AND (:q IS NULL OR LOWER(e.emp_num || ' ' || e.first_name"
                    + " || ' ' || COALESCE(e.last_name, '') || ' ' || f.field_label || ' ' ||"
                    + " COALESCE(t.hrms_request_id, '')) LIKE :q ESCAPE '\\')";

    /**
     * The register filters of the user's cycle unit (null at Headquarters: every unit). UI audit
     * S10: a zone or region office sees the tasks of every paying unit below it (its data scope, as
     * the request part of the register already does), not only those paid at its own unit.
     */
    MapSqlParameterSource params(Long unit, Filter filter) {
        return params(unit == null ? null : org.subtree(unit), filter);
    }

    /** The register filters over {@code units} (null: every unit). */
    static MapSqlParameterSource params(Set<Long> units, Filter filter) {
        return new MapSqlParameterSource()
                .addValue("all", units == null ? "Y" : "N", Types.VARCHAR)
                .addValue("units", units == null || units.isEmpty() ? Set.of(-1L) : units)
                .addValue("status", filter.status(), Types.VARCHAR)
                .addValue("field", filter.fieldCode(), Types.VARCHAR)
                .addValue("eis", filter.eis(), Types.VARCHAR)
                .addValue("nameDob", filter.nameDob() ? "Y" : "N", Types.VARCHAR)
                .addValue("q", searchText(filter.text()), Types.VARCHAR);
    }

    /** The drawn search (employee, field, HRMS Request ID) as a LIKE pattern, or null. */
    private static String searchText(String text) {
        return text == null || text.isBlank()
                ? null
                : "%"
                        + in.gov.fci.payroll.platform.persistence.Likes.escape(
                                text.strip().toLowerCase(java.util.Locale.ROOT))
                        + "%";
    }

    /** SRS-1-68: the ageing days set on A-07, empty while not configured. */
    OptionalLong ageing() {
        return parameters.configured(SystemParameters.Code.I21_AGEING_DAYS);
    }

    TaskView view(ResultSet rs, OptionalLong ageing) throws SQLException {
        String field = rs.getString("field_code");
        Timestamp received = rs.getTimestamp("received_at");
        Instant at = received == null ? null : received.toInstant();
        String status = rs.getString("shown_status");
        boolean pending = HrmsChangeRules.PENDING.contains(Status.valueOf(status));
        long days =
                at == null || !pending
                        ? 0
                        : java.time.Duration.between(at, clock.instant()).toDays();
        String yymm = rs.getString("target_payroll_yymm");
        return new TaskView(
                rs.getLong("hrms_change_task_id"),
                rs.getString("task_no"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                field,
                rs.getString("field_label"),
                rs.getString("field_group"),
                HrmsChangeRules.mask(field, rs.getString("current_value")),
                HrmsChangeRules.mask(field, rs.getString("new_value")),
                date(rs.getObject("effective_date")),
                rs.getString("hrms_request_id"),
                rs.getString("task_source"),
                status,
                yymm.substring(0, 4) + "-" + yymm.substring(4),
                "Y".equals(rs.getString("received_before_freeze")),
                at,
                days,
                "Y".equals(rs.getString("otp_required")),
                rs.getString("order_no"),
                date(rs.getObject("order_date")),
                rs.getString("issuing_authority"),
                "Y".equals(rs.getString("order_held_in_hrms")),
                rs.getString("maker_user"),
                rs.getString("checker_user"),
                rs.getString("return_reason"),
                rs.getString("rejection_reason"),
                rs.getObject("approval_id", Long.class),
                rs.getString("order_ref"),
                HrmsChangeRules.overdue(pending, days, ageing),
                rs.getString("hrms_change_reason"));
    }

    static LocalDate date(Object value) {
        return switch (value) {
            case java.sql.Date d -> d.toLocalDate();
            case Timestamp ts -> ts.toLocalDateTime().toLocalDate();
            case LocalDate d -> d;
            case java.time.LocalDateTime dt -> dt.toLocalDate();
            case null, default -> null;
        };
    }

    private static String yymm(YearMonth month) {
        return String.format("%04d%02d", month.getYear(), month.getMonthValue());
    }

    private static String name(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String login(AccessContext context) {
        String login = context.loginId();
        return login.length() <= 50 ? login : login.substring(0, 50);
    }
}
