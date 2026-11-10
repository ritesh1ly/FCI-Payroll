package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.employee.business.PayStatusRules;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.business.model.RecordStatus;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.api.OrgMasters;
import in.gov.fci.payroll.organisation.business.model.OrgDesignation;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.PayScaleBand;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.Likes;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee master (M-01), the employee's own eSeva view (E-01) and the consolidated head-count MIS
 * (R-01). Every read is bounded by the session's data scope in the query itself; a record outside
 * it answers 403, never its content. Changes of Payroll-owned attributes go through maker-checker
 * and only reach the record when approved.
 */
@Service
@Loggable
public class EmployeeService {

    public record EmployeeRow(
            String eis,
            String name,
            String designation,
            String band,
            String location,
            String payPattern,
            String employeeType,
            String status,
            // Wireframes v6.1 M-01 columns (OD-121): pay scale code, basic, HRA city class of the
            // posting, CPF code and bank code. The drawn PAN column stays without data: the
            // register carries no PAN, masked or not; the dossier shows it masked (SRS-SEC-4,
            // SRS-1-56, WF-226).
            String payScale,
            BigDecimal basic,
            String city,
            String cpfCode,
            String bank) {}

    public record EmployeeDetail(
            String eis,
            String firstName,
            String lastName,
            String designation,
            String band,
            String location,
            String locationCode,
            String payPattern,
            String employeeType,
            String providentFundType,
            String panMasked,
            String cpfCode,
            String mobileNumber,
            String emailAddress,
            String bankAccountMasked,
            BigDecimal currentBasic,
            String status,
            String employmentStatus,
            boolean changePending,
            // SRS-13-1 (US-GAP-51): read-only, Calculated by Increment & Pay Fixation (I-05).
            java.time.LocalDate nextIncrementDue,
            // SRS-1-52 / M-01 field table (US-GAP-40): HRMS-sourced, read-only.
            java.time.LocalDate birthDate,
            java.time.LocalDate joiningDate,
            java.time.LocalDate retirementDate,
            String gender,
            String employeeCategory,
            String cdaStaffCode,
            // SRS-1-54 / BR-SEC-001: the last four digits only.
            String aadhaarMasked,
            String pranNumber,
            // SRS-6-51: whether a UAN is on record; the number itself is not shown.
            boolean uanOnRecord,
            // SRS-1-76: EPS number, date of joining, EPFO Member ID and the EPS details.
            String epsNumber,
            java.time.LocalDate epsJoinDate,
            String epfoMemberId,
            Boolean epsPre2014Member,
            java.time.LocalDate epsEntitlementDate,
            String epsSeparationReason,
            java.time.LocalDate epsSeparationDate,
            // M-01 Control: Hold and Stop-Payment (Enterable, own flow), Absconding (HRMS), Casual
            // Labour Indicator (HRMS).
            boolean holdFlag,
            boolean abscondingFlag,
            boolean stopPaymentFlag,
            boolean casualLabour,
            // M-01 HRA & Accommodation and DA Rate Applied: derived from the place of posting
            // (Location Master) and the rule engine's rates in force today, for the pay pattern;
            // null when not derivable or not configured (never a guess).
            String hraCityClass,
            BigDecimal daRateApplied,
            BigDecimal hraRateApplied,
            // M-01 Pay Status (SRS l.3738, US-GAP-275; OD-53): System, derived, read-only.
            String payStatus,
            // SRS-G-53 (NEW-G1-07): set when the session reads the record as the previous unit
            // (the date of the transfer out of it); the record is then read-only for it.
            java.time.LocalDate transferredOut) {}

    public record LocationCount(
            String location, String locationCode, Map<String, Long> byStatus, long total) {}

    /**
     * R-01 one page of the offices with the column totals over every office of the scope (not over
     * the page): the shared page shape plus {@code totals} and {@code grandTotal}.
     */
    public record HeadCountPage(
            List<LocationCount> items,
            int page,
            int size,
            long total,
            Map<String, Long> totals,
            long grandTotal) {}

    private final EmployeeRepository employees;
    private final OrgMasters masters;
    private final OrgDirectory org;
    private final Authorization policy;
    private final in.gov.fci.payroll.employee.api.IncrementDue increments;
    private final in.gov.fci.payroll.configuration.api.PayConfiguration rates;
    private final java.time.Clock clock;
    private final PreviousUnitAccess previousUnit;

    @org.springframework.beans.factory.annotation.Autowired
    public EmployeeService(
            EmployeeRepository employees,
            OrgMasters masters,
            OrgDirectory org,
            Authorization policy,
            in.gov.fci.payroll.employee.api.IncrementDue increments,
            in.gov.fci.payroll.configuration.api.PayConfiguration rates,
            java.time.Clock clock,
            PreviousUnitAccess previousUnit) {
        this.previousUnit = previousUnit;
        this.employees = employees;
        this.masters = masters;
        this.org = org;
        this.policy = policy;
        this.increments = increments;
        this.rates = rates;
        this.clock = clock;
    }

    /** Without the previous unit's read access (SRS-G-53): the present posting's scope only. */
    public EmployeeService(
            EmployeeRepository employees,
            OrgMasters masters,
            OrgDirectory org,
            Authorization policy,
            in.gov.fci.payroll.employee.api.IncrementDue increments,
            in.gov.fci.payroll.configuration.api.PayConfiguration rates,
            java.time.Clock clock) {
        this(employees, masters, org, policy, increments, rates, clock, null);
    }

    /** Without the rule engine's rates (no DA / HRA rate applied shown). */
    public EmployeeService(
            EmployeeRepository employees,
            OrgMasters masters,
            OrgDirectory org,
            Authorization policy,
            in.gov.fci.payroll.employee.api.IncrementDue increments) {
        this(employees, masters, org, policy, increments, null, java.time.Clock.systemUTC());
    }

    /** Without the increment module (no next due date shown). */
    public EmployeeService(
            EmployeeRepository employees,
            OrgMasters masters,
            OrgDirectory org,
            Authorization policy) {
        this(employees, masters, org, policy, id -> java.util.Optional.empty());
    }

    /**
     * SRS-1-11, SRS-NF-3: one page of the M-01 list in the data scope, filtered on the server by
     * text, pay pattern and status line ({@code status} null for all; the values of {@link
     * Employee#displayStatus()}).
     */
    @Transactional(readOnly = true)
    public Paged<EmployeeRow> list(
            AccessContext context, String text, String payPattern, String status, PageSpec spec) {
        return list(context, text, payPattern, status, null, spec);
    }

    /** The list narrowed to one office of the scope and those below it ({@code office} code). */
    @Transactional(readOnly = true)
    public Paged<EmployeeRow> list(
            AccessContext context,
            String text,
            String payPattern,
            String status,
            String office,
            PageSpec spec) {
        policy.require(context, "M-01", "VIEW");
        java.util.Set<Long> scope = narrowed(context, office);
        if (scope.isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        Page<Employee> found =
                employees.search(
                        scope,
                        contains(text),
                        blankToNull(payPattern),
                        blankToNull(status),
                        // Oracle review F3: emp_num is unique per parent zone only; the id ends the
                        // order, so pages neither repeat nor skip a member.
                        spec.pageable(Sort.by("empNum").and(Sort.by("id"))));
        Lookups lookups = lookups();
        return Paged.of(found).map(e -> row(e, lookups));
    }

    /**
     * SRS-1-11, SRS-NF-3: the M-01 tiles over the whole filtered scope (the status filter aside).
     */
    public record StatusCounts(
            long total,
            long payrollSetupPending,
            long changePending,
            long panCpfMissing,
            long bankInvalid,
            long active) {}

    @Transactional(readOnly = true)
    public StatusCounts statusCounts(AccessContext context, String text, String payPattern) {
        return statusCounts(context, text, payPattern, null);
    }

    /** The tiles of one office of the scope and those below it ({@code office} code). */
    @Transactional(readOnly = true)
    public StatusCounts statusCounts(
            AccessContext context, String text, String payPattern, String office) {
        policy.require(context, "M-01", "VIEW");
        java.util.Set<Long> scope = narrowed(context, office);
        if (scope.isEmpty()) {
            return new StatusCounts(0, 0, 0, 0, 0, 0);
        }
        Object[] row =
                employees.statusCounts(scope, contains(text), blankToNull(payPattern)).get(0);
        return new StatusCounts(
                count(row[0]),
                count(row[1]),
                count(row[2]),
                count(row[3]),
                count(row[4]),
                count(row[5]));
    }

    /**
     * The session's data scope, cut to {@code office} and the offices below it. An office outside
     * the scope or unknown gives nothing: a filter never widens the scope (R-RBAC).
     */
    private java.util.Set<Long> narrowed(AccessContext context, String office) {
        java.util.Set<Long> scope = context.dataScope();
        if (office == null || office.isBlank()) {
            return scope;
        }
        java.util.Set<Long> within = new java.util.HashSet<>(scope);
        within.retainAll(
                org.byCode(office.strip())
                        .map(l -> org.subtree(l.getId()))
                        .orElse(java.util.Set.of()));
        return within;
    }

    /** A sum over no rows is null. */
    private static long count(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Transactional(readOnly = true)
    public EmployeeDetail get(AccessContext context, String eis) {
        Employee employee = find(eis);
        // SRS-G-53: a unit the member was transferred out of keeps read-only access (VIEW only).
        java.util.Optional<java.time.LocalDate> transferredOut =
                previousUnit == null
                        ? java.util.Optional.empty()
                        : previousUnit.transferredOut(
                                context, employee.getId(), employee.getLocationId());
        if (transferredOut.isPresent()) {
            policy.require(context, "M-01", "VIEW");
            return detail(employee, lookups(), transferredOut.get());
        }
        policy.requireEmployeeRecord(
                context, "M-01", "VIEW", employee.getId(), employee.getLocationId());
        return detail(employee, lookups(), null);
    }

    /** E-01: the signed-in employee's own record only. */
    @Transactional(readOnly = true)
    public EmployeeDetail own(AccessContext context) {
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", "SELF"}, List.of(), null);
        }
        Employee employee =
                employees
                        .findById(context.employeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireEmployeeRecord(
                context, "E-01", "SELF", employee.getId(), employee.getLocationId());
        return detail(employee, lookups(), null);
    }

    /** R-01: head count per location and status in the session's scope (VIEW only). */
    @Transactional(readOnly = true)
    public List<LocationCount> headCount(AccessContext context) {
        policy.require(context, "R-01", "VIEW");
        if (context.dataScope().isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Long>> counts = new LinkedHashMap<>();
        for (Object[] row : employees.countByLocationAndStatus(context.dataScope())) {
            Long location = (Long) row[0];
            counts.computeIfAbsent(location, k -> new LinkedHashMap<>())
                    .put(((RecordStatus) row[1]).name(), (Long) row[2]);
        }
        return org.locations(counts.keySet()).stream()
                .sorted((a, b) -> a.getCode().compareTo(b.getCode()))
                .map(
                        l -> {
                            Map<String, Long> byStatus = counts.get(l.getId());
                            Map<RecordStatus, Long> ordered = new EnumMap<>(RecordStatus.class);
                            byStatus.forEach((k, v) -> ordered.put(RecordStatus.valueOf(k), v));
                            Map<String, Long> view = new LinkedHashMap<>();
                            ordered.forEach((k, v) -> view.put(k.name(), v));
                            return new LocationCount(
                                    l.displayLabel(),
                                    l.getCode(),
                                    view,
                                    byStatus.values().stream().mapToLong(Long::longValue).sum());
                        })
                .toList();
    }

    /** R-01 one page of the offices, with the totals over all of them (X-pagination). */
    @Transactional(readOnly = true)
    public HeadCountPage headCount(AccessContext context, PageSpec spec) {
        List<LocationCount> all = headCount(context);
        Map<String, Long> totals = new java.util.TreeMap<>();
        all.forEach(c -> c.byStatus().forEach((k, v) -> totals.merge(k, v, Long::sum)));
        Paged<LocationCount> page = Paged.slice(all, spec);
        return new HeadCountPage(
                page.items(),
                page.page(),
                page.size(),
                page.total(),
                totals,
                all.stream().mapToLong(LocationCount::total).sum());
    }

    private static String contains(String text) {
        return text == null || text.isBlank()
                ? null
                : "%" + Likes.escape(text.strip().toLowerCase(Locale.ROOT)) + "%";
    }

    private Employee find(String eis) {
        return employees
                .findByEmpNum(eis == null ? "" : eis.strip())
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private record Lookups(
            Map<Long, OrgDesignation> designations,
            Map<Long, PayScaleBand> bands,
            Map<Long, String> banks) {}

    private Lookups lookups() {
        return new Lookups(
                masters.designationsById(), masters.payBandsById(), masters.bankCodesByBranch());
    }

    private EmployeeRow row(Employee e, Lookups lookups) {
        OrgLocation posting = org.location(e.getLocationId()).orElse(null);
        PayScaleBand band = lookups.bands().get(e.getBandId());
        return new EmployeeRow(
                e.getEmpNum(),
                e.fullName(),
                name(lookups.designations().get(e.getDesignationId())),
                bandName(band),
                posting == null ? null : posting.displayLabel(),
                e.getPayPattern(),
                e.getEmployeeType(),
                e.displayStatus(),
                band == null ? null : band.getCode(),
                e.getCurrentBasic(),
                posting == null ? null : posting.getCityClass(),
                e.getCpfCode(),
                e.getBankBranchId() == null ? null : lookups.banks().get(e.getBankBranchId()));
    }

    private EmployeeDetail detail(Employee e, Lookups lookups, java.time.LocalDate transferredOut) {
        OrgLocation location = org.location(e.getLocationId()).orElse(null);
        // M-01 DA / HRA Rate Applied (Rule Engine, M-04): the staff rates in force on today's
        // business day (IST), as the engine resolves them for a period.
        in.gov.fci.payroll.configuration.api.PayConfiguration.Snapshot snapshot =
                rates == null
                        ? null
                        : rates.staffConfiguration(
                                java.time.LocalDate.now(
                                        clock.withZone(java.time.ZoneId.of("Asia/Kolkata"))));
        return new EmployeeDetail(
                e.getEmpNum(),
                e.getFirstName(),
                e.getLastName(),
                name(lookups.designations().get(e.getDesignationId())),
                bandName(lookups.bands().get(e.getBandId())),
                location == null ? null : location.displayLabel(),
                location == null ? null : location.getCode(),
                e.getPayPattern(),
                e.getEmployeeType(),
                e.getProvidentFundType(),
                maskPan(e.getPanNumber()),
                e.getCpfCode(),
                e.getMobileNumber(),
                e.getEmailAddress(),
                EmployeeApprovalEffect.mask("bankAccount", e.getBankAccountNumber()),
                e.getCurrentBasic(),
                e.displayStatus(),
                e.getEmploymentStatus(),
                e.getRecordStatus() == RecordStatus.CHANGE_PENDING,
                e.getId() == null ? null : increments.nextDue(e.getId()).orElse(null),
                e.getBirthDate(),
                e.getJoiningDate(),
                e.getRetirementDate(),
                e.getGender(),
                e.getHrmsEmployeeType(),
                e.getCdaStaffCode(),
                e.aadhaarLastFourMasked(),
                e.getPranNumber(),
                e.hasUan(),
                e.getEpsNumber(),
                e.getEpsJoinDate(),
                e.getEpfoMemberId(),
                e.getEpsPre2014Member(),
                e.getEpsEntitlementDate(),
                e.getEpsSeparationReason(),
                e.getEpsSeparationDate(),
                e.isHoldFlag(),
                e.isAbscondingFlag(),
                e.isStopPaymentFlag(),
                e.isCasualLabour(),
                location == null ? null : location.getCityClass(),
                snapshot == null ? null : snapshot.da(e.getPayPattern()).orElse(null),
                snapshot == null || location == null
                        ? null
                        : snapshot.hra(e.getPayPattern(), location.getCityClass()).orElse(null),
                PayStatusRules.of(e.getEmploymentStatus(), e.isHoldFlag()).name(),
                transferredOut);
    }

    private static String name(OrgDesignation d) {
        return d == null ? null : d.getName();
    }

    private static String bandName(PayScaleBand b) {
        return b == null ? null : b.getCode();
    }

    /**
     * PAN shown by its last four characters only (SRS l.754 / l.802, BMAD TR-035): the shared
     * {@link in.gov.fci.payroll.platform.logging.PiiMasks#pan}; a value that is not a ten-character
     * PAN is never shown as it is.
     */
    static String maskPan(String pan) {
        return in.gov.fci.payroll.platform.logging.PiiMasks.pan(pan);
    }
}
