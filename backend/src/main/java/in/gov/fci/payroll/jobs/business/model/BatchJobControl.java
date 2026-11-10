package in.gov.fci.payroll.jobs.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A Stop / Pause / Resume / Restart command ({@code batch_job_control}). Written by the online
 * instance; applied (or rejected, with the reason) by a batch instance.
 */
@Entity
@Table(name = "batch_job_control")
public class BatchJobControl {

    public enum Status {
        PENDING,
        APPLIED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "control_id")
    private Long id;

    @Column(name = "job_request_id", nullable = false)
    private Long jobRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "command", nullable = false, length = 10)
    private ControlCommand command;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_status", nullable = false, length = 10)
    private Status status;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_by_login", nullable = false, length = 150)
    private String requestedByLogin;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "applied_by", length = 100)
    private String appliedBy;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "outcome_message", length = 500)
    private String outcomeMessage;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long version;

    protected BatchJobControl() {}

    public static BatchJobControl request(
            Long jobRequestId,
            ControlCommand command,
            Long userId,
            String login,
            String reason,
            Instant now) {
        BatchJobControl control = new BatchJobControl();
        control.jobRequestId = jobRequestId;
        control.command = command;
        control.status = Status.PENDING;
        control.requestedBy = userId;
        control.requestedByLogin = login;
        control.reason = reason;
        control.requestedAt = now;
        return control;
    }

    public void applied(String instance, String message, Instant now) {
        this.status = Status.APPLIED;
        this.appliedBy = instance;
        this.appliedAt = now;
        this.outcomeMessage = message;
    }

    public void rejected(String instance, String message, Instant now) {
        this.status = Status.REJECTED;
        this.appliedBy = instance;
        this.appliedAt = now;
        this.outcomeMessage =
                message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }

    public Long getId() {
        return id;
    }

    public Long getJobRequestId() {
        return jobRequestId;
    }

    public ControlCommand getCommand() {
        return command;
    }

    public Status getStatus() {
        return status;
    }

    public String getRequestedByLogin() {
        return requestedByLogin;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getReason() {
        return reason;
    }

    public String getAppliedBy() {
        return appliedBy;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public String getOutcomeMessage() {
        return outcomeMessage;
    }
}
