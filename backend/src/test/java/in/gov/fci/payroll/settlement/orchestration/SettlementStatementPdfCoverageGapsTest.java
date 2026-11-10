package in.gov.fci.payroll.settlement.orchestration;

import static in.gov.fci.payroll.platform.export.PdfText.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-10-12, OD-144: a statement of a case with figures not yet computed shows dashes, never an
 * assumed zero line: no separation date, no pending salary or arrears, no recovery (from payroll or
 * gratuity), a recovery without a reference; one of pending salary and arrears is shown alone.
 */
class SettlementStatementPdfCoverageGapsTest {

    private static SettlementViews.CaseDetail detail(
            BigDecimal pendingSalary,
            BigDecimal arrears,
            List<SettlementViews.Recovery> recoveries) {
        SettlementViews.CaseRow row =
                new SettlementViews.CaseRow(
                        8L,
                        "FF-2026-0446",
                        "104883",
                        "Asha Devi",
                        "DO Agra",
                        "RESIGNATION",
                        null,
                        "OPEN",
                        List.of(),
                        "OPEN",
                        null,
                        "STAFF",
                        null,
                        null,
                        null,
                        false,
                        false);
        return new SettlementViews.CaseDetail(
                row,
                71L,
                List.of(),
                pendingSalary,
                null,
                arrears,
                null,
                null,
                null,
                null,
                false,
                recoveries,
                List.of(),
                "PENDING",
                null,
                null,
                false,
                null,
                false);
    }

    @Test
    void SRS_10_12_aCaseNotYetComputedShowsDashesAndNoRecoveryTable() {
        String text = text(SettlementStatementPdf.of(detail(null, null, List.of())));

        assertThat(text)
                .contains("Final settlement statement - case FF-2026-0446")
                .contains("Separation date", "Pending salary + arrears", "Payroll dues", "Gratuity")
                .doesNotContain("RECOVERIES", "net recovery", "Benefits are held", "(0", "(-")
                .doesNotContain("0.00");
    }

    @Test
    void SRS_10_12_onePartOfThePendingSalaryIsShownAloneAndAMissingReferenceIsBlank() {
        String text =
                text(
                        SettlementStatementPdf.of(
                                detail(
                                        null,
                                        new BigDecimal("1200.00"),
                                        List.of(
                                                new SettlementViews.Recovery(
                                                        2L,
                                                        "ADVANCE",
                                                        null,
                                                        "202610",
                                                        new BigDecimal("500.00"),
                                                        "PAYROLL",
                                                        null,
                                                        null,
                                                        null,
                                                        null)))));

        assertThat(text)
                .contains("1,200.00")
                .contains("RECOVERIES", "ADVANCE", "202610", "500.00", "PAYROLL")
                .doesNotContain("null");
        assertThat(
                        text(
                                SettlementStatementPdf.of(
                                        detail(new BigDecimal("3000.00"), null, List.of()))))
                .contains("3,000.00");
    }
}
