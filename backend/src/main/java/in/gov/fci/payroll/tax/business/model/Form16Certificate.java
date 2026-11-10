package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code form16}: a member's annual tax certificate of a financial year (target DDL v4.0 Section
 * 11; SRS-7-5, SRS-7-9, SRS-7-21; BR-TAX-010 … 012). Part B is generated from the locked year; Part
 * A, issued by the tax department, is uploaded and merged; only a merged certificate is released
 * ({@code chk_form16_release}). One per member and year ({@code uk_form16}); a correction is a
 * reissue against a correction return, audited field by field.
 */
@Entity
@Table(name = "form16")
public class Form16Certificate {

    /** {@code release_status}. */
    public enum Status {
        DRAFT,
        PENDING_PART_A,
        MERGED,
        RELEASED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form16_id")
    private Long form16Id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "gross_salary", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossSalary;

    @Column(name = "taxable_income", precision = 14, scale = 2)
    private BigDecimal taxableIncome;

    @Column(name = "tax_deducted", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxDeducted;

    @Column(name = "part_b_generated_at")
    private Instant partBGeneratedAt;

    @Column(name = "part_a_received_at")
    private Instant partAReceivedAt;

    @Column(name = "part_a_attachment_id")
    private Long partAAttachmentId;

    @Column(name = "merged_at")
    private Instant mergedAt;

    @Column(name = "release_status", nullable = false, length = 20)
    private String releaseStatus;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "definition_version_id")
    private Long definitionVersionId;

    protected Form16Certificate() {}

    /** Part B as generated from the locked year under the governing M-17 version. */
    public static Form16Certificate generated(
            Long employeeId,
            String finYear,
            BigDecimal gross,
            BigDecimal taxable,
            BigDecimal tax,
            Long version,
            Instant at) {
        Form16Certificate f = new Form16Certificate();
        f.employeeId = employeeId;
        f.finYear = finYear;
        f.releaseStatus = Status.PENDING_PART_A.name();
        f.regenerate(gross, taxable, tax, version, at);
        return f;
    }

    /** Part B generated again (before release, or on a reissue against a correction return). */
    public void regenerate(
            BigDecimal gross, BigDecimal taxable, BigDecimal tax, Long version, Instant at) {
        grossSalary = gross;
        taxableIncome = taxable;
        taxDeducted = tax;
        definitionVersionId = version;
        partBGeneratedAt = at;
        if (status() == Status.RELEASED) {
            // A reissue: released again only through the Checker.
            releaseStatus = Status.MERGED.name();
            releasedAt = null;
        }
    }

    /** SRS-7-9: Part A received and merged with Part B. */
    public void partAMerged(Long attachment, Instant at) {
        partAAttachmentId = attachment;
        partAReceivedAt = at;
        mergedAt = at;
        releaseStatus = Status.MERGED.name();
    }

    /** BR-TAX-012: released by the Payroll Checker after the reconciliation. */
    public void released(Instant at) {
        releaseStatus = Status.RELEASED.name();
        releasedAt = at;
    }

    public Status status() {
        return Status.valueOf(releaseStatus);
    }

    /** The audited figures (SRS-20-2), as a reissue compares them. */
    public Map<String, String> image() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("gross_salary", grossSalary == null ? null : grossSalary.toPlainString());
        m.put("taxable_income", taxableIncome == null ? null : taxableIncome.toPlainString());
        m.put("tax_deducted", taxDeducted == null ? null : taxDeducted.toPlainString());
        m.put("release_status", releaseStatus);
        return m;
    }

    public Long getForm16Id() {
        return form16Id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getFinYear() {
        return finYear;
    }

    public BigDecimal getGrossSalary() {
        return grossSalary;
    }

    public BigDecimal getTaxableIncome() {
        return taxableIncome;
    }

    public BigDecimal getTaxDeducted() {
        return taxDeducted;
    }

    public Instant getPartBGeneratedAt() {
        return partBGeneratedAt;
    }

    public Long getPartAAttachmentId() {
        return partAAttachmentId;
    }

    public Instant getMergedAt() {
        return mergedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public Long getDefinitionVersionId() {
        return definitionVersionId;
    }
}
