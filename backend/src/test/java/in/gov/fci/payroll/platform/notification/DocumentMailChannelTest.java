package in.gov.fci.payroll.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.gov.fci.payroll.platform.logging.LogCapture;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The e-mailed member documents on each channel (OD-148; SRS-17-1, SRS-17-12; PENDING MAIL-1,
 * MAIL-2; R-PII): by SMTP a UTF-8 MIME message with the PDF attached (or the body alone), a refused
 * recipient reported as a bounce; on the local log channel the masked address, subject, body length
 * and the attachment's name and size, never the body or the file; a channel that cannot carry
 * documents says so.
 */
class DocumentMailChannelTest {

    private static final String FROM = "noreply@fci-demo.test";
    private static final String TO = "emp.rsharma@fci-demo.test";

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final NotificationSender smtp =
            new NotificationSenders().smtpNotificationSender(mail, FROM);

    private static byte[] pdf() throws IOException {
        try (PDDocument d = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.addPage(new PDPage());
            d.save(out);
            return out.toByteArray();
        }
    }

    private MimeMessage sent() {
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(message.capture());
        MimeMessage m = message.getValue();
        try {
            m.saveChanges(); // as JavaMail does at sending: the parts' headers are set
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    private void mimeMessages() {
        when(mail.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void SRS_17_1_theSmtpChannelSendsTheHindiSubjectAndThePdfAsAttachment() throws Exception {
        mimeMessages();
        smtp.sendDocument(
                TO,
                "Rakesh Sharma",
                "FCILekha: 10/2026 की आपकी वेतन पर्ची",
                "प्रिय Rakesh Sharma",
                MailAttachment.pdf("payslip-202610.pdf", pdf()),
                "<document-mail-7@fcilekha.fci.gov.in>");
        MimeMessage m = sent();
        assertThat(m.getMessageID())
                .as("the e-mail's own Message-ID, the same on every attempt")
                .isEqualTo("<document-mail-7@fcilekha.fci.gov.in>");
        assertThat(m.getFrom()).extracting(Address::toString).containsExactly(FROM);
        assertThat(m.getAllRecipients()).extracting(Address::toString).containsExactly(TO);
        assertThat(m.getSubject()).isEqualTo("FCILekha: 10/2026 की आपकी वेतन पर्ची");
        Multipart parts = (Multipart) m.getContent();
        Part attached = null;
        for (int i = 0; i < parts.getCount(); i++) {
            Part p = parts.getBodyPart(i);
            if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
                attached = p;
            }
        }
        assertThat(attached).isNotNull();
        assertThat(attached.getFileName()).isEqualTo("payslip-202610.pdf");
        assertThat(attached.getContentType()).startsWith("application/pdf");
    }

    @Test
    void SRS_17_1_withoutAnAttachmentTheBodyGoesAlone() throws Exception {
        mimeMessages();
        smtp.sendDocument(TO, "R", "Subject", "Sign in to eSeva", null, null);
        MimeMessage m = sent();
        assertThat(m.getContent()).isEqualTo("Sign in to eSeva");
    }

    @Test
    void SRS_17_12_aRecipientTheServerRefusesIsABounceAndOtherFailuresAreRethrown() {
        mimeMessages();
        SendFailedException invalid =
                new SendFailedException(
                        "550 unknown",
                        null,
                        new Address[0],
                        new Address[0],
                        new Address[] {address()});
        doThrow(new MailSendException("refused", invalid)).when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> smtp.sendDocument(TO, "R", "S", "B", null, null))
                .isInstanceOf(RecipientRejectedException.class)
                .hasMessageNotContaining(TO);

        MailSendException byMessage =
                new MailSendException(
                        Map.of(
                                new Object(),
                                new SendFailedException(
                                        "550", null, null, null, new Address[] {address()})));
        assertThat(NotificationSenders.rejectsRecipient(byMessage)).isTrue();
        MailSendException down = new MailSendException("connection refused");
        assertThat(NotificationSenders.rejectsRecipient(down)).isFalse();
        assertThat(
                        NotificationSenders.rejectsRecipient(
                                new MailSendException(
                                        "x", new SendFailedException("421 try later"))))
                .isFalse();
        doThrow(down).when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> smtp.sendDocument(TO, "R", "S", "B", null, null)).isSameAs(down);
    }

    @Test
    void SRS_17_12_aMessageThatCannotBePreparedIsAPreparationFailure() throws Exception {
        MimeMessage broken = mock(MimeMessage.class);
        when(mail.createMimeMessage()).thenReturn(broken);
        org.mockito.Mockito.doThrow(new MessagingException("bad"))
                .when(broken)
                .setSubject(any(), any());
        assertThatThrownBy(() -> smtp.sendDocument(TO, "R", "S", "B", null, null))
                .isInstanceOf(org.springframework.mail.MailPreparationException.class);
    }

    private static InternetAddress address() {
        try {
            return new InternetAddress(TO);
        } catch (jakarta.mail.internet.AddressException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void R_PII_theLogChannelRecordsTheDocumentEmailWithoutItsBodyOrFile() throws Exception {
        byte[] file = pdf();
        try (LogCapture logs = LogCapture.of("FCI_NOTIFICATIONS", Level.INFO)) {
            NotificationSender log = new NotificationSenders().loggingNotificationSender(false);
            log.sendDocument(
                    TO,
                    "Rakesh Sharma",
                    "FCILekha: your payslip for 10/2026",
                    "Dear Rakesh Sharma, secret body",
                    MailAttachment.pdf("payslip-202610.pdf", file),
                    "<document-mail-7@fcilekha.fci.gov.in>");
            log.sendDocument(TO, "Rakesh Sharma", "S", null, null, null);
            List<String> messages = logs.messages();
            assertThat(messages).hasSize(2);
            assertThat(messages.getFirst())
                    .contains("type=DOCUMENT")
                    .contains("subject=\"FCILekha: your payslip for 10/2026\"")
                    .contains("attachment=payslip-202610.pdf")
                    .contains("attachmentBytes=" + file.length)
                    .contains("bodyLength=31")
                    .doesNotContain(TO)
                    .doesNotContain("secret body");
            assertThat(messages.get(1)).contains("attachment=none").contains("bodyLength=0");
        }
    }

    @Test
    void SRS_17_12_aChannelWithoutDocumentsRefusesSoTheEmailIsRetried() {
        NotificationSender bare =
                new NotificationSender() {
                    @Override
                    public void sendOtp(String e, String n, String p, String c, int m) {}

                    @Override
                    public void sendAccountLocked(String e, String n) {}
                };
        assertThatThrownBy(() -> bare.sendDocument(TO, "R", "S", "B", null, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void R_PII_anAttachmentNamesItsFileAndSizeNeverItsContentAndIsCopied() throws Exception {
        byte[] bytes = {1, 2, 3};
        MailAttachment a = MailAttachment.pdf("a.pdf", bytes);
        bytes[0] = 9;
        assertThat(a.content()).containsExactly(1, 2, 3);
        a.content()[1] = 9;
        assertThat(a.content()).containsExactly(1, 2, 3);
        assertThat(a.size()).isEqualTo(3);
        assertThat(a).hasToString("MailAttachment[a.pdf, application/pdf, 3 B]");
        assertThat(a)
                .isEqualTo(MailAttachment.pdf("a.pdf", new byte[] {1, 2, 3}))
                .hasSameHashCodeAs(MailAttachment.pdf("a.pdf", new byte[] {1, 2, 3}))
                .isNotEqualTo(MailAttachment.pdf("b.pdf", new byte[] {1, 2, 3}))
                .isNotEqualTo(new MailAttachment("a.pdf", "text/plain", new byte[] {1, 2, 3}))
                .isNotEqualTo(MailAttachment.pdf("a.pdf", new byte[] {1}))
                .isNotEqualTo("a.pdf");
        assertThatThrownBy(() -> new MailAttachment(null, "x", bytes))
                .isInstanceOf(NullPointerException.class);
    }

    // MAIL-1: a protected PDF opens only with its password.
    @Test
    void SRS_17_1_aProtectedPdfOpensOnlyWithTheMembersPassword() throws Exception {
        byte[] protectedPdf = PdfProtection.protect(pdf(), "ABCD01011990");
        assertThatThrownBy(() -> Loader.loadPDF(protectedPdf).close())
                .isInstanceOf(InvalidPasswordException.class);
        try (PDDocument d = Loader.loadPDF(protectedPdf, "ABCD01011990")) {
            assertThat(d.isEncrypted()).isTrue();
            assertThat(d.getCurrentAccessPermission().canModify()).isFalse();
            assertThat(d.getCurrentAccessPermission().canPrint()).isTrue();
        }
        assertThatThrownBy(() -> PdfProtection.protect(new byte[] {1, 2}, "x"))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void OD_148_theReleasedDocumentsAndRenderedValuesAreCopied() {
        List<MemberDocumentsReleased.Item> items =
                new java.util.ArrayList<>(List.of(new MemberDocumentsReleased.Item(1, "x", "y")));
        MemberDocumentsReleased e = new MemberDocumentsReleased("FORM16", 3, "c", items);
        items.clear();
        assertThat(e.items()).hasSize(1);
        Map<String, String> values = new java.util.HashMap<>(Map.of("month", "10/2026"));
        MemberDocuments.Rendered r =
                new MemberDocuments.Rendered(MailAttachment.pdf("a.pdf", new byte[] {1}), values);
        values.clear();
        assertThat(r.values()).containsEntry("month", "10/2026");
        assertThat(new RecipientRejectedException("m", null)).hasMessage("m");
    }
}
