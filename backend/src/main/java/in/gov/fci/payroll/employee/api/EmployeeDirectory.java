package in.gov.fci.payroll.employee.api;

import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.platform.logging.PiiMasks;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee identity lookups for other modules (EIS number, record id, name, unit, status); no
 * restricted personal data.
 */
@Component
public class EmployeeDirectory {

    /** Oracle's limit of values in one IN list. */
    private static final int IN_LIMIT = 1000;

    private final EmployeeRepository employees;

    public EmployeeDirectory(EmployeeRepository employees) {
        this.employees = employees;
    }

    /** The record id of the employee with EIS number {@code eis}. */
    @Transactional(readOnly = true)
    public Optional<Long> idOf(String eis) {
        return eis == null
                ? Optional.empty()
                : employees.findByEmpNum(eis.strip()).map(Employee::getId);
    }

    /** The working location of employee record {@code employeeId} (the user's home unit). */
    @Transactional(readOnly = true)
    public Optional<Long> locationOf(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees.findById(employeeId).map(Employee::getLocationId);
    }

    /** Record ids of the employees working at {@code locations}; empty for no location. */
    @Transactional(readOnly = true)
    public java.util.Set<Long> idsAt(java.util.Collection<Long> locations) {
        return locations == null || locations.isEmpty()
                ? java.util.Set.of()
                : employees.idsAt(locations);
    }

    /** The EIS number of employee record {@code employeeId}. */
    @Transactional(readOnly = true)
    public Optional<String> eisOf(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees.findById(employeeId).map(Employee::getEmpNum);
    }

    /**
     * A member as another module shows it on a transaction: identity, unit and status, never PAN,
     * bank account or other restricted fields (SRS-SEC-4).
     *
     * @param employmentStatus EMPLOYMENT_STATUS list value (IN_SERVICE, SUSPENDED, SEPARATED, ...)
     */
    public record Member(
            Long id, String eis, String name, Long locationId, String employmentStatus) {}

    /** The member with record id {@code employeeId}. */
    @Transactional(readOnly = true)
    public Optional<Member> member(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees.findById(employeeId).map(EmployeeDirectory::member);
    }

    /**
     * The employee type of record {@code employeeId} (STAFF, DEPT_LABOUR, DPS_LABOUR or
     * NWNP_LABOUR): HRMS holds staff only, labour have no HRMS interface (SRS-INT-27).
     */
    @Transactional(readOnly = true)
    public Optional<String> employeeTypeOf(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees.findById(employeeId).map(Employee::getEmployeeType);
    }

    /** The member with EIS number {@code eis}. */
    @Transactional(readOnly = true)
    public Optional<Member> memberByEis(String eis) {
        return eis == null || eis.isBlank()
                ? Optional.empty()
                : employees.findByEmpNum(eis.strip()).map(EmployeeDirectory::member);
    }

    /**
     * The member holding the CPF code or the UAN (SRS I-22 field table l.2234, "Member (CPF Code /
     * UAN)"), both received from HRMS. Empty when none or more than one record holds it, never a
     * guess.
     */
    @Transactional(readOnly = true)
    public Optional<Member> memberByCpfCodeOrUan(String cpfCode, String uan) {
        List<Employee> found =
                cpfCode != null && !cpfCode.isBlank()
                        ? employees.findByCpfCode(cpfCode.strip())
                        : uan != null && !uan.isBlank()
                                ? employees.findByUanNumber(uan.strip())
                                : List.of();
        return found.size() == 1 ? Optional.of(member(found.get(0))) : Optional.empty();
    }

    /**
     * The CPF identity of a member as received from HRMS (BR-CPF-040, read-only in Payroll): the
     * CPF code, the parent zone holding the CPF Member ID and the provident fund the member belongs
     * to. No UAN, PAN or bank data.
     *
     * @param providentFundType CPF, GPF, NPS or NONE (derived from the pay pattern)
     */
    public record CpfIdentity(
            Long id,
            String eis,
            String cpfCode,
            Long parentZoneId,
            String providentFundType,
            String employmentStatus,
            Long locationId) {}

    /** The CPF identity of employee record {@code employeeId}. */
    @Transactional(readOnly = true)
    public Optional<CpfIdentity> cpfIdentity(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees.findById(employeeId).map(EmployeeDirectory::cpfIdentityOf);
    }

    /**
     * The CPF identities of many records at once (C-12 runs over a zone's ledgers); an unknown id
     * is absent from the map.
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, CpfIdentity> cpfIdentities(java.util.Collection<Long> ids) {
        java.util.Map<Long, CpfIdentity> out = new java.util.HashMap<>();
        forEachChunk(ids, e -> out.put(e.getId(), cpfIdentityOf(e)));
        return out;
    }

    /** The members of many records at once; an unknown id is absent from the map. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Member> members(java.util.Collection<Long> ids) {
        java.util.Map<Long, Member> out = new java.util.HashMap<>();
        forEachChunk(ids, e -> out.put(e.getId(), member(e)));
        return out;
    }

    /** Oracle refuses more than 1,000 values in one IN list. */
    private void forEachChunk(
            java.util.Collection<Long> ids, java.util.function.Consumer<Employee> each) {
        List<Long> all = ids == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(ids));
        for (int from = 0; from < all.size(); from += IN_LIMIT) {
            employees
                    .findByIdIn(all.subList(from, Math.min(all.size(), from + IN_LIMIT)))
                    .forEach(each);
        }
    }

    private static CpfIdentity cpfIdentityOf(Employee e) {
        return new CpfIdentity(
                e.getId(),
                e.getEmpNum(),
                e.getCpfCode(),
                e.getParentZoneId(),
                e.getProvidentFundType(),
                e.getEmploymentStatus(),
                e.getLocationId());
    }

    /**
     * What the CPF withdrawal chain checks of a member (SRS-6-4, SRS-6-5, SRS-6-59; all received
     * from HRMS): the EPS date of joining (membership years), retirement and separation dates, and
     * whether a UAN and an Aadhaar (KYC) are on record - never the numbers (SRS-6-51).
     */
    public record CpfEligibility(
            Long id,
            LocalDate epsJoinDate,
            LocalDate retirementDate,
            LocalDate separationDate,
            boolean uanOnRecord,
            boolean kycOnRecord) {}

    /** The CPF eligibility data of employee record {@code employeeId}. */
    @Transactional(readOnly = true)
    public Optional<CpfEligibility> cpfEligibility(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees
                        .findById(employeeId)
                        .map(
                                e ->
                                        new CpfEligibility(
                                                e.getId(),
                                                e.getEpsJoinDate(),
                                                e.getRetirementDate(),
                                                e.getSeparationDate(),
                                                e.hasUan(),
                                                e.hasAadhaar()));
    }

    /**
     * What the Unit CPF Administrator verifies at tier 1 of the withdrawal chain (SRS-6-4; SRS I-04
     * field table "UAN: Verified by the Unit CPF Administrator"), both received from HRMS: the UAN,
     * and the Aadhaar as its last four digits only (SRS-6-51, BR-SEC-001); null when not on record.
     * The full Aadhaar never leaves this module.
     */
    public record CpfKyc(String uan, String aadhaarMasked) {}

    /** The KYC of employee record {@code employeeId} as the CPF chain may show it. */
    @Transactional(readOnly = true)
    public Optional<CpfKyc> cpfKyc(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees
                        .findById(employeeId)
                        .map(e -> new CpfKyc(e.uanOrNull(), e.aadhaarLastFourMasked()));
    }

    /** The UAN and masked Aadhaar of many members at once (a register page); unknown ids absent. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, CpfKyc> cpfKycs(java.util.Collection<Long> ids) {
        java.util.Map<Long, CpfKyc> out = new java.util.HashMap<>();
        forEachChunk(
                ids, e -> out.put(e.getId(), new CpfKyc(e.uanOrNull(), e.aadhaarLastFourMasked())));
        return out;
    }

    /**
     * Locks the member's record for the rest of the caller's transaction, so that two requests of
     * one member (two tabs, a double click) are checked one after the other against the counts the
     * other has changed (SRS-6-55, BR-CPF-010). Only inside a transaction: a lock outside one ends
     * at once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockMember(Long employeeId) {
        employees.lockById(employeeId);
    }

    /** Whether a member's PAN (HRMS) is on record and of the PAN structure (SRS-7-13). */
    public enum PanStatus {
        VALID,
        INVALID,
        MISSING
    }

    private static final java.util.regex.Pattern PAN =
            java.util.regex.Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    /**
     * SRS-7-13: the state of the member's PAN, never the number (tax screens show it masked,
     * SRS-7-23). Structure only (five letters, four digits, a letter); the tax department's own
     * check is the return validation utility.
     */
    @Transactional(readOnly = true)
    public Optional<PanStatus> panStatus(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees.findById(employeeId).map(e -> panStatusOf(e.getPanNumber()));
    }

    /**
     * SRS l.2589 / SRS-7-23: a member's PAN as a tax register may show it - the last four
     * characters only - with its state (a missing or invalid PAN is shown as the exception).
     */
    public record MaskedPan(String masked, PanStatus status) {}

    /** The masked PAN and its state of many members at once (a register page); unknown absent. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, MaskedPan> maskedPans(java.util.Collection<Long> ids) {
        java.util.Map<Long, MaskedPan> out = new java.util.HashMap<>();
        forEachChunk(ids, e -> out.put(e.getId(), maskedPanOf(e.getPanNumber())));
        return out;
    }

    /**
     * The PAN masked the one way every module shows it ({@link PiiMasks#pan}: the last four behind
     * six asterisks, a value that is not ten characters shows none of itself, a missing one none;
     * R-PII, SRS l.754 / l.802, BMAD TR-035 follow-up) with its state.
     */
    public static MaskedPan maskedPanOf(String pan) {
        return new MaskedPan(PiiMasks.pan(pan), panStatusOf(pan));
    }

    /** The PAN state of a value as HRMS holds it. */
    public static PanStatus panStatusOf(String pan) {
        if (pan == null || pan.isBlank()) {
            return PanStatus.MISSING;
        }
        return PAN.matcher(pan.strip()).matches() ? PanStatus.VALID : PanStatus.INVALID;
    }

    /**
     * The PAN in full, for the statutory return or certificate that prescribes it and only there
     * (SRS-7-23: Form 24Q deductee detail, Form 16). Never shown on a screen or written to a log.
     */
    @Transactional(readOnly = true)
    public Optional<String> panForStatutoryOutput(Long employeeId) {
        return employeeId == null
                ? Optional.empty()
                : employees.findById(employeeId).map(Employee::getPanNumber).map(String::strip);
    }

    private static Member member(Employee e) {
        return new Member(
                e.getId(), e.getEmpNum(), e.fullName(), e.getLocationId(), e.getEmploymentStatus());
    }
}
