package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * C-01 gate acceptance, check acknowledgement and member review (SRS-15-11, SRS-15-17, SRS-15-39):
 * the refusals before any evaluation.
 */
class CycleGateServiceTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 4);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessContext context = mock(AccessContext.class);

    @SuppressWarnings("unchecked")
    private final CycleGateService service =
            new CycleGateService(
                    jdbc,
                    org,
                    mock(Authorization.class),
                    mock(AuditService.class),
                    mock(NetPayFloors.class),
                    Clock.systemUTC(),
                    mock(ObjectProvider.class));

    CycleGateServiceTest() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(40L);
        when(context.locationId()).thenReturn(41L);
        when(context.loginId()).thenReturn("maker.delhi");
        when(org.cycleUnitOf(41L)).thenReturn(Optional.of(unit));
    }

    private static void refused(ErrorCode code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    private void cycle(String status, String initiatedBy, String frozenBy) {
        Map<String, Object> row = new HashMap<>();
        row.put("CYCLE_INSTANCE_ID", 9L);
        row.put("CYCLE_STATUS", status);
        row.put("INITIATED_BY", initiatedBy);
        row.put("FROZEN_BY", frozenBy);
        when(jdbc.queryForList(anyString(), eq("202604"), eq(40L))).thenReturn(List.of(row));
    }

    @Test
    void SRS_15_17_onlyAnOverridableGateIsAccepted() {
        for (String gate : new String[] {"G9", "G0", "G1", "G3", "G4"}) {
            refused(ApiErrorCode.CYCLE_GATE_HARD, () -> service.accept(context, PERIOD, gate, "r"));
        }
        // SRS l.1525 (BMAD TR-007): G6 is overridable, but per member, never as a whole gate.
        refused(
                ApiErrorCode.CYCLE_GATE_PER_MEMBER,
                () -> service.accept(context, PERIOD, "G6", "r"));
        refused(ApiErrorCode.REASON_REQUIRED, () -> service.accept(context, PERIOD, "G5", " "));
        refused(
                CommonErrorCode.VALIDATION_FAILED,
                () -> service.accept(context, PERIOD, "G5", "ré"));
    }

    @Test
    void SRS_15_39_anUnknownCycleIsNotFoundAndALockedOneIsNotChanged() {
        when(jdbc.queryForList(anyString(), eq("202604"), eq(40L))).thenReturn(List.of());
        refused(CommonErrorCode.NOT_FOUND, () -> service.acknowledge(context, PERIOD, 3L, "r"));
        cycle("LOCKED", "other", null);
        refused(ApiErrorCode.CYCLE_STATE, () -> service.acknowledge(context, PERIOD, 3L, "r"));
        refused(
                ApiErrorCode.CYCLE_STATE,
                () -> service.reviewMember(context, PERIOD, 3L, true, "r"));
    }

    @Test
    void SRS_15_11_theCyclesMakerDoesNotReviewItsMembers() {
        cycle("FROZEN", "other", "maker.delhi");
        refused(
                ApiErrorCode.MAKER_CHECKER,
                () -> service.reviewMember(context, PERIOD, 3L, true, "r"));
        cycle("INITIATED", "maker.delhi", null);
        refused(
                ApiErrorCode.MAKER_CHECKER,
                () -> service.reviewMember(context, PERIOD, 3L, false, "r"));
    }

    /** SRS-G-29 / R-ASCII: a C-01 reason from the reason dialog (a text area) keeps line breaks. */
    @Test
    void SRS_G_29_aReasonWithLineBreaksIsAcceptedAndAControlCharacterIsNot() {
        assertThat(CycleGateService.reason(" Checked\r\nwith HQ ")).isEqualTo("Checked\r\nwith HQ");
        assertThat(CycleGateService.reason("x".repeat(CycleGateService.MAX_REASON)))
                .hasSize(CycleGateService.MAX_REASON);
        assertThatThrownBy(
                        () -> CycleGateService.reason("x".repeat(CycleGateService.MAX_REASON + 1)))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> CycleGateService.reason("a\u0000b"))
                .isInstanceOf(FciException.class);
    }
}
