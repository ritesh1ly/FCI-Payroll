package in.gov.fci.payroll.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import in.gov.fci.payroll.platform.logging.LogCapture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The notification channels (SRS-G-48 OTP delivery, SRS-G-49 lock-out after failed attempts; rules
 * R-OTP and R-PII). By mail (uat / prod) each message goes to the user alone from the configured
 * sender, bilingual in its subject, and the OTP mail names the purpose, the code, its validity and
 * the warning never to share it. On the log channel (local / dev) the code is printed only when
 * {@code log-codes} is on, every address is masked, and an SMS is recorded with the last four
 * digits and its length, never its text.
 */
class NotificationSendersTest {

    private static final String FROM = "noreply@fci-demo.test";
    private static final String TO = "officer.delhi@fci-demo.test";

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final NotificationSender smtp =
            new NotificationSenders().smtpNotificationSender(mail, FROM);

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> message =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        SimpleMailMessage m = message.getValue();
        assertThat(m.getFrom()).isEqualTo(FROM);
        assertThat(m.getTo()).containsExactly(TO);
        assertThat(m.getText())
                .startsWith("Dear A. Officer,")
                .endsWith("— Food Corporation of India");
        return m;
    }

    @Test
    void SRS_G_48_theOtpMailNamesItsPurposeCodeAndValidityAndWarnsNeverToShareIt() {
        smtp.sendOtp(TO, "A. Officer", "sign-in", "482913", 5);
        SimpleMailMessage m = sent();
        assertThat(m.getSubject()).isEqualTo("FCILekha: one-time password / एकबारगी पासवर्ड");
        assertThat(m.getText())
                .contains("one-time password for sign-in is 482913")
                .contains("valid for 5 minutes")
                .contains("Never share it with anyone, including FCI staff");
    }

    @Test
    void SRS_G_49_theLockOutMailSaysForHowLongAndWhatToDoIfItWasNotTheUser() {
        smtp.sendAccountLocked(TO, "A. Officer");
        SimpleMailMessage m = sent();
        assertThat(m.getSubject()).contains("account temporarily locked");
        assertThat(m.getText()).contains("locked for 15 minutes").contains("contact your");
    }

    @Test
    void SRS_G_49_theFailureAlertCountsTheWrongCodesAndSaysTheAccountIsNotLocked() {
        smtp.sendOtpFailureAlert(TO, "A. Officer", "salary release", 3);
        SimpleMailMessage m = sent();
        assertThat(m.getSubject()).contains("security alert");
        assertThat(m.getText())
                .contains(
                        "3 wrong one-time passwords were entered on your account for salary"
                                + " release")
                .contains("Your account is not locked");
    }

    @Test
    void SRS_G_48_aVerifiedCodeIsReportedSoTheUserCanSpotMisuse() {
        smtp.sendOtpVerified(TO, "A. Officer", "bank change");
        SimpleMailMessage m = sent();
        assertThat(m.getSubject()).contains("one-time password used");
        assertThat(m.getText())
                .contains("A one-time password for bank change was just verified")
                .doesNotContainPattern("\\d{6}");
    }

    // ---------------------------------------------------------------- the log channel

    private static List<String> logged(
            boolean logCodes, java.util.function.Consumer<NotificationSender> send) {
        try (LogCapture logs = LogCapture.of("FCI_NOTIFICATIONS", Level.INFO)) {
            send.accept(new NotificationSenders().loggingNotificationSender(logCodes));
            return logs.messages();
        }
    }

    @Test
    void R_OTP_theLogChannelPrintsACodeOnlyWhenLocalDevelopmentAsksForIt() {
        List<String> production =
                logged(false, s -> s.sendOtp(TO, "A. Officer", "sign-in", "482913", 5));
        assertThat(production)
                .singleElement()
                .asString()
                .contains("type=OTP purpose=sign-in")
                .doesNotContain("482913")
                .doesNotContain(TO);
        List<String> local = logged(true, s -> s.sendOtp(TO, "A. Officer", "sign-in", "482913", 5));
        assertThat(local).singleElement().asString().contains("devCode=482913").doesNotContain(TO);
    }

    @Test
    void R_PII_everyLoggedNotificationMasksTheAddress() {
        List<String> messages =
                logged(
                        false,
                        s -> {
                            s.sendAccountLocked(TO, "A. Officer");
                            s.sendOtpFailureAlert(TO, "A. Officer", "sign-in", 3);
                            s.sendOtpVerified(TO, "A. Officer", "sign-in");
                        });
        assertThat(messages)
                .hasSize(3)
                .allSatisfy(m -> assertThat(m).doesNotContain(TO).contains("to="));
        assertThat(messages.get(0)).contains("type=ACCOUNT_LOCKED");
        assertThat(messages.get(1)).contains("type=OTP_FAILURE_ALERT").contains("attempts=3");
        assertThat(messages.get(2)).contains("type=OTP_VERIFIED");
    }

    @Test
    void R_PII_anSmsIsRecordedWithTheLastFourDigitsAndItsLengthNeverItsText() {
        SmsSender sms = new NotificationSenders().loggingSmsSender(false);
        try (LogCapture logs = LogCapture.of("FCI_NOTIFICATIONS", Level.INFO)) {
            sms.send("9876543210", "Your OTP is 482913");
            sms.send("123", null);
            sms.send(null, "x");
            List<ILoggingEvent> events = logs.events();
            assertThat(events).hasSize(3);
            assertThat(events.get(0).getFormattedMessage())
                    .contains("to=******3210")
                    .contains("length=18")
                    .doesNotContain("482913")
                    .doesNotContain("9876");
            assertThat(events.get(1).getFormattedMessage())
                    .contains("to=****")
                    .contains("length=0");
            assertThat(events.get(2).getFormattedMessage())
                    .contains("to=****")
                    .contains("length=1");
        }
    }

    @Test
    void SRS_G_48_aChannelWithoutAlertsOrReceiptsSendsNothingForThem() {
        List<String> calls = new java.util.ArrayList<>();
        NotificationSender minimal =
                new NotificationSender() {
                    @Override
                    public void sendOtp(
                            String email, String name, String purpose, String code, int minutes) {
                        calls.add("otp");
                    }

                    @Override
                    public void sendAccountLocked(String email, String name) {
                        calls.add("locked");
                    }
                };
        minimal.sendOtpFailureAlert(TO, "A. Officer", "sign-in", 3);
        minimal.sendOtpVerified(TO, "A. Officer", "sign-in");
        assertThat(calls).isEmpty();
    }

    @Test
    void SRS_17_1_aGeneralMessageGoesByMailWithItsOwnSubjectAndBody() {
        smtp.sendMessage(
                TO,
                "A. Officer",
                "Payslip October 2026",
                "Dear A. Officer,\n\nYour payslip is ready.\n\n— Food Corporation of India");
        SimpleMailMessage m = sent();
        assertThat(m.getSubject()).isEqualTo("Payslip October 2026");
        assertThat(m.getText()).contains("Your payslip is ready.");
    }

    @Test
    void R_PII_aLoggedGeneralMessageShowsItsLengthNeverItsText() {
        List<String> messages =
                logged(
                        false,
                        s -> {
                            s.sendMessage(TO, "A. Officer", "Payslip", "Net pay 45000");
                            s.sendMessage(TO, "A. Officer", "Payslip", null);
                        });
        assertThat(messages.get(0))
                .contains("type=MESSAGE")
                .contains("length=13")
                .doesNotContain("45000")
                .doesNotContain(TO);
        assertThat(messages.get(1)).contains("length=0");
    }

    /**
     * SRS-G-48 / R-OTP: an OTP by SMS names its purpose and the masked number; the code is printed
     * only on a development run (log-codes), never elsewhere; the log stand-in is not connected.
     */
    @Test
    void SRS_G_48_anSmsOtpShowsItsCodeOnlyOnADevelopmentRun() {
        try (LogCapture logs = LogCapture.of("FCI_NOTIFICATIONS", Level.INFO)) {
            SmsSender production = new NotificationSenders().loggingSmsSender(false);
            production.sendOtp("9876543210", "AUTH_LOGIN", "482913", 5);
            SmsSender local = new NotificationSenders().loggingSmsSender(true);
            local.sendOtp("9876543210", "AUTH_LOGIN", "482913", 5);
            assertThat(production.connected()).isFalse();
            List<String> lines = logs.messages();
            assertThat(lines.get(0))
                    .contains("type=OTP purpose=AUTH_LOGIN")
                    .contains("to=******3210")
                    .doesNotContain("482913")
                    .doesNotContain("98765");
            assertThat(lines.get(1)).contains("devCode=482913").doesNotContain("98765");
        }
    }

    @Test
    void R_PII_aMobileShowsItsLastFourDigitsFromFourDigitsOn() {
        assertThat(NotificationSenders.masked("1234")).isEqualTo("******1234");
        assertThat(NotificationSenders.masked("123")).isEqualTo("****");
        assertThat(NotificationSenders.masked(null)).isEqualTo("****");
    }

    @Test
    void SRS_G_48_theAccountChangedMailNamesWhatTheAdministratorChangedAndWarnsTheUser() {
        smtp.sendAccountChanged(TO, "A. Officer", "mobile number");
        SimpleMailMessage m = sent();
        assertThat(m.getSubject()).isEqualTo("FCILekha: account changed / खाते में परिवर्तन");
        assertThat(m.getText())
                .contains("An administrator changed the mobile number of your account")
                .contains("contact your administrator at once")
                .doesNotContainPattern("\\d{6}");
    }

    @Test
    void R_PII_theLoggedAccountChangeMasksTheAddressAndNamesOnlyWhatChanged() {
        List<String> messages =
                logged(false, s -> s.sendAccountChanged(TO, "A. Officer", "e-mail address"));
        assertThat(messages)
                .singleElement()
                .asString()
                .contains("type=ACCOUNT_CHANGED")
                .contains("what=\"e-mail address\"")
                .contains("to=")
                .doesNotContain(TO)
                .doesNotContain("A. Officer");
    }

    @Test
    void R_PII_aLoggedAccountChangeWithNoDescriptionStillMasksTheAddress() {
        List<String> messages = logged(true, s -> s.sendAccountChanged(TO, null, null));
        assertThat(messages)
                .singleElement()
                .asString()
                .contains("what=\"null\"")
                .doesNotContain(TO);
    }

    @Test
    void SRS_G_48_theLogChannelNeverPrintsACodeForTheNonOtpMessagesEvenWhenLogCodesIsOn() {
        List<String> messages =
                logged(
                        true,
                        s -> {
                            s.sendAccountLocked(TO, "A. Officer");
                            s.sendOtpVerified(TO, "A. Officer", "sign-in");
                        });
        assertThat(messages).hasSize(2).allSatisfy(m -> assertThat(m).doesNotContain("devCode"));
    }
}
