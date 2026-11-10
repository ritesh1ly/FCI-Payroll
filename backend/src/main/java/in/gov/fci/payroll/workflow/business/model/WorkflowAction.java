package in.gov.fci.payroll.workflow.business.model;

import in.gov.fci.payroll.platform.security.OtpGatedAction;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code approval_workflow_action} (target DDL v4.0 S05, X-01 Approval Workflow Master): one
 * approval action at one office level, effective-dated and versioned, with two or three levels
 * ({@link WorkflowLevel}). An item completes under the version it was submitted with ({@code
 * approval_queue.workflow_action_id}). Rows, not code: the SRS v0.5 module approval-authority table
 * (Dynamic Rule &amp; Parameter Engine: Headquarters only, closed to Region-level Maker and
 * Checker) and SRS-15-6 (no Region acts on behalf of another) are data here.
 */
@Entity
@Table(name = "approval_workflow_action")
public class WorkflowAction {

    /** {@code office_level} of an action or a level. */
    public enum OfficeLevel {
        HQ,
        ZO,
        RO,
        DO,
        /** The item's own paying unit: nobody approves for another unit. */
        PAYING_UNIT,
        OWN_CYCLE_INSTANCE
    }

    /** {@code action_status}. */
    public enum Status {
        DRAFT,
        PENDING_APPROVAL,
        ACTIVE,
        SUPERSEDED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "workflow_action_id")
    private Long id;

    @Column(name = "action_code", nullable = false, length = 40)
    private String actionCode;

    @Column(name = "action_name", nullable = false, length = 200)
    private String actionName;

    @Column(name = "module_code", nullable = false, length = 40)
    private String moduleCode;

    @Column(name = "screen_code", length = 20)
    private String screenCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "office_level", nullable = false, length = 20)
    private OfficeLevel officeLevel;

    @Column(name = "level_count", nullable = false)
    private Integer levelCount;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "otp_required", nullable = false, length = 1)
    private Boolean otpRequired;

    /** SRS-G-13, UC-07 A2: the Checker may approve items of this action together. */
    @Convert(converter = YesNoConverter.class)
    @Column(name = "bulk_approval_allowed", nullable = false, length = 1)
    private Boolean bulkApprovalAllowed = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "otp_action_code", length = 40)
    private OtpGatedAction otpAction;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "attachment_required", nullable = false, length = 1)
    private Boolean attachmentRequired;

    @Column(name = "target_tat_hours")
    private Integer targetTatHours;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_status", nullable = false, length = 20)
    private Status status;

    /** The approval of a version proposed on A-08 (null for seeded versions). */
    @Column(name = "approval_id")
    private Long approvalId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected WorkflowAction() {}

    /**
     * A proposed version (SRS-22-12, A-08): PENDING_APPROVAL until a Payroll Checker decides it;
     * the router keeps using the version in force until then. Validated by {@code WorkflowRules}.
     */
    public static WorkflowAction propose(
            String actionCode,
            String actionName,
            String moduleCode,
            String screenCode,
            OfficeLevel officeLevel,
            int levelCount,
            OtpGatedAction otpAction,
            boolean attachmentRequired,
            Integer targetTatHours,
            int versionNo,
            LocalDate effectiveFrom) {
        WorkflowAction a = new WorkflowAction();
        a.actionCode = actionCode;
        a.actionName = actionName;
        a.moduleCode = moduleCode;
        a.screenCode = screenCode;
        a.officeLevel = officeLevel;
        a.levelCount = levelCount;
        a.otpRequired = otpAction != null;
        a.otpAction = otpAction;
        a.attachmentRequired = attachmentRequired;
        a.targetTatHours = targetTatHours;
        a.versionNo = versionNo;
        a.effectiveFrom = effectiveFrom;
        a.status = Status.PENDING_APPROVAL;
        return a;
    }

    /** The checker approved: in force from {@code from} (today or the proposed later date). */
    public void approve(LocalDate from) {
        if (status != Status.PENDING_APPROVAL) {
            throw new IllegalStateException("not pending: " + status);
        }
        effectiveFrom = from;
        status = Status.ACTIVE;
    }

    /** The checker rejected or returned the proposal: it never comes into force. */
    public void cancel() {
        if (status == Status.PENDING_APPROVAL) {
            status = Status.CANCELLED;
        }
    }

    /**
     * A newer version starts on {@code from}: this one ends the day before, and is SUPERSEDED once
     * that day has passed; until then it stays in force. A version the successor replaces on or
     * before its own first day never comes into force: it is SUPERSEDED at once (as SRS-19-11
     * reports a version replaced by an approved successor) and keeps its dates, because an end
     * before the start is no date range ({@code chk_appr_wf_span}).
     *
     * @return whether the end date moved (false for a version replaced before it started)
     */
    public boolean endBefore(LocalDate from, LocalDate today) {
        if (!from.isAfter(effectiveFrom)) {
            status = Status.SUPERSEDED;
            return false;
        }
        effectiveTo = from.minusDays(1);
        if (effectiveTo.isBefore(today)) {
            status = Status.SUPERSEDED;
        }
        return true;
    }

    public void linkApproval(Long approvalId) {
        this.approvalId = approvalId;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    /** In force on {@code day}: ACTIVE and within its dates. */
    public boolean isActiveOn(LocalDate day) {
        return status == Status.ACTIVE
                && !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** Maintained at Headquarters: the maker must sign in at HQ. */
    public boolean isHeadquartersOnly() {
        return officeLevel == OfficeLevel.HQ;
    }

    public boolean isOtpRequired() {
        return Boolean.TRUE.equals(otpRequired);
    }

    public boolean isBulkApprovalAllowed() {
        return Boolean.TRUE.equals(bulkApprovalAllowed);
    }

    /** SRS-G-13: X-01 sets whether this action's items may be approved together (UC-07 A2). */
    public WorkflowAction allowBulkApproval(boolean allowed) {
        this.bulkApprovalAllowed = allowed;
        return this;
    }

    public Long getId() {
        return id;
    }

    public String getActionCode() {
        return actionCode;
    }

    public String getActionName() {
        return actionName;
    }

    public String getModuleCode() {
        return moduleCode;
    }

    public String getScreenCode() {
        return screenCode;
    }

    public OfficeLevel getOfficeLevel() {
        return officeLevel;
    }

    public int getLevelCount() {
        return levelCount;
    }

    /** The gated action of the maker's OTP (null when no OTP is required). */
    public OtpGatedAction getOtpAction() {
        return otpAction;
    }

    public boolean isAttachmentRequired() {
        return Boolean.TRUE.equals(attachmentRequired);
    }

    public Integer getTargetTatHours() {
        return targetTatHours;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public Status getStatus() {
        return status;
    }
}
