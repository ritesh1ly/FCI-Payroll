package in.gov.fci.payroll.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.MeService;
import in.gov.fci.payroll.iam.orchestration.MenuCatalog;
import in.gov.fci.payroll.iam.orchestration.PasswordService;
import in.gov.fci.payroll.iam.orchestration.SessionTimeouts;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import in.gov.fci.payroll.platform.paging.PageSizes;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * /auth/me, /auth/switch-scope and /auth/step-up (SRS-G-48, SRS-SEC-14): a persona code the screens
 * do not know lands on the workspace, the last sign-in is shown when there was one, a blank office
 * keeps the session's own, an unknown office is refused, and a step-up with nothing to confirm
 * neither rotates the session nor logs it.
 */
class MeControllerEdgeTest {

    private final MeService me = mock(MeService.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final SecurityAuditLogger securityLog = mock(SecurityAuditLogger.class);
    private final AccessContext context = mock(AccessContext.class);
    private final MeController controller =
            new MeController(
                    me,
                    mock(SessionTimeouts.class),
                    org,
                    mock(PasswordService.class),
                    contexts,
                    mock(MessageBundles.class),
                    mock(MenuCatalog.class),
                    securityLog,
                    Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC),
                    mock(ApiProperties.class, RETURNS_DEEP_STUBS),
                    mock(PageSizes.class));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    MeControllerEdgeTest() {
        when(contexts.current()).thenReturn(context);
        when(context.userId()).thenReturn(7L);
        when(context.sessionId()).thenReturn("s-7");
        request.getSession(true);
    }

    @Test
    void SRS_G_48_anUnknownPersonaLandsOnTheWorkspaceAndTheLastSignInIsShown() {
        Instant last = Instant.parse("2026-10-28T04:00:00Z");
        when(me.me(any(), any()))
                .thenReturn(
                        new MeService.Me(
                                7L,
                                "u7",
                                "User",
                                "NO_SUCH_PERSONA",
                                null,
                                null,
                                "R",
                                "Role",
                                null,
                                List.of(),
                                List.of(),
                                List.of(),
                                false,
                                "2026-10",
                                "OPEN",
                                "en",
                                false,
                                false,
                                List.of(),
                                null,
                                last,
                                List.of(),
                                false));
        Map<String, Object> body = controller.me(request).getBody();
        assertThat(body).containsEntry("persona", null).containsEntry("route", Personas.WORKSPACE);
        assertThat(body).containsEntry("lastLoginAt", last.toString());
    }

    @Test
    void SRS_SEC_14_aBlankOfficeKeepsTheSessionsOwnAndAnUnknownOneIsRefused() {
        controller.switchScope(new MeController.SwitchRequest("PAYROLL_MAKER", " ", null), request);
        verify(me).switchScope(eq(context), eq("PAYROLL_MAKER"), isNull(), any());
        when(org.byCode("ZZ999")).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                controller.switchScope(
                                        new MeController.SwitchRequest(
                                                "PAYROLL_MAKER", "ZZ999", null),
                                        request))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_G_48_aStepUpWithNothingToConfirmNeitherRotatesNorLogs() {
        String before = request.getSession().getId();
        assertThat(controller.stepUp(null, request).getStatusCode().value()).isEqualTo(204);
        assertThat(request.getSession().getId()).isEqualTo(before);
        verify(securityLog, never()).log(any());
    }

    @Test
    void SRS_G_48_aFirstSignInShowsNoLastSignIn() {
        when(me.me(any(), any()))
                .thenReturn(
                        new MeService.Me(
                                7L, "u7", "User", "STAFF", null, null, "R", "Role", null, List.of(),
                                List.of(), List.of(), false, "2026-10", "OPEN", "en", false, false,
                                List.of(), null, null, List.of(), false));
        Map<String, Object> body = controller.me(request).getBody();
        assertThat(body).containsEntry("lastLoginAt", null);
    }
}
