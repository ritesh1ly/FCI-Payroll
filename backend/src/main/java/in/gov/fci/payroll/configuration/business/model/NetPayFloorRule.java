package in.gov.fci.payroll.configuration.business.model;

import in.gov.fci.payroll.configuration.business.DatedVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code net_pay_floor_rule}: M-14 net-pay floor of one payroll type (target DDL v4.0 S18,
 * SRS-16-13), effective-dated. The table has no state column and no version number: a version is in
 * force when its approval is APPROVED and its dates cover the day. Values are settings seeded from
 * the current system; an unconfirmed floor is reported as such.
 */
@Entity
@Table(name = "net_pay_floor_rule")
public class NetPayFloorRule implements DatedVersion {

    /** {@code floor_basis} (DDL CHECK). */
    public enum Basis {
        PERCENT_OF_GROSS,
        ABSOLUTE_AMOUNT,
        NONE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "floor_rule_id")
    private Long floorRuleId;

    @Column(name = "payroll_type", length = 20)
    private String payrollType;

    @Column(name = "floor_basis", length = 20)
    private String floorBasis;

    @Column(name = "floor_value", precision = 12, scale = 2)
    private BigDecimal floorValue;

    @Column(name = "deduction_ceiling_pct", precision = 7, scale = 4)
    private BigDecimal deductionCeilingPct;

    @Column(name = "source_instrument", length = 300)
    private String sourceInstrument;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_confirmed", nullable = false, length = 1)
    private Boolean isConfirmed;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "approval_id")
    private Long approvalId;

    protected NetPayFloorRule() {}

    /** A proposed version; in force only once its approval is APPROVED. */
    public static NetPayFloorRule propose(
            String payrollType,
            Basis basis,
            BigDecimal floorValue,
            BigDecimal deductionCeilingPct,
            String sourceInstrument,
            boolean confirmed,
            LocalDate from,
            Long approvalId) {
        NetPayFloorRule r = new NetPayFloorRule();
        r.payrollType = payrollType;
        r.floorBasis = basis.name();
        r.floorValue = floorValue;
        r.deductionCeilingPct = deductionCeilingPct;
        r.sourceInstrument = sourceInstrument;
        r.isConfirmed = confirmed;
        r.effectiveFrom = from;
        r.approvalId = approvalId;
        return r;
    }

    @Override
    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    public Long getFloorRuleId() {
        return floorRuleId;
    }

    public String getPayrollType() {
        return payrollType;
    }

    public String getFloorBasis() {
        return floorBasis;
    }

    public BigDecimal getFloorValue() {
        return floorValue;
    }

    public BigDecimal getDeductionCeilingPct() {
        return deductionCeilingPct;
    }

    public String getSourceInstrument() {
        return sourceInstrument;
    }

    public boolean isIsConfirmed() {
        return Boolean.TRUE.equals(isConfirmed);
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
