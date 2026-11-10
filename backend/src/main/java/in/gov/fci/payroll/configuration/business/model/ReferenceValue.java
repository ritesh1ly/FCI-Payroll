package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code reference_value}: One effective-dated value of a reference list (target DDL v4.0 S02);
 * Hindi / local names in label_master.
 */
@Entity
@Table(name = "reference_value")
public class ReferenceValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "value_id")
    private Long valueId;

    @Column(name = "list_id", nullable = false)
    private Long listId;

    @Column(name = "value_code", nullable = false, length = 40)
    private String valueCode;

    @Column(name = "value_label_en", nullable = false, length = 200)
    private String valueLabelEn;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "parent_value_id")
    private Long parentValueId;

    @Column(name = "numeric_attribute", precision = 18, scale = 4)
    private BigDecimal numericAttribute;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    protected ReferenceValue() {}

    public Long getValueId() {
        return valueId;
    }

    public Long getListId() {
        return listId;
    }

    public String getValueCode() {
        return valueCode;
    }

    public String getValueLabelEn() {
        return valueLabelEn;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public Long getParentValueId() {
        return parentValueId;
    }

    public BigDecimal getNumericAttribute() {
        return numericAttribute;
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
