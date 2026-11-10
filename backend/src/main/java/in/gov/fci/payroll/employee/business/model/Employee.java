package in.gov.fci.payroll.employee.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hibernate.type.YesNoConverter;

/**
 * {@code employee} (target DDL S03), the attributes M-01 and eSeva use. BR-EMPM-001: records arrive
 * only through the HRMS interface; Payroll changes a Payroll-owned attribute only through an
 * approved {@code approval_queue} item ({@link #applyApprovedChange}).
 */
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "employee_id")
    private Long id;

    @Column(name = "emp_num", nullable = false, length = 20)
    private String empNum;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    // Oracle review F5: birth date, PAN, Basic and bank account change only through their JDBC
    // flows (I-21 HrmsChangeApplier, the increment / pay-fixation BasicPayService); the entity has
    // no
    // mutator for them, so a JPA save never writes back a value it read before such a change.
    @Column(name = "birth_date", nullable = false, updatable = false)
    private LocalDate birthDate;

    @Column(name = "employee_type", nullable = false, length = 20)
    private String employeeType;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "emp_lbr_flag", nullable = false, length = 1)
    private Boolean labour;

    @Column(name = "pan_number", length = 20, updatable = false)
    private String panNumber;

    @Column(name = "designation_id")
    private Long designationId;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "depot_location_id")
    private Long depotLocationId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "band_id")
    private Long bandId;

    @Column(name = "pay_pattern", length = 10)
    private String payPattern;

    @Column(name = "provident_fund_type", length = 10)
    private String providentFundType;

    @Column(name = "cpf_code", length = 20)
    private String cpfCode;

    @Column(name = "parent_zone_id")
    private Long parentZoneId;

    @Column(name = "employment_status", nullable = false, length = 25)
    private String employmentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_status", nullable = false, length = 25)
    private RecordStatus recordStatus;

    @Column(name = "current_basic", precision = 12, scale = 2, updatable = false)
    private BigDecimal currentBasic;

    @Column(name = "mobile_number", length = 20)
    private String mobileNumber;

    @Column(name = "email_address", length = 150)
    private String emailAddress;

    @Column(name = "bank_account_number", length = 30, updatable = false)
    private String bankAccountNumber;

    @Column(name = "bank_branch_id")
    private Long bankBranchId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Received from HRMS (target DDL v4.0 S03, BR-CPF-040): read-only in Payroll, never keyed here.
    @Column(name = "hrms_employee_type", length = 30, insertable = false, updatable = false)
    private String hrmsEmployeeType;

    @Column(name = "eps_number", length = 30, insertable = false, updatable = false)
    private String epsNumber;

    @Column(name = "eps_join_date", insertable = false, updatable = false)
    private LocalDate epsJoinDate;

    @Column(name = "uan_number", length = 20, insertable = false, updatable = false)
    private String uanNumber;

    @Column(name = "aadhaar_number", length = 20, insertable = false, updatable = false)
    private String aadhaarNumber;

    @Column(name = "retirement_date", insertable = false, updatable = false)
    private LocalDate retirementDate;

    @Column(name = "separation_date", insertable = false, updatable = false)
    private LocalDate separationDate;

    @Column(name = "epfo_member_id", length = 30, insertable = false, updatable = false)
    private String epfoMemberId;

    @Column(name = "pran_number", length = 12, insertable = false, updatable = false)
    private String pranNumber;

    @Column(name = "cda_staff_code", length = 20, insertable = false, updatable = false)
    private String cdaStaffCode;

    // SRS-1-52 / M-01 field table (US-GAP-40): further HRMS-sourced and control fields, read-only
    // here (the control flags change through their own approved flows, never through this entity).
    @Column(name = "gender", length = 10, insertable = false, updatable = false)
    private String gender;

    @Column(name = "comp_joining_date", insertable = false, updatable = false)
    private LocalDate joiningDate;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "hold_flag", length = 1, insertable = false, updatable = false)
    private Boolean holdFlag;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "absconding_flag", length = 1, insertable = false, updatable = false)
    private Boolean abscondingFlag;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "stop_payment_flag", length = 1, insertable = false, updatable = false)
    private Boolean stopPaymentFlag;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "casual_labour_flag", length = 1, insertable = false, updatable = false)
    private Boolean casualLabour;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "eps_pre2014_member_flag", length = 1, insertable = false, updatable = false)
    private Boolean epsPre2014Member;

    @Column(name = "eps_entitlement_date", insertable = false, updatable = false)
    private LocalDate epsEntitlementDate;

    @Column(name = "eps_separation_reason", length = 40, insertable = false, updatable = false)
    private String epsSeparationReason;

    @Column(name = "eps_separation_date", insertable = false, updatable = false)
    private LocalDate epsSeparationDate;

    protected Employee() {}

    /** An approved I-17 event's effect on the employment status (EMPLOYMENT_STATUS list). */
    public void applyEmploymentStatus(String status, Instant now) {
        this.employmentStatus = Objects.requireNonNull(status);
        this.updatedAt = now;
    }

    /**
     * An approved M-11 transfer: the employee now works at {@code toLocationId}; the staff number
     * is reissued if a new one is given. The CPF parent zone never changes (BR-CPF-020,
     * BR-CPF-038).
     */
    public void applyTransfer(Long toLocationId, String newEmpNum, Instant now) {
        this.locationId = Objects.requireNonNull(toLocationId);
        if (newEmpNum != null && !newEmpNum.isBlank()) {
            this.empNum = newEmpNum.strip();
        }
        this.updatedAt = now;
    }

    public String getHrmsEmployeeType() {
        return hrmsEmployeeType;
    }

    public String getEpsNumber() {
        return epsNumber;
    }

    public LocalDate getEpsJoinDate() {
        return epsJoinDate;
    }

    /** A UAN is on record (the number itself never leaves the module: SRS-6-51). */
    public boolean hasUan() {
        return uanNumber != null && !uanNumber.isBlank();
    }

    /** An Aadhaar is on record for KYC (the number itself never leaves the module: SRS-6-51). */
    public boolean hasAadhaar() {
        return aadhaarNumber != null && !aadhaarNumber.isBlank();
    }

    /** The UAN as received from HRMS, or null when none is on record. */
    public String uanOrNull() {
        return hasUan() ? uanNumber.strip() : null;
    }

    /**
     * SRS-6-51 (BR-SEC-001): the Aadhaar as its last four digits only ({@code XXXX-XXXX-1234}, the
     * masking of the logs), or null when none is on record or it is too short to mask safely.
     */
    public String aadhaarLastFourMasked() {
        if (!hasAadhaar()) {
            return null;
        }
        String digits = aadhaarNumber.replaceAll("\\D", "");
        return digits.length() < 12 ? null : "XXXX-XXXX-" + digits.substring(digits.length() - 4);
    }

    public LocalDate getRetirementDate() {
        return retirementDate;
    }

    public LocalDate getSeparationDate() {
        return separationDate;
    }

    public String getEpfoMemberId() {
        return epfoMemberId;
    }

    public String getPranNumber() {
        return pranNumber;
    }

    public String getCdaStaffCode() {
        return cdaStaffCode;
    }

    public String getGender() {
        return gender;
    }

    public LocalDate getJoiningDate() {
        return joiningDate;
    }

    public boolean isHoldFlag() {
        return Boolean.TRUE.equals(holdFlag);
    }

    public boolean isAbscondingFlag() {
        return Boolean.TRUE.equals(abscondingFlag);
    }

    public boolean isStopPaymentFlag() {
        return Boolean.TRUE.equals(stopPaymentFlag);
    }

    public boolean isCasualLabour() {
        return Boolean.TRUE.equals(casualLabour);
    }

    /** M-01 EPS Details: a previous EPS member before 2014 (null when HRMS sent nothing). */
    public Boolean getEpsPre2014Member() {
        return epsPre2014Member;
    }

    public LocalDate getEpsEntitlementDate() {
        return epsEntitlementDate;
    }

    public String getEpsSeparationReason() {
        return epsSeparationReason;
    }

    public LocalDate getEpsSeparationDate() {
        return epsSeparationDate;
    }

    /** First, middle and last name, as the payroll master shows it. */
    public String fullName() {
        return Stream.of(firstName, middleName, lastName)
                .filter(Objects::nonNull)
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining(" "));
    }

    /** {@code R. Sharma}: the short form of the live M-01 list. */
    public String shortName() {
        return lastName == null ? firstName : firstName.charAt(0) + ". " + lastName;
    }

    /**
     * The status line of M-01, derived from the record (never stored): setup and change states
     * first, then data-quality flags.
     */
    public String displayStatus() {
        if (recordStatus == RecordStatus.PAYROLL_SETUP_PENDING) {
            return "PAYROLL_SETUP_PENDING";
        }
        if (recordStatus == RecordStatus.CHANGE_PENDING) {
            return "CHANGE_PENDING";
        }
        if (panNumber == null || (!isLabour() && cpfCode == null)) {
            return "PAN_CPF_MISSING";
        }
        if (bankBranchId == null) {
            return "BANK_INVALID";
        }
        return "ACTIVE";
    }

    /**
     * Clears the pending-change status a retired M-01 change request left (plan 16 D16-19): the
     * checker's decision on such an item ends it, and nothing of the request is applied.
     */
    public void clearChangePending(Instant now) {
        if (recordStatus == RecordStatus.CHANGE_PENDING) {
            this.recordStatus = RecordStatus.ACTIVE;
            this.updatedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public String getEmpNum() {
        return empNum;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getEmployeeType() {
        return employeeType;
    }

    public boolean isLabour() {
        return Boolean.TRUE.equals(labour);
    }

    public String getPanNumber() {
        return panNumber;
    }

    public Long getDesignationId() {
        return designationId;
    }

    public Long getLocationId() {
        return locationId;
    }

    public Long getDepotLocationId() {
        return depotLocationId;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public Long getBandId() {
        return bandId;
    }

    public String getPayPattern() {
        return payPattern;
    }

    public String getProvidentFundType() {
        return providentFundType;
    }

    public String getCpfCode() {
        return cpfCode;
    }

    public Long getParentZoneId() {
        return parentZoneId;
    }

    public String getEmploymentStatus() {
        return employmentStatus;
    }

    public RecordStatus getRecordStatus() {
        return recordStatus;
    }

    public BigDecimal getCurrentBasic() {
        return currentBasic;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public String getBankAccountNumber() {
        return bankAccountNumber;
    }

    public Long getBankBranchId() {
        return bankBranchId;
    }

    public Long getRowVersion() {
        return rowVersion;
    }
}
