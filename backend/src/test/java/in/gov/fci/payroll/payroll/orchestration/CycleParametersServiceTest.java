package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * M-10 Cycle Parameters panel (SRS-15-39; plan 05 D-23): the back-date window is the A-07
 * MAX_RETRO_PERIOD when FCI has configured it and none ("not configured") while it is unseeded; the
 * staff net-pay floor in force is shown when set and none otherwise; the authorities and checks are
 * read as configured.
 */
class CycleParametersServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final SystemParameters settings = mock(SystemParameters.class);
    private final NetPayFloors floors = mock(NetPayFloors.class);
    private final CycleParametersService service =
            new CycleParametersService(db.jdbc, mock(Authorization.class), settings, floors);

    CycleParametersServiceTest() {
        when(settings.today()).thenReturn(TODAY);
    }

    @Test
    void SRS_15_39_theBackDateWindowAndTheFloorAreShownWhenConfigured() {
        when(settings.configured(SystemParameters.Code.MAX_RETRO_PERIOD))
                .thenReturn(OptionalLong.of(6));
        when(floors.inForce("STAFF", TODAY))
                .thenReturn(
                        Optional.of(
                                new NetPayFloors.Floor(
                                        "ABSOLUTE_AMOUNT", new BigDecimal("5000"), true)));
        db.rows(
                "FROM approval_workflow_action a",
                cols("C02_LOCK", 1, "Checker", "PAYROLL_CHECKER", "DO", "Y"));
        CycleParametersService.Parameters p = service.parameters(mock(AccessContext.class));
        assertThat(p.backDateWindowMonths()).isEqualTo(6L);
        assertThat(p.staffNetPayFloor())
                .isEqualTo(
                        new CycleParametersService.Floor(
                                "ABSOLUTE_AMOUNT", new BigDecimal("5000"), true));
        assertThat(p.authorities())
                .containsExactly(
                        new CycleParametersService.Authority(
                                "C02_LOCK", 1, "Checker", "PAYROLL_CHECKER", "DO", true));
    }

    @Test
    void SRS_15_39_anUnseededWindowAndFloorAreShownAsNotConfigured() {
        when(settings.configured(SystemParameters.Code.MAX_RETRO_PERIOD))
                .thenReturn(OptionalLong.empty());
        when(floors.inForce("STAFF", TODAY)).thenReturn(Optional.empty());
        CycleParametersService.Parameters p = service.parameters(mock(AccessContext.class));
        assertThat(p.backDateWindowMonths()).isNull();
        assertThat(p.staffNetPayFloor()).isNull();
        assertThat(p.authorities()).isEmpty();
        assertThat(p.mdpHeads()).isEmpty();
        assertThat(p.checks()).isEmpty();
    }
}
