package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.document.business.DocumentErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The document store on the real schema (V025: document_attachment, document_content,
 * document_access_log) - SRS-G-30, SRS-G-31, SRS-DM-13, BR-AUDIT-009, KB OC-7 / OC-8.
 */
class DocumentStoreIT extends ApiTestSupport {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired DocumentStore documents;

    private static String record() {
        return "it-" + UUID.randomUUID();
    }

    private static DocumentStore.NewAttachment order(String key, String type, String reference) {
        return new DocumentStore.NewAttachment(
                "off_cycle_payment",
                key,
                type,
                reference,
                LocalDate.of(2026, 10, 1),
                "RO Delhi",
                "Order 14.pdf",
                PDF,
                true);
    }

    @Test
    void SRS_G_30_anOrderIsStoredWithItsParticularsBytesChecksumAndAuditEntry() {
        String key = record();
        DocumentStore.AttachmentView view =
                documents.attach(
                        "maker.delhi@fci-demo.test", order(key, "SANCTION_ORDER", "RO/DL/14"));

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.contentType()).isEqualTo("application/pdf");
        assertThat(view.documentTypeName()).isEqualTo("Sanction Order");
        assertThat(view.sizeBytes()).isEqualTo(PDF.length);
        assertThat(view.checksumSha256()).hasSize(64);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT storage_provider FROM document_attachment WHERE"
                                        + " attachment_id = ?",
                                String.class,
                                view.id()))
                .isEqualTo("ORACLE_SECUREFILE");
        // The folder is the configured key layout: prefix, owning record type, month (India).
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT storage_object_key FROM document_attachment WHERE"
                                        + " attachment_id = ?",
                                String.class,
                                view.id()))
                .startsWith(
                        String.format(
                                "fci-payroll/off_cycle_payment/%04d/%02d/",
                                today.getYear(), today.getMonthValue()))
                .hasSize("fci-payroll/off_cycle_payment/yyyy/mm/".length() + 36);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_content WHERE attachment_id = ?",
                                Integer.class,
                                view.id()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_key = ?",
                                Integer.class,
                                key))
                .as("one audit entry in the same transaction")
                .isEqualTo(1);
        assertThat(documents.countActive("off_cycle_payment", key)).isEqualTo(1);
    }

    @Test
    void OC_3_aSanctionOrderWithoutReferenceIsRefusedAndNothingIsStored() {
        String key = record();
        assertThatThrownBy(
                        () ->
                                documents.attach(
                                        "maker.delhi@fci-demo.test",
                                        order(key, "SANCTION_ORDER", null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(DocumentErrorCode.REFERENCE_REQUIRED));
        assertThat(documents.countActive("off_cycle_payment", key)).isZero();
        assertThatThrownBy(
                        () ->
                                documents.attach(
                                        "maker.delhi@fci-demo.test", order(key, "MEMO", "x")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(DocumentErrorCode.UNKNOWN_TYPE));
    }

    @Test
    void OC_7_readingIsLimitedToTheOwningRecordAndEveryReadIsLogged() {
        String key = record();
        Long id = documents.attach("maker.delhi@fci-demo.test", order(key, "OTHER", null)).id();

        DocumentStore.Content content =
                documents.read("ddo.delhi@fci-demo.test", "10.0.0.7", "off_cycle_payment", key, id);
        assertThat(content.bytes()).isEqualTo(PDF);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_access_log WHERE attachment_id = ?"
                                        + " AND accessed_by = ? AND access_type = 'DOWNLOAD'",
                                Integer.class,
                                id,
                                "ddo.delhi@fci-demo.test"))
                .isEqualTo(1);

        // The same id through another record, or another entity, is "not found": no probing.
        assertThatThrownBy(() -> documents.read("x", null, "off_cycle_payment", record(), id))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> documents.read("x", null, "supplementary_payment", key, id))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void SRS_G_31_aReplacedFileIsSupersededAndCancellingKeepsEveryRow() {
        String key = record();
        Long first = documents.attach("maker.delhi@fci-demo.test", order(key, "OTHER", null)).id();
        Long second =
                documents
                        .replace(
                                "maker.delhi@fci-demo.test",
                                "off_cycle_payment",
                                key,
                                first,
                                order(key, "OTHER", null))
                        .id();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT attachment_status FROM document_attachment WHERE"
                                        + " attachment_id = ?",
                                String.class,
                                first))
                .isEqualTo("SUPERSEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT superseded_by_attachment_id FROM document_attachment WHERE"
                                        + " attachment_id = ?",
                                Long.class,
                                first))
                .isEqualTo(second);
        assertThat(documents.active("off_cycle_payment", key))
                .extracting(DocumentStore.AttachmentView::id)
                .containsExactly(second);

        documents.cancelAll(
                "maker.delhi@fci-demo.test", "off_cycle_payment", key, "Case cancelled");
        assertThat(documents.countActive("off_cycle_payment", key)).isZero();
        // Never deleted (BR-AUDIT-009): both rows and both files are still there.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_attachment WHERE entity_key = ?",
                                Integer.class,
                                key))
                .isEqualTo(2);
        assertThat(documents.read("checker", null, "off_cycle_payment", key, first).bytes())
                .isEqualTo(PDF);
    }

    @Test
    void CF_13_aNonPdfImageFileOrAnEmptyFileIsRefused() {
        DocumentStore.NewAttachment exe =
                new DocumentStore.NewAttachment(
                        "off_cycle_payment",
                        record(),
                        "OTHER",
                        null,
                        LocalDate.of(2026, 10, 1),
                        "RO",
                        "order.pdf",
                        new byte[] {0x4D, 0x5A, 0, 0},
                        false);
        assertThatThrownBy(() -> documents.attach("m", exe))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(DocumentErrorCode.FILE_TYPE));
    }

    @Test
    void SRS_G_30_signedInSessionsReadTheDocumentTypesAnonymousOnesDoNot() throws Exception {
        tools.jackson.databind.JsonNode types =
                body(
                        mvc.perform(
                                        userGet(
                                                login(
                                                        "maker.delhi@fci-demo.test",
                                                        "PAYROLL_MAKER",
                                                        "DH001"),
                                                "/v1/document-types"))
                                .andReturn());
        assertThat(texts(types, "code")).contains("SANCTION_ORDER", "CIRCULAR", "OTHER");
        assertThat(types.get(0).has("referenceRequired")).isTrue();
        int anonymous =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/v1/document-types"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(anonymous).isEqualTo(401);
    }
}
