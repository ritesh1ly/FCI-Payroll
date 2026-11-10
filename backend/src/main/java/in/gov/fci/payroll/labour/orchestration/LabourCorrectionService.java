package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.LabourRules;
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
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.InputPatterns;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * M-02 labour name / date-of-birth correction (plan 20 phase 20e; SRS-2-59, BR-LABM-003,
 * BR-LABM-010; DDL identity_correction_request; D20-18). Labour are not in HRMS, so Establishment /
 * HR of the labour's office corrects the name as per the service book, or the date of birth, with
 * the service-book page or the order attached; the Payroll Checker approves (route M02_CORRECTION).
 * A date-of-birth correction recalculates the retirement date and the EPS cut-off (A-07
 * RETIREMENT_AGE, EPS_CUTOFF_AGE; refused while either is unset, as for employees); where the new
 * retirement date has already passed, Headquarters Establishment also approves (route
 * M02_CORRECTION_HQ). Approved, the person record changes and the request is APPLIED. The pay, EPS
 * and CPF differences go through Arrears once the labour computation exists (PENDING LBR-20d-3),
 * and so does the pre-lock alert of a retirement in the current or the next month.
 */
@Service
@Loggable
public class LabourCorrectionService {

    static final String SCREEN = "M-02";
    static final String ENTITY = "identity_correction_request";

    /** DDL {@code identity_correction_request.correction_type}. */
    public static final Set<String> TYPES = Set.of("NAME", "DOB");

    /** A correction as entered (the name for NAME, the date of birth for DOB). */
    public record Correction(
            String type,
            String firstName,
            String lastName,
            LocalDate birthDate,
            String reasonCode,
            String reasonText,
            String orderReference,
            LocalDate orderDate,
            String issuingAuthority,
            LocalDate effectiveDate) {}

    /** The attached service-book page or order. */
    public record Document(String fileName, byte[] content) {}

    /** The corrected name as the approval carries it (a first name may hold a space). */
    record Name(String firstName, String lastName) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final LabourPersons persons;
    private final DocumentStore documents;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public LabourCorrectionService(
            JdbcTemplate jdbc,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            LabourPersons persons,
            DocumentStore documents,
            SystemParameters parameters,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.persons = persons;
        this.documents = documents;
        this.parameters = parameters;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** M-02: a name or date-of-birth correction for the Payroll Checker (SRS-2-59). */
    @Transactional
    public SubmittedApproval correct(
            AccessContext context, long employeeId, Correction c, Document document) {
        Map<String, Object> labour = labour(employeeId);
        OrgLocation office =
                org.cycleUnitOf(((Number) labour.get("DEPOT_LOCATION_ID")).longValue())
                        .orElseThrow(() -> invalid("DEPOT_OFFICE"));
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        checkEntry(c, document);
        Integer open =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM identity_correction_request WHERE employee_id = ? AND"
                                + " correction_type = ? AND request_status IN ('SUBMITTED',"
                                + " 'PENDING_HQ_ESTT')",
                        Integer.class,
                        employeeId,
                        c.type());
        if (open != null && open > 0) {
            throw invalid("PENDING");
        }
        String current;
        String requested;
        String payload = null;
        LocalDate retirement = null;
        LocalDate epsCutoff = null;
        if ("NAME".equals(c.type())) {
            if (!LabourRules.name(c.firstName())
                    || (blank(c.lastName()) != null && !LabourRules.name(c.lastName()))
                    || !LabourRules.fullNameFits(c.firstName(), c.lastName())) {
                throw invalid("NAME");
            }
            current = fullName(labour.get("FIRST_NAME"), labour.get("LAST_NAME"));
            requested = fullName(c.firstName(), c.lastName());
            try {
                payload =
                        json.writeValueAsString(
                                new Name(c.firstName().strip(), blank(c.lastName())));
            } catch (JacksonException ex) {
                throw new IllegalStateException("Correction payload", ex);
            }
        } else {
            if (c.birthDate() == null
                    || c.birthDate().isAfter(parameters.today())
                    || !c.birthDate().isBefore(date(labour.get("COMP_JOINING_DATE")))) {
                throw invalid("BIRTH_DATE");
            }
            OptionalLong age = parameters.configured(SystemParameters.Code.RETIREMENT_AGE);
            OptionalLong eps = parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE);
            if (age.isEmpty() || eps.isEmpty()) {
                throw new FciException(
                        LabourErrorCode.NOT_CONFIGURED,
                        new Object[] {"RETIREMENT_AGE / EPS_CUTOFF_AGE"},
                        List.of(),
                        null);
            }
            current = date(labour.get("BIRTH_DATE")).toString();
            requested = c.birthDate().toString();
            retirement = LabourRules.retirementDate(c.birthDate(), (int) age.getAsLong());
            epsCutoff = LabourRules.epsCutoff(c.birthDate(), (int) eps.getAsLong());
        }
        if (current.equals(requested)) {
            throw invalid("UNCHANGED");
        }
        // SRS-2-59: a new retirement date already past goes to Headquarters Establishment too.
        boolean hq = retirement != null && retirement.isBefore(parameters.today());
        String text = blank(c.reasonText());
        String reason = text == null ? c.reasonCode() : text;
        // Oracle review F2: the generated key of this INSERT, never MAX(id) read back.
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO identity_correction_request (employee_id, correction_type,"
                            + " current_value, requested_value, reason_code, reason_text,"
                            + " order_reference, order_date, issuing_authority, effective_date,"
                            + " recalculated_retirement_date, recalculated_eps_cutoff_date,"
                            + " downstream_impact_flag, higher_authority_review, request_status,"
                            + " raised_by, raised_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, 'PENDING_ATTACHMENT', ?, ?)",
                        "correction_id",
                        employeeId,
                        c.type(),
                        current,
                        requested,
                        c.reasonCode(),
                        text,
                        blank(c.orderReference()),
                        Date.valueOf(c.orderDate()),
                        c.issuingAuthority().strip(),
                        Date.valueOf(c.effectiveDate()),
                        retirement == null ? null : Date.valueOf(retirement),
                        epsCutoff == null ? null : Date.valueOf(epsCutoff),
                        retirement == null ? "N" : "Y",
                        hq ? "Y" : "N",
                        context.loginId(),
                        Timestamp.from(clock.instant()));
        DocumentStore.AttachmentView doc =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                String.valueOf(id),
                                "AS_PER_ORDER".equals(c.reasonCode()) ? "SANCTION_ORDER" : "OTHER",
                                blank(c.orderReference()),
                                c.orderDate(),
                                c.issuingAuthority().strip(),
                                document.fileName(),
                                document.content(),
                                true));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                hq ? "M02_CORRECTION_HQ" : "M02_CORRECTION",
                                SCREEN,
                                "EDIT",
                                ENTITY,
                                String.valueOf(id),
                                ActionType.UPDATE,
                                office.getId(),
                                payload,
                                c.effectiveDate(),
                                reason),
                        null);
        jdbc.update(
                "UPDATE identity_correction_request SET approval_id = ?, attachment_id = ?,"
                        + " request_status = ? WHERE correction_id = ?",
                item.id(),
                doc.id(),
                hq ? "PENDING_HQ_ESTT" : "SUBMITTED",
                id);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                (String) labour.get("LABOUR_NUMBER"),
                                "NAME".equals(c.type()) ? "name" : "birth_date",
                                current,
                                requested,
                                reason)
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(c.effectiveDate()));
        return item;
    }

    /** The final decision: an approved correction is applied to the person record. */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        long id = Long.parseLong(decision.entityKey());
        Map<String, Object> r =
                jdbc.queryForMap(
                        "SELECT employee_id, correction_type, requested_value,"
                                + " recalculated_retirement_date FROM identity_correction_request"
                                + " WHERE correction_id = ?",
                        id);
        String outcome = decision.approved() ? "APPLIED" : "REJECTED";
        if (decision.approved()) {
            long employee = ((Number) r.get("EMPLOYEE_ID")).longValue();
            String requested = (String) r.get("REQUESTED_VALUE");
            if ("NAME".equals(r.get("CORRECTION_TYPE"))) {
                Name name;
                try {
                    name = json.readValue(decision.payload(), Name.class);
                } catch (JacksonException ex) {
                    throw new IllegalStateException("Unreadable correction", ex);
                }
                persons.correct(employee, name.firstName(), name.lastName(), null, null);
            } else {
                persons.correct(
                        employee,
                        null,
                        null,
                        LocalDate.parse(requested),
                        date(r.get("RECALCULATED_RETIREMENT_DATE")));
            }
        }
        jdbc.update(
                "UPDATE identity_correction_request SET request_status = ?, applied_at = ? WHERE"
                        + " correction_id = ?",
                outcome,
                decision.approved() ? Timestamp.from(clock.instant()) : null,
                id);
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "request_status",
                                "SUBMITTED",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    /** The type, the reason of the M-16 list, the document's particulars and the file. */
    private void checkEntry(Correction c, Document document) {
        if (c == null || c.type() == null || !TYPES.contains(c.type())) {
            throw invalid("TYPE");
        }
        if (c.reasonCode() == null || !reasons().contains(c.reasonCode())) {
            throw invalid("REASON");
        }
        String text = blank(c.reasonText());
        if (text != null && (text.length() > 1000 || !InputPatterns.isPrintableText(text))) {
            throw invalid("REASON_TEXT");
        }
        if (c.effectiveDate() == null) {
            throw invalid("EFFECTIVE_DATE");
        }
        if (blank(c.issuingAuthority()) == null
                || c.orderDate() == null
                || ("AS_PER_ORDER".equals(c.reasonCode()) && blank(c.orderReference()) == null)) {
            throw invalid("ORDER");
        }
        if (document == null || document.content() == null || document.content().length == 0) {
            throw invalid("DOCUMENT");
        }
    }

    private Map<String, Object> labour(long employeeId) {
        return jdbc
                .queryForList(
                        "SELECT p.labour_number, p.depot_location_id, e.first_name, e.last_name,"
                            + " e.birth_date, e.comp_joining_date FROM labour_profile p JOIN"
                            + " employee e ON e.employee_id = p.employee_id WHERE p.employee_id = ?"
                            + " AND p.labour_status = 'ACTIVE'",
                        employeeId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private List<String> reasons() {
        Date today = Date.valueOf(parameters.today());
        return jdbc.queryForList(
                "SELECT v.value_code FROM reference_value v JOIN reference_list l ON l.list_id ="
                        + " v.list_id WHERE l.list_code = 'CORRECTION_REASON' AND v.effective_from"
                        + " <= ? AND (v.effective_to IS NULL OR v.effective_to >= ?)",
                String.class,
                today,
                today);
    }

    private static String fullName(Object first, Object last) {
        String l = last == null ? null : blank(last.toString());
        String f = first.toString().strip();
        return l == null ? f : f + " " + l;
    }

    private static LocalDate date(Object value) {
        return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.ENTRY, new Object[] {defect}, List.of(), null);
    }
}
