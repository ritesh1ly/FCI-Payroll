package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.AuditService.Security;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.orchestration.SecurityOffences.Action;
import in.gov.fci.payroll.iam.orchestration.SecurityOffences.Outcome;
import in.gov.fci.payroll.iam.orchestration.SecurityOffences.Refusal;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.config.SecurityOffenceProperties;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** OD-130 (SRS-G-25 records the refusal; the enforcement is the owner's). */
class SecurityOffencesTest {

    private static final Instant NOW = Instant.parse("2026-11-07T06:00:00Z");
    private static final Long USER = 42L;
    private static final String LOGIN = "maker.delhi@fci-demo.test";
    private static final Refusal REFUSAL =
            new Refusal(
                    USER,
                    "s1",
                    "GET",
                    "/v1/employees/{eis}",
                    "M-01",
                    "VIEW",
                    "AUTHZ-403-PERMISSION");

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final AppUser user = account(USER, AppUser.Status.ACTIVE);

    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityAuditLogger.LOGGER_NAME);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void capture() {
        when(properties.businessZone()).thenReturn(ZoneId.of("Asia/Kolkata"));
        when(users.findHoldersOfRole(eq("SYSTEM_ADMINISTRATOR"), any())).thenReturn(List.of());
        when(users.lockById(USER)).thenReturn(Optional.of(user));
        when(audit.latestSecurityEvent(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lines.start();
        logger.addAppender(lines);
        MDC.put(MdcKeys.CLIENT_IP, "10.1.2.3");
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
        MDC.clear();
    }

    private static AppUser account(Long id, AppUser.Status status) {
        AppUser account = mock(AppUser.class);
        when(account.getId()).thenReturn(id);
        when(account.getLoginId()).thenReturn(LOGIN);
        when(account.getStatus()).thenReturn(status);
        return account;
    }

    private SecurityOffences offences(SecurityOffenceProperties settings) {
        return offences(settings, TransactionOperations.withoutTransaction());
    }

    private SecurityOffences offences(
            SecurityOffenceProperties settings, TransactionOperations tx) {
        return new SecurityOffences(
                users,
                sessions,
                contexts,
                audit,
                settings,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                tx);
    }

    private static SecurityOffenceProperties on(String logout, int suspendAfter) {
        return new SecurityOffenceProperties("Y", logout, suspendAfter);
    }

    private void counted(long count) {
        when(audit.countSecurityEvents(LOGIN, Security.UNAUTHORIZED_ACCESS, Instant.EPOCH))
                .thenReturn(count);
    }

    private String lastLine() {
        return lines.list.getLast().getFormattedMessage();
    }

    @Test
    void SRS_G_25_OD_130_aRefusalIsRecordedCountedAndLogsTheUserOut() {
        counted(1);
        Outcome outcome = offences(on("Y", 3)).handle(REFUSAL);

        assertThat(outcome).isEqualTo(new Outcome(1, Action.LOGOUT, true));
        verify(audit)
                .securityEventInTransaction(
                        Security.UNAUTHORIZED_ACCESS,
                        LOGIN,
                        "api:s1",
                        "GET /v1/employees/{eis}",
                        "method=GET path=/v1/employees/{eis} screen=M-01 action=VIEW"
                                + " code=AUTHZ-403-PERMISSION");
        verify(sessions).endAllSessions(USER, NOW, AppSession.EndReason.REVOCATION);
        verify(contexts).evictAll();
        verify(user, never()).changeStatus(any(), any(), any());
    }

    @Test
    void OD_130_theThirdOffenceSuspendsWithItsJustificationInEventAndAudit() {
        counted(3);
        when(audit.recentSecurityEvents(LOGIN, Security.UNAUTHORIZED_ACCESS, Instant.EPOCH, 10))
                .thenReturn(
                        List.of(
                                view("GET /v1/admin/users", "10.1.2.3", NOW),
                                view("POST /v1/rules", "10.1.2.4", NOW.minusSeconds(60))));

        Outcome outcome = offences(on("Y", 3)).handle(REFUSAL);

        assertThat(outcome).isEqualTo(new Outcome(3, Action.SUSPENDED, true));
        // SUSPENDED, never LOCKED (OTP self-service) and never DEACTIVATED (fixed reasons).
        verify(user).changeStatus(AppUser.Status.SUSPENDED, null, NOW);
        verify(users).save(user);
        verify(sessions).endAllSessions(USER, NOW, AppSession.EndReason.ADMIN_REVOKE);
        ArgumentCaptor<String> why = ArgumentCaptor.forClass(String.class);
        verify(audit)
                .securityEventInTransaction(
                        eq(Security.ACCOUNT_SUSPENDED_OFFENCE),
                        eq(LOGIN),
                        eq("app_user"),
                        eq("42"),
                        why.capture());
        assertThat(why.getValue())
                .contains("3 unauthorized access attempts")
                .contains("2026-11-07T06:00:00Z GET /v1/admin/users from 10.1.2.3")
                .contains("2026-11-07T05:59:00Z POST /v1/rules from 10.1.2.4");
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("SYSTEM"), entry.capture());
        assertThat(entry.getValue().fieldName()).isEqualTo("user_status");
        assertThat(entry.getValue().oldValue()).isEqualTo("ACTIVE");
        assertThat(entry.getValue().newValue()).isEqualTo("SUSPENDED");
        assertThat(entry.getValue().reason()).isEqualTo(why.getValue());
        assertThat(lastLine()).contains("offence=3").contains("action=SUSPENDED");
    }

    private static AuditService.SecurityEventView view(String target, String ip, Instant at) {
        return new AuditService.SecurityEventView(
                Security.UNAUTHORIZED_ACCESS.name(), target, "detail", ip, at);
    }

    @Test
    void OD_130_aLongJustificationIsCutToTheAuditColumnButKeptWholeInTheEvent() {
        counted(3);
        List<AuditService.SecurityEventView> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(view("GET /v1/a/very/long/route/of/the/application/" + i, "10.1.2.3", NOW));
        }
        when(audit.recentSecurityEvents(LOGIN, Security.UNAUTHORIZED_ACCESS, Instant.EPOCH, 10))
                .thenReturn(many);

        offences(on("Y", 3)).handle(REFUSAL);

        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("SYSTEM"), entry.capture());
        assertThat(entry.getValue().reason()).hasSize(500);
    }

    @Test
    void OD_130_theCountStartsAgainAfterTheLastReinstatement() {
        Instant reinstated = NOW.minusSeconds(3600);
        when(audit.latestSecurityEvent(Security.OFFENCE_COUNT_RESET, "app_user", "42"))
                .thenReturn(Optional.of(reinstated));
        when(audit.countSecurityEvents(LOGIN, Security.UNAUTHORIZED_ACCESS, reinstated))
                .thenReturn(1L);
        // Before the reinstatement there were many: they no longer count.
        counted(9);

        Outcome outcome = offences(on("Y", 3)).handle(REFUSAL);

        assertThat(outcome.offenceNumber()).isEqualTo(1);
        assertThat(outcome.action()).isEqualTo(Action.LOGOUT);
    }

    @Test
    void OD_130_reinstatingWritesTheReset() {
        offences(on("Y", 3)).resetCount("sysadmin@x", USER, "cleared by the security officer");

        verify(audit)
                .securityEventInTransaction(
                        Security.OFFENCE_COUNT_RESET,
                        "sysadmin@x",
                        "app_user",
                        "42",
                        "cleared by the security officer");
    }

    @Test
    void OD_130_aSuspensionForOffencesIsTheOneAfterTheLastReset() {
        SecurityOffences service = offences(on("Y", 3));
        assertThat(service.isSuspendedForOffences(USER)).isFalse();

        when(audit.latestSecurityEvent(Security.ACCOUNT_SUSPENDED_OFFENCE, "app_user", "42"))
                .thenReturn(Optional.of(NOW));
        assertThat(service.isSuspendedForOffences(USER)).isTrue();

        when(audit.latestSecurityEvent(Security.OFFENCE_COUNT_RESET, "app_user", "42"))
                .thenReturn(Optional.of(NOW.plusSeconds(1)));
        assertThat(service.isSuspendedForOffences(USER)).isFalse();
    }

    @Test
    void OD_130_theLastActiveAdministratorIsLoggedOutButNeverSuspended() {
        AppUser other = account(7L, AppUser.Status.SUSPENDED);
        when(users.findHoldersOfRole(eq("SYSTEM_ADMINISTRATOR"), any()))
                .thenReturn(List.of(7L, USER));
        when(users.lockAll(List.of(7L, USER))).thenReturn(List.of(other, user));
        counted(3);

        Outcome outcome = offences(on("Y", 3)).handle(REFUSAL);

        assertThat(outcome).isEqualTo(new Outcome(3, Action.LAST_ADMIN_PROTECTED, true));
        verify(user, never()).changeStatus(any(), any(), any());
        verify(sessions).endAllSessions(USER, NOW, AppSession.EndReason.REVOCATION);
        assertThat(lastLine()).contains("action=LAST_ADMIN_PROTECTED");
    }

    @Test
    void OD_130_theLastAdministratorWithLogoutOffStaysSignedIn() {
        when(users.findHoldersOfRole(eq("SYSTEM_ADMINISTRATOR"), any())).thenReturn(List.of(USER));
        when(users.lockAll(List.of(USER))).thenReturn(List.of(user));
        counted(3);

        Outcome outcome = offences(on("N", 3)).handle(REFUSAL);

        assertThat(outcome).isEqualTo(new Outcome(3, Action.LAST_ADMIN_PROTECTED, false));
        verify(sessions, never()).endAllSessions(any(), any(), any());
        verify(contexts, never()).evictAll();
    }

    @Test
    void OD_130_anAdministratorWithAnotherActiveAdministratorIsSuspendedAsAnyone() {
        AppUser other = account(7L, AppUser.Status.ACTIVE);
        when(users.findHoldersOfRole(eq("SYSTEM_ADMINISTRATOR"), any()))
                .thenReturn(List.of(7L, USER));
        when(users.lockAll(List.of(7L, USER))).thenReturn(List.of(other, user));
        counted(3);

        Outcome outcome = offences(on("Y", 3)).handle(REFUSAL);

        assertThat(outcome.action()).isEqualTo(Action.SUSPENDED);
        verify(user).changeStatus(AppUser.Status.SUSPENDED, null, NOW);
    }

    @Test
    void OD_130_enabledOffWritesOnlyTheEventAndTheCriticalLog() {
        counted(99);
        Outcome outcome = offences(new SecurityOffenceProperties("N", "Y", 3)).handle(REFUSAL);

        assertThat(outcome).isEqualTo(new Outcome(0, Action.LOGGED, false));
        // Not the counted type: nothing noted while off can suspend anyone once it is switched on.
        verify(audit)
                .securityEventInTransaction(
                        eq(Security.UNAUTHORIZED_ACCESS_NOTED),
                        eq(LOGIN),
                        eq("api:s1"),
                        anyString(),
                        anyString());
        verify(audit, never())
                .securityEventInTransaction(
                        eq(Security.UNAUTHORIZED_ACCESS), any(), any(), any(), any());
        verify(sessions, never()).endAllSessions(any(), any(), any());
        verify(user, never()).changeStatus(any(), any(), any());
        assertThat(lastLine()).contains("event=SECURITY_OFFENCE").contains("action=LOGGED");
    }

    @Test
    void OD_130_logoutOffStillCountsAndStillSuspendsAtTheThreshold() {
        counted(2);
        assertThat(offences(on("N", 3)).handle(REFUSAL))
                .isEqualTo(new Outcome(2, Action.LOGGED, false));
        verify(sessions, never()).endAllSessions(any(), any(), any());

        counted(3);
        assertThat(offences(on("N", 3)).handle(REFUSAL).action()).isEqualTo(Action.SUSPENDED);
    }

    @Test
    void OD_130_suspendAfterZeroLogsOutForEverAndNeverSuspends() {
        counted(500);
        Outcome outcome = offences(on("Y", 0)).handle(REFUSAL);

        assertThat(outcome).isEqualTo(new Outcome(500, Action.LOGOUT, true));
        verify(user, never()).changeStatus(any(), any(), any());
    }

    @Test
    void OD_130_suspendAfterThreeIsNotReachedByTheSecondOffence() {
        counted(2);
        assertThat(offences(on("Y", 3)).handle(REFUSAL).action()).isEqualTo(Action.LOGOUT);
    }

    @Test
    void OD_130_anAccountThatIsNoLongerActiveIsLeftAsItIs() {
        AppUser locked = account(USER, AppUser.Status.LOCKED);
        when(users.lockById(USER)).thenReturn(Optional.of(locked));
        counted(3);

        assertThat(offences(on("Y", 3)).handle(REFUSAL).action()).isEqualTo(Action.LOGOUT);
        verify(locked, never()).changeStatus(any(), any(), any());
    }

    @Test
    void OD_130_aFailedRecordLeavesTheRefusalAloneAndSaysSo() {
        when(users.lockById(USER)).thenThrow(new QueryTimeoutException("lock timeout"));

        Outcome outcome = offences(on("Y", 3)).handle(REFUSAL);

        assertThat(outcome).isEqualTo(new Outcome(0, Action.RECORD_FAILED, false));
        verify(contexts, never()).evictAll();
        assertThat(lastLine()).contains("action=RECORD_FAILED");
    }

    @Test
    void OD_130_aVanishedUserRowIsAFailedRecord() {
        when(users.lockById(USER)).thenReturn(Optional.empty());

        assertThat(offences(on("Y", 3)).handle(REFUSAL).action()).isEqualTo(Action.RECORD_FAILED);
    }

    @Test
    void OD_130_anAdministratorRowVanishedUnderTheLockIsAFailedRecord() {
        when(users.findHoldersOfRole(eq("SYSTEM_ADMINISTRATOR"), any())).thenReturn(List.of(USER));
        when(users.lockAll(List.of(USER))).thenReturn(List.of());

        assertThat(offences(on("Y", 3)).handle(REFUSAL).action()).isEqualTo(Action.RECORD_FAILED);
    }

    @Test
    void OD_130_theCriticalLineHasOneShapeAndNoPersonalData() {
        counted(2);
        offences(on("Y", 3)).handle(REFUSAL);

        ILoggingEvent line = lines.list.getLast();
        assertThat(line.getLevel()).isEqualTo(Level.ERROR);
        assertThat(line.getMarkerList()).extracting("name").containsExactly("SECURITY_OFFENCE");
        assertThat(line.getFormattedMessage())
                .isEqualTo(
                        "event=SECURITY_OFFENCE severity=CRITICAL user=42 offence=2 method=GET"
                                + " path=/v1/employees/{eis} ip=10.1.2.3 action=LOGOUT");
        assertThat(line.getFormattedMessage()).doesNotContain(LOGIN).doesNotContain("fci-demo");
    }

    @Test
    void OD_130_theSessionsNextRefusalIsNotedNotCountedAndEndsNothingAgain() {
        when(audit.hasSecurityEvent(LOGIN, Security.UNAUTHORIZED_ACCESS, "api:s1"))
                .thenReturn(true);
        counted(2);

        Outcome outcome = offences(on("Y", 2)).handle(REFUSAL);

        assertThat(outcome).isEqualTo(new Outcome(2, Action.ALREADY_COUNTED, true));
        verify(audit)
                .securityEventInTransaction(
                        eq(Security.UNAUTHORIZED_ACCESS_NOTED),
                        eq(LOGIN),
                        eq("api:s1"),
                        anyString(),
                        anyString());
        verify(audit, never())
                .securityEventInTransaction(
                        eq(Security.UNAUTHORIZED_ACCESS), any(), any(), any(), any());
        // The threshold is met, but this is no new offence; a new sign-in is not ended either.
        verify(user, never()).changeStatus(any(), any(), any());
        verify(sessions, never()).endAllSessions(any(), any(), any());
        assertThat(lastLine()).contains("offence=2").contains("action=ALREADY_COUNTED");
    }

    @Test
    void OD_130_withLogoutOffOneSessionStillCountsOnceAndTheResponseSaysNoEnd() {
        when(audit.hasSecurityEvent(LOGIN, Security.UNAUTHORIZED_ACCESS, "api:s1"))
                .thenReturn(true);
        counted(1);

        assertThat(offences(on("N", 3)).handle(REFUSAL))
                .isEqualTo(new Outcome(1, Action.ALREADY_COUNTED, false));
    }

    /** The fake answers what the database would, under the row lock of each transaction. */
    private SecurityOffences locked(ReentrantLock row, List<String> counted) {
        when(users.lockById(USER))
                .thenAnswer(
                        call -> {
                            row.lock();
                            return Optional.of(user);
                        });
        org.mockito.Mockito.doAnswer(
                        call -> {
                            counted.add(call.getArgument(2));
                            return null;
                        })
                .when(audit)
                .securityEventInTransaction(
                        eq(Security.UNAUTHORIZED_ACCESS), any(), any(), any(), any());
        when(audit.hasSecurityEvent(eq(LOGIN), eq(Security.UNAUTHORIZED_ACCESS), anyString()))
                .thenAnswer(call -> counted.contains(call.getArgument(2)));
        when(audit.countSecurityEvents(LOGIN, Security.UNAUTHORIZED_ACCESS, Instant.EPOCH))
                .thenAnswer(call -> (long) counted.size());
        return offences(
                on("Y", 3),
                new TransactionOperations() {
                    @Override
                    public <T> T execute(TransactionCallback<T> action) {
                        try {
                            return action.doInTransaction(null);
                        } finally {
                            row.unlock();
                        }
                    }
                });
    }

    /**
     * One stale link: M-01 sends five reads at once, and that is one offence (never a suspension).
     */
    @Test
    void OD_130_fiveRefusedCallsOfOneSessionAtOnceCountOnce() {
        List<String> counted = new CopyOnWriteArrayList<>();
        SecurityOffences service = locked(new ReentrantLock(), counted);

        List<CompletableFuture<Outcome>> calls =
                java.util.stream.IntStream.range(0, 5)
                        .mapToObj(i -> CompletableFuture.supplyAsync(() -> service.handle(REFUSAL)))
                        .toList();
        List<Action> actions = calls.stream().map(c -> c.join().action()).toList();

        assertThat(counted).containsExactly("api:s1");
        assertThat(actions)
                .containsOnlyOnce(Action.LOGOUT)
                .doesNotContain(Action.SUSPENDED)
                .filteredOn(a -> a == Action.ALREADY_COUNTED)
                .hasSize(4);
        verify(user, never()).changeStatus(any(), any(), any());
    }

    @Test
    void OD_130_twoSessionsAtOnceCountTwiceOnTheRowLock() {
        List<String> counted = new CopyOnWriteArrayList<>();
        SecurityOffences service = locked(new ReentrantLock(), counted);
        Refusal other = new Refusal(USER, "s2", "GET", "/v1/x", "M-01", "VIEW", "CODE");

        CompletableFuture<Outcome> first =
                CompletableFuture.supplyAsync(() -> service.handle(REFUSAL));
        CompletableFuture<Outcome> second =
                CompletableFuture.supplyAsync(() -> service.handle(other));

        assertThat(List.of(first.join().offenceNumber(), second.join().offenceNumber()))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void OD_130_settingsReadAsTheKeysSay() {
        assertThat(new SecurityOffenceProperties("Y", "Y", 3).logsOut()).isTrue();
        assertThat(new SecurityOffenceProperties("Y", "N", 3).logsOut()).isFalse();
        assertThat(new SecurityOffenceProperties("N", "Y", 3).logsOut()).isFalse();
        assertThat(new SecurityOffenceProperties("N", "Y", 3).suspendsAt(3)).isFalse();
        assertThat(new SecurityOffenceProperties("Y", "Y", 0).suspendsAt(3)).isFalse();
        assertThat(new SecurityOffenceProperties("Y", "Y", 3).suspendsAt(2)).isFalse();
        assertThat(new SecurityOffenceProperties("Y", "Y", 3).suspendsAt(3)).isTrue();
    }
}
