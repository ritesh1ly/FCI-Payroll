package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** OD-130: which refusals of the policy are offences; the answers themselves do not change. */
class AccessPolicyOffenceTest {

    private static final long OWN = 10L;
    private static final long OTHER = 20L;
    private static final long EMPLOYEE = 900L;

    private final AccessContextService contexts = mock(AccessContextService.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessPolicy policy =
            new AccessPolicy(
                    contexts,
                    org,
                    mock(AuditService.class),
                    mock(SystemParameters.class),
                    Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));

    private final ActiveGroup group =
            new ActiveGroup(
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

    private AccessContext context(long location, Set<Long> scope) {
        return new AccessContext(
                1L,
                "user@fci-demo.test",
                "User",
                "session",
                "PAYROLL",
                location,
                null,
                List.of(group),
                Map.of(group.id(), Set.of("M-01:VIEW")),
                Set.of(),
                Set.of(),
                scope,
                Set.of(),
                null);
    }

    private FciException refusal(Runnable call) {
        return catchThrowableOfType(FciException.class, call::run);
    }

    @Test
    void OD_130_aScreenTheRoleDoesNotHoldIsAnOffence() {
        when(contexts.current()).thenReturn(context(OWN, Set.of(OWN)));

        FciException refused = refusal(() -> policy.gate("A-01", "VIEW"));

        assertThat(refused.getErrorCode()).isEqualTo(ApiErrorCode.PERMISSION_DENIED);
        assertThat(refused.isAccessOffence()).isTrue();
    }

    @Test
    void OD_130_anOfficeTheUserNamedOutsideTheScopeIsAnOffence() {
        AccessContext context = context(OWN, Set.of(OWN));

        FciException refused = refusal(() -> policy.requireAt(context, "M-01", "VIEW", OTHER));

        assertThat(refused.getErrorCode()).isEqualTo(ApiErrorCode.OUT_OF_SCOPE);
        assertThat(refused.isAccessOffence()).isTrue();
    }

    @Test
    void OD_130_theSessionsOwnOfficeOutsideItsScopeIsASetUpGapNoOffence() {
        AccessContext context = context(OWN, Set.of(OTHER));

        FciException refused = refusal(() -> policy.requireAt(context, "M-01", "VIEW", OWN));

        assertThat(refused.getErrorCode()).isEqualTo(ApiErrorCode.OUT_OF_SCOPE);
        assertThat(refused.isAccessOffence()).isFalse();
    }

    @Test
    void OD_130_aRecordOutsideTheScopeAnswers404AndIsAnOffence() {
        AccessContext context = context(OWN, Set.of(OWN));

        FciException refused =
                refusal(() -> policy.requireRecordAt(context, "M-01", "VIEW", OTHER));

        assertThat(refused.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(refused.isAccessOffence()).isTrue();
    }

    @Test
    void OD_130_anotherMembersRecordAnswers404AndIsAnOffence() {
        AccessContext member =
                new AccessContext(
                        1L,
                        "member@fci-demo.test",
                        "Member",
                        "session",
                        "ESEVA",
                        OWN,
                        EMPLOYEE + 1,
                        List.of(),
                        Map.of(),
                        Set.of("E-01:VIEW"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        null);

        FciException refused =
                refusal(() -> policy.requireEmployeeRecord(member, "E-01", "VIEW", EMPLOYEE, OWN));

        assertThat(refused.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(refused.isAccessOffence()).isTrue();
    }

    @Test
    void OD_130_anEmployeeWithoutAnOfficeOutsideHeadquartersIsAnOffence() {
        OrgLocation office = mock(OrgLocation.class);
        when(office.getType()).thenReturn(LocationType.DO);
        when(org.location(OWN)).thenReturn(Optional.of(office));
        AccessContext context = context(OWN, Set.of(OWN));

        FciException refused =
                refusal(
                        () ->
                                policy.requireEmployeeRecord(
                                        context, "M-01", "VIEW", EMPLOYEE, null));

        assertThat(refused.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(refused.isAccessOffence()).isTrue();
    }

    @Test
    void OD_130_aNormalMissingRecordIsNoOffence() {
        assertThat(new FciException(CommonErrorCode.NOT_FOUND).isAccessOffence()).isFalse();
    }
}
