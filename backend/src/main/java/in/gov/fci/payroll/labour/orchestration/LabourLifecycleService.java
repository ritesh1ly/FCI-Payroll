package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
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
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Labour separation and Permanent Light Duty on M-02 (plan 20 phase 20e; SRS-2-30, SRS-2-49,
 * SRS-10-19; D20-16, D20-17). Establishment / HR of the labour's office proposes, the Payroll
 * Checker approves (routes M02_SEPARATION, M02_LIGHT_DUTY).
 *
 * <p>Separation (Retirement, Resignation, Death or Termination, the O-05 field table): from a date
 * in the current month or later, with no live work-slip of the labour from that date. Approved, the
 * gang membership and the Labour Master record end the day before, the person is SEPARATED and the
 * Full &amp; Final Settlement case opens through the employee module's separation event.
 *
 * <p>Permanent Light Duty: a handling labour becomes ancillary labour of the depot's Ancillary Pool
 * from a date, with the order and the pay option (Ancillary pay, or Protected pay with the Handling
 * wage level kept); the protected items wait for FCI's answer to OQ-5 (v0.4). Approved, the
 * designation changes, the gang membership moves to the pool and the old designation and gang stay
 * in history.
 *
 * <p>SRS-2-49 (l.4404) "Keep the choice in history; change it only by a new approved transaction":
 * a new order for a labour already on approved Permanent Light Duty changes only the pay option.
 * The designation, the pool membership and the old order stay; on approval the old order is
 * SUPERSEDED (DDL {@code supersedes_light_duty_id}) and the new one carries the option.
 */
@Service
@Loggable
public class LabourLifecycleService {

    static final String SCREEN = "M-02";
    static final String SEPARATION = "labour_separation";
    static final String LIGHT_DUTY = "labour_light_duty";

    /** SRS O-05 field table: the separation types of the Labour Master. */
    public static final Set<String> SEPARATION_TYPES =
            Set.of("RETIREMENT", "RESIGNATION", "DEATH", "TERMINATION");

    /** DDL {@code labour_light_duty.pay_option}. */
    public static final Set<String> PAY_OPTIONS = Set.of("ANCILLARY_PAY", "PROTECTED_PAY");

    /**
     * A separation as entered, with its order (SRS l.4429 "starts the action on M-02 with the order
     * attached"; NWNP l.4971): reference, date and issuing authority, carried to the Checker in the
     * approval payload as the Light Duty order is.
     */
    public record Separation(
            String type,
            LocalDate date,
            String reason,
            String orderReference,
            LocalDate orderDate,
            String issuingAuthority) {

        /** A separation without its order (refused: ORDER). */
        public Separation(String type, LocalDate date, String reason) {
            this(type, date, reason, null, null, null);
        }
    }

    /** A Permanent Light Duty order as entered. */
    public record LightDuty(
            Long toDesignationId,
            LocalDate effectiveDate,
            String payOption,
            BigDecimal protectedBasic,
            String orderReference,
            LocalDate orderDate,
            String issuingAuthority,
            String reason) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final LabourPersons persons;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public LabourLifecycleService(
            JdbcTemplate jdbc,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            LabourPersons persons,
            SystemParameters parameters,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.persons = persons;
        this.parameters = parameters;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** M-02: a separation of the labour for the Payroll Checker (SRS-2-30, SRS-10-19). */
    @Transactional
    public SubmittedApproval separate(AccessContext context, long employeeId, Separation s) {
        Map<String, Object> labour = labour(employeeId);
        OrgLocation office = officeOf(labour);
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        if (s == null || s.type() == null || !SEPARATION_TYPES.contains(s.type())) {
            throw invalid("TYPE");
        }
        requireReason(s.reason());
        if (s.date() == null || s.date().isBefore(YearMonth.from(parameters.today()).atDay(1))) {
            throw invalid("DATE");
        }
        // SRS l.4429 / l.4971: the separation order; its date is not after the separation date.
        if (blank(s.orderReference()) == null
                || s.orderDate() == null
                || blank(s.issuingAuthority()) == null
                || s.orderReference().strip().length() > 100
                || s.issuingAuthority().strip().length() > 150) {
            throw invalid("ORDER");
        }
        if (s.orderDate().isAfter(parameters.today())) {
            throw invalid("ORDER_DATE");
        }
        Integer slips =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_slip w JOIN work_slip_participant p ON"
                            + " p.work_slip_id = w.work_slip_id AND p.work_slip_date ="
                            + " w.work_slip_date WHERE p.employee_id = ? AND w.work_slip_date >= ?"
                            + " AND w.slip_status NOT IN ('AMENDED', 'CANCELLED')",
                        Integer.class,
                        employeeId,
                        Date.valueOf(s.date()));
        if (slips != null && slips > 0) {
            throw invalid("WORK_SLIP_AFTER");
        }
        requireNoMembershipFrom(employeeId, s.date());
        String payload;
        try {
            payload = json.writeValueAsString(s);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Separation payload", ex);
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "M02_SEPARATION",
                                SCREEN,
                                "EDIT",
                                SEPARATION,
                                String.valueOf(employeeId),
                                ActionType.END_DATE,
                                office.getId(),
                                payload,
                                s.date(),
                                s.reason().strip()),
                        null);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                SEPARATION,
                                (String) labour.get("LABOUR_NUMBER"),
                                "labour_status",
                                (String) labour.get("LABOUR_STATUS"),
                                "SEPARATION_PENDING",
                                s.type() + ": " + s.reason().strip())
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(s.date()));
        return item;
    }

    /** M-02: a Permanent Light Duty order for the Payroll Checker (SRS-2-49). */
    @Transactional
    public SubmittedApproval lightDuty(AccessContext context, long employeeId, LightDuty d) {
        Map<String, Object> labour = labour(employeeId);
        OrgLocation office = officeOf(labour);
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        if (d == null
                || d.effectiveDate() == null
                || d.effectiveDate().isBefore(parameters.today())) {
            throw invalid("DATE");
        }
        if (d.payOption() == null || !PAY_OPTIONS.contains(d.payOption())) {
            throw invalid("PAY_OPTION");
        }
        if ("PROTECTED_PAY".equals(d.payOption())
                && (d.protectedBasic() == null || d.protectedBasic().signum() <= 0)) {
            throw invalid("PROTECTED_BASIC");
        }
        if (blank(d.orderReference()) == null
                || d.orderDate() == null
                || blank(d.issuingAuthority()) == null) {
            throw invalid("ORDER");
        }
        requireReason(d.reason());
        Map<String, Object> member = handlingMembership(employeeId, d.effectiveDate()).orElse(null);
        if (member == null) {
            return payOptionChange(context, employeeId, labour, office, d);
        }
        Integer ancillary =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM org_designation WHERE designation_id = ? AND"
                                + " designation_family = ? AND gang_role_code = 'ANCILLARY'",
                        Integer.class,
                        java.util.Objects.requireNonNullElse(d.toDesignationId(), -1L),
                        labour.get("PAYROLL_TYPE"));
        if (ancillary == null || ancillary == 0) {
            throw invalid("DESIGNATION");
        }
        Long pool = pool(number(labour.get("DEPOT_LOCATION_ID")));
        requireNoPendingOrder(employeeId);
        // Oracle review F2: the generated key of this INSERT, never MAX(id) read back.
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO labour_light_duty (employee_id, change_reason_code,"
                                + " from_designation_id, to_designation_id, from_gang_id,"
                                + " ancillary_pool_id, effective_date, pay_option, protected_basic,"
                                + " order_reference, order_date, issuing_authority, reason,"
                                + " light_duty_status, created_by, created_at) VALUES (?,"
                                + " 'PERMANENT_LIGHT_DUTY', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " 'PENDING_APPROVAL', ?, ?)",
                        "light_duty_id",
                        employeeId,
                        labour.get("DESIGNATION_ID"),
                        d.toDesignationId(),
                        member.get("GANG_ID"),
                        pool,
                        Date.valueOf(d.effectiveDate()),
                        d.payOption(),
                        "PROTECTED_PAY".equals(d.payOption()) ? d.protectedBasic() : null,
                        d.orderReference().strip(),
                        Date.valueOf(d.orderDate()),
                        d.issuingAuthority().strip(),
                        d.reason().strip(),
                        context.loginId(),
                        Timestamp.from(clock.instant()));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "M02_LIGHT_DUTY",
                                SCREEN,
                                "EDIT",
                                LIGHT_DUTY,
                                String.valueOf(id),
                                ActionType.UPDATE,
                                office.getId(),
                                null,
                                d.effectiveDate(),
                                d.reason().strip()),
                        null);
        jdbc.update(
                "UPDATE labour_light_duty SET approval_id = ? WHERE light_duty_id = ?",
                item.id(),
                id);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                LIGHT_DUTY,
                                (String) labour.get("LABOUR_NUMBER"),
                                "designation",
                                String.valueOf(labour.get("DESIGNATION_ID")),
                                String.valueOf(d.toDesignationId()),
                                "Permanent Light Duty, " + d.payOption())
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(d.effectiveDate()));
        return item;
    }

    /**
     * SRS-2-49 (l.4404): a new order for a labour on approved Permanent Light Duty changes only the
     * pay option. Refused: no approved order (NOT_HANDLING), a designation other than the one the
     * order gave (DESIGNATION), a date before the order in force (DATE), the option already in
     * force (SAME_PAY_OPTION), an order already with the Checker (PENDING).
     */
    private SubmittedApproval payOptionChange(
            AccessContext context,
            long employeeId,
            Map<String, Object> labour,
            OrgLocation office,
            LightDuty d) {
        Map<String, Object> current =
                jdbc
                        .queryForList(
                                "SELECT light_duty_id, from_designation_id, to_designation_id,"
                                        + " from_gang_id, ancillary_pool_id, effective_date,"
                                        + " pay_option FROM labour_light_duty WHERE employee_id ="
                                        + " ? AND light_duty_status = 'APPROVED' ORDER BY"
                                        + " effective_date DESC, light_duty_id DESC",
                                employeeId)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> invalid("NOT_HANDLING"));
        long designation = number(current.get("TO_DESIGNATION_ID"));
        if (number(labour.get("DESIGNATION_ID")) != designation
                || (d.toDesignationId() != null && d.toDesignationId() != designation)) {
            throw invalid("DESIGNATION");
        }
        LocalDate inForce =
                new java.sql.Date(((java.util.Date) current.get("EFFECTIVE_DATE")).getTime())
                        .toLocalDate();
        if (d.effectiveDate().isBefore(inForce)) {
            throw invalid("DATE");
        }
        String before = (String) current.get("PAY_OPTION");
        if (d.payOption().equals(before)) {
            throw invalid("SAME_PAY_OPTION");
        }
        requireNoPendingOrder(employeeId);
        long previous = number(current.get("LIGHT_DUTY_ID"));
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO labour_light_duty (employee_id, change_reason_code,"
                                + " from_designation_id, to_designation_id, from_gang_id,"
                                + " ancillary_pool_id, effective_date, pay_option, protected_basic,"
                                + " supersedes_light_duty_id, order_reference, order_date,"
                                + " issuing_authority, reason, light_duty_status, created_by,"
                                + " created_at) VALUES (?, 'PERMANENT_LIGHT_DUTY', ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?)",
                        "light_duty_id",
                        employeeId,
                        current.get("FROM_DESIGNATION_ID"),
                        designation,
                        current.get("FROM_GANG_ID"),
                        current.get("ANCILLARY_POOL_ID"),
                        Date.valueOf(d.effectiveDate()),
                        d.payOption(),
                        "PROTECTED_PAY".equals(d.payOption()) ? d.protectedBasic() : null,
                        previous,
                        d.orderReference().strip(),
                        Date.valueOf(d.orderDate()),
                        d.issuingAuthority().strip(),
                        d.reason().strip(),
                        context.loginId(),
                        Timestamp.from(clock.instant()));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "M02_LIGHT_DUTY",
                                SCREEN,
                                "EDIT",
                                LIGHT_DUTY,
                                String.valueOf(id),
                                ActionType.UPDATE,
                                office.getId(),
                                null,
                                d.effectiveDate(),
                                d.reason().strip()),
                        null);
        jdbc.update(
                "UPDATE labour_light_duty SET approval_id = ? WHERE light_duty_id = ?",
                item.id(),
                id);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                LIGHT_DUTY,
                                (String) labour.get("LABOUR_NUMBER"),
                                "pay_option",
                                before,
                                d.payOption(),
                                "Permanent Light Duty pay option, " + d.payOption())
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(d.effectiveDate()));
        return item;
    }

    private void requireNoPendingOrder(long employeeId) {
        Integer pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_light_duty WHERE employee_id = ? AND"
                                + " light_duty_status = 'PENDING_APPROVAL'",
                        Integer.class,
                        employeeId);
        if (pending != null && pending > 0) {
            throw invalid("PENDING");
        }
    }

    /** The Payroll Checker's decision on a separation or a Permanent Light Duty order. */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (decision.concerns(SEPARATION)) {
            separationDecided(decision);
        } else if (decision.concerns(LIGHT_DUTY)) {
            lightDutyDecided(decision);
        }
    }

    private void separationDecided(ApprovalDecided decision) {
        long employeeId = Long.parseLong(decision.entityKey());
        String outcome = decision.approved() ? "SEPARATED" : "REJECTED";
        if (decision.approved()) {
            Separation s;
            try {
                s = json.readValue(decision.payload(), Separation.class);
            } catch (JacksonException ex) {
                throw new IllegalStateException("Unreadable separation", ex);
            }
            // A membership approved after the proposal may start on the separation date by now.
            requireNoMembershipFrom(employeeId, s.date());
            LocalDate last = s.date().minusDays(1);
            jdbc.update(
                    "UPDATE labour_gang_membership SET effective_to = ?, end_reason = 'SEPARATION'"
                        + " WHERE employee_id = ? AND (effective_to IS NULL OR effective_to > ?)",
                    Date.valueOf(last),
                    employeeId,
                    Date.valueOf(last));
            jdbc.update(
                    "UPDATE labour_profile SET labour_status = ?, effective_to = ?, row_version ="
                            + " row_version + 1 WHERE employee_id = ?",
                    "RETIREMENT".equals(s.type()) ? "RETIRED" : "SEPARATED",
                    Date.valueOf(last),
                    employeeId);
            persons.separate(employeeId, s.type(), s.date(), s.reason(), decision.checkerLoginId());
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                SEPARATION,
                                String.valueOf(employeeId),
                                "labour_status",
                                "SEPARATION_PENDING",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    private void lightDutyDecided(ApprovalDecided decision) {
        long id = Long.parseLong(decision.entityKey());
        Map<String, Object> d =
                jdbc.queryForMap(
                        "SELECT l.employee_id, l.to_designation_id, l.from_gang_id,"
                            + " l.effective_date, l.supersedes_light_duty_id, p.pool_gang_id FROM"
                            + " labour_light_duty l JOIN ancillary_pool p ON p.ancillary_pool_id ="
                            + " l.ancillary_pool_id WHERE l.light_duty_id = ?",
                        id);
        String outcome = decision.approved() ? "APPROVED" : "REJECTED";
        jdbc.update(
                "UPDATE labour_light_duty SET light_duty_status = ? WHERE light_duty_id = ?",
                outcome,
                id);
        Object superseded = d.get("SUPERSEDES_LIGHT_DUTY_ID");
        if (decision.approved() && superseded != null) {
            // SRS-2-49: a pay-option change; the designation and the pool membership stay.
            jdbc.update(
                    "UPDATE labour_light_duty SET light_duty_status = 'SUPERSEDED' WHERE"
                            + " light_duty_id = ? AND light_duty_status = 'APPROVED'",
                    superseded);
        } else if (decision.approved()) {
            long employee = number(d.get("EMPLOYEE_ID"));
            LocalDate from =
                    new java.sql.Date(((java.util.Date) d.get("EFFECTIVE_DATE")).getTime())
                            .toLocalDate();
            jdbc.update(
                    "UPDATE labour_gang_membership SET effective_to = ?, end_reason = 'LIGHT_DUTY'"
                            + " WHERE employee_id = ? AND gang_id = ? AND effective_from < ? AND"
                            + " (effective_to IS NULL OR effective_to >= ?)",
                    Date.valueOf(from.minusDays(1)),
                    employee,
                    d.get("FROM_GANG_ID"),
                    Date.valueOf(from),
                    Date.valueOf(from));
            jdbc.update(
                    "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                            + " effective_from, approval_id) VALUES (?, ?, 'ANCILLARY', ?, ?)",
                    d.get("POOL_GANG_ID"),
                    employee,
                    Date.valueOf(from),
                    decision.approvalId());
            persons.move(employee, null, null, null, number(d.get("TO_DESIGNATION_ID")));
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                LIGHT_DUTY,
                                String.valueOf(id),
                                "light_duty_status",
                                "PENDING_APPROVAL",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    // ---------------------------------------------------------------- reads

    private Map<String, Object> labour(long employeeId) {
        return jdbc
                .queryForList(
                        "SELECT p.labour_number, p.payroll_type, p.depot_location_id,"
                            + " p.labour_status, e.designation_id FROM labour_profile p JOIN"
                            + " employee e ON e.employee_id = p.employee_id WHERE p.employee_id = ?"
                            + " AND p.labour_status = 'ACTIVE'",
                        employeeId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private java.util.Optional<Map<String, Object>> handlingMembership(
            long employeeId, LocalDate date) {
        return jdbc
                .queryForList(
                        "SELECT m.gang_id FROM labour_gang_membership m JOIN labour_gang g ON"
                            + " g.gang_id = m.gang_id WHERE m.employee_id = ? AND m.gang_role ="
                            + " 'HANDLING' AND g.gang_type = 'HANDLING' AND m.effective_from <= ?"
                            + " AND (m.effective_to IS NULL OR m.effective_to >= ?)",
                        employeeId,
                        Date.valueOf(date),
                        Date.valueOf(date))
                .stream()
                .findFirst();
    }

    private Long pool(long depotId) {
        return jdbc
                .queryForList(
                        "SELECT ancillary_pool_id FROM ancillary_pool WHERE depot_location_id = ?"
                                + " AND pool_status = 'ACTIVE'",
                        Long.class,
                        depotId)
                .stream()
                .findFirst()
                .orElseThrow(() -> invalid("NO_POOL"));
    }

    private OrgLocation officeOf(Map<String, Object> labour) {
        return org.cycleUnitOf(number(labour.get("DEPOT_LOCATION_ID")))
                .orElseThrow(() -> invalid("DEPOT_OFFICE"));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw invalid("REASON");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    /**
     * SRS-10-19 / SRS-2-30: on separation every gang membership ends the day before the separation
     * date (DDL chk_gang_membership_span: an end never before its start). A membership that starts
     * on or after that date would end before it began, so the separation is refused naming it
     * (MEMBERSHIP_FROM; OD-100, CLR-154) instead of failing on the constraint.
     */
    private void requireNoMembershipFrom(long employeeId, LocalDate date) {
        Integer later =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang_membership WHERE employee_id = ? AND"
                                + " effective_from >= ?",
                        Integer.class,
                        employeeId,
                        Date.valueOf(date));
        if (later != null && later > 0) {
            throw invalid("MEMBERSHIP_FROM");
        }
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.ENTRY, new Object[] {defect}, List.of(), null);
    }
}
