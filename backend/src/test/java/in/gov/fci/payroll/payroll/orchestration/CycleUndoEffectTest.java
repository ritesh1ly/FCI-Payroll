package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.api.UndoableTransaction;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-15-3 / SRS-15-10, SRS-G-21 (plan 05 phase 05e): the decisions the authorised undo ignores or
 * refuses before touching anything: another outcome or entity, a route that asked no OTP, a kind no
 * module defines any more, a transaction gone or moved to another cycle. The applied path is proved
 * end to end by {@code CycleUndoIT}.
 */
class CycleUndoEffectTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CycleUndo undo = mock(CycleUndo.class);
    private final CycleLeases leases = mock(CycleLeases.class);
    private final AuditService audit = mock(AuditService.class);
    private final CycleUndoEffect effect =
            new CycleUndoEffect(jdbc, undo, leases, audit, JsonMapper.builder().build());

    private static ApprovalDecided decided(ApprovalStatus outcome, String entity, Long otp) {
        return new ApprovalDecided(
                70L,
                "C-02",
                entity,
                "it_payment:P-1",
                outcome,
                "",
                "{\"cycleInstanceId\":9,\"period\":\"2026-08\",\"entity\":\"it_payment\","
                        + "\"key\":\"P-1\",\"reason\":\"Twice\"}",
                "sysadmin",
                Instant.parse("2026-10-25T06:30:00Z"),
                otp);
    }

    private static void refusedWith(Runnable call, Object code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(FciException.class)
                .satisfies(
                        e ->
                                org.assertj.core.api.Assertions.assertThat(
                                                ((FciException) e).getErrorCode())
                                        .isEqualTo(code));
    }

    @Test
    void SRS_15_3_otherOutcomesAndEntitiesAreIgnored() {
        effect.on(decided(ApprovalStatus.REJECTED, CycleUndo.UNDO_ENTITY, 5L));
        effect.on(decided(ApprovalStatus.APPROVED, "cycle_unfreeze", 5L));
        verifyNoInteractions(jdbc, undo, leases, audit);
    }

    @Test
    void SRS_G_21_aRouteWithoutTheAdministratorsOtpFailsTheDecision() {
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                CycleUndo.UNDO_ENTITY,
                                                null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("C02_UNDO");
        verifyNoInteractions(jdbc, leases, audit);
    }

    @Test
    void SRS_15_3_aKindNoLongerDefinedOrATransactionGoneOrElsewhereIsRefused() {
        when(undo.kind("it_payment")).thenReturn(Optional.empty());
        refusedWith(
                () -> effect.on(decided(ApprovalStatus.APPROVED, CycleUndo.UNDO_ENTITY, 5L)),
                ApiErrorCode.CYCLE_UNDO_TYPE);

        UndoableTransaction kind = mock(UndoableTransaction.class);
        when(undo.kind("it_payment")).thenReturn(Optional.of(kind));
        when(kind.find("P-1")).thenReturn(Optional.empty());
        refusedWith(
                () -> effect.on(decided(ApprovalStatus.APPROVED, CycleUndo.UNDO_ENTITY, 5L)),
                CommonErrorCode.NOT_FOUND);

        // SRS-15-6: no instance undoes for another.
        when(kind.find("P-1"))
                .thenReturn(Optional.of(new UndoableTransaction.Target(10L, "COMPUTED", "x")));
        refusedWith(
                () -> effect.on(decided(ApprovalStatus.APPROVED, CycleUndo.UNDO_ENTITY, 5L)),
                CommonErrorCode.NOT_FOUND);
        verifyNoInteractions(leases, audit);
    }

    /**
     * SRS-15-3 / SRS-15-10: the authoriser holds the cycle's lease under the login cut to the 50
     * characters of the *_by columns.
     */
    @Test
    void SRS_15_3_theAuthoriserHoldsTheLeaseUnderTheCutLogin() {
        UndoableTransaction kind = mock(UndoableTransaction.class);
        when(undo.kind("it_payment")).thenReturn(Optional.of(kind));
        when(kind.find("P-1"))
                .thenReturn(Optional.of(new UndoableTransaction.Target(9L, "COMPUTED", "x")));
        String login = "a".repeat(60);
        effect.on(
                new ApprovalDecided(
                        70L,
                        "C-02",
                        CycleUndo.UNDO_ENTITY,
                        "it_payment:P-1",
                        ApprovalStatus.APPROVED,
                        "",
                        "{\"cycleInstanceId\":9,\"period\":\"2026-08\",\"entity\":\"it_payment\","
                                + "\"key\":\"P-1\",\"reason\":\"Twice\"}",
                        login,
                        Instant.parse("2026-10-25T06:30:00Z"),
                        5L));
        org.mockito.Mockito.verify(leases)
                .acquire(9L, CycleLeases.Action.UNDO, "a".repeat(50), null);
        org.mockito.Mockito.verify(kind).undo("P-1", "Twice", login, 70L);
    }
}
