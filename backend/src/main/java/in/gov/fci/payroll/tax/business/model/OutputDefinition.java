package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code report_definition} of the statutory-output domain, as M-17 maintains it (target DDL v4.0
 * Section 24; SRS M-17, BR-TAX-013; plan 08 D-1). The catalogue rows are the statutory outputs the
 * SRS names (seeded without a version, so blocked); module 15 adds the REPORT domain on the same
 * table. Read-only here: the catalogue changes by migration, its layouts by versions.
 */
@Entity
@Table(name = "report_definition")
public class OutputDefinition {

    @Id
    @Column(name = "report_def_id")
    private Long reportDefId;

    @Column(name = "report_key", nullable = false, length = 60)
    private String reportKey;

    @Column(name = "report_name", nullable = false, length = 300)
    private String reportName;

    @Column(name = "producing_screen_code", nullable = false, length = 20)
    private String producingScreenCode;

    @Column(name = "output_domain", nullable = false, length = 20)
    private String outputDomain;

    @Column(name = "statutory_reference", length = 200)
    private String statutoryReference;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_flag", nullable = false, length = 1)
    private String activeFlag;

    protected OutputDefinition() {}

    public Long getReportDefId() {
        return reportDefId;
    }

    public String getReportKey() {
        return reportKey;
    }

    public String getReportName() {
        return reportName;
    }

    public String getProducingScreenCode() {
        return producingScreenCode;
    }

    public String getOutputDomain() {
        return outputDomain;
    }

    public String getStatutoryReference() {
        return statutoryReference;
    }

    public boolean isActive() {
        return "Y".equals(activeFlag);
    }
}
