package in.gov.fci.payroll.reporting.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * O-06 Invoice Outputs read side (Wireframes v6.1, OD-121; FRS Annexure A.4, BR-AUDIT-010): reads
 * only, under O-06 VIEW; the GL gap is the invoices' total against the GL posting statements.
 */
class InvoiceOutputsServiceTest {

    private static final PageSpec SPEC = new PageSpec(0, 20);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final InvoiceOutputsService service = new InvoiceOutputsService(db.named, policy);
    private final AccessContext user =
            new AccessContext(
                    3L,
                    "maker@x",
                    "User",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("PAYROLL_MAKER"),
                    Set.of(300L));

    @Test
    void SRS_G_2_aRefusedViewReadsNothing() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(user, "O-06", "VIEW");
        assertThatThrownBy(() -> service.overview(user, null, null))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.register(user, "202601", null, null, SPEC))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.lines(user, "202601", null, SPEC))
                .isInstanceOf(FciException.class);
        assertThat(db.queries("")).isEmpty();
    }

    @Test
    void FRS_A4_withoutInvoicesThereIsNoPeriodToShow() {
        var o = service.overview(user, null, null);
        assertThat(o.period()).isNull();
        assertThat(o.count()).isZero();
        assertThat(o.locked()).isFalse();
    }

    @Test
    void FRS_A4_theGlGapIsTheInvoiceTotalAgainstThePostingStatement() {
        db.rows("SELECT DISTINCT i.payroll_yymm", row("PAYROLL_YYMM", "202601"));
        db.value("SELECT SUM(i.invoice_amount)", new BigDecimal("1000"));
        db.value("SELECT SUM(s.total_debit)", new BigDecimal("940"));
        var o = service.overview(user, "202601", null);
        assertThat(o.value()).isEqualByComparingTo("1000");
        assertThat(o.glGap()).isEqualByComparingTo("60");
        assertThat(o.locked()).isTrue();
    }

    @Test
    void FRS_A4_anUnlockedCycleIsNotALockedPeriod() {
        db.value("FROM payroll_cycle_instance c", 1L);
        assertThat(service.overview(user, "202601", null).locked()).isFalse();
    }

    @Test
    void FRS_A4_anOutputWithNothingStagedStillStandsInTheRegister() {
        db.value("SELECT COUNT(*) FROM report_definition d", 1L);
        db.rows(
                "SELECT d.report_key",
                row(
                        "REPORT_KEY",
                        "INCENTIVE_INVOICE",
                        "REPORT_NAME",
                        "Incentive Invoice",
                        "CATALOGUE_SECTION",
                        "A.4.3",
                        "BLOCKED_REASON",
                        "Waits for C-06",
                        "CNT",
                        0L,
                        "VAL",
                        null,
                        "HELD",
                        0L,
                        "RELEASED",
                        0L));
        var r = service.register(user, "202601", null, null, SPEC).items().get(0);
        assertThat(r.value()).isEqualByComparingTo("0");
        assertThat(r.blockedReason()).isEqualTo("Waits for C-06");
    }

    @Test
    void BR_AUDIT_010_aLineCarriesItsStatusAndSentDate() {
        Instant sent = Instant.parse("2026-02-01T05:00:00Z");
        db.value("SELECT COUNT(*) FROM ap_invoice_staging", 2L);
        db.rows(
                "SELECT i.invoice_staging_id",
                row(
                        "INVOICE_STAGING_ID",
                        1L,
                        "INVOICE_NUMBER",
                        "202601000001",
                        "PAYMENT_TYPE",
                        "Salary",
                        "INVOICE_AMOUNT",
                        new BigDecimal("10"),
                        "POSTING_STATUS",
                        "SENT",
                        "SENT_TO_APPS_AT",
                        Timestamp.from(sent),
                        "APPS_CANCEL_STATUS",
                        "NOT_REQUESTED",
                        "UNIT_LABEL",
                        "DH001 - Delhi HQ"),
                row(
                        "INVOICE_STAGING_ID",
                        2L,
                        "INVOICE_NUMBER",
                        "202601000002",
                        "PAYMENT_TYPE",
                        "Salary",
                        "INVOICE_AMOUNT",
                        new BigDecimal("20"),
                        "POSTING_STATUS",
                        "HELD",
                        "SENT_TO_APPS_AT",
                        null,
                        "APPS_CANCEL_STATUS",
                        "NOT_REQUESTED",
                        "UNIT_LABEL",
                        "DH001 - Delhi HQ"));
        var page = service.lines(user, "202601", null, SPEC);
        assertThat(page.items().get(0).sentToApps()).isEqualTo(sent);
        assertThat(page.items().get(1).sentToApps()).isNull();
    }
}
