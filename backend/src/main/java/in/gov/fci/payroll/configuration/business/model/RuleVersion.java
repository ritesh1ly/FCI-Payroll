package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.type.YesNoConverter;

/**
 * {@code rule_version} (target DDL v4.0 S06): one effective-dated value of a rule. Only an APPROVED
 * version is ever in force, and at most one per date (BR-RULE-002, trg_rule_version_temporal). The
 * Draft / Pending / Active / Superseded vocabulary is derived as at a date, never stored.
 */
@Entity
@Table(name = "rule_version")
public class RuleVersion {

    /** {@code approval_state}. */
    public enum State {
        DRAFT,
        PENDING_APPROVAL,
        APPROVED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_version_id")
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "scalar_value", precision = 18, scale = 4)
    private BigDecimal scalarValue;

    @Column(name = "formula_expression", length = 4000)
    private String formulaExpression;

    @Column(name = "rounding_rule_key", length = 100)
    private String roundingRuleKey;

    @Column(name = "circular_order_id")
    private Long circularOrderId;

    @Column(name = "notification_reference", length = 200)
    private String notificationReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_state", nullable = false, length = 20)
    private State state;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_blocked", nullable = false, length = 1)
    private Boolean blocked;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RuleVersion() {}

    /** A proposed value from {@code from}; pending until its approval is decided. */
    public static RuleVersion propose(
            Long ruleId,
            int versionNo,
            LocalDate from,
            BigDecimal value,
            String by,
            Long approvalId,
            Instant now) {
        RuleVersion version = new RuleVersion();
        version.ruleId = Objects.requireNonNull(ruleId);
        version.versionNo = versionNo;
        version.effectiveFrom = Objects.requireNonNull(from);
        version.scalarValue = value;
        version.state = State.PENDING_APPROVAL;
        version.blocked = false;
        version.approvalId = approvalId;
        // created_by is VARCHAR2(50).
        version.createdBy = by.substring(0, Math.min(by.length(), 50));
        version.createdAt = now;
        return version;
    }

    /**
     * An M-08 version as the Rule Approver approves it (SRS-19-4, SRS-19-12; plan 06 D-7): the row
     * is written only on approval, from its stated date (also before today: a late order is paid as
     * arrears, SRS-19-16), with an optional last day. Until then the proposal lives in its approval
     * item, so a returned proposal never holds its date (uk_rule_version_eff).
     *
     * @param value the scalar of a Rate, Ceiling or Floor rule, else null
     * @param formula the formula of a Formula, Eligibility or Applicability rule, else null
     * @param roundingRuleKey the rounding setting (a ROUNDING rule), or null
     * @param circularId the Active M-25 circular the version rests on (SRS-19-17)
     * @param proposedBy the Rule Administrator who proposed it
     * @param proposedAt when it was proposed
     */
    public static RuleVersion approved(
            Long ruleId,
            int versionNo,
            LocalDate from,
            LocalDate to,
            BigDecimal value,
            String formula,
            String roundingRuleKey,
            Long circularId,
            String proposedBy,
            Long approvalId,
            Instant proposedAt) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("A version cannot end before it starts");
        }
        RuleVersion version =
                propose(ruleId, versionNo, from, value, proposedBy, approvalId, proposedAt);
        version.effectiveTo = to;
        version.formulaExpression = formula;
        version.roundingRuleKey = roundingRuleKey;
        version.circularOrderId = Objects.requireNonNull(circularId);
        version.state = State.APPROVED;
        return version;
    }

    /**
     * Ends an approved version on {@code last} with no successor (SRS-19-6: an explicit end date).
     */
    public void endOn(LocalDate last) {
        if (state != State.APPROVED || last.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("Cannot end on " + last);
        }
        effectiveTo = last;
    }

    /** In force on {@code day}: approved and within its dates. */
    public boolean isInForceOn(LocalDate day) {
        return state == State.APPROVED
                && !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** Approved from {@code from} (the decision date, or the proposed date if later). */
    public void approve(LocalDate from) {
        if (state != State.PENDING_APPROVAL) {
            throw new IllegalStateException("Not pending");
        }
        if (from.isAfter(effectiveFrom)) {
            effectiveFrom = from;
        }
        state = State.APPROVED;
    }

    public void reject() {
        if (state == State.PENDING_APPROVAL) {
            state = State.REJECTED;
        }
    }

    /** Superseded: the last day in force is the day before its successor starts. */
    public void endBefore(LocalDate successorFrom) {
        LocalDate last = successorFrom.minusDays(1);
        if (last.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException(
                    "A successor cannot start on or before " + effectiveFrom);
        }
        effectiveTo = last;
    }

    public Long getId() {
        return id;
    }

    public Long getRuleId() {
        return ruleId;
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

    public BigDecimal getScalarValue() {
        return scalarValue;
    }

    public Long getCircularOrderId() {
        return circularOrderId;
    }

    public String getFormulaExpression() {
        return formulaExpression;
    }

    public String getRoundingRuleKey() {
        return roundingRuleKey;
    }

    /**
     * A blocked statutory-output definition refuses to generate until its layout is loaded (KB
     * RE-16).
     */
    public boolean isBlocked() {
        return Boolean.TRUE.equals(blocked);
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public State getState() {
        return state;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
