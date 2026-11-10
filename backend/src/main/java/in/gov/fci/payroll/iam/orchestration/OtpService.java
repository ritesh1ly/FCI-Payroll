package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.notification.SmsSender;
import in.gov.fci.payroll.platform.security.KeyMaterial;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time passwords for the gated actions ({@code otp_challenge.gated_action}, target DDL v4.0);
 * {@code AUTH_LOGIN} confirms a sensitive role after sign-in (step-up and role switch, Module 02
 * D-2), never the sign-in itself. Six digits, sent to the acting user's own registered contact,
 * stored only as an HMAC, valid for the A-07 OTP validity, the A-07 OTP retry limit (SRS-G-22; five
 * until set), single use, at most five per hour per action. A new challenge abandons the user's
 * older open one for the same action; a lockout is a security event, and while the A-07 cooling-off
 * period runs after it no new challenge of that action is issued (SRS-G-22, SRS-EH-13: the action
 * is blocked and the user starts again afterwards).
 *
 * <p>Owner's decision of 2026-10-21: after {@code fci.api.otp.alert-after-failures} (3) wrong codes
 * on one challenge the user is alerted by e-mail and SMS, and every verified code is notified the
 * same way; the account itself is never locked for wrong codes. A failed notification never fails
 * the user's action.
 */
@Service
@Loggable
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityRepositories.Otps otps;
    private final NotificationSender notifications;
    private final KeyMaterial keys;
    private final Clock clock;
    private final ApiProperties.Otp settings;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final SmsSender sms;

    /** OD-146: the demo viewer's memory; absent outside Spring (unit tests), off outside demo. */
    private DemoOtpCodes demoCodes;

    public OtpService(
            IdentityRepositories.Otps otps,
            NotificationSender notifications,
            KeyMaterial keys,
            Clock clock,
            ApiProperties properties,
            SystemParameters parameters,
            AuditService audit,
            SmsSender sms) {
        this.otps = otps;
        this.notifications = notifications;
        this.keys = keys;
        this.clock = clock;
        this.settings = properties.otp();
        this.parameters = parameters;
        this.audit = audit;
        this.sms = sms;
    }

    /** OD-146: set by Spring; keeps the code in memory only when the demo viewer is on. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDemoCodes(DemoOtpCodes demoCodes) {
        this.demoCodes = demoCodes;
    }

    /**
     * Issues and sends a challenge for {@code action} on the entity; returns it (the code itself is
     * never returned). Own transaction: the challenge survives the 428 that asks the caller to
     * present it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OtpChallenge issue(
            AppUser user, OtpGatedAction action, String entityName, String entityKey) {
        Instant now = clock.instant();
        // UC-07 precondition (SRS l.576) with SRS-G-3: the code goes only to the user's own
        // registered contact; without a mobile or an e-mail nothing is issued and nothing falls
        // back to another contact (US-GAP-118).
        if (blank(user.getRegisteredMobile()) && blank(user.getRegisteredEmail())) {
            throw new FciException(ApiErrorCode.OTP_NO_CONTACT);
        }
        requireNoCoolingOff(user, action, now);
        if (otps.countIssuedSince(user.getId(), action, now.minusSeconds(3600))
                >= settings.maxPerHour()) {
            throw new FciException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        for (OtpChallenge open :
                otps.findByUserIdAndGatedActionAndOutcome(
                        user.getId(), action, OtpChallenge.Outcome.ISSUED)) {
            open.abandon(now);
            otps.save(open);
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        // SRS-G-48 (OD-36): the registered mobile first; the registered e-mail when there is no
        // mobile, and also while no SMS gateway is connected (FCI to name it), so the code arrives.
        String mobile = user.getRegisteredMobile();
        boolean bySms = mobile != null && !mobile.isBlank();
        OtpChallenge challenge =
                otps.save(
                        OtpChallenge.issue(
                                user.getId(),
                                user.getLoginId(),
                                action,
                                entityName,
                                entityKey,
                                hash(user.getId(), action, code),
                                MDC.get(MdcKeys.CLIENT_IP),
                                now,
                                parameters.otpValidity(),
                                bySms ? OtpChallenge.SMS : OtpChallenge.EMAIL));
        int minutes = (int) parameters.otpValidity().toMinutes();
        if (demoCodes != null) {
            demoCodes.remember(user.getId(), action, code, now.plus(parameters.otpValidity()));
        }
        if (bySms) {
            sms.sendOtp(mobile, action.name(), code, minutes);
        }
        if ((!bySms || !sms.connected()) && user.getRegisteredEmail() != null) {
            notifications.sendOtp(
                    user.getRegisteredEmail(), user.getDisplayName(), action.name(), code, minutes);
        }
        return challenge;
    }

    /**
     * Verifies and consumes a challenge of {@code action} that belongs to {@code user}. Any
     * mismatch is the same INVALID_OTP. Own transaction: a wrong attempt is counted even though the
     * caller's action fails, and a used code stays used.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = FciException.class)
    public OtpChallenge verify(
            AppUser user, OtpGatedAction action, Long challengeId, @Sensitive String code) {
        return verify(user, action, challengeId, code, null, null);
    }

    /**
     * As {@link #verify(AppUser, OtpGatedAction, Long, String)}, and the challenge must have been
     * issued for this item ({@code entityName} / {@code entityKey}; null = any item of the action):
     * a code asked while deciding one item confirms that item only (security review 2026-10-31). A
     * challenge of another item is the same INVALID_OTP and is neither counted nor consumed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = FciException.class)
    public OtpChallenge verify(
            AppUser user,
            OtpGatedAction action,
            Long challengeId,
            @Sensitive String code,
            String entityName,
            String entityKey) {
        if (challengeId == null || code == null || !code.matches("^[0-9]{6}$")) {
            throw new FciException(ApiErrorCode.INVALID_OTP);
        }
        OtpChallenge challenge =
                otps.findById(challengeId)
                        .filter(
                                c ->
                                        c.getUserId().equals(user.getId())
                                                && c.getGatedAction() == action
                                                && sameItem(c, entityName, entityKey))
                        .orElseThrow(() -> new FciException(ApiErrorCode.INVALID_OTP));
        boolean wasOpen = challenge.getOutcome() == OtpChallenge.Outcome.ISSUED;
        boolean ok =
                challenge.verify(
                        hash(user.getId(), action, code),
                        parameters.otpMaxAttempts(),
                        clock.instant());
        otps.save(challenge);
        if (!ok) {
            if (challenge.getAttempts() == settings.alertAfterFailures()) {
                notify(
                        user,
                        () ->
                                notifications.sendOtpFailureAlert(
                                        user.getRegisteredEmail(),
                                        user.getDisplayName(),
                                        action.name(),
                                        challenge.getAttempts()),
                        "FCILekha security alert: "
                                + challenge.getAttempts()
                                + " wrong OTPs for "
                                + action.name()
                                + ". Account not locked. Not you? Contact your administrator.");
            }
            // SRS-G-22: one event when the limit is exceeded, not one per later attempt.
            if (wasOpen && challenge.getOutcome() == OtpChallenge.Outcome.LOCKED_OUT) {
                audit.securityEvent(
                        AuditService.Security.OTP_LOCKOUT,
                        user.getLoginId(),
                        "otp_challenge",
                        String.valueOf(challenge.getId()),
                        action.name());
            }
            throw new FciException(ApiErrorCode.INVALID_OTP);
        }
        notify(
                user,
                () ->
                        notifications.sendOtpVerified(
                                user.getRegisteredEmail(), user.getDisplayName(), action.name()),
                "FCILekha: OTP for "
                        + action.name()
                        + " verified on your account. Not you? Contact your administrator.");
        return challenge;
    }

    /**
     * SRS-G-22: once the retry limit is exceeded the action is blocked for the A-07 cooling-off
     * period (from the lockout); asked again within it, the refusal names when it ends. Nothing is
     * blocked while the period is not set (OD-67).
     */
    private void requireNoCoolingOff(AppUser user, OtpGatedAction action, Instant now) {
        java.util.Optional<java.time.Duration> coolingOff = parameters.otpCoolingOff();
        if (coolingOff.isEmpty()) {
            return;
        }
        java.util.Optional<Instant> until =
                otps.findFirstByUserIdAndGatedActionAndOutcomeOrderByIdDesc(
                                user.getId(), action, OtpChallenge.Outcome.LOCKED_OUT)
                        .map(OtpChallenge::getOutcomeAt)
                        .map(lockedAt -> lockedAt.plus(coolingOff.get()))
                        .filter(end -> end.isAfter(now));
        if (until.isPresent()) {
            throw new FciException(
                    ApiErrorCode.OTP_COOLING_OFF,
                    new Object[] {
                        java.time.format.DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm")
                                .format(until.get().atZone(java.time.ZoneId.of("Asia/Kolkata")))
                    },
                    java.util.List.of(),
                    null);
        }
    }

    /** The challenge was issued for the item (compared as stored: at most 100 characters). */
    private static boolean sameItem(OtpChallenge challenge, String entityName, String entityKey) {
        if (entityName == null && entityKey == null) {
            return true;
        }
        return java.util.Objects.equals(challenge.getEntityName(), stored(entityName))
                && java.util.Objects.equals(challenge.getEntityKey(), stored(entityKey));
    }

    private static String stored(String value) {
        return value == null || value.length() <= 100 ? value : value.substring(0, 100);
    }

    /** E-mail and SMS to the user's own contacts; a failure is logged, never thrown. */
    private void notify(AppUser user, Runnable email, String smsText) {
        try {
            if (user.getRegisteredEmail() != null) {
                email.run();
            }
            if (user.getRegisteredMobile() != null && !user.getRegisteredMobile().isBlank()) {
                sms.send(user.getRegisteredMobile(), smsText);
            }
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(OtpService.class)
                    .warn(
                            "event=NOTIFICATION_FAILED type=OTP exception={}",
                            failure.getClass().getName());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Whether the critical activities ask for an OTP ({@code fci.api.otp.required}). */
    public boolean verificationRequired() {
        return settings.verificationRequired();
    }

    /**
     * With OTP verification switched off ({@code fci.api.otp.required = N}; refused in prod and a
     * real UAT by StartupGuards): records the challenge the action would have asked for as
     * confirmed, sends nothing, and logs it, so every record that keeps its OTP (an approval
     * decision, a CPF sanction tier) still names one. Never used while verification is required.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OtpChallenge confirmWithoutCode(
            AppUser user, OtpGatedAction action, String entityName, String entityKey) {
        if (settings.verificationRequired()) {
            throw new IllegalStateException("OTP verification is required: no code-less confirm");
        }
        Instant now = clock.instant();
        OtpChallenge challenge =
                OtpChallenge.issue(
                        user.getId(),
                        user.getLoginId(),
                        action,
                        entityName,
                        entityKey,
                        hash(user.getId(), action, SecureTokens.randomToken(16)),
                        MDC.get(MdcKeys.CLIENT_IP),
                        now,
                        parameters.otpValidity());
        challenge.confirmWithoutCode(now);
        OtpChallenge saved = otps.save(challenge);
        LoggerFactory.getLogger(OtpService.class)
                .warn(
                        "event=OTP_NOT_REQUIRED action={} challenge={} (fci.api.otp.required=N)",
                        action,
                        saved.getId());
        return saved;
    }

    /** Verifies the newest open challenge of {@code action} (password reset carries no id). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = FciException.class)
    public OtpChallenge verifyLatest(AppUser user, OtpGatedAction action, @Sensitive String code) {
        Long id =
                otps.findFirstByUserIdAndGatedActionAndOutcomeOrderByIdDesc(
                                user.getId(), action, OtpChallenge.Outcome.ISSUED)
                        .map(OtpChallenge::getId)
                        .orElseThrow(() -> new FciException(ApiErrorCode.INVALID_OTP));
        return verify(user, action, id, code);
    }

    private String hash(Long userId, OtpGatedAction action, String code) {
        return SecureTokens.hmacSha256Hex(keys.auditHmacKey(), userId + ":" + action + ":" + code);
    }
}
