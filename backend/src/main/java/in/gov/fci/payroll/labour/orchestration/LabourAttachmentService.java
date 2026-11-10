package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.TransferRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Temporary attachment of labour to a gang on M-09 (plan 20 phase 20e; SRS-2-47; D20-13, D20-15).
 * Establishment / HR attaches a handling or ancillary labour to another gang of the same payroll
 * type for a date range, or moves it permanently (no end date), from today or a later date; the
 * Payroll Checker approves (route M09_ATTACH). Attachments never overlap (DDL {@code
 * trg_labour_temp_attach_overlap}). Approved, the labour's membership of its gang ends the day
 * before, the attached gang's membership covers the attached days - so the labour appears in that
 * gang's attendance and work-slips and counts in its strength - and, after a temporary attachment,
 * the membership of the home gang resumes (D20-15).
 */
@Service
@Loggable
public class LabourAttachmentService {

    static final String SCREEN = "M-09";
    static final String ENTITY = "labour_temp_attachment";
    static final String ROUTE = "M09_ATTACH";

    /** An attachment as entered. */
    public record Request(
            Long toGangId,
            LocalDate from,
            LocalDate to,
            String reason,
            String orderReference,
            LocalDate orderDate,
            String issuingAuthority) {}

    private final JdbcTemplate jdbc;
    private final LabourGangs gangs;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final Clock clock;

    public LabourAttachmentService(
            JdbcTemplate jdbc,
            LabourGangs gangs,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            SystemParameters parameters,
            AuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.gangs = gangs;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.parameters = parameters;
        this.audit = audit;
        this.clock = clock;
    }

    /** M-09: an attachment of the labour for the Payroll Checker (SRS-2-47). */
    @Transactional
    public SubmittedApproval propose(AccessContext context, long employeeId, Request r) {
        if (r == null || r.toGangId() == null) {
            throw invalid("ENTRY");
        }
        if (r.reason() == null || r.reason().isBlank() || r.reason().strip().length() > 500) {
            throw invalid("REASON");
        }
        TransferRules.attachmentDefect(r.from(), r.to(), parameters.today())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        Map<String, Object> member = membership(employeeId, r.from());
        LabourGangs.Gang home = gangs.gang(number(member.get("GANG_ID")));
        policy.requireRecordAt(context, SCREEN, "EDIT", officeOf(home.depotId()).getId());
        String role = (String) member.get("GANG_ROLE");
        if (!"HANDLING".equals(role) && !"ANCILLARY".equals(role)) {
            throw invalid("LEADER");
        }
        LabourGangs.Gang to = gangs.gang(r.toGangId());
        policy.requireRecordAt(context, SCREEN, "EDIT", officeOf(to.depotId()).getId());
        if (to.id() == home.id()
                || !to.activeOn(r.from())
                || (r.to() != null && !to.activeOn(r.to()))
                || !to.payrollType().equals(home.payrollType())) {
            throw invalid("TO_GANG");
        }
        // The membership must run unchanged through the attachment: no later change is planned.
        Object endsAt = member.get("EFFECTIVE_TO");
        if (endsAt != null
                && (r.to() == null
                        || new java.sql.Date(((java.util.Date) endsAt).getTime())
                                .toLocalDate()
                                .isBefore(r.to()))) {
            throw invalid("MEMBERSHIP_ENDS");
        }
        Integer later =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang_membership WHERE employee_id = ? AND"
                                + " effective_from > ?",
                        Integer.class,
                        employeeId,
                        Date.valueOf(r.from()));
        if (later != null && later > 0) {
            throw invalid("LATER_CHANGE");
        }
        // SRS-2-47: one gang a day; the DDL trigger is the last guard, this is the clear refusal.
        Integer overlapping =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_temp_attachment WHERE employee_id = ? AND"
                            + " attachment_status IN ('PENDING_APPROVAL', 'APPROVED') AND from_date"
                            + " <= COALESCE(CAST(? AS DATE), DATE '9999-12-31') AND"
                            + " COALESCE(to_date, DATE '9999-12-31') >= ?",
                        Integer.class,
                        employeeId,
                        r.to() == null ? null : Date.valueOf(r.to()),
                        Date.valueOf(r.from()));
        if (overlapping != null && overlapping > 0) {
            throw invalid("OVERLAP");
        }
        Long sequence =
                jdbc.queryForObject("SELECT COUNT(*) FROM labour_temp_attachment", Long.class);
        String ref =
                TransferRules.attachmentRef(
                        r.from().getYear(), (sequence == null ? 0 : sequence) + 1);
        jdbc.update(
                "INSERT INTO labour_temp_attachment (attachment_ref, employee_id, labour_category,"
                    + " from_gang_id, to_gang_id, from_date, to_date, charge_location_id, reason,"
                    + " order_reference, order_date, issuing_authority, attachment_status,"
                    + " created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " 'PENDING_APPROVAL', ?, ?)",
                ref,
                employeeId,
                role,
                home.id(),
                to.id(),
                Date.valueOf(r.from()),
                r.to() == null ? null : Date.valueOf(r.to()),
                to.depotId(),
                r.reason().strip(),
                blank(r.orderReference()),
                r.orderDate() == null ? null : Date.valueOf(r.orderDate()),
                blank(r.issuingAuthority()),
                context.loginId(),
                Timestamp.from(clock.instant()));
        Long id =
                jdbc.queryForObject(
                        "SELECT temp_attachment_id FROM labour_temp_attachment WHERE attachment_ref"
                                + " = ?",
                        Long.class,
                        ref);
        if (id == null) {
            throw new IllegalStateException("The attachment just written is not found: " + ref);
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "EDIT",
                                ENTITY,
                                String.valueOf(id),
                                ActionType.CREATE,
                                officeOf(home.depotId()).getId(),
                                null,
                                r.from(),
                                r.reason().strip()),
                        null);
        jdbc.update(
                "UPDATE labour_temp_attachment SET approval_id = ? WHERE temp_attachment_id = ?",
                item.id(),
                id);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                ref,
                                "attachment_status",
                                null,
                                "PENDING_APPROVAL",
                                home.code() + " to " + to.code())
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(r.from()));
        return item;
    }

    /** The Payroll Checker's decision: approved, the memberships follow the attachment. */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        long id = Long.parseLong(decision.entityKey());
        Map<String, Object> a =
                jdbc.queryForMap(
                        "SELECT attachment_ref, employee_id, labour_category, from_gang_id,"
                            + " to_gang_id, from_date, to_date FROM labour_temp_attachment WHERE"
                            + " temp_attachment_id = ?",
                        id);
        String outcome = decision.approved() ? "APPROVED" : "REJECTED";
        jdbc.update(
                "UPDATE labour_temp_attachment SET attachment_status = ? WHERE temp_attachment_id ="
                        + " ?",
                outcome,
                id);
        if (decision.approved()) {
            long employee = number(a.get("EMPLOYEE_ID"));
            LocalDate from = date(a.get("FROM_DATE"));
            LocalDate to = a.get("TO_DATE") == null ? null : date(a.get("TO_DATE"));
            Object role = a.get("LABOUR_CATEGORY");
            jdbc.update(
                    "UPDATE labour_gang_membership SET effective_to = ?, end_reason = 'ATTACHMENT'"
                            + " WHERE employee_id = ? AND gang_id = ? AND effective_from < ? AND"
                            + " (effective_to IS NULL OR effective_to >= ?)",
                    Date.valueOf(from.minusDays(1)),
                    employee,
                    a.get("FROM_GANG_ID"),
                    Date.valueOf(from),
                    Date.valueOf(from));
            insert(a.get("TO_GANG_ID"), employee, role, from, to, decision.approvalId());
            if (to != null) {
                insert(
                        a.get("FROM_GANG_ID"),
                        employee,
                        role,
                        to.plusDays(1),
                        null,
                        decision.approvalId());
            }
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                (String) a.get("ATTACHMENT_REF"),
                                "attachment_status",
                                "PENDING_APPROVAL",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    private void insert(
            Object gangId,
            long employee,
            Object role,
            LocalDate from,
            LocalDate to,
            Long approval) {
        jdbc.update(
                "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                        + " effective_from, effective_to, approval_id) VALUES (?, ?, ?, ?, ?, ?)",
                gangId,
                employee,
                role,
                Date.valueOf(from),
                to == null ? null : Date.valueOf(to),
                approval);
    }

    private Map<String, Object> membership(long employeeId, LocalDate date) {
        return jdbc
                .queryForList(
                        "SELECT m.gang_id, m.gang_role, m.effective_to FROM labour_gang_membership"
                            + " m JOIN labour_profile p ON p.employee_id = m.employee_id WHERE"
                            + " m.employee_id = ? AND p.labour_status = 'ACTIVE' AND"
                            + " m.effective_from <= ? AND (m.effective_to IS NULL OR m.effective_to"
                            + " >= ?)",
                        employeeId,
                        Date.valueOf(date),
                        Date.valueOf(date))
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private OrgLocation officeOf(long depotId) {
        return org.cycleUnitOf(depotId).orElseThrow(() -> invalid("DEPOT_OFFICE"));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static LocalDate date(Object value) {
        return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.TRANSFER, new Object[] {defect}, List.of(), null);
    }
}
