package in.gov.fci.payroll.reporting.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.reporting.orchestration.OutputWindowsService.BankRow;
import in.gov.fci.payroll.reporting.orchestration.OutputWindowsService.GlRow;
import in.gov.fci.payroll.reporting.orchestration.OutputWindowsService.InvoiceState;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * O-02 Bank Advice window (SRS-15-34, US-GAP-147, SRS l.1713): a bank without an advice reads NOT
 * GENERATED; members without a bank (the "-" row) are counted but not as a bank; the totals add up
 * every row; the GL preview and invoice states are shown as stored (an invoice never sent has no
 * time); the EBS state is the newest batch's.
 */
class OutputWindowsServiceCoverageGapsTest {

    private static final Instant SENT = Instant.parse("2026-11-05T06:00:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OutputWindowsService service =
            new OutputWindowsService(
                    db.named,
                    mock(Authorization.class),
                    Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
    private final AccessContext finance =
            new AccessContext(
                    1L,
                    "finance@x",
                    "F",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static Map<String, Object> bank(
            String name, long members, String net, long blocked, String ff, String status) {
        return row(
                "BANK",
                name,
                "MEMBERS",
                members,
                "NET",
                new BigDecimal(net),
                "BLOCKED",
                blocked,
                "FF",
                ff,
                "ST",
                status);
    }

    @Test
    void SRS_15_34_theBankSummaryGlPreviewAndInvoicesAreShownAsStored() {
        db.rows(
                "SELECT m.bank_id, COALESCE(m.bank_name, '-') bank",
                bank("SBI", 40, "1200000.00", 0, "CSV", "GENERATED"),
                bank("PNB", 10, "300000.00", 1, null, null),
                bank("-", 2, "50000.00", 2, null, null));
        db.rows(
                "FROM gl_posting_line l",
                row("H", "Basic Pay", "A", "51010", "D", new BigDecimal("900000"), "C", null),
                row("H", "Net pay", "A", "21010", "D", null, "C", new BigDecimal("900000")));
        db.rows(
                "FROM ap_invoice_staging i",
                row("S", "SENT", "N", 3L, "AT", Timestamp.from(SENT)),
                row("S", "STAGED", "N", 1L, "AT", null));
        db.rows("FROM ap_posting_batch b", row("BATCH_STATUS", "POSTED"));

        OutputWindowsService.BankOverview o = service.bank(finance, "202610");

        assertThat(o.yymm()).isEqualTo("202610");
        assertThat(o.bankRows())
                .containsExactly(
                        new BankRow("SBI", 40, new BigDecimal("1200000.00"), 0, "CSV", "GENERATED"),
                        new BankRow(
                                "PNB", 10, new BigDecimal("300000.00"), 1, null, "NOT_GENERATED"),
                        new BankRow("-", 2, new BigDecimal("50000.00"), 2, null, "NOT_GENERATED"));
        assertThat(o.net()).isEqualByComparingTo("1550000.00");
        assertThat(o.banks()).isEqualTo(2);
        assertThat(o.blocked()).isEqualTo(3);
        assertThat(o.members()).isEqualTo(52);
        assertThat(o.gl())
                .containsExactly(
                        new GlRow("Basic Pay", "51010", new BigDecimal("900000"), null),
                        new GlRow("Net pay", "21010", null, new BigDecimal("900000")));
        assertThat(o.invoices())
                .containsExactly(
                        new InvoiceState("SENT", 3, SENT), new InvoiceState("STAGED", 1, null));
        assertThat(o.ebsStatus()).isEqualTo("POSTED");
    }
}
