package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * {@code gl_account_mapping}: M-24 natural account of a pay component per unit and cost centre
 * (target DDL v4.0 S23), effective-dated.
 */
@Entity
@Table(name = "gl_account_mapping")
public class GlAccountMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mapping_id")
    private Long mappingId;

    @Column(name = "component_id", nullable = false)
    private Long componentId;

    @Column(name = "org_unit_id")
    private Long orgUnitId;

    @Column(name = "cost_centre_id")
    private Long costCentreId;

    @Column(name = "natural_account", nullable = false, length = 20)
    private String naturalAccount;

    @Column(name = "gl_code_combination_id")
    private Long glCodeCombinationId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "approval_id")
    private Long approvalId;

    protected GlAccountMapping() {}

    /** A proposed mapping version; in force only once its approval is APPROVED. */
    public static GlAccountMapping propose(
            Long componentId,
            Long orgUnitId,
            Long costCentreId,
            String naturalAccount,
            int versionNo,
            LocalDate from,
            Long approvalId) {
        GlAccountMapping m = new GlAccountMapping();
        m.componentId = componentId;
        m.orgUnitId = orgUnitId;
        m.costCentreId = costCentreId;
        m.naturalAccount = naturalAccount;
        m.versionNo = versionNo;
        m.effectiveFrom = from;
        m.approvalId = approvalId;
        return m;
    }

    public boolean coversDay(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    public Long getMappingId() {
        return mappingId;
    }

    public Long getComponentId() {
        return componentId;
    }

    public Long getOrgUnitId() {
        return orgUnitId;
    }

    public Long getCostCentreId() {
        return costCentreId;
    }

    public String getNaturalAccount() {
        return naturalAccount;
    }

    public Long getGlCodeCombinationId() {
        return glCodeCombinationId;
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
}
