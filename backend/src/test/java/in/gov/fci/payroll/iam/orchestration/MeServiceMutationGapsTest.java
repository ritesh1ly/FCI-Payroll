package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.LoginPersona;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import in.gov.fci.payroll.workflow.api.GovernedActions;
import in.gov.fci.payroll.workflow.api.HeadquartersOnlyScreens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mutation gaps of {@link MeService}: the profile (password change due, read-only consolidated
 * view, period status, step-up), the governed buttons, the scope options with the current one
 * flagged, the audit line of a scope switch and the step-up's cache refresh (SRS-G-48, SRS-G-21,
 * Module 02 D-2).
 */
class MeServiceMutationGapsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final Instant NOW = TODAY.atTime(11, 30).atZone(IST).toInstant();
    private static final long OFFICE = 40L;

    private final AccessContextService contexts = mock(AccessContextService.class);
    private final ScopeResolver scopes = mock(ScopeResolver.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final IdentityRepositories.Personas personas =
            mock(IdentityRepositories.Personas.class);
    private final IdentityRepositories.Sessions sessions =
            mock(IdentityRepositories.Sessions.class);
    private final IdentityRepositories.Preferences preferences =
            mock(IdentityRepositories.Preferences.class);
    private final GovernedActions inventory = mock(GovernedActions.class);
    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final AuditService audit = mock(AuditService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final OtpService otps = mock(OtpService.class);

    private final MeService service =
            new MeService(
                    contexts,
                    scopes,
                    org,
                    users,
                    credentials,
                    personas,
                    sessions,
                    preferences,
                    inventory,
                    policy,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(ApiProperties.class),
                    parameters,
                    periods,
                    otps,
                    mock(HeadquartersOnlyScreens.class));

    private final AppUser user = mock(AppUser.class);
    private final AppSession session = mock(AppSession.class);
    private final AppUserCredential credential = mock(AppUserCredential.class);

    @BeforeEach
    void wire() {
        when(user.getId()).thenReturn(1L);
        when(user.getLoginId()).thenReturn("maker@x");
        when(user.getDisplayName()).thenReturn("Asha Verma");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(credentials.findById(1L)).thenReturn(Optional.of(credential));
        when(parameters.stepUpValidity()).thenReturn(Duration.ofMinutes(15));
        when(parameters.passwordMaxAge()).thenReturn(Duration.ofDays(90));
        when(periods.current()).thenReturn(YearMonth.from(TODAY));
        when(contexts.today()).thenReturn(TODAY);
        when(otps.verificationRequired()).thenReturn(false);
        when(session.getId()).thenReturn(77L);
        when(session.getTokenHash()).thenReturn("token-hash");
        when(sessions.findByToken("s")).thenReturn(Optional.of(session));
        LoginPersona payroll = mock(LoginPersona.class);
        when(payroll.getCode()).thenReturn("PAYROLL");
        when(payroll.getLandingScreenCode()).thenReturn("D-01");
        when(personas.findById("PAYROLL")).thenReturn(Optional.of(payroll));
    }

    private static ActiveGroup group(String code, String role, boolean consolidated) {
        return new ActiveGroup(
                3L,
                code,
                "Group " + code,
                4L,
                role,
                "Role " + role,
                ScopeType.DIVISION,
                consolidated,
                Set.of(OFFICE),
                Set.of(),
                AccessMode.FULL);
    }

    private static AccessContext context(
            List<ActiveGroup> groups,
            Long location,
            Long employee,
            Set<String> sensitive,
            Instant stepUpAt) {
        return new AccessContext(
                1L,
                "maker@x",
                "Maker",
                "s",
                "PAYROLL",
                location,
                employee,
                groups,
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(OFFICE),
                sensitive,
                stepUpAt);
    }

    private static AccessContext plain(ActiveGroup... groups) {
        return context(List.of(groups), OFFICE, 9L, Set.of(), null);
    }

    private static OrgLocation office(long id, String code) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getName()).thenReturn("Office " + code);
        when(l.getType()).thenReturn(LocationType.DO);
        when(l.displayLabel()).thenReturn(code + " - Office " + code);
        return l;
    }

    // ------------------------------------------------------------------ the view

    @Test
    void SRS_G_48_theViewCarriesTheLocationAndGroupsOfTheSession() {
        OrgLocation delhi = office(OFFICE, "DL01");
        when(org.location(OFFICE)).thenReturn(Optional.of(delhi));
        when(credential.isMustChange()).thenReturn(false);

        MeService.Me me = service.me(plain(group("AG-MAKER", "PAYROLL_MAKER", false)));

        assertThat(me.userId()).isEqualTo(1L);
        assertThat(me.loginId()).isEqualTo("maker@x");
        assertThat(me.displayName()).isEqualTo("Asha Verma");
        assertThat(me.landingScreen()).isEqualTo("D-01");
        assertThat(me.location())
                .isEqualTo(
                        new MeService.LocationView(
                                OFFICE, "DL01", "Office DL01", "DO", "DL01 - Office DL01"));
        assertThat(me.groups())
                .containsExactly(
                        new MeService.GroupView(
                                3L,
                                "AG-MAKER",
                                "Group AG-MAKER",
                                "PAYROLL_MAKER",
                                "Role PAYROLL_MAKER",
                                false));
        assertThat(me.roles()).containsExactly("PAYROLL_MAKER");
    }

    @Test
    void SRS_G_48_aSessionWithoutAnOfficeOrWithAnUnknownOfficeHasNoLocation() {
        when(org.location(OFFICE)).thenReturn(Optional.empty());

        assertThat(service.me(plain(group("AG-MAKER", "PAYROLL_MAKER", false))).location())
                .as("unknown office")
                .isNull();
        AccessContext noOffice =
                context(
                        List.of(group("AG-MAKER", "PAYROLL_MAKER", false)),
                        null,
                        9L,
                        Set.of(),
                        null);
        assertThat(service.me(noOffice).location()).as("no office").isNull();
    }

    @Test
    void SRS_SEC_13_aPasswordChangeIsDueOnlyWhenForcedExpiredOrMissing() {
        AccessContext maker = plain(group("AG-MAKER", "PAYROLL_MAKER", false));

        when(credential.isMustChange()).thenReturn(false);
        when(credential.isExpired(NOW, Duration.ofDays(90))).thenReturn(false);
        assertThat(service.me(maker).passwordChangeRequired()).as("fresh").isFalse();

        when(credential.isMustChange()).thenReturn(true);
        assertThat(service.me(maker).passwordChangeRequired()).as("forced").isTrue();

        when(credential.isMustChange()).thenReturn(false);
        when(credential.isExpired(NOW, Duration.ofDays(90))).thenReturn(true);
        assertThat(service.me(maker).passwordChangeRequired()).as("expired").isTrue();

        when(credentials.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.me(maker).passwordChangeRequired()).as("missing").isTrue();
    }

    @Test
    void SRS_G_48_theViewIsReadOnlyOnlyWhenEveryGroupIsConsolidated() {
        ActiveGroup consolidated = group("AG-MIS", "PAYROLL_VIEWER", true);
        ActiveGroup ordinary = group("AG-MAKER", "PAYROLL_MAKER", false);

        assertThat(service.me(plain(consolidated)).readOnlyConsolidated()).isTrue();
        assertThat(service.me(plain(consolidated, consolidated)).readOnlyConsolidated()).isTrue();
        assertThat(service.me(plain(consolidated, ordinary)).readOnlyConsolidated()).isFalse();
        assertThat(service.me(plain(ordinary)).readOnlyConsolidated()).isFalse();
        assertThat(service.me(context(List.of(), null, 9L, Set.of(), null)).readOnlyConsolidated())
                .as("no group")
                .isFalse();
    }

    @Test
    void SRS_G_48_theWorkingPeriodIsCurrentWhenItIsTheCalendarsAndPastOtherwise() {
        AccessContext maker = plain(group("AG-MAKER", "PAYROLL_MAKER", false));
        YearMonth current = YearMonth.from(TODAY);

        MeService.Me same = service.me(maker, Optional.of(current));
        assertThat(same.periodStatus()).isEqualTo("CURRENT");
        assertThat(same.period()).isEqualTo(current.toString());

        MeService.Me earlier = service.me(maker, Optional.of(current.minusMonths(1)));
        assertThat(earlier.periodStatus()).isEqualTo("PAST");
        assertThat(earlier.period()).isEqualTo(current.minusMonths(1).toString());

        MeService.Me none = service.me(maker, Optional.empty());
        assertThat(none.periodStatus()).isEqualTo("CURRENT");
        assertThat(none.period()).isEqualTo(current.toString());
    }

    @Test
    void SRS_G_21_theStepUpIsRequiredOnlyForASensitiveSessionWithoutAValidConfirmation() {
        ActiveGroup g = group("AG-MAKER", "PAYROLL_MAKER", false);
        List<ActiveGroup> groups = List.of(g);

        MeService.Me unconfirmed = service.me(context(groups, OFFICE, 9L, Set.of("A-04"), null));
        assertThat(unconfirmed.stepUpRequired()).isTrue();
        assertThat(unconfirmed.stepUpValidUntil()).isNull();
        assertThat(unconfirmed.sensitiveScreens()).containsExactly("A-04");

        Instant confirmedAt = NOW.minusSeconds(60);
        MeService.Me confirmed =
                service.me(context(groups, OFFICE, 9L, Set.of("A-04"), confirmedAt));
        assertThat(confirmed.stepUpRequired()).isFalse();
        assertThat(confirmed.stepUpValidUntil())
                .isEqualTo(confirmedAt.plus(Duration.ofMinutes(15)));

        Instant stale = NOW.minus(Duration.ofMinutes(16));
        assertThat(service.me(context(groups, OFFICE, 9L, Set.of("A-04"), stale)).stepUpRequired())
                .isTrue();

        assertThat(service.me(context(groups, OFFICE, 9L, Set.of(), null)).stepUpRequired())
                .as("nothing sensitive")
                .isFalse();
    }

    // ------------------------------------------------------------------ governed buttons

    @Test
    void SRS_G_48_everyGovernedButtonSaysWhetherItIsAllowedWithTheReasonWhenNot() {
        AccessContext maker = plain(group("AG-MAKER", "PAYROLL_MAKER", false));
        when(inventory.all())
                .thenReturn(
                        List.of(
                                new GovernedActions.GovernedAction(
                                        "M-01", "EDIT", "screen.edit", "reason.edit"),
                                new GovernedActions.GovernedAction(
                                        "M-01", "APPROVE", "screen.approve", "reason.approve")));
        when(policy.can(maker, "M-01", "EDIT")).thenReturn(true);
        when(policy.can(maker, "M-01", "APPROVE")).thenReturn(false);

        List<MeService.ActionView> actions = service.actions(maker);

        assertThat(actions)
                .containsExactly(
                        new MeService.ActionView("M-01", "EDIT", "screen.edit", true, null),
                        new MeService.ActionView(
                                "M-01", "APPROVE", "screen.approve", false, "reason.approve"));
    }

    @Test
    void SRS_G_48_noGovernedButtonsGiveAnEmptyList() {
        when(inventory.all()).thenReturn(List.of());
        assertThat(service.actions(plain(group("AG-MAKER", "PAYROLL_MAKER", false)))).isEmpty();
    }

    // ------------------------------------------------------------------ scope options

    private ScopeResolver.RoleScope scope(String role, OrgLocation at, ActiveGroup... groups) {
        return new ScopeResolver.RoleScope("PAYROLL", at, role, "Role " + role, List.of(groups));
    }

    @Test
    void SRS_G_48_theScopeOptionFlagsTheCurrentOfficeAndGroupOnly() {
        ActiveGroup acting = group("AG-MAKER", "PAYROLL_MAKER", false);
        ActiveGroup other = group("AG-OTHER", "PAYROLL_CHECKER", false);
        OrgLocation here = office(OFFICE, "DL01");
        OrgLocation elsewhere = office(41L, "DL02");
        AccessContext maker = plain(acting);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any()))
                .thenReturn(
                        List.of(
                                scope("PAYROLL_MAKER", here, acting),
                                scope("PAYROLL_MAKER", elsewhere, acting),
                                scope("PAYROLL_CHECKER", here, other),
                                scope(AccessContextService.ESEVA_ROLE, null)));

        List<MeService.ScopeOption> options = service.scopeOptions(maker);

        assertThat(options).hasSize(4);
        assertThat(options)
                .extracting(MeService.ScopeOption::current)
                .containsExactly(true, false, false, false);
        MeService.ScopeOption first = options.get(0);
        assertThat(first.personaCode()).isEqualTo("PAYROLL");
        assertThat(first.roleCode()).isEqualTo("PAYROLL_MAKER");
        assertThat(first.roleName()).isEqualTo("Role PAYROLL_MAKER");
        assertThat(first.location().code()).isEqualTo("DL01");
        assertThat(first.groups())
                .extracting(MeService.GroupView::code)
                .containsExactly("AG-MAKER");
        assertThat(options.get(1).location().code()).isEqualTo("DL02");
        MeService.ScopeOption eseva = options.get(3);
        assertThat(eseva.location()).isNull();
        assertThat(eseva.groups()).isEmpty();
    }

    @Test
    void SRS_G_48_theESevaOptionIsCurrentOnlyForAnESevaSession() {
        AccessContext member = context(List.of(), null, 9L, Set.of(), null);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any()))
                .thenReturn(List.of(scope(AccessContextService.ESEVA_ROLE, null)));

        List<MeService.ScopeOption> options = service.scopeOptions(member);

        assertThat(options).hasSize(1);
        assertThat(options.get(0).current()).isTrue();
        assertThat(options.get(0).location()).isNull();
    }

    @Test
    void SRS_G_48_noScopeIsOfferedWhenTheUserHoldsNone() {
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(List.of());
        assertThat(service.scopeOptions(plain(group("AG-MAKER", "PAYROLL_MAKER", false))))
                .isEmpty();
    }

    // ------------------------------------------------------------------ switch + step-up audit

    @Test
    void SRS_G_48_aSwitchOfOfficeInTheSameRoleIsAuditedWithBeforeAndAfter() {
        ActiveGroup acting = group("AG-MAKER", "PAYROLL_MAKER", false);
        AccessContext maker = plain(acting);
        OrgLocation elsewhere = office(41L, "DL02");
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any()))
                .thenReturn(List.of(scope("PAYROLL_MAKER", elsewhere, acting)));

        service.switchScope(maker, "PAYROLL_MAKER", 41L, null);

        verify(audit)
                .record(
                        "maker@x",
                        AuditService.Entry.change(
                                "app_session",
                                "77",
                                "SCOPE_SWITCH",
                                "[PAYROLL_MAKER] @40",
                                "PAYROLL_MAKER @41",
                                "Scope switched"));
        verify(sessions).save(session);
        verify(contexts).evictSession("token-hash");
    }

    @Test
    void SRS_G_21_aStepUpEvictsTheCachedContextOfTheSessionAndAudits() {
        AccessContext sensitive =
                context(
                        List.of(group("AG-MAKER", "PAYROLL_MAKER", false)),
                        OFFICE,
                        9L,
                        Set.of("A-04"),
                        null);
        OtpChallenge bound = mock(OtpChallenge.class);
        when(bound.getId()).thenReturn(701L);
        when(otps.confirmWithoutCode(
                        user,
                        in.gov.fci.payroll.platform.security.OtpGatedAction.AUTH_LOGIN,
                        MeService.STEP_UP_ENTITY,
                        "77"))
                .thenReturn(bound);

        assertThat(service.stepUp(sensitive, null)).isTrue();

        verify(session).confirmStepUp(701L, NOW);
        verify(sessions).save(session);
        verify(contexts).evictSession("token-hash");
        verify(audit)
                .record(
                        "maker@x",
                        AuditService.Entry.change(
                                "app_session",
                                "77",
                                "STEP_UP",
                                null,
                                "701",
                                "Sensitive role confirmed with OTP"));
    }
}
