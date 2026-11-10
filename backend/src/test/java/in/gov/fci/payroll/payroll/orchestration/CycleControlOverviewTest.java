package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * C-01 overview (SRS l.1755-1767, SRS-15-5, SRS-G-32): every read is on C-01 VIEW at the session's
 * unit, a session with no office is refused, a note needs the Maker's right and a cycle.
 */
class CycleControlOverviewTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AccessContext context = mock(AccessContext.class);
    private final OrgLocation unit = mock(OrgLocation.class);
    private final CycleControlOverview service =
            new CycleControlOverview(
                    jdbc,
                    org,
                    policy,
                    mock(CycleGateService.class),
                    mock(CycleLeases.class),
                    mock(PreRunChecks.class),
                    documents,
                    Clock.systemUTC());

    @BeforeEach
    void session() {
        when(context.locationId()).thenReturn(9L);
        when(context.loginId()).thenReturn("maker.delhi");
        when(context.dataScope()).thenReturn(Set.of(5L));
        when(unit.getId()).thenReturn(5L);
        when(unit.getCode()).thenReturn("DH001");
        when(org.cycleUnitOf(9L)).thenReturn(Optional.of(unit));
    }

    private void refuse(String screen, String action) {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .requireAt(context, screen, action, 5L);
    }

    @Test
    void SRS_15_6_aSessionWithoutOfficeIsRefused() {
        when(context.locationId()).thenReturn(null);
        assertThatThrownBy(() -> service.overview(context, PERIOD))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void SRS_G_25_everyReadIsOnC01ViewAtTheSessionsUnit() {
        refuse("C-01", "VIEW");
        PageSpec page = new PageSpec(0, 20);
        assertThatThrownBy(() -> service.overview(context, PERIOD))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.periods(context, page)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.report(context, PERIOD, page))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.orders(context, PERIOD)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.file(context, "::1", PERIOD, 3L))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(jdbc);
        verifyNoInteractions(documents);
    }

    @Test
    void SRS_G_32_storingANoteIsTheMakersRight() {
        refuse("C-02", "CREATE");
        assertThatThrownBy(() -> service.attachOrder(context, PERIOD, note()))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(documents);
    }

    @Test
    void SRS_G_32_aPeriodWithNoCycleTakesNoNote() {
        assertThatThrownBy(() -> service.attachOrder(context, PERIOD, note()))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(documents);
    }

    @Test
    void SRS_G_32_theNoteIsStoredAgainstTheInstance_notWhatTheClientSent() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(
                        List.<Map<String, Object>>of(
                                Map.<String, Object>of("CYCLE_INSTANCE_ID", 41L)));
        service.attachOrder(context, PERIOD, note());
        verify(documents)
                .attach(
                        eq("maker.delhi"),
                        ArgumentMatchers.argThat(
                                a ->
                                        "payroll_cycle_instance".equals(a.entityName())
                                                && "41".equals(a.entityKey())
                                                && !a.containsPii()));
    }

    @Test
    void SRS_G_32_noCycleAtAllListsNoNote_andAFileIsNotFound() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of());
        assertThat(service.orders(context, PERIOD)).isEmpty();
        assertThatThrownBy(() -> service.file(context, "::1", PERIOD, 3L))
                .isInstanceOf(FciException.class);
        verify(documents, Mockito.never())
                .read(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void SRS_G_32_aPeriodWithNoCycleShowsTheUnitsLatestNotes() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(41L));
        service.orders(context, PERIOD);
        verify(documents).active("payroll_cycle_instance", "41");
    }

    private static DocumentStore.NewAttachment note() {
        return new DocumentStore.NewAttachment(
                "forged",
                "1",
                "SANCTION_ORDER",
                "FCI/1",
                LocalDate.of(2026, 10, 1),
                "RO Delhi",
                "note.pdf",
                new byte[] {1},
                true);
    }
}
