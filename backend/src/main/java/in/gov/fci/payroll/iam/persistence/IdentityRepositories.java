package in.gov.fci.payroll.iam.persistence;

import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.LoginPersona;
import in.gov.fci.payroll.iam.business.model.LoginPersonaRole;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.business.model.PasswordHistoryEntry;
import in.gov.fci.payroll.iam.business.model.UserPreference;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Target DDL S27: credentials, password history, login personas, sessions, preferences and OTP
 * challenges.
 */
public final class IdentityRepositories {

    private IdentityRepositories() {}

    public interface Otps extends Repository<OtpChallenge, Long> {
        Optional<OtpChallenge> findById(Long id);

        Optional<OtpChallenge> findFirstByUserIdAndGatedActionAndOutcomeOrderByIdDesc(
                Long userId, OtpGatedAction action, OtpChallenge.Outcome outcome);

        List<OtpChallenge> findByUserIdAndGatedActionAndOutcome(
                Long userId, OtpGatedAction action, OtpChallenge.Outcome outcome);

        OtpChallenge save(OtpChallenge challenge);

        @Query(
                "select count(o) from OtpChallenge o where o.userId = :userId"
                        + " and o.gatedAction = :action and o.issuedAt > :since")
        long countIssuedSince(
                @Param("userId") Long userId,
                @Param("action") OtpGatedAction action,
                @Param("since") Instant since);

        /**
         * Open challenges issued before {@code before} (housekeeping checks each one's validity).
         */
        List<OtpChallenge> findByOutcomeAndIssuedAtBefore(
                OtpChallenge.Outcome outcome, Instant before);
    }

    public interface Credentials extends Repository<AppUserCredential, Long> {
        Optional<AppUserCredential> findById(Long userId);

        AppUserCredential save(AppUserCredential credential);
    }

    public interface PasswordHistory
            extends Repository<PasswordHistoryEntry, PasswordHistoryEntry.Key> {
        @Query(
                "select h from PasswordHistoryEntry h where h.key.userId = :userId"
                        + " order by h.key.changedAt desc")
        List<PasswordHistoryEntry> findNewestFirst(@Param("userId") Long userId);

        PasswordHistoryEntry save(PasswordHistoryEntry entry);

        void delete(PasswordHistoryEntry entry);
    }

    public interface Personas extends Repository<LoginPersona, String> {
        List<LoginPersona> findAllByOrderByDisplayOrder();

        Optional<LoginPersona> findById(String code);
    }

    public interface PersonaRoles extends Repository<LoginPersonaRole, LoginPersonaRole.Key> {
        List<LoginPersonaRole> findAll();
    }

    public interface Preferences extends Repository<UserPreference, Long> {
        Optional<UserPreference> findById(Long userId);

        UserPreference save(UserPreference preference);
    }

    public interface Sessions extends Repository<AppSession, Long> {
        Optional<AppSession> findByTokenHash(String tokenHash);

        /** The session of a token (looked up by its hash; the token is never stored). */
        default Optional<AppSession> findByToken(String token) {
            return token == null
                    ? Optional.empty()
                    : findByTokenHash(SecureTokens.sha256Hex(token));
        }

        List<AppSession> findByUserIdAndEndedAtIsNull(Long userId);

        /** The user's most recent session: the scope a new sign-in starts in, if still valid. */
        Optional<AppSession> findFirstByUserIdOrderByStartedAtDesc(Long userId);

        /** The user's latest sessions, newest first: the A-01 login record (SRS-G-50). */
        List<AppSession> findTop20ByUserIdOrderByStartedAtDesc(Long userId);

        AppSession save(AppSession session);
    }
}
