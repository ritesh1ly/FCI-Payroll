package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.eseva.orchestration.DocumentMailDispatch;
import in.gov.fci.payroll.platform.notification.MemberDocumentsReleased;
import in.gov.fci.payroll.platform.notification.RecipientRejectedException;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The e-mailed payslip and Form 16 (OD-148; SRS-17-1 l.3250, O-01 l.3390, l.3392; SRS-17-12;
 * SRS-INT-16; PENDING F16-5, MAIL-1, MAIL-2): queued once in the lock's (release's) transaction,
 * one per member with a registered e-mail, the others listed SUPPRESSED; sent by the batch
 * dispatcher with the PDF the member downloads (masked), in the member's language, retried by the
 * MEMBER_EMAIL settings (demo seed V082_62: 3 retries) and failed without touching the lock; the
 * Payroll Maker reads the office's e-mails in scope, the member only the own. Fixture months in
 * 1998, which no other test uses.
 */
class DocumentMailIT extends DocumentMailSupport {

    // SC-14-0017 / O-01 l.3390: after the lock the payslip is e-mailed; the member without an
    // e-mail ID is listed, not failed silently; once only (l.3392: the same document).
    @Test
    void SRS_17_1_theLockQueuesOnePayslipEmailPerMemberAndSendsTheMaskedPdfOnce() throws Exception {
        YearMonth month = YearMonth.of(1998, 1);
        long cycle = lockedMonth(month);
        lock(cycle, month);
        assertThat(count("PAYSLIP", "199801")).isEqualTo(2);
        assertThat(row(self, "PAYSLIP", "199801")).containsEntry("DELIVERY_STATUS", "QUEUED");
        assertThat(row(other, "PAYSLIP", "199801"))
                .containsEntry("DELIVERY_STATUS", "SUPPRESSED")
                .containsEntry("DELIVERY_DETAIL", "No registered e-mail");
        lock(cycle, month);
        assertThat(count("PAYSLIP", "199801"))
                .as("a repeated lock event queues nothing")
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'document_mail' AND entity_key = ?",
                                Long.class,
                                "PAYSLIP|" + unit + "|199801"))
                .as("R-AUD: the queueing is audited in the lock's transaction")
                .isEqualTo(1);

        Server server = new Server();
        dispatch(server).run(Instant.now());
        List<Sent> sent = server.about("199801");
        assertThat(sent).hasSize(1);
        Sent mail = sent.getFirst();
        assertThat(mail.email())
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT email_address FROM employee WHERE employee_id = ?",
                                String.class,
                                self));
        // The demo seed holds FCI's PAYSLIP template (V050_9, demo text): it wins over the keys.
        assertThat(mail.subject()).isEqualTo("FCILekha payslip 01/1998 (demo text)");
        assertThat(mail.body()).contains("Rakesh").contains("net pay 39600");
        assertThat(mail.attachment().fileName()).isEqualTo("payslip-199801.pdf");
        assertThat(mail.attachment().contentType()).isEqualTo("application/pdf");
        String pan =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE employee_id = ?",
                        String.class,
                        self);
        try (PDDocument pdf = Loader.loadPDF(mail.attachment().content())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("JANUARY 1998");
            if (pan != null) {
                assertThat(text).as("R-PII: the PAN masked as on screen").doesNotContain(pan);
            }
        }
        assertThat(row(self, "PAYSLIP", "199801"))
                .containsEntry("DELIVERY_STATUS", "SENT")
                .containsEntry("ATTACHMENT_NAME", "payslip-199801.pdf");

        dispatch(server).run(Instant.now().plusSeconds(86_400));
        assertThat(server.about("199801")).as("never sent twice").hasSize(1);
    }

    @Test
    void SRS_17_12_aFailingMailServerRetriesByTheSettingsThenFailsWithoutBlockingTheLock() {
        YearMonth month = YearMonth.of(1998, 2);
        long cycle = lockedMonth(month);
        Server server = new Server();
        server.failure = new IllegalStateException("smtp down");
        lock(cycle, month);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cycle_status FROM payroll_cycle_instance WHERE"
                                        + " cycle_instance_id = ?",
                                String.class,
                                cycle))
                .isEqualTo("LOCKED");
        int retries = settings.inForce(DocumentMailDispatch.INTERFACE).orElseThrow().retryCount();
        Instant at = Instant.now();
        dispatch(server).run(at);
        Map<String, Object> first = row(self, "PAYSLIP", "199802");
        assertThat(first).containsEntry("DELIVERY_STATUS", "QUEUED");
        assertThat(((Number) first.get("ATTEMPT_COUNT")).intValue()).isEqualTo(1);
        assertThat((String) first.get("DELIVERY_DETAIL")).startsWith("Attempt 1 failed");
        dispatch(server).run(at);
        assertThat(((Number) row(self, "PAYSLIP", "199802").get("ATTEMPT_COUNT")).intValue())
                .as("not due before the back-off")
                .isEqualTo(1);
        for (int day = 1; day <= retries + 2; day++) {
            dispatch(server).run(at.plusSeconds(86_400L * 30 * day));
        }
        Map<String, Object> last = row(self, "PAYSLIP", "199802");
        assertThat(last)
                .containsEntry("DELIVERY_STATUS", "FAILED")
                .containsEntry("BOUNCED_FLAG", "N");
        assertThat(((Number) last.get("ATTEMPT_COUNT")).intValue()).isEqualTo(retries + 1);
        assertThat((String) last.get("DELIVERY_DETAIL"))
                .startsWith("Failed after " + (retries + 1) + " attempt(s)")
                .contains("smtp down");
    }

    // MAIL-2: a recipient the server refuses is a bounce: failed at once, listed.
    @Test
    void SRS_17_12_aRefusedRecipientIsMarkedBouncedAtOnce() {
        YearMonth month = YearMonth.of(1998, 3);
        lock(lockedMonth(month), month);
        Server server = new Server();
        server.failure =
                new RecipientRejectedException("Recipient refused by the mail server", null);
        dispatch(server).run(Instant.now());
        Map<String, Object> r = row(self, "PAYSLIP", "199803");
        assertThat(r).containsEntry("DELIVERY_STATUS", "FAILED").containsEntry("BOUNCED_FLAG", "Y");
        assertThat(((Number) r.get("ATTEMPT_COUNT")).intValue()).isEqualTo(1);
        assertThat((String) r.get("DELIVERY_DETAIL")).startsWith("Bounced (550)");
    }

    @Test
    void SRS_17_1_aMemberWhosePreferredLanguageIsHindiGetsTheHindiEmail() {
        YearMonth month = YearMonth.of(1998, 4);
        lock(lockedMonth(month), month);
        locale("hi");
        Server server = new Server();
        dispatch(server).run(Instant.now());
        Sent mail = server.about("199804").getFirst();
        assertThat(mail.subject()).as("FCI's template, Hindi").isEqualTo("वेतन पर्ची 04/1998");
        assertThat(mail.body()).contains("शुद्ध वेतन 39600");

        YearMonth august = YearMonth.of(1998, 8);
        lock(lockedMonth(august), august);
        withoutTemplate(() -> dispatch(server).run(Instant.now()));
        Sent keyed = server.about("199808").getFirst();
        assertThat(keyed.subject())
                .as("the message keys, Hindi")
                .isEqualTo("FCILekha: 08/1998 की आपकी वेतन पर्ची");
        assertThat(keyed.body()).contains("संलग्न");
    }

    // MAIL-1: protection off until FCI decides; when on, the rule is a setting and required.
    @Test
    void SRS_17_1_aPasswordProtectedPdfOpensOnlyWithThePasswordOfTheConfiguredRule()
            throws Exception {
        YearMonth month = YearMonth.of(1998, 5);
        lock(lockedMonth(month), month);
        Server server = new Server();
        assertThat(dispatch(server, true, true, " ").run(Instant.now()))
                .as("protection on without a rule: nothing goes out unprotected")
                .isZero();
        assertThat(server.about("199805")).isEmpty();
        assertThat(row(self, "PAYSLIP", "199805")).containsEntry("DELIVERY_STATUS", "QUEUED");

        dispatch(server, true, true, "{eis}{dob:ddMMyyyy}").run(Instant.now());
        Sent mail = server.about("199805").getFirst();
        Map<String, Object> e =
                jdbc.queryForMap(
                        "SELECT emp_num, birth_date FROM employee WHERE employee_id = ?", self);
        Object b = e.get("BIRTH_DATE");
        LocalDate birth =
                b instanceof java.sql.Timestamp t
                        ? t.toLocalDateTime().toLocalDate()
                        : ((Date) b).toLocalDate();
        String password = e.get("EMP_NUM") + birth.format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        assertThatThrownBy(() -> Loader.loadPDF(mail.attachment().content()).close())
                .isInstanceOf(InvalidPasswordException.class);
        try (PDDocument pdf = Loader.loadPDF(mail.attachment().content(), password)) {
            assertThat(pdf.isEncrypted()).isTrue();
            assertThat(pdf.getNumberOfPages()).isPositive();
        }
    }

    // MAIL-1: attachment off: a notice to download it in eSeva, no file.
    @Test
    void SRS_17_1_withTheAttachmentSettingOffTheEmailTellsTheMemberToDownloadIt() {
        YearMonth month = YearMonth.of(1998, 6);
        lock(lockedMonth(month), month);
        Server server = new Server();
        withoutTemplate(() -> dispatch(server, false, false, "").run(Instant.now()));
        Sent mail = server.about("06/1998").getFirst();
        assertThat(mail.subject()).isEqualTo("FCILekha: your payslip for 06/1998");
        assertThat(mail.attachment()).isNull();
        assertThat(mail.body()).contains("Sign in to eSeva");
        assertThat(row(self, "PAYSLIP", "199806"))
                .containsEntry("DELIVERY_STATUS", "SENT")
                .containsEntry("ATTACHMENT_NAME", null);
    }

    // OD-148 / F16-5: Form 16 on release, once per certificate; a certificate that cannot be read
    // as released is not sent (listed, never retried).
    @Test
    void SRS_17_12_aReleasedForm16IsQueuedOnceAndAnUnreadableOneIsNotSent() {
        MemberDocumentsReleased released =
                new MemberDocumentsReleased(
                        "FORM16",
                        unit,
                        "it.checker",
                        List.of(
                                new MemberDocumentsReleased.Item(self, "1997-98", "999999901"),
                                new MemberDocumentsReleased.Item(other, "1997-98", "999999902")));
        new TransactionTemplate(transactions)
                .executeWithoutResult(tx -> events.publishEvent(released));
        new TransactionTemplate(transactions)
                .executeWithoutResult(tx -> events.publishEvent(released));
        assertThat(count("FORM16", "1997-98")).isEqualTo(2);
        assertThat(row(other, "FORM16", "1997-98"))
                .containsEntry("DELIVERY_STATUS", "SUPPRESSED")
                .containsEntry("DELIVERY_DETAIL", "No registered e-mail");
        Server server = new Server();
        dispatch(server).run(Instant.now());
        assertThat(server.about("1997-98")).isEmpty();
        assertThat(row(self, "FORM16", "1997-98"))
                .containsEntry("DELIVERY_STATUS", "SUPPRESSED")
                .containsEntry("DELIVERY_DETAIL", "Document not available");
    }

    // Definition of done 3: wrong role, out-of-scope office; the member reads the own only.
    @Test
    void SRS_17_12_thePayrollMakerReadsTheOfficesEmailsInScopeAndTheMemberOnlyTheOwn()
            throws Exception {
        YearMonth month = YearMonth.of(1998, 7);
        lock(lockedMonth(month), month);
        String path =
                "/v1/document-mails?documentType=PAYSLIP&orgUnitId=" + unit + "&period=199807";
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode view = body(mvc.perform(userGet(maker, path)).andReturn());
        assertThat(view.path("counts").path("QUEUED").asLong()).isEqualTo(1);
        assertThat(view.path("counts").path("SUPPRESSED").asLong()).isEqualTo(1);
        assertThat(view.path("page").path("items").get(0).path("status").asString())
                .as("problems first")
                .isEqualTo("SUPPRESSED");
        assertThat(view.toString()).as("R-PII: no address").doesNotContain("@");
        JsonNode suppressed =
                body(mvc.perform(userGet(maker, path + "&status=SUPPRESSED")).andReturn());
        assertThat(suppressed.path("page").path("items")).hasSize(1);

        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(mvc.perform(userGet(chennai, path)).andReturn().getResponse().getStatus())
                .as("another office")
                .isEqualTo(403);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(mvc.perform(userGet(member, path)).andReturn().getResponse().getStatus())
                .as("wrong role")
                .isEqualTo(403);
        assertThat(
                        mvc.perform(userGet(maker, path.replace("PAYSLIP", "SALARY")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);

        JsonNode mine =
                body(
                        mvc.perform(userGet(member, "/v1/eseva/document-mails/mine?size=100"))
                                .andReturn());
        long own =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_mail WHERE employee_id = ?",
                        Long.class,
                        self);
        assertThat(mine.path("total").asLong()).isEqualTo(own);
        assertThat(mine.toString()).contains("199807");
        assertThat(
                        mvc.perform(userGet(maker, "/v1/eseva/document-mails/mine"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("staff hold no own eSeva record read")
                .isEqualTo(403);
    }
}
