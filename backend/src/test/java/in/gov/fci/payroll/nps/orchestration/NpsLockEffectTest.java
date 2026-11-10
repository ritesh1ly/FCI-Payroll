package in.gov.fci.payroll.nps.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.api.PreLockCheck;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The NPS records at the lock and the pre-lock check (plan 18 phases 18a / 18b; SRS-9-18, SRS-9-1a;
 * D18-6): a member of the locked unit is APPLIED, one separated before the month
 * STOPPED_SEPARATION; another unit's member, or one without an office, is left alone; a lock with
 * nothing to apply writes nothing. The check counts only the unit's mismatches.
 */
class NpsLockEffectTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AuditService audit = mock(AuditService.class);
    private final NpsLockEffect effect = new NpsLockEffect(jdbc, org, audit);

    NpsLockEffectTest() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        OrgLocation other = mock(OrgLocation.class);
        when(other.getId()).thenReturn(400L);
        when(org.cycleUnitOf(30L)).thenReturn(Optional.of(unit));
        when(org.cycleUnitOf(40L)).thenReturn(Optional.of(other));
    }

    private static Map<String, Object> row(long id, Long location, LocalDate separation) {
        Map<String, Object> r = new HashMap<>();
        r.put("NPS_DEDUCTION_ID", id);
        r.put("LOCATION_ID", location);
        r.put("SEPARATION_DATE", separation == null ? null : Date.valueOf(separation));
        return r;
    }

    private static CycleLocked locked() {
        return new CycleLocked(
                9L, 300L, "202610", "checker", Instant.parse("2026-10-31T10:00:00Z"));
    }

    @Test
    void D18_6_theUnitsRecordsAreAppliedOrStoppedFromSeparation() {
        when(jdbc.queryForList(anyString(), eq("202610")))
                .thenReturn(
                        List.of(
                                row(1L, 30L, null),
                                row(2L, 30L, LocalDate.of(2026, 9, 30)),
                                row(3L, 30L, LocalDate.of(2026, 10, 15)),
                                row(4L, 40L, null),
                                row(5L, null, null)));
        List<List<Object[]>> batches = new ArrayList<>();
        when(jdbc.batchUpdate(anyString(), anyList()))
                .thenAnswer(
                        i -> {
                            batches.add(i.getArgument(1));
                            return new int[0];
                        });
        effect.on(locked());
        verify(jdbc)
                .batchUpdate(
                        startsWith("UPDATE nps_deduction SET apply_status = 'APPLIED'"), anyList());
        verify(jdbc)
                .batchUpdate(
                        startsWith("UPDATE nps_deduction SET apply_status = 'STOPPED_SEPARATION'"),
                        anyList());
        assertThat(batches.get(0)).extracting(a -> a[1]).containsExactly(1L, 3L);
        assertThat(batches.get(1)).extracting(a -> a[1]).containsExactly(2L);
        verify(audit).record(eq("checker"), any());
    }

    @Test
    void D18_6_nothingToApplyWritesNothingAndOnlyStoppedIsWrittenAlone() {
        when(jdbc.queryForList(anyString(), eq("202610"))).thenReturn(List.of(row(4L, 40L, null)));
        effect.on(locked());
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
        verifyNoInteractions(audit);

        when(jdbc.queryForList(anyString(), eq("202610")))
                .thenReturn(List.of(row(2L, 30L, LocalDate.of(2026, 1, 1))));
        effect.on(locked());
        verify(jdbc, never())
                .batchUpdate(
                        startsWith("UPDATE nps_deduction SET apply_status = 'APPLIED'"), anyList());
    }

    @Test
    void SRS_9_1a_theCheckCountsOnlyTheUnitsMismatches() {
        NpsMismatchCheck check = new NpsMismatchCheck(jdbc, org);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("202610")))
                .thenReturn(Arrays.asList(30L, 40L, null, 30L));
        PreLockCheck.Outcome o = check.evaluate(new PreLockCheck.Cycle(9L, 300L, "202610"));
        assertThat(o.failures()).isEqualTo(2L);
        assertThat(o.detail()).startsWith("2 NPS");
    }
}
