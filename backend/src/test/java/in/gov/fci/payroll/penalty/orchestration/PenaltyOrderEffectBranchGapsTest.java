package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRecoveryLine;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The last approval of an I-06 order (SRS-14-10, SRS-14-15; US-GAP-229): an item submitted before
 * the Penalty ID was given at entry carries none and is numbered now; a below-floor order writes
 * the floor override only when the floor in force was recorded with it (none without an M-14 floor
 * in force); a breach with its floor writes the override with the approval that allowed it.
 */
class PenaltyOrderEffectBranchGapsTest {

    private final PenaltyWriter writer = mock(PenaltyWriter.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PenaltyRepositories.Orders orders = mock(PenaltyRepositories.Orders.class);
    private final PenaltyRepositories.Lines lines = mock(PenaltyRepositories.Lines.class);
    private final PenaltyOrderEffect effect =
            new PenaltyOrderEffect(writer, jdbc, JsonMapper.builder().build(), orders, lines);

    PenaltyOrderEffectBranchGapsTest() {
        PenaltyOrder order = mock(PenaltyOrder.class);
        when(order.getId()).thenReturn(40L);
        when(writer.create(any(), anyString(), anyLong(), any())).thenReturn(order);
    }

    private void approve(String extra) {
        effect.on(
                new ApprovalDecided(
                        8L,
                        "I-06",
                        PenaltyOrderService.ENTITY,
                        "2|PEN-2026-00012",
                        ApprovalStatus.APPROVED,
                        null,
                        "{\"employeeId\":2,\"nature\":\"RECOVERY\",\"orderReference\":\"O-1\","
                                + "\"effectiveDate\":\"2026-04-01\",\"amount\":1200,"
                                + "\"instalments\":2,\"startYymm\":\"202604\","
                                + "\"backdated\":false"
                                + extra
                                + "}",
                        "penalty.checker@x",
                        Instant.parse("2026-04-12T05:00:00Z")));
    }

    @Test
    void SRS_14_15_anItemWithoutAPenaltyIdGivenAtEntryIsNumberedNow() {
        approve(",\"penaltyRef\":null");
        ArgumentCaptor<PenaltyWriter.Approved> approved = ArgumentCaptor.captor();
        verify(writer).create(approved.capture(), eq("penalty.checker@x"), eq(8L), any());
        assertThat(approved.getValue().penaltyRef()).isNull();
        assertThat(approved.getValue().amount()).isEqualByComparingTo("1200");
    }

    @Test
    void SRS_14_10_aBelowFloorOrderWithoutARecordedFloorWritesNoOverride() {
        approve(",\"penaltyRef\":\"PEN-2026-00012\",\"belowFloor\":true,\"floor\":null");
        approve(",\"penaltyRef\":\"PEN-2026-00012\",\"belowFloor\":true");
        verifyNoInteractions(jdbc);
    }

    @Test
    void SRS_14_10_aBreachWithItsFloorWritesTheOverrideWithItsApproval() {
        approve(
                ",\"penaltyRef\":\"PEN-2026-00012\",\"belowFloor\":true,\"floor\":1000,"
                        + "\"netAfter\":400");
        verify(jdbc)
                .update(
                        contains("INSERT INTO net_pay_floor_override"),
                        eq(2L),
                        eq("202604"),
                        eq(new BigDecimal("400")),
                        eq(new BigDecimal("1000")),
                        eq(8L));
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                8L,
                "I-06",
                entity,
                "2|PEN-2026-00012",
                outcome,
                null,
                payload,
                "penalty.checker@x",
                Instant.parse("2026-04-12T05:00:00Z"));
    }

    /** PN-17: a returned or rejected request writes nothing; it stays in its approval item. */
    @Test
    void SRS_14_1_aNotApprovedDecisionOrAnotherEntityWritesNothing() {
        for (ApprovalStatus outcome :
                new ApprovalStatus[] {
                    ApprovalStatus.REJECTED,
                    ApprovalStatus.RETURNED,
                    ApprovalStatus.WITHDRAWN,
                    ApprovalStatus.PENDING_APPROVAL
                }) {
            effect.on(decided(PenaltyOrderService.ENTITY, outcome, "{bad"));
        }
        effect.on(decided("loan_account", ApprovalStatus.APPROVED, "{bad"));

        verifyNoInteractions(writer, jdbc, orders, lines);
    }

    @Test
    void SRS_14_1_anUnreadablePayloadNamesTheApprovalAndWritesNothing() {
        for (String broken : new String[] {"{bad", "{\"employeeId\":"}) {
            assertThatThrownBy(
                            () ->
                                    effect.on(
                                            decided(
                                                    PenaltyOrderService.ENTITY,
                                                    ApprovalStatus.APPROVED,
                                                    broken)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unreadable penalty order 8")
                    .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
        }

        verifyNoInteractions(jdbc);
    }

    /** SRS l.2995: an order deferred to F&F recovers nothing in service and writes no override. */
    @Test
    void SRS_14_10_anOrderDeferredToFnfPausesAndDefersEveryInstalment() {
        PenaltyOrder order = mock(PenaltyOrder.class);
        when(order.getId()).thenReturn(41L);
        when(writer.create(any(), anyString(), anyLong(), any())).thenReturn(order);
        PenaltyRecoveryLine first = mock(PenaltyRecoveryLine.class);
        PenaltyRecoveryLine second = mock(PenaltyRecoveryLine.class);
        when(lines.findByOrderIdOrderByPayrollYymm(41L)).thenReturn(List.of(first, second));

        approve(
                ",\"penaltyRef\":\"PEN-2026-00012\",\"deferToFnf\":true,\"belowFloor\":true,"
                        + "\"floor\":1000,\"netAfter\":400");

        verify(first).deferred(PenaltyOrderEffect.DEFERRED_AT_ENTRY);
        verify(second).deferred(PenaltyOrderEffect.DEFERRED_AT_ENTRY);
        verify(lines).save(first);
        verify(lines).save(second);
        verify(order).status(PenaltyOrder.Status.PAUSED_FNF);
        verify(orders).save(order);
        verifyNoInteractions(jdbc);
    }

    @Test
    void SRS_14_10_anOrderInServiceStaysActiveAndDefersNothing() {
        approve(",\"penaltyRef\":\"PEN-2026-00012\",\"deferToFnf\":false");

        verify(lines, never()).save(any());
        verify(orders, never()).save(any());
        verifyNoInteractions(jdbc);
    }
}
