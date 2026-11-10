package in.gov.fci.payroll.settlement.orchestration;

import static in.gov.fci.payroll.platform.export.PdfText.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-10-12, SRS-10-13, OD-144: the settlement statement PDF carries the case and the rows of the
 * on-screen statement with their amounts and recoveries; no bank account or PAN.
 */
class SettlementStatementPdfTest {

    static SettlementViews.CaseDetail detail(boolean netRecovery, String sanction) {
        SettlementViews.CaseRow row =
                new SettlementViews.CaseRow(
                        7L,
                        "FF-2026-0445",
                        "104882",
                        "Ravi Sharma",
                        "DO Delhi",
                        "SUPERANNUATION",
                        LocalDate.of(2026, 9, 30),
                        "COMPUTED",
                        List.of(),
                        "OPEN",
                        new BigDecimal(netRecovery ? "-4790.00" : "245210.00"),
                        "STAFF",
                        new BigDecimal("260000.00"),
                        new BigDecimal("40000.00"),
                        new BigDecimal("25000.00"),
                        false,
                        false);
        return new SettlementViews.CaseDetail(
                row,
                70L,
                List.of(),
                new BigDecimal("80000.00"),
                new BigDecimal("150000.00"),
                new BigDecimal("30000.00"),
                null,
                new BigDecimal("1250000.00"),
                new BigDecimal("40000.00"),
                new BigDecimal("25000.00"),
                netRecovery,
                List.of(
                        new SettlementViews.Recovery(
                                1L,
                                "LOAN",
                                "12 HBA-2024-0912",
                                "202609",
                                new BigDecimal("25000.00"),
                                "GRATUITY",
                                null,
                                null,
                                null,
                                null)),
                List.of(),
                "PENDING",
                sanction == null ? new BigDecimal("1842116.00") : null,
                sanction,
                netRecovery,
                null,
                false);
    }

    @Test
    void SRS_10_12_theStatementCarriesTheCaseAndEveryComponent() {
        String text = text(SettlementStatementPdf.of(detail(false, null)));
        assertThat(text)
                .contains("Final settlement statement - case FF-2026-0445")
                .contains("104882", "Ravi Sharma", "DO Delhi", "SUPERANNUATION", "30-09-2026")
                .contains("Pending salary + arrears", "1,10,000.00")
                .contains("Leave encashment (EL + HPL)", "1,50,000.00")
                .contains("Ex-gratia")
                .contains("Payroll dues", "2,60,000.00", "(15,000.00)")
                .contains("Payroll net payable", "2,45,210.00")
                .contains("Gratuity", "12,50,000.00", "- 25,000.00")
                .contains("CPF final payment (information only)", "18,42,116.00")
                .contains("RECOVERIES", "LOAN", "HBA-2024-0912", "202609", "25,000.00");
        assertThat(text).doesNotContain("12 HBA-2024-0912", "net recovery");
    }

    @Test
    void SRS_10_6_aNetRecoveryAndALinkedCpfSanctionAreSaid() {
        String text = text(SettlementStatementPdf.of(detail(true, "CPF/FS/2026/0042")));
        assertThat(text)
                .contains("-4,790.00", "net recovery", "Linked - CPF/FS/2026/0042")
                .contains("Benefits are held");
    }
}
