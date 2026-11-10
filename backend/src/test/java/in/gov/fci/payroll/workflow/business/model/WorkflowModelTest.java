package in.gov.fci.payroll.workflow.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.approval.ActionType;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Approval routes and decisions (R-MC; SRS-G-1): an action is active only inside its dates, an
 * HQ-only action, and a decision is never the maker's own, needs a reason unless it approves, and
 * records the level and the checker's role.
 */
class WorkflowModelTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");

    private static WorkflowAction action(
            WorkflowAction.Status status, LocalDate to, WorkflowAction.OfficeLevel level) {
        WorkflowAction a = BeanUtils.instantiateClass(WorkflowAction.class);
        ReflectionTestUtils.setField(a, "status", status);
        ReflectionTestUtils.setField(a, "effectiveFrom", FROM);
        ReflectionTestUtils.setField(a, "effectiveTo", to);
        ReflectionTestUtils.setField(a, "officeLevel", level);
        return a;
    }

    @Test
    void R_MC_anActionIsActiveInsideItsDatesBothEndsIncluded() {
        WorkflowAction a =
                action(
                        WorkflowAction.Status.ACTIVE,
                        FROM.plusDays(30),
                        WorkflowAction.OfficeLevel.DO);
        assertThat(a.isActiveOn(FROM.minusDays(1))).isFalse();
        assertThat(a.isActiveOn(FROM)).isTrue();
        assertThat(a.isActiveOn(FROM.plusDays(30))).isTrue();
        assertThat(a.isActiveOn(FROM.plusDays(31))).isFalse();
        assertThat(
                        action(WorkflowAction.Status.ACTIVE, null, WorkflowAction.OfficeLevel.DO)
                                .isActiveOn(FROM.plusYears(9)))
                .isTrue();
        for (WorkflowAction.Status s : WorkflowAction.Status.values()) {
            if (s != WorkflowAction.Status.ACTIVE) {
                assertThat(action(s, null, WorkflowAction.OfficeLevel.DO).isActiveOn(FROM))
                        .as(s.name())
                        .isFalse();
            }
        }
    }

    @Test
    void R_MC_onlyAnHqActionIsHeadquartersOnly() {
        for (WorkflowAction.OfficeLevel l : WorkflowAction.OfficeLevel.values()) {
            assertThat(action(WorkflowAction.Status.ACTIVE, null, l).isHeadquartersOnly())
                    .as(l.name())
                    .isEqualTo(l == WorkflowAction.OfficeLevel.HQ);
        }
    }

    private static ApprovalItem item() {
        ApprovalItem item =
                ApprovalItem.submit(
                        "I-20",
                        3L,
                        2,
                        "off_cycle_payment",
                        "7",
                        ActionType.CREATE,
                        "{}",
                        FROM,
                        "maker.delhi",
                        "r",
                        null,
                        null,
                        NOW);
        ReflectionTestUtils.setField(item, "id", 41L);
        return item;
    }

    @Test
    void SRS_G_1_theMakerNeverDecidesTheOwnItem() {
        assertThatThrownBy(
                        () ->
                                ApprovalDecision.record(
                                        item(),
                                        null,
                                        "maker.delhi",
                                        "PAYROLL_CHECKER",
                                        ApprovalDecision.Kind.APPROVE,
                                        null,
                                        null,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Maker");
    }

    @Test
    void SRS_G_1_aReturnOrRejectionNeedsAReasonAnApprovalDoesNot() {
        for (ApprovalDecision.Kind kind :
                new ApprovalDecision.Kind[] {
                    ApprovalDecision.Kind.RETURN, ApprovalDecision.Kind.REJECT
                }) {
            assertThatThrownBy(
                            () ->
                                    ApprovalDecision.record(
                                            item(),
                                            null,
                                            "ddo",
                                            "PAYROLL_CHECKER",
                                            kind,
                                            null,
                                            null,
                                            NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    ApprovalDecision.record(
                                            item(),
                                            null,
                                            "ddo",
                                            "PAYROLL_CHECKER",
                                            kind,
                                            "  ",
                                            null,
                                            NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(
                            ApprovalDecision.record(
                                            item(),
                                            null,
                                            "ddo",
                                            "PAYROLL_CHECKER",
                                            kind,
                                            "wrong month",
                                            null,
                                            NOW)
                                    .getReason())
                    .isEqualTo("wrong month");
        }
        ApprovalDecision approved =
                ApprovalDecision.record(
                        item(),
                        null,
                        "ddo",
                        "PAYROLL_CHECKER",
                        ApprovalDecision.Kind.APPROVE,
                        null,
                        5L,
                        NOW);
        assertThat(approved.getDecision()).isEqualTo(ApprovalDecision.Kind.APPROVE);
        assertThat(approved.getApprovalId()).isEqualTo(41L);
        assertThat(approved.getLevelNo()).isEqualTo(item().getCurrentLevel());
        assertThat(approved.getDecidedBy()).isEqualTo("ddo");
        assertThat(approved.getActingRoleCode()).isEqualTo("PAYROLL_CHECKER");
        assertThat(approved.getDecidedAt()).isEqualTo(NOW);
        assertThat(ReflectionTestUtils.getField(approved, "submittedBy")).isEqualTo("maker.delhi");
        assertThat(ReflectionTestUtils.getField(approved, "otpChallengeId")).isEqualTo(5L);
        assertThat(ReflectionTestUtils.getField(approved, "workflowLevelId")).isNull();
    }

    @Test
    void SRS_G_1_aDecisionRecordsItsWorkflowLevelAndNeedsARole() {
        WorkflowLevel level = BeanUtils.instantiateClass(WorkflowLevel.class);
        ReflectionTestUtils.setField(level, "id", 12L);
        ApprovalDecision d =
                ApprovalDecision.record(
                        item(),
                        level,
                        "ddo",
                        "PAYROLL_CHECKER",
                        ApprovalDecision.Kind.APPROVE,
                        null,
                        null,
                        NOW);
        assertThat(ReflectionTestUtils.getField(d, "workflowLevelId")).isEqualTo(12L);
        assertThatThrownBy(
                        () ->
                                ApprovalDecision.record(
                                        item(),
                                        null,
                                        "ddo",
                                        null,
                                        ApprovalDecision.Kind.APPROVE,
                                        null,
                                        null,
                                        NOW))
                .isInstanceOf(NullPointerException.class);
        ApprovalItem unsaved = item();
        ReflectionTestUtils.setField(unsaved, "id", null);
        assertThatThrownBy(
                        () ->
                                ApprovalDecision.record(
                                        unsaved,
                                        null,
                                        "ddo",
                                        "R",
                                        ApprovalDecision.Kind.APPROVE,
                                        null,
                                        null,
                                        NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void SRS_22_12_aProposedRouteIsPendingUntilApprovedAndEndsTheDayBeforeItsSuccessor() {
        WorkflowAction a =
                WorkflowAction.propose(
                        "M08_EDIT",
                        "M-08 rule version",
                        "RULES",
                        "M-08",
                        WorkflowAction.OfficeLevel.HQ,
                        2,
                        null,
                        false,
                        48,
                        2,
                        FROM);
        assertThat(a.getStatus()).isEqualTo(WorkflowAction.Status.PENDING_APPROVAL);
        assertThat(a.isOtpRequired()).isFalse();
        assertThat(a.isActiveOn(FROM)).isFalse();
        LocalDate approvedOn = FROM.plusDays(3);
        a.approve(approvedOn);
        assertThat(a.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        assertThat(a.getEffectiveFrom()).isEqualTo(approvedOn);
        assertThatThrownBy(() -> a.approve(approvedOn)).isInstanceOf(IllegalStateException.class);
        // A cancel of an approved route is a no-op: only a pending proposal is cancelled.
        a.cancel();
        assertThat(a.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        // A successor from a later day: this one stays in force until the day before.
        LocalDate successor = approvedOn.plusDays(10);
        a.endBefore(successor, approvedOn);
        assertThat(a.getEffectiveTo()).isEqualTo(successor.minusDays(1));
        assertThat(a.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        // Once that day has passed it is SUPERSEDED; on the day itself it is still in force.
        a.endBefore(successor, successor.minusDays(1));
        assertThat(a.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        a.endBefore(successor, successor);
        assertThat(a.getStatus()).isEqualTo(WorkflowAction.Status.SUPERSEDED);
    }

    @Test
    void SRS_G_13_bulkApprovalIsOffUntilX01AllowsItAndCanBeWithdrawn() {
        WorkflowAction a =
                WorkflowAction.propose(
                        "M08_EDIT",
                        "M-08 rule version",
                        "RULES",
                        "M-08",
                        WorkflowAction.OfficeLevel.HQ,
                        2,
                        null,
                        false,
                        48,
                        2,
                        FROM);
        assertThat(a.isBulkApprovalAllowed()).isFalse();
        assertThat(a.allowBulkApproval(true)).isSameAs(a);
        assertThat(a.isBulkApprovalAllowed()).isTrue();
        assertThat(a.allowBulkApproval(false).isBulkApprovalAllowed()).isFalse();
    }

    @Test
    void SRS_22_17_eachLevelIsNamedByItsPlaceInTheRoute() {
        assertThat(
                        WorkflowLevel.of(
                                        1L,
                                        1,
                                        "PAYROLL_MAKER",
                                        WorkflowAction.OfficeLevel.HQ,
                                        false)
                                .getName())
                .isEqualTo(WorkflowLevel.Name.MAKER);
        assertThat(
                        WorkflowLevel.of(
                                        1L,
                                        2,
                                        "PAYROLL_CHECKER",
                                        WorkflowAction.OfficeLevel.HQ,
                                        true)
                                .getName())
                .isEqualTo(WorkflowLevel.Name.CHECKER);
        WorkflowLevel third =
                WorkflowLevel.of(1L, 3, "AGM_CPF", WorkflowAction.OfficeLevel.HQ, true);
        assertThat(third.getName()).isEqualTo(WorkflowLevel.Name.APPROVER);
        assertThat(third.getLevelNo()).isEqualTo(3);
        assertThat(third.getRoleCode()).isEqualTo("AGM_CPF");
    }

    @Test
    void SRS_22_12_aFutureRouteReplacedBeforeItStartsIsNeverInForce() {
        WorkflowAction future =
                WorkflowAction.propose(
                        "M08_EDIT",
                        "M-08 rule version",
                        "RULES",
                        "M-08",
                        WorkflowAction.OfficeLevel.HQ,
                        2,
                        null,
                        false,
                        48,
                        3,
                        FROM.plusDays(30));
        future.approve(FROM.plusDays(30));
        // A successor approved today starts on day 10, before this one's day 30: never in force,
        // and its dates stay (an end before the start is no range: chk_appr_wf_span).
        assertThat(future.endBefore(FROM.plusDays(10), FROM)).isFalse();
        assertThat(future.getStatus()).isEqualTo(WorkflowAction.Status.SUPERSEDED);
        assertThat(future.getEffectiveFrom()).isEqualTo(FROM.plusDays(30));
        assertThat(future.getEffectiveTo()).isNull();
        assertThat(future.isActiveOn(FROM.plusDays(30))).isFalse();
        assertThat(future.isActiveOn(FROM.plusDays(5))).isFalse();
    }

    @Test
    void SRS_22_12_aSuccessorFromTheSameFirstDayAlsoReplacesItBeforeItStarts() {
        WorkflowAction sameDay =
                WorkflowAction.propose(
                        "M08_EDIT",
                        "M-08 rule version",
                        "RULES",
                        "M-08",
                        WorkflowAction.OfficeLevel.HQ,
                        2,
                        null,
                        false,
                        48,
                        3,
                        FROM.plusDays(30));
        sameDay.approve(FROM.plusDays(30));
        assertThat(sameDay.endBefore(FROM.plusDays(30), FROM)).isFalse();
        assertThat(sameDay.getStatus()).isEqualTo(WorkflowAction.Status.SUPERSEDED);
        assertThat(sameDay.getEffectiveTo()).isNull();
        // One day later is a real end: the version is in force on its first day only.
        WorkflowAction oneDay =
                WorkflowAction.propose(
                        "M08_EDIT",
                        "x",
                        "RULES",
                        "M-08",
                        WorkflowAction.OfficeLevel.HQ,
                        2,
                        null,
                        false,
                        48,
                        4,
                        FROM.plusDays(30));
        oneDay.approve(FROM.plusDays(30));
        assertThat(oneDay.endBefore(FROM.plusDays(31), FROM)).isTrue();
        assertThat(oneDay.getEffectiveTo()).isEqualTo(FROM.plusDays(30));
        assertThat(oneDay.getStatus()).isEqualTo(WorkflowAction.Status.ACTIVE);
        assertThat(oneDay.isActiveOn(FROM.plusDays(30))).isTrue();
    }

    @Test
    void SRS_22_12_aCancelledProposalNeverComesIntoForceAndAnOtpActionRequiresTheOtp() {
        WorkflowAction pending =
                WorkflowAction.propose(
                        "C02_LOCK",
                        "Lock",
                        "PAYROLL",
                        "C-02",
                        WorkflowAction.OfficeLevel.DO,
                        1,
                        in.gov.fci.payroll.platform.security.OtpGatedAction.CYCLE_FREEZE_LOCK,
                        true,
                        null,
                        1,
                        FROM);
        assertThat(pending.isOtpRequired()).isTrue();
        pending.cancel();
        assertThat(pending.getStatus()).isEqualTo(WorkflowAction.Status.CANCELLED);
        assertThatThrownBy(() -> pending.approve(FROM)).isInstanceOf(IllegalStateException.class);
    }
}
