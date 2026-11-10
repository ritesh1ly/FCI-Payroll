package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code pay_component}: M-07 pay component (target DDL v4.0 S07): the pay code is the entity;
 * end-dated, never deleted or re-issued.
 */
@Entity
@Table(name = "pay_component")
public class PayComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "component_id")
    private Long componentId;

    @Column(name = "legacy_pay_code", nullable = false)
    private Integer legacyPayCode;

    @Column(name = "component_name", nullable = false, length = 150)
    private String componentName;

    @Column(name = "component_type", nullable = false, length = 10)
    private String componentType;

    @Column(name = "payslip_group", length = 40)
    private String payslipGroup;

    @Column(name = "basis_rule_key", length = 100)
    private String basisRuleKey;

    @Column(name = "source_code", nullable = false, length = 10)
    private String sourceCode;

    @Column(name = "gl_natural_account", length = 20)
    private String glNaturalAccount;

    @Column(name = "taxable_flag", nullable = false, length = 10)
    private String taxableFlag;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "in_cpf_base_flag", nullable = false, length = 1)
    private Boolean inCpfBaseFlag;

    /** US-GAP-298 (SRS-LV-15, BR-LEAVE-007; V077_1): the M-07 "Subject to LWP" attribute. */
    @Convert(converter = YesNoConverter.class)
    @Column(name = "lwp_subject_flag", nullable = false, length = 1)
    private Boolean lwpSubjectFlag;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "labour_specific_flag", nullable = false, length = 1)
    private Boolean labourSpecificFlag;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "supp_excluded_flag", nullable = false, length = 1)
    private Boolean suppExcludedFlag;

    @Column(name = "agency_id")
    private Long agencyId;

    @Column(name = "eligibility_rule_key", length = 100)
    private String eligibilityRuleKey;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_retired", nullable = false, length = 1)
    private Boolean isRetired;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected PayComponent() {}

    /** M-07: a new catalogue entry as the Rule Approver approved it (plan 06 phase 06d). */
    public static PayComponent defined(
            in.gov.fci.payroll.configuration.business.ComponentRules.Definition d) {
        PayComponent c = new PayComponent();
        c.legacyPayCode = d.payCode();
        c.versionNo = 1;
        c.suppExcludedFlag = false;
        c.isRetired = false;
        c.apply(d);
        return c;
    }

    /**
     * M-07: a revision of the entry (the code stays; a new version number). The code's own start is
     * kept: the attributes are held in place, not dated per version, so taking the revision's date
     * as the start would put the code out of force for every earlier day (a re-run of an earlier
     * month, the as-of view, M-14 exclusions; BR-CYC-011..017, UI audit S7, Wave D review 3). A
     * revision dated before the start brings the start forward to it (OD-66).
     */
    public void revised(in.gov.fci.payroll.configuration.business.ComponentRules.Definition d) {
        LocalDate start = effectiveFrom;
        apply(d);
        if (start != null && !d.effectiveFrom().isBefore(start)) {
            effectiveFrom = start;
        }
        versionNo = versionNo == null ? 1 : versionNo + 1;
    }

    private void apply(in.gov.fci.payroll.configuration.business.ComponentRules.Definition d) {
        componentName = d.name().strip();
        componentType = d.type();
        payslipGroup = d.payslipGroup();
        basisRuleKey = d.basisRuleKey();
        eligibilityRuleKey = d.eligibilityRuleKey();
        sourceCode = d.source();
        glNaturalAccount = d.naturalAccount();
        taxableFlag = d.taxable();
        inCpfBaseFlag = d.inCpfBase();
        lwpSubjectFlag = Boolean.TRUE.equals(d.subjectToLwp());
        labourSpecificFlag =
                in.gov.fci.payroll.configuration.business.ComponentRules.labourOnly(
                        d.payrollTypes());
        effectiveFrom = d.effectiveFrom();
    }

    /** M-07: retired from the day after {@code lastDay}; end-dated, never deleted or reused. */
    public void retired(LocalDate lastDay) {
        effectiveTo = lastDay;
        isRetired = true;
    }

    /**
     * M-14 (SRS-16-13): whether the supplementary run skips this pay code; changed only by an
     * approved M14_EDIT.
     */
    public void setSuppExcluded(boolean excluded) {
        suppExcludedFlag = excluded;
    }

    /** An active catalogue entry on {@code day}: not retired and its dates cover the day. */
    public boolean isActiveOn(java.time.LocalDate day) {
        return !isIsRetired()
                && !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public Long getComponentId() {
        return componentId;
    }

    public Integer getLegacyPayCode() {
        return legacyPayCode;
    }

    public String getComponentName() {
        return componentName;
    }

    public String getComponentType() {
        return componentType;
    }

    public String getPayslipGroup() {
        return payslipGroup;
    }

    public String getBasisRuleKey() {
        return basisRuleKey;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public String getGlNaturalAccount() {
        return glNaturalAccount;
    }

    public String getTaxableFlag() {
        return taxableFlag;
    }

    public boolean isInCpfBaseFlag() {
        return Boolean.TRUE.equals(inCpfBaseFlag);
    }

    public boolean isLwpSubjectFlag() {
        return Boolean.TRUE.equals(lwpSubjectFlag);
    }

    public boolean isLabourSpecificFlag() {
        return Boolean.TRUE.equals(labourSpecificFlag);
    }

    public boolean isSuppExcludedFlag() {
        return Boolean.TRUE.equals(suppExcludedFlag);
    }

    public Long getAgencyId() {
        return agencyId;
    }

    public String getEligibilityRuleKey() {
        return eligibilityRuleKey;
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

    public boolean isIsRetired() {
        return Boolean.TRUE.equals(isRetired);
    }
}
