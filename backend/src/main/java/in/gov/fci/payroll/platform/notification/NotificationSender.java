package in.gov.fci.payroll.platform.notification;

/**
 * Out-of-band messages to a user's OWN registered contact (HLD §3.11.3: never a shared or
 * role-level contact).
 */
public interface NotificationSender {

    /** A one-time password for {@code purpose} (password reset, access assignment …). */
    void sendOtp(String email, String displayName, String purpose, String code, int validMinutes);

    /** Tells the account owner that the account was locked after repeated failed sign-ins. */
    void sendAccountLocked(String email, String displayName);

    /**
     * Security alert: {@code attempts} wrong one-time passwords for {@code purpose}; the account is
     * not locked (the code is void after its last attempt and a new one can be requested).
     */
    default void sendOtpFailureAlert(
            String email, String displayName, String purpose, int attempts) {}

    /** Notice that a one-time password for {@code purpose} was just verified on the account. */
    default void sendOtpVerified(String email, String displayName, String purpose) {}

    /**
     * Security alert: an administrator changed how the account signs in ({@code what}: its
     * registered mobile, or a temporary password), so the owner learns of a change they did not
     * make (security review 2026-10-31).
     */
    default void sendAccountChanged(String email, String displayName, String what) {}

    /**
     * A message to the member's own registered e-mail in FCI's wording (plan 14 phase 14e;
     * SRS-17-12, SRS-17-1): the subject and body come from a notification template, never from
     * code. Throws when the channel cannot send; the caller records the failure and goes on.
     */
    default void sendMessage(String email, String displayName, String subject, String body) {}

    /**
     * A member's own document by e-mail (OD-148; SRS-17-1: the payslip after the lock; Form 16 on
     * release, PENDING F16-5): the subject and body in the member's language, the PDF attached
     * ({@code attachment} null: the body alone, when the attachment setting is off, PENDING
     * MAIL-1). {@code messageId} is the e-mail's own Message-ID, the same on every attempt, so a
     * receiving server can drop a repeat (null: the server's own). Throws when the channel cannot
     * send ({@link RecipientRejectedException} when the server refuses the address); the caller
     * records the failure and retries by its settings.
     */
    default void sendDocument(
            String email,
            String displayName,
            String subject,
            String body,
            MailAttachment attachment,
            String messageId) {
        throw new UnsupportedOperationException("This channel cannot send documents");
    }
}
