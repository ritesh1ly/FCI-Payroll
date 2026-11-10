package in.gov.fci.payroll.cpf.business.model;

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
 * {@code pf_interest_rate}: one M-19 PF interest rate (target DDL v4.0 Section 10; SRS-6-11,
 * BR-CPF-033): the FCI-declared (Trust) rate for active and deputed members or the EPFO-declared
 * rate for inactive members, of one financial year and fund, from a date. Written only when the
 * Rule Approver approves it (the proposal lives in its approval item until then), so every row is
 * approved. An undeclared rate ({@code is_declared = N}) is never used by an interest run.
 */
@Entity
@Table(name = "pf_interest_rate")
public class PfInterestRate {

    /** {@code rate_source} (DDL CHECK): FCI_TRUST = FCI-declared, RPFC = EPFO-declared. */
    public enum Source {
        FCI_TRUST,
        RPFC
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interest_rate_id")
    private Long interestRateId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "fund_type", nullable = false, length = 10)
    private String fundType;

    @Column(name = "rate_source", nullable = false, length = 20)
    private String rateSource;

    @Column(name = "member_population", length = 20)
    private String memberPopulation;

    @Column(name = "rate_percent", nullable = false, precision = 7, scale = 4)
    private BigDecimal ratePercent;

    @Column(name = "applies_from", nullable = false)
    private LocalDate appliesFrom;

    @Column(name = "applies_to")
    private LocalDate appliesTo;

    @Column(name = "applicability_note", length = 500)
    private String applicabilityNote;

    @Column(name = "bonus_interest_pct", precision = 7, scale = 4)
    private BigDecimal bonusInterestPct;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_declared", nullable = false, length = 1)
    private Boolean isDeclared;

    @Column(name = "approval_id")
    private Long approvalId;

    protected PfInterestRate() {}

    /** The rate an approved proposal writes. */
    public static PfInterestRate approved(
            String finYear,
            String fundType,
            Source source,
            String memberPopulation,
            BigDecimal ratePercent,
            LocalDate appliesFrom,
            LocalDate appliesTo,
            String applicabilityNote,
            BigDecimal bonusInterestPct,
            boolean declared,
            Long approvalId) {
        PfInterestRate r = new PfInterestRate();
        r.finYear = finYear;
        r.fundType = fundType;
        r.rateSource = source.name();
        r.memberPopulation = memberPopulation;
        r.ratePercent = ratePercent;
        r.appliesFrom = appliesFrom;
        r.appliesTo = appliesTo;
        r.applicabilityNote = applicabilityNote;
        r.bonusInterestPct = bonusInterestPct;
        r.isDeclared = declared;
        r.approvalId = approvalId;
        return r;
    }

    /** M-19: a later approved rate of the same year, fund and source replaces this one. */
    public void endBefore(LocalDate successorFrom) {
        appliesTo = successorFrom.minusDays(1);
    }

    /** M-19: FCI or EPFO declares the rate (the rate as declared; audited with the old value). */
    public void declare(BigDecimal declaredRate) {
        ratePercent = declaredRate;
        isDeclared = true;
    }

    public Long getInterestRateId() {
        return interestRateId;
    }

    public String getFinYear() {
        return finYear;
    }

    public String getFundType() {
        return fundType;
    }

    public String getRateSource() {
        return rateSource;
    }

    public String getMemberPopulation() {
        return memberPopulation;
    }

    public BigDecimal getRatePercent() {
        return ratePercent;
    }

    public LocalDate getAppliesFrom() {
        return appliesFrom;
    }

    public LocalDate getAppliesTo() {
        return appliesTo;
    }

    public String getApplicabilityNote() {
        return applicabilityNote;
    }

    public BigDecimal getBonusInterestPct() {
        return bonusInterestPct;
    }

    public boolean isIsDeclared() {
        return Boolean.TRUE.equals(isDeclared);
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
