package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SRS-SEC-14: "idle logout after 15 minutes for payroll users and 10 minutes for eSeva members".
 * One policy decides a session's idle timeout from its scope, at sign-in (the session) and after a
 * switch (the scope); the values are the A-07 settings.
 */
class SessionTimeoutsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");

    private final SystemParameters parameters = mock(SystemParameters.class);
    private final SessionTimeouts timeouts = new SessionTimeouts(parameters);

    @BeforeEach
    void settings() {
        when(parameters.sessionIdleTimeout()).thenReturn(Duration.ofMinutes(15));
        when(parameters.esevaSessionIdleTimeout()).thenReturn(Duration.ofMinutes(10));
    }

    @Test
    void SRS_SEC_14_aSessionStartedAtAnOfficeIdlesOutAfterThePayrollTimeoutAnEsevaOneAfterItsOwn() {
        AppSession staff =
                AppSession.start("h", 1L, "PAYROLL", 40L, List.of(3L), 9L, null, null, NOW);
        AppSession member =
                AppSession.start("h", 2L, "EMPLOYEE", null, List.of(), 9L, null, null, NOW);
        assertThat(timeouts.idle(staff)).isEqualTo(Duration.ofMinutes(15));
        assertThat(timeouts.idle(member)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void SRS_SEC_14_afterASwitchTheTimeoutOfTheNewScopeApplies() {
        OrgLocation office = mock(OrgLocation.class);
        ScopeResolver.RoleScope maker =
                new ScopeResolver.RoleScope("PAYROLL", office, "PAYROLL_MAKER", "Maker", List.of());
        ScopeResolver.RoleScope eseva =
                new ScopeResolver.RoleScope(
                        "EMPLOYEE", null, AccessContextService.ESEVA_ROLE, "Employee", List.of());
        assertThat(timeouts.idle(maker)).isEqualTo(Duration.ofMinutes(15));
        assertThat(timeouts.idle(eseva)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void SRS_SEC_14_aChangedSettingAppliesToTheNextSession() {
        when(parameters.esevaSessionIdleTimeout()).thenReturn(Duration.ofMinutes(5));
        AppSession member =
                AppSession.start("h", 2L, "EMPLOYEE", null, List.of(), 9L, null, null, NOW);
        assertThat(timeouts.idle(member)).isEqualTo(Duration.ofMinutes(5));
    }
}
