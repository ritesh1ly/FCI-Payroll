package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code it_section_master}: one version of an income-tax section (target DDL v4.0 Section 11;
 * SRS-7-2, SRS-7-20; BR-TAX-002). Effective-dated: a change is a new version from a later date and
 * the version it replaces ends the day before; nothing is edited in place or deleted. A NULL limit
 * is a section with no fixed limit.
 */
@Entity
@Table(name = "it_section_master")
public class ItSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "section_code", nullable = false, length = 20)
    private String sectionCode;

    @Column(name = "section_description", nullable = false, length = 500)
    private String sectionDescription;

    @Column(name = "max_deduction_amount", precision = 12, scale = 2)
    private BigDecimal maxDeductionAmount;

    @Column(name = "regime_applicability", length = 10)
    private String regimeApplicability;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected ItSection() {}

    /** A version as the Rule Approver approved it. */
    public static ItSection approved(
            String code, String description, BigDecimal limit, String regime, LocalDate from) {
        ItSection s = new ItSection();
        s.sectionCode = code;
        s.sectionDescription = description;
        s.maxDeductionAmount = limit;
        s.regimeApplicability = regime;
        s.effectiveFrom = from;
        return s;
    }

    /** A later version replaces this one from {@code successorFrom}. */
    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    /** In force on {@code day}. */
    public boolean inForceOn(LocalDate day) {
        return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    public Long getSectionId() {
        return sectionId;
    }

    public String getSectionCode() {
        return sectionCode;
    }

    public String getSectionDescription() {
        return sectionDescription;
    }

    public BigDecimal getMaxDeductionAmount() {
        return maxDeductionAmount;
    }

    public String getRegimeApplicability() {
        return regimeApplicability;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
