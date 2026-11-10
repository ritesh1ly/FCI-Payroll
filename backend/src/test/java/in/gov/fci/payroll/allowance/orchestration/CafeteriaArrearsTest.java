package in.gov.fci.payroll.allowance.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.allowance.business.model.CafeteriaElection;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SRS-11-16, SRS-G-33: the arrear of a cafeteria selection received after the freeze: each locked
 * month from its start date is set against what was paid; the selection becomes ARREAR_RAISED with
 * the event linked; a supplementary payment and an earlier arrear count as paid; a month that
 * differs in nothing raises nothing.
 */
class CafeteriaArrearsTest {

    private static final String LOCKED = "c.cycle_status = 'LOCKED'";
    private static final String VERSIONS = "e.effective_from, e.effective_to";
    private static final String RUN = "c.legacy_pay_code, rc.amount";
    private static final String EARLIER = "FROM arrear_detail d";
    private static final String SUPPLEMENTARY = "FROM supplementary_payment s";

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final RetroEvents retro = mock(RetroEvents.class);
    private final AllowanceRepositories.Elections elections =
            mock(AllowanceRepositories.Elections.class);
    private final AuditService audit = mock(AuditService.class);
    private final CafeteriaArrears arrears = new CafeteriaArrears(db.jdbc, retro, elections, audit);

    private static CafeteriaElection election() {
        CafeteriaElection e =
                CafeteriaElection.applied(
                        7L,
                        "2026-27",
                        1,
                        LocalDate.of(2026, 5, 1),
                        "HR-1",
                        Instant.parse("2026-07-10T06:00:00Z"),
                        9L);
        org.springframework.test.util.ReflectionTestUtils.setField(e, "id", 21L);
        return e;
    }

    private void selectionFromMay(String monthly) {
        db.rows(
                VERSIONS,
                cols(LocalDate.of(2026, 5, 1), null, "APPLIED", 917, new BigDecimal(monthly)));
    }

    private List<RetroEvents.Line> linesRaised() {
        ArgumentCaptor<RetroEvents.Known> known = ArgumentCaptor.forClass(RetroEvents.Known.class);
        verify(retro).known(known.capture());
        return known.getValue().lines();
    }

    @Test
    void SRS_11_16_aSelectionReceivedAfterTheFreezeRaisesTheArrearOfEachLockedMonth() {
        db.rows(LOCKED, cols("202605"), cols("202606"));
        selectionFromMay("1000");
        when(retro.known(any())).thenReturn(55L);
        CafeteriaElection e = election();

        assertThat(arrears.settle(e, "checker@x", 9L)).contains(55L);

        assertThat(linesRaised())
                .extracting(RetroEvents.Line::yymm, RetroEvents.Line::payCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("202605", 917),
                        org.assertj.core.groups.Tuple.tuple("202606", 917));
        assertThat(e.getStatus()).isEqualTo("ARREAR_RAISED");
        assertThat(e.getArrearEventId()).isEqualTo(55L);
        verify(elections).save(e);
    }

    @Test
    void SRS_11_16_theEventNamesItsRecordAndTheMonthItReaches() {
        db.rows(LOCKED, cols("202605"));
        selectionFromMay("1000");
        when(retro.known(any())).thenReturn(55L);
        arrears.settle(election(), "checker@x", 9L);
        ArgumentCaptor<RetroEvents.Known> known = ArgumentCaptor.forClass(RetroEvents.Known.class);
        verify(retro).known(known.capture());
        assertThat(known.getValue().sourceEntity()).isEqualTo("cafeteria_election");
        assertThat(known.getValue().sourceKey()).isEqualTo("21-202605");
        assertThat(known.getValue().eventCode()).isEqualTo("REIMBURSEMENT");
        assertThat(known.getValue().reason()).contains("received after the freeze");
    }

    @Test
    void SRS_11_16_noLockedMonthRaisesNothing_theOpenRunPaysIt() {
        CafeteriaElection e = election();
        assertThat(arrears.settle(e, "checker@x", 9L)).isEmpty();
        verify(retro, never()).known(any());
        assertThat(e.getStatus()).isEqualTo("APPLIED");
    }

    @Test
    void SRS_11_16_aMonthTheRunPaidInFullRaisesNothing() {
        db.rows(LOCKED, cols("202605"));
        selectionFromMay("1000");
        db.rows(RUN, cols(917, new BigDecimal("1000")));
        CafeteriaElection e = election();
        assertThat(arrears.settle(e, "checker@x", 9L)).isEmpty();
        verify(retro, never()).known(any());
        assertThat(e.getStatus()).isEqualTo("APPLIED");
    }

    @Test
    void SRS_G_33_aSupplementaryCafeteriaPaymentOfTheMonthIsNotPaidAgain() {
        db.rows(LOCKED, cols("202605"));
        selectionFromMay("1000");
        db.rows(SUPPLEMENTARY, cols(917, new BigDecimal("600")));
        when(retro.known(any())).thenReturn(55L);
        arrears.settle(election(), "checker@x", 9L);
        assertThat(linesRaised())
                .extracting(RetroEvents.Line::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("400"));
    }

    @Test
    void SRS_G_33_aSupplementaryPaymentWithoutAPayCodeCountsAgainstWhatIsDue() {
        db.rows(LOCKED, cols("202605"));
        selectionFromMay("1000");
        db.rows(SUPPLEMENTARY, cols(null, new BigDecimal("1000")));
        assertThat(arrears.settle(election(), "checker@x", 9L)).isEmpty();
    }

    @Test
    void SRS_11_16_anEarlierArrearOfTheMonthIsNotPaidAgain() {
        db.rows(LOCKED, cols("202605"));
        selectionFromMay("1500");
        db.rows(EARLIER, cols(917, new BigDecimal("1000")));
        when(retro.known(any())).thenReturn(55L);
        arrears.settle(election(), "checker@x", 9L);
        assertThat(linesRaised())
                .extracting(RetroEvents.Line::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("500"));
    }

    @Test
    void SRS_11_16_aLowerSelectionThanThePaidOneRaisesARecovery() {
        db.rows(LOCKED, cols("202605"));
        selectionFromMay("400");
        db.rows(RUN, cols(917, new BigDecimal("1000")));
        when(retro.known(any())).thenReturn(55L);
        arrears.settle(election(), "checker@x", 9L);
        assertThat(linesRaised())
                .extracting(RetroEvents.Line::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("-600"));
    }

    @Test
    void SRS_11_16_aVersionNotInForceIsOwedNothing_otherPayCodesOfTheRunAreIgnored() {
        db.rows(LOCKED, cols("202605"));
        db.rows(
                VERSIONS,
                cols(LocalDate.of(2026, 5, 1), null, "CANCELLED", 917, new BigDecimal("1000")),
                cols(LocalDate.of(2026, 5, 1), null, "RECEIVED", 918, new BigDecimal("750")));
        db.rows(RUN, cols(101, new BigDecimal("50000")));
        assertThat(arrears.settle(election(), "checker@x", 9L)).isEmpty();
    }

    @Test
    void SRS_11_16_aPartMonthIsDueProRata_aSupersededVersionEndsWhereItEnded() {
        db.rows(LOCKED, cols("202605"));
        db.rows(
                VERSIONS,
                cols(
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 5, 15),
                        "SUPERSEDED",
                        917,
                        new BigDecimal("3100")),
                cols(LocalDate.of(2026, 5, 16), null, "APPLIED", 917, new BigDecimal("6200")));
        db.rows(RUN, cols(917, new BigDecimal("3100")));
        when(retro.known(any())).thenReturn(55L);
        arrears.settle(election(), "checker@x", 9L);
        // 3100 x 15/31 + 6200 x 16/31 = 1500 + 3200 = 4700, of which 3100 were paid.
        assertThat(linesRaised())
                .extracting(RetroEvents.Line::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1600"));
    }

    @Test
    void SRS_11_16_aCycleLockRaisesTheArrearOfASelectionApprovedAfterTheRunWasComputed() {
        db.rows("c.received_at > r.computed_at", cols(21L));
        db.rows(LOCKED, cols("202605"));
        selectionFromMay("1000");
        when(retro.known(any())).thenReturn(55L);
        CafeteriaElection e = election();
        when(elections.findById(21L)).thenReturn(Optional.of(e));
        new CafeteriaLockEffect(db.jdbc, elections, arrears)
                .on(new CycleLocked(3L, 40L, "202605", "checker@x", Instant.now()));
        assertThat(e.getStatus()).isEqualTo("ARREAR_RAISED");
    }

    @Test
    void SRS_11_16_aCycleLockWithNoLateSelectionDoesNothing() {
        new CafeteriaLockEffect(db.jdbc, elections, arrears)
                .on(new CycleLocked(3L, 40L, "202605", "checker@x", Instant.now()));
        verify(retro, never()).known(any());
    }
}
