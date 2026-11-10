package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.AccessPolicy;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code can(user, screen, action, target)}: role grant ∩ group scope, the read-only consolidated
 * view and the eSeva own-record rule (RBAC_DESIGN.md §4).
 */
class AccessPolicyTest {

    private static final long HQ = 1;
    private static final long DELHI = 10;
    private static final long MUMBAI = 20;

    private final AccessContextService contexts = mock(AccessContextService.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AuditService audit = mock(AuditService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private AccessPolicy policy;

    @BeforeEach
    void setUp() {
        when(parameters.stepUpValidity()).thenReturn(Duration.ofMinutes(15));
        policy =
                new AccessPolicy(
                        contexts, org, audit, parameters, Clock.fixed(NOW, ZoneOffset.UTC));
        location(HQ, LocationType.HQ);
        location(DELHI, LocationType.DO);
        location(MUMBAI, LocationType.DO);
    }

    @Test
    void grantsWhenTheGroupsRoleHoldsThePermissionInsideTheDataScope() {
        AccessContext maker =
                context(
                        DELHI,
                        group(1, "PAYROLL_MAKER", false),
                        Set.of("M-01:VIEW", "C-02:LOCK"),
                        Set.of(DELHI));
        assertThat(policy.canAt(maker, "M-01", "VIEW", DELHI)).isTrue();
        assertThat(policy.canAt(maker, "C-02", "LOCK", DELHI)).isTrue();
        assertThat(policy.can(maker, "M-01", "VIEW")).isTrue();
    }

    @Test
    void deniesAPermissionNoActiveGroupGrants() {
        AccessContext maker =
                context(
                        DELHI,
                        group(1, "PAYROLL_MAKER", false),
                        Set.of("M-01:VIEW"),
                        Set.of(DELHI));
        assertThat(policy.denialAt(maker, "M-01", "APPROVE", DELHI))
                .isEqualTo(ApiErrorCode.PERMISSION_DENIED);
        assertThatThrownBy(() -> policy.requireAt(maker, "M-01", "APPROVE", DELHI))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.PERMISSION_DENIED);
                            assertThat(ex.getMessageArgs()).containsExactly("M-01", "APPROVE");
                        });
    }

    @Test
    void deniesTargetsOutsideTheDataScope() {
        AccessContext checker =
                context(
                        DELHI,
                        group(1, "PAYROLL_CHECKER", false),
                        Set.of("M-01:APPROVE"),
                        Set.of(DELHI));
        assertThat(policy.denialAt(checker, "M-01", "APPROVE", MUMBAI))
                .isEqualTo(ApiErrorCode.OUT_OF_SCOPE);
    }

    /** OWASP A01 (project owner, 2026-10-24): a record outside the scope reads as unknown. */
    @Test
    void SRS_G_29_aRecordOutsideTheDataScopeIs404AndAnOfficeTheUserNamesIs403() {
        AccessContext checker =
                context(
                        DELHI,
                        group(1, "PAYROLL_CHECKER", false),
                        Set.of("M-01:APPROVE"),
                        Set.of(DELHI));
        assertThatThrownBy(() -> policy.requireRecordAt(checker, "M-01", "APPROVE", MUMBAI))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        // The screen's own "no such record" code, so the two answers cannot be told apart.
        assertThatThrownBy(
                        () ->
                                policy.requireRecordAt(
                                        checker,
                                        "M-01",
                                        "APPROVE",
                                        MUMBAI,
                                        ApiErrorCode.AUDIT_RECORD_NOT_FOUND))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.AUDIT_RECORD_NOT_FOUND));
        // Every attempt is a security event, whatever the caller is told.
        verify(audit, times(2))
                .securityEvent(
                        AuditService.Security.OUT_OF_SCOPE_ACCESS,
                        checker.loginId(),
                        "org_location",
                        String.valueOf(MUMBAI),
                        "M-01:APPROVE");
        // An office the user names stays 403; inside the scope, and without the permission, as
        // before.
        assertThatThrownBy(() -> policy.requireAt(checker, "M-01", "APPROVE", MUMBAI))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.OUT_OF_SCOPE));
        policy.requireRecordAt(checker, "M-01", "APPROVE", DELHI);
        assertThatThrownBy(() -> policy.requireRecordAt(checker, "M-01", "EDIT", DELHI))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
    }

    @Test
    void theConsolidatedHqViewReadsAndExportsRegionDataButNeverChangesIt() {
        AccessContext su =
                context(
                        HQ,
                        group(5, "RULE_ADMINISTRATOR", true),
                        Set.of("M-22:VIEW", "M-22:EXPORT", "M-22:CREATE"),
                        Set.of(HQ, DELHI, MUMBAI));
        assertThat(policy.canAt(su, "M-22", "VIEW", DELHI)).isTrue();
        assertThat(policy.canAt(su, "M-22", "EXPORT", MUMBAI)).isTrue();
        assertThat(policy.denialAt(su, "M-22", "CREATE", DELHI))
                .isEqualTo(ApiErrorCode.READ_ONLY_CONSOLIDATED);
        // Headquarters' own data is not "a Region's data": HQ rule work stays possible.
        assertThat(policy.canAt(su, "M-22", "CREATE", HQ)).isTrue();
        assertThat(policy.isAtHeadquarters(su)).isTrue();
    }

    @Test
    void aSecondNonConsolidatedGroupStillGrantsWhereTheConsolidatedOneCannot() {
        ActiveGroup consolidated = group(5, "RULE_APPROVER", true);
        ActiveGroup regular = group(6, "PAYROLL_CHECKER", false);
        AccessContext both =
                new AccessContext(
                        1L,
                        "u",
                        "U",
                        "s",
                        "DDO_MANAGER",
                        HQ,
                        null,
                        List.of(consolidated, regular),
                        Map.of(5L, Set.of("M-01:APPROVE"), 6L, Set.of("M-01:APPROVE")),
                        Set.of(),
                        Set.of(),
                        Set.of(HQ, DELHI));
        assertThat(policy.canAt(both, "M-01", "APPROVE", DELHI)).isTrue();
    }

    /** A checker whose role approves on W-01 (sensitive) and only views R-01. */
    private static AccessContext checker(Instant stepUpAt) {
        return new AccessContext(
                1L,
                "u",
                "U",
                "s",
                "DDO_MANAGER",
                DELHI,
                null,
                List.of(),
                Map.of(),
                Set.of("W-01:VIEW", "W-01:APPROVE", "R-01:VIEW"),
                Set.of(),
                Set.of(DELHI),
                Set.of("W-01"),
                stepUpAt);
    }

    @Test
    void SRS_G_48_aSensitiveScreenNeedsAnOtpStepUpBeforeAnyCall() {
        when(contexts.current()).thenReturn(checker(null));
        assertThatThrownBy(() -> policy.gate("W-01", "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.STEP_UP_REQUIRED));
        // A screen without a sensitive action never asks.
        assertThat(policy.gate("R-01", "VIEW")).isTrue();
    }

    @Test
    void SRS_G_48_aRecentStepUpIsNotAskedAgainUntilItsValidityEnds() {
        when(contexts.current()).thenReturn(checker(NOW.minus(Duration.ofMinutes(14))));
        assertThat(policy.gate("W-01", "APPROVE")).isTrue();
        // Exactly at the end of the validity the user is asked again (an impostor at an unattended
        // session cannot keep it alive).
        when(contexts.current()).thenReturn(checker(NOW.minus(Duration.ofMinutes(15))));
        assertThatThrownBy(() -> policy.gate("W-01", "APPROVE")).isInstanceOf(FciException.class);
    }

    @Test
    void aMissingPermissionIsRefusedBeforeAnyStepUp() {
        when(contexts.current()).thenReturn(checker(null));
        assertThatThrownBy(() -> policy.gate("A-01", "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
    }

    // BR-ESEVA-001: a self-service session reaches only the member's own records.
    @Test
    void eSevaReachesOnlyTheUsersOwnEmployeeRecord() {
        AccessContext eseva =
                new AccessContext(
                        7L,
                        "emp",
                        "Emp",
                        "s",
                        "EMPLOYEE",
                        null,
                        100L,
                        List.of(),
                        Map.of(),
                        Set.of("E-01:VIEW"),
                        Set.of("EMPLOYEE_ESEVA"),
                        Set.of());
        Employee own = employee(100L, DELHI);
        Employee other = employee(101L, DELHI);
        policy.requireEmployeeRecord(eseva, "E-01", "VIEW", own.getId(), own.getLocationId());
        assertThatThrownBy(
                        () ->
                                policy.requireEmployeeRecord(
                                        eseva,
                                        "E-01",
                                        "VIEW",
                                        other.getId(),
                                        other.getLocationId()))
                // Another member's record does not exist for this member (404, never 403).
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        assertThatThrownBy(
                        () ->
                                policy.requireEmployeeRecord(
                                        eseva, "M-01", "VIEW", own.getId(), own.getLocationId()))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
        assertThat(eseva.isEseva()).isTrue();
    }

    @Test
    void anEmployeeWithoutAnOfficeIsHeadquartersOnlyNeverEveryOffices() {
        // Security review 2026-10-31: a null record office passed every office's scope check.
        AccessContext delhi =
                context(
                        DELHI,
                        group(1, "PAYROLL_MAKER", false),
                        Set.of("M-01:VIEW"),
                        Set.of(DELHI));
        assertThatThrownBy(() -> policy.requireEmployeeRecord(delhi, "M-01", "VIEW", 500L, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        verify(audit)
                .securityEvent(
                        AuditService.Security.OUT_OF_SCOPE_ACCESS,
                        "user@fci-demo.test",
                        "employee",
                        "500",
                        "M-01:VIEW");
        AccessContext hq =
                context(HQ, group(2, "PAYROLL_MAKER", false), Set.of("M-01:VIEW"), Set.of(HQ));
        policy.requireEmployeeRecord(hq, "M-01", "VIEW", 500L, null);
        // An office in scope is unchanged.
        policy.requireEmployeeRecord(delhi, "M-01", "VIEW", 501L, DELHI);
    }

    @Test
    void theEndpointGateAnswersThePrecise403() {
        AccessContext maker =
                context(
                        DELHI,
                        group(1, "PAYROLL_MAKER", false),
                        Set.of("M-01:VIEW"),
                        Set.of(DELHI));
        when(contexts.current()).thenReturn(maker);
        assertThat(policy.gate("M-01", "VIEW")).isTrue();
        assertThatThrownBy(() -> policy.gate("A-01", "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
    }

    @Test
    void SRS_22_8_theScreenLevelCheckReadsTheCallersCurrentContextAndAnswersFalseNeverThrows() {
        AccessContext maker =
                context(
                        DELHI,
                        group(1, "PAYROLL_MAKER", false),
                        Set.of("M-01:VIEW"),
                        Set.of(DELHI));
        when(contexts.current()).thenReturn(maker);
        assertThat(policy.can("M-01", "VIEW")).isTrue();
        assertThat(policy.can("M-01", "APPROVE")).as("action not granted").isFalse();
        assertThat(policy.can("A-01", "VIEW")).as("screen not granted").isFalse();
        assertThat(policy.can("m-01", "view")).as("keys are case sensitive").isFalse();
    }

    @Test
    void SRS_22_8_aCallerWhoseSessionIsGoneIsAskedToSignInBeforeAnyPermissionIsJudged() {
        when(contexts.current())
                .thenThrow(new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        assertThatThrownBy(() -> policy.can("M-01", "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
        assertThatThrownBy(() -> policy.gate("M-01", "VIEW")).isInstanceOf(FciException.class);
    }

    @Test
    void eitherOfTwoPermissionsPassesTheGateAndNeitherIsThe403OfTheFirst() {
        AccessContext approver =
                context(
                        HQ,
                        group(1, "RULE_APPROVER", false),
                        Set.of("M-08:VIEW", "M-08:APPROVE"),
                        Set.of(HQ));
        when(contexts.current()).thenReturn(approver);
        // Only the second permission is held: gate(a) or gate(b) would have thrown on the first.
        assertThat(policy.gateEither("M-08", "CREATE", "M-08", "APPROVE")).isTrue();
        assertThat(policy.gateEither("M-08", "VIEW", "M-08", "CREATE")).isTrue();
        assertThatThrownBy(() -> policy.gateEither("M-07", "CREATE", "M-07", "EDIT"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.PERMISSION_DENIED);
                            assertThat(ex.getMessageArgs()).containsExactly("M-07", "CREATE");
                        });
    }

    private void location(long id, LocationType type) {
        OrgLocation location = mock(OrgLocation.class);
        when(location.getId()).thenReturn(id);
        when(location.getType()).thenReturn(type);
        when(org.location(id)).thenReturn(Optional.of(location));
    }

    private static ActiveGroup group(long id, String role, boolean consolidated) {
        return new ActiveGroup(
                id,
                "AG-" + id,
                "Group " + id,
                id,
                role,
                role,
                consolidated ? ScopeType.HEADQUARTERS : ScopeType.DIVISION,
                consolidated,
                Set.of(),
                Set.of(),
                AccessMode.FULL);
    }

    private static AccessContext context(
            long location, ActiveGroup group, Set<String> keys, Set<Long> scope) {
        return new AccessContext(
                1L,
                "user@fci-demo.test",
                "User",
                "session",
                "PAYROLL",
                location,
                null,
                List.of(group),
                Map.of(group.id(), keys),
                Set.of(),
                Set.of(),
                scope);
    }

    private static Employee employee(long id, long location) {
        Employee employee = mock(Employee.class);
        when(employee.getId()).thenReturn(id);
        when(employee.getLocationId()).thenReturn(location);
        return employee;
    }

    /** BR-ESEVA-001: a direct (eSeva) permission is held for no office, so never at one. */
    @Test
    void aDirectPermissionIsHeldForNoOfficeOnly() {
        AccessContext eseva =
                new AccessContext(
                        7L,
                        "emp",
                        "Emp",
                        "s",
                        "EMPLOYEE",
                        null,
                        100L,
                        List.of(),
                        Map.of(),
                        Set.of("E-01:VIEW"),
                        Set.of("EMPLOYEE_ESEVA"),
                        Set.of());
        assertThat(policy.denialAt(eseva, "E-01", "VIEW", null)).isNull();
        assertThat(policy.denialAt(eseva, "E-01", "VIEW", DELHI))
                .isEqualTo(ApiErrorCode.PERMISSION_DENIED);
    }
}
