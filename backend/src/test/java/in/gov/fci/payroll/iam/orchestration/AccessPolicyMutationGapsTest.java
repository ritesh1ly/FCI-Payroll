package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
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
import org.junit.jupiter.api.Test;

/**
 * Mutation gaps of {@link AccessPolicy}: SRS-22-8 (role grant within data scope), SRS-G-29 (a
 * record outside the scope is unknown), screen-level {@code require}, {@code gateEither}.
 *
 * <p>Equivalent mutants: AccessPolicy:102 and :104 (gateEither return replaced with true): the
 * returned {@code gate(...)} is true or throws, never false, so the return value cannot differ.
 */
class AccessPolicyMutationGapsTest {

    private static final long HQ = 1;
    private static final long DELHI = 10;
    private static final long MUMBAI = 20;
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    private final AccessContextService contexts = mock(AccessContextService.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AuditService audit = mock(AuditService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessPolicy policy;

    AccessPolicyMutationGapsTest() {
        when(parameters.stepUpValidity()).thenReturn(Duration.ofMinutes(15));
        policy =
                new AccessPolicy(
                        contexts, org, audit, parameters, Clock.fixed(NOW, ZoneOffset.UTC));
        location(HQ, LocationType.HQ);
        location(DELHI, LocationType.DO);
        location(MUMBAI, LocationType.DO);
    }

    private void location(long id, LocationType type) {
        OrgLocation location = mock(OrgLocation.class);
        when(location.getId()).thenReturn(id);
        when(location.getType()).thenReturn(type);
        when(org.location(id)).thenReturn(Optional.of(location));
    }

    private static ActiveGroup group() {
        return new ActiveGroup(
                1L,
                "AG-1",
                "Group 1",
                1L,
                "PAYROLL_MAKER",
                "PAYROLL_MAKER",
                ScopeType.DIVISION,
                false,
                Set.of(),
                Set.of(),
                AccessMode.FULL);
    }

    private static AccessContext context(
            long location, Set<String> keys, Set<Long> scope, Set<String> sensitive) {
        ActiveGroup group = group();
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
                scope,
                sensitive,
                null);
    }

    private static AccessContext plain(Set<String> keys, Set<Long> scope) {
        return context(DELHI, keys, scope, Set.of());
    }

    @Test
    void SRS_22_8_canAtIsFalseOutsideTheDataScopeAndWithoutThePermission() {
        AccessContext ctx = plain(Set.of("M-01:VIEW"), Set.of(DELHI));

        assertThat(policy.canAt(ctx, "M-01", "VIEW", DELHI)).isTrue();
        assertThat(policy.canAt(ctx, "M-01", "VIEW", MUMBAI)).isFalse();
        assertThat(policy.canAt(ctx, "M-01", "APPROVE", DELHI)).isFalse();
    }

    @Test
    void SRS_22_8_requireWithoutALocationThrowsWhenNoGroupHoldsThePermission() {
        AccessContext ctx = plain(Set.of("M-01:VIEW"), Set.of(DELHI));

        assertThatCode(() -> policy.require(ctx, "M-01", "VIEW")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.require(ctx, "M-01", "APPROVE"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.PERMISSION_DENIED);
                            assertThat(e.getMessageArgs()).containsExactly("M-01", "APPROVE");
                        });
    }

    @Test
    void SRS_G_29_requireEmployeeRecordRefusesAMissingPermissionAtAnInScopeOffice() {
        AccessContext ctx = plain(Set.of("M-01:VIEW"), Set.of(DELHI));

        assertThatCode(() -> policy.requireEmployeeRecord(ctx, "M-01", "VIEW", 5L, DELHI))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireEmployeeRecord(ctx, "M-01", "APPROVE", 5L, DELHI))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
    }

    @Test
    void SRS_G_29_requireEmployeeRecordReadsAnOutOfScopeRecordAsNotFound() {
        AccessContext ctx = plain(Set.of("M-01:VIEW"), Set.of(DELHI));

        assertThatThrownBy(() -> policy.requireEmployeeRecord(ctx, "M-01", "VIEW", 5L, MUMBAI))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void SRS_G_29_requireEmployeeRecordWithoutAnOfficeIsHeadquartersOnly() {
        AccessContext atDelhi = plain(Set.of("M-01:VIEW"), Set.of(DELHI));
        assertThatThrownBy(() -> policy.requireEmployeeRecord(atDelhi, "M-01", "VIEW", 5L, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));

        AccessContext atHq = context(HQ, Set.of("M-01:VIEW"), Set.of(HQ), Set.of());
        assertThatCode(() -> policy.requireEmployeeRecord(atHq, "M-01", "VIEW", 5L, null))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_G_48_gateEitherAdmitsTheSecondPermissionWhenTheFirstIsNotHeld() {
        when(contexts.current()).thenReturn(plain(Set.of("M-08:APPROVE"), Set.of(DELHI)));

        assertThat(policy.gateEither("M-08", "CREATE", "M-08", "APPROVE")).isTrue();
    }

    @Test
    void SRS_G_48_gateEitherPrefersTheFirstPermissionAndRefusesWhenNeitherIsHeld() {
        when(contexts.current())
                .thenReturn(plain(Set.of("M-08:CREATE", "M-08:APPROVE"), Set.of(DELHI)));
        assertThat(policy.gateEither("M-08", "CREATE", "M-08", "APPROVE")).isTrue();

        when(contexts.current()).thenReturn(plain(Set.of("M-09:VIEW"), Set.of(DELHI)));
        assertThatThrownBy(() -> policy.gateEither("M-08", "CREATE", "M-08", "APPROVE"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.PERMISSION_DENIED);
                            assertThat(e.getMessageArgs()).containsExactly("M-08", "CREATE");
                        });
    }

    @Test
    void SRS_G_48_gateEitherAppliesTheStepUpOfTheSecondPermissionWhenThatOneIsUsed() {
        when(contexts.current())
                .thenReturn(context(DELHI, Set.of("M-08:APPROVE"), Set.of(DELHI), Set.of("M-08")));

        assertThatThrownBy(() -> policy.gateEither("M-08", "CREATE", "M-08", "APPROVE"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.STEP_UP_REQUIRED));
    }
}
