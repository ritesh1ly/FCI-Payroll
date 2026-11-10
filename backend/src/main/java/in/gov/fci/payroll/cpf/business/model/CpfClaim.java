package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code cpf_claim}: the member's CPF request as raised in Payroll eSeva or received from the CPF
 * SSP (target DDL v4.0 Section 10; SRS-6-4, BR-CPF-039): purpose, amount and property details, and
 * its status as the member sees it. Its sanction ({@link PfSanction}) carries the chain. Every
 * status change is a {@link CpfClaimHistory} row.
 */
@Entity
@Table(name = "cpf_claim")
public class CpfClaim {

    /** {@code claim_status} (DDL CHECK). */
    public enum Status {
        SUBMITTED,
        DOCS_PENDING,
        UNDER_REVIEW,
        RETURNED,
        APPROVED,
        REJECTED,
        PAID,
        WITHDRAWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "claim_type", nullable = false, length = 30)
    private String claimType;

    @Column(name = "purpose_id")
    private Long purposeId;

    @Column(name = "sanction_id")
    private Long sanctionId;

    @Column(name = "claimed_amount")
    private BigDecimal claimedAmount;

    @Column(name = "property_address", length = 500)
    private String propertyAddress;

    @Column(name = "property_description", length = 1000)
    private String propertyDescription;

    @Column(name = "origin_channel", nullable = false, length = 15)
    private String originChannel;

    @Column(name = "claim_status", nullable = false, length = 20)
    private String claimStatus;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected CpfClaim() {}

    /** A request raised in Payroll eSeva. */
    @SuppressWarnings("java:S107") // the DDL row
    public static CpfClaim eseva(
            Long employeeId,
            String type,
            Long purposeId,
            Long sanctionId,
            BigDecimal amount,
            String address,
            String description,
            Instant at) {
        CpfClaim c = new CpfClaim();
        c.employeeId = employeeId;
        c.claimType = type;
        c.purposeId = purposeId;
        c.sanctionId = sanctionId;
        c.claimedAmount = amount;
        c.propertyAddress = address;
        c.propertyDescription = description;
        c.originChannel = "ESEVA";
        c.claimStatus = Status.SUBMITTED.name();
        c.submittedAt = at;
        return c;
    }

    /** The member resubmits a returned request with the corrected amount and property details. */
    public void resubmitted(BigDecimal amount, String address, String description) {
        claimedAmount = amount;
        propertyAddress = address;
        propertyDescription = description;
    }

    /** Moves to {@code next}; a final outcome records when it was decided. */
    public void status(Status next, Instant at) {
        claimStatus = next.name();
        if (next == Status.APPROVED || next == Status.REJECTED || next == Status.WITHDRAWN) {
            decidedAt = at;
        }
    }

    public Status status() {
        return Status.valueOf(claimStatus);
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getClaimType() {
        return claimType;
    }

    public Long getPurposeId() {
        return purposeId;
    }

    public Long getSanctionId() {
        return sanctionId;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }

    public String getPropertyAddress() {
        return propertyAddress;
    }

    public String getPropertyDescription() {
        return propertyDescription;
    }

    public String getOriginChannel() {
        return originChannel;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
