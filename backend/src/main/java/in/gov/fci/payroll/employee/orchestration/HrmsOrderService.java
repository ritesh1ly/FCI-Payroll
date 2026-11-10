package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.business.HrmsChangeRules;
import in.gov.fci.payroll.employee.business.HrmsChangeRules.Status;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One order for many staff on I-21 (plan 16 phase 16b; SRS-1-67, SRS-G-31; DDL {@code
 * hrms_change_task_order}): the Payroll Maker enters a transfer order or a promotion list once and
 * links every task it covers; the Payroll Checker approves them all in one action, with one OTP
 * when any of them is an increment-date or Basic Pay change (route I21_ORDER / I21_ORDER_OTP). The
 * tasks must be of one paying unit, waiting for their order, applicable, and not a DOB change whose
 * retirement date is past (that one keeps its own route to HQ Establishment).
 */
@Service
@Loggable
public class HrmsOrderService {

    /** Approval entity of a bulk order (key: {@code task_order_id}). */
    public static final String ENTITY = "hrms_change_task_order";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** What the Maker enters once for the whole order. */
    public record NewOrder(
            String orderNo,
            LocalDate orderDate,
            String issuingAuthority,
            String subject,
            List<Long> taskIds,
            Long copyAttachmentId,
            String copyProvisionalKey) {

        /** An order without its copy (refused at create: SRS-1-67 "details and copy once"). */
        public NewOrder(
                String orderNo,
                LocalDate orderDate,
                String issuingAuthority,
                String subject,
                List<Long> taskIds) {
            this(orderNo, orderDate, issuingAuthority, subject, taskIds, null, null);
        }
    }

    /** A bulk order copy stored before its order (SRS-G-31): its id and provisional key. */
    public record StoredCopy(long attachmentId, String provisionalKey) {}

    /** The order and its approval item. */
    public record Created(long orderId, String orderRef, long approvalId, int tasks) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final HrmsChangeService changes;
    private final HrmsChangeApplier applier;
    private final Clock clock;
    private final DocumentStore documents;

    public HrmsOrderService(
            JdbcTemplate jdbc,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            HrmsChangeService changes,
            HrmsChangeApplier applier,
            Clock clock,
            DocumentStore documents) {
        this.documents = documents;
        this.jdbc = jdbc;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.changes = changes;
        this.applier = applier;
        this.clock = clock;
    }

    /**
     * SRS-1-67 / SRS-G-31: the Maker stores the one copy of an order covering many staff first; the
     * order then names it (the Checker opens it from the Approvals Inbox).
     */
    @Transactional
    public StoredCopy storeCopy(AccessContext context, HrmsChangeService.OrderCopy copy) {
        if (!policy.can(context, HrmsChangeService.SCREEN, "ORDER")
                && !policy.can(context, HrmsChangeService.SCREEN, "ORDER_OTP")) {
            policy.require(context, HrmsChangeService.SCREEN, "ORDER");
        }
        String provisional = DocumentStore.PROVISIONAL + UUID.randomUUID();
        DocumentStore.AttachmentView stored =
                documents.attach(
                        login(context),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                provisional,
                                copy.documentType(),
                                copy.reference(),
                                copy.orderDate(),
                                copy.issuingAuthority(),
                                copy.fileName(),
                                copy.content(),
                                true));
        return new StoredCopy(stored.id(), provisional);
    }

    /** The copy this user stored for the order; none is an audited refusal (SRS-20-27). */
    private DocumentStore.AttachmentView storedCopy(AccessContext context, NewOrder order) {
        String me = login(context);
        DocumentStore.AttachmentView copy =
                order.copyAttachmentId() == null
                                || order.copyProvisionalKey() == null
                                || !order.copyProvisionalKey().startsWith(DocumentStore.PROVISIONAL)
                        ? null
                        : documents.active(ENTITY, order.copyProvisionalKey()).stream()
                                .filter(
                                        a ->
                                                a.id().equals(order.copyAttachmentId())
                                                        && me.equals(a.uploadedBy()))
                                .findFirst()
                                .orElse(null);
        if (copy == null) {
            audit.submissionBlocked(
                    me, ENTITY, order.orderNo(), HrmsChangeService.SCREEN, "bulk order copy");
            throw new FciException(
                    ApiErrorCode.I21_ORDER, new Object[] {"COPY_MISSING"}, List.of(), null);
        }
        return copy;
    }

    @Transactional
    public Created create(AccessContext context, NewOrder order) {
        Set<Long> ids = new LinkedHashSet<>(order.taskIds() == null ? List.of() : order.taskIds());
        if (ids.size() < 2) {
            throw new FciException(
                    ApiErrorCode.I21_ORDER, new Object[] {"FEWER_THAN_TWO_TASKS"}, List.of(), null);
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        HrmsChangeRules.orderDefect(
                        order.orderNo(), order.orderDate(), order.issuingAuthority(), false, today)
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.I21_ORDER, new Object[] {d}, List.of(), null);
                        });
        Long unit = null;
        boolean otp = false;
        List<String> numbers = new ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> task =
                    jdbc
                            .queryForList(
                                    "SELECT t.task_no, t.task_status, t.paying_unit_id,"
                                        + " t.otp_required, t.pay_effect_field_id, f.field_code"
                                        + " FROM hrms_change_task t JOIN hrms_pay_effect_field f ON"
                                        + " f.pay_effect_field_id = t.pay_effect_field_id WHERE"
                                        + " t.hrms_change_task_id = ?",
                                    id)
                            .stream()
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new FciException(
                                                    ApiErrorCode.I21_ORDER,
                                                    new Object[] {"UNKNOWN_TASK " + id},
                                                    List.of(),
                                                    null));
            long taskUnit = ((Number) task.get("PAYING_UNIT_ID")).longValue();
            if (unit != null && unit != taskUnit) {
                throw new FciException(
                        ApiErrorCode.I21_ORDER, new Object[] {"MIXED_UNITS"}, List.of(), null);
            }
            unit = taskUnit;
            Status status = Status.valueOf((String) task.get("TASK_STATUS"));
            if (!HrmsChangeRules.submittable(status)) {
                throw new FciException(
                        ApiErrorCode.I21_STATE,
                        new Object[] {task.get("TASK_NO") + " " + status},
                        List.of(),
                        null);
            }
            Map<String, Object> field =
                    jdbc.queryForMap(
                            "SELECT * FROM hrms_pay_effect_field WHERE pay_effect_field_id = ?",
                            task.get("PAY_EFFECT_FIELD_ID"));
            if (!applier.applicable(field)) {
                throw new FciException(
                        ApiErrorCode.I21_NOT_APPLICABLE,
                        new Object[] {task.get("FIELD_CODE")},
                        List.of(),
                        null);
            }
            if ("DATE_OF_BIRTH".equals(task.get("FIELD_CODE"))
                    && changes.dobPreview(id).retirementPast()) {
                throw new FciException(
                        ApiErrorCode.I21_ORDER,
                        new Object[] {"DOB_NEEDS_HQ " + task.get("TASK_NO")},
                        List.of(),
                        null);
            }
            otp |= "Y".equals(task.get("OTP_REQUIRED"));
            numbers.add((String) task.get("TASK_NO"));
        }
        String action = otp ? "ORDER_OTP" : "ORDER";
        policy.requireAt(context, HrmsChangeService.SCREEN, action, unit);
        String me = login(context);
        // SRS-1-67 / SRS-G-31 (US-GAP-5): the order details and its copy, recorded once.
        DocumentStore.AttachmentView copy = storedCopy(context, order);
        boolean otpRequired = otp;
        String subject =
                order.subject() == null || order.subject().isBlank()
                        ? null
                        : order.subject().strip();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO hrms_change_task_order (order_ref, order_no,"
                                            + " order_date, issuing_authority, order_subject,"
                                            + " task_count, entered_by, entered_at, order_status,"
                                            + " otp_required) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                                            + " 'SUBMITTED', ?)",
                                    new String[] {"task_order_id"});
                    // order_ref is unique: a per-insert placeholder until the id gives the
                    // reference
                    ps.setString(1, "TMP-" + UUID.randomUUID().toString().substring(0, 26));
                    ps.setString(2, order.orderNo().strip());
                    ps.setObject(3, order.orderDate());
                    ps.setString(4, order.issuingAuthority().strip());
                    ps.setString(5, subject);
                    ps.setInt(6, ids.size());
                    ps.setString(7, me);
                    ps.setTimestamp(8, Timestamp.from(clock.instant()));
                    ps.setString(9, otpRequired ? "Y" : "N");
                    return ps;
                },
                key);
        long orderId = GeneratedKeys.id(key);
        String ref = "HCO-" + today.getYear() + "-" + String.format("%04d", orderId);
        jdbc.update(
                "UPDATE hrms_change_task_order SET order_ref = ? WHERE task_order_id = ?",
                ref,
                orderId);
        documents.adopt(me, ENTITY, order.copyProvisionalKey(), copy.id(), String.valueOf(orderId));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                HrmsChangeService.SCREEN,
                                action,
                                ENTITY,
                                String.valueOf(orderId),
                                ActionType.UPDATE,
                                unit,
                                "{\"orderId\":" + orderId + ",\"orderRef\":\"" + ref + "\"}",
                                order.orderDate(),
                                "Order "
                                        + order.orderNo().strip()
                                        + " ("
                                        + ids.size()
                                        + " HRMS changes: "
                                        + String.join(", ", numbers)
                                        + ")"),
                        (OtpProof) null);
        jdbc.update(
                "UPDATE hrms_change_task_order SET approval_id = ? WHERE task_order_id = ?",
                item.id(),
                orderId);
        for (Long id : ids) {
            jdbc.update(
                    "UPDATE hrms_change_task SET task_order_id = ?, task_status ="
                        + " 'PENDING_CHECKER', maker_user = ?, maker_submitted_at = ?, approval_id"
                        + " = ?, return_reason = NULL, row_version = row_version + 1 WHERE"
                        + " hrms_change_task_id = ?",
                    orderId,
                    me,
                    Timestamp.from(clock.instant()),
                    item.id(),
                    id);
        }
        audit.record(
                me,
                AuditService.Entry.change(
                                ENTITY,
                                ref,
                                "order_status",
                                null,
                                "SUBMITTED",
                                "Order "
                                        + order.orderNo().strip()
                                        + ": "
                                        + String.join(", ", numbers))
                        .approval(item.id())
                        .module(HrmsChangeService.SCREEN));
        return new Created(orderId, ref, item.id(), ids.size());
    }

    private static String login(AccessContext context) {
        String login = context.loginId();
        return login.substring(0, Math.min(50, login.length()));
    }
}
