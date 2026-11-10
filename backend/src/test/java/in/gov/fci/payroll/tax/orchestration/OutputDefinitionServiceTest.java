package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.FieldSources;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.OutputRules;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.OutputDefinition;
import in.gov.fci.payroll.tax.business.model.OutputField;
import in.gov.fci.payroll.tax.business.model.OutputField.Binding;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-17 beyond the integration path (SRS M-17, BR-MIS-002, BR-TAX-013): Headquarters only, the
 * reason and the request required, controls optional, decisions of other screens or rejected, an
 * output of another domain, a version ended only when it overlaps the new one, every binding
 * written, a pay code or field source that does not resolve, a formula the engine refuses.
 */
class OutputDefinitionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2027, 4, 1);

    private final TaxRepositories.OutputDefinitions definitions =
            mock(TaxRepositories.OutputDefinitions.class);
    private final TaxRepositories.OutputVersions versions =
            mock(TaxRepositories.OutputVersions.class);
    private final TaxRepositories.OutputFields fields = mock(TaxRepositories.OutputFields.class);
    private final TaxRepositories.OutputControls controls =
            mock(TaxRepositories.OutputControls.class);
    private final PayConfiguration pay = mock(PayConfiguration.class);
    private final FieldSources fieldSources = mock(FieldSources.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final OutputDefinitionService service =
            new OutputDefinitionService(
                    definitions,
                    versions,
                    fields,
                    controls,
                    pay,
                    fieldSources,
                    rules,
                    approvals,
                    statuses,
                    policy,
                    audit,
                    json,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext hq =
            new AccessContext(
                    2L,
                    "admin",
                    "Admin",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(1L));
    private final OutputDefinition definition = mock(OutputDefinition.class);

    OutputDefinitionServiceTest() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        when(definition.getReportKey()).thenReturn("FORM_12BA");
        when(definition.getReportDefId()).thenReturn(4L);
        when(definition.getOutputDomain()).thenReturn("STATUTORY_OUTPUT");
        when(definition.isActive()).thenReturn(true);
        when(definitions.findByReportKey("FORM_12BA")).thenReturn(Optional.of(definition));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
        when(statuses.pending(any())).thenReturn(List.of());
        PayConfiguration.Component basic = mock(PayConfiguration.Component.class);
        when(basic.componentId()).thenReturn(101L);
        when(pay.component(anyInt())).thenReturn(Optional.empty());
        when(pay.component(101)).thenReturn(Optional.of(basic));
        when(fieldSources.field("employee", "pan_number"))
                .thenReturn(
                        Optional.of(new FieldSources.Field(5L, "employee", "pan_number", "HRMS")));
        when(versions.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static OutputDefinitionService.Proposal proposal(
            List<OutputRules.Field> fields, List<OutputRules.Control> controls) {
        return new OutputDefinitionService.Proposal(
                "FORM_12BA", FROM, "PDF", " ", "", true, fields, controls, "admin", NOW);
    }

    private ApprovalDecided decided(
            String module, String entity, ApprovalStatus outcome, String payload, String reason) {
        return new ApprovalDecided(
                90L, module, entity, "FORM_12BA", outcome, reason, payload, "approver", NOW);
    }

    @Test
    void BR_MIS_002_onlyHeadquartersProposesWithAReasonAndARequest() {
        AccessContext region =
                new AccessContext(
                        2L,
                        "admin",
                        "Admin",
                        "s",
                        "P",
                        3L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(3L));
        OutputDefinitionService.Proposal p =
                proposal(
                        List.of(new OutputRules.Field("Form", Binding.LITERAL, "Form 12BA", null)),
                        null);
        refused(() -> service.propose(region, p, "IT"), ApiErrorCode.HQ_ONLY);
        refused(() -> service.propose(hq, p, " "), CommonErrorCode.VALIDATION_FAILED);
        refused(() -> service.propose(hq, p, null), CommonErrorCode.VALIDATION_FAILED);
        refused(() -> service.propose(hq, null, "IT"), CommonErrorCode.VALIDATION_FAILED);
        // Controls are optional; blank note and rounding travel as nothing.
        assertThat(service.propose(hq, p, " IT ")).isEqualTo(77L);
        // An output outside the statutory domain, or none.
        OutputDefinition report = mock(OutputDefinition.class);
        when(report.getOutputDomain()).thenReturn("REPORT");
        when(definitions.findByReportKey("MIS_1")).thenReturn(Optional.of(report));
        OutputDefinitionService.Proposal mis =
                new OutputDefinitionService.Proposal(
                        "MIS_1",
                        FROM,
                        "PDF",
                        null,
                        null,
                        true,
                        p.fields(),
                        List.of(),
                        "admin",
                        NOW);
        refused(() -> service.propose(hq, mis, "IT"), CommonErrorCode.NOT_FOUND);
        OutputDefinitionService.Proposal none =
                new OutputDefinitionService.Proposal(
                        null, FROM, "PDF", null, null, true, p.fields(), List.of(), "admin", NOW);
        refused(() -> service.propose(hq, none, "IT"), CommonErrorCode.NOT_FOUND);
        // A withdrawn (inactive) definition is never versioned and is not in the catalogue.
        OutputDefinition withdrawn = mock(OutputDefinition.class);
        when(withdrawn.getOutputDomain()).thenReturn("STATUTORY_OUTPUT");
        when(withdrawn.getReportKey()).thenReturn("FORM_OLD");
        when(definitions.findByReportKey("FORM_OLD")).thenReturn(Optional.of(withdrawn));
        OutputDefinitionService.Proposal old =
                new OutputDefinitionService.Proposal(
                        "FORM_OLD",
                        FROM,
                        "PDF",
                        null,
                        null,
                        true,
                        p.fields(),
                        List.of(),
                        "admin",
                        NOW);
        refused(() -> service.propose(hq, old, "IT"), CommonErrorCode.NOT_FOUND);
        when(definitions.findByOutputDomainOrderByReportKey("STATUTORY_OUTPUT"))
                .thenReturn(List.of(definition, withdrawn));
        assertThat(service.catalogue(hq))
                .extracting(OutputDefinitionService.OutputView::key)
                .containsExactly("FORM_12BA");
    }

    @Test
    void BR_TAX_013_aReferenceThatDoesNotResolveIsRefused() {
        for (OutputRules.Field bad :
                List.of(
                        new OutputRules.Field("Pay", Binding.COMPONENT, "BASIC", null),
                        new OutputRules.Field("Pay", Binding.COMPONENT, "999", null),
                        new OutputRules.Field("PAN", Binding.FIELD_SOURCE, "employee.", null),
                        new OutputRules.Field("PAN", Binding.FIELD_SOURCE, ".pan_number", null),
                        new OutputRules.Field("PAN", Binding.FIELD_SOURCE, "employee", null),
                        new OutputRules.Field(
                                "PAN", Binding.FIELD_SOURCE, "employee.aadhaar", null))) {
            refused(
                    () -> service.propose(hq, proposal(List.of(bad), List.of()), "IT"),
                    TaxErrorCode.OUTPUT_BINDING);
        }
        doThrow(new FciException(ApiErrorCode.RULE_NOT_CONFIGURED))
                .when(rules)
                .checkFormula("BAD +", false);
        refused(
                () ->
                        service.propose(
                                hq,
                                proposal(
                                        List.of(
                                                new OutputRules.Field(
                                                        "G", Binding.DERIVED, "BAD +", null)),
                                        List.of()),
                                "IT"),
                TaxErrorCode.OUTPUT_BINDING);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void BR_MIS_002_onlyAnApprovedVersionOfM17IsWrittenAndItEndsTheOverlappingOne() {
        String payload =
                json.writeValueAsString(
                        proposal(
                                List.of(
                                        new OutputRules.Field(
                                                "Basic", Binding.COMPONENT, "101", null),
                                        new OutputRules.Field(
                                                "PAN",
                                                Binding.FIELD_SOURCE,
                                                "employee.pan_number",
                                                "XXXXX9999X"),
                                        new OutputRules.Field(
                                                "Total", Binding.AGGREGATE, "SUM", null),
                                        new OutputRules.Field(
                                                "Gross", Binding.DERIVED, "BASIC + 1", null),
                                        new OutputRules.Field(
                                                "Form", Binding.LITERAL, "Form 12BA", " ")),
                                List.of(
                                        new OutputRules.Control(
                                                "TDS", "CHALLAN", BigDecimal.ONE, true))));
        service.on(
                decided(
                        "M-18",
                        "report_definition_version",
                        ApprovalStatus.APPROVED,
                        payload,
                        null));
        service.on(decided("M-17", "it_section_master", ApprovalStatus.APPROVED, payload, null));
        service.on(
                decided(
                        "M-17",
                        "report_definition_version",
                        ApprovalStatus.REJECTED,
                        payload,
                        "no"));
        verifyNoInteractions(versions);

        OutputVersion open =
                OutputVersion.approved(
                        4L,
                        1,
                        LocalDate.of(2026, 4, 1),
                        "PDF",
                        null,
                        null,
                        true,
                        null,
                        1L,
                        "admin",
                        NOW);
        OutputVersion closed =
                OutputVersion.approved(
                        4L,
                        2,
                        LocalDate.of(2025, 4, 1),
                        "PDF",
                        null,
                        null,
                        true,
                        null,
                        1L,
                        "admin",
                        NOW);
        closed.endBefore(LocalDate.of(2026, 4, 1));
        when(versions.findByReportDefIdOrderByEffectiveFromDesc(4L))
                .thenReturn(List.of(open, closed));
        service.on(
                decided(
                        "M-17",
                        "report_definition_version",
                        ApprovalStatus.APPROVED,
                        payload,
                        null));
        assertThat(open.getEffectiveTo()).isEqualTo(FROM.minusDays(1));
        assertThat(closed.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 3, 31));
        verify(fields, org.mockito.Mockito.times(5)).save(any(OutputField.class));
        verify(controls).save(any());
        verify(audit).record(eq("approver"), any());
    }

    @Test
    void BR_MIS_002_theGoverningVersionIsTheOneInForceOnTheDayOrTheOutputIsBlocked() {
        when(definitions.findByReportKey("FORM_10E")).thenReturn(Optional.empty());
        refused(() -> service.requireGoverning("FORM_10E", FROM), TaxErrorCode.OUTPUT_BLOCKED);
        OutputVersion v =
                OutputVersion.approved(
                        4L,
                        1,
                        LocalDate.of(2026, 4, 1),
                        "PDF",
                        null,
                        null,
                        true,
                        null,
                        1L,
                        "admin",
                        NOW);
        when(versions.findByReportDefIdOrderByEffectiveFromDesc(4L)).thenReturn(List.of(v));
        assertThat(service.governing("FORM_12BA", LocalDate.of(2026, 3, 31))).isEmpty();
        assertThat(service.governing("FORM_12BA", FROM)).isPresent();
        assertThat(service.controlsOf(7L)).isEmpty();
    }

    /**
     * BR-MIS-002: versions never overlap - one already ended exactly on the new start day (the
     * boundary) still overlaps and is ended the day before.
     */
    @Test
    void BR_MIS_002_aVersionEndingOnTheNewStartIsEndedTheDayBefore() {
        String payload =
                json.writeValueAsString(
                        proposal(
                                List.of(
                                        new OutputRules.Field(
                                                "Basic", Binding.COMPONENT, "101", null)),
                                List.of()));
        OutputVersion endsOnStart =
                OutputVersion.approved(
                        4L,
                        1,
                        LocalDate.of(2026, 4, 1),
                        "PDF",
                        null,
                        null,
                        true,
                        null,
                        1L,
                        "admin",
                        NOW);
        endsOnStart.endBefore(FROM.plusDays(1));
        assertThat(endsOnStart.getEffectiveTo()).isEqualTo(FROM);
        when(versions.findByReportDefIdOrderByEffectiveFromDesc(4L))
                .thenReturn(List.of(endsOnStart));
        service.on(
                decided(
                        "M-17",
                        "report_definition_version",
                        ApprovalStatus.APPROVED,
                        payload,
                        "Notified format"));
        assertThat(endsOnStart.getEffectiveTo()).isEqualTo(FROM.minusDays(1));
        verify(fields).save(any(OutputField.class));
    }
}
