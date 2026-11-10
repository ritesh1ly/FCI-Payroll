package in.gov.fci.payroll.arrear.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.GeneratedKeysStub;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The one retrospective mechanism (plan 13 phase 13b; SRS-21-7, SRS-8-13, SRS-8-14; D13-1): an open
 * event of the same source is reused; an event type that is not configured is a programming error,
 * never a silent skip; only a raised or computed single-member event is computed again, and an
 * event decided meanwhile is left as it is. An event whose lines the owning module states (plan 13
 * phase 13g) is written COMPUTED with them, refuses a pay code with no component, and is never
 * recomputed.
 */
class RetroEventServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ArrearRegisterService register = mock(ArrearRegisterService.class);
    private final RetroEventService service;
    private final AccessContext context = mock(AccessContext.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SystemParameters parameters = mock(SystemParameters.class);

    RetroEventServiceTest() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(parameters.today()).thenReturn(LocalDate.of(2026, 10, 29));
        when(parameters.maxRetroMonths()).thenReturn(java.util.OptionalLong.of(60));
        service =
                new RetroEventService(
                        jdbc,
                        mock(PendingSalary.class),
                        mock(AuditService.class),
                        events,
                        transactions,
                        register,
                        parameters);
    }

    private static RetroEvents.Raise raise(String code) {
        return new RetroEvents.Raise(
                7L,
                LocalDate.of(2026, 7, 1),
                code,
                "LEAVE",
                "leave_transaction",
                "HCA-1",
                "Back-dated leave",
                "ddo@x");
    }

    @Test
    void SRS_21_7_anOpenEventOfTheSameSourceIsReusedAndAnUnknownTypeIsAnError() {
        when(jdbc.queryForList(
                        contains("FROM arrear_event WHERE source_module"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(41L));
        assertThat(service.raise(raise("LEAVE"))).isEqualTo(41L);
        when(jdbc.queryForList(
                        contains("FROM arrear_event WHERE source_module"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.raise(raise("NO_SUCH_TYPE")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never())
                .update(
                        org.mockito.ArgumentMatchers.argThat(
                                GeneratedKeysStub.insertOf("INSERT INTO arrear_event (")),
                        any(org.springframework.jdbc.support.KeyHolder.class));
        eventType(3L);
        assertThatThrownBy(() -> service.raise(raise("LEAVE")))
                .as("the database returned no key")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Oracle review F2: the event is the row this INSERT wrote (its generated key), never MAX(id)
     * of the same source - identities are cached and NOORDER, and another session's event of the
     * same source can hold the higher id.
     */
    @Test
    void SRS_21_7_theEventIsTheKeyOfItsOwnInsertNeverTheHighestIdOfTheSource() {
        eventType(3L);
        GeneratedKeysStub.answer(jdbc, "INSERT INTO arrear_event (", 42L);
        when(jdbc.queryForObject(
                        contains("MAX(arrear_event_id)"), eq(Long.class), any(Object[].class)))
                .thenReturn(99L);
        assertThat(service.raise(raise("LEAVE"))).isEqualTo(42L);
        verify(jdbc).update(contains("INSERT INTO arrear_event_member"), eq(42L), eq(7L));
        assertThat(GeneratedKeysStub.args(jdbc, "INSERT INTO arrear_event (").getFirst())
                .as("the type id looked up first, an INSERT ... VALUES (keys are returned for it)")
                .isEqualTo(3L);
    }

    private void eventType(long id) {
        when(jdbc.queryForList(
                        contains("FROM arrear_event_type WHERE event_code"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(id));
    }

    @Test
    void SRS_8_13_onlyARaisedOrComputedSingleMemberEventIsComputedAgain() {
        when(register.requireEvent(context, 5L, "SUBMIT"))
                .thenReturn(Map.of("EVENT_STATUS", "APPROVED"));
        assertThatThrownBy(() -> service.recompute(context, 5L))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ArrearErrorCode.STATE);
        when(register.requireEvent(context, 5L, "SUBMIT"))
                .thenReturn(Map.of("EVENT_STATUS", "RAISED"));
        when(jdbc.queryForList(
                        contains("FROM arrear_event_member"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.recompute(context, 5L))
                .as("a bulk event is recomputed by its job")
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ArrearErrorCode.STATE);
    }

    @Test
    void SRS_8_14_anEventDecidedMeanwhileIsLeftAsItIs() {
        when(jdbc.queryForMap(contains("SELECT event_status, effective_from"), any(Object[].class)))
                .thenReturn(
                        Map.of(
                                "EVENT_STATUS",
                                "APPROVED",
                                "EFFECTIVE_FROM",
                                Date.valueOf(LocalDate.of(2026, 7, 1))));
        assertThat(service.compute(5L, 7L, "maker@x")).isEmpty();
        verify(jdbc, never())
                .update(contains("SET arrear_status = 'REVERSED'"), any(Object[].class));
        verify(jdbc, never()).update(anyString(), eq(5L));
    }

    private static RetroEvents.Known known(RetroEvents.Line... lines) {
        return new RetroEvents.Known(
                7L,
                "PENALTY",
                "PENALTY",
                "penalty_revoke",
                "12",
                "Reversing arrear of PEN-1",
                "ddo@x",
                List.of(lines));
    }

    private static RetroEvents.Line line(String yymm, String amount) {
        return new RetroEvents.Line(yymm, 912, new BigDecimal(amount));
    }

    // SC-13-0085: each line names the pay component the event revises.
    @Test
    void SRS_21_10_statedLinesAreWrittenComputedOnceAndAPayCodeNeedsAComponent() {
        when(jdbc.queryForList(
                        contains("FROM arrear_event WHERE source_module"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(41L))
                .thenReturn(List.of());
        assertThat(service.known(known(line("202608", "1000")))).isEqualTo(41L);

        when(jdbc.queryForList(
                        contains("FROM pay_component WHERE legacy_pay_code"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.known(known(line("202608", "1000"))))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ArrearErrorCode.NOT_CONFIGURED);

        when(jdbc.queryForList(
                        contains("FROM pay_component WHERE legacy_pay_code"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(88L));
        eventType(3L);
        GeneratedKeysStub.answer(jdbc, "INSERT INTO arrear_event (", 42L);
        assertThat(service.known(known(line("202609", "400"), line("202608", "600"))))
                .isEqualTo(42L);
        verify(jdbc)
                .update(
                        contains("INSERT INTO arrear_detail"),
                        eq(42L),
                        eq(7L),
                        eq(88L),
                        eq("202608"),
                        eq(new BigDecimal("600")),
                        eq(Date.valueOf(LocalDate.of(2026, 8, 1))));
        List<Object> event = GeneratedKeysStub.args(jdbc, "beyond_window_flag, event_status");
        assertThat(event.getFirst()).as("the PENALTY type").isEqualTo(3L);
        assertThat(event.get(2)).isEqualTo(Date.valueOf(LocalDate.of(2026, 8, 1)));
        assertThat(event.subList(4, 6)).containsExactly("202608", "202609");
        assertThat(event.subList(9, 11)).containsExactly("N", "COMPUTED");
    }

    @Test
    void SRS_21_10_aKnownEventNeedsALineAndARemarkAndIsNeverRecomputed() {
        assertThatThrownBy(() -> known()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RetroEvents.Known(
                                        7L,
                                        "PENALTY",
                                        "PENALTY",
                                        "e",
                                        "k",
                                        " ",
                                        "ddo@x",
                                        List.of(line("202608", "1"))))
                .isInstanceOf(IllegalArgumentException.class);
        when(register.requireEvent(context, 5L, "SUBMIT"))
                .thenReturn(Map.of("EVENT_STATUS", "COMPUTED"));
        when(jdbc.queryForList(
                        contains("FROM arrear_event_member"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(7L));
        when(jdbc.queryForObject(
                        contains("recomputed_amount IS NULL"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(2);
        assertThatThrownBy(() -> service.recompute(context, 5L))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ArrearErrorCode.STATE);
    }

    @Test
    void SRS_21_9_aBackDatedEventWithoutARemarkIsRefusedWhenRaisedOrStated() {
        for (String remark : java.util.Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(
                            () ->
                                    new RetroEvents.Raise(
                                            7L,
                                            java.time.LocalDate.of(2026, 8, 1),
                                            "LEAVE",
                                            "LEAVE",
                                            "leave_application",
                                            "L-1",
                                            remark,
                                            "ddo@x"))
                    .as(String.valueOf(remark))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SRS-21-9");
            assertThatThrownBy(
                            () ->
                                    new RetroEvents.Known(
                                            7L,
                                            "PENALTY",
                                            "PENALTY",
                                            "e",
                                            "k",
                                            remark,
                                            "ddo@x",
                                            List.of(line("202608", "1"))))
                    .as(String.valueOf(remark))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SRS-21-9");
        }
        assertThat(
                        new RetroEvents.Raise(
                                        7L,
                                        java.time.LocalDate.of(2026, 8, 1),
                                        "LEAVE",
                                        "LEAVE",
                                        "leave_application",
                                        "L-1",
                                        "Back-dated leave",
                                        "ddo@x")
                                .reason())
                .isEqualTo("Back-dated leave");
    }

    @Test
    void SRS_21_10_anUnconfiguredTypeOrAnEventNotFoundAfterItsInsertIsAnError() {
        when(jdbc.queryForList(
                        contains("FROM pay_component WHERE legacy_pay_code"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(88L));
        assertThatThrownBy(() -> service.known(known(line("202608", "1000"))))
                .isInstanceOf(IllegalArgumentException.class);
        RetroEvents.Header header =
                new RetroEvents.Header(
                        "PAY_REVISION",
                        null,
                        LocalDate.of(2026, 7, 1),
                        "PAYFIX",
                        "pay_fixation",
                        "PF-1",
                        "r".repeat(150),
                        null,
                        "ddo@x");
        assertThatThrownBy(() -> service.header(header))
                .isInstanceOf(IllegalArgumentException.class);

        eventType(3L);
        assertThatThrownBy(() -> service.known(known(line("202608", "1000"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.header(header)).isInstanceOf(IllegalStateException.class);
        assertThat(GeneratedKeysStub.args(jdbc, "circular_order_id, event_status").get(1))
                .as("the reference cut to the column")
                .isEqualTo("r".repeat(100));
    }

    private void inserted(long id) {
        when(jdbc.queryForList(
                        contains("FROM arrear_event WHERE source_module"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of());
        eventType(3L);
        GeneratedKeysStub.answer(jdbc, "INSERT INTO arrear_event (", id);
        when(jdbc.queryForList(
                        contains("FROM pay_component WHERE legacy_pay_code"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(88L));
    }

    // SC-13-0075 / SC-13-0088: a back-dated event older than the HQ limit is held, not computed.
    @Test
    void SRS_8_6_aRaisedEventBeyondTheBackDateLimitIsHeldUncomputed() {
        inserted(51L);
        RetroEvents.Raise old =
                new RetroEvents.Raise(
                        7L,
                        LocalDate.of(2021, 9, 30),
                        "LEAVE",
                        "LEAVE",
                        "leave_transaction",
                        "HCA-9",
                        "Back-dated leave",
                        "ddo@x");
        assertThat(service.raise(old)).isEqualTo(51L);
        assertThat(GeneratedKeysStub.args(jdbc, "beyond_window_flag").get(8)).isEqualTo("Y");
        verify(events, never()).publishEvent(any(Object.class));

        RetroEvents.Raise inside =
                new RetroEvents.Raise(
                        7L,
                        LocalDate.of(2021, 10, 1),
                        "LEAVE",
                        "LEAVE",
                        "leave_transaction",
                        "HCA-10",
                        "Back-dated leave",
                        "ddo@x");
        service.raise(inside);
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    void SRS_8_6_aStatedEventBeyondTheBackDateLimitIsWrittenRaised() {
        inserted(52L);
        service.known(known(line("202109", "500")));
        assertThat(GeneratedKeysStub.args(jdbc, "beyond_window_flag, event_status").subList(9, 11))
                .containsExactly("Y", "RAISED");
    }

    /**
     * SRS-8-6, R-RULE, KB CF-49 (CLR-147, OD-97): the back-date limit has no source value and is
     * unset outside the demo data. A back-dated event is then refused "not configured" - never held
     * or released against a guessed limit - while an event of the current period needs no limit.
     */
    @Test
    void SRS_8_6_anUnsetBackDateLimitRefusesABackDatedEventAndKeepsOneOfTheCurrentPeriod() {
        when(parameters.maxRetroMonths()).thenReturn(java.util.OptionalLong.empty());
        inserted(53L);
        assertThatThrownBy(() -> service.raise(raise("LEAVE")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ArrearErrorCode.NOT_CONFIGURED);
                            assertThat(e.getMessageArgs()).containsExactly("MAX_RETRO_PERIOD");
                        });
        assertThatThrownBy(() -> service.known(known(line("202609", "500"))))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ArrearErrorCode.NOT_CONFIGURED);
        verify(events, never()).publishEvent(any(Object.class));

        RetroEvents.Raise current =
                new RetroEvents.Raise(
                        7L,
                        LocalDate.of(2026, 10, 1),
                        "LEAVE",
                        "LEAVE",
                        "leave_transaction",
                        "HCA-11",
                        "Back-dated leave",
                        "ddo@x");
        assertThat(service.raise(current)).isEqualTo(53L);
        assertThat(GeneratedKeysStub.args(jdbc, "beyond_window_flag").get(8)).isEqualTo("N");
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    void SRS_8_6_anEventBeyondTheLimitIsComputedOnlyAfterItsHqException() {
        java.util.HashMap<String, Object> held = new java.util.HashMap<>();
        held.put("EVENT_STATUS", "RAISED");
        held.put("BEYOND_WINDOW_FLAG", "Y");
        held.put("HQ_EXCEPTION_APPROVAL_ID", null);
        when(register.requireEvent(context, 5L, "SUBMIT")).thenReturn(held);
        assertThatThrownBy(() -> service.recompute(context, 5L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ArrearErrorCode.BEYOND_WINDOW);
                            assertThat(e.getMessageArgs()).containsExactly(60);
                        });
        held.put("HQ_EXCEPTION_APPROVAL_ID", 77L);
        when(jdbc.queryForList(
                        contains("FROM arrear_event_member"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.recompute(context, 5L))
                .as("past the window check: a bulk event is still refused as such")
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ArrearErrorCode.STATE);
    }

    @Test
    void SRS_8_6_anApprovedExceptionComputesTheEventOrReleasesItsStatedLines() {
        when(jdbc.queryForObject(
                        contains("recomputed_amount IS NULL"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(2);
        service.releaseException(5L, "checker.hq@x");
        verify(jdbc).update(contains("SET event_status = 'COMPUTED'"), eq(5L));
        verify(events, never()).publishEvent(any(Object.class));

        when(jdbc.queryForObject(
                        contains("recomputed_amount IS NULL"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(0);
        when(jdbc.queryForList(
                        contains("FROM arrear_event_member"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of())
                .thenReturn(List.of(7L));
        service.releaseException(6L, "checker.hq@x");
        verify(events, never()).publishEvent(any(Object.class));
        service.releaseException(6L, "checker.hq@x");
        verify(events).publishEvent(new RetroEventService.Raised(6L, 7L, "checker.hq@x"));
    }
}
