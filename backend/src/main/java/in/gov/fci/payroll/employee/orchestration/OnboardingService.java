package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.employee.api.HrmsNewEmployees;
import in.gov.fci.payroll.employee.business.HrmsChangeRules;
import in.gov.fci.payroll.employee.business.OnboardingRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Onboarding of a new staff record (plan 16 D16-34 … D16-37; SRS-1-11, SRS-1-12, SRS-1-13,
 * SRS-1-16, SRS-1-17, SRS-1-52, SRS-1-53; screen M-01; UC "Onboard New Employee/Staff Record").
 *
 * <p>Intake (port {@link HrmsNewEmployees}, called by the HRMS interface): the record is checked,
 * matched against every employee on PAN and Aadhaar (and on the EIS within its zone), and created
 * in Payroll Setup Pending with the HRMS-sourced fields; the pay pattern is derived from the pay
 * scale's type, the provident fund type from the pattern (IDA: CPF; CDA / FNB: GPF or NPS as HRMS
 * holds it). A match is not created and is answered HELD_DUPLICATE for the interface exception
 * queue; the current system's biographical check is not supplied (PENDING 16-onboard).
 *
 * <p>Submission: Establishment / HR of the paying unit completes the Payroll-owned fields (the
 * allowance and other flags through M-01 FLAG; the CDA Staff Code here) and submits the record; it
 * is Change Pending until the Payroll Checker decides (route M01_ONBOARD). Approved, it is Active
 * and joins the next C-02 run; returned or rejected, it is Payroll Setup Pending again with the
 * reason, and Establishment / HR is told (SRS-1-12: no separate Rejected status).
 */
@Service
@Loggable
public class OnboardingService implements HrmsNewEmployees {

    static final String SCREEN = "M-01";
    static final String ENTITY = "employee_onboarding";
    static final String ROUTE = "M01_ONBOARD";

    /** What the Maker adds on submission (the CDA Staff Code for a CDA member). */
    record Setup(String cdaStaffCode) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public OnboardingService(
            JdbcTemplate jdbc,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            SystemParameters parameters,
            AuditService audit,
            ApplicationEventPublisher events,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.parameters = parameters;
        this.audit = audit;
        this.events = events;
        this.json = json;
    }

    // ---------------------------------------------------------------- intake

    @Override
    @Transactional
    public Received receive(NewEmployee e) {
        Optional<String> defect = OnboardingRules.defect(e);
        if (defect.isPresent()) {
            return new Received(Outcome.HELD_INVALID, null, null, defect.get());
        }
        Map<String, Object> band =
                one(
                        "SELECT band_id, scale_type FROM pay_scale_band WHERE band_code = ? AND"
                                + " active_flag = 'Y'",
                        e.payScaleCode().strip());
        if (band == null || band.get("SCALE_TYPE") == null) {
            return new Received(Outcome.HELD_INVALID, null, null, "PAY_SCALE");
        }
        Map<String, Object> posting =
                one(
                        "SELECT location_id, zone_id FROM org_location WHERE location_code = ? AND"
                                + " active_flag = 'Y'",
                        e.postingCode().strip());
        if (posting == null || posting.get("ZONE_ID") == null) {
            return new Received(Outcome.HELD_INVALID, null, null, "POSTING");
        }
        Long designation =
                id(
                        "SELECT designation_id FROM org_designation WHERE designation_code = ?",
                        e.designationCode().strip());
        if (designation == null) {
            return new Received(Outcome.HELD_INVALID, null, null, "DESIGNATION");
        }
        Long branch =
                id(
                        "SELECT branch_id FROM bank_branch WHERE ifsc_code = ? AND active_flag ="
                                + " 'Y'",
                        e.ifsc().strip().toUpperCase(java.util.Locale.ROOT));
        if (branch == null) {
            return new Received(Outcome.HELD_INVALID, null, null, "IFSC");
        }
        String pattern = (String) band.get("SCALE_TYPE");
        Optional<String> pf = OnboardingRules.providentFund(pattern, e.providentFundType());
        if (pf.isEmpty()) {
            return new Received(Outcome.HELD_INVALID, null, null, "PROVIDENT_FUND_TYPE");
        }
        long zone = ((Number) posting.get("ZONE_ID")).longValue();
        String pan = e.panNumber().strip().toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> sameEis =
                one(
                        "SELECT employee_id, pan_number FROM employee WHERE emp_num = ? AND"
                                + " parent_zone_id = ?",
                        e.eis().strip(),
                        zone);
        if (sameEis != null) {
            return pan.equals(sameEis.get("PAN_NUMBER"))
                    ? new Received(
                            Outcome.ALREADY_RECEIVED,
                            ((Number) sameEis.get("EMPLOYEE_ID")).longValue(),
                            null,
                            null)
                    : new Received(Outcome.HELD_DUPLICATE, null, e.eis().strip(), "EIS");
        }
        // SRS-1-13: never a second record of an existing employee (PAN, Aadhaar, CPF Code).
        String matched =
                match("SELECT emp_num FROM employee WHERE pan_number = ?", pan)
                        .or(() -> aadhaarMatch(e.eis().strip(), e.aadhaar().strip()))
                        .or(
                                () ->
                                        blank(e.cpfCode()) == null
                                                ? Optional.empty()
                                                : match(
                                                        "SELECT emp_num FROM employee WHERE"
                                                                + " cpf_code = ?",
                                                        e.cpfCode().strip()))
                        .orElse(null);
        if (matched != null) {
            return new Received(Outcome.HELD_DUPLICATE, null, matched, "MATCH");
        }
        OptionalLong age = parameters.configured(SystemParameters.Code.RETIREMENT_AGE);
        LocalDate retirement =
                age.isPresent()
                        ? HrmsChangeRules.retirementDate(e.birthDate(), (int) age.getAsLong())
                        : null;
        jdbc.update(
                "INSERT INTO employee (emp_num, first_name, last_name, father_name, gender,"
                    + " birth_date, comp_joining_date, employee_type, emp_lbr_flag, aadhaar_number,"
                    + " pan_number, designation_id, band_id, location_id, parent_zone_id,"
                    + " pay_pattern, pay_pattern_source, provident_fund_type, cpf_code,"
                    + " mobile_number, email_address, bank_account_number, bank_branch_id,"
                    + " retirement_date, employment_status, record_status, record_origin) VALUES"
                    + " (?, ?, ?, ?, ?, ?, ?, 'STAFF', 'N', ?, ?, ?, ?, ?, ?, ?, 'DERIVED', ?, ?,"
                    + " ?, ?, ?, ?, ?, 'IN_SERVICE', 'PAYROLL_SETUP_PENDING', 'INTERFACE')",
                e.eis().strip(),
                e.firstName().strip(),
                blank(e.lastName()),
                blank(e.fatherName()),
                blank(e.gender()),
                Date.valueOf(e.birthDate()),
                Date.valueOf(e.joiningDate()),
                e.aadhaar().strip(),
                pan,
                designation,
                band.get("BAND_ID"),
                posting.get("LOCATION_ID"),
                zone,
                pattern,
                pf.get(),
                blank(e.cpfCode()),
                blank(e.mobileNumber()),
                blank(e.emailAddress()),
                e.bankAccountNumber().strip(),
                branch,
                retirement == null ? null : Date.valueOf(retirement));
        long employee =
                id(
                        "SELECT employee_id FROM employee WHERE emp_num = ? AND parent_zone_id ="
                                + " ?",
                        e.eis().strip(),
                        zone);
        audit.record(
                "hrms.interface",
                AuditService.Entry.change(
                                "employee",
                                e.eis().strip(),
                                "record_status",
                                null,
                                "PAYROLL_SETUP_PENDING",
                                "New record received from HRMS (INTF_PAY_EMP_BOB)")
                        .module(SCREEN));
        return new Received(Outcome.CREATED, employee, null, null);
    }

    // ---------------------------------------------------------------- submission

    /** M-01: Establishment / HR submits a completed record to the Payroll Checker (SRS-1-11). */
    @Transactional
    public SubmittedApproval submit(
            AccessContext context, String eis, String cdaStaffCode, String reason) {
        Map<String, Object> r =
                Optional.ofNullable(
                                one(
                                        "SELECT employee_id, location_id, pay_pattern,"
                                                + " record_status FROM employee WHERE emp_num = ?"
                                                + " AND emp_lbr_flag = 'N'",
                                        eis))
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        OrgLocation unit =
                org.cycleUnitOf(((Number) r.get("LOCATION_ID")).longValue())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireAt(context, SCREEN, "SUBMIT", unit.getId());
        if (!"PAYROLL_SETUP_PENDING".equals(r.get("RECORD_STATUS"))) {
            throw new FciException(
                    ApiErrorCode.M01_ONBOARD_STATE,
                    new Object[] {r.get("RECORD_STATUS")},
                    List.of(),
                    null);
        }
        OnboardingRules.submissionDefect((String) r.get("PAY_PATTERN"), cdaStaffCode, reason)
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.M01_ONBOARD, new Object[] {d}, List.of(), null);
                        });
        long employee = ((Number) r.get("EMPLOYEE_ID")).longValue();
        String payload;
        try {
            payload = json.writeValueAsString(new Setup(blank(cdaStaffCode)));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Onboarding payload", ex);
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "SUBMIT",
                                ENTITY,
                                String.valueOf(employee),
                                ActionType.UPDATE,
                                unit.getId(),
                                payload,
                                parameters.today(),
                                reason.strip()),
                        null);
        jdbc.update(
                "UPDATE employee SET record_status = 'CHANGE_PENDING', row_version = row_version +"
                        + " 1, updated_at = SYSTIMESTAMP WHERE employee_id = ?",
                employee);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "employee",
                                eis,
                                "record_status",
                                "PAYROLL_SETUP_PENDING",
                                "CHANGE_PENDING",
                                reason.strip())
                        .approval(item.id())
                        .module(SCREEN));
        return item;
    }

    /** The Payroll Checker's decision: Active, or back to Payroll Setup Pending (SRS-1-12). */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        long employee = Long.parseLong(decision.entityKey());
        Map<String, Object> r =
                one("SELECT emp_num, location_id FROM employee WHERE employee_id = ?", employee);
        if (r == null) {
            return;
        }
        String eis = (String) r.get("EMP_NUM");
        String to = decision.approved() ? "ACTIVE" : "PAYROLL_SETUP_PENDING";
        if (decision.approved()) {
            Setup setup;
            try {
                setup = json.readValue(decision.payload(), Setup.class);
            } catch (JacksonException ex) {
                throw new IllegalStateException("Unreadable onboarding", ex);
            }
            jdbc.update(
                    "UPDATE employee SET record_status = 'ACTIVE', cda_staff_code = COALESCE(?,"
                            + " cda_staff_code), row_version = row_version + 1, updated_at ="
                            + " SYSTIMESTAMP WHERE employee_id = ? AND record_status ="
                            + " 'CHANGE_PENDING'",
                    setup.cdaStaffCode(),
                    employee);
        } else {
            jdbc.update(
                    "UPDATE employee SET record_status = 'PAYROLL_SETUP_PENDING', row_version ="
                            + " row_version + 1, updated_at = SYSTIMESTAMP WHERE employee_id = ?"
                            + " AND record_status = 'CHANGE_PENDING'",
                    employee);
            org.cycleUnitOf(((Number) r.get("LOCATION_ID")).longValue())
                    .ifPresent(
                            unit ->
                                    events.publishEvent(
                                            new EmployeeEvents.OnboardingReturned(
                                                    employee,
                                                    eis,
                                                    unit.getId(),
                                                    decision.reason(),
                                                    decision.checkerLoginId())));
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                "employee",
                                eis,
                                "record_status",
                                "CHANGE_PENDING",
                                to,
                                decision.reason() == null ? to : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    // ---------------------------------------------------------------- reads

    /**
     * SRS-SEC-5 (BR-SEC-001): the Aadhaar number is used only to prevent a duplicate member record,
     * and "every use in a permitted check" is recorded in the audit trail: one row per check, under
     * the incoming EIS, with its outcome. The number itself is never written.
     */
    private Optional<String> aadhaarMatch(String eis, String aadhaar) {
        Optional<String> matched =
                match("SELECT emp_num FROM employee WHERE aadhaar_number = ?", aadhaar);
        audit.record(
                "hrms.interface",
                AuditService.Entry.change(
                                "employee",
                                eis,
                                "AADHAAR_CHECK",
                                null,
                                matched.map(m -> "MATCH " + m).orElse("NO_MATCH"),
                                "Aadhaar used in the duplicate-member check of a record received"
                                        + " from HRMS (SRS-1-13, SRS-SEC-5)")
                        .module(SCREEN));
        return matched;
    }

    private Optional<String> match(String sql, String value) {
        return jdbc.queryForList(sql, String.class, value).stream().findFirst();
    }

    private Map<String, Object> one(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().findFirst().orElse(null);
    }

    private Long id(String sql, Object... args) {
        return jdbc.queryForList(sql, Long.class, args).stream().findFirst().orElse(null);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
