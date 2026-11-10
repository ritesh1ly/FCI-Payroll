package in.gov.fci.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ScopeType;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Login and maker-checker rules that live in the entities themselves. */
class LoginAndWorkflowRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T05:30:00Z");

    // SC-01-0147: failed password attempts count towards the lockout setting.
    @Test
    void theFifthFailureLocksForFifteenMinutesAndSuccessResetsTheCount() {
        AppUserCredential credential = AppUserCredential.initial(1L, "{bcrypt}x", NOW);
        for (int i = 1; i <= 4; i++) {
            assertThat(credential.registerFailure(5, Duration.ofMinutes(15), NOW)).isFalse();
        }
        assertThat(credential.isLockedAt(NOW)).isFalse();
        assertThat(credential.registerFailure(5, Duration.ofMinutes(15), NOW)).isTrue();
        assertThat(credential.isLockedAt(NOW.plusSeconds(899))).isTrue();
        assertThat(credential.isLockedAt(NOW.plusSeconds(900))).isFalse();
        credential.registerSuccess();
        assertThat(credential.getFailedAttempts()).isZero();
        assertThat(credential.getLockedUntil()).isNull();
    }

    @Test
    void newAndAdministratorResetPasswordsMustBeChangedButOwnChangesNeedNot() {
        AppUserCredential credential = AppUserCredential.initial(1L, "{bcrypt}x", NOW);
        assertThat(credential.isMustChange()).isTrue();
        credential.replace("{argon2}y", NOW);
        assertThat(credential.isMustChange()).isFalse();
        assertThat(credential.getHashAlgorithm()).isEqualTo("ARGON2ID");
        credential.resetByAdministrator("{argon2}z", NOW);
        assertThat(credential.isMustChange()).isTrue();
        assertThat(credential.isExpired(NOW.plus(Duration.ofDays(91)), Duration.ofDays(90)))
                .isTrue();
    }

    // SC-01-0146: the User ID is the official e-mail address.
    @Test
    void loginIdsAreTheOfficialEmailTrimmedAndLowerCase() {
        assertThat(AppUser.normaliseLogin("  Maker.Delhi@FCI-Demo.test "))
                .isEqualTo("maker.delhi@fci-demo.test");
        AppUser user =
                AppUser.create(
                        "X@Y.in",
                        null,
                        "X",
                        AppUser.Category.OPERATIONS,
                        null,
                        "X@Y.in",
                        1L,
                        "admin",
                        NOW);
        user.changeStatus(AppUser.Status.DEACTIVATED, AppUser.DeactivationReason.SEPARATION, NOW);
        assertThatThrownBy(() -> user.changeStatus(AppUser.Status.ACTIVE, null, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ApprovalItem item(int levels) {
        return ApprovalItem.submit(
                "M-01",
                1L,
                levels,
                "employee",
                "106620",
                ActionType.UPDATE,
                "{}",
                null,
                "hr@fci-demo.test",
                "r",
                null,
                null,
                NOW);
    }

    @Test
    void SRS_G_1_theMakerIsNeverTheCheckerAndADecisionIsFinal() {
        ApprovalItem item = item(2);
        assertThatThrownBy(
                        () -> item.decide(ApprovalStatus.APPROVED, "hr@fci-demo.test", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        item.decide(ApprovalStatus.APPROVED, "ddo@fci-demo.test", "ok", NOW);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThatThrownBy(
                        () ->
                                item.decide(
                                        ApprovalStatus.REJECTED, "other@fci-demo.test", "no", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void SRS_G_1_twoOrThreeLevelsAndOnlyTheLastLevelIsFinal() {
        assertThatThrownBy(() -> item(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(4)).isInstanceOf(IllegalArgumentException.class);
        ApprovalItem three = item(3);
        assertThat(three.isAtFinalLevel()).isFalse();
        three.advance();
        assertThat(three.isAtFinalLevel()).isTrue();
        assertThat(three.isPending()).isTrue();
        assertThatThrownBy(three::advance).isInstanceOf(IllegalStateException.class);
    }

    // SC-01-0056: a Checker cannot return an item without a reason.
    @Test
    void aReturnNeedsAReasonAndPendingIsNotADecision() {
        ApprovalItem item = item(2);
        assertThatThrownBy(
                        () -> item.decide(ApprovalStatus.RETURNED, "ddo@fci-demo.test", " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> item.decide(ApprovalStatus.RETURNED, "ddo@fci-demo.test", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                item.decide(
                                        ApprovalStatus.PENDING_APPROVAL,
                                        "ddo@fci-demo.test",
                                        "x",
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
        item.decide(ApprovalStatus.RETURNED, "ddo@fci-demo.test", "order copy missing", NOW);
        assertThat(item.getStatus()).isEqualTo(ApprovalStatus.RETURNED);
    }

    @Test
    void BR_APPR_007_noApproverInScopeIsFlaggedOnce() {
        ApprovalItem item = item(2);
        item.flagNoApprover(NOW);
        item.flagNoApprover(NOW.plusSeconds(60));
        assertThat(item.isNoApproverInScope()).isTrue();
        assertThat(item.getFlaggedToHqAdminAt()).isEqualTo(NOW);
        assertThat(item.isPending()).as("never approved automatically").isTrue();
    }

    // SC-02-0033: a consolidated view only on a Headquarters-scoped group.
    @Test
    void onlyAHeadquartersGroupMayBeAConsolidatedView() {
        assertThatThrownBy(
                        () ->
                                AccessGroup.define(
                                        "AG-X",
                                        "X",
                                        1L,
                                        ScopeType.REGION,
                                        true,
                                        AccessMode.FULL,
                                        LocalDate.of(2026, 1, 1),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        AccessGroup hq =
                AccessGroup.define(
                        "AG-Y",
                        "Y",
                        1L,
                        ScopeType.HEADQUARTERS,
                        true,
                        AccessMode.FULL,
                        LocalDate.of(2026, 1, 1),
                        null,
                        null);
        hq.endOn(LocalDate.of(2026, 6, 30));
        assertThat(hq.isActiveOn(LocalDate.of(2026, 6, 30))).isTrue();
        assertThat(hq.isActiveOn(LocalDate.of(2026, 7, 1))).isFalse();
    }

    @Test
    void SRS_22_2_aGroupKeepsItsAccessModeAndAGroupWithoutOneHasFullAccess() {
        for (AccessMode mode : AccessMode.values()) {
            AccessGroup g =
                    AccessGroup.define(
                            "AG-M",
                            "M",
                            1L,
                            ScopeType.REGION,
                            false,
                            mode,
                            LocalDate.of(2026, 1, 1),
                            null,
                            null);
            assertThat(g.getAccessMode()).as(mode.name()).isEqualTo(mode);
        }
        AccessGroup unset =
                AccessGroup.define(
                        "AG-N",
                        "N",
                        1L,
                        ScopeType.REGION,
                        false,
                        null,
                        LocalDate.of(2026, 1, 1),
                        null,
                        null);
        assertThat(unset.getAccessMode()).isEqualTo(AccessMode.FULL);
        // A group without an end date stays in force; never before its first day.
        assertThat(unset.isActiveOn(LocalDate.of(2090, 1, 1))).isTrue();
        assertThat(unset.isActiveOn(LocalDate.of(2025, 12, 31))).isFalse();
    }

    @Test
    void SRS_22_6_anAssignmentIsCurrentUpToItsEndDateIncludedOrWithoutEnd() {
        AppUserAccessGroup bounded =
                AppUserAccessGroup.propose(
                        1L,
                        2L,
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 31),
                        "admin",
                        null,
                        9L);
        assertThat(bounded.isCurrentOn(LocalDate.of(2026, 10, 1))).as("first day").isTrue();
        assertThat(bounded.isCurrentOn(LocalDate.of(2026, 10, 31))).as("last day").isTrue();
        assertThat(bounded.isCurrentOn(LocalDate.of(2026, 11, 1))).as("day after").isFalse();
        AppUserAccessGroup open =
                AppUserAccessGroup.propose(
                        1L, 2L, LocalDate.of(2026, 10, 1), null, "admin", null, 9L);
        assertThat(open.isCurrentOn(LocalDate.of(2090, 1, 1))).isTrue();
        assertThat(open.isCurrentOn(LocalDate.of(2026, 9, 30))).isFalse();
    }

    @Test
    void assignmentsAreCurrentWithinTheirDatesUntilRevoked() {
        AppUserAccessGroup assignment =
                AppUserAccessGroup.propose(
                        1L,
                        2L,
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 31),
                        "admin",
                        null,
                        9L);
        assertThat(assignment.isCurrentOn(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(assignment.isCurrentOn(LocalDate.of(2026, 10, 16))).isTrue();
        assignment.revoke(AppUserAccessGroup.RevokeCause.TRANSFER, "Transferred", NOW);
        assertThat(assignment.isCurrentOn(LocalDate.of(2026, 10, 16))).isFalse();
        assertThatThrownBy(
                        () ->
                                assignment.revoke(
                                        AppUserAccessGroup.RevokeCause.TRANSFER, "again", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                AppUserAccessGroup.propose(
                                        1L,
                                        2L,
                                        LocalDate.of(2026, 10, 2),
                                        LocalDate.of(2026, 10, 1),
                                        "a",
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSessionKeepsItsActiveScopeUntilSwitchedOrEnded() {
        AppSession session =
                AppSession.start(
                        "hash-of-token",
                        1L,
                        "DDO_MANAGER",
                        10L,
                        List.of(3L, 4L),
                        null,
                        "127.0.0.1",
                        "JUnit",
                        NOW);
        assertThat(session.activeGroupIdList()).containsExactly(3L, 4L);
        session.switchScope("DDO_MANAGER", 20L, List.of(5L), NOW);
        assertThat(session.getActiveLocationId()).isEqualTo(20L);
        assertThat(session.activeGroupIdList()).containsExactly(5L);
        session.end(AppSession.EndReason.LOGOUT, NOW);
        assertThat(session.isOpen()).isFalse();
        session.end(AppSession.EndReason.TIMEOUT, NOW.plusSeconds(5));
        assertThat(session.getEndReason()).as("the first end reason stands").isEqualTo("LOGOUT");
    }
}
