package in.gov.fci.payroll.configuration.business.model;

import in.gov.fci.payroll.configuration.business.DatedVersion;
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
 * {@code operation_master}: M-27 work-slip operation (target DDL v4.0 S14, SRS-G-41), one row per
 * version, end-dated and never deleted. No state column: a version is in force when its approval is
 * APPROVED and its dates cover the day.
 */
@Entity
@Table(name = "operation_master")
public class OperationMaster implements DatedVersion {

    /** {@code norm_type} (DDL CHECK). */
    public enum NormType {
        HANDLING,
        HEIGHT,
        LEAD,
        PIECE_RATE,
        NONE
    }

    /** {@code rate_norm_link} (DDL CHECK). */
    public enum RateNormLink {
        NORM_SLAB,
        SOR_ASOR_RATE,
        ALLOWANCE,
        TIME_BASED,
        NONE
    }

    /** What a version holds besides its code, dates and approval. */
    public record Particulars(
            String name,
            boolean departmental,
            boolean dps,
            boolean nwnp,
            boolean bagBased,
            NormType normType,
            RateNormLink rateNormLink,
            boolean dusting,
            Long allowanceComponentId,
            boolean presentButNoWork,
            Long circularOrderId) {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operation_id")
    private Long operationId;

    @Column(name = "operation_code", nullable = false, length = 20)
    private String operationCode;

    @Column(name = "operation_name", nullable = false, length = 150)
    private String operationName;

    @Column(name = "label_master_id")
    private Long labelMasterId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "applies_dept_labour", nullable = false, length = 1)
    private Boolean appliesDeptLabour;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "applies_dps_labour", nullable = false, length = 1)
    private Boolean appliesDpsLabour;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "applies_nwnp_labour", nullable = false, length = 1)
    private Boolean appliesNwnpLabour;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_bag_based", nullable = false, length = 1)
    private Boolean isBagBased;

    @Column(name = "norm_type", length = 20)
    private String normType;

    @Column(name = "rate_norm_link", length = 20)
    private String rateNormLink;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_dusting", nullable = false, length = 1)
    private Boolean isDusting;

    @Column(name = "allowance_component_id")
    private Long allowanceComponentId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_present_but_no_work", nullable = false, length = 1)
    private Boolean isPresentButNoWork;

    @Column(name = "circular_order_id")
    private Long circularOrderId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "approval_id")
    private Long approvalId;

    protected OperationMaster() {}

    /** A proposed version; in force only once its approval is APPROVED. */
    public static OperationMaster propose(
            String code, Particulars p, int versionNo, LocalDate from, Long approvalId) {
        OperationMaster o = new OperationMaster();
        o.operationCode = code;
        o.operationName = p.name();
        o.appliesDeptLabour = p.departmental();
        o.appliesDpsLabour = p.dps();
        o.appliesNwnpLabour = p.nwnp();
        o.isBagBased = p.bagBased();
        o.normType = p.normType() == null ? null : p.normType().name();
        o.rateNormLink = p.rateNormLink() == null ? null : p.rateNormLink().name();
        o.isDusting = p.dusting();
        o.allowanceComponentId = p.allowanceComponentId();
        o.isPresentButNoWork = p.presentButNoWork();
        o.circularOrderId = p.circularOrderId();
        o.versionNo = versionNo;
        o.effectiveFrom = from;
        o.approvalId = approvalId;
        return o;
    }

    @Override
    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    public Long getOperationId() {
        return operationId;
    }

    public String getOperationCode() {
        return operationCode;
    }

    public String getOperationName() {
        return operationName;
    }

    public Long getLabelMasterId() {
        return labelMasterId;
    }

    public boolean isAppliesDeptLabour() {
        return Boolean.TRUE.equals(appliesDeptLabour);
    }

    public boolean isAppliesDpsLabour() {
        return Boolean.TRUE.equals(appliesDpsLabour);
    }

    public boolean isAppliesNwnpLabour() {
        return Boolean.TRUE.equals(appliesNwnpLabour);
    }

    public boolean isIsBagBased() {
        return Boolean.TRUE.equals(isBagBased);
    }

    public String getNormType() {
        return normType;
    }

    public String getRateNormLink() {
        return rateNormLink;
    }

    public boolean isIsDusting() {
        return Boolean.TRUE.equals(isDusting);
    }

    public Long getAllowanceComponentId() {
        return allowanceComponentId;
    }

    public boolean isIsPresentButNoWork() {
        return Boolean.TRUE.equals(isPresentButNoWork);
    }

    public Long getCircularOrderId() {
        return circularOrderId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    @Override
    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    @Override
    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    @Override
    public Long getApprovalId() {
        return approvalId;
    }
}
