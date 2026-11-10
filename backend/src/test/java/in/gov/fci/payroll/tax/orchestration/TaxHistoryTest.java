package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.business.model.OutputControlTotal;
import in.gov.fci.payroll.tax.business.model.OutputDefinition;
import in.gov.fci.payroll.tax.business.model.OutputField;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.business.model.PtState;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The tax masters on X-04 and their History buttons (SRS-20-29, SRS-20-3): an M-17 statutory output
 * is its approved versions with their fields and control totals (a seeded output none yet, a report
 * of another domain no record, BR-TAX-013); an M-18 section its versions numbered oldest first
 * (SRS-7-2); an M-18 PT state one row with the fields its approvals audit (SRS-7-4); an unknown key
 * is no record.
 */
class TaxHistoryTest {

    private final TaxHistory config = new TaxHistory();

    @Test
    void SRS_20_29_anOutputIsItsApprovedVersionsWithFieldsAndControlTotals() {
        TaxRepositories.OutputDefinitions definitions =
                mock(TaxRepositories.OutputDefinitions.class);
        TaxRepositories.OutputVersions versions = mock(TaxRepositories.OutputVersions.class);
        TaxRepositories.OutputFields fields = mock(TaxRepositories.OutputFields.class);
        TaxRepositories.OutputControls controls = mock(TaxRepositories.OutputControls.class);
        RecordHistorySource source =
                config.outputDefinitionHistory(definitions, versions, fields, controls);
        assertThat(source.entity()).isEqualTo("report_definition_version");
        OutputDefinition form24q = mock(OutputDefinition.class);
        when(form24q.getReportDefId()).thenReturn(3L);
        when(form24q.getOutputDomain()).thenReturn("STATUTORY_OUTPUT");
        OutputDefinition register = mock(OutputDefinition.class);
        when(register.getOutputDomain()).thenReturn("MIS");
        when(definitions.findByReportKey("FORM_24Q")).thenReturn(Optional.of(form24q));
        when(definitions.findByReportKey("PAY_REGISTER")).thenReturn(Optional.of(register));
        OutputVersion first = version(10L, 1, LocalDate.of(2026, 4, 1), "APPROVED", true);
        OutputVersion second = version(11L, 2, LocalDate.of(2027, 4, 1), "APPROVED", false);
        OutputVersion blocked = version(12L, 3, LocalDate.of(2028, 4, 1), "REJECTED", false);
        when(versions.findByReportDefIdOrderByEffectiveFromDesc(3L))
                .thenReturn(List.of(blocked, second, first));
        OutputField pan = mock(OutputField.class);
        when(pan.getFieldLabel()).thenReturn("PAN");
        OutputField tds = mock(OutputField.class);
        when(tds.getFieldLabel()).thenReturn("TDS");
        when(fields.findByReportVersionIdOrderByFieldOrder(10L)).thenReturn(List.of(pan, tds));
        OutputControlTotal total = mock(OutputControlTotal.class);
        when(total.getControlLabel()).thenReturn("Total TDS");
        when(controls.findByReportVersionId(10L)).thenReturn(List.of(total));

        HistoryRecord record = source.find("FORM_24Q").orElseThrow();
        assertThat(record.isVersioned()).isTrue();
        assertThat(record.screen()).isEqualTo("M-17");
        assertThat(record.auditKeys()).containsExactly("FORM_24Q");
        assertThat(record.versions()).extracting(v -> v.versionNo()).containsExactly(1, 2);
        assertThat(record.versions().get(0).approvalId()).isEqualTo(70L);
        assertThat(record.versions().get(0).values())
                .containsEntry("layout_format", "TXT")
                .containsEntry("period_lock_required", "Y")
                .containsEntry("fields", "PAN, TDS")
                .containsEntry("control_totals", "Total TDS");
        assertThat(record.versions().get(1).values())
                .containsEntry("period_lock_required", "N")
                .containsEntry("fields", "");
        assertThat(source.find("PAY_REGISTER")).as("an M-12 report").isEmpty();
        assertThat(source.find("NONE")).isEmpty();
    }

    private static OutputVersion version(
            long id, int no, LocalDate from, String state, boolean periodLock) {
        OutputVersion v = mock(OutputVersion.class);
        when(v.getReportVersionId()).thenReturn(id);
        when(v.getVersionNo()).thenReturn(no);
        when(v.getEffectiveFrom()).thenReturn(from);
        when(v.getApprovalId()).thenReturn(70L);
        when(v.getApprovalState()).thenReturn(state);
        when(v.getLayoutFormat()).thenReturn("TXT");
        when(v.isPeriodLockRequired()).thenReturn(periodLock);
        return v;
    }

    @Test
    void SRS_7_2_aSectionIsItsVersionsNumberedOldestFirst() {
        TaxRepositories.Sections sections = mock(TaxRepositories.Sections.class);
        RecordHistorySource source = config.sectionHistory(sections);
        assertThat(source.entity()).isEqualTo("it_section_master");
        assertThat(source.find("80C")).isEmpty();
        ItSection old =
                ItSection.approved(
                        "80C",
                        "Savings",
                        new BigDecimal("150000"),
                        "OLD",
                        LocalDate.of(2024, 4, 1));
        old.endBefore(LocalDate.of(2026, 4, 1));
        ItSection now =
                ItSection.approved("80C", "Savings", null, "BOTH", LocalDate.of(2026, 4, 1));
        when(sections.findBySectionCodeOrderByEffectiveFromDesc("80C"))
                .thenReturn(List.of(now, old));
        HistoryRecord record = source.find("80C").orElseThrow();
        assertThat(record.screen()).isEqualTo("M-18");
        assertThat(record.auditKeys()).containsExactly("80C");
        assertThat(record.versions()).extracting(v -> v.versionNo()).containsExactly(1, 2);
        assertThat(record.versions().get(0).effectiveTo()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(record.versions().get(0).values())
                .containsEntry("max_deduction_amount", "150000")
                .containsEntry("regime_applicability", "OLD");
        assertThat(record.versions().get(1).values()).containsEntry("max_deduction_amount", null);
        assertThat(record.versions().get(1).approvalId()).isNull();
    }

    @Test
    void SRS_7_4_aPtStateIsOneRowWithItsAuditedFields() {
        TaxRepositories.PtStates states = mock(TaxRepositories.PtStates.class);
        RecordHistorySource source = config.ptStateHistory(states);
        assertThat(source.entity()).isEqualTo("pt_state_master");
        PtState state = mock(PtState.class);
        when(state.getEffectiveFrom()).thenReturn(LocalDate.of(2026, 4, 1));
        when(state.image()).thenReturn(java.util.Map.of("state_name", "Maharashtra"));
        when(states.findByStateCode("MH")).thenReturn(Optional.of(state));
        HistoryRecord record = source.find("MH").orElseThrow();
        assertThat(record.isVersioned()).isFalse();
        assertThat(record.screen()).isEqualTo("M-18");
        assertThat(record.auditKeys()).containsExactly("MH");
        assertThat(record.existsFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(record.current()).containsEntry("state_name", "Maharashtra");
        assertThat(source.find("XX")).isEmpty();
    }
}
