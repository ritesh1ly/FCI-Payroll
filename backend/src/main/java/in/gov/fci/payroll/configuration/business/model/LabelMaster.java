package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.YesNoConverter;

/** {@code label_master}: M-26 label in a language and scope (target DDL v4.0 S02). */
@Entity
@Table(name = "label_master")
public class LabelMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "label_id")
    private Long labelId;

    @Column(name = "label_key", nullable = false, length = 100)
    private String labelKey;

    @Column(name = "label_type", nullable = false, length = 15)
    private String labelType;

    @Column(name = "master_entity", length = 60)
    private String masterEntity;

    @Column(name = "master_code", length = 40)
    private String masterCode;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(name = "scope_level", nullable = false, length = 10)
    private String scopeLevel;

    @Column(name = "scope_location_id")
    private Long scopeLocationId;

    @Nationalized
    @Column(name = "label_text", nullable = false, length = 1000)
    private String labelText;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_localisable", nullable = false, length = 1)
    private Boolean isLocalisable;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "label_status", nullable = false, length = 20)
    private String labelStatus;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    protected LabelMaster() {}

    /** {@code label_type} (DDL CHECK). */
    public enum Type {
        UI_LABEL,
        HELP_TEXT,
        MASTER_VALUE
    }

    /** {@code scope_level} (DDL CHECK). */
    public enum Scope {
        GLOBAL,
        REGION,
        OFFICE
    }

    /** {@code label_status} (DDL CHECK). */
    public enum Status {
        PENDING_APPROVAL,
        ACTIVE,
        SUPERSEDED,
        WITHDRAWN,
        REJECTED
    }

    /** What the maker enters (SRS-G-39). */
    public record Particulars(
            String key,
            Type type,
            String masterEntity,
            String masterCode,
            String language,
            Scope scope,
            Long scopeLocationId,
            String text,
            boolean localisable,
            LocalDate effectiveFrom) {}

    /** A proposed label version awaiting the checker. */
    public static LabelMaster propose(
            Particulars p, int versionNo, String actor, String reason, Instant now) {
        LabelMaster l = new LabelMaster();
        l.labelKey = p.key();
        l.labelType = p.type().name();
        l.masterEntity = p.masterEntity();
        l.masterCode = p.masterCode();
        l.languageCode = p.language();
        l.scopeLevel = p.scope().name();
        l.scopeLocationId = p.scopeLocationId();
        l.labelText = p.text();
        l.isLocalisable = p.localisable();
        l.versionNo = versionNo;
        l.effectiveFrom = p.effectiveFrom();
        l.labelStatus = Status.PENDING_APPROVAL.name();
        l.changedBy = actor;
        l.changedAt = now;
        l.changeReason = reason;
        return l;
    }

    public Status status() {
        return Status.valueOf(labelStatus);
    }

    public void submitted(Long approvalId) {
        this.approvalId = approvalId;
    }

    /** The checker's decision on this version's own approval. */
    public void decided(boolean approved, String actor, Instant now) {
        labelStatus = approved ? Status.ACTIVE.name() : Status.REJECTED.name();
        touch(actor, now);
    }

    /** Replaced by an approved later version of the same key, language and scope. */
    public void supersede(LocalDate successorFrom, String actor, Instant now) {
        labelStatus = Status.SUPERSEDED.name();
        effectiveTo = successorFrom.minusDays(1);
        touch(actor, now);
    }

    /** Withdrawn (end-dated, never deleted): the next label in the SRS-G-39 order shows again. */
    public void withdraw(LocalDate lastDay, String actor, String reason, Instant now) {
        labelStatus = Status.WITHDRAWN.name();
        effectiveTo = lastDay;
        changeReason = reason;
        touch(actor, now);
    }

    public boolean coversDay(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    private void touch(String actor, Instant now) {
        changedBy = actor;
        changedAt = now;
    }

    public Long getLabelId() {
        return labelId;
    }

    public String getLabelKey() {
        return labelKey;
    }

    public String getLabelType() {
        return labelType;
    }

    public String getMasterEntity() {
        return masterEntity;
    }

    public String getMasterCode() {
        return masterCode;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public String getScopeLevel() {
        return scopeLevel;
    }

    public Long getScopeLocationId() {
        return scopeLocationId;
    }

    public String getLabelText() {
        return labelText;
    }

    public boolean isIsLocalisable() {
        return Boolean.TRUE.equals(isLocalisable);
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getLabelStatus() {
        return labelStatus;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getChangeReason() {
        return changeReason;
    }
}
