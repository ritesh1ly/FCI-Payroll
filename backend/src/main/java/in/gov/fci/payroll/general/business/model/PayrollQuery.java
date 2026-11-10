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
import org.hibernate.type.YesNoConverter;

/**
 * {@code payroll_query} (target DDL v4.0 S08; X-06; SRS-G-11, FRS V0.5 §6.12.12): a member's or
 * staff user's question about one payroll record, tracked to resolution. "The query record tracks
 * status only; it never computes" — a correction it leads to goes through that correction's own
 * maker-checker flow.
 *
 * <p>Lifecycle (forward only): {@code OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED}.
 *
 * <ul>
 *   <li>{@link #respond}: the Payroll Maker records a response; OPEN or IN_PROGRESS -> IN_PROGRESS
 *       (a later response replaces the earlier one while the query is still in progress).
 *   <li>{@link #resolve}: requires a Maker response; IN_PROGRESS -> RESOLVED. For a query about a
 *       computed amount the resolver must be a Checker other than the responder ({@code
 *       chk_query_checker}).
 *   <li>{@link #close}: the system closes a RESOLVED query once the resolution is confirmed.
 * </ul>
 */
@Entity
@Table(name = "payroll_query")
public class PayrollQuery {

    /** {@code query_status} (CHECK of the DDL). */
    public enum Status {
        OPEN,
        IN_PROGRESS,
        RESOLVED,
        CLOSED
    }

    /** {@code raised_channel} (CHECK of the DDL): a member through eSeva, or payroll staff. */
    public enum Channel {
        ESEVA,
        STAFF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "query_id")
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "cycle_instance_id")
    private Long cycleInstanceId;

    /** The Payroll ID the query is raised against ({@code payroll_result_id}, as text). */
    @Column(name = "payroll_result_ref", length = 40)
    private String payrollResultRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "raised_channel", nullable = false, length = 15)
    private Channel channel;

    @Column(name = "query_text", nullable = false, length = 4000)
    private String queryText;

    @Enumerated(EnumType.STRING)
    @Column(name = "query_status", nullable = false, length = 15)
    private Status status;

    @Column(name = "raised_by", nullable = false, length = 50)
    private String raisedBy;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "maker_response", length = 4000)
    private String makerResponse;

    @Column(name = "responded_by", length = 50)
    private String respondedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "concerns_computed_amount", nullable = false, length = 1)
    private Boolean concernsComputedAmount;

    @Column(name = "resolution_text", length = 4000)
    private String resolutionText;

    @Column(name = "resolved_by", length = 50)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected PayrollQuery() {}

    /** A new OPEN query against one payroll record (SRS-G-11 main flow, step 1). */
    public static PayrollQuery raise(
            Long employeeId,
            Long cycleInstanceId,
            Long payrollResultId,
            Channel channel,
            String text,
            boolean concernsComputedAmount,
            String raisedBy,
            Instant now) {
        PayrollQuery q = new PayrollQuery();
        q.employeeId = employeeId;
        q.cycleInstanceId = cycleInstanceId;
        q.payrollResultRef = String.valueOf(payrollResultId);
        q.channel = channel;
        q.queryText = text;
        q.concernsComputedAmount = concernsComputedAmount;
        q.status = Status.OPEN;
        q.raisedBy = cut(raisedBy);
        q.raisedAt = now;
        return q;
    }

    /**
     * The Payroll Maker's response; the query moves to (or stays) IN_PROGRESS. The Maker, who
     * investigates, may find that a query concerns a computed amount (the raiser's flag is a first
     * guess) and set the flag, but never clears it: the flag decides that a Checker confirms the
     * resolution ("Where the query is about a computed amount, the Payroll Checker reviews and
     * confirms"), so the person it would exempt cannot remove it (SRS-22-5, separation of duties).
     */
    public void respond(String response, boolean computedAmount, String maker, Instant now) {
        requireStatus(Status.OPEN, Status.IN_PROGRESS);
        if (Boolean.TRUE.equals(concernsComputedAmount) && !computedAmount) {
            throw new FciException(GeneralErrorCode.QUERY_COMPUTED_FLAG);
        }
        this.concernsComputedAmount = computedAmount;
        this.makerResponse = response;
        this.respondedBy = cut(maker);
        this.respondedAt = now;
        this.status = Status.IN_PROGRESS;
    }

    /**
     * Resolves the query.
     *
     * @param byChecker whether the resolver acts as Payroll Checker (X-06 APPROVE)
     */
    public void resolve(String resolution, String resolver, boolean byChecker, Instant now) {
        requireStatus(Status.IN_PROGRESS);
        // X-06 field table: "Maker Response - required before the query can move to Resolved".
        if (makerResponse == null || makerResponse.isBlank()) {
            throw new FciException(GeneralErrorCode.QUERY_RESPONSE_REQUIRED);
        }
        if (Boolean.TRUE.equals(concernsComputedAmount)) {
            // Use case: "Where the query is about a computed amount, the Payroll Checker reviews
            // and
            // confirms the resolution" - and never the person who responded (chk_query_checker).
            if (!byChecker) {
                throw new FciException(GeneralErrorCode.QUERY_CHECKER_REQUIRED);
            }
            if (cut(resolver).equals(respondedBy)) {
                throw new FciException(GeneralErrorCode.QUERY_SAME_PERSON);
            }
        }
        this.resolutionText = resolution;
        this.resolvedBy = cut(resolver);
        this.resolvedAt = now;
        this.status = Status.RESOLVED;
    }

    /** The system closes a resolved query (use case: "once resolution is confirmed"). */
    public void close() {
        requireStatus(Status.RESOLVED);
        this.status = Status.CLOSED;
    }

    private void requireStatus(Status... allowed) {
        if (!List.of(allowed).contains(status)) {
            throw new FciException(
                    GeneralErrorCode.QUERY_STATUS, new Object[] {status.name()}, List.of(), null);
        }
    }

    /** Login ids are stored in VARCHAR2(50) columns; a longer id is cut, never rejected. */
    private static String cut(String login) {
        return Inputs.cut(login, 50);
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getCycleInstanceId() {
        return cycleInstanceId;
    }

    public String getPayrollResultRef() {
        return payrollResultRef;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getQueryText() {
        return queryText;
    }

    public Status getStatus() {
        return status;
    }

    public String getRaisedBy() {
        return raisedBy;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public String getMakerResponse() {
        return makerResponse;
    }

    public String getRespondedBy() {
        return respondedBy;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public boolean concernsComputedAmount() {
        return Boolean.TRUE.equals(concernsComputedAmount);
    }

    public String getResolutionText() {
        return resolutionText;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
