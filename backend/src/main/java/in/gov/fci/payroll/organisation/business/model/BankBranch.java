package in.gov.fci.payroll.organisation.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code bank_branch} (target DDL S02). */
@Entity
@Table(name = "bank_branch")
public class BankBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "branch_id")
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "ifsc_code", nullable = false, length = 15)
    private String ifscCode;

    @Column(name = "branch_name", nullable = false, length = 200)
    private String name;

    protected BankBranch() {}

    public Long getId() {
        return id;
    }

    public Long getBankId() {
        return bankId;
    }

    public String getIfscCode() {
        return ifscCode;
    }

    public String getName() {
        return name;
    }
}
