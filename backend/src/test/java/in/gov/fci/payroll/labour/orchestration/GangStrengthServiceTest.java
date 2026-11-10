package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.labour.business.GangStrengthRules;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gang strength and shortfall (SRS-2-48 l.4403; OD-90): the day's check raises or clears the X-05
 * GANG_SHORTFALL, the report lists the marked days, and only the Depot Manager of the depot closes
 * an open alert with a reason.
 */
class GangStrengthServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final Authorization policy = mock(Authorization.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final GangStrengthService service =
            new GangStrengthService(jdbc, gangs, policy, worklist, audit);
    private final LabourGangs.Gang gang =
            new LabourGangs.Gang(
                    5L,
                    "DP-DL01-G002",
                    "Okhla Gang 1",
                    7L,
                    "DP-DL01",
                    "DEPT_LABOUR",
                    "ACTIVE",
                    DAY.minusYears(1),
                    null);

    @BeforeEach
    void wire() {
        when(context.loginId()).thenReturn("dm.okhla@fci-demo.test");
        when(gangs.workGang(5L)).thenReturn(gang);
    }

    private void strength(Integer n) {
        when(jdbc.queryForList(
                        contains("FROM labour_gang_strength s"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(n == null ? List.of() : List.of(n));
    }

    private static Map<String, Object> row(String code, String mark, String weight) {
        Map<String, Object> r = new HashMap<>();
        r.put("LABOUR_NUMBER", code);
        r.put("ATTENDANCE_CODE", mark);
        r.put("PRESENT_DAY_WEIGHT", weight == null ? null : new BigDecimal(weight));
        return r;
    }

    private void members(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(contains("FROM labour_gang_membership m"), any(Object[].class)))
                .thenReturn(rows);
    }

    private void open(Integer n) {
        when(jdbc.queryForObject(
                        contains("FROM payroll_exception"), eq(Integer.class), any(Object[].class)))
                .thenReturn(n);
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @Test
    void SRS_2_48_aShortDayRaisesTheAlertToTheDepotManager() {
        strength(3);
        members(List.of(row("L1", "W", "1"), row("L2", "A", "0"), row("L3", null, null)));
        GangStrengthRules.Check c = service.checkDay("ldh", gang, DAY);
        assertThat(c.shortBy()).isEqualTo(2);
        ArgumentCaptor<ExceptionWorklist.NewEntry> entry =
                ArgumentCaptor.forClass(ExceptionWorklist.NewEntry.class);
        verify(worklist).raise(eq("ldh"), entry.capture());
        assertThat(entry.getValue().exceptionCode()).isEqualTo("GANG_SHORTFALL");
        assertThat(entry.getValue().severity()).isEqualTo("WARNING");
        assertThat(entry.getValue().fieldName()).isEqualTo("gang 5 2026-09-14");
        assertThat(entry.getValue().detail()).contains("absent: L2, L3");
        verify(worklist, never())
                .clear(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void SRS_2_48_aFilledGapClosesTheAlertAndThePoolIsNotChecked() {
        strength(2);
        members(List.of(row("L1", "W", "1"), row("L2", "D", "1")));
        assertThat(service.checkDay("ldh", gang, DAY).shortfall()).isFalse();
        verify(worklist)
                .clear(
                        eq("ldh"),
                        eq("GANG_SHORTFALL"),
                        isNull(),
                        isNull(),
                        eq("gang 5 2026-09-14"),
                        contains("Gap filled"));
        strength(null);
        assertThat(service.checkDay("ldh", gang, DAY)).isNull();
        verify(worklist, never()).raise(anyString(), any());
    }

    @Test
    void SRS_2_48_theReportListsEachMarkedDayWithItsShortfallAttachedLabourAndAlert() {
        when(jdbc.queryForList(
                        contains("SELECT DISTINCT attendance_date"),
                        eq(Date.class),
                        any(Object[].class)))
                .thenReturn(List.of(Date.valueOf(DAY), Date.valueOf(DAY.plusDays(1))));
        when(jdbc.queryForList(
                        contains("FROM labour_gang_strength s"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(List.of(2))
                .thenReturn(List.of());
        members(List.of(row("L1", "W", "1"), row("L2", "A", "0")));
        when(jdbc.queryForList(
                        contains("FROM labour_temp_attachment"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of("L9"));
        open(1);
        GangStrengthService.Report r = service.report(context, 5L, YearMonth.of(2026, 9));
        verify(policy).requireRecordAt(context, "M-09", "VIEW", 7L);
        assertThat(r.yymm()).isEqualTo("202609");
        assertThat(r.gangName()).isEqualTo("Okhla Gang 1");
        assertThat(r.days()).as("the day without strength is left out").hasSize(1);
        GangStrengthService.Day d = r.days().get(0);
        assertThat(d.date()).isEqualTo(DAY);
        assertThat(d.shortBy()).isEqualTo(1);
        assertThat(d.absent()).containsExactly("L2");
        assertThat(d.attached()).containsExactly("L9");
        assertThat(d.alertOpen()).isTrue();
    }

    @Test
    void SRS_2_48_theDepotManagerClosesAnOpenAlertWithAReason() {
        defect(() -> service.close(context, 5L, null, "x"), "DATE");
        defect(() -> service.close(context, 5L, DAY, null), "REASON");
        defect(() -> service.close(context, 5L, DAY, " "), "REASON");
        defect(() -> service.close(context, 5L, DAY, "x".repeat(501)), "REASON");
        open(0);
        defect(() -> service.close(context, 5L, DAY, "Rake late"), "NO_OPEN_SHORTFALL");
        open(null);
        defect(() -> service.close(context, 5L, DAY, "Rake late"), "NO_OPEN_SHORTFALL");
        open(1);
        when(worklist.clear(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(1);
        assertThat(
                        service.close(
                                context,
                                5L,
                                DAY,
                                " ".repeat(2) + "Pool labour sent " + "x".repeat(400)))
                .isEqualTo(1);
        verify(policy, org.mockito.Mockito.atLeastOnce())
                .requireRecordAt(context, "M-09", "APPROVE", 7L);
        verify(worklist)
                .clear(
                        eq("dm.okhla@fci-demo.test"),
                        eq("GANG_SHORTFALL"),
                        isNull(),
                        isNull(),
                        eq("gang 5 2026-09-14"),
                        contains("Closed by the Depot Manager: Pool labour sent"));
        verify(audit).record(eq("dm.okhla@fci-demo.test"), any(AuditService.Entry.class));
    }
}
