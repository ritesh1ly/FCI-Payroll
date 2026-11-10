package in.gov.fci.payroll.loans.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The I-04 cards as Wireframes v6.1 draws them (SRS-12-1, SRS-12-3, SRS-12-5, BR-AUDIT-009,
 * BR-AUDIT-010, SRS-G-30): the ledger's halt and reconciliation, the tiles with a month that falls
 * back to the latest instalments, the invoice status of each advance, and an order stored only with
 * a loan set up on I-04.
 */
class LoanScreenViewsTest {

    private static final Timestamp SENT = Timestamp.from(Instant.parse("2026-07-28T05:00:00Z"));

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext hq = mock(AccessContext.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final LoanQueryService loans =
            new LoanQueryService(db.named, mock(OrgDirectory.class), policy);
    private final LoanOrderService orders =
            new LoanOrderService(loans, documents, db.jdbc, policy, audit);

    LoanScreenViewsTest() {
        when(hq.locationId()).thenReturn(1L);
        when(hq.loginId()).thenReturn("maker");
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
    }

    private static Map<String, Object> loan(String source, Date separated, BigDecimal scheduled) {
        return row(
                "loan_id",
                5L,
                "loan_number",
                "LN-2026-0402",
                "emp_num",
                "880603",
                "first_name",
                "Harpal",
                "last_name",
                "Rana",
                "loan_type_code",
                "FESTIVAL_ADVANCE",
                "sanctioned_amount",
                new BigDecimal("243360"),
                "recovery_start_yymm",
                "202609",
                "num_principal_instalments",
                12,
                "instalment_amount",
                new BigDecimal("20280"),
                "paid_instalments",
                2,
                "principal_outstanding",
                new BigDecimal("202800"),
                "interest_amount",
                BigDecimal.ZERO,
                "interest_outstanding",
                BigDecimal.ZERO,
                "loan_status",
                "ACTIVE",
                "loan_source",
                source,
                "separation_date",
                separated,
                "scheduled_principal",
                scheduled);
    }

    private static DocumentStore.AttachmentView attachment(long id, String entity, String key) {
        return new DocumentStore.AttachmentView(
                id,
                entity,
                key,
                "SANCTION_ORDER",
                "Sanction Order",
                "FCI/1",
                LocalDate.of(2026, 7, 1),
                "AGM",
                "order.pdf",
                "application/pdf",
                1L,
                "x",
                "ACTIVE",
                "maker",
                Instant.parse("2026-07-01T05:00:00Z"));
    }

    private static LoanChangeService.Document order() {
        return new LoanChangeService.Document(
                "SANCTION_ORDER",
                "FCI/1",
                LocalDate.of(2026, 7, 1),
                "AGM",
                "order.pdf",
                new byte[] {1});
    }

    @Test
    void SRS_12_1_SRS_12_5_theLedgerTellsAReconciledScheduleAndASeparatedMember() {
        db.rows(
                        "AS scheduled_principal",
                        loan("LABOUR_ENT", null, new BigDecimal("243360.00")),
                        loan("HRMS", Date.valueOf("2026-07-31"), new BigDecimal("240000")),
                        loan("HRMS", null, null))
                .value("SELECT COUNT(*)", 3L);
        List<LoanQueryService.LoanRow> rows =
                loans.loans(hq, null, null, new PageSpec(0, 20)).items();
        assertThat(rows)
                .extracting(LoanQueryService.LoanRow::scheduleReconciled)
                .containsExactly(true, false, true);
        assertThat(rows)
                .extracting(LoanQueryService.LoanRow::separated)
                .containsExactly(false, true, false);
    }

    @Test
    void SRS_12_3_theTilesSumOpenLoansAndTheLatestMonthOfInstalments() {
        db.rows("AS total", row("n", 8L, "total", new BigDecimal("2942800")))
                .value("SELECT MAX(l.payroll_yymm)", "202610")
                .rows(
                        "AS principal",
                        row(
                                "principal",
                                new BigDecimal("60200"),
                                "interest",
                                new BigDecimal("6900")))
                .rows(
                        "r.staging_object",
                        row("staging_object", "INTF_PAY_LOAN_RESCHEDULE", "n", 3L),
                        row("staging_object", "INTF_PAY_LOAN_FORECLOSURE", "n", 1L));
        assertThat(loans.summary(hq, "202611"))
                .isEqualTo(
                        new LoanQueryService.Summary(
                                "202610",
                                8,
                                new BigDecimal("2942800"),
                                new BigDecimal("60200"),
                                new BigDecimal("6900"),
                                4,
                                "INTF_PAY_LOAN_RESCHEDULE"));
        assertThat(db.queries("AS principal").get(0)).contains("('ACTIVE', 'RESCHEDULED')");
    }

    @Test
    void SRS_12_3_noLoansAndNoRejectionsReadZero() {
        db.rows("AS total", row("n", 0L, "total", BigDecimal.ZERO))
                .value("SELECT MAX(l.payroll_yymm)", ScriptedJdbc.NULL)
                .rows(
                        "AS principal",
                        row("principal", BigDecimal.ZERO, "interest", BigDecimal.ZERO));
        LoanQueryService.Summary summary = loans.summary(hq, "202611");
        assertThat(summary.yymm()).isNull();
        assertThat(summary.importFailures()).isZero();
        assertThat(summary.failingInterface()).isNull();
    }

    @Test
    void BR_AUDIT_010_theInvoiceStatusIsReadFromTheLinkedInvoice() {
        assertThat(LoanQueryService.invoiceStatus(null, null, null)).isEqualTo("NOT_RAISED");
        assertThat(LoanQueryService.invoiceStatus("1", null, "NOT_REQUESTED")).isEqualTo("RAISED");
        assertThat(LoanQueryService.invoiceStatus("1", SENT, "NOT_REQUESTED"))
                .isEqualTo("SENT_TO_APPS");
        assertThat(LoanQueryService.invoiceStatus("1", SENT, "REFUSED")).isEqualTo("SENT_TO_APPS");
        assertThat(LoanQueryService.invoiceStatus("1", SENT, "REQUESTED"))
                .isEqualTo("CANCEL_REQUESTED");
        assertThat(LoanQueryService.invoiceStatus("1", SENT, "CONFIRMED"))
                .isEqualTo("CANCELLED_IN_APPS");
    }

    @Test
    void BR_AUDIT_010_aDisbursementNamesItsLoanOrItsOwnReference() {
        Map<String, Object> linked =
                row(
                        "settlement_id",
                        2L,
                        "loan_number",
                        "LN-2026-0412",
                        "advance_type",
                        "PLI_ADVANCE",
                        "loan_source",
                        "HRMS",
                        "emp_num",
                        "880606",
                        "first_name",
                        "Mohini",
                        "last_name",
                        "Vaid",
                        "loan_type_name",
                        "PLI Advance",
                        "amount",
                        new BigDecimal("480000"),
                        "invoice_number",
                        "202610700201",
                        "sent_to_apps_at",
                        SENT,
                        "apps_cancel_status",
                        "CONFIRMED");
        Map<String, Object> loose =
                row(
                        "settlement_id",
                        3L,
                        "loan_number",
                        null,
                        "advance_type",
                        "PLI_ADVANCE",
                        "loan_source",
                        null,
                        "emp_num",
                        "880607",
                        "first_name",
                        "Laxmi",
                        "last_name",
                        " ",
                        "loan_type_name",
                        null,
                        "amount",
                        new BigDecimal("10000"),
                        "invoice_number",
                        null,
                        "sent_to_apps_at",
                        null,
                        "apps_cancel_status",
                        null);
        db.rows("a.settlement_id, h.loan_number", linked, loose).value("SELECT COUNT(*)", 2L);
        List<LoanQueryService.Disbursement> rows =
                loans.disbursements(hq, new PageSpec(0, 20)).items();
        assertThat(rows.get(0).entry()).isEqualTo("LN-2026-0412");
        assertThat(rows.get(0).paymentType()).isEqualTo("PLI Advance");
        assertThat(rows.get(0).invoiceStatus()).isEqualTo("CANCELLED_IN_APPS");
        assertThat(rows.get(0).sentToApps()).isEqualTo(LocalDate.of(2026, 7, 28));
        assertThat(rows.get(0).appsCancelled()).isTrue();
        assertThat(rows.get(1).entry()).isEqualTo("ADV-3");
        assertThat(rows.get(1).employee()).isEqualTo("Laxmi");
        assertThat(rows.get(1).paymentType()).isEqualTo("PLI_ADVANCE");
        assertThat(rows.get(1).invoiceStatus()).isEqualTo("NOT_RAISED");
        assertThat(rows.get(1).sentToApps()).isNull();
        assertThat(rows.get(1).appsCancelled()).isFalse();
    }

    @Test
    void SRS_G_30_theOrdersOfALoanAreTheSanctionsAndThoseStoredWithIt() {
        db.rows("AS scheduled_principal", loan("LABOUR_ENT", null, null))
                .rows("q.entity_key", row("entity_key", "9|FESTIVAL_ADVANCE|ab"));
        DocumentStore.AttachmentView sanction =
                attachment(1L, LoanSanctionService.ENTITY, "9|FESTIVAL_ADVANCE|ab");
        DocumentStore.AttachmentView later = attachment(2L, LoanOrderService.ENTITY, "5");
        when(documents.active(LoanSanctionService.ENTITY, "9|FESTIVAL_ADVANCE|ab"))
                .thenReturn(List.of(sanction));
        when(documents.active(LoanOrderService.ENTITY, "5")).thenReturn(List.of(later));
        assertThat(orders.list(hq, 5L)).containsExactly(sanction, later);
    }

    @Test
    void SRS_G_30_aLoanFromHrmsHasNoSanctionItemHereAndAFileOfAnotherRecordIsNotFound() {
        db.rows("AS scheduled_principal", loan("HRMS", null, null));
        when(documents.active(LoanOrderService.ENTITY, "5")).thenReturn(List.of());
        assertThat(orders.list(hq, 5L)).isEmpty();
        assertThatThrownBy(() -> orders.file(hq, "1.1.1.1", 5L, 99L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void SRS_G_30_aStoredOrderIsReadFromItsOwner() {
        db.rows("AS scheduled_principal", loan("LABOUR_ENT", null, null));
        when(documents.active(LoanOrderService.ENTITY, "5"))
                .thenReturn(List.of(attachment(2L, LoanOrderService.ENTITY, "5")));
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(documents.read("maker", "1.1.1.1", LoanOrderService.ENTITY, "5", 2L))
                .thenReturn(content);
        assertThat(orders.file(hq, "1.1.1.1", 5L, 2L)).isSameAs(content);
    }

    @Test
    void SRS_G_30_anOrderIsStoredWithALoanSetUpHereAndAudited() {
        db.rows("AS scheduled_principal", loan("LABOUR_ENT", null, null));
        DocumentStore.AttachmentView stored = attachment(2L, LoanOrderService.ENTITY, "5");
        when(documents.attach(eq("maker"), any())).thenReturn(stored);
        assertThat(orders.attach(hq, 5L, order())).isSameAs(stored);
        verify(policy).require(hq, LoanSanctionService.SCREEN, "LOAN_SANCTION");
        verify(audit).record(eq("maker"), any());
    }

    @Test
    void R_SRC_anEmployeeLoanFromHrmsTakesNoOrderHere() {
        db.rows("AS scheduled_principal", loan("HRMS", null, null));
        assertThatThrownBy(() -> orders.attach(hq, 5L, order())).isInstanceOf(FciException.class);
        verify(documents, never()).attach(any(), any());
    }
}
