package in.gov.fci.payroll.platform.notification;

import in.gov.fci.payroll.platform.logging.Sensitive;

/**
 * Short messages to a user's OWN registered mobile number: security alerts, notices and one-time
 * passwords (SRS-G-48: the OTP goes to the registered contact). The gateway is a deployment choice
 * (>> OPEN <<: FCI to name the SMS service at go-live; no provider is named in the code, R-OTP).
 * Until one is configured the log channel records that a message was due, with the number masked;
 * it prints an OTP code only where {@code fci.api.notification.log-codes} allows it (local / dev /
 * demo, refused elsewhere by StartupGuards).
 */
public interface SmsSender {

    /** Sends {@code text} (no code, no personal data) to {@code mobile}. */
    void send(String mobile, String text);

    /**
     * Sends a one-time password for {@code purpose}, valid for {@code minutes}: the only message
     * that carries a code.
     */
    void sendOtp(String mobile, String purpose, @Sensitive String code, int minutes);

    /**
     * Whether a real SMS gateway delivers the messages. False for the log stand-in: an OTP is then
     * also sent to the registered e-mail, so a user can sign in before FCI connects the gateway.
     */
    default boolean connected() {
        return false;
    }
}
