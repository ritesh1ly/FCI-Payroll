package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.platform.export.PdfText.pages;
import static in.gov.fci.payroll.platform.export.PdfText.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * OD-144, SRS-1-10, SRS-17-7, SRS-18-23, SRS O-01 l.3392: the payslip PDF carries the stored
 * figures of the month, masked as the screens show them, marks a provisional month, and gives the
 * same text every time it is generated for the same closed month.
 */
class PayslipPdfTest {

    static PayslipService.Payslip slip(boolean provisional) {
        return new PayslipService.Payslip(
                "202609",
                "104882",
                "Ravi Sharma",
                "Manager",
                "Regional Office Delhi",
                "IDA",
                "******234K",
                "********4412",
                "110000000001",
                List.of(
                        new PayslipService.Line(
                                101, "Basic Pay", "BASIC", new BigDecimal("62400.00")),
                        new PayslipService.Line(
                                102, "Dearness Allowance", "DA", new BigDecimal("31200.00"))),
                List.of(
                        new PayslipService.Line(
                                301, "CPF (Employee)", "CPF", new BigDecimal("11232.00"))),
                new BigDecimal("93600.00"),
                new BigDecimal("11232.00"),
                new BigDecimal("82368.00"),
                "STAFF",
                provisional);
    }

    @Test
    void SRS_17_7_thePayslipCarriesItsKeyFieldsAndAmounts() {
        String text = text(PayslipPdf.of(slip(false)));
        assertThat(text)
                .contains("FOOD CORPORATION OF INDIA")
                .contains("Pay Slip for the month of SEPTEMBER 2026")
                .contains("Regional Office Delhi")
                .contains("104882", "Ravi Sharma", "Manager", "IDA", "110000000001")
                .contains("Basic Pay", "62,400.00", "Dearness Allowance", "31,200.00")
                .contains("CPF (Employee)", "11,232.00")
                .contains("Gross Earnings", "93,600.00", "Total Deductions")
                .contains("NET PAY: Rs. 82,368.00")
                .contains("Rupees Eighty Two Thousand Three Hundred and Sixty Eight only");
        assertThat(text).doesNotContain("PROVISIONAL");
    }

    @Test
    void R_PII_panAndBankAccountArePrintedMaskedAsTheScreensShowThem() {
        String text = text(PayslipPdf.of(slip(false)));
        assertThat(text).contains("******234K", "********4412");
        assertThat(text).doesNotContainPattern("[A-Z]{5}[0-9]{4}[A-Z]");
    }

    @Test
    void SRS_18_23_aMonthBeforeTheLockIsMarkedProvisional() {
        assertThat(text(PayslipPdf.of(slip(true))))
                .contains("(PROVISIONAL)")
                .contains("not yet locked");
    }

    @Test
    void SRS_O01_l3392_twoGenerationsOfTheSameClosedMonthGiveTheSameText() {
        assertThat(text(PayslipPdf.of(slip(false)))).isEqualTo(text(PayslipPdf.of(slip(false))));
    }

    @Test
    void OD_144_aBulkFileHasOnePagePerPayslip() {
        byte[] bulk = PayslipPdf.of(List.of(slip(false), slip(true), slip(false)));
        assertThat(pages(bulk)).isEqualTo(3);
        assertThat(PayslipPdf.month("202603")).isEqualTo("MARCH 2026");
    }

    @Test
    void SRS_17_7_aLabourPayslipSaysItsLayoutAndUnevenColumnsLineUp() {
        PayslipService.Payslip s = slip(false);
        PayslipService.Payslip labour =
                new PayslipService.Payslip(
                        s.yymm(),
                        s.eis(),
                        s.name(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        s.deductions(),
                        s.earnings(),
                        s.gross(),
                        s.totalDeductions(),
                        s.net(),
                        "LABOUR",
                        false);
        assertThat(text(PayslipPdf.of(labour))).contains("Labour", "Basic Pay", "CPF (Employee)");
    }

    private static PayslipService.Payslip custom(
            String name,
            String designation,
            List<PayslipService.Line> earnings,
            List<PayslipService.Line> deductions,
            String net) {
        PayslipService.Payslip s = slip(false);
        return new PayslipService.Payslip(
                s.yymm(),
                s.eis(),
                name,
                designation,
                null,
                null,
                null,
                null,
                null,
                earnings,
                deductions,
                s.gross(),
                s.totalDeductions(),
                new BigDecimal(net),
                "STAFF",
                false);
    }

    private static PayslipService.Line line(int code, String name, String amount) {
        return new PayslipService.Line(code, name, "X", new BigDecimal(amount));
    }

    @Test
    void SRS_1_8_aZeroOrNegativeNetIsPrintedAsComputed() {
        assertThat(text(PayslipPdf.of(custom("A", "B", List.of(), List.of(), "0.00"))))
                .contains("NET PAY: Rs. 0.00", "Rupees Zero only");
        assertThat(text(PayslipPdf.of(custom("A", "B", List.of(), List.of(), "-1250.50"))))
                .contains(
                        "NET PAY: Rs. -1,250.50",
                        "Minus Rupees One Thousand Two Hundred and Fifty and Fifty Paise only");
    }

    @Test
    void SRS_1_10_arrearSupplementaryAndOffCycleLinesArePrintedByTheirOwnNames() {
        String text =
                text(
                        PayslipPdf.of(
                                custom(
                                        "A",
                                        "B",
                                        List.of(
                                                line(101, "Basic Pay", "62400"),
                                                line(611, "DA Arrear (Jan-Jun 2026)", "18720"),
                                                line(
                                                        711,
                                                        "Supplementary - Leave Encashment",
                                                        "40000")),
                                        List.of(line(904, "Arrear Recovery - Overpayment", "1200")),
                                        "119920")));
        assertThat(text)
                .contains("DA Arrear (Jan-Jun 2026)", "18,720.00")
                .contains("Supplementary - Leave Encashment", "40,000.00")
                .contains("Arrear Recovery - Overpayment", "1,200.00");
    }

    @Test
    void OD_144_manyLinesAndAVeryLongNameRunOnToFurtherPagesWithNothingLost() {
        List<PayslipService.Line> earnings = new java.util.ArrayList<>();
        for (int i = 0; i < 90; i++) {
            earnings.add(line(100 + i, "Earning component number " + i, "100"));
        }
        String longName = "Venkata Subrahmanya " + "Lakshminarasimha ".repeat(8) + "Iyer";
        byte[] pdf =
                PayslipPdf.of(
                        custom(
                                longName,
                                "Assistant General Manager (General)",
                                earnings,
                                List.of(),
                                "9000"));
        assertThat(pages(pdf)).isGreaterThan(1);
        String text = text(pdf);
        assertThat(text).contains("Earning component number 0", "Earning component number 89");
        assertThat(text).contains("Iyer").contains("Venkata Subrahmanya");
        assertThat(text).contains("Page 1 of " + pages(pdf));
    }

    @Test
    void OD_144_missingOptionalFieldsPrintADash() {
        String text = text(PayslipPdf.of(custom("A. Kumar", null, List.of(), List.of(), "10")));
        assertThat(text).contains("Designation -", "PAN -", "Bank A/c -", "PRAN -");
    }

    @Test
    void LIB_1_HI_aHindiNameIsMarkedNotPrintedAsQuestionMarks() {
        String text = text(PayslipPdf.of(custom("रवि शर्मा", "B", List.of(), List.of(), "10")));
        assertThat(text).contains("[Hindi]").doesNotContain("??");
    }

    @Test
    void R_PII_theFileMetadataCarriesNoNameOrNumber() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d =
                org.apache.pdfbox.Loader.loadPDF(PayslipPdf.of(slip(false)))) {
            org.apache.pdfbox.pdmodel.PDDocumentInformation info = d.getDocumentInformation();
            assertThat(info.getTitle()).isEqualTo("Pay Slip 202609");
            assertThat(info.getAuthor()).isNull();
            assertThat(info.getSubject()).isNull();
            assertThat(info.getKeywords()).isNull();
            assertThat(info.getCreationDate()).isNull();
            assertThat(String.valueOf(info.getCOSObject()))
                    .doesNotContain("Ravi", "104882", "234K");
        }
    }

    @Test
    void SRS_O01_l3392_theSameMonthGivesTheSameBytesAndAnotherMemberOthers() {
        assertThat(PayslipPdf.of(slip(false))).isEqualTo(PayslipPdf.of(slip(false)));
        PayslipService.Payslip s = slip(false);
        PayslipService.Payslip other =
                new PayslipService.Payslip(
                        s.yymm(),
                        "104883",
                        s.name(),
                        s.designation(),
                        s.office(),
                        s.payPattern(),
                        s.panMasked(),
                        s.bankAccountMasked(),
                        s.pran(),
                        s.earnings(),
                        s.deductions(),
                        s.gross(),
                        s.totalDeductions(),
                        s.net(),
                        s.layoutVariant(),
                        false);
        assertThat(PayslipPdf.of(other)).isNotEqualTo(PayslipPdf.of(s));
    }
}
