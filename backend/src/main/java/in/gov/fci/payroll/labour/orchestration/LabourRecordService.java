package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.LabourRecordRules;
import in.gov.fci.payroll.labour.business.LabourRecordRules.Person;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Labour Master records kept in Payroll because labour are not in HRMS (plan 22 phase 22c; NWNP
 * Reinstate Details and Nominee &amp; Dependent Details use cases; BR-FNF-014; D22-7, D22-8):
 *
 * <ul>
 *   <li>Establishment / HR records a separated member's reinstatement with its order attached; the
 *       Payroll Checker approves (route M02_REINSTATEMENT); the earlier records stay, the member is
 *       active and in service again from the date and the CPF ledger clears the ex-member interest
 *       stop (SRS-6-9, through {@link LabourPersons#reinstate}); the gang is joined on M-09;
 *   <li>Establishment / HR records the member's nominees (shares adding to 100) and dependents; the
 *       Payroll Checker approves (route M02_NOMINATION); approved, they replace the earlier ones,
 *       which end the day before and are kept, for Full &amp; Final Settlement and CPF.
 * </ul>
 */
@Service
@Loggable
public class LabourRecordService {

    static final String SCREEN = "M-02";
    static final String REINSTATEMENT = "labour_reinstatement";
    static final String NOMINATION = "labour_nomination";

    /** A reinstatement as entered with its order. */
    public record Reinstatement(
            LocalDate date,
            String orderReference,
            LocalDate orderDate,
            String issuingAuthority,
            String reason) {}

    /** The reinstatement order. */
    public record Document(String fileName, byte[] content) {}

    /** The nominees and dependents as entered. */
    public record Nomination(List<Person> nominees, List<Person> dependents, String reason) {

        /** No list given is an empty list (one place, not every reader). */
        public Nomination {
            nominees = nominees == null ? List.of() : List.copyOf(nominees);
            dependents = dependents == null ? List.of() : List.copyOf(dependents);
        }
    }

    /** A member's nominees and dependents in force, and whether a change waits. */
    public record Nominations(List<Person> nominees, List<Person> dependents, boolean pending) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final LabourPersons persons;
    private final DocumentStore documents;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final ObjectMapper json;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public LabourRecordService(
            JdbcTemplate jdbc,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            LabourPersons persons,
            DocumentStore documents,
            SystemParameters parameters,
            AuditService audit,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.persons = persons;
        this.documents = documents;
        this.parameters = parameters;
        this.audit = audit;
        this.json = json;
    }

    /** M-02: a separated member's reinstatement with its order, for the Payroll Checker. */
    @Transactional
    public SubmittedApproval reinstate(
            AccessContext context, long employeeId, Reinstatement r, Document document) {
        Map<String, Object> labour = labour(employeeId);
        OrgLocation office = office(labour);
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        if (r == null
                || document == null
                || document.content() == null
                || document.content().length == 0
                || r.orderDate() == null
                || r.issuingAuthority() == null
                || r.issuingAuthority().isBlank()) {
            throw invalid("ORDER");
        }
        LabourRecordRules.reinstatementDefect(
                        (String) labour.get("LABOUR_STATUS"),
                        date(labour.get("EFFECTIVE_TO")),
                        r.date(),
                        r.reason())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        pendingRefused(REINSTATEMENT, employeeId);
        documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        REINSTATEMENT,
                        String.valueOf(employeeId),
                        "SANCTION_ORDER",
                        r.orderReference() == null || r.orderReference().isBlank()
                                ? null
                                : r.orderReference().strip(),
                        r.orderDate(),
                        r.issuingAuthority().strip(),
                        document.fileName(),
                        document.content(),
                        true));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "M02_REINSTATEMENT",
                                SCREEN,
                                "EDIT",
                                REINSTATEMENT,
                                String.valueOf(employeeId),
                                ActionType.UPDATE,
                                office.getId(),
                                json.writeValueAsString(r),
                                r.date(),
                                "Reinstatement of "
                                        + labour.get("LABOUR_NUMBER")
                                        + ": "
                                        + r.reason().strip()),
                        null);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                REINSTATEMENT,
                                String.valueOf(employeeId),
                                "labour_status",
                                "SEPARATED",
                                "REINSTATEMENT_PENDING",
                                r.reason().strip())
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(r.date()));
        return item;
    }

    /** M-02: a member's nominees and dependents in force. */
    @Transactional(readOnly = true)
    public Nominations nominations(AccessContext context, long employeeId) {
        Map<String, Object> labour = labour(employeeId);
        policy.requireRecordAt(context, SCREEN, "VIEW", office(labour).getId());
        LocalDate today = parameters.today();
        List<Person> nominees =
                jdbc.query(
                        "SELECT nominee_name, relationship, date_of_birth, share_percent FROM"
                            + " pf_nominee WHERE employee_id = ? AND effective_from <= ? AND"
                            + " (effective_to IS NULL OR effective_to >= ?) ORDER BY nominee_id",
                        (rs, i) ->
                                new Person(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(),
                                        rs.getBigDecimal(4),
                                        true),
                        employeeId,
                        Date.valueOf(today),
                        Date.valueOf(today));
        List<Person> dependents =
                jdbc.query(
                        "SELECT dependent_name, relationship, date_of_birth, is_nominee FROM"
                                + " employee_dependent WHERE employee_id = ? AND active_flag = 'Y'"
                                + " ORDER BY dependent_id",
                        (rs, i) ->
                                new Person(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(),
                                        null,
                                        "Y".equals(rs.getString(4))),
                        employeeId);
        return new Nominations(nominees, dependents, pending(NOMINATION, employeeId));
    }

    /** M-02: the member's nominees and dependents, for the Payroll Checker (BR-FNF-014). */
    @Transactional
    public SubmittedApproval nominate(AccessContext context, long employeeId, Nomination n) {
        Map<String, Object> labour = labour(employeeId);
        OrgLocation office = office(labour);
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        if (n == null) {
            throw invalid("EMPTY");
        }
        LabourRecordRules.nominationDefect(
                        n.nominees(),
                        n.dependents(),
                        new HashSet<>(relationships()),
                        parameters.today(),
                        n.reason())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        pendingRefused(NOMINATION, employeeId);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "M02_NOMINATION",
                                SCREEN,
                                "EDIT",
                                NOMINATION,
                                String.valueOf(employeeId),
                                ActionType.UPDATE,
                                office.getId(),
                                json.writeValueAsString(n),
                                parameters.today(),
                                "Nominees and dependents of "
                                        + labour.get("LABOUR_NUMBER")
                                        + ": "
                                        + n.reason().strip()),
                        null);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                NOMINATION,
                                String.valueOf(employeeId),
                                "nominees",
                                null,
                                n.nominees().size()
                                        + " nominee(s), "
                                        + n.dependents().size()
                                        + " dependent(s)",
                                n.reason().strip())
                        .approval(item.id())
                        .module(SCREEN));
        return item;
    }

    /** The Payroll Checker's decision on a reinstatement or a nomination. */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (decision.concerns(REINSTATEMENT)) {
            reinstated(decision);
        } else if (decision.concerns(NOMINATION)) {
            nominated(decision);
        }
    }

    private void reinstated(ApprovalDecided decision) {
        long employeeId = Long.parseLong(decision.entityKey());
        String outcome = decision.approved() ? "ACTIVE" : "SEPARATED";
        if (decision.approved()) {
            Reinstatement r = read(decision.payload(), Reinstatement.class);
            jdbc.update(
                    "UPDATE labour_profile SET labour_status = 'ACTIVE', effective_to = NULL,"
                            + " row_version = row_version + 1 WHERE employee_id = ? AND"
                            + " labour_status = 'SEPARATED'",
                    employeeId);
            persons.reinstate(employeeId, r.date(), decision.checkerLoginId());
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                REINSTATEMENT,
                                String.valueOf(employeeId),
                                "labour_status",
                                "REINSTATEMENT_PENDING",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    private void nominated(ApprovalDecided decision) {
        long employeeId = Long.parseLong(decision.entityKey());
        if (decision.approved()) {
            Nomination n = read(decision.payload(), Nomination.class);
            LocalDate today = parameters.today();
            Date end = Date.valueOf(today.minusDays(1));
            jdbc.update(
                    "UPDATE pf_nominee SET effective_to = ? WHERE employee_id = ? AND (effective_to"
                            + " IS NULL OR effective_to >= ?)",
                    end,
                    employeeId,
                    Date.valueOf(today));
            jdbc.update(
                    "UPDATE employee_dependent SET active_flag = 'N', effective_to = ? WHERE"
                            + " employee_id = ? AND active_flag = 'Y'",
                    end,
                    employeeId);
            for (Person p : n.nominees()) {
                jdbc.update(
                        "INSERT INTO pf_nominee (employee_id, nominee_name, relationship,"
                            + " date_of_birth, share_percent, effective_from, approval_id) VALUES"
                            + " (?, ?, ?, ?, ?, ?, ?)",
                        employeeId,
                        p.name().strip(),
                        p.relationship(),
                        p.birthDate() == null ? null : Date.valueOf(p.birthDate()),
                        p.share(),
                        Date.valueOf(today),
                        decision.approvalId());
            }
            for (Person p : n.dependents()) {
                jdbc.update(
                        "INSERT INTO employee_dependent (employee_id, dependent_name, relationship,"
                            + " date_of_birth, is_nominee, effective_from) VALUES (?, ?, ?, ?, ?,"
                            + " ?)",
                        employeeId,
                        p.name().strip(),
                        p.relationship(),
                        p.birthDate() == null ? null : Date.valueOf(p.birthDate()),
                        p.nominee() ? "Y" : "N",
                        Date.valueOf(today));
            }
        }
        String outcome = decision.approved() ? "APPROVED" : "REJECTED";
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                NOMINATION,
                                String.valueOf(employeeId),
                                "approval_state",
                                "PENDING_APPROVAL",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return json.readValue(payload, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unreadable " + type.getSimpleName(), ex);
        }
    }

    private boolean pending(String entity, long employeeId) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_queue WHERE entity_name = ? AND entity_key ="
                                + " ? AND status = 'PENDING_APPROVAL'",
                        Integer.class,
                        entity,
                        String.valueOf(employeeId));
        return n != null && n > 0;
    }

    private void pendingRefused(String entity, long employeeId) {
        if (pending(entity, employeeId)) {
            throw invalid("PENDING");
        }
    }

    private Map<String, Object> labour(long employeeId) {
        return jdbc
                .queryForList(
                        "SELECT p.labour_number, p.depot_location_id, p.labour_status,"
                                + " p.effective_to FROM labour_profile p WHERE p.employee_id = ?",
                        employeeId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private OrgLocation office(Map<String, Object> labour) {
        return org.cycleUnitOf(((Number) labour.get("DEPOT_LOCATION_ID")).longValue())
                .orElseThrow(() -> invalid("DEPOT_OFFICE"));
    }

    private List<String> relationships() {
        Date today = Date.valueOf(parameters.today());
        return jdbc.queryForList(
                "SELECT v.value_code FROM reference_value v JOIN reference_list l ON l.list_id ="
                    + " v.list_id WHERE l.list_code = 'NOMINEE_RELATIONSHIP' AND v.effective_from"
                    + " <= ? AND (v.effective_to IS NULL OR v.effective_to >= ?)",
                String.class,
                today,
                today);
    }

    private static LocalDate date(Object value) {
        return value == null
                ? null
                : new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.ENTRY, new Object[] {defect}, List.of(), null);
    }
}
