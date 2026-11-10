package in.gov.fci.payroll.general.business.model;

import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.Inputs;
import in.gov.fci.payroll.platform.error.FciException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.hibernate.type.YesNoConverter;

/**
 * {@code payroll_exception} (target DDL v4.0 S08): one entry of the single cross-module Exceptions
 * &amp; Holds worklist X-05 (SRS-G-9, FRS V0.5 §17.9.2). C-08 is the same store filtered to one run
 * and its gate-G2 items; gate G2 reads this table.
 *
 * <p>Lifecycle:
 *
 * <pre>
 * OPEN ──assign──▶ IN_PROGRESS ──clear (condition cleared, cause recorded)──▶ RESOLVED
 *   │                   │
 *   ├──accept (Checker, non-blocking G2 item, reason)──▶ ACCEPTED   (stays visible: "Accepted with Reason")
 *   └── raised on an HRMS / interface value ──▶ ROUTED_UPSTREAM ──clear when the corrected value arrives──▶ RESOLVED
 * </pre>
 *
 * Nothing is "resolved" by a button on the worklist: the value is corrected on the named correction
 * screen (or at source) and the module that raised the exception clears it, with the cause.
 */
@Entity
@Table(name = "payroll_exception")
public class ExceptionEntry {

    /** {@code exception_status} (CHECK of the DDL). */
    public enum Status {
        OPEN,
        IN_PROGRESS,
        RESOLVED,
        ACCEPTED,
        ROUTED_UPSTREAM
    }

    /**
     * {@code severity} (CHECK of the DDL). The C-08 screen vocabulary Critical / High / Medium /
     * Review has no agreed mapping to these values (Q-016, CF-21): the DDL values are stored and
     * shown.
     */
    public enum Severity {
        BLOCKING,
        WARNING,
        INFORMATIONAL,
        SECURITY
    }

    /** Field-source markers (KB field-source pattern; CHECK of the DDL). */
    public enum FieldSource {
        ENT,
        CALC,
        HRMS,
        RULE,
        SYS,
        ESS,
        INTF
    }

    /** The owners X-05 may assign (SRS X-05 "Owner" column; HRMS = corrected at source). */
    public static final Set<String> OWNERS =
            Set.of(
                    "PAYROLL_MAKER",
                    "LABOUR_DEALING_HAND",
                    "HRMS",
                    "SYSTEM_ADMINISTRATOR",
                    "RULE_ADMINISTRATOR",
                    "ESTABLISHMENT_HR");

    private static final Set<Status> UNSETTLED =
            Set.of(Status.OPEN, Status.IN_PROGRESS, Status.ROUTED_UPSTREAM);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exception_id")
    private Long id;

    @Column(name = "cycle_instance_id")
    private Long cycleInstanceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "module_code", length = 40)
    private String moduleCode;

    @Column(name = "exception_code", nullable = false, length = 40)
    private String exceptionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 15)
    private Severity severity;

    /** G0-G6, or null when the exception blocks no gate. */
    @Column(name = "blocks_gate", length = 4)
    private String blocksGate;

    @Column(name = "hrms_change_task_id")
    private Long hrmsChangeTaskId;

    @Column(name = "entity_name", length = 100)
    private String entityName;

    @Column(name = "field_name", length = 100)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_source", length = 10)
    private FieldSource fieldSource;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "resolvable_in_payroll", nullable = false, length = 1)
    private Boolean resolvableInPayroll;

    @Column(name = "correction_screen", length = 20)
    private String correctionScreen;

    @Column(name = "owner_role_code", length = 30)
    private String ownerRoleCode;

    @Column(name = "detail", nullable = false, length = 2000)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "exception_status", nullable = false, length = 20)
    private Status status;

    @Column(name = "accepted_by", length = 50)
    private String acceptedBy;

    @Column(name = "accepted_reason", length = 1000)
    private String acceptedReason;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ExceptionEntry() {}

    /**
     * A new exception. An exception on an HRMS- or interface-sourced value is "not resolvable in
     * Payroll" (SRS-G-9, SRS-EH-4) and starts ROUTED_UPSTREAM with owner HRMS / the Administrator;
     * the one exception is an HRMS value under an I-21 change task, which is decided on I-21 (DDL
     * {@code chk_exception_hrms_ro}).
     */
    public static ExceptionEntry raise(
            Long cycleInstanceId,
            Long employeeId,
            String moduleCode,
            String exceptionCode,
            Severity severity,
            String blocksGate,
            Long hrmsChangeTaskId,
            String entityName,
            String fieldName,
            FieldSource fieldSource,
            String correctionScreen,
            String detail,
            Instant now) {
        ExceptionEntry e = new ExceptionEntry();
        e.cycleInstanceId = cycleInstanceId;
        e.employeeId = employeeId;
        e.moduleCode = moduleCode;
        e.exceptionCode = exceptionCode;
        e.severity = severity;
        e.blocksGate = blocksGate;
        e.hrmsChangeTaskId = hrmsChangeTaskId;
        e.entityName = entityName;
        e.fieldName = fieldName;
        e.fieldSource = fieldSource;
        e.correctionScreen = correctionScreen;
        e.detail = detail;
        e.raisedAt = now;
        boolean upstream =
                (fieldSource == FieldSource.HRMS && hrmsChangeTaskId == null)
                        || fieldSource == FieldSource.INTF;
        e.resolvableInPayroll = !upstream;
        e.status = upstream ? Status.ROUTED_UPSTREAM : Status.OPEN;
        e.ownerRoleCode =
                fieldSource == FieldSource.HRMS && upstream
                        ? "HRMS"
                        : fieldSource == FieldSource.INTF ? "SYSTEM_ADMINISTRATOR" : null;
        return e;
    }

    /** Assigns an owner (Payroll Checker); an OPEN item becomes IN_PROGRESS. */
    public void assign(String owner) {
        if (!OWNERS.contains(owner)) {
            throw new FciException(GeneralErrorCode.EXCEPTION_OWNER);
        }
        requireUnsettled();
        this.ownerRoleCode = owner;
        if (status == Status.OPEN) {
            status = Status.IN_PROGRESS;
        }
    }

    /**
     * Accepts the exception instead of a correction (SRS-G-9): only an item that holds gate G2, is
     * not BLOCKING ("a blocking exception must be cleared") and can be corrected in Payroll, with a
     * recorded reason; the Checker's name and reason stay on the row ({@code
     * chk_exception_accept}). An item routed upstream "stays open until the corrected value
     * arrives" (SRS X-05 alternate flow): it is never accepted here; gate G2 itself may still be
     * accepted on C-01.
     */
    public void accept(String checker, String reason) {
        requireUnsettled();
        if (!"G2".equals(blocksGate) || severity == Severity.BLOCKING || !isResolvableInPayroll()) {
            throw new FciException(GeneralErrorCode.EXCEPTION_NOT_ACCEPTABLE);
        }
        if (reason == null || reason.isBlank()) {
            throw new FciException(GeneralErrorCode.EXCEPTION_REASON);
        }
        this.acceptedBy = Inputs.cut(checker, 50);
        this.acceptedReason = Inputs.cut(reason, 1000);
        this.status = Status.ACCEPTED;
    }

    /**
     * Closes the exception because its condition cleared (the module that raised it calls this when
     * it re-checks, or when the corrected value arrives from source). The caller records the cause
     * in the audit trail: "no exception is closed without a recorded cause".
     */
    public void clear(Instant now) {
        requireUnsettled();
        this.status = Status.RESOLVED;
        this.resolvedAt = now;
    }

    /** The statuses of an entry still to be settled (open, in progress, routed upstream). */
    public static Set<Status> unsettledStatuses() {
        return UNSETTLED;
    }

    /** Still on the worklist as something to act on (not RESOLVED and not ACCEPTED). */
    public boolean isUnsettled() {
        return UNSETTLED.contains(status);
    }

    private void requireUnsettled() {
        if (!isUnsettled()) {
            throw new FciException(
                    GeneralErrorCode.EXCEPTION_STATUS,
                    new Object[] {status.name()},
                    List.of(),
                    null);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getCycleInstanceId() {
        return cycleInstanceId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getModuleCode() {
        return moduleCode;
    }

    public String getExceptionCode() {
        return exceptionCode;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getBlocksGate() {
        return blocksGate;
    }

    public Long getHrmsChangeTaskId() {
        return hrmsChangeTaskId;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public FieldSource getFieldSource() {
        return fieldSource;
    }

    public boolean isResolvableInPayroll() {
        return Boolean.TRUE.equals(resolvableInPayroll);
    }

    public String getCorrectionScreen() {
        return correctionScreen;
    }

    public String getOwnerRoleCode() {
        return ownerRoleCode;
    }

    public String getDetail() {
        return detail;
    }

    public Status getStatus() {
        return status;
    }

    public String getAcceptedBy() {
        return acceptedBy;
    }

    public String getAcceptedReason() {
        return acceptedReason;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
