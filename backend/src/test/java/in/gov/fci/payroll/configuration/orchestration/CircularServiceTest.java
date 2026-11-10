package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.CircularOrder;
import in.gov.fci.payroll.configuration.business.model.CircularOrderLink;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * M-25 circulars (SRS-G-38): "where used" across links, operations and rate sets, an order copy
 * that was never attached, and an unknown circular named as not active.
 */
class CircularServiceTest {

    private final ConfigurationRepositories.Circulars circulars =
            mock(ConfigurationRepositories.Circulars.class);
    private final ConfigurationRepositories.CircularLinks links =
            mock(ConfigurationRepositories.CircularLinks.class);
    private final ConfigurationRepositories.Operations operations =
            mock(ConfigurationRepositories.Operations.class);
    private final ConfigurationRepositories.RateSets rateSets =
            mock(ConfigurationRepositories.RateSets.class);
    private final AccessContext context = mock(AccessContext.class);
    private final CircularService service =
            new CircularService(
                    circulars,
                    mock(ConfigurationRepositories.CircularScopes.class),
                    links,
                    operations,
                    rateSets,
                    mock(ReferenceData.class),
                    mock(OrgDirectory.class),
                    mock(DocumentStore.class),
                    mock(MakerChecker.class),
                    mock(Authorization.class),
                    mock(SystemParameters.class),
                    mock(AuditService.class),
                    Clock.systemUTC());

    private static void refused(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    /**
     * Wave D review 6 (DDL circular_order_link: a link is never deleted): the active links of a
     * record that did not take effect are CANCELLED with who, when and why; a link already
     * cancelled is left as it is.
     */
    @Test
    void SRS_G_38_theActiveLinksOfARecordThatDidNotTakeEffectAreCancelled() {
        CircularOrderLink active =
                CircularOrderLink.link(
                        4L,
                        CircularOrderLink.Type.MASTER_ENTRY,
                        "allowance_ceiling_norm",
                        "44",
                        "M-15",
                        "maker",
                        java.time.Instant.parse("2026-11-01T05:00:00Z"));
        CircularOrderLink cancelled =
                CircularOrderLink.link(
                        5L,
                        CircularOrderLink.Type.MASTER_ENTRY,
                        "allowance_ceiling_norm",
                        "44",
                        "M-15",
                        "maker",
                        java.time.Instant.parse("2026-10-01T05:00:00Z"));
        cancelled.cancel("earlier", "before", java.time.Instant.parse("2026-10-02T05:00:00Z"));
        when(links.findByLinkedEntityAndLinkedKeyOrderByCircularLinkId(
                        "allowance_ceiling_norm", "44"))
                .thenReturn(java.util.List.of(cancelled, active));
        assertThat(
                        service.cancelLinks(
                                "checker", "allowance_ceiling_norm", "44", "M-15", "rejected"))
                .isEqualTo(1);
        assertThat(active.getLinkStatus()).isEqualTo("CANCELLED");
        assertThat(active.getCancelledBy()).isEqualTo("checker");
        assertThat(active.getCancelReason()).isEqualTo("rejected");
        assertThat(active.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancelledBy()).isEqualTo("earlier");
        org.mockito.Mockito.verify(links).save(active);
        org.mockito.Mockito.verify(links, org.mockito.Mockito.never()).save(cancelled);
        assertThat(service.cancelLinks("checker", "allowance_ceiling_norm", "45", "M-15", "x"))
                .isZero();
    }

    @Test
    void SRS_G_38_whereUsedListsEachLinkOnceWithTheOperationsAndRateSetsItGoverns() {
        when(circulars.findById(4L)).thenReturn(Optional.of(mock(CircularOrder.class)));
        CircularOrderLink link = mock(CircularOrderLink.class);
        when(link.getLinkType()).thenReturn("MASTER_ENTRY");
        when(link.getLinkedEntity()).thenReturn(OperationService.ENTITY);
        when(link.getLinkedKey()).thenReturn("OP-1");
        when(links.findByCircularOrderIdOrderByLinkedAtDesc(4L)).thenReturn(List.of(link));
        OperationMaster linked = mock(OperationMaster.class);
        when(linked.getOperationCode()).thenReturn("OP-1");
        OperationMaster unlinked = mock(OperationMaster.class);
        when(unlinked.getOperationCode()).thenReturn("OP-2");
        when(operations.findByCircularOrderIdOrderByOperationCodeAscVersionNoDesc(4L))
                .thenReturn(List.of(linked, unlinked));
        RateSet rates = mock(RateSet.class);
        when(rates.getRateSetCode()).thenReturn("HRA");
        when(rates.getVersionNo()).thenReturn(2);
        when(rates.getApprovalState()).thenReturn("APPROVED");
        when(rateSets.findByCircularOrderIdOrderByRateSetCodeAscVersionNoDesc(4L))
                .thenReturn(List.of(rates));
        assertThat(service.whereUsed(context, 4L))
                .extracting(CircularService.UseView::key)
                .containsExactly("OP-1", "OP-2", "HRA v2");
        refused(CommonErrorCode.NOT_FOUND, () -> service.whereUsed(context, null));
    }

    @Test
    void SRS_G_38_anOrderCopyNeverAttachedIsNotFoundAndAnUnknownCircularIsNotActive() {
        CircularOrder unattached = mock(CircularOrder.class);
        // A mocked Long getter answers 0, not null: the copy that was never attached is null.
        when(unattached.getDocumentAttachmentId()).thenReturn(null);
        when(circulars.findById(4L)).thenReturn(Optional.of(unattached));
        refused(CommonErrorCode.NOT_FOUND, () -> service.document(context, 4L, "10.0.0.1"));
        // Wave D review 6: no circular at all is a required field, with or without a category.
        refused(ApiErrorCode.CIRCULAR_REQUIRED, () -> service.requireActive((Long) null));
        refused(
                ApiErrorCode.CIRCULAR_REQUIRED,
                () -> service.requireActive((Long) null, "WAGE_REVISION"));
        when(circulars.findById(5L)).thenReturn(Optional.empty());
        refused(ApiErrorCode.CIRCULAR_NOT_ACTIVE, () -> service.requireActive(5L));
    }

    /** I-25 field table (SRS l.3164): an Active circular of the category asked, else refused. */
    @Test
    void SRS_8_25_anOrderOfAnotherCategoryOrNotActiveIsRefusedWhereACategoryIsAsked() {
        CircularOrder wr = mock(CircularOrder.class);
        when(wr.isActive()).thenReturn(true);
        when(wr.getCategoryCode()).thenReturn("WAGE_REVISION");
        when(circulars.findById(6L)).thenReturn(Optional.of(wr));
        service.requireActive(6L, "WAGE_REVISION");
        refused(ApiErrorCode.CIRCULAR_WRONG_CATEGORY, () -> service.requireActive(6L, "DA"));
        CircularOrder uncategorised = mock(CircularOrder.class);
        when(uncategorised.isActive()).thenReturn(true);
        when(uncategorised.getCategoryCode()).thenReturn(null);
        when(circulars.findById(7L)).thenReturn(Optional.of(uncategorised));
        refused(
                ApiErrorCode.CIRCULAR_WRONG_CATEGORY,
                () -> service.requireActive(7L, "WAGE_REVISION"));
        CircularOrder withdrawn = mock(CircularOrder.class);
        when(withdrawn.isActive()).thenReturn(false);
        when(withdrawn.getCategoryCode()).thenReturn("WAGE_REVISION");
        when(circulars.findById(8L)).thenReturn(Optional.of(withdrawn));
        refused(ApiErrorCode.CIRCULAR_NOT_ACTIVE, () -> service.requireActive(8L, "WAGE_REVISION"));
        refused(ApiErrorCode.CIRCULAR_REQUIRED, () -> service.requireActive(null, "WAGE_REVISION"));
    }
}
