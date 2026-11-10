package in.gov.fci.payroll.settlement.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The reads of O-05 as drawn (pack SCREENS.o5; SRS-10-6, SRS-10-8, SRS-10-11, SRS-10-13, SRS-10-22,
 * SRS-G-37): the worklist columns, the tiles and the settlement payments.
 */
class SettlementViewsPageTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final SettlementViews views =
            new SettlementViews(
                    db.named,
                    policy,
                    statuses,
                    Clock.fixed(Instant.parse("2026-11-07T20:00:00Z"), ZoneId.of("UTC")));

    private static Map<String, Object> caseRow(long id, String reference, String type) {
        return row(
                "settlement_case_id",
                id,
                "case_reference",
                reference,
                "emp_num",
                "E" + id,
                "first_name",
                "Asha",
                "last_name",
                "Rao",
                "location_code",
                "DH001",
                "location_name",
                "Delhi",
                "separation_type",
                "RETIREMENT",
                "separation_date",
                Date.valueOf(LocalDate.of(2026, 9, 30)),
                "case_stage",
                4,
                "settlement_status",
                "COMPUTED",
                "net_payable",
                new BigDecimal("-4790"),
                "payroll_type",
                type,
                "total_recoveries",
                new BigDecimal("539800"),
                "recovered_from_gratuity",
                new BigDecimal("288400"),
                "payroll_dues",
                new BigDecimal("246610"));
    }

    @Test
    void SRS_10_8_eachRowCarriesItsPayrollDuesRecoveriesAndWhoHoldsIt() {
        db.rows(
                "ORDER BY c.settlement_case_id DESC OFFSET",
                caseRow(1L, "FF-1", "DEPT_LABOUR"),
                caseRow(2L, "FF-2", "STAFF"));
        db.rows(
                "SELECT r.hq_approval_id, s.settlement_case_id",
                row("hq_approval_id", 70L, "settlement_case_id", 1L),
                row("hq_approval_id", 71L, "settlement_case_id", 2L));
        when(statuses.of(any()))
                .thenReturn(
                        Map.of(70L, ApprovalStatus.PENDING_APPROVAL, 71L, ApprovalStatus.APPROVED));
        when(statuses.pending(SettlementWork.ENTITY))
                .thenReturn(
                        List.of(
                                new ApprovalStatuses.Pending(
                                        9L, "FF-2", "{}", "m", Instant.EPOCH)));
        List<SettlementViews.CaseRow> rows =
                views.cases(context, new SettlementViews.Filter(null, null), new PageSpec(0, 20))
                        .items();
        assertThat(rows.get(0).payrollType()).isEqualTo("DEPT_LABOUR");
        assertThat(rows.get(0).payrollDues()).isEqualByComparingTo("246610");
        assertThat(rows.get(0).totalRecoveries()).isEqualByComparingTo("539800");
        assertThat(rows.get(0).recoveredFromGratuity()).isEqualByComparingTo("288400");
        assertThat(rows.get(0).hqApprovalPending()).as("SRS-10-22").isTrue();
        assertThat(rows.get(0).awaitingChecker()).isFalse();
        assertThat(rows.get(1).hqApprovalPending()).as("approved: no longer held").isFalse();
        assertThat(rows.get(1).awaitingChecker()).as("the Checker's item").isTrue();
    }

    @Test
    void SRS_10_22_aPageWithNoGratuityRecoveryAsksNoStatus() {
        db.rows("ORDER BY c.settlement_case_id DESC OFFSET", caseRow(1L, "FF-1", "STAFF"));
        assertThat(
                        views.cases(
                                        context,
                                        new SettlementViews.Filter(null, null),
                                        new PageSpec(0, 20))
                                .items())
                .hasSize(1);
        verify(statuses, never()).of(any());
    }

    @Test
    void SRS_10_11_anEmptyPageReadsNoPendingItem() {
        assertThat(
                        views.cases(
                                        context,
                                        new SettlementViews.Filter(null, null),
                                        new PageSpec(0, 20))
                                .items())
                .isEmpty();
        verify(statuses, never()).pending(SettlementWork.ENTITY);
    }

    /** O-05 tiles: by type, settled with its amount, held by clearance, HQ and net recovery. */
    @Test
    void SRS_10_11_SRS_10_22_SRS_10_6_theTilesCountTheOfficesCases() {
        db.rows(
                "GROUP BY c.separation_type",
                row("t", "DEATH", "c", 1L, "s", 0L, "a", BigDecimal.ZERO),
                row("t", "RETIREMENT", "c", 3L, "s", 2L, "a", new BigDecimal("771420")));
        db.value("COUNT(DISTINCT c.settlement_case_id)", 4L);
        db.rows("SELECT r.hq_approval_id FROM", row("hq_approval_id", 70L), row("id", 71L));
        db.value("s.net_payable < 0", 2L);
        when(statuses.of(any()))
                .thenReturn(
                        Map.of(70L, ApprovalStatus.PENDING_APPROVAL, 71L, ApprovalStatus.APPROVED));
        SettlementViews.Summary s = views.summary(context);
        assertThat(s.separations()).isEqualTo(4);
        assertThat(s.settled()).isEqualTo(2);
        assertThat(s.settledAmount()).isEqualByComparingTo("771420");
        assertThat(s.byType()).extracting(SettlementViews.TypeCount::type).contains("DEATH");
        assertThat(s.awaitingClearance()).isEqualTo(4);
        assertThat(s.hqApproval()).as("only the one still pending").isEqualTo(1);
        assertThat(s.netRecovery()).isEqualTo(2);
        verify(policy).require(context, SettlementCaseOpener.SCREEN, "VIEW");
    }

    @Test
    void SRS_10_11_noCaseCountsZeroAndAsksNoStatus() {
        SettlementViews.Summary s = views.summary(context);
        assertThat(s.separations()).isZero();
        assertThat(s.settledAmount()).isEqualByComparingTo("0");
        assertThat(s.hqApproval()).isZero();
        verify(statuses, never()).of(any());
    }

    /** SRS-G-37, BR-FNF-016: a negative net is the credit memo, a positive the net settlement. */
    @Test
    void SRS_G_37_eachPaymentShowsItsInvoiceAndACreditMemoItsOwnNumber() {
        db.value("SELECT COUNT(*)", 2L);
        db.rows(
                "s.ap_invoice_id",
                row(
                        "settlement_case_id",
                        1L,
                        "case_reference",
                        "FF-1",
                        "emp_num",
                        "E1",
                        "first_name",
                        "Asha",
                        "last_name",
                        null,
                        "net_payable",
                        new BigDecimal("771420"),
                        "ap_invoice_id",
                        202610000877L,
                        "credit_memo_invoice_id",
                        null,
                        "invoice_status",
                        "SENT_TO_APPS",
                        "invoice_sent_at",
                        Timestamp.from(Instant.parse("2026-10-24T20:00:00Z")),
                        "apps_cancel_status",
                        "CONFIRMED",
                        "settlement_status",
                        "PAID"),
                row(
                        "settlement_case_id",
                        2L,
                        "case_reference",
                        "FF-2",
                        "emp_num",
                        "E2",
                        "first_name",
                        null,
                        "last_name",
                        "Rao",
                        "net_payable",
                        new BigDecimal("-4790"),
                        "ap_invoice_id",
                        5L,
                        "credit_memo_invoice_id",
                        900L,
                        "invoice_status",
                        "NOT_RAISED",
                        "invoice_sent_at",
                        null,
                        "apps_cancel_status",
                        "NOT_REQUESTED",
                        "settlement_status",
                        "APPROVED"));
        List<SettlementViews.InvoiceRow> rows =
                views.invoices(context, new PageSpec(0, 20)).items();
        assertThat(rows.get(0).creditMemo()).isFalse();
        assertThat(rows.get(0).invoiceNo()).isEqualTo("202610000877");
        assertThat(rows.get(0).sentToApps())
                .as("the Indian date of the sending")
                .isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(rows.get(0).appsCancelled()).isTrue();
        assertThat(rows.get(1).creditMemo()).isTrue();
        assertThat(rows.get(1).invoiceNo()).isEqualTo("900");
        assertThat(rows.get(1).sentToApps()).isNull();
        assertThat(rows.get(1).appsCancelled()).isFalse();
    }

    @Test
    void SRS_G_31_aCaseOutOfTheSessionsOfficesHasNoReference() {
        assertThat(
                        ((FciException) catchThrowable(() -> views.reference(context, 5L)))
                                .getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_G_31_aCaseOfTheOfficesAnswersItsReference() {
        db.rows("SELECT c.case_reference", row("case_reference", "FF-1"));
        assertThat(views.reference(context, 5L)).isEqualTo("FF-1");
    }
}
