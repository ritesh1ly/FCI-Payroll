package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.payroll.api.NetPayFloorChecked;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SRS-15-13 and the Rule source (SRS l.2028): with no net-pay floor version in force the records
 * are held in Exceptions & Holds (C-08) until one is activated, then released.
 */
class NetPayFloorHoldsTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 10, 1);

    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final NetPayFloorHolds holds = new NetPayFloorHolds(worklist);

    @Test
    void SRS_15_13_noFloorVersionInForceHoldsTheCycleOnC08AsBlockingOnG6() {
        holds.on(new NetPayFloorChecked(70L, 7L, "STAFF", PERIOD, false));
        ArgumentCaptor<ExceptionWorklist.NewEntry> entry =
                ArgumentCaptor.forClass(ExceptionWorklist.NewEntry.class);
        verify(worklist).raise(eq("system.payroll"), entry.capture());
        ExceptionWorklist.NewEntry e = entry.getValue();
        assertThat(e.cycleInstanceId()).isEqualTo(70L);
        assertThat(e.employeeId()).isNull();
        assertThat(e.exceptionCode()).isEqualTo("NET_PAY_FLOOR_NOT_CONFIGURED");
        assertThat(e.severity()).isEqualTo("BLOCKING");
        assertThat(e.blocksGate()).isEqualTo("G6");
        assertThat(e.fieldSource()).isEqualTo("RULE");
        assertThat(e.correctionScreen()).isEqualTo("M-14");
        assertThat(e.detail())
                .isEqualTo(
                        "No net-pay floor version of STAFF is in force on 2026-10-01: held until"
                                + " the Rule Administrator adds the version and the Rule Approver"
                                + " activates it.");
        verify(worklist, never())
                .clear(anyString(), anyString(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void SRS_15_13_aLaterRunWithAFloorVersionInForceReleasesTheHoldNamingTheRun() {
        holds.on(new NetPayFloorChecked(70L, 8L, "STAFF", PERIOD, true));
        verify(worklist)
                .clear(
                        eq("system.payroll"),
                        eq("NET_PAY_FLOOR_NOT_CONFIGURED"),
                        eq(70L),
                        isNull(),
                        eq("floor_value"),
                        eq("Net-pay floor version in force for Actual run 8"));
        verify(worklist, never()).raise(anyString(), any());
    }
}
