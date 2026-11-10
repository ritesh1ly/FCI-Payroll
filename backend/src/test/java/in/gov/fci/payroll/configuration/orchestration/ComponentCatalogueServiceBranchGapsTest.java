package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.ComponentRules;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * M-07 Pay Component Catalogue edge cases (SRS-19-1, SRS-G-42): a pay code already in the catalogue
 * is never defined again, whatever the retired list says; a basis rule the M-08 catalogue does not
 * hold (no count, or zero) refuses the definition; a seeded entry without a version number is shown
 * as version 1.
 */
class ComponentCatalogueServiceBranchGapsTest {

    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ComponentCatalogueService service =
            new ComponentCatalogueService(
                    components,
                    approvals,
                    mock(Authorization.class),
                    jdbc,
                    mock(ObjectMapper.class),
                    mock(SystemParameters.class));
    private final AccessContext context = mock(AccessContext.class);

    private static ComponentRules.Definition definition() {
        return new ComponentRules.Definition(
                913,
                "Demo washing allowance",
                "EARNING",
                "ALLOWANCES",
                "DEMO.WASHING",
                "RULE",
                "NA-4101",
                "PARTLY",
                false,
                Set.of("STAFF"),
                LocalDate.of(2026, 4, 1));
    }

    private static void refused(Runnable call, ApiErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_G_42_aPayCodeInTheCatalogueIsNeverDefinedAgain() {
        when(components.findByLegacyPayCode(913)).thenReturn(Optional.of(mock(PayComponent.class)));
        when(jdbc.queryForObject(
                        contains("retired_pay_code"), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        refused(
                () -> service.define(context, definition(), "New allowance"),
                ApiErrorCode.COMPONENT_CODE_TAKEN);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_19_1_aBasisRuleTheCatalogueDoesNotHoldRefusesTheDefinition() {
        when(components.findByLegacyPayCode(913)).thenReturn(Optional.empty());
        // A free code: absent from the catalogue and from the retired list (no count read).
        when(jdbc.queryForObject(
                        contains("retired_pay_code"), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);
        when(jdbc.queryForObject(
                        contains("rule_definition"), eq(Integer.class), any(Object[].class)))
                .thenReturn(null, 0);
        refused(
                () -> service.define(context, definition(), "New allowance"),
                ApiErrorCode.COMPONENT_DEFINITION);
        refused(
                () -> service.define(context, definition(), "New allowance"),
                ApiErrorCode.COMPONENT_DEFINITION);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_19_1_aSeededEntryWithoutAVersionNumberIsVersionOne() {
        PayComponent seeded = mock(PayComponent.class);
        when(seeded.getLegacyPayCode()).thenReturn(101);
        when(seeded.getVersionNo()).thenReturn(null);
        PayComponent revised = mock(PayComponent.class);
        when(revised.getLegacyPayCode()).thenReturn(102);
        when(revised.getVersionNo()).thenReturn(3);
        when(components.findAllByOrderByLegacyPayCode()).thenReturn(List.of(seeded, revised));
        assertThat(service.list(context, null))
                .extracting(ComponentCatalogueService.ComponentRow::versionNo)
                .containsExactly(1, 3);
    }

    private static ComponentRules.Definition defined(
            int payCode, String name, String type, LocalDate from) {
        return new ComponentRules.Definition(
                payCode,
                name,
                type,
                "ALLOWANCES",
                "DEMO.WASHING",
                "RULE",
                "NA-4101",
                "PARTLY",
                false,
                Set.of("STAFF"),
                from);
    }

    /** SRS-19-1: a definition the rules fault, or none at all, is refused before any lookup. */
    @Test
    void SRS_19_1_aDefectiveOrMissingDefinitionIsRefusedForDefineAndRevise() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        List<ComponentRules.Definition> bad =
                java.util.Arrays.asList(
                        null,
                        defined(0, "Demo washing allowance", "EARNING", from),
                        defined(1000, "Demo washing allowance", "EARNING", from),
                        defined(913, " ", "EARNING", from),
                        defined(913, "Demo washing allowance", "BONUS", from),
                        defined(913, "Demo washing allowance", "EARNING", null));
        for (ComponentRules.Definition d : bad) {
            refused(
                    () -> service.define(context, d, "New allowance"),
                    ApiErrorCode.COMPONENT_DEFINITION);
            refused(
                    () -> service.revise(context, d, "Revision"),
                    ApiErrorCode.COMPONENT_DEFINITION);
        }
        verify(approvals, never()).submit(any(), any(), any());
        verify(components, never()).findByLegacyPayCode(org.mockito.ArgumentMatchers.anyInt());
    }

    /** SRS-19-1: the boundary pay codes 1 and 999 pass the shape check (no defect is reported). */
    @Test
    void SRS_19_1_theBoundaryPayCodesOneAndNineHundredNinetyNineAreAccepted() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        assertThat(ComponentRules.defect(defined(1, "Demo washing allowance", "EARNING", from)))
                .isEmpty();
        assertThat(ComponentRules.defect(defined(999, "Demo washing allowance", "EARNING", from)))
                .isEmpty();
    }

    /** SRS-19-1: revising a pay code that is not in the catalogue is not found. */
    @Test
    void SRS_19_1_revisingAnUnknownPayCodeIsNotFound() {
        when(components.findByLegacyPayCode(913)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revise(context, definition(), "Revision"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(
                                                in.gov.fci.payroll.platform.error.CommonErrorCode
                                                        .NOT_FOUND));
        verify(approvals, never()).submit(any(), any(), any());
    }

    /** SRS M-07 Where Used: registered consumers first, then the lists that name the code. */
    @Test
    void SRS_19_1_whereUsedListsTheConsumersThenTheReferenceListsThatNameTheCode()
            throws Exception {
        PayComponent c = mock(PayComponent.class);
        when(c.getComponentId()).thenReturn(55L);
        when(components.findByLegacyPayCode(913)).thenReturn(Optional.of(c));
        java.sql.ResultSet usage = mock(java.sql.ResultSet.class);
        when(usage.getString(1)).thenReturn("ENGINE");
        when(usage.getString(2)).thenReturn("STAFF");
        when(usage.getString(3)).thenReturn("Basis rule");
        java.sql.ResultSet list = mock(java.sql.ResultSet.class);
        when(list.getString(1)).thenReturn("LEAVE_REASON");
        when(list.getString(2)).thenReturn("MEDICAL");
        org.mockito.Mockito.doAnswer(
                        call -> {
                            org.springframework.jdbc.core.RowCallbackHandler h =
                                    call.getArgument(1);
                            h.processRow(usage);
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("component_usage"),
                        any(org.springframework.jdbc.core.RowCallbackHandler.class),
                        any(Object[].class));
        org.mockito.Mockito.doAnswer(
                        call -> {
                            org.springframework.jdbc.core.RowCallbackHandler h =
                                    call.getArgument(1);
                            h.processRow(list);
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("reference_value"),
                        any(org.springframework.jdbc.core.RowCallbackHandler.class),
                        any(Object[].class));

        List<ComponentCatalogueService.Usage> used = service.whereUsed(context, 913);

        assertThat(used)
                .containsExactly(
                        new ComponentCatalogueService.Usage("ENGINE", "STAFF", "Basis rule"),
                        new ComponentCatalogueService.Usage(
                                "REFERENCE_LIST", "LEAVE_REASON", "MEDICAL"));
    }

    /** SRS M-07 Where Used: an unknown pay code is not found and nothing is read. */
    @Test
    void SRS_19_1_whereUsedOfAnUnknownPayCodeIsNotFound() {
        when(components.findByLegacyPayCode(913)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.whereUsed(context, 913)).isInstanceOf(FciException.class);
        verify(jdbc, never())
                .query(
                        any(String.class),
                        any(org.springframework.jdbc.core.RowCallbackHandler.class),
                        any(Object[].class));
    }
}
