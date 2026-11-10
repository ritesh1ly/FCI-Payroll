package in.gov.fci.payroll.document.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.business.model.DocumentType;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The order-control file rules: SRS-G-30 (types, limits), SRS-DM-13 / OC-3 (particulars), R-ASCII.
 */
class AttachmentRulesTest {

    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0};
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0};
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private static DocumentType type(boolean requiresReference) {
        DocumentType type = mock(DocumentType.class);
        when(type.requiresReferenceNumber()).thenReturn(requiresReference);
        return type;
    }

    private static FciException error(Runnable call) {
        try {
            call.run();
        } catch (FciException ex) {
            return ex;
        }
        throw new AssertionError("expected an FciException");
    }

    @Test
    void SRS_G_30_pdfJpgAndPngAreRecognisedFromTheBytes() {
        assertThat(AttachmentRules.detectContentType(PDF)).contains("application/pdf");
        assertThat(AttachmentRules.detectContentType(PNG)).contains("image/png");
        assertThat(AttachmentRules.detectContentType(JPG)).contains("image/jpeg");
    }

    @Test
    void SRS_G_30_anythingElseIsRefusedWhateverItIsCalled() {
        byte[] script = "#!/bin/sh\nrm -rf /".getBytes(StandardCharsets.US_ASCII);
        byte[] zip = {0x50, 0x4B, 0x03, 0x04};
        assertThat(AttachmentRules.detectContentType(script)).isEmpty();
        assertThat(AttachmentRules.detectContentType(zip)).isEmpty();
        assertThat(AttachmentRules.detectContentType(new byte[] {0x25}))
                .as("truncated PDF magic")
                .isEmpty();
        assertThat(error(() -> AttachmentRules.requireAcceptedFile(script, 1000)).getErrorCode())
                .isEqualTo(DocumentErrorCode.FILE_TYPE);
    }

    @Test
    void CF_13_emptyAndOversizedFilesAreRefused() {
        assertThat(
                        error(() -> AttachmentRules.requireAcceptedFile(new byte[0], 1000))
                                .getErrorCode())
                .isEqualTo(DocumentErrorCode.EMPTY_FILE);
        assertThat(error(() -> AttachmentRules.requireAcceptedFile(null, 1000)).getErrorCode())
                .isEqualTo(DocumentErrorCode.EMPTY_FILE);
        FciException tooBig = error(() -> AttachmentRules.requireAcceptedFile(PDF, PDF.length - 1));
        assertThat(tooBig.getErrorCode()).isEqualTo(DocumentErrorCode.FILE_SIZE);
        assertThat(AttachmentRules.requireAcceptedFile(PDF, PDF.length))
                .as("exactly at the limit")
                .isEqualTo("application/pdf");
    }

    @Test
    void CF_13_theFileCountLimitIsInclusive() {
        assertThatCode(() -> AttachmentRules.requireRoomForAnother(9, 10))
                .doesNotThrowAnyException();
        assertThat(error(() -> AttachmentRules.requireRoomForAnother(10, 10)).getErrorCode())
                .isEqualTo(DocumentErrorCode.FILE_COUNT);
    }

    @Test
    void SRS_DM_13_anOrderNeedsDateAndIssuingAuthorityAndNotAFutureDate() {
        DocumentType other = type(false);
        assertThatCode(
                        () ->
                                AttachmentRules.requireOrderParticulars(
                                        other, null, TODAY, "RO Delhi", TODAY))
                .doesNotThrowAnyException();
        assertThat(
                        error(
                                        () ->
                                                AttachmentRules.requireOrderParticulars(
                                                        other, null, null, "RO Delhi", TODAY))
                                .getErrorCode())
                .isEqualTo(DocumentErrorCode.ORDER_PARTICULARS);
        assertThat(
                        error(
                                        () ->
                                                AttachmentRules.requireOrderParticulars(
                                                        other, null, TODAY, " ", TODAY))
                                .getErrorCode())
                .isEqualTo(DocumentErrorCode.ORDER_PARTICULARS);
        assertThat(
                        error(
                                        () ->
                                                AttachmentRules.requireOrderParticulars(
                                                        other,
                                                        null,
                                                        TODAY.plusDays(1),
                                                        "RO",
                                                        TODAY))
                                .getErrorCode())
                .isEqualTo(DocumentErrorCode.ORDER_PARTICULARS);
    }

    @Test
    void SRS_DM_13_aSanctionOrderOrCircularNeedsItsReferenceNumber() {
        DocumentType sanction = type(true);
        assertThat(
                        error(
                                        () ->
                                                AttachmentRules.requireOrderParticulars(
                                                        sanction, "", TODAY, "HQ", TODAY))
                                .getErrorCode())
                .isEqualTo(DocumentErrorCode.REFERENCE_REQUIRED);
        assertThatCode(
                        () ->
                                AttachmentRules.requireOrderParticulars(
                                        sanction, "HQ/2026/14", TODAY, "HQ", TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    void aSystemCapturedFileWithoutTypeNeedsNoParticulars() {
        assertThatCode(() -> AttachmentRules.requireOrderParticulars(null, null, null, null, TODAY))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
        "'../../etc/passwd', passwd",
        "'C:\\Windows\\order.pdf', order.pdf",
        "'..hidden.pdf', hidden.pdf",
        "'आदेश.pdf', ____.pdf",
        "'order\"; x=1.pdf', order__ x_1.pdf",
        "'   ', document.pdf",
        "'////', document.pdf"
    })
    void fileNamesArePathFreePrintableAscii(String original, String expected) {
        assertThat(AttachmentRules.safeFileName(original, "application/pdf")).isEqualTo(expected);
    }

    @Test
    void aVeryLongFileNameKeepsItsEnd() {
        String name = "a".repeat(300) + ".pdf";
        assertThat(AttachmentRules.safeFileName(name, "application/pdf"))
                .hasSize(255)
                .endsWith(".pdf");
    }

    @Test
    void R_ASCII_particularsMustBePrintableAscii() {
        assertThat(AttachmentRules.ascii("  RO/DL/2026  ", 60)).isEqualTo("RO/DL/2026");
        assertThat(AttachmentRules.ascii(" ", 60)).isNull();
        assertThat(AttachmentRules.ascii("x".repeat(70), 60)).hasSize(60);
        assertThatThrownBy(() -> AttachmentRules.ascii("आदेश", 60))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> AttachmentRules.ascii("a\u0007b", 60))
                .isInstanceOf(FciException.class);
    }

    @Test
    void theChecksumIsLowerCaseHexSha256() {
        assertThat(AttachmentRules.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void SRS_G_46_aSystemSourceFileMustBePlainText() {
        assertThat(
                        AttachmentRules.requireTextSourceFile(
                                "A,B\r\n1\t2\n".getBytes(StandardCharsets.US_ASCII), 100))
                .isEqualTo("text/csv");
        assertThatThrownBy(() -> AttachmentRules.requireTextSourceFile(null, 100))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(DocumentErrorCode.EMPTY_FILE));
        assertThatThrownBy(() -> AttachmentRules.requireTextSourceFile(new byte[0], 100))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> AttachmentRules.requireTextSourceFile(new byte[101], 100))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(DocumentErrorCode.FILE_SIZE));
        // One binary or non-ASCII byte refuses the whole file.
        assertThatThrownBy(() -> AttachmentRules.requireTextSourceFile(new byte[] {'a', 0x00}, 100))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(DocumentErrorCode.FILE_TYPE));
        assertThatThrownBy(
                        () ->
                                AttachmentRules.requireTextSourceFile(
                                        new byte[] {'a', (byte) 0xC3}, 100))
                .isInstanceOf(FciException.class);
    }

    @Test
    void nothingIsRecognisedFromNoBytes() {
        assertThat(AttachmentRules.detectContentType(null)).isEmpty();
    }

    @Test
    void aNamelessFileIsNamedAfterItsDetectedType() {
        assertThat(AttachmentRules.safeFileName(null, "image/png")).isEqualTo("document.png");
        assertThat(AttachmentRules.safeFileName("", "image/jpeg")).isEqualTo("document.jpg");
        assertThat(AttachmentRules.safeFileName("___", "text/csv")).isEqualTo("document");
        assertThat(AttachmentRules.safeFileName(null, null)).isEqualTo("document");
    }

    @Test
    void R_ASCII_particularsAreStrippedAndKeptWithinTheColumn() {
        assertThat(AttachmentRules.ascii(null, 10)).isNull();
        assertThat(AttachmentRules.ascii("   ", 10)).isNull();
        assertThat(AttachmentRules.ascii("  RO/14  ", 10)).isEqualTo("RO/14");
        assertThat(AttachmentRules.ascii("x".repeat(12), 10)).hasSize(10);
        assertThatThrownBy(() -> AttachmentRules.ascii("RO\u007f14", 10))
                .isInstanceOf(FciException.class);
    }

    @Test
    void CF_13_aFileOfExactlyTheLimitIsAcceptedAndTheRefusalNamesTheLimitInMegabytes() {
        long limit = 2L * 1024 * 1024;
        byte[] pdf = new byte[(int) limit];
        System.arraycopy(
                "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, pdf, 0, 5);
        assertThat(AttachmentRules.requireAcceptedFile(pdf, limit)).isEqualTo("application/pdf");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> AttachmentRules.requireAcceptedFile(new byte[(int) limit + 1], limit))
                .satisfies(
                        t ->
                                assertThat(
                                                ((in.gov.fci.payroll.platform.error.FciException) t)
                                                        .getMessageArgs())
                                        .containsExactly(2L));
        byte[] text = new byte[(int) limit];
        java.util.Arrays.fill(text, (byte) 'a');
        assertThat(AttachmentRules.requireTextSourceFile(text, limit)).isEqualTo("text/csv");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                AttachmentRules.requireTextSourceFile(
                                        new byte[(int) limit + 1], limit))
                .satisfies(
                        t ->
                                assertThat(
                                                ((in.gov.fci.payroll.platform.error.FciException) t)
                                                        .getMessageArgs())
                                        .containsExactly(2L));
    }

    @Test
    void R_ASCII_aSourceFileIsPrintableAsciiFromSpaceToTilde() {
        assertThat(
                        AttachmentRules.requireTextSourceFile(
                                new byte[] {0x20, 0x7E, 0x09, 0x0A, 0x0D}, 10))
                .isEqualTo("text/csv");
        for (byte refused : new byte[] {0x1F, 0x7F, 0x00, (byte) 0x80}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    AttachmentRules.requireTextSourceFile(
                                            new byte[] {0x41, refused}, 10))
                    .as(String.valueOf(refused))
                    .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        }
    }

    @Test
    void SRS_G_30_aFileHoldingOnlyItsSignatureIsStillRecognised() {
        assertThat(
                        AttachmentRules.detectContentType(
                                "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .contains("application/pdf");
        assertThat(
                        AttachmentRules.detectContentType(
                                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))
                .contains("image/jpeg");
        // One byte short of a signature is nothing.
        assertThat(AttachmentRules.detectContentType(new byte[] {(byte) 0xFF, (byte) 0xD8}))
                .isEmpty();
    }

    @Test
    void R_ASCII_aParticularMayContainSpacesButNoControlCharacter() {
        assertThat(AttachmentRules.ascii("  HQ Personnel Division  ", 100))
                .isEqualTo("HQ Personnel Division");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> AttachmentRules.ascii("HQ" + (char) 0x1F + "P", 100))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
    }
}
