package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.business.ControlFlagRules;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
 * M-01 Hold and Stop-Payment flags (SRS-1-7, SRS-1-11; FRS §7.8.1 Scenario 4; US-GAP-4):
 * Establishment/HR sets or clears a flag from a date with a reason and the order (SRS-G-30;
 * SRS-G-32 "M-01 Employee Payroll Master (salary release order for Payroll-owned fields)"); it goes
 * to the Payroll Checker on the M-01 FLAG route, the Establishment/HR to Payroll Checker route of
 * the allowance flags; on approval the flag is written ({@link EmployeeControlFlagEffect}). The
 * next Actual run suppresses the payment and raises PAYMENT_HELD on X-05 while the flag is set.
 */
@Service
@Loggable
public class EmployeeControlFlagService {

    /** Approval entity (key: {@code <eis>|<flag>}): one pending change per member and flag. */
    public static final String ENTITY = "employee_control_flag";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** A flag with its value and whether a change waits for the Checker. */
    public record FlagState(String code, String value, boolean pending) {}

    /** The control flags of an employee. */
    public record Flags(String eis, LocalDate joiningDate, List<FlagState> flags) {}

    /** What Establishment/HR proposes. */
    public record Proposal(String flagCode, String value, LocalDate effectiveFrom, String reason) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final DocumentStore documents;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public EmployeeControlFlagService(
            JdbcTemplate jdbc,
            Authorization policy,
            MakerChecker approvals,
            DocumentStore documents,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.approvals = approvals;
        this.documents = documents;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Flags flags(AccessContext context, String eis) {
        Map<String, Object> e = employee(context, eis, "VIEW");
        Set<String> pending = pending((String) e.get("EMP_NUM"));
        return new Flags(
                (String) e.get("EMP_NUM"),
                HrmsChangeService.date(e.get("COMP_JOINING_DATE")),
                ControlFlagRules.COLUMNS.keySet().stream()
                        .sorted()
                        .map(
                                code ->
                                        new FlagState(
                                                code,
                                                (String) e.get(ControlFlagRules.COLUMNS.get(code)),
                                                pending.contains(code)))
                        .toList());
    }

    /** Sets or clears a flag: to the Payroll Checker with the order (202). */
    @Transactional
    public SubmittedApproval propose(
            AccessContext context, String eis, Proposal p, EmployeeFlagService.Document document) {
        Map<String, Object> e = employee(context, eis, "FLAG");
        // SRS-1-77 (US-GAP-49): a separated record is read-only.
        if (EmployeeFlagService.separated(e)) {
            throw new FciException(
                    ApiErrorCode.M01_CONTROL_FLAG, new Object[] {"SEPARATED"}, List.of(), null);
        }
        String code = p.flagCode() == null ? null : p.flagCode().strip();
        String column = code == null ? null : ControlFlagRules.COLUMNS.get(code);
        String current = column == null ? null : (String) e.get(column);
        String empNum = (String) e.get("EMP_NUM");
        ControlFlagRules.defect(
                        code,
                        p.value(),
                        current,
                        p.effectiveFrom(),
                        HrmsChangeService.date(e.get("COMP_JOINING_DATE")),
                        LocalDate.now(clock.withZone(IST)),
                        p.reason())
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.M01_CONTROL_FLAG,
                                    new Object[] {d},
                                    List.of(),
                                    null);
                        });
        if (pending(empNum).contains(code)) {
            throw new FciException(
                    ApiErrorCode.M01_CONTROL_PENDING, new Object[] {code}, List.of(), null);
        }
        String key = empNum + "|" + code;
        if (document == null || document.content() == null || document.content().length == 0) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(
                    context.loginId(), ENTITY, key, EmployeeFlagService.SCREEN, "order copy");
            throw new FciException(
                    ApiErrorCode.M01_CONTROL_FLAG,
                    new Object[] {"DOCUMENT_MISSING"},
                    List.of(),
                    null);
        }
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeId", ((Number) e.get("EMPLOYEE_ID")).longValue());
        payload.put("eis", empNum);
        payload.put("flagCode", code);
        payload.put("currentValue", current);
        payload.put("value", p.value());
        payload.put("effectiveFrom", p.effectiveFrom().toString());
        payload.put("reason", p.reason().strip());
        payload.put("attachmentId", attached.id());
        return approvals.submit(
                context,
                new Submission(
                        EmployeeFlagService.SCREEN,
                        "FLAG",
                        ENTITY,
                        key,
                        ActionType.UPDATE,
                        // A member without an office is reached at Headquarters only (S-7).
                        e.get("LOCATION_ID") == null
                                ? context.locationId()
                                : ((Number) e.get("LOCATION_ID")).longValue(),
                        json.writeValueAsString(payload),
                        p.effectiveFrom(),
                        ("Y".equals(p.value()) ? "Set " : "Clear ")
                                + code
                                + " ("
                                + current
                                + " -> "
                                + p.value()
                                + ") from "
                                + p.effectiveFrom()
                                + ": "
                                + p.reason().strip()),
                (OtpProof) null);
    }

    private Map<String, Object> employee(AccessContext context, String eis, String action) {
        Map<String, Object> e =
                jdbc
                        .queryForList(
                                "SELECT employee_id, emp_num, location_id, comp_joining_date,"
                                        + " hold_flag, stop_payment_flag, employment_status FROM"
                                        + " employee WHERE emp_num = ?",
                                eis == null ? "" : eis.strip())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        Object location = e.get("LOCATION_ID");
        policy.requireEmployeeRecord(
                context,
                EmployeeFlagService.SCREEN,
                action,
                ((Number) e.get("EMPLOYEE_ID")).longValue(),
                location == null ? null : ((Number) location).longValue());
        return e;
    }

    /** The flags of the employee with a change waiting for the Checker. */
    private Set<String> pending(String empNum) {
        return jdbc
                .queryForList(
                        "SELECT entity_key FROM approval_queue WHERE entity_name = ? AND"
                                + " entity_key LIKE ? AND status = 'PENDING_APPROVAL'",
                        String.class,
                        ENTITY,
                        empNum + "|%")
                .stream()
                .map(k -> k.substring(k.indexOf('|') + 1))
                .collect(Collectors.toSet());
    }
}
