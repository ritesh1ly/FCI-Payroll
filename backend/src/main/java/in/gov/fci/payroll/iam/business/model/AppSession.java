package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code app_session} (target DDL v4.0 S05): one signed-in session. Only the hash of the session
 * token is stored ({@code session_token_hash}); the token itself lives in the server-side HTTP
 * session. FCILekha extension columns hold the ACTIVE SCOPE of ADR-0023 (persona, office, for eSeva
 * the user's own employee record); {@code active_access_groups} holds the groups it admits. Kept
 * server side and re-validated on every request; switch-scope rewrites it.
 */
@Entity
@Table(name = "app_session")
public class AppSession {

    /** {@code end_reason} values (target DDL v4.0). */
    public enum EndReason {
        LOGOUT,
        TIMEOUT,
        ABSOLUTE_LIMIT,
        ADMIN_REVOKE,
        SUPERSEDED,
        ROLE_CHANGE,
        REVOCATION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "session_token_hash", nullable = false, length = 128, updatable = false)
    private String tokenHash;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 30)
    private String endReason;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "active_access_groups", length = 500)
    private String activeGroupIds;

    @Column(name = "persona_code", length = 20)
    private String personaCode;

    @Column(name = "active_location_id")
    private Long activeLocationId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "switched_at")
    private Instant switchedAt;

    /**
     * The OTP that last confirmed the user in this session ({@code AUTH_LOGIN}): a step-up for a
     * sensitive screen or a role switch (SRS-G-48, Module 02 D-2); null until then.
     */
    @Column(name = "login_otp_challenge_id")
    private Long loginOtpChallengeId;

    /**
     * When that confirmation happened; it lasts the STEP_UP_VALIDITY setting [FCILekha extension].
     */
    @Column(name = "step_up_at")
    private Instant stepUpAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected AppSession() {}

    /** Opens a session for the token whose hash is {@code tokenHash} (never the token itself). */
    public static AppSession start(
            String tokenHash,
            Long userId,
            String personaCode,
            Long locationId,
            Collection<Long> groupIds,
            Long employeeId,
            String sourceIp,
            String userAgent,
            Instant now) {
        AppSession session = new AppSession();
        session.tokenHash = Objects.requireNonNull(tokenHash);
        session.userId = Objects.requireNonNull(userId);
        session.personaCode = Objects.requireNonNull(personaCode);
        session.activeLocationId = locationId;
        session.activeGroupIds = join(groupIds);
        session.employeeId = employeeId;
        session.sourceIp = sourceIp;
        session.userAgent =
                userAgent == null || userAgent.length() <= 400
                        ? userAgent
                        : userAgent.substring(0, 400);
        session.startedAt = now;
        return session;
    }

    public void switchScope(
            String personaCode, Long locationId, Collection<Long> groupIds, Instant now) {
        this.personaCode = personaCode;
        this.activeLocationId = locationId;
        this.activeGroupIds = join(groupIds);
        this.switchedAt = now;
    }

    /** The user confirmed this session with an OTP ({@code AUTH_LOGIN}) at {@code now}. */
    public void confirmStepUp(Long challengeId, Instant now) {
        this.loginOtpChallengeId = Objects.requireNonNull(challengeId);
        this.stepUpAt = Objects.requireNonNull(now);
    }

    public Instant getStepUpAt() {
        return stepUpAt;
    }

    public void end(EndReason reason, Instant now) {
        if (endedAt == null) {
            endedAt = now;
            endReason = reason.name();
        }
    }

    public boolean isOpen() {
        return endedAt == null;
    }

    public List<Long> activeGroupIdList() {
        if (activeGroupIds == null || activeGroupIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(activeGroupIds.split(",")).map(Long::valueOf).toList();
    }

    private static String join(Collection<Long> ids) {
        return ids == null || ids.isEmpty()
                ? null
                : ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPersonaCode() {
        return personaCode;
    }

    public Long getActiveLocationId() {
        return activeLocationId;
    }

    /**
     * In the eSeva scope: the member's own record, without an office (a staff scope always acts at
     * an office). SRS-SEC-14 gives this scope its own idle timeout.
     */
    public boolean isEsevaScope() {
        return activeLocationId == null;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    /** When the session started ({@code started_at}): the absolute timeout counts from here. */
    public Instant getCreatedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }

    /** The client address at sign-in (SRS-G-50 login record). */
    public String getSourceIp() {
        return sourceIp;
    }

    /** The browser's User-Agent at sign-in, at most 400 characters (SRS-G-50 login record). */
    public String getUserAgent() {
        return userAgent;
    }
}
