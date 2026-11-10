package in.gov.fci.payroll.audit.api;

import in.gov.fci.payroll.audit.business.model.AuditEvent;
import in.gov.fci.payroll.audit.business.model.SecurityEventRecord;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.logging.CriticalLog;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import in.gov.fci.payroll.platform.logging.SecurityEvent;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code audit_event} rows (target DDL S05) and the matching security log line. Every row
 * carries before, after, user, timestamp, effective date and reason (BR-AUDIT-003). Sign-in events
 * use entity {@code app_user} and field {@code LOGIN}, {@code LOGOUT}, {@code LOCKOUT} …
 *
 * <p>Security incidents (lockout, unlock, out-of-scope access, OTP lockout) also go to {@code
 * security_event} (target DDL v4.0), in their own transaction so a refused request still leaves its
 * trace.
 */
@Service
public class AuditService {

    /** One audit row. {@code oldValue}/{@code newValue} are never secrets or hashes. */
    public record Entry(
            String entityName,
            String entityKey,
            String fieldName,
            String oldValue,
            String newValue,
            String reason,
            Long approvalId,
            ChangeType changeType,
            String moduleCode,
            LocalDate effectiveDate) {

        public static Entry change(
                String entity,
                String key,
                String field,
                String oldValue,
                String newValue,
                String reason) {
            return new Entry(
                    entity,
                    key,
                    field,
                    oldValue,
                    newValue,
                    reason,
                    null,
                    ChangeType.CHANGE,
                    null,
                    null);
        }

        public Entry approval(Long id) {
            return new Entry(
                    entityName,
                    entityKey,
                    fieldName,
                    oldValue,
                    newValue,
                    reason,
                    id,
                    changeType,
                    moduleCode,
                    effectiveDate);
        }

        public Entry module(String code) {
            return new Entry(
                    entityName,
                    entityKey,
                    fieldName,
                    oldValue,
                    newValue,
                    reason,
                    approvalId,
                    changeType,
                    code,
                    effectiveDate);
        }

        public Entry type(ChangeType type) {
            return new Entry(
                    entityName,
                    entityKey,
                    fieldName,
                    oldValue,
                    newValue,
                    reason,
                    approvalId,
                    type,
                    moduleCode,
                    effectiveDate);
        }

        /**
         * The date from which the change applies (SRS-G-2, X-04 Effective Date); without it the row
         * carries the business date it was recorded on: a change effective at once applies from its
         * recording date (OD-50, US-GAP-130; FCI to confirm), so the row's Effective Date is never
         * empty (SRS-20-2).
         */
        public Entry effective(LocalDate date) {
            return new Entry(
                    entityName,
                    entityKey,
                    fieldName,
                    oldValue,
                    newValue,
                    reason,
                    approvalId,
                    changeType,
                    moduleCode,
                    date);
        }
    }

    /** {@code security_event.event_type} values written by the application. */
    public enum Security {
        /** Too many wrong passwords: the account is locked for a while. */
        ACCOUNT_LOCKOUT,
        /** An administrator cleared a lockout, with a reason. */
        ACCOUNT_UNLOCK,
        /** Too many wrong OTP codes: the challenge is locked out. */
        OTP_LOCKOUT,
        /** An action on data outside the session's office scope was refused. */
        OUT_OF_SCOPE_ACCESS,
        /** A scope (office / role) the user does not hold was asked for. */
        SCOPE_SWITCH_DENIED,
        /** A session cookie came from another browser or address than the one that signed in. */
        SESSION_HIJACK_SUSPECTED,
        /** An administrator ended a user's open sessions, with a reason (SRS-G-49). */
        SESSION_TERMINATED,
        /** An administrator suspended an account, alone or in a group, with a reason (SRS-G-49). */
        ACCOUNT_SUSPENDED,
        /** A request tried to change or delete a history row through the API (SRS-20-19). */
        HISTORY_EDIT_ATTEMPT,
        /**
         * The database refused a change to the audit trail or the Legacy Audit table (SRS-20-21).
         */
        AUDIT_TAMPER,
        /** OD-130: a refused call that counts as an offence of the signed-in user. */
        UNAUTHORIZED_ACCESS,
        /** OD-130: a refused call that adds no count: the switch is off, or its session has one. */
        UNAUTHORIZED_ACCESS_NOTED,
        /** OD-130: the account was suspended for its offences; the detail is the justification. */
        ACCOUNT_SUSPENDED_OFFENCE,
        /** OD-130: an administrator reinstated the account: the offence count starts again. */
        OFFENCE_COUNT_RESET
    }

    /** One {@code security_event} row, as the A-01 offence record shows it. */
    public record SecurityEventView(
            String type, String target, String detail, String sourceIp, Instant occurredAt) {}

    /** Administrator actions recorded as security events; every other type is a failure. */
    private static final java.util.Set<Security> ADMIN_ACTIONS =
            java.util.EnumSet.of(
                    Security.ACCOUNT_UNLOCK,
                    Security.SESSION_TERMINATED,
                    Security.ACCOUNT_SUSPENDED,
                    Security.OFFENCE_COUNT_RESET);

    /** OD-130: the {@code security_event} types that are also a critical log event. */
    private static final Map<Security, CriticalLog.Event> CRITICAL =
            Map.of(
                    Security.ACCOUNT_LOCKOUT, CriticalLog.Event.ACCOUNT_LOCKED,
                    Security.OTP_LOCKOUT, CriticalLog.Event.OTP_LIMIT_EXCEEDED,
                    Security.SESSION_HIJACK_SUSPECTED, CriticalLog.Event.SESSION_HIJACK_SUSPECTED,
                    Security.AUDIT_TAMPER, CriticalLog.Event.AUDIT_TAMPER);

    private final AuditRepositories.Audit audit;
    private final AuditRepositories.SecurityEvents securityEvents;
    private final SecurityAuditLogger securityLog;
    private final Clock clock;
    private final ApiProperties properties;
    private final ObjectProvider<CurrentAccess> access;

    public AuditService(
            AuditRepositories.Audit audit,
            AuditRepositories.SecurityEvents securityEvents,
            SecurityAuditLogger securityLog,
            Clock clock,
            ApiProperties properties,
            ObjectProvider<CurrentAccess> access) {
        this.access = access;
        this.audit = audit;
        this.securityEvents = securityEvents;
        this.securityLog = securityLog;
        this.clock = clock;
        this.properties = properties;
    }

    /** Who changed a record last and when (SRS-15-29: a stale save names the change). */
    public record LastChange(String changedBy, Instant changedAt) {}

    /** The latest audited change of a record, or empty when it has none. */
    @Transactional(readOnly = true)
    public Optional<LastChange> lastChange(String entity, String key) {
        return audit.findFirstByEntityNameAndEntityKeyOrderByChangedAtDescIdDesc(entity, key)
                .map(e -> new LastChange(e.getChangedBy(), e.getChangedAt()));
    }

    /** Within the caller's transaction: the audit row commits or rolls back with the change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, Entry entry) {
        write(actor, entry);
    }

    /**
     * Several rows within the caller's transaction (one per changed field, {@link FieldChanges}):
     * all commit or roll back with the change (SRS-20-18).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAll(String actor, java.util.Collection<Entry> entries) {
        entries.forEach(entry -> write(actor, entry));
    }

    /**
     * In its own transaction: for events that must persist although the caller fails (a failed
     * sign-in, a denied action).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(String actor, Entry entry) {
        write(actor, entry);
    }

    /**
     * SRS-20-27: "Also record in the audit trail ... submissions blocked for a missing attachment".
     * In its own transaction, so the row is kept although the refused submission rolls back.
     *
     * @param entityKey the record's key, or "-" when the record does not exist yet
     * @param missing what was missing, e.g. "order copy", "receipt"
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void submissionBlocked(
            String actor, String entityName, String entityKey, String module, String missing) {
        write(
                actor,
                Entry.change(
                                entityName,
                                entityKey == null || entityKey.isBlank() ? "-" : entityKey,
                                "SUBMISSION_BLOCKED",
                                null,
                                "ATTACHMENT_MISSING",
                                "Submission blocked: " + missing + " missing")
                        .module(module));
    }

    /**
     * Records a security event ({@code security_event}) and its security log line, in its own
     * transaction. {@code detail} holds identifiers and reason codes only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void securityEvent(
            Security type, String actor, String targetEntity, String targetKey, String detail) {
        write(type, actor, targetEntity, targetKey, detail);
        CriticalLog.Event critical = CRITICAL.get(type);
        if (critical != null) {
            // The target key is an id, never the login: an alert reads it, no person is named.
            CriticalLog.log(
                    critical,
                    CriticalLog.fields(
                            "target",
                            targetEntity + ":" + targetKey,
                            "ip",
                            MDC.get(MdcKeys.CLIENT_IP)));
        }
    }

    /**
     * OD-130: as {@link #securityEvent}, in the caller's transaction: the event stands or falls
     * with the change it records (the offence count, the suspension). Never a critical log here:
     * the caller writes it once the transaction committed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void securityEventInTransaction(
            Security type, String actor, String targetEntity, String targetKey, String detail) {
        write(type, actor, targetEntity, targetKey, detail);
    }

    /** OD-130: {@code actor}'s events of {@code type} after {@code after}. */
    @Transactional(readOnly = true)
    public long countSecurityEvents(String actor, Security type, Instant after) {
        return securityEvents.countByActingUserAndEventTypeAndOccurredAtGreaterThan(
                truncate(actor, 50), type.name(), after);
    }

    /** OD-130: {@code actor} has an event of {@code type} on this target entity. */
    @Transactional(readOnly = true)
    public boolean hasSecurityEvent(String actor, Security type, String targetEntity) {
        return securityEvents.existsByActingUserAndEventTypeAndTargetEntity(
                truncate(actor, 50), type.name(), targetEntity);
    }

    /** OD-130: when the latest event of {@code type} on the target happened, if ever. */
    @Transactional(readOnly = true)
    public Optional<Instant> latestSecurityEvent(
            Security type, String targetEntity, String targetKey) {
        return securityEvents
                .findFirstByEventTypeAndTargetEntityAndTargetKeyOrderByOccurredAtDesc(
                        type.name(), targetEntity, targetKey)
                .map(SecurityEventRecord::getOccurredAt);
    }

    /** OD-130: {@code actor}'s newest events of {@code type} after {@code after}, at most limit. */
    @Transactional(readOnly = true)
    public List<SecurityEventView> recentSecurityEvents(
            String actor, Security type, Instant after, int limit) {
        return securityEvents
                .findByActingUserAndEventTypeAndOccurredAtGreaterThanOrderByOccurredAtDesc(
                        truncate(actor, 50), type.name(), after, PageRequest.of(0, limit))
                .stream()
                .map(AuditService::view)
                .toList();
    }

    /** OD-130: the page of {@code actor}'s events of these types, newest first (A-01). */
    @Transactional(readOnly = true)
    public Page<SecurityEventView> securityEventsOf(
            String actor, java.util.Collection<Security> types, int page, int size) {
        return securityEvents
                .findByActingUserAndEventTypeInOrderByOccurredAtDesc(
                        truncate(actor, 50),
                        types.stream().map(Security::name).toList(),
                        PageRequest.of(page, size))
                .map(AuditService::view);
    }

    private static SecurityEventView view(SecurityEventRecord row) {
        return new SecurityEventView(
                row.getEventType(),
                row.getTargetKey(),
                row.getDetail(),
                row.getSourceIp(),
                row.getOccurredAt());
    }

    private void write(
            Security type, String actor, String targetEntity, String targetKey, String detail) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String by = actor == null || actor.isBlank() ? "anonymous" : truncate(actor, 50);
        securityEvents.save(
                SecurityEventRecord.of(
                        type.name(),
                        by,
                        targetEntity,
                        targetKey,
                        detail,
                        MDC.get(MdcKeys.CLIENT_IP),
                        now));
        boolean failure = !ADMIN_ACTIONS.contains(type);
        securityLog.log(
                (failure
                                ? SecurityEvent.failure(type.name(), by, detail)
                                : SecurityEvent.success(type.name(), by))
                        .withTarget(targetEntity, targetKey));
    }

    /**
     * The office of the signed-in session making the change (A-05 shows a non-HQ scope only its own
     * offices' rows); null without one (sign-in attempts, batch jobs, start-up).
     */
    private Long actingLocation() {
        CurrentAccess current = access.getIfAvailable();
        if (current == null) {
            return null;
        }
        try {
            return current.current().locationId();
        } catch (RuntimeException noSession) {
            return null;
        }
    }

    /**
     * BR-AUDIT-003 / SRS-20-2: "a row missing any of these is not a valid audit entry", so a row
     * without a reason is refused, never stored with a placeholder; the change it records rolls
     * back with it (SRS-20-18). Every caller states why: the user's reason, or the action the
     * system took. The effective date is never missing: without a stated one the change applies
     * from the business date (India) it is recorded on ({@link Entry#effective}; OD-50, US-GAP-130:
     * the recorded date of an immediately effective change, kept as a default rather than stated by
     * each of the 180-odd callers; FCI to confirm).
     */
    static void requireComplete(Entry entry) {
        if (entry.reason() == null || entry.reason().isBlank()) {
            throw new IllegalArgumentException(
                    "BR-AUDIT-003: an audit row needs a reason ("
                            + entry.entityName()
                            + "."
                            + entry.fieldName()
                            + ")");
        }
    }

    private void write(String actor, Entry entry) {
        requireComplete(entry);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = LocalDate.ofInstant(now, properties.businessZone());
        String by = actor == null || actor.isBlank() ? "system" : truncate(actor, 50);
        audit.save(
                AuditEvent.of(
                        entry.entityName(),
                        truncate(entry.entityKey(), 100),
                        entry.fieldName(),
                        entry.oldValue(),
                        entry.newValue(),
                        by,
                        now,
                        entry.effectiveDate() == null ? today : entry.effectiveDate(),
                        truncate(entry.reason(), 500),
                        entry.approvalId(),
                        entry.changeType(),
                        MDC.get(MdcKeys.CLIENT_IP),
                        entry.moduleCode(),
                        actingLocation()));
        boolean failure = entry.newValue() != null && entry.newValue().startsWith("FAILURE");
        String type = eventType(entry.fieldName());
        SecurityEvent event =
                (failure
                                ? SecurityEvent.failure(type, by, entry.newValue())
                                : SecurityEvent.success(type, by))
                        .withTarget(entry.entityName(), entry.entityKey());
        securityLog.log(event);
    }

    /**
     * {@code LOGIN} stays {@code LOGIN}; {@code mobileNumber} or {@code revoked_at} become {@code
     * AUDIT_MOBILE_NUMBER}. Always a valid security-log type (upper case and underscores, 3 to 64
     * characters): digits and other signs of a field name ({@code level_1}, a rate line key) are
     * dropped, so an unusual field name can never fail the change it records.
     */
    static String eventType(String field) {
        String snake =
                field.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                        .toUpperCase(java.util.Locale.ROOT)
                        .replaceAll("[^A-Z]+", "_")
                        .replaceAll("^_+|_+$", "");
        if (snake.isEmpty()) {
            return "AUDIT_CHANGE";
        }
        String type = field.equals(snake) ? snake : "AUDIT_" + snake;
        if (type.length() < 3) {
            return "AUDIT_" + type;
        }
        return type.length() <= 64 ? type : type.substring(0, 64).replaceAll("_+$", "");
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
