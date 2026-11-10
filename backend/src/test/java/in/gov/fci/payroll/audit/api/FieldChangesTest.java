package in.gov.fci.payroll.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SRS-20-2, BR-AUDIT-001 / 003: one audit row per changed field. */
class FieldChangesTest {

    @Test
    void SRS_20_2_onlyChangedFieldsWriteARowInFieldOrderWithBeforeAndAfter() {
        Map<String, String> before = new HashMap<>(Map.of("b", "1", "a", "x", "same", "s"));
        Map<String, String> after =
                new HashMap<>(Map.of("b", "2", "a", "x", "same", "s", "c", "new"));
        List<AuditService.Entry> rows =
                FieldChanges.between("rate_set", "HRA", before, after, "Revision");
        assertThat(rows).extracting(AuditService.Entry::fieldName).containsExactly("b", "c");
        assertThat(rows.get(0).oldValue()).isEqualTo("1");
        assertThat(rows.get(0).newValue()).isEqualTo("2");
        assertThat(rows.get(1).oldValue()).isNull();
        assertThat(rows)
                .allSatisfy(
                        r -> {
                            assertThat(r.entityName()).isEqualTo("rate_set");
                            assertThat(r.entityKey()).isEqualTo("HRA");
                            assertThat(r.reason()).isEqualTo("Revision");
                            assertThat(r.changeType()).isEqualTo(ChangeType.CHANGE);
                        });
    }

    @Test
    void SRS_20_2_aFieldClearedOrRemovedIsAChangeToNull() {
        Map<String, String> before = Map.of("a", "1", "gone", "g");
        Map<String, String> after = new HashMap<>();
        after.put("a", null);
        List<AuditService.Entry> rows = FieldChanges.between("e", "k", before, after, "r");
        assertThat(rows).extracting(AuditService.Entry::fieldName).containsExactly("a", "gone");
        assertThat(rows).allSatisfy(r -> assertThat(r.newValue()).isNull());
    }

    @Test
    void SRS_20_2_aNewRecordWritesItsInitialValuesAndSkipsEmptyFields() {
        Map<String, String> after = new HashMap<>();
        after.put("name", "Zonal Office");
        after.put("note", null);
        List<AuditService.Entry> rows = FieldChanges.between("e", "k", null, after, "r");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).changeType()).isEqualTo(ChangeType.INITIAL_VALUE);
        assertThat(rows.get(0).newValue()).isEqualTo("Zonal Office");
    }

    @Test
    void anUnchangedRecordWritesNothingAndAnAfterImageIsRequired() {
        assertThat(FieldChanges.between("e", "k", Map.of("a", "1"), Map.of("a", "1"), "r"))
                .isEmpty();
        assertThatThrownBy(() -> FieldChanges.between("e", "k", Map.of(), null, "r"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void SRS_G_2_anEntryCarriesTheDateTheChangeAppliesFromWhenGiven() {
        AuditService.Entry entry =
                AuditService.Entry.change("e", "k", "f", "1", "2", "r")
                        .approval(7L)
                        .module("A-07")
                        .effective(LocalDate.of(2026, 11, 1));
        assertThat(entry.effectiveDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(entry.approvalId()).isEqualTo(7L);
        assertThat(entry.moduleCode()).isEqualTo("A-07");
        assertThat(AuditService.Entry.change("e", "k", "f", null, null, "r").effectiveDate())
                .isNull();
    }
}
