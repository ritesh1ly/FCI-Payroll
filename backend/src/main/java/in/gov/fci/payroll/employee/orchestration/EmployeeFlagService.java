package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.business.FlagRules;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * M-01 Payroll allowance flags (plan 16 phase 16d-2; SRS-1-4, SRS-1-11; FRS BR-EMPM-004 / 005 /
 * 006; DDL {@code employee_eligibility_flag}): Establishment/HR proposes a flag value from a date
 * with a reason (M-01 FLAG, route M01_FLAG); the Payroll Checker approves on W-01 and the version
 * is written ({@link EmployeeFlagEffect}); nothing is overwritten, every version stays. The engine
 * reads the version in force on the period's first day (D16-16).
 *
 * <p>SRS-1-26 (FRS §7.5 steps 1-5; deep review DR-10): the request carries the current and the
 * requested value, the reason and the supporting document (SRS-G-30), which the Checker sees with
 * the item. A version effective before the current month is settled by the arrear module for the
 * locked months (SRS-21-11; {@link EmployeeFlagEffect}). OPEN-QUESTION (PENDING DR-10-cpf):
 * SRS-1-26 adds the CPF approver at ZO / HQ "where the change affects CPF"; the sources name no
 * allowance flag that does, so route M01_FLAG has no CPF level unless FCI configures one on X-01.
 */
@Service
@Loggable
public class EmployeeFlagService {

    /** Approval entity (key: {@code <eis>|<flag>|<effective date>}). */
    public static final String ENTITY = "employee_eligibility_flag";

    static final String SCREEN = "M-01";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** A flag of the catalogue with its value today and whether a change waits for the Checker. */
    public record FlagState(
            String code, String label, String value, LocalDate since, boolean pending) {}

    /** One recorded version. */
    public record FlagVersion(
            String code,
            String value,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason,
            String changedBy,
            Instant changedAt,
            Long approvalId) {}

    /** The flags of an employee today. */
    public record Flags(String eis, LocalDate joiningDate, List<FlagState> flags) {}

    /**
     * What Establishment/HR proposes: the value from a date, until an end date when one is given
     * (M-01 Payroll Flags row, BR-EMPM-004: "effective date, end date ... recorded on every
     * change"; US-GAP-39). After the end date the flag no longer applies (it reads "N").
     */
    public record Proposal(
            String flagCode,
            String value,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {

        /** A proposal without an end date. */
        public Proposal(String flagCode, String value, LocalDate effectiveFrom, String reason) {
            this(flagCode, value, effectiveFrom, null, reason);
        }
    }

    /** SRS-1-26 / SRS-G-30: the supporting document of a proposal. */
    public record Document(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final DocumentStore documents;
    private final ObjectMapper json;
    private final Clock clock;

    public EmployeeFlagService(
            JdbcTemplate jdbc,
            Authorization policy,
            MakerChecker approvals,
            DocumentStore documents,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.approvals = approvals;
        this.documents = documents;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Flags flags(AccessContext context, String eis) {
        Map<String, Object> employee = employee(context, eis, "VIEW");
        long employeeId = ((Number) employee.get("EMPLOYEE_ID")).longValue();
        LocalDate today = LocalDate.now(clock.withZone(IST));
        List<FlagRules.Version> versions = versions(employeeId);
        Set<String> pending = pendingFlags(eis);
        List<FlagState> states =
                catalogue(today).entrySet().stream()
                        .map(
                                c ->
                                        new FlagState(
                                                c.getKey(),
                                                c.getValue(),
                                                FlagRules.valueOn(versions, c.getKey(), today),
                                                since(versions, c.getKey(), today),
                                                pending.contains(c.getKey())))
                        .toList();
        return new Flags(
                (String) employee.get("EMP_NUM"),
                HrmsChangeService.date(employee.get("COMP_JOINING_DATE")),
                states);
    }

    /** Every version, newest first, paged (BR-EMPM-004: prior values retained). */
    @Transactional(readOnly = true)
    public Paged<FlagVersion> history(AccessContext context, String eis, PageSpec spec) {
        Map<String, Object> employee = employee(context, eis, "VIEW");
        long employeeId = ((Number) employee.get("EMPLOYEE_ID")).longValue();
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM employee_eligibility_flag WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        List<FlagVersion> rows =
                jdbc.query(
                        "SELECT flag_code, flag_value, effective_from, effective_to, reason,"
                                + " changed_by, changed_at, approval_id FROM"
                                + " employee_eligibility_flag WHERE employee_id = ? ORDER BY"
                                + " effective_from DESC, eligibility_id DESC OFFSET ? ROWS FETCH"
                                + " NEXT ? ROWS ONLY",
                        (rs, i) -> {
                            Timestamp at = rs.getTimestamp("changed_at");
                            return new FlagVersion(
                                    rs.getString("flag_code"),
                                    rs.getString("flag_value"),
                                    HrmsChangeService.date(rs.getObject("effective_from")),
                                    HrmsChangeService.date(rs.getObject("effective_to")),
                                    rs.getString("reason"),
                                    rs.getString("changed_by"),
                                    at == null ? null : at.toInstant(),
                                    rs.getObject("approval_id", Long.class));
                        },
                        employeeId,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * SRS-1-11 / SRS-1-26: Establishment/HR proposes the flag with the supporting document; the
     * Payroll Checker decides on W-01 with the current and requested value in view.
     */
    @Transactional
    public SubmittedApproval propose(
            AccessContext context, String eis, Proposal proposal, Document document) {
        Map<String, Object> employee = employee(context, eis, "FLAG");
        if (separated(employee)) {
            throw new FciException(
                    ApiErrorCode.M01_FLAG, new Object[] {"SEPARATED"}, List.of(), null);
        }
        long employeeId = ((Number) employee.get("EMPLOYEE_ID")).longValue();
        String code = proposal.flagCode() == null ? null : proposal.flagCode().strip();
        Map<String, String> catalogue = catalogue(LocalDate.now(clock.withZone(IST)));
        FlagRules.defect(
                        code != null && catalogue.containsKey(code),
                        proposal.value(),
                        proposal.effectiveFrom(),
                        proposal.effectiveTo(),
                        HrmsChangeService.date(employee.get("COMP_JOINING_DATE")),
                        proposal.reason())
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.M01_FLAG, new Object[] {d}, List.of(), null);
                        });
        String empNum = (String) employee.get("EMP_NUM");
        if (pendingFlags(empNum).contains(code)) {
            throw new FciException(
                    ApiErrorCode.M01_FLAG_PENDING, new Object[] {code}, List.of(), null);
        }
        List<FlagRules.Version> versions = versions(employeeId);
        if (versions.stream()
                .anyMatch(
                        v ->
                                v.flagCode().equals(code)
                                        && v.effectiveFrom().equals(proposal.effectiveFrom()))) {
            throw new FciException(
                    ApiErrorCode.M01_FLAG, new Object[] {"SAME_DATE"}, List.of(), null);
        }
        if (!FlagRules.changes(versions, code, proposal.effectiveFrom(), proposal.value())) {
            throw new FciException(
                    ApiErrorCode.M01_FLAG, new Object[] {"NO_CHANGE"}, List.of(), null);
        }
        if (document == null || document.content() == null || document.content().length == 0) {
            throw new FciException(
                    ApiErrorCode.M01_FLAG, new Object[] {"DOCUMENT_MISSING"}, List.of(), null);
        }
        // SRS-1-26: the value in force on the date the change takes effect.
        String current = FlagRules.valueOn(versions, code, proposal.effectiveFrom());
        String key = empNum + "|" + code + "|" + proposal.effectiveFrom();
        String provisional = DocumentStore.PROVISIONAL + UUID.randomUUID();
        DocumentStore.AttachmentView attached =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                provisional,
                                document.documentType(),
                                document.reference(),
                                document.orderDate(),
                                document.issuingAuthority(),
                                document.fileName(),
                                document.content(),
                                true));
        documents.adopt(context.loginId(), ENTITY, provisional, attached.id(), key);
        String payload;
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("employeeId", employeeId);
            body.put("flagCode", code);
            body.put("currentValue", current);
            body.put("value", proposal.value());
            body.put("effectiveFrom", proposal.effectiveFrom().toString());
            body.put(
                    "effectiveTo",
                    proposal.effectiveTo() == null ? "" : proposal.effectiveTo().toString());
            body.put("reason", proposal.reason().strip());
            body.put("attachmentId", attached.id());
            payload = json.writeValueAsString(body);
        } catch (tools.jackson.core.JacksonException ex) {
            throw new IllegalStateException("Cannot serialise a flag proposal", ex);
        }
        return approvals.submit(
                context,
                new Submission(
                        SCREEN,
                        "FLAG",
                        ENTITY,
                        key,
                        ActionType.UPDATE,
                        ((Number) employee.get("LOCATION_ID")).longValue(),
                        payload,
                        proposal.effectiveFrom(),
                        "Allowance flag "
                                + code
                                + " "
                                + current
                                + " -> "
                                + proposal.value()
                                + " from "
                                + proposal.effectiveFrom()
                                + (proposal.effectiveTo() == null
                                        ? ""
                                        : " to " + proposal.effectiveTo())
                                + ": "
                                + proposal.reason().strip()),
                (OtpProof) null);
    }

    // ---------------------------------------------------------------- reads

    /**
     * SRS-1-77 (US-GAP-49): after separation the record is read-only; only the FSD / paying unit
     * changes (M-11), so no flag is set or cleared on it.
     */
    static boolean separated(Map<String, Object> employee) {
        return "SEPARATED".equals(employee.get("EMPLOYMENT_STATUS"));
    }

    private Map<String, Object> employee(AccessContext context, String eis, String action) {
        Map<String, Object> employee =
                jdbc
                        .queryForList(
                                "SELECT employee_id, emp_num, location_id, comp_joining_date,"
                                        + " employment_status FROM employee WHERE emp_num = ?",
                                eis == null ? "" : eis.strip())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireEmployeeRecord(
                context,
                SCREEN,
                action,
                ((Number) employee.get("EMPLOYEE_ID")).longValue(),
                ((Number) employee.get("LOCATION_ID")).longValue());
        return employee;
    }

    /** The ELIGIBILITY_FLAG list in force: code -> label, in list order. */
    private Map<String, String> catalogue(LocalDate day) {
        Map<String, String> codes = new java.util.LinkedHashMap<>();
        jdbc.query(
                "SELECT v.value_code, v.value_label_en FROM reference_value v JOIN reference_list l"
                        + " ON l.list_id = v.list_id WHERE l.list_code = 'ELIGIBILITY_FLAG' AND"
                        + " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >="
                        + " ?) ORDER BY v.sort_order, v.value_code",
                rs -> {
                    codes.put(rs.getString(1), rs.getString(2));
                },
                java.sql.Date.valueOf(day),
                java.sql.Date.valueOf(day));
        return codes;
    }

    private List<FlagRules.Version> versions(long employeeId) {
        return jdbc.query(
                "SELECT flag_code, flag_value, effective_from, effective_to FROM"
                        + " employee_eligibility_flag WHERE employee_id = ?",
                (rs, i) ->
                        new FlagRules.Version(
                                rs.getString("flag_code"),
                                rs.getString("flag_value"),
                                HrmsChangeService.date(rs.getObject("effective_from")),
                                HrmsChangeService.date(rs.getObject("effective_to"))),
                employeeId);
    }

    /** The flags of the employee with a proposal waiting for the Checker. */
    private Set<String> pendingFlags(String empNum) {
        return jdbc
                .queryForList(
                        "SELECT entity_key FROM approval_queue WHERE entity_name = ? AND"
                                + " entity_key LIKE ? AND status = 'PENDING_APPROVAL'",
                        String.class,
                        ENTITY,
                        empNum + "|%")
                .stream()
                .map(k -> k.split("\\|")[1])
                .collect(Collectors.toSet());
    }

    private static LocalDate since(List<FlagRules.Version> versions, String code, LocalDate day) {
        return versions.stream()
                .filter(v -> v.flagCode().equals(code) && !v.effectiveFrom().isAfter(day))
                .map(FlagRules.Version::effectiveFrom)
                .max(LocalDate::compareTo)
                .orElse(null);
    }
}
