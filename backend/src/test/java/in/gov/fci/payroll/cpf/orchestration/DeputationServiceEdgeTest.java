package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.model.BorrowingOrganisation;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-09 refusals before any write (plan 07 phase 07g, D-20; SRS-6-28): a borrowing organisation's
 * code, name and contact, evidence outside the reader's scope, an entry without its type.
 */
class DeputationServiceEdgeTest {

    private final CpfRepositories.BorrowingOrgs orgs = mock(CpfRepositories.BorrowingOrgs.class);
    private final CpfRepositories.DeputationEntries entries =
            mock(CpfRepositories.DeputationEntries.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final AccessContext context = mock(AccessContext.class);
    private final DeputationService service =
            new DeputationService(
                    orgs,
                    entries,
                    mock(CpfRepositories.Sanctions.class),
                    mock(CpfLedgerService.class),
                    mock(CpfMembers.class),
                    directory,
                    mock(DocumentStore.class),
                    mock(MakerChecker.class),
                    mock(Authorization.class),
                    mock(AuditService.class),
                    mock(JdbcTemplate.class),
                    JsonMapper.builder().build(),
                    Clock.systemUTC());

    private static void refused(ErrorCode code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_6_28_aBorrowingOrganisationHasACodeANameAPrintableContactAndIsNew() {
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, null, "PSU", null));
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, "psu", "PSU", null));
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, "PSU", null, null));
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, "PSU", "x".repeat(251), null));
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, "PSU", "Café", null));
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, "PSU", "PSU", "x".repeat(501)));
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, "PSU", "PSU", "tel\u0007"));
        when(orgs.findByOrgCode("PSU")).thenReturn(Optional.of(mock(BorrowingOrganisation.class)));
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, " PSU ", " PSU ", "  "));
        // A printable contact passes its own check; the code is still taken.
        refused(
                CpfErrorCode.BORROWING_ORG,
                () -> service.addOrganisation(context, "PSU", "PSU", " 011-2345 6789 "));
    }

    @Test
    void SRS_6_28_evidenceOutsideTheReadersScopeIsNotFoundAndAnEntryNeedsItsType() {
        when(entries.findById(1L)).thenReturn(Optional.empty());
        refused(CommonErrorCode.NOT_FOUND, () -> service.evidence(context, "10.0.0.1", 1L, 2L));
        CpfDeputationRecovery other = mock(CpfDeputationRecovery.class);
        when(other.getEmployeeId()).thenReturn(9L);
        when(entries.findById(3L)).thenReturn(Optional.of(other));
        when(directory.idsAt(any())).thenReturn(Set.of(8L));
        refused(CommonErrorCode.NOT_FOUND, () -> service.evidence(context, "10.0.0.1", 3L, 2L));
        refused(CommonErrorCode.VALIDATION_FAILED, () -> service.record(context, null, null, null));
        refused(
                CommonErrorCode.VALIDATION_FAILED,
                () ->
                        service.record(
                                context,
                                new DeputationService.Entry(
                                        "E1", 1L, null, null, "202604", null, null, null, null),
                                null,
                                null));
    }

    @Test
    void SRS_6_30_aReaderWithoutOfficesSeesNoEntriesAndAnEntryOfAnUnknownMemberShowsNoEis() {
        when(context.dataScope()).thenReturn(Set.of());
        assertThat(service.register(context)).isEmpty();
        when(context.dataScope()).thenReturn(Set.of(10L));
        CpfDeputationRecovery r = mock(CpfDeputationRecovery.class);
        when(r.getEmployeeId()).thenReturn(9L);
        when(r.type()).thenReturn(CpfDeputationRecovery.EntryType.CONTRIBUTION_RECEIPT);
        when(r.status()).thenReturn(CpfDeputationRecovery.Status.APPROVED);
        when(entries.inScope(Set.of(10L))).thenReturn(List.of(r));
        when(directory.members(List.of(9L))).thenReturn(Map.of());
        assertThat(service.register(context))
                .extracting(DeputationService.EntryView::eis)
                .containsExactly("");
    }

    @Test
    void SRS_6_28_theEvidenceOfAnEntryInScopeIsRead() {
        CpfDeputationRecovery r = mock(CpfDeputationRecovery.class);
        when(r.getId()).thenReturn(3L);
        when(r.getEmployeeId()).thenReturn(8L);
        when(entries.findById(3L)).thenReturn(Optional.of(r));
        when(directory.locationOf(8L)).thenReturn(Optional.of(10L));
        when(context.dataScope()).thenReturn(Set.of(10L));
        assertThatCode(() -> service.evidence(context, "10.0.0.1", 3L, 2L))
                .doesNotThrowAnyException();
    }
}
