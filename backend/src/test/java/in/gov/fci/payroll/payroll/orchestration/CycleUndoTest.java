package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.UndoableTransaction;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-15-3 / SRS-15-6 (plan 05 D-10): the undoable kinds come from the modules that register them,
 * one per entity; a session without an office, or an office without a cycle unit, reaches neither
 * the undo request nor the register.
 */
class CycleUndoTest {

    private final OrgDirectory org = mock(OrgDirectory.class);
    private final UndoableTransaction payments = mock(UndoableTransaction.class);

    private CycleUndo undo() {
        when(payments.entity()).thenReturn("it_payment");
        return new CycleUndo(
                mock(JdbcTemplate.class),
                org,
                mock(Authorization.class),
                mock(MakerChecker.class),
                mock(AuditService.class),
                mock(CycleLeases.class),
                JsonMapper.builder().build(),
                List.of(payments));
    }

    private static AccessContext at(Long location) {
        return new AccessContext(
                1L,
                "maker@x",
                "Maker",
                "s",
                "P",
                location,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of("PAYROLL_MAKER"),
                Set.of(1L));
    }

    @Test
    void SRS_15_3_onlyARegisteredKindIsUndoable() {
        CycleUndo undo = undo();
        assertThat(undo.kind("it_payment")).contains(payments);
        assertThat(undo.kind("mdp_transaction")).isEmpty();
    }

    @Test
    void SRS_15_6_aSessionWithoutAnOfficeOrACycleUnitReachesNothing() {
        CycleUndo undo = undo();
        assertThatThrownBy(
                        () ->
                                undo.requestUndo(
                                        at(null), YearMonth.of(2026, 8), "it_payment", "P", "x"))
                .isInstanceOf(FciException.class)
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode())
                                        .isEqualTo(CommonErrorCode.ACCESS_DENIED));
        when(org.cycleUnitOf(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> undo.register(at(5L), new PageSpec(0, 20)))
                .isInstanceOf(FciException.class)
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.CYCLE_NO_UNIT));
    }
}
