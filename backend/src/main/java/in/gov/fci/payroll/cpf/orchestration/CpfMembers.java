package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CPF member behind a ledger (SRS-6-59, BR-CPF-040): the CPF code and parent zone are received
 * from HRMS and read-only in Payroll; a member without them gets no CPF posting, and the gap is
 * raised on the Exceptions &amp; Holds worklist (X-05) where it is followed up, then cleared when
 * HRMS supplies it. Within staff the code is unique by {@code uk_employee_cpf_code}; against labour
 * by the DDL's cross-uniqueness triggers once {@code labour_profile} exists (module 20).
 */
@Service
public class CpfMembers {

    /** The finding module on X-05. */
    static final String MODULE = "CPF";

    /** The X-05 condition of a member without a CPF code or parent zone from HRMS. */
    static final String CODE_MISSING = "CPF_CODE_MISSING";

    /** The fund whose members have a CPF ledger. */
    static final String CPF_FUND = "CPF";

    /** A CPF member as the ledger needs it. */
    public record Member(
            Long employeeId, String eis, String cpfCode, Long zoneId, Long locationId) {}

    private final EmployeeDirectory directory;
    private final CpfExceptions exceptions;

    public CpfMembers(EmployeeDirectory directory, CpfExceptions exceptions) {
        this.directory = directory;
        this.exceptions = exceptions;
    }

    /**
     * SRS-6-59: the member, or a refusal - not found, not a CPF member, or (raised on X-05 first)
     * no CPF code or parent zone from HRMS. The X-05 entry is raised in its own transaction, so it
     * survives the refusal and the rollback of the posting that found it. A refusal never marks the
     * caller's transaction for rollback: a life-cycle listener catches it for a member who is not a
     * CPF member, and the event's own approval must not fail.
     */
    @Transactional(readOnly = true, noRollbackFor = FciException.class)
    public Member require(Long employeeId, String actor) {
        EmployeeDirectory.CpfIdentity identity =
                directory
                        .cpfIdentity(employeeId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (!CPF_FUND.equals(identity.providentFundType())) {
            throw new FciException(CpfErrorCode.NOT_A_CPF_MEMBER);
        }
        boolean noCode = identity.cpfCode() == null || identity.cpfCode().isBlank();
        if (noCode || identity.parentZoneId() == null) {
            String field = noCode ? "cpf_code" : "parent_zone_id";
            exceptions.raise(
                    actor,
                    new ExceptionWorklist.NewEntry(
                            null,
                            employeeId,
                            MODULE,
                            CODE_MISSING,
                            "BLOCKING",
                            null,
                            null,
                            "employee",
                            field,
                            "HRMS",
                            "M-01",
                            "No CPF "
                                    + (noCode ? "code" : "parent zone")
                                    + " received from HRMS: no CPF is deducted or posted until it"
                                    + " is (SRS-6-59)."));
            throw new FciException(CpfErrorCode.CPF_CODE_MISSING);
        }
        return new Member(
                identity.id(),
                identity.eis(),
                identity.cpfCode(),
                identity.parentZoneId(),
                identity.locationId());
    }
}
