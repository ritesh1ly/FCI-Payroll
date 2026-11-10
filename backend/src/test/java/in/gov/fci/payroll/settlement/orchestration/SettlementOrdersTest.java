package in.gov.fci.payroll.settlement.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.settlement.business.SettlementErrorCode;
import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/**
 * O-05 order copies (SRS-G-30, SRS-G-31, SRS-G-32 O-05; SRS-10-29; PENDING API-11-2): read within
 * the session's offices, attached by the Maker of the member's office, and never once the case is
 * posted.
 */
class SettlementOrdersTest {

    private final Authorization policy = mock(Authorization.class);
    private final SettlementWork work = mock(SettlementWork.class);
    private final SettlementViews views = mock(SettlementViews.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final SettlementOrders service = new SettlementOrders(policy, work, views, documents);
    private final AccessContext context = mock(AccessContext.class);

    private void caseAt(Stage stage) {
        when(work.load(5L))
                .thenReturn(
                        new SettlementWork.Case(
                                5L,
                                "FF-1",
                                7L,
                                10L,
                                LocalDate.of(2026, 9, 30),
                                stage,
                                9L,
                                "COMPUTED",
                                "SEPARATED"));
        when(context.loginId()).thenReturn("maker@x");
    }

    private void attach() {
        service.attach(
                context,
                5L,
                "SANCTION_ORDER",
                "FCI/1",
                LocalDate.of(2026, 10, 1),
                "RO Delhi",
                "letter.pdf",
                new byte[] {1});
    }

    private ErrorCode refusal(Runnable call) {
        return ((FciException) catchThrowable(call::run)).getErrorCode();
    }

    @Test
    void SRS_G_31_theOrdersOfACaseAreReadByItsReferenceInTheSessionsOffices() {
        when(views.reference(context, 5L)).thenReturn("FF-1");
        when(documents.active("settlement_case", "FF-1")).thenReturn(List.of());
        assertThat(service.orders(context, 5L)).isEmpty();
        verify(documents).active("settlement_case", "FF-1");
    }

    /** SRS-G-31: a case out of scope answers not found, and no order is read. */
    @Test
    void SRS_G_31_aCaseOutOfScopeShowsNoOrderAndGivesNoFile() {
        when(views.reference(context, 5L)).thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(refusal(() -> service.orders(context, 5L))).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(refusal(() -> service.file(context, "1.1.1.1", 5L, 40L)))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        verify(documents, never()).read(any(), any(), any(), any(), any());
    }

    @Test
    void SRS_G_31_aFileIsReadAsAnAttachmentOfThatCase() {
        when(views.reference(context, 5L)).thenReturn("FF-1");
        when(context.loginId()).thenReturn("maker@x");
        service.file(context, "1.1.1.1", 5L, 40L);
        verify(documents).read("maker@x", "1.1.1.1", "settlement_case", "FF-1", 40L);
    }

    @Test
    void SRS_G_30_theMakerStoresTheOrderWithItsParticularsAgainstTheCase() {
        caseAt(Stage.COMPUTED);
        attach();
        verify(policy).requireEmployeeRecord(context, "O-05", "COMPUTE", 7L, 10L);
        ArgumentCaptor<DocumentStore.NewAttachment> stored =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(documents).attach(eq("maker@x"), stored.capture());
        assertThat(stored.getValue().entityName()).isEqualTo("settlement_case");
        assertThat(stored.getValue().entityKey()).isEqualTo("FF-1");
        assertThat(stored.getValue().documentTypeCode()).isEqualTo("SANCTION_ORDER");
        assertThat(stored.getValue().referenceNumber()).isEqualTo("FCI/1");
        assertThat(stored.getValue().issuingAuthority()).isEqualTo("RO Delhi");
        assertThat(stored.getValue().containsPii()).isTrue();
    }

    /** R-RBAC data scope: a Maker of another office is refused before anything is stored. */
    @Test
    void SRS_G_30_aMakerOfAnotherOfficeStoresNothing() {
        caseAt(Stage.COMPUTED);
        doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .requireEmployeeRecord(context, "O-05", "COMPUTE", 7L, 10L);
        assertThat(refusal(this::attach)).isEqualTo(ApiErrorCode.PERMISSION_DENIED);
        verify(documents, never()).attach(any(), any());
    }

    /** SRS-10-29: on posting the records are read-only. */
    @ParameterizedTest
    @EnumSource(
            value = Stage.class,
            names = {"POSTED", "CLOSED"})
    void SRS_10_29_aPostedOrClosedCaseTakesNoOrder(Stage stage) {
        caseAt(stage);
        assertThat(refusal(this::attach)).isEqualTo(SettlementErrorCode.STAGE);
        verify(documents, never()).attach(any(), any());
    }

    @Test
    void SRS_10_29_anApprovedCaseStillTakesItsSuccessionDocument() {
        caseAt(Stage.APPROVED);
        attach();
        verify(documents).attach(eq("maker@x"), any());
    }
}
