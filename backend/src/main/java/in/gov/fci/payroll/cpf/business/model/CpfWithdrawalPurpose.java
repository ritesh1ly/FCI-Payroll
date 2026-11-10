package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code cpf_withdrawal_purpose}: one version of a CPF withdrawal purpose (target DDL v4.0 Section
 * 10; SRS-6-5, SRS-6-39; BR-CPF-028, 032, 037, 039). Effective-dated: a change is a new version
 * from a later date and the version it replaces ends the day before; nothing is edited in place or
 * deleted. {@code is_confirmed} = N while FCI has not confirmed the purpose (Q-074).
 */
@Entity
@Table(name = "cpf_withdrawal_purpose")
public class CpfWithdrawalPurpose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "purpose_id")
    private Long purposeId;

    @Column(name = "purpose_code", nullable = false, length = 30)
    private String purposeCode;

    @Column(name = "purpose_name", nullable = false, length = 200)
    private String purposeName;

    @Column(name = "withdrawal_category", nullable = false, length = 12)
    private String withdrawalCategory;

    @Column(name = "sanction_limit_rule_key", length = 100)
    private String sanctionLimitRuleKey;

    @Column(name = "max_occurrences")
    private Integer maxOccurrences;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "requires_no_caste_dispute", nullable = false, length = 1)
    private Boolean requiresNoCasteDispute;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "requires_property_details", nullable = false, length = 1)
    private Boolean requiresPropertyDetails;

    @Column(name = "circular_order_id")
    private Long circularOrderId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_confirmed", nullable = false, length = 1)
    private Boolean isConfirmed;

    protected CpfWithdrawalPurpose() {}

    /** A version as the Rule Approver approved it. */
    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public static CpfWithdrawalPurpose approved(
            String code,
            String name,
            String category,
            String limitRuleKey,
            Integer maxOccurrences,
            boolean noCasteDispute,
            boolean propertyDetails,
            LocalDate effectiveFrom,
            boolean confirmed) {
        CpfWithdrawalPurpose p = new CpfWithdrawalPurpose();
        p.purposeCode = code;
        p.purposeName = name;
        p.withdrawalCategory = category;
        p.sanctionLimitRuleKey = limitRuleKey;
        p.maxOccurrences = maxOccurrences;
        p.requiresNoCasteDispute = noCasteDispute;
        p.requiresPropertyDetails = propertyDetails;
        p.effectiveFrom = effectiveFrom;
        p.isConfirmed = confirmed;
        return p;
    }

    /** A later version replaces this one from {@code successorFrom}. */
    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    /** In force on {@code day}. */
    public boolean inForceOn(LocalDate day) {
        return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    public Long getPurposeId() {
        return purposeId;
    }

    public String getPurposeCode() {
        return purposeCode;
    }

    public String getPurposeName() {
        return purposeName;
    }

    public String getWithdrawalCategory() {
        return withdrawalCategory;
    }

    public String getSanctionLimitRuleKey() {
        return sanctionLimitRuleKey;
    }

    public Integer getMaxOccurrences() {
        return maxOccurrences;
    }

    public boolean isRequiresNoCasteDispute() {
        return Boolean.TRUE.equals(requiresNoCasteDispute);
    }

    public boolean isRequiresPropertyDetails() {
        return Boolean.TRUE.equals(requiresPropertyDetails);
    }

    public Long getCircularOrderId() {
        return circularOrderId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public boolean isConfirmed() {
        return Boolean.TRUE.equals(isConfirmed);
    }
}
