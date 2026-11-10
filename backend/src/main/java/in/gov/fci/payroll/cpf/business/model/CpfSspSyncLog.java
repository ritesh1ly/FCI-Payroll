package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code cpf_ssp_sync_log}: every change Payroll sends, or intentionally does not send, to the CPF
 * Self-Service Portal (target DDL v4.0 Section 10; SRS-6-10, BR-INT-014, BR-CPF-035): a change not
 * synced is a SKIPPED row with its reason, never nothing.
 */
@Entity
@Table(name = "cpf_ssp_sync_log")
public class CpfSspSyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sync_id")
    private Long syncId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "sync_direction", nullable = false, length = 10)
    private String syncDirection;

    @Column(name = "sync_trigger", length = 30)
    private String syncTrigger;

    @Column(name = "sync_status", nullable = false, length = 15)
    private String syncStatus;

    @Column(name = "field_list", length = 1000)
    private String fieldList;

    @Column(name = "skip_reason", length = 500)
    private String skipReason;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Column(name = "failure_detail", length = 2000)
    private String failureDetail;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /**
     * Wave D review 7: the {@code failure_detail} of a row whose change waits for the caller's
     * commit (DDL statuses allow no QUEUED, so the row is FAILED with this exact marker); a real
     * failure's detail starts {@link #SEND_FAILED} instead.
     */
    public static final String QUEUED = "QUEUED";

    /** Wave D review 7: the prefix of a real send failure's detail. */
    public static final String SEND_FAILED = "SEND_FAILED: ";

    protected CpfSspSyncLog() {}

    /** An outbound change and what became of it (SUCCESS, FAILED or SKIPPED). */
    public static CpfSspSyncLog toSsp(
            Long employeeId,
            String trigger,
            String fields,
            String status,
            String skipReason,
            String failure,
            Instant at) {
        CpfSspSyncLog log = new CpfSspSyncLog();
        log.employeeId = employeeId;
        log.syncDirection = "TO_SSP";
        log.syncTrigger = trigger;
        log.syncStatus = status;
        log.fieldList = fields;
        log.skipReason = skipReason;
        log.failureDetail = failure;
        log.syncedAt = at;
        log.retryCount = 0;
        return log;
    }

    /**
     * The outcome of a change sent after its transaction committed (Wave C review): SUCCESS, or
     * FAILED with the failure for a later retry.
     *
     * @param failure the failure, or null when the portal took the change
     */
    public void sent(String failure, Instant at) {
        syncStatus = failure == null ? "SUCCESS" : "FAILED";
        failureDetail = failure == null ? null : SEND_FAILED + failure;
        syncedAt = at;
    }

    /** Wave D review 7: still waiting to be sent (not a failure the portal reported). */
    public boolean isQueued() {
        return "FAILED".equals(syncStatus) && QUEUED.equals(failureDetail);
    }

    /**
     * Wave D review 7: the idempotency key the change is sent with ({@code CPF-SSP-<sync_id>}): the
     * same for every send of this row.
     */
    public String idempotencyKey() {
        return idempotencyKeyOf(syncId);
    }

    /** The idempotency key of log row {@code syncId}. */
    public static String idempotencyKeyOf(Long syncId) {
        return "CPF-SSP-" + syncId;
    }

    public Long getSyncId() {
        return syncId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getSyncDirection() {
        return syncDirection;
    }

    public String getSyncTrigger() {
        return syncTrigger;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public String getFieldList() {
        return fieldList;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public Integer getRetryCount() {
        return retryCount;
    }
}
