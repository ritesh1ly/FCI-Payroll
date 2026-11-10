package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.LabourRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.api.OrgMasters;
import in.gov.fci.payroll.organisation.business.model.OrgDesignation;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.logging.PiiMasks;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M-02 Labour Master (plan 20 phase 20a; SRS-2-27, SRS-2-30, SRS-2-2, SRS-3-11; BR-LABM-004,
 * BR-LABM-010; LC-1 … LC-11; D20-1, D20-3). Labour are kept in Payroll, not HRMS: the Establishment
 * / HR Maker (or the System Administrator) enters or amends a labour member at a depot of the
 * user's offices and the Payroll Checker of that office approves (route M02_LABOUR; never the
 * maker). On approval the labour code is generated, the person written through {@code
 * LabourPersons} and the {@code labour_profile} recorded; the retirement date follows the date of
 * birth (A-07 RETIREMENT_AGE). Designation changes go through the designation-change action and
 * depot changes through a transfer (phase 20e): an amendment keeps both. PAN, CPF code and UAN are
 * unique.
 */
@Service
@Loggable
public class LabourMasterService {

    static final String SCREEN = "M-02";
    static final String ENTITY = "labour_profile";
    static final String ROUTE = "M02_LABOUR";

    /** What the maker enters (M-02 field table). */
    public record Request(
            String payrollType,
            String firstName,
            String lastName,
            String fatherName,
            String gender,
            LocalDate birthDate,
            LocalDate joiningDate,
            Long designationId,
            Long depotLocationId,
            String panNumber,
            String ifsc,
            String bankAccount,
            String mobile,
            Boolean pfApplicable,
            String cpfCode,
            String uanNumber,
            Boolean casualLabour,
            String reason) {}

    /**
     * One labour member as the M-02 amendment form opens it (SRS-2-30: the maker edits the record
     * as it stands). R-PII: the PAN, the bank account and the mobile number are masked; the form
     * leaves them empty and an empty one is kept.
     */
    public record Detail(
            long employeeId,
            String labourCode,
            String payrollType,
            String firstName,
            String lastName,
            String fatherName,
            String gender,
            LocalDate birthDate,
            LocalDate joiningDate,
            Long designationId,
            Long depotLocationId,
            String panMasked,
            String ifsc,
            String bankAccountMasked,
            String mobileMasked,
            boolean pfApplicable,
            String cpfCode,
            String uanNumber,
            boolean casualLabour) {}

    /** One labour member as M-02 lists it (PAN and bank account masked). */
    public record Row(
            long employeeId,
            String labourCode,
            String name,
            String payrollType,
            String designation,
            String depot,
            String status,
            LocalDate joiningDate,
            LocalDate retirementDate,
            String panMasked,
            String bankAccountMasked,
            boolean casualLabour) {}

    /**
     * The scope's labour members. P1 (PERFORMANCE_GUIDE): driven from the scope's employees (the
     * {@code employee.location_id} index, labour members only) to their profile by its unique
     * employee id; from the profile side every labour member of the Corporation was compared with
     * the scope's offices (a region desk: p50 450 ms at 28,000 members).
     */
    private static final String FROM =
            " FROM employee e JOIN labour_profile p ON p.employee_id = e.employee_id LEFT JOIN"
                + " org_designation d ON d.designation_id = e.designation_id LEFT JOIN org_location"
                + " l ON l.location_id = p.depot_location_id WHERE e.emp_lbr_flag = 'Y' AND (:all ="
                + " 'Y' OR e.location_id IN (:locations)) AND (:type IS NULL OR p.payroll_type ="
                + " :type) AND (:text IS NULL OR UPPER(e.first_name || ' ' || COALESCE(e.last_name,"
                + " '') || ' ' || p.labour_number) LIKE :text) AND (:depot IS NULL OR"
                + " p.depot_location_id = :depot) AND (:gang IS NULL OR EXISTS (SELECT 1 FROM"
                + " labour_gang_membership m JOIN labour_gang g ON g.gang_id = m.gang_id WHERE"
                + " m.employee_id = e.employee_id AND m.effective_from <= :gangDate AND"
                + " (m.effective_to IS NULL OR m.effective_to >= :gangDate) AND (UPPER(g.gang_code)"
                + " LIKE :gang OR UPPER(g.gang_name) LIKE :gang)))";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final OrgDirectory org;
    private final OrgMasters masters;
    private final LabourDepots depots;
    private final MakerChecker approvals;
    private final LabourPersons persons;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final ObjectMapper json;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public LabourMasterService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            Authorization policy,
            OrgDirectory org,
            OrgMasters masters,
            LabourDepots depots,
            MakerChecker approvals,
            LabourPersons persons,
            SystemParameters parameters,
            AuditService audit,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.named = named;
        this.policy = policy;
        this.org = org;
        this.masters = masters;
        this.depots = depots;
        this.approvals = approvals;
        this.persons = persons;
        this.parameters = parameters;
        this.audit = audit;
        this.json = json;
    }

    /** One choice of an M-02 / M-09 dropdown. */
    public record Option(long id, String code, String name, String family) {}

    /** The M-02 / M-09 dropdowns. */
    public record Options(List<Option> depots, List<Option> designations) {}

    /**
     * M-02 / M-09 dropdowns: the depots of the session's offices and the labour designations, from
     * the cached organisation (no register read).
     */
    @Transactional(readOnly = true)
    public Options options(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        List<Option> depots =
                this.depots
                        .of(context, policy.isAtHeadquarters(context), parameters.today())
                        .stream()
                        .map(l -> new Option(l.getId(), l.getCode(), l.getName(), null))
                        .toList();
        List<Option> designations =
                masters.designationsById().values().stream()
                        .filter(d -> LabourRules.PAYROLL_TYPES.contains(d.getFamily()))
                        .sorted(Comparator.comparing(OrgDesignation::getCode))
                        .map(d -> new Option(d.getId(), d.getCode(), d.getName(), d.getFamily()))
                        .toList();
        return new Options(depots, designations);
    }

    /** M-02: the labour of the session's offices; paged. */
    @Transactional(readOnly = true)
    public Paged<Row> page(AccessContext context, String payrollType, String text, PageSpec spec) {
        return page(context, new Search(payrollType, text, null, null), spec);
    }

    /**
     * The M-02 search (SRS NWNP Master Search, SRS-2-40): worker code or name, gang (code or name
     * of the gang the worker was in on {@code asOf}, today's India business date when null) and
     * depot, each optional, always within the actor's Access Group scope (the depot narrows it,
     * never widens it). SRS-2-40 (l.4396): labour are selected by their gang membership in the
     * period queried, not by current status, so a labour since transferred out of the gang, retired
     * or separated is found for a date they were in it.
     */
    public record Search(String payrollType, String text, String gang, Long depot, LocalDate asOf) {

        /** A search by the gang the worker is in today. */
        public Search(String payrollType, String text, String gang, Long depot) {
            this(payrollType, text, gang, depot, null);
        }
    }

    /** {@link #page(AccessContext, String, String, PageSpec)} with every search criterion. */
    @Transactional(readOnly = true)
    public Paged<Row> page(AccessContext context, Search search, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p =
                OfficeScope.of(context, policy.isAtHeadquarters(context))
                        .addValue("type", blank(search.payrollType()), Types.VARCHAR)
                        .addValue("text", like(search.text()), Types.VARCHAR)
                        .addValue("gang", like(search.gang()), Types.VARCHAR)
                        .addValue("depot", search.depot(), Types.BIGINT)
                        .addValue(
                                "gangDate",
                                java.sql.Date.valueOf(
                                        search.asOf() == null
                                                ? parameters.today()
                                                : search.asOf()));
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Row> rows =
                named.query(
                        "SELECT e.employee_id, p.labour_number, e.first_name, e.last_name,"
                                + " p.payroll_type, d.designation_name, l.location_code,"
                                + " l.location_name, p.labour_status, e.comp_joining_date,"
                                + " e.retirement_date, e.pan_number, e.bank_account_number,"
                                + " e.casual_labour_flag"
                                + FROM
                                + " ORDER BY p.labour_number, e.employee_id OFFSET :offset ROWS"
                                + " FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            String last = rs.getString("last_name");
                            java.sql.Date retire = rs.getDate("retirement_date");
                            String code = rs.getString("location_code");
                            return new Row(
                                    rs.getLong("employee_id"),
                                    rs.getString("labour_number"),
                                    last == null || last.isBlank()
                                            ? rs.getString("first_name")
                                            : rs.getString("first_name") + " " + last,
                                    rs.getString("payroll_type"),
                                    rs.getString("designation_name"),
                                    code == null
                                            ? null
                                            : code + " - " + rs.getString("location_name"),
                                    rs.getString("labour_status"),
                                    rs.getDate("comp_joining_date").toLocalDate(),
                                    retire == null ? null : retire.toLocalDate(),
                                    maskPan(rs.getString("pan_number")),
                                    maskTail(rs.getString("bank_account_number")),
                                    "Y".equals(rs.getString("casual_labour_flag")));
                        });
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** M-02: a new labour member for the Payroll Checker (SRS-2-30). */
    @Transactional
    public SubmittedApproval create(AccessContext context, Request r) {
        OrgLocation office = checked(context, "CREATE", r, null);
        return submit(
                context,
                office,
                "NEW-" + UUID.randomUUID(),
                ActionType.CREATE,
                r,
                "New " + r.payrollType() + " labour " + r.firstName());
    }

    /**
     * M-02: one labour member as the amendment form opens it (SRS-2-30), within the session's
     * offices (an office outside them answers as unknown).
     */
    @Transactional(readOnly = true)
    public Detail detail(AccessContext context, long employeeId) {
        policy.require(context, SCREEN, "VIEW");
        Map<String, Object> c = current(employeeId);
        policy.requireRecordAt(
                context, SCREEN, "VIEW", ((Number) c.get("DEPOT_LOCATION_ID")).longValue());
        Request r = stored(c);
        return new Detail(
                employeeId,
                (String) c.get("LABOUR_NUMBER"),
                r.payrollType(),
                r.firstName(),
                r.lastName(),
                r.fatherName(),
                r.gender(),
                r.birthDate(),
                r.joiningDate(),
                r.designationId(),
                r.depotLocationId(),
                maskPan(r.panNumber()),
                r.ifsc(),
                maskTail(r.bankAccount()),
                maskTail(r.mobile()),
                Boolean.TRUE.equals(r.pfApplicable()),
                r.cpfCode(),
                r.uanNumber(),
                Boolean.TRUE.equals(r.casualLabour()));
    }

    /**
     * M-02: an amendment of a labour member (SRS-2-30). The payroll type, designation and depot
     * stay (a designation change, a transfer); the name and the date of birth stay too: they change
     * only through the correction with the service-book page (SRS-2-59). SRS-2-30 "keep the old
     * value in force": a field the amendment does not carry (null) keeps its current value, so an
     * amendment of the bank account never clears the PAN, the PF flag, the CPF code or the UAN; an
     * optional field sent empty is cleared. An amendment that changes nothing is refused.
     */
    @Transactional
    public SubmittedApproval amend(AccessContext context, long employeeId, Request r) {
        if (r == null) {
            throw invalid("ENTRY");
        }
        Map<String, Object> current = current(employeeId);
        Request base = stored(current);
        Request kept = merged(r, base);
        if (normalised(kept).equals(normalised(withReason(base, kept.reason())))) {
            throw invalid("NO_CHANGE");
        }
        OrgLocation office = checked(context, "EDIT", kept, employeeId);
        return submit(
                context,
                office,
                String.valueOf(employeeId),
                ActionType.UPDATE,
                kept,
                "Amendment of " + current.get("LABOUR_NUMBER"));
    }

    /** The Payroll Checker's approval writes the person and the profile (SRS-2-30). */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY) || !decision.approved()) {
            return;
        }
        Request r = read(decision.payload());
        boolean created = decision.entityKey().startsWith("NEW-");
        Map<String, Object> current =
                created ? Map.of() : current(Long.parseLong(decision.entityKey()));
        if (!created) {
            // SRS-2-30 / SRS-2-59: the name, date of birth, designation and depot are never the
            // amendment's; they are read as they stand at approval (a correction, designation
            // change or transfer approved meanwhile is kept).
            r = keptAtApproval(r, current);
        }
        OrgLocation office = office(r.depotLocationId());
        LocalDate retire = retirement(r.birthDate());
        Long branch = branch(r.ifsc());
        Long zone = office.getZoneId();
        if (created) {
            Long last =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM labour_profile WHERE payroll_type = ?",
                            Long.class,
                            r.payrollType());
            String code = LabourRules.labourCode(r.payrollType(), RowCounts.of(last) + 1);
            long employee =
                    persons.create(
                            person(code, r, zone, branch, retire), decision.checkerLoginId());
            jdbc.update(
                    "INSERT INTO labour_profile (employee_id, labour_number, payroll_type,"
                        + " depot_location_id, pf_applicable_flag, cpf_code, uan_number,"
                        + " labour_status, effective_from) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE',"
                        + " ?)",
                    employee,
                    code,
                    r.payrollType(),
                    r.depotLocationId(),
                    Boolean.TRUE.equals(r.pfApplicable()) ? "Y" : "N",
                    blank(r.cpfCode()),
                    blank(r.uanNumber()),
                    Date.valueOf(r.joiningDate()));
            record(decision, code, null, "ACTIVE", "Labour " + code + " created");
            return;
        }
        long employee = Long.parseLong(decision.entityKey());
        String code = (String) current.get("LABOUR_NUMBER");
        persons.amend(employee, person(code, r, zone, branch, retire), decision.checkerLoginId());
        jdbc.update(
                "UPDATE labour_profile SET pf_applicable_flag = ?, cpf_code = ?, uan_number = ?,"
                        + " row_version = row_version + 1 WHERE employee_id = ?",
                Boolean.TRUE.equals(r.pfApplicable()) ? "Y" : "N",
                blank(r.cpfCode()),
                blank(r.uanNumber()),
                employee);
        record(decision, code, "AMENDED", "AMENDED", "Labour " + code + " amended: " + r.reason());
    }

    // ---------------------------------------------------------------- checks

    /** SRS-2-30 / LC-9 / LC-10: the entry, the depot in scope, designation of the payroll type. */
    private OrgLocation checked(AccessContext context, String action, Request r, Long self) {
        if (r == null) {
            throw invalid("ENTRY");
        }
        LabourRules.defect(
                        new LabourRules.Entry(
                                r.payrollType(),
                                r.firstName(),
                                r.lastName(),
                                r.birthDate(),
                                r.joiningDate(),
                                upper(r.panNumber()),
                                upper(r.ifsc()),
                                r.bankAccount(),
                                r.mobile(),
                                Boolean.TRUE.equals(r.pfApplicable()),
                                upper(r.cpfCode())),
                        parameters.today())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        if (r.reason() == null || r.reason().isBlank() || r.reason().strip().length() > 500) {
            throw invalid("REASON");
        }
        OrgLocation depot =
                org.location(java.util.Objects.requireNonNullElse(r.depotLocationId(), -1L))
                        .filter(l -> "DEPOT".equals(l.getType().name()))
                        .orElseThrow(() -> invalid("DEPOT"));
        OrgLocation office = office(depot.getId());
        policy.requireAt(context, SCREEN, action, office.getId());
        Integer designation =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM org_designation WHERE designation_id = ? AND"
                                + " designation_family = ?",
                        Integer.class,
                        java.util.Objects.requireNonNullElse(r.designationId(), -1L),
                        r.payrollType());
        if (designation == null || designation == 0) {
            throw invalid("DESIGNATION");
        }
        if (branch(r.ifsc()) == null) {
            throw invalid("IFSC");
        }
        unique(
                "PAN",
                "SELECT COUNT(*) FROM employee WHERE pan_number = ? AND employee_id <> ?",
                upper(r.panNumber()),
                self);
        // CPF codes are unique across the Corporation, staff and labour (LC-9).
        unique(
                "CPF code",
                "SELECT COUNT(*) FROM employee WHERE cpf_code = ? AND employee_id <> ?",
                upper(r.cpfCode()),
                self);
        unique(
                "CPF code",
                "SELECT COUNT(*) FROM labour_profile WHERE cpf_code = ? AND employee_id <> ?",
                upper(r.cpfCode()),
                self);
        unique(
                "UAN",
                "SELECT COUNT(*) FROM labour_profile WHERE uan_number = ? AND employee_id <> ?",
                blank(r.uanNumber()),
                self);
        return office;
    }

    private void unique(String what, String sql, String value, Long self) {
        if (value == null) {
            return;
        }
        Integer n =
                jdbc.queryForObject(
                        sql, Integer.class, value, java.util.Objects.requireNonNullElse(self, -1L));
        if (n != null && n > 0) {
            throw new FciException(LabourErrorCode.DUPLICATE, new Object[] {what}, List.of(), null);
        }
    }

    private SubmittedApproval submit(
            AccessContext context,
            OrgLocation office,
            String key,
            ActionType type,
            Request r,
            String reason) {
        String payload;
        try {
            payload = json.writeValueAsString(normalised(r));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Labour Master payload", ex);
        }
        return approvals.submit(
                context,
                new Submission(
                        ROUTE,
                        SCREEN,
                        type == ActionType.CREATE ? "CREATE" : "EDIT",
                        ENTITY,
                        key,
                        type,
                        office.getId(),
                        payload,
                        parameters.today(),
                        reason + ": " + r.reason().strip()),
                null);
    }

    private static Request normalised(Request r) {
        return new Request(
                r.payrollType(),
                blank(r.firstName()),
                blank(r.lastName()),
                blank(r.fatherName()),
                blank(r.gender()),
                r.birthDate(),
                r.joiningDate(),
                r.designationId(),
                r.depotLocationId(),
                upper(r.panNumber()),
                upper(r.ifsc()),
                r.bankAccount(),
                blank(r.mobile()),
                r.pfApplicable(),
                upper(r.cpfCode()),
                blank(r.uanNumber()),
                r.casualLabour(),
                r.reason() == null ? null : r.reason().strip());
    }

    // ---------------------------------------------------------------- helpers

    private Request read(String payload) {
        try {
            JsonNode n = json.readTree(payload);
            return json.treeToValue(n, Request.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unreadable Labour Master payload", ex);
        }
    }

    private OrgLocation office(long depotId) {
        return org.cycleUnitOf(depotId).orElseThrow(() -> invalid("DEPOT_OFFICE"));
    }

    private Long branch(String ifsc) {
        return jdbc
                .queryForList(
                        "SELECT branch_id FROM bank_branch WHERE ifsc_code = ? AND active_flag ="
                                + " 'Y'",
                        Long.class,
                        upper(ifsc))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** D20-3: the A-07 RETIREMENT_AGE; unset, the date stays empty (never assumed). */
    private LocalDate retirement(LocalDate birthDate) {
        OptionalLong age = parameters.configured(SystemParameters.Code.RETIREMENT_AGE);
        return age.isPresent()
                ? LabourRules.retirementDate(birthDate, (int) age.getAsLong())
                : null;
    }

    private static LabourPersons.Person person(
            String code, Request r, Long zone, Long branch, LocalDate retire) {
        return new LabourPersons.Person(
                code,
                r.payrollType(),
                r.firstName(),
                r.lastName(),
                r.fatherName(),
                r.gender(),
                r.birthDate(),
                r.joiningDate(),
                r.designationId(),
                // As the migrated labour: the person sits at the depot; the cycle unit follows from
                // it.
                r.depotLocationId(),
                r.depotLocationId(),
                zone,
                r.panNumber(),
                Boolean.TRUE.equals(r.pfApplicable()) ? r.cpfCode() : null,
                r.uanNumber(),
                r.mobile(),
                r.bankAccount(),
                branch,
                retire,
                Boolean.TRUE.equals(r.casualLabour()));
    }

    /** The record as it stands, as a request (the reason empty). */
    private static Request stored(Map<String, Object> c) {
        return new Request(
                (String) c.get("PAYROLL_TYPE"),
                (String) c.get("FIRST_NAME"),
                (String) c.get("LAST_NAME"),
                (String) c.get("FATHER_NAME"),
                (String) c.get("GENDER"),
                localDate(c.get("BIRTH_DATE")),
                localDate(c.get("COMP_JOINING_DATE")),
                id(c.get("DESIGNATION_ID")),
                id(c.get("DEPOT_LOCATION_ID")),
                (String) c.get("PAN_NUMBER"),
                (String) c.get("IFSC_CODE"),
                (String) c.get("BANK_ACCOUNT_NUMBER"),
                (String) c.get("MOBILE_NUMBER"),
                "Y".equals(c.get("PF_APPLICABLE_FLAG")),
                (String) c.get("CPF_CODE"),
                (String) c.get("UAN_NUMBER"),
                "Y".equals(c.get("CASUAL_LABOUR_FLAG")),
                "");
    }

    /**
     * SRS-2-30 "keep the old value in force": the amendment's fields over the record as it stands;
     * a field the amendment does not carry (null) keeps its value. The kept fields (SRS-2-59, a
     * designation change, a transfer) are always the record's.
     */
    static Request merged(Request change, Request base) {
        return new Request(
                base.payrollType(),
                base.firstName(),
                base.lastName(),
                or(change.fatherName(), base.fatherName()),
                or(change.gender(), base.gender()),
                base.birthDate(),
                or(change.joiningDate(), base.joiningDate()),
                base.designationId(),
                base.depotLocationId(),
                or(change.panNumber(), base.panNumber()),
                or(change.ifsc(), base.ifsc()),
                or(change.bankAccount(), base.bankAccount()),
                or(change.mobile(), base.mobile()),
                or(change.pfApplicable(), base.pfApplicable()),
                or(change.cpfCode(), base.cpfCode()),
                or(change.uanNumber(), base.uanNumber()),
                or(change.casualLabour(), base.casualLabour()),
                change.reason());
    }

    /** An approved amendment: the kept fields as the record stands at approval (SRS-2-59). */
    private static Request keptAtApproval(Request approved, Map<String, Object> current) {
        Request now = stored(current);
        return new Request(
                or(now.payrollType(), approved.payrollType()),
                or(now.firstName(), approved.firstName()),
                current.containsKey("FIRST_NAME") ? now.lastName() : approved.lastName(),
                approved.fatherName(),
                approved.gender(),
                or(now.birthDate(), approved.birthDate()),
                approved.joiningDate(),
                or(now.designationId(), approved.designationId()),
                or(now.depotLocationId(), approved.depotLocationId()),
                approved.panNumber(),
                approved.ifsc(),
                approved.bankAccount(),
                approved.mobile(),
                approved.pfApplicable(),
                approved.cpfCode(),
                approved.uanNumber(),
                approved.casualLabour(),
                approved.reason());
    }

    private static Request withReason(Request r, String reason) {
        return new Request(
                r.payrollType(),
                r.firstName(),
                r.lastName(),
                r.fatherName(),
                r.gender(),
                r.birthDate(),
                r.joiningDate(),
                r.designationId(),
                r.depotLocationId(),
                r.panNumber(),
                r.ifsc(),
                r.bankAccount(),
                r.mobile(),
                r.pfApplicable(),
                r.cpfCode(),
                r.uanNumber(),
                r.casualLabour(),
                reason);
    }

    private static <T> T or(T value, T otherwise) {
        return value == null ? otherwise : value;
    }

    private static LocalDate localDate(Object value) {
        return value == null
                ? null
                : new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static Long id(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Map<String, Object> current(long employeeId) {
        return jdbc
                .queryForList(
                        "SELECT p.labour_number, p.payroll_type, p.depot_location_id,"
                                + " p.pf_applicable_flag, p.cpf_code, p.uan_number,"
                                + " e.designation_id, e.first_name, e.last_name, e.father_name,"
                                + " e.gender, e.birth_date, e.comp_joining_date, e.pan_number,"
                                + " e.mobile_number, e.bank_account_number, e.casual_labour_flag,"
                                + " b.ifsc_code FROM labour_profile p JOIN employee e ON"
                                + " e.employee_id = p.employee_id LEFT JOIN bank_branch b ON"
                                + " b.branch_id = e.bank_branch_id WHERE p.employee_id = ?",
                        employeeId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private void record(ApprovalDecided d, String code, String before, String after, String why) {
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(ENTITY, code, "labour_status", before, after, why)
                        .approval(d.approvalId())
                        .module(SCREEN));
    }

    /** A contains-match pattern, upper-cased; null for a blank criterion. */
    private static String like(String value) {
        return blank(value) == null
                ? null
                : "%" + value.strip().toUpperCase(java.util.Locale.ROOT) + "%";
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String upper(String value) {
        String s = blank(value);
        return s == null ? null : s.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * R-PII, BR-SEC-001 / BR-SEC-002 (SRS l.754, l.802: "the last 4 digits only"): the PAN masked
     * through the shared {@link PiiMasks#pan} (BMAD TR-035 follow-up, 2026-11-04), as in every
     * other module.
     */
    static String maskPan(String pan) {
        return PiiMasks.pan(pan);
    }

    /** R-PII: a bank account or mobile number by its last four, through {@link PiiMasks}. */
    static String maskTail(String number) {
        return PiiMasks.lastFour(number);
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.ENTRY, new Object[] {defect}, List.of(), null);
    }
}
