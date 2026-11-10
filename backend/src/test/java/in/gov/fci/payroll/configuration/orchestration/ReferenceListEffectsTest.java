package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * M-16 reference values on approval (plan 04; SRS-16-24, SRS-20-2, SRS-G-39): a new code must still
 * be free and a changed one still exist; the Hindi label is versioned - unchanged does nothing,
 * blank withdraws it, new text started today is corrected in place, older text is superseded.
 */
class ReferenceListEffectsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReferenceListService lists = mock(ReferenceListService.class);
    private final ReferenceData reference = mock(ReferenceData.class);
    private final MasterAudit audit = mock(MasterAudit.class);
    private final ReferenceListEffects effects =
            new ReferenceListEffects(jdbc, lists, reference, audit);

    ReferenceListEffectsTest() {
        when(reference.today()).thenReturn(TODAY);
        when(lists.head("CLAIM_TYPE")).thenReturn(new ReferenceListService.ListHead(4L, false));
    }

    private ApprovalDecided decided(ApprovalStatus outcome, ReferenceListService.Change change) {
        when(lists.read("payload")).thenReturn(change);
        return new ApprovalDecided(
                9L,
                "M-16",
                ReferenceListService.ENTITY,
                "CLAIM_TYPE.LTC",
                outcome,
                null,
                "payload",
                "checker@x",
                Instant.now());
    }

    private static ReferenceListService.Change change(String op, String hindi, LocalDate to) {
        return new ReferenceListService.Change(
                op, "CLAIM_TYPE", "LTC", "Leave travel", hindi, 3, TODAY, to, "Order", "maker@x");
    }

    private void hindi(Object[] existing) {
        when(jdbc.query(
                        contains("FROM label_master"),
                        org.mockito.ArgumentMatchers.<RowMapper<Object[]>>any(),
                        any(Object[].class)))
                .thenReturn(existing == null ? List.of() : Collections.singletonList(existing));
    }

    private static ReferenceListService.ValueState state() {
        return new ReferenceListService.ValueState(
                21L, "Leave travel", 3, TODAY.minusYears(1), null);
    }

    @Test
    void SRS_20_2_aRejectionOrAnotherEntityChangesNothingAndACodeMustStillBeFreeOrStillExist() {
        effects.on(
                new ApprovalDecided(
                        9L,
                        "M-16",
                        "other",
                        "x",
                        ApprovalStatus.APPROVED,
                        null,
                        "payload",
                        "c",
                        Instant.now()));
        effects.on(decided(ApprovalStatus.REJECTED, change("CREATE", null, null)));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        when(lists.value(4L, "LTC")).thenReturn(Optional.of(state()));
        ApprovalDecided create = decided(ApprovalStatus.APPROVED, change("CREATE", null, null));
        assertThatThrownBy(() -> effects.on(create))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.REFERENCE_CODE_TAKEN);
        when(lists.value(4L, "LTC")).thenReturn(Optional.empty());
        ApprovalDecided update = decided(ApprovalStatus.APPROVED, change("UPDATE", null, null));
        assertThatThrownBy(() -> effects.on(update))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MASTER_NOT_IN_CATALOGUE);
    }

    @Test
    void SRS_G_39_aNewCodeWithAnEndDateGetsItsFirstHindiLabel() {
        when(lists.value(4L, "LTC")).thenReturn(Optional.empty()).thenReturn(Optional.of(state()));
        hindi(null);
        when(jdbc.queryForObject(
                        contains("COALESCE(MAX(version_no)"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(null);
        effects.on(
                decided(ApprovalStatus.APPROVED, change("CREATE", "यात्रा", TODAY.plusYears(1))));
        verify(jdbc).update(contains("INSERT INTO reference_value"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO label_master"), any(Object[].class));
        verify(reference).changed("checker@x");
    }

    @Test
    void SRS_G_39_theHindiLabelIsUnchangedWithdrawnCorrectedOrSuperseded() {
        when(lists.value(4L, "LTC")).thenReturn(Optional.of(state()));
        hindi(new Object[] {5L, TODAY.minusMonths(2), "यात्रा"});
        effects.on(decided(ApprovalStatus.APPROVED, change("UPDATE", "यात्रा", null)));
        verify(jdbc, never()).update(contains("label_master"), any(Object[].class));

        effects.on(decided(ApprovalStatus.APPROVED, change("UPDATE", null, TODAY.plusMonths(1))));
        verify(jdbc)
                .update(
                        contains("SET label_status = ?"),
                        eq("WITHDRAWN"),
                        any(),
                        any(),
                        any(),
                        eq(5L));

        hindi(new Object[] {6L, TODAY, "यात्र"});
        effects.on(decided(ApprovalStatus.APPROVED, change("UPDATE", "यात्रा", null)));
        verify(jdbc).update(contains("SET label_text = ?"), any(Object[].class));

        hindi(new Object[] {7L, TODAY.minusDays(3), "पुराना"});
        when(jdbc.queryForObject(
                        contains("COALESCE(MAX(version_no)"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(2);
        effects.on(decided(ApprovalStatus.APPROVED, change("UPDATE", "नया", null)));
        verify(jdbc)
                .update(
                        contains("SET label_status = ?"),
                        eq("SUPERSEDED"),
                        any(),
                        any(),
                        any(),
                        eq(7L));
        verify(jdbc).update(contains("INSERT INTO label_master"), any(Object[].class));
    }
}
