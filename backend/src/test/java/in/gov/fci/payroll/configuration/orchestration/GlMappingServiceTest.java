package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.GlAccountMapping;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.orchestration.GlMappingService.ComponentRow;
import in.gov.fci.payroll.configuration.orchestration.GlMappingService.MappingVersion;
import in.gov.fci.payroll.configuration.orchestration.GlMappingService.ProposedMapping;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M-24 Accounting &amp; Bank Mapping (SRS-G-7; DDL {@code gl_account_mapping}, S23): Finance
 * proposes the natural account of a pay code from a date, the Payroll Checker approves. The checks
 * before anything is submitted, the version numbering, and which versions the screen shows.
 */
class GlMappingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final long HQ = 1L;
    private static final int PAY_CODE = 131;
    private static final long COMPONENT = 5L;

    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);
    private final ConfigurationRepositories.GlMappings mappings =
            mock(ConfigurationRepositories.GlMappings.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters settings = mock(SystemParameters.class);

    private final GlMappingService service =
            new GlMappingService(components, mappings, statuses, approvals, policy, settings);

    private final AccessContext finance =
            new AccessContext(
                    7L,
                    "finance.hq@x",
                    "Finance",
                    "s",
                    "P",
                    HQ,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(HQ));

    private PayComponent component(boolean retired) {
        PayComponent c = mock(PayComponent.class);
        when(c.getComponentId()).thenReturn(COMPONENT);
        when(c.getLegacyPayCode()).thenReturn(PAY_CODE);
        when(c.getComponentName()).thenReturn("City Compensatory Allowance");
        when(c.getComponentType()).thenReturn("EARNING");
        when(c.isIsRetired()).thenReturn(retired);
        when(components.findByLegacyPayCode(PAY_CODE)).thenReturn(Optional.of(c));
        return c;
    }

    private static GlAccountMapping version(
            Long unit, String account, int no, LocalDate from, Long approval) {
        return GlAccountMapping.propose(COMPONENT, unit, null, account, no, from, approval);
    }

    private void history(List<GlAccountMapping> rows, Map<Long, ApprovalStatus> decided) {
        when(mappings.findByComponentIdOrderByVersionNoDesc(COMPONENT)).thenReturn(rows);
        when(mappings.findByComponentIdInOrderByComponentIdAscVersionNoDesc(anyCollection()))
                .thenReturn(rows);
        // As ApprovalStatusReader: a mutable map, so a row without an approval reads null.
        when(statuses.of(anyCollection())).thenReturn(new HashMap<>(decided));
    }

    private void submitted(long approvalId) {
        when(policy.isAtHeadquarters(finance)).thenReturn(true);
        when(approvals.submit(any(), any(), isNull()))
                .thenReturn(
                        new SubmittedApproval(approvalId, ApprovalStatus.PENDING_APPROVAL, null));
        when(mappings.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void refused(ProposedMapping request, Enum<?> code) {
        assertThatThrownBy(() -> service.propose(finance, PAY_CODE, request, null))
                .isInstanceOf(FciException.class)
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(code);
    }

    @Test
    void SRS_G_7_aMappingNeedsAnEffectiveDateAndANaturalAccountOfTheGlFormat() {
        component(false);
        refused(null, CommonErrorCode.VALIDATION_FAILED);
        refused(new ProposedMapping(null, "51010", "r"), CommonErrorCode.VALIDATION_FAILED);
        refused(new ProposedMapping(TODAY, null, "r"), CommonErrorCode.VALIDATION_FAILED);
        // Capitals, digits and hyphens, 2 to 20 characters (natural_account VARCHAR2(20)), not
        // starting with a hyphen.
        refused(new ProposedMapping(TODAY, "gl-51010", "r"), CommonErrorCode.VALIDATION_FAILED);
        refused(new ProposedMapping(TODAY, "5", "r"), CommonErrorCode.VALIDATION_FAILED);
        refused(new ProposedMapping(TODAY, "-51010", "r"), CommonErrorCode.VALIDATION_FAILED);
        refused(new ProposedMapping(TODAY, "5".repeat(21), "r"), CommonErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_G_7_aRetiredPayCodeTakesNoNewMapping() {
        component(true);
        refused(new ProposedMapping(TODAY, "51010", "r"), CommonErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_G_7_aPayCodeOutsideThePayComponentCatalogueIsRefused() {
        when(components.findByLegacyPayCode(PAY_CODE)).thenReturn(Optional.empty());
        refused(new ProposedMapping(TODAY, "51010", "r"), ApiErrorCode.MASTER_NOT_IN_CATALOGUE);
    }

    @Test
    void SRS_G_7_theFirstMappingOfAPayCodeIsVersionOneFiledAtHeadquarters() {
        component(false);
        history(List.of(), Map.of());
        submitted(90L);

        MappingVersion proposed =
                service.propose(
                        finance,
                        PAY_CODE,
                        new ProposedMapping(TODAY.plusDays(9), " 51010-01 ", "New pay code"),
                        null);

        assertThat(proposed.versionNo()).isEqualTo(1);
        // The account is kept without the spaces typed around it, in the row and in the request.
        assertThat(proposed.naturalAccount()).isEqualTo("51010-01");
        assertThat(proposed.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(proposed.approvalId()).isEqualTo(90L);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(any(), sent.capture(), isNull());
        assertThat(sent.getValue().locationId()).isEqualTo(HQ);
        assertThat(sent.getValue().payload()).contains("\"naturalAccount\":\"51010-01\"");
        assertThat(sent.getValue().effectiveDate()).isEqualTo(TODAY.plusDays(9));
    }

    @Test
    void SRS_G_7_aNewVersionFollowsTheLatestAllUnitsVersionAndOnlyApprovedStartsBoundItsDate() {
        component(false);
        // Version 2 was rejected for 1 December; version 1 is approved from 1 April. A mapping of
        // one unit (org_unit_id set) belongs to another screen and is ignored here.
        history(
                List.of(
                        version(44L, "60000", 9, LocalDate.of(2026, 5, 1), 80L),
                        version(null, "51099", 2, LocalDate.of(2026, 12, 1), 71L),
                        version(null, "51010", 1, LocalDate.of(2026, 4, 1), 70L)),
                Map.of(
                        80L, ApprovalStatus.APPROVED,
                        71L, ApprovalStatus.REJECTED,
                        70L, ApprovalStatus.APPROVED));
        submitted(91L);

        // A start before the rejected version's is fine: only approved starts bound the date.
        MappingVersion proposed =
                service.propose(
                        finance,
                        PAY_CODE,
                        new ProposedMapping(LocalDate.of(2026, 11, 15), "51020", "Revision"),
                        null);
        assertThat(proposed.versionNo()).isEqualTo(3);

        // The rejected version's own date stays taken (uk_gl_account_mapping covers every row).
        refused(
                new ProposedMapping(LocalDate.of(2026, 12, 1), "51020", "Revision"),
                ApiErrorCode.MASTER_DATE_TAKEN);
        // Not after the latest approved start of the all-units mapping.
        refused(
                new ProposedMapping(LocalDate.of(2026, 4, 1), "51020", "Revision"),
                ApiErrorCode.MASTER_DATE_NOT_AFTER);
    }

    @Test
    void SRS_G_7_theScreenShowsTheApprovedVersionInForceTodayAndThePendingOne() {
        PayComponent code = component(false);
        List<GlAccountMapping> rows = new ArrayList<>();
        rows.add(version(44L, "60000", 9, LocalDate.of(2026, 5, 1), 80L));
        rows.add(version(null, "51030", 4, TODAY.plusDays(30), 73L));
        rows.add(version(null, "51020", 3, TODAY.plusDays(10), 72L));
        rows.add(version(null, "51010", 2, LocalDate.of(2026, 4, 1), 70L));
        rows.add(version(null, "40000", 1, LocalDate.of(2025, 4, 1), null));
        history(
                rows,
                Map.of(
                        80L, ApprovalStatus.APPROVED,
                        73L, ApprovalStatus.PENDING_APPROVAL,
                        72L, ApprovalStatus.APPROVED,
                        70L, ApprovalStatus.APPROVED));
        when(components.findAllByOrderByLegacyPayCode()).thenReturn(List.of(code));
        when(settings.today()).thenReturn(TODAY);

        ComponentRow row = service.list(finance).get(0);

        assertThat(row.payCode()).isEqualTo(PAY_CODE);
        // Approved but starting later is not in force; the unit's own mapping is never shown here.
        assertThat(row.current().naturalAccount()).isEqualTo("51010");
        assertThat(row.pending().naturalAccount()).isEqualTo("51030");

        List<MappingVersion> versions = service.history(finance, PAY_CODE);
        assertThat(versions).extracting(MappingVersion::versionNo).containsExactly(4, 3, 2, 1);
        // A version carried over without an approval item shows no decision.
        assertThat(versions.get(3).status()).isEqualTo("NONE");
        assertThat(versions.get(0).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void SRS_G_7_aPayCodeWithoutAMappingInForceShowsNone() {
        PayComponent code = component(false);
        history(List.of(), Map.of());
        when(components.findAllByOrderByLegacyPayCode()).thenReturn(List.of(code));
        when(settings.today()).thenReturn(TODAY);

        ComponentRow row = service.list(finance).get(0);

        assertThat(row.current()).isNull();
        assertThat(row.pending()).isNull();
    }
}
