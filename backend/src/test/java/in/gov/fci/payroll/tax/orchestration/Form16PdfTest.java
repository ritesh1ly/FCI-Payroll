package in.gov.fci.payroll.tax.orchestration;

import static in.gov.fci.payroll.platform.export.PdfText.pages;
import static in.gov.fci.payroll.platform.export.PdfText.text;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.export.PdfDocument;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * SRS-7-5, SRS-7-9, SRS-7-23, SRS-7-30: the Part B PDF prints the prescribed form's text and the
 * worked year's figures, the employee's PAN in full (the form prescribes it), nothing of ours, and
 * is placed after the received Part A.
 */
class Form16PdfTest {

    static Form16Service.PartBDocument document(Regime regime, String status) {
        Form16Service.CertificateView view =
                new Form16Service.CertificateView(
                        5L,
                        9L,
                        "870001",
                        "Ravi Sharma",
                        "2025-26",
                        new BigDecimal("1240000.00"),
                        new BigDecimal("683800.00"),
                        new BigDecimal("26000.00"),
                        "RECEIVED",
                        status,
                        Instant.parse("2026-05-30T05:00:00Z"),
                        Instant.parse("2026-06-12T05:00:00Z"),
                        77L,
                        "******234K",
                        "VALID");
        return new Form16Service.PartBDocument(
                view,
                "Regional Office Delhi",
                "ABCPS1234K",
                Form16PartB.of(Form16PartBTest.worked(regime)).orElseThrow());
    }

    @Test
    void SRS_7_30_thePartBPdfPrintsThePrescribedFormAndTheFigures() {
        String text = text(Form16Pdf.of(document(Regime.OLD, "RELEASED")));
        assertThat(text)
                .contains("FORM NO. 16", "[See rule 31(1)(a)]", "PART B (Annexure)")
                .contains("Details of Salary Paid and any other income and tax deducted")
                .contains("Whether opting for taxation u/s 115BAC?", "Yes/No")
                .contains("Food Corporation of India, Regional Office Delhi")
                .contains("Assessment Year", "2026-27", "01-04-2025", "31-03-2026")
                .contains("Salary as per provisions contained in section 17(1)", "12,60,000.00")
                .contains("House rent allowance under section 10(13A)", "1,00,000.00")
                .contains("Gross total income (6+8)", "9,78,800.00")
                .contains("Deductions under Chapter VI-A", "Gross Amount", "Deductible Amount")
                .contains("Total taxable income (9-11)", "6,83,800.00")
                .contains("Health and education cess", "1,000.00")
                .contains("Net tax payable (17-18)", "26,000.00")
                .contains("Verification", "do hereby certify that the information given above");
        // The 115BAC answer: "No" under the old regime (the heading "Yes/No" is the only "Yes").
        assertThat(text.split("Yes", -1).length - 1).isEqualTo(1);
        // Nothing outside the form: no page numbers, no generation time.
        assertThat(text).doesNotContain("Page 1 of", "FCILekha");
    }

    @Test
    void SRS_7_23_theEmployeesPanIsPrintedInFullOnTheCertificateThatPrescribesIt() {
        String text = text(Form16Pdf.of(document(Regime.NEW, "RELEASED")));
        assertThat(text).contains("ABCPS1234K").doesNotContain("******234K");
        // The 115BAC answer: "Yes" under the new regime (besides the heading "Yes/No").
        assertThat(text.split("Yes", -1).length - 1).isEqualTo(2);
    }

    @Test
    void SRS_O01_l3392_theSameCertificatePrintsTheSameText() {
        assertThat(text(Form16Pdf.of(document(Regime.OLD, "RELEASED"))))
                .isEqualTo(text(Form16Pdf.of(document(Regime.OLD, "RELEASED"))));
    }

    @Test
    void SRS_7_9_theReceivedPartAComesFirstThenPartB() {
        byte[] partA =
                PdfDocument.portrait("Part A", null)
                        .heading("PART A FROM TRACES")
                        .pageBreak()
                        .heading("PART A PAGE TWO")
                        .toBytes();
        byte[] partB = Form16Pdf.of(document(Regime.OLD, "RELEASED"));
        Form16Pdf.Merged merged = Form16Pdf.merge(partA, partB);
        assertThat(merged.partAIncluded()).isTrue();
        assertThat(merged.pages()).isEqualTo(pages(partA) + pages(partB));
        String text = text(merged.pdf());
        assertThat(text.indexOf("PART A FROM TRACES"))
                .isLessThan(text.indexOf("PART A PAGE TWO"))
                .isLessThan(text.indexOf("PART B (Annexure)"));
    }

    @Test
    void SRS_7_9_withoutAPdfPartAOnlyPartBIsReturned() {
        byte[] partB = Form16Pdf.of(document(Regime.OLD, "RELEASED"));
        Form16Pdf.Merged none = Form16Pdf.merge(null, partB);
        assertThat(none.partAIncluded()).isFalse();
        assertThat(none.pdf()).isSameAs(partB);
        Form16Pdf.Merged image =
                Form16Pdf.merge(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3}, partB);
        assertThat(image.partAIncluded()).isFalse();
        assertThat(Form16Pdf.isPdf(new byte[] {'%', 'P'})).isFalse();
    }
}
