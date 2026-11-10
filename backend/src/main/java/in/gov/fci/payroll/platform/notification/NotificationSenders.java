package in.gov.fci.payroll.platform.notification;

import in.gov.fci.payroll.platform.logging.SensitiveDataMasker;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Notification channels, selected by {@code fci.api.notification.mode}: {@code smtp} (uat/prod) or
 * {@code log} (local/dev). The log channel prints the one-time code only when {@code
 * fci.api.notification.log-codes=true}, which only the local profile sets (refused in uat/prod).
 */
@Configuration(proxyBeanMethods = false)
public class NotificationSenders {

    @Bean
    @ConditionalOnProperty(name = "fci.api.notification.mode", havingValue = "smtp")
    NotificationSender smtpNotificationSender(
            JavaMailSender mailSender, @Value("${fci.api.notification.from}") String from) {
        return new NotificationSender() {
            @Override
            public void sendOtp(
                    String email, String displayName, String purpose, String code, int minutes) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email);
                message.setSubject("FCILekha: one-time password / एकबारगी पासवर्ड");
                message.setText(
                        "Dear "
                                + displayName
                                + ",\n\nYour one-time password for "
                                + purpose
                                + " is "
                                + code
                                + ". It is valid for "
                                + minutes
                                + " minutes. Never share it with anyone, including FCI staff.\n\n"
                                + "If you did not request it, contact your administrator.\n\n"
                                + "— Food Corporation of India");
                mailSender.send(message);
            }

            @Override
            public void sendAccountLocked(String email, String displayName) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email);
                message.setSubject("FCI: account temporarily locked / खाता अस्थायी रूप से लॉक");
                message.setText(
                        "Dear "
                                + displayName
                                + ",\n\n"
                                + "Your account was locked for 15 minutes after repeated failed"
                                + " sign-in attempts. If this was not you, contact your"
                                + " administrator.\n\n"
                                + "— Food Corporation of India");
                mailSender.send(message);
            }

            @Override
            public void sendOtpFailureAlert(
                    String email, String displayName, String purpose, int attempts) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email);
                message.setSubject("FCILekha: security alert / सुरक्षा चेतावनी");
                message.setText(
                        "Dear "
                                + displayName
                                + ",\n\n"
                                + attempts
                                + " wrong one-time passwords were entered on your account for "
                                + purpose
                                + ". Your account is not locked. If this was not you, change your"
                                + " password and contact your administrator.\n\n"
                                + "— Food Corporation of India");
                mailSender.send(message);
            }

            @Override
            public void sendOtpVerified(String email, String displayName, String purpose) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email);
                message.setSubject("FCILekha: one-time password used / एकबारगी पासवर्ड प्रयुक्त");
                message.setText(
                        "Dear "
                                + displayName
                                + ",\n\nA one-time password for "
                                + purpose
                                + " was just verified on your account. If this was not you,"
                                + " contact your administrator at once.\n\n"
                                + "— Food Corporation of India");
                mailSender.send(message);
            }

            @Override
            public void sendAccountChanged(String email, String displayName, String what) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email);
                message.setSubject("FCILekha: account changed / खाते में परिवर्तन");
                message.setText(
                        "Dear "
                                + displayName
                                + ",\n\nAn administrator changed the "
                                + what
                                + " of your account. If you did not ask for this, contact your"
                                + " administrator at once.\n\n"
                                + "— Food Corporation of India");
                mailSender.send(message);
            }

            @Override
            public void sendMessage(String email, String displayName, String subject, String body) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email);
                message.setSubject(subject);
                message.setText(body);
                mailSender.send(message);
            }

            @Override
            public void sendDocument(
                    String email,
                    String displayName,
                    String subject,
                    String body,
                    MailAttachment attachment,
                    String messageId) {
                classified(
                        () ->
                                mailSender.send(
                                        document(
                                                mailSender,
                                                from,
                                                email,
                                                subject,
                                                body,
                                                attachment,
                                                messageId)));
            }
        };
    }

    /**
     * OD-148: a MIME message (UTF-8, so a Hindi subject and body travel as written) with the PDF
     * attached and the e-mail's fixed Message-ID; a recipient the server refuses is a {@link
     * RecipientRejectedException} (bounce).
     */
    static MimeMessage document(
            JavaMailSender mailSender,
            String from,
            String email,
            String subject,
            String body,
            MailAttachment attachment,
            String messageId) {
        MimeMessage created = mailSender.createMimeMessage();
        MimeMessage message =
                messageId == null ? created : new FixedIdMessage(created.getSession(), messageId);
        try {
            MimeMessageHelper helper =
                    new MimeMessageHelper(
                            message, attachment != null, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body, false);
            if (attachment != null) {
                helper.addAttachment(
                        attachment.fileName(),
                        new ByteArrayResource(attachment.content()),
                        attachment.contentType());
            }
        } catch (MessagingException e) {
            throw new MailPreparationException("Cannot prepare the document e-mail", e);
        }
        return message;
    }

    /**
     * Sends through {@code send}; a refusal of the recipient address itself (the server's bounce at
     * sending) becomes a {@link RecipientRejectedException}, any other failure is rethrown.
     */
    static void classified(Runnable send) {
        try {
            send.run();
        } catch (MailSendException e) {
            if (rejectsRecipient(e)) {
                throw new RecipientRejectedException("Recipient refused by the mail server", e);
            }
            throw e;
        }
    }

    /** Whether a failed send names an invalid recipient address (jakarta.mail). */
    static boolean rejectsRecipient(MailSendException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SendFailedException s && invalid(s)) {
                return true;
            }
        }
        return e.getFailedMessages().values().stream()
                .anyMatch(x -> x instanceof SendFailedException s && invalid(s));
    }

    private static boolean invalid(SendFailedException s) {
        return s.getInvalidAddresses() != null && s.getInvalidAddresses().length > 0;
    }

    @Bean
    @ConditionalOnProperty(
            name = "fci.api.notification.mode",
            havingValue = "log",
            matchIfMissing = true)
    NotificationSender loggingNotificationSender(
            @Value("${fci.api.notification.log-codes:false}") boolean logCodes) {
        Logger log = LoggerFactory.getLogger("FCI_NOTIFICATIONS");
        return new NotificationSender() {
            @Override
            public void sendOtp(
                    String email, String displayName, String purpose, String code, int minutes) {
                if (logCodes) {
                    log.warn(
                            "event=NOTIFICATION channel=log type=OTP purpose={} to={} devCode={}",
                            purpose,
                            SensitiveDataMasker.maskEmail(email),
                            code);
                } else {
                    log.info(
                            "event=NOTIFICATION channel=log type=OTP purpose={} to={}",
                            purpose,
                            SensitiveDataMasker.maskEmail(email));
                }
            }

            @Override
            public void sendAccountLocked(String email, String displayName) {
                log.info(
                        "event=NOTIFICATION channel=log type=ACCOUNT_LOCKED to={}",
                        SensitiveDataMasker.maskEmail(email));
            }

            @Override
            public void sendOtpFailureAlert(
                    String email, String displayName, String purpose, int attempts) {
                log.warn(
                        "event=NOTIFICATION channel=log type=OTP_FAILURE_ALERT purpose={}"
                                + " attempts={} to={}",
                        purpose,
                        attempts,
                        SensitiveDataMasker.maskEmail(email));
            }

            @Override
            public void sendOtpVerified(String email, String displayName, String purpose) {
                log.info(
                        "event=NOTIFICATION channel=log type=OTP_VERIFIED purpose={} to={}",
                        purpose,
                        SensitiveDataMasker.maskEmail(email));
            }

            @Override
            public void sendAccountChanged(String email, String displayName, String what) {
                log.info(
                        "event=NOTIFICATION channel=log type=ACCOUNT_CHANGED what=\"{}\" to={}",
                        what,
                        SensitiveDataMasker.maskEmail(email));
            }

            @Override
            public void sendMessage(String email, String displayName, String subject, String body) {
                log.info(
                        "event=NOTIFICATION channel=log type=MESSAGE to={} length={}",
                        SensitiveDataMasker.maskEmail(email),
                        body == null ? 0 : body.length());
            }

            /**
             * OD-148: the local channel records that the document e-mail went out: the masked
             * address, the subject, the body's length, the attachment's name and size; never the
             * body text nor the file (R-PII).
             */
            @Override
            public void sendDocument(
                    String email,
                    String displayName,
                    String subject,
                    String body,
                    MailAttachment attachment,
                    String messageId) {
                log.info(
                        "event=NOTIFICATION channel=log type=DOCUMENT to={} subject=\"{}\""
                                + " bodyLength={} attachment={} attachmentBytes={} messageId={}",
                        SensitiveDataMasker.maskEmail(email),
                        subject,
                        body == null ? 0 : body.length(),
                        attachment == null ? "none" : attachment.fileName(),
                        attachment == null ? 0 : attachment.size(),
                        messageId);
            }
        };
    }

    /**
     * SMS until a gateway is configured (>> OPEN <<, no provider named): records that a message was
     * due, with the number masked to its last four digits and without the text. An OTP's code is
     * printed only when {@code fci.api.notification.log-codes} is on (local / dev / demo, as the
     * e-mail log channel); never otherwise. Not {@link SmsSender#connected() connected}.
     */
    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    SmsSender loggingSmsSender(@Value("${fci.api.notification.log-codes:false}") boolean logCodes) {
        Logger log = LoggerFactory.getLogger("FCI_NOTIFICATIONS");
        return new SmsSender() {
            @Override
            public void send(String mobile, String text) {
                log.info(
                        "event=NOTIFICATION channel=sms-log to={} length={}",
                        masked(mobile),
                        text == null ? 0 : text.length());
            }

            @Override
            public void sendOtp(String mobile, String purpose, String code, int minutes) {
                if (logCodes) {
                    log.warn(
                            "event=NOTIFICATION channel=sms-log type=OTP purpose={} to={}"
                                    + " devCode={}",
                            purpose,
                            masked(mobile),
                            code);
                } else {
                    log.info(
                            "event=NOTIFICATION channel=sms-log type=OTP purpose={} to={}",
                            purpose,
                            masked(mobile));
                }
            }
        };
    }

    /** The last four digits of a mobile number only (R-PII). */
    static String masked(String mobile) {
        return mobile == null || mobile.length() < 4
                ? "****"
                : "******" + mobile.substring(mobile.length() - 4);
    }

    /** A MIME message that keeps the Message-ID it was given (JavaMail would replace it). */
    static final class FixedIdMessage extends MimeMessage {
        private final String messageId;

        FixedIdMessage(jakarta.mail.Session session, String messageId) {
            super(session);
            this.messageId = messageId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", messageId);
        }
    }
}
