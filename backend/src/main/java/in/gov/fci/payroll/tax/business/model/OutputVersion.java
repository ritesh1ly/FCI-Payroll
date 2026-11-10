package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code report_definition_version}: one approved, effective-dated layout of a statutory output
 * (target DDL v4.0 Section 24; SRS M-17, BR-TAX-013). Written only on the Rule Approver's approval;
 * a later version ends the one before the day before it starts, and earlier periods regenerate
 * under the version effective for them. Never edited or deleted.
 */
@Entity
@Table(name = "report_definition_version")
public class OutputVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_version_id")
    private Long reportVersionId;

    @Column(name = "report_def_id", nullable = false)
    private Long reportDefId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "layout_format", length = 30)
    private String layoutFormat;

    @Lob
    @Column(name = "layout_definition")
    private String layoutDefinition;

    @Column(name = "rounding_rule_key", length = 100)
    private String roundingRuleKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "period_lock_required", nullable = false, length = 1)
    private String periodLockRequired;

    @Column(name = "output_store_mode", nullable = false, length = 10)
    private String outputStoreMode;

    @Column(name = "approval_state", nullable = false, length = 20)
    private String approvalState;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "is_blocked", nullable = false, length = 1)
    private String blocked;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Column(name = "supersedes_version_id")
    private Long supersedesVersionId;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutputVersion() {}

    /** A version as the Rule Approver approved it. */
    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public static OutputVersion approved(
            Long definitionId,
            int number,
            LocalDate from,
            String format,
            String layout,
            String roundingRuleKey,
            boolean periodLock,
            Long supersedes,
            Long approval,
            String by,
            Instant at) {
        OutputVersion v = new OutputVersion();
        v.reportDefId = definitionId;
        v.versionNo = number;
        v.effectiveFrom = from;
        v.layoutFormat = format;
        v.layoutDefinition = layout;
        v.roundingRuleKey = roundingRuleKey;
        v.periodLockRequired = periodLock ? "Y" : "N";
        v.outputStoreMode = "STORE";
        v.approvalState = "APPROVED";
        v.blocked = "N";
        v.supersedesVersionId = supersedes;
        v.approvalId = approval;
        v.createdBy = by;
        v.createdAt = at;
        return v;
    }

    /** A later version replaces this one from {@code successorFrom}. */
    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    /**
     * Approved, not blocked and in force on {@code day}: the version an output is generated under.
     */
    public boolean governs(LocalDate day) {
        return "APPROVED".equals(approvalState)
                && !"Y".equals(blocked)
                && !effectiveFrom.isAfter(day)
                && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    public Long getReportVersionId() {
        return reportVersionId;
    }

    public Long getReportDefId() {
        return reportDefId;
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

    public String getLayoutFormat() {
        return layoutFormat;
    }

    public String getRoundingRuleKey() {
        return roundingRuleKey;
    }

    public boolean isPeriodLockRequired() {
        return "Y".equals(periodLockRequired);
    }

    public boolean isBlocked() {
        return "Y".equals(blocked);
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public String getApprovalState() {
        return approvalState;
    }
}
