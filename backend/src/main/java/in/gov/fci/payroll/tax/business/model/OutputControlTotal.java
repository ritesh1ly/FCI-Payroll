package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code report_control_total}: a control total a statutory output must reconcile before release
 * (target DDL v4.0 Section 24; SRS-7-8, BR-TAX-011, BR-TAX-012): against the posted payroll, the GL
 * posting statement, the bank advice or the remitted challans, within a tolerance.
 */
@Entity
@Table(name = "report_control_total")
public class OutputControlTotal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "control_def_id")
    private Long controlDefId;

    @Column(name = "report_version_id", nullable = false)
    private Long reportVersionId;

    @Column(name = "control_label", nullable = false, length = 200)
    private String controlLabel;

    @Column(name = "reconciles_against", nullable = false, length = 40)
    private String reconcilesAgainst;

    @Column(name = "tolerance_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal toleranceAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "is_release_gate", nullable = false, length = 1)
    private String releaseGate;

    protected OutputControlTotal() {}

    public static OutputControlTotal of(
            Long versionId, String label, String against, BigDecimal tolerance, boolean gate) {
        OutputControlTotal c = new OutputControlTotal();
        c.reportVersionId = versionId;
        c.controlLabel = label;
        c.reconcilesAgainst = against;
        c.toleranceAmount = tolerance;
        c.releaseGate = gate ? "Y" : "N";
        return c;
    }

    public Long getReportVersionId() {
        return reportVersionId;
    }

    public String getControlLabel() {
        return controlLabel;
    }

    public String getReconcilesAgainst() {
        return reconcilesAgainst;
    }

    public BigDecimal getToleranceAmount() {
        return toleranceAmount;
    }

    public boolean isReleaseGate() {
        return "Y".equals(releaseGate);
    }
}
