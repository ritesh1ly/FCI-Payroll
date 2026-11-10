package in.gov.fci.payroll.cpf.business.model;

import in.gov.fci.payroll.cpf.business.LedgerBalances;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code employee_pf_header}: one member's CPF / PF ledger of a financial year, kept against the
 * CPF Member ID received from HRMS (target DDL v4.0 Section 10; BR-CPF-040, SRS-6-57). Opening and
 * closing balances are held per part (employee, employer, VPF); no part is ever negative ({@code
 * chk_pf_balance_nonneg}). The closing balance changes only through the ledger's one posting path;
 * an established opening balance only through I-22.
 */
@Entity
@Table(name = "employee_pf_header")
public class EmployeePfHeader {

    /** {@code member_status} (DDL CHECK): drives the interest rate and the interest stop. */
    public enum MemberStatus {
        ACTIVE,
        DEPUTATION,
        EX_MEMBER,
        FINALLY_SETTLED,
        CLOSED_ZONE_CHANGE
    }

    /** {@code opening_balance_source} (DDL CHECK). */
    public enum OpeningSource {
        MIGRATION,
        SYSTEM_COMPUTED,
        CORRECTION,
        ZONE_CARRY_FORWARD
    }

    /** {@code interest_end_reason} (DDL CHECK). */
    public enum InterestEndReason {
        EX_MEMBER_3_YEARS,
        FINAL_SANCTION,
        ZONE_CHANGE_CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pf_header_id")
    private Long pfHeaderId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "fund_type", nullable = false, length = 10)
    private String fundType;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Column(name = "cpf_member_id_hist_id")
    private Long cpfMemberIdHistId;

    @Column(name = "vcpf_subscription_amt", precision = 12, scale = 2)
    private BigDecimal vcpfSubscriptionAmt;

    @Column(name = "opening_balance_employee", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingBalanceEmployee;

    @Column(name = "opening_balance_employer", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingBalanceEmployer;

    @Column(name = "opening_balance_vpf", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingBalanceVpf;

    @Column(name = "closing_balance_employee", nullable = false, precision = 14, scale = 2)
    private BigDecimal closingBalanceEmployee;

    @Column(name = "closing_balance_employer", nullable = false, precision = 14, scale = 2)
    private BigDecimal closingBalanceEmployer;

    @Column(name = "closing_balance_vpf", nullable = false, precision = 14, scale = 2)
    private BigDecimal closingBalanceVpf;

    @Column(name = "interest_credited", nullable = false, precision = 14, scale = 2)
    private BigDecimal interestCredited;

    @Column(name = "interest_taxable_acct", nullable = false, precision = 14, scale = 2)
    private BigDecimal interestTaxableAcct;

    @Column(name = "interest_non_taxable_acct", nullable = false, precision = 14, scale = 2)
    private BigDecimal interestNonTaxableAcct;

    @Column(name = "bonus_interest_credited", nullable = false, precision = 14, scale = 2)
    private BigDecimal bonusInterestCredited;

    @Column(name = "opening_balance_source", nullable = false, length = 30)
    private String openingBalanceSource;

    @Column(name = "member_status", nullable = false, length = 20)
    private String memberStatus;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_deputation", nullable = false, length = 1)
    private Boolean isDeputation;

    @Column(name = "interest_end_date")
    private LocalDate interestEndDate;

    @Column(name = "interest_end_reason", length = 20)
    private String interestEndReason;

    @Column(name = "interest_end_set_at")
    private Instant interestEndSetAt;

    @Column(name = "interest_end_cleared_at")
    private Instant interestEndClearedAt;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "interest_frozen_flag", nullable = false, length = 1)
    private Boolean interestFrozenFlag;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected EmployeePfHeader() {}

    /**
     * A member's ledger of a financial year, opened at {@code opening} (closing = opening until the
     * first posting); a member already on deputation or an ex-member carries that state.
     */
    public static EmployeePfHeader open(
            Long employeeId,
            String finYear,
            String fundType,
            Long zoneId,
            Long memberIdHistId,
            LedgerBalances opening,
            OpeningSource source,
            MemberStatus status,
            boolean deputation) {
        EmployeePfHeader h = new EmployeePfHeader();
        h.employeeId = employeeId;
        h.finYear = finYear;
        h.fundType = fundType;
        h.zoneId = zoneId;
        h.cpfMemberIdHistId = memberIdHistId;
        h.openingBalanceEmployee = opening.employee();
        h.openingBalanceEmployer = opening.employer();
        h.openingBalanceVpf = opening.vpf();
        h.closingBalanceEmployee = opening.employee();
        h.closingBalanceEmployer = opening.employer();
        h.closingBalanceVpf = opening.vpf();
        h.interestCredited = BigDecimal.ZERO;
        h.interestTaxableAcct = BigDecimal.ZERO;
        h.interestNonTaxableAcct = BigDecimal.ZERO;
        h.bonusInterestCredited = BigDecimal.ZERO;
        h.openingBalanceSource = source.name();
        h.memberStatus = status.name();
        h.isDeputation = deputation;
        h.interestFrozenFlag = false;
        return h;
    }

    /** The closing balance per part. */
    public LedgerBalances closing() {
        return new LedgerBalances(
                closingBalanceEmployee, closingBalanceEmployer, closingBalanceVpf);
    }

    /** The opening balance per part. */
    public LedgerBalances opening() {
        return new LedgerBalances(
                openingBalanceEmployee, openingBalanceEmployer, openingBalanceVpf);
    }

    /** SRS-6-57: the new closing balance, already checked non-negative by the ledger. */
    public void close(LedgerBalances balances) {
        closingBalanceEmployee = balances.employee();
        closingBalanceEmployer = balances.employer();
        closingBalanceVpf = balances.vpf();
    }

    /**
     * SRS-6-43: an approved correction of an earlier year's opening balance reaches this later year
     * through the carry-forward: its opening (carried, not established here) and closing move by
     * the same difference, each already checked non-negative by the ledger.
     */
    public void carry(LedgerBalances opening, LedgerBalances closing) {
        openingBalanceEmployee = opening.employee();
        openingBalanceEmployer = opening.employer();
        openingBalanceVpf = opening.vpf();
        close(closing);
    }

    /**
     * SRS-6-9: the ex-member stop; never set for a member on deputation (the DDL refuses it, {@code
     * chk_pf_hdr_dep_no_stop}).
     */
    public void stopInterest(LocalDate end, InterestEndReason reason, Instant now) {
        interestEndDate = end;
        interestEndReason = reason.name();
        interestEndSetAt = now;
        interestEndClearedAt = null;
    }

    /** SRS-6-9: reinstatement clears the stop. */
    public void clearInterestStop(Instant now) {
        interestEndDate = null;
        interestEndReason = null;
        interestEndClearedAt = now;
    }

    /** SRS-6-46: the year's interest is frozen; nothing more is run or adjusted for it. */
    public void freezeInterest() {
        interestFrozenFlag = true;
    }

    public void status(MemberStatus status, boolean deputation) {
        memberStatus = status.name();
        isDeputation = deputation;
    }

    public Long getPfHeaderId() {
        return pfHeaderId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getFinYear() {
        return finYear;
    }

    public String getFundType() {
        return fundType;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getCpfMemberIdHistId() {
        return cpfMemberIdHistId;
    }

    public BigDecimal getVcpfSubscriptionAmt() {
        return vcpfSubscriptionAmt;
    }

    public BigDecimal getOpeningBalanceEmployee() {
        return openingBalanceEmployee;
    }

    public BigDecimal getOpeningBalanceEmployer() {
        return openingBalanceEmployer;
    }

    public BigDecimal getOpeningBalanceVpf() {
        return openingBalanceVpf;
    }

    public BigDecimal getClosingBalanceEmployee() {
        return closingBalanceEmployee;
    }

    public BigDecimal getClosingBalanceEmployer() {
        return closingBalanceEmployer;
    }

    public BigDecimal getClosingBalanceVpf() {
        return closingBalanceVpf;
    }

    public BigDecimal getInterestCredited() {
        return interestCredited;
    }

    public BigDecimal getInterestTaxableAcct() {
        return interestTaxableAcct;
    }

    public BigDecimal getInterestNonTaxableAcct() {
        return interestNonTaxableAcct;
    }

    public BigDecimal getBonusInterestCredited() {
        return bonusInterestCredited;
    }

    public String getOpeningBalanceSource() {
        return openingBalanceSource;
    }

    public String getMemberStatus() {
        return memberStatus;
    }

    public boolean isIsDeputation() {
        return Boolean.TRUE.equals(isDeputation);
    }

    public LocalDate getInterestEndDate() {
        return interestEndDate;
    }

    public String getInterestEndReason() {
        return interestEndReason;
    }

    public Instant getInterestEndSetAt() {
        return interestEndSetAt;
    }

    public Instant getInterestEndClearedAt() {
        return interestEndClearedAt;
    }

    public boolean isInterestFrozenFlag() {
        return Boolean.TRUE.equals(interestFrozenFlag);
    }

    public Long getRowVersion() {
        return rowVersion;
    }
}
