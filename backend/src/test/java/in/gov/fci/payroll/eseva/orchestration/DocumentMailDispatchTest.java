package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.integration.api.InterfaceBreakers;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.PdfRenderGate;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import in.gov.fci.payroll.platform.notification.DocumentFailedException;
import in.gov.fci.payroll.platform.notification.DocumentNotReadyException;
import in.gov.fci.payroll.platform.notification.MailAttachment;
import in.gov.fci.payroll.platform.notification.MemberDocuments;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The document e-mail dispatcher's guards and edges (OD-148; SRS-17-12, SRS-INT-6, SRS-INT-16;
 * PENDING MAIL-1, MAIL-2, MAIL-4): switched off, unconfigured (interface or capacity), a password
 * rule missing, the channel paused, the breaker open, the window full or no connection free:
 * nothing is read or sent. Per e-mail: taken by another sender, no or vanished address, an unknown
 * document, a busy render bulkhead, a PDF not generated, a generation failure, a business refusal,
 * a broken render, a missing password value, the attachment cap, a full window at the send, FCI's
 * template (Hindi when there) over the message keys, a permanent refusal, and an outbox failure
 * that leaves the e-mail to its lease. The full flows run in DocumentMailIT and
 * DocumentMailCapacityIT.
 */
class DocumentMailDispatchTest {

    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final InterfaceSettings settings = mock(InterfaceSettings.class);
    private final InterfaceBreakers breakers = mock(InterfaceBreakers.class);
    private final MailChannel channel = mock(MailChannel.class);
    private final AuditService audit = mock(AuditService.class);
    private final MessageBundles messages = mock(MessageBundles.class);
    private final MemberDocuments payslips = mock(MemberDocuments.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private static final InterfaceSettings.Settings SETTINGS =
            new InterfaceSettings.Settings(
                    "MEMBER_EMAIL",
                    2,
                    60,
                    "FIXED",
                    30,
                    false,
                    null,
                    null,
                    null,
                    LocalDate.of(2020, 4, 1),
                    false);

    private static MailChannel.Config capacity(long maxBytes, String oversize) {
        return new MailChannel.Config(60, null, null, 2, 300, maxBytes, oversize, 0, 900);
    }

    DocumentMailDispatchTest() {
        when(payslips.documentType()).thenReturn("PAYSLIP");
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(messages.messages("en"))
                .thenReturn(
                        Map.of(
                                "mail.payslip.subject", "Payslip {month}",
                                "mail.payslip.body", "Dear {name}, attached",
                                "mail.payslip.bodyNoAttachment", "Dear {name}, sign in"));
        when(settings.inForce("MEMBER_EMAIL")).thenReturn(Optional.of(SETTINGS));
        when(channel.config(NOW))
                .thenReturn(Optional.of(capacity(1_000_000, "WITHOUT_ATTACHMENT")));
        when(channel.pausedUntil(NOW)).thenReturn(Optional.empty());
        when(breakers.admit("MEMBER_EMAIL")).thenReturn(true);
        when(channel.hasCapacity(any(), eq(NOW))).thenReturn(true);
        when(channel.acquireSlots(anyString(), anyInt(), any(), eq(NOW))).thenReturn(List.of(1));
        when(channel.takeToken(any(), eq(NOW))).thenReturn(true);
        when(jdbc.update(contains("SET attempt_count = ?, last_attempt_at"), any(Object[].class)))
                .thenReturn(1);
    }

    private DocumentMailDispatch dispatch(DocumentMailDispatch.Options options) {
        return new DocumentMailDispatch(
                jdbc,
                sender,
                settings,
                breakers,
                channel,
                audit,
                messages,
                List.of(payslips),
                transactions,
                options,
                () -> 0.5);
    }

    private static DocumentMailDispatch.Options on() {
        return new DocumentMailDispatch.Options(true, 50, true, false, "");
    }

    private static DocumentMailDispatch.Options options(
            boolean attachment, boolean password, String rule) {
        return new DocumentMailDispatch.Options(
                true,
                50,
                attachment,
                password,
                rule,
                1,
                Duration.ofHours(24),
                Duration.ofMinutes(15));
    }

    private static DocumentMailDispatch.Due due(long id, String type) {
        return new DocumentMailDispatch.Due(
                id,
                100 + id,
                Timestamp.from(NOW),
                7L,
                type,
                "202610",
                "202610",
                "x",
                0,
                false,
                null);
    }

    private void member(String email, String locale) {
        Map<String, Object> m = new HashMap<>();
        m.put("EMAIL_ADDRESS", email);
        m.put("FIRST_NAME", "Rakesh");
        m.put("LAST_NAME", "Sharma");
        m.put("LOCALE", locale);
        when(jdbc.queryForList(contains("SELECT e.email_address"), any(Object[].class)))
                .thenReturn(List.of(m));
    }

    private void rendered(int bytes) {
        when(payslips.render(7L, "202610", "202610"))
                .thenReturn(
                        Optional.of(
                                new MemberDocuments.Rendered(
                                        MailAttachment.pdf("payslip-202610.pdf", new byte[bytes]),
                                        Map.of("month", "10/2026"))));
    }

    private DocumentMailDispatch.Outcome deliver(DocumentMailDispatch d, String type) {
        return d.deliver(
                due(1, type), SETTINGS, capacity(1_000_000, "WITHOUT_ATTACHMENT"), NOW, "me");
    }

    private void verifyFinal(String status, String detail, String failureClass) {
        verify(jdbc)
                .update(
                        contains("SET delivery_status = ?, delivery_detail = ?"),
                        eq(status),
                        eq(detail),
                        eq(101L),
                        any());
        verify(jdbc).update(contains("failure_class = ? WHERE"), eq(failureClass), eq(1L));
    }

    @Test
    void SRS_17_12_switchedOffOrNotConfiguredNothingIsRead() {
        assertThat(dispatch(on()).name()).isEqualTo("documentEmails");
        assertThat(dispatch(new DocumentMailDispatch.Options(false, 50, true, false, "")).run(NOW))
                .isZero();
        when(settings.inForce("MEMBER_EMAIL")).thenReturn(Optional.empty());
        assertThat(dispatch(on()).run(NOW)).isZero();
        when(settings.inForce("MEMBER_EMAIL")).thenReturn(Optional.of(SETTINGS));
        when(channel.config(NOW)).thenReturn(Optional.empty());
        assertThat(dispatch(on()).run(NOW)).as("capacity not set by FCI IT").isZero();
        when(channel.config(NOW)).thenReturn(Optional.of(capacity(10, "FAIL")));
        assertThat(dispatch(options(true, true, " ")).run(NOW)).as("MAIL-1 rule missing").isZero();
        verify(jdbc, never())
                .query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class));
    }

    @Test
    void SRS_INT_6_aPausedChannelAnOpenBreakerAFullWindowOrNoFreeConnectionSendNothing() {
        when(channel.pausedUntil(NOW)).thenReturn(Optional.of(NOW.plusSeconds(60)));
        assertThat(dispatch(on()).run(NOW)).isZero();
        when(channel.pausedUntil(NOW)).thenReturn(Optional.empty());
        when(breakers.admit("MEMBER_EMAIL")).thenReturn(false);
        assertThat(dispatch(on()).run(NOW)).isZero();
        when(breakers.admit("MEMBER_EMAIL")).thenReturn(true);
        when(channel.hasCapacity(any(), eq(NOW))).thenReturn(false);
        assertThat(dispatch(on()).run(NOW)).isZero();
        when(channel.hasCapacity(any(), eq(NOW))).thenReturn(true);
        when(channel.acquireSlots(anyString(), anyInt(), any(), eq(NOW))).thenReturn(List.of());
        assertThat(dispatch(on()).run(NOW)).isZero();
        verify(jdbc, never())
                .query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class));
        verify(channel, never()).releaseSlots(anyString());
    }

    @Test
    void SRS_17_12_aRunReleasesItsConnectionsAlsoWithNothingDue() {
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class)))
                .thenReturn(List.of());
        assertThat(dispatch(on()).run(NOW)).isZero();
        verify(channel).releaseSlots(anyString());
    }

    @Test
    void SRS_17_12_anEmailAnotherSenderTookIsSkipped() {
        when(jdbc.update(contains("SET attempt_count = ?, last_attempt_at"), any(Object[].class)))
                .thenReturn(0);
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.SKIPPED);
        verify(sender, never())
                .sendDocument(
                        anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void SRS_17_1_noAddressAnUnknownDocumentOrNoMemberAreRecordedNotSent() {
        member(" ", "en");
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.SUPPRESSED);
        verifyFinal("SUPPRESSED", DocumentMails.NO_EMAIL, null);
    }

    @Test
    void SRS_17_1_anUnknownDocumentTypeIsNotAvailable() {
        member("m@fci-demo.test", "en");
        assertThat(deliver(dispatch(on()), "LETTER"))
                .isEqualTo(DocumentMailDispatch.Outcome.SUPPRESSED);
        verifyFinal("SUPPRESSED", "Document not available", null);
    }

    @Test
    void SRS_17_1_aVanishedMemberHasNoAddress() {
        when(jdbc.queryForList(contains("SELECT e.email_address"), any(Object[].class)))
                .thenReturn(List.of());
        assertThat(deliver(dispatch(on()), "LETTER"))
                .isEqualTo(DocumentMailDispatch.Outcome.SUPPRESSED);
    }

    @Test
    void SRS_17_12_aBusyRenderBulkheadDefersAndEndsTheRun() {
        member("m@fci-demo.test", "en");
        when(payslips.render(anyLong(), anyString(), anyString()))
                .thenThrow(new PdfRenderGate.Busy(5));
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.QUOTA);
        verify(jdbc)
                .update(
                        contains("SET attempt_count = ?, next_attempt_at = ?"),
                        eq(0),
                        eq(Timestamp.from(NOW.plusSeconds(5))),
                        eq(1L));
    }

    @Test
    void SRS_17_12_aPdfNotReadyDefersAndARecordedGenerationFailureFails() {
        member("m@fci-demo.test", "en");
        when(payslips.render(anyLong(), anyString(), anyString()))
                .thenThrow(new DocumentNotReadyException("later"));
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.DEFERRED);
        verify(jdbc)
                .update(
                        contains("SET attempt_count = ?, next_attempt_at = ?"),
                        eq(0),
                        eq(Timestamp.from(NOW.plus(Duration.ofMinutes(15)))),
                        eq(1L));
    }

    @Test
    void SRS_17_12_aGenerationFailureFailsTheEmailWithItsReason() {
        member("m@fci-demo.test", "en");
        when(payslips.render(anyLong(), anyString(), anyString()))
                .thenThrow(new DocumentFailedException("no bank advice"));
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.FAILED);
        verifyFinal(
                "FAILED",
                "Document generation failed: DocumentFailedException: no bank advice",
                "RENDER");
    }

    @Test
    void SRS_17_12_aBusinessRefusalOrABrokenRenderFailsWithoutBlockingTheQueue() {
        member("m@fci-demo.test", "en");
        when(payslips.render(anyLong(), anyString(), anyString()))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.FAILED);
        org.mockito.Mockito.doThrow(new IllegalStateException("font"))
                .when(payslips)
                .render(anyLong(), anyString(), anyString());
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.FAILED);
        verify(jdbc, times(2)).update(contains("failure_class = ? WHERE"), eq("RENDER"), eq(1L));
        verify(sender, never())
                .sendDocument(
                        anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void SRS_17_1_aMissingPasswordValueFailsRatherThanSendUnprotected() {
        member("m@fci-demo.test", "en");
        rendered(1);
        Map<String, Object> e = new HashMap<>();
        e.put("PAN_NUMBER", null);
        e.put("BIRTH_DATE", Timestamp.from(NOW));
        e.put("EMP_NUM", "104882");
        when(jdbc.queryForMap(contains("SELECT pan_number"), any(Object[].class))).thenReturn(e);
        assertThat(deliver(dispatch(options(true, true, "{pan:4}{dob:ddMM}")), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.FAILED);
        e.put("BIRTH_DATE", Date.valueOf("1990-01-05"));
        e.put("EMP_NUM", null);
        deliver(dispatch(options(true, true, "{eis}{dob:ddMM}")), "PAYSLIP");
        e.put("BIRTH_DATE", null);
        e.put("PAN_NUMBER", "ABCDE1234F");
        deliver(dispatch(options(true, true, "{pan:4}{dob:ddMM}")), "PAYSLIP");
        verify(jdbc, times(3)).update(contains("failure_class = ? WHERE"), eq("PERMANENT"), eq(1L));
        verify(sender, never())
                .sendDocument(
                        anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void SRS_17_1_anAttachmentAboveTheCapFailsWhenTheSettingSaysFail() {
        member("m@fci-demo.test", "en");
        rendered(20);
        assertThat(
                        dispatch(on())
                                .deliver(
                                        due(1, "PAYSLIP"),
                                        SETTINGS,
                                        capacity(10, "FAIL"),
                                        NOW,
                                        "me"))
                .isEqualTo(DocumentMailDispatch.Outcome.FAILED);
        verifyFinal("FAILED", "Attachment above the size limit", "SIZE");
    }

    @Test
    void SRS_INT_16_aWindowFullAtTheSendGivesTheEmailBack() {
        member("m@fci-demo.test", "en");
        rendered(1);
        when(channel.takeToken(any(), eq(NOW))).thenReturn(false);
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.QUOTA);
        verify(jdbc)
                .update(
                        contains(
                                "claimed_by = NULL, claim_until = NULL WHERE document_mail_id = ?"
                                        + " AND claimed_by = ?"),
                        eq(0),
                        eq(1L),
                        eq("me"));
    }

    @Test
    void SRS_17_1_fciTemplateWinsInHindiWhenItIsThereAndEnglishOtherwise() {
        member("m@fci-demo.test", "hi");
        rendered(1);
        Map<String, Object> template = new HashMap<>();
        template.put("SUBJECT_EN", "EN {month}");
        template.put("BODY_EN", "EN body {name}");
        template.put("SUBJECT_HI", "HI {month}");
        template.put("BODY_HI", "HI body {name}");
        when(jdbc.queryForList(contains("FROM notification_template"), any(Object[].class)))
                .thenReturn(List.of(template));
        assertThat(deliver(dispatch(on()), "PAYSLIP")).isEqualTo(DocumentMailDispatch.Outcome.SENT);
        verify(sender)
                .sendDocument(
                        eq("m@fci-demo.test"),
                        eq("Rakesh Sharma"),
                        eq("HI 10/2026"),
                        eq("HI body Rakesh Sharma"),
                        any(MailAttachment.class),
                        eq("<document-mail-1@fcilekha.fci.gov.in>"));
        template.put("BODY_HI", null);
        deliver(dispatch(on()), "PAYSLIP");
        template.put("SUBJECT_HI", null);
        template.put("BODY_HI", "HI");
        deliver(dispatch(on()), "PAYSLIP");
        verify(sender, times(2))
                .sendDocument(
                        anyString(),
                        anyString(),
                        eq("EN 10/2026"),
                        eq("EN body Rakesh Sharma"),
                        any(MailAttachment.class),
                        anyString());
    }

    @Test
    void SRS_17_1_theMessageKeysAndTheNoticeWithoutTheAttachment() {
        member(" m@fci-demo.test ", null);
        rendered(1);
        assertThat(deliver(dispatch(options(false, true, "{eis}")), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.SENT);
        verify(sender)
                .sendDocument(
                        eq("m@fci-demo.test"),
                        eq("Rakesh Sharma"),
                        eq("Payslip 10/2026"),
                        eq("Dear Rakesh Sharma, sign in"),
                        isNull(),
                        anyString());
        verify(jdbc).update(contains("attachment_name"), isNull(), isNull(), eq(1L));
    }

    @Test
    void SRS_17_12_aPermanentRefusalFailsAtOnceAndASecondSizeRefusalWithoutPdfFails() {
        member("m@fci-demo.test", "en");
        rendered(1);
        org.mockito.Mockito.doThrow(new MailSendException("503 5.5.1 Bad sequence"))
                .when(sender)
                .sendDocument(
                        anyString(), anyString(), anyString(), anyString(), any(), anyString());
        assertThat(deliver(dispatch(on()), "PAYSLIP"))
                .isEqualTo(DocumentMailDispatch.Outcome.FAILED);
        verify(jdbc).update(contains("failure_class = ? WHERE"), eq("PERMANENT"), eq(1L));
        org.mockito.Mockito.doThrow(new MailSendException("552 too big"))
                .when(sender)
                .sendDocument(
                        anyString(), anyString(), anyString(), anyString(), any(), anyString());
        assertThat(deliver(dispatch(options(false, false, "")), "PAYSLIP"))
                .as("already without the PDF: nothing smaller to send")
                .isEqualTo(DocumentMailDispatch.Outcome.FAILED);
        verify(jdbc).update(contains("failure_class = ? WHERE"), eq("SIZE"), eq(1L));
    }

    @Test
    void SRS_INT_16_theRetryWaitsTheBackOffStretchedByTheJitter() {
        member("m@fci-demo.test", "en");
        rendered(1);
        org.mockito.Mockito.doThrow(new MailSendException("451 4.3.0 try later"))
                .when(sender)
                .sendDocument(
                        anyString(), anyString(), anyString(), anyString(), any(), anyString());
        MailChannel.Config jitter =
                new MailChannel.Config(null, null, null, 1, 300, 1_000_000, "FAIL", 50, 900);
        assertThat(dispatch(on()).deliver(due(1, "PAYSLIP"), SETTINGS, jitter, NOW, "me"))
                .isEqualTo(DocumentMailDispatch.Outcome.RETRY);
        // 60 s FIXED back-off + 50 % jitter x 0.5 = 75 s
        verify(jdbc)
                .update(
                        contains("SET next_attempt_at = ?, claimed_by = NULL"),
                        eq(Timestamp.from(NOW.plusSeconds(75))),
                        eq("TRANSIENT"),
                        eq(1L));
        verify(breakers).failed(eq("MEMBER_EMAIL"), eq("Mail server unavailable (451)"));
    }

    @Test
    void SRS_17_12_anOutboxFailureLeavesTheEmailToItsLease() {
        when(jdbc.update(contains("SET attempt_count = ?, last_attempt_at"), any(Object[].class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("lock"));
        assertThat(
                        dispatch(on())
                                .deliverSafely(
                                        due(1, "PAYSLIP"),
                                        SETTINGS,
                                        capacity(1, "FAIL"),
                                        NOW,
                                        "me"))
                .isEqualTo(DocumentMailDispatch.Outcome.SKIPPED);
    }
}
