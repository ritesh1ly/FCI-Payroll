package in.gov.fci.payroll.arrear.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * I-08 Arrears / Retro (SRS-8-6, SRS-8-8, SRS-8-13, SRS-8-15, SRS-G-30; R-RULE): the periods of an
 * event are its sums by month and an event outside the session's offices is not found; the retro
 * window is shown only when configured; a new event of the current month is never beyond the
 * window, one before it is held beyond the limit only when it starts earlier than the configured
 * window, and an unconfigured window refuses a back-dated event; an order is attached only while
 * the event is open to changes; a sum or count the database leaves empty is zero.
 */
class ArrearOverviewServiceCoverageGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "maker@x",
                    "M",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));
    private final ArrearOverviewService service =
            new ArrearOverviewService(db.named, db.jdbc, policy, parameters, audit, documents);

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(LocalDate.of(2026, 11, 7));
        when(parameters.maxRetroMonths()).thenReturn(OptionalLong.of(12));
    }

    private void inScope(long n) {
        db.value("SELECT COUNT(*) FROM arrear_event e WHERE e.arrear_event_id", n);
    }

    @Test
    void SRS_8_13_thePeriodsOfAnEventAreItsSumsByMonth() {
        inScope(1L);
        db.rows(
                "FROM arrear_detail d JOIN pay_component c",
                row(
                        "YYMM",
                        "202607",
                        "ORIGINAL",
                        new BigDecimal("40000"),
                        "REVISED",
                        new BigDecimal("41200"),
                        "BASIC_DIFF",
                        new BigDecimal("1000"),
                        "DA_DIFF",
                        new BigDecimal("150"),
                        "HRA_DIFF",
                        new BigDecimal("50"),
                        "NET",
                        new BigDecimal("1200")));

        List<ArrearOverviewService.PeriodRow> rows = service.periods(context, 5L);

        assertThat(rows)
                .containsExactly(
                        new ArrearOverviewService.PeriodRow(
                                "202607",
                                new BigDecimal("40000"),
                                new BigDecimal("41200"),
                                new BigDecimal("1000"),
                                new BigDecimal("150"),
                                new BigDecimal("50"),
                                new BigDecimal("1200")));
    }

    @Test
    void SRS_G_2_anEventOutsideTheOfficesIsNotFound() {
        inScope(0L);

        assertThatThrownBy(() -> service.periods(context, 5L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                            assertThat(e.getMessageArgs()).containsExactly("arrear_event", 5L);
                        });
        assertThat(db.queries("FROM arrear_detail d JOIN pay_component c")).isEmpty();
    }

    @Test
    void SRS_8_6_theRetroWindowIsShownOnlyWhenConfigured() {
        assertThat(service.controls(context))
                .isEqualTo(new ArrearOverviewService.Controls(12L, null));
        when(parameters.maxRetroMonths()).thenReturn(OptionalLong.empty());
        assertThat(service.controls(context))
                .isEqualTo(new ArrearOverviewService.Controls(null, null));
    }

    private void manualType() {
        db.rows(
                "ARREAR_MANUAL_EVENT_TYPE",
                row(
                        "EVENT_CODE",
                        "PAY_REV",
                        "EVENT_NAME",
                        "Pay revision",
                        "EVENT_CATEGORY",
                        "PAY"));
        db.rows("SELECT event_type_id FROM arrear_event_type", row("ID", 5L));
    }

    private AuditService.Entry audited() {
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("maker@x"), entry.capture());
        return entry.getValue();
    }

    @Test
    void SRS_8_8_anEventOfTheCurrentMonthIsNeverBeyondTheWindowAndHasNoEnd() {
        manualType();
        when(parameters.maxRetroMonths()).thenReturn(OptionalLong.empty());

        long id =
                service.create(
                        context,
                        new ArrearOverviewService.NewEvent(
                                "PAY_REV",
                                " CIR/1 ",
                                "STAFF",
                                LocalDate.of(2026, 11, 1),
                                null,
                                null));

        assertThat(id).isEqualTo(1000L);
        List<Object> args = db.updates("INSERT INTO arrear_event").get(0).args();
        assertThat(args.get(1)).isEqualTo("CIR/1");
        assertThat(args.get(3)).isNull();
        assertThat(args.get(6)).isEqualTo("202611");
        assertThat(args.get(7)).isEqualTo("202611");
        assertThat(args.get(9)).isEqualTo("N");
        AuditService.Entry entry = audited();
        assertThat(entry.reason())
                .isEqualTo("Retro event PAY_REV 2026-11 raised on I-08: CIR/1")
                .doesNotContain(" to ");
        assertThat(entry.newValue()).isEqualTo("RAISED");
    }

    @Test
    void SRS_8_6_anEventStartingBeforeTheWindowIsHeldBeyondTheLimit() {
        manualType();

        service.create(
                context,
                new ArrearOverviewService.NewEvent(
                        "PAY_REV",
                        "CIR/2",
                        "STAFF",
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 3, 31),
                        7L));

        List<Object> args = db.updates("INSERT INTO arrear_event").get(0).args();
        assertThat(args.get(3)).isEqualTo(Date.valueOf("2025-03-31"));
        assertThat(args.get(6)).isEqualTo("202501");
        assertThat(args.get(7)).isEqualTo("202503");
        assertThat(args.get(8)).isEqualTo(7L);
        assertThat(args.get(9)).isEqualTo("Y");
        assertThat(audited().reason())
                .isEqualTo(
                        "Retro event PAY_REV 2025-01 to 2025-03 raised on I-08: CIR/2 (beyond the"
                                + " back-date limit, SRS-8-6)");
    }

    @Test
    void SRS_8_6_aBackDatedEventWithinTheWindowIsNotHeld() {
        manualType();

        service.create(
                context,
                new ArrearOverviewService.NewEvent(
                        "PAY_REV", "CIR/3", "STAFF", LocalDate.of(2026, 6, 1), null, null));

        assertThat(db.updates("INSERT INTO arrear_event").get(0).args().get(9)).isEqualTo("N");
    }

    @Test
    void SRS_8_6_aBackDatedEventWithoutTheWindowSettingIsRefused() {
        manualType();
        when(parameters.maxRetroMonths()).thenReturn(OptionalLong.empty());

        assertThatThrownBy(
                        () ->
                                service.create(
                                        context,
                                        new ArrearOverviewService.NewEvent(
                                                "PAY_REV",
                                                "CIR/4",
                                                "STAFF",
                                                LocalDate.of(2026, 6, 1),
                                                null,
                                                null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ArrearErrorCode.NOT_CONFIGURED);
                            assertThat(e.getMessageArgs()).containsExactly("MAX_RETRO_PERIOD");
                        });
        assertThat(db.updates("INSERT INTO arrear_event")).isEmpty();
        verify(audit, never()).record(any(), any());
    }

    @Test
    void SRS_G_30_anOrderIsAttachedOnlyWhileTheEventIsOpenToChanges() {
        inScope(1L);
        db.value("SELECT event_status FROM arrear_event", "COMPUTED");
        DocumentStore.AttachmentView stored = mock(DocumentStore.AttachmentView.class);
        when(documents.attach(eq("maker@x"), any())).thenReturn(stored);
        ArrearOverviewService.NewOrder order =
                new ArrearOverviewService.NewOrder(
                        "SANCTION_ORDER",
                        "R-1",
                        LocalDate.of(2026, 11, 1),
                        "HQ",
                        "o.pdf",
                        new byte[] {1});

        assertThat(service.attachOrder(context, 5L, order)).isSameAs(stored);

        db.value("SELECT event_status FROM arrear_event", "POSTED");
        assertThatThrownBy(() -> service.attachOrder(context, 5L, order))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("POSTED"));
        verify(documents).attach(eq("maker@x"), any());
    }

    @Test
    void SRS_8_13_sumsAndCountsTheDatabaseLeavesEmptyAreZero() {
        db.value("SELECT", ScriptedJdbc.NULL);

        ArrearOverviewService.Summary s = service.summary(context);

        assertThat(s.openEvents()).isZero();
        assertThat(s.awaitingApproval()).isZero();
        assertThat(s.arrears()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.arrearMembers()).isZero();
        assertThat(s.recoveries()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.recoveryMembers()).isZero();
    }
}
