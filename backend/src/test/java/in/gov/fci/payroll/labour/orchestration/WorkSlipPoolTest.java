package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.labour.orchestration.WorkSlipService.Entry;
import in.gov.fci.payroll.labour.orchestration.WorkSlipService.RowEntry;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * SRS-2-46 / I-02 l.4621: the Ancillary Pool is booked on I-02 without bag fields: a bag-based
 * operation, a bag type, a number of bags or a stack height on a pool slip is refused; a handling
 * gang keeps them. The pool is a work gang only for attendance and work-slips.
 */
class WorkSlipPoolTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);
    private static final long BAG_OP = 1L;
    private static final long TIME_OP = 4L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final WorkSlipService service =
            new WorkSlipService(
                    jdbc,
                    gangs,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    parameters,
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    mock(ExceptionWorklist.class));

    private static LabourGangs.Gang gang(String type) {
        return new LabourGangs.Gang(
                6L,
                "DP-DL01-G001",
                "Ancillary Pool",
                7L,
                "DP-DL01 - Okhla",
                "DEPT_LABOUR",
                "ACTIVE",
                LocalDate.of(2025, 7, 1),
                null,
                type);
    }

    @BeforeEach
    void wire() throws Exception {
        when(parameters.today()).thenReturn(TODAY);
        when(gangs.workGang(6L)).thenReturn(gang("ANCILLARY_POOL"));
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.OPEN));
        // Two operations of the payroll type: one bag-based, one by time.
        doAnswer(
                        inv -> {
                            RowCallbackHandler h = inv.getArgument(1);
                            for (long id : new long[] {BAG_OP, TIME_OP}) {
                                ResultSet rs = mock(ResultSet.class);
                                when(rs.getLong("operation_id")).thenReturn(id);
                                when(rs.getString("operation_code"))
                                        .thenReturn(id == BAG_OP ? "W2P" : "SWEEP");
                                when(rs.getString("operation_name")).thenReturn("op");
                                when(rs.getString("is_bag_based"))
                                        .thenReturn(id == BAG_OP ? "Y" : "N");
                                when(rs.getString("is_present_but_no_work")).thenReturn("N");
                                h.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("FROM operation_master"),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
    }

    private static RowEntry row(long op, Long bagType, Integer bags, Integer stack) {
        return new RowEntry(op, bagType, bags, stack, "09:00", "11:00", null, null, null);
    }

    private void refused(RowEntry row) {
        assertThatThrownBy(
                        () ->
                                service.save(
                                        context,
                                        6L,
                                        new Entry(DAY, false, List.of(row), List.of(13L))))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .isEqualTo("POOL_NO_BAGS"));
    }

    @Test
    void SRS_2_46_aPoolSlipTakesNoBagBasedOperationAndNoBagField() {
        refused(row(BAG_OP, null, null, null));
        refused(row(TIME_OP, 2L, null, null));
        refused(row(TIME_OP, null, 100, null));
        refused(row(TIME_OP, null, null, 8));
    }

    @Test
    // BR-LABR-005 / SRS-2-20: ancillary labour of the pool book a non-bag operation such as
    // Fumigation (a QC operation, Circular 23/2012); FCI's operation list is PENDING B-23.
    void SRS_2_46_BR_LABR_005_aPoolRowByTimeGoesOnToTheOtherChecksAndAHandlingGangKeepsItsBags() {
        // Past the pool check: the next refusal is another one (no bag type known).
        assertThatThrownBy(
                        () ->
                                service.save(
                                        context,
                                        6L,
                                        new Entry(
                                                DAY,
                                                false,
                                                List.of(row(TIME_OP, null, null, null)),
                                                List.of(13L))))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .isNotEqualTo("POOL_NO_BAGS"));
        when(gangs.workGang(6L)).thenReturn(gang("HANDLING"));
        assertThatThrownBy(
                        () ->
                                service.save(
                                        context,
                                        6L,
                                        new Entry(
                                                DAY,
                                                false,
                                                List.of(row(BAG_OP, 2L, 100, 8)),
                                                List.of(13L))))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .isNotEqualTo("POOL_NO_BAGS"));
        assertThat(gang("ANCILLARY_POOL").pool()).isTrue();
        assertThat(gang("HANDLING").pool()).isFalse();
        assertThat(
                        new LabourGangs.Gang(
                                        1L, "c", "n", 7L, "d", "DEPT_LABOUR", "ACTIVE", DAY, null)
                                .type())
                .isEqualTo("HANDLING");
    }
}
