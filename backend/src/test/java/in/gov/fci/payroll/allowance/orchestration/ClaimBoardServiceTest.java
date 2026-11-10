package in.gov.fci.payroll.allowance.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The drawn tiles and the Lease / Licence table of I-03 and the breach tile of M-15 (SRS-11-11,
 * SRS-11-17, SRS-16-14; API-10-3): every figure is scoped to the user's offices, under the screen's
 * own VIEW; a lease with HRA not stopped is a conflict; the 7 % figure is never computed here.
 */
class ClaimBoardServiceTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ClaimScope scope = mock(ClaimScope.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final ClaimBoardService board = new ClaimBoardService(db.named, scope, settings);

    ClaimBoardServiceTest() {
        when(settings.today()).thenReturn(LocalDate.of(2026, 11, 7));
        when(scope.offices(any(), any(), any())).thenReturn(Optional.of(Set.of(40L)));
    }

    @Test
    void SRS_11_11_theTilesCountTheMonthsClaimsAndTheRecordsInForce() {
        db.rows(
                "AS claims",
                row("claims", 5L, "payable", new BigDecimal("12000"), "waiting", 2L, "capped", 1L));
        db.value("FROM lease_accommodation_detail", 3L);
        ClaimBoardService.Counts c = board.counts(context);
        assertThat(c.claims()).isEqualTo(5L);
        assertThat(c.payable()).isEqualByComparingTo("12000");
        assertThat(c.pendingApproval()).isEqualTo(2L);
        assertThat(c.capped()).isEqualTo(1L);
        assertThat(c.leases()).isEqualTo(3L);
        verify(scope).offices(context, "I-03", "VIEW");
    }

    @Test
    void SRS_11_11_aMonthWithNoClaimReadsZero() {
        db.rows(
                "AS claims",
                row("claims", 0L, "payable", BigDecimal.ZERO, "waiting", null, "capped", null));
        ClaimBoardService.Counts c = board.counts(context);
        assertThat(c.pendingApproval()).isZero();
        assertThat(c.capped()).isZero();
        assertThat(c.leases()).isZero();
    }

    @Test
    void SRS_16_14_theBreachTileIsOfTheM15RightAndOnlyCappedClaims() {
        db.value("c.was_capped = 'Y'", 4L);
        assertThat(board.ceilingBreaches(context)).isEqualTo(4L);
        verify(scope).offices(eq(context), eq("M-15"), eq("VIEW"));
    }

    @Test
    void RBAC_anOfficeOutsideTheUsersScopeIsRefused() {
        when(scope.offices(any(), any(), any()))
                .thenThrow(new FciException(CommonErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> board.counts(context)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> board.leases(context, new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> board.ceilingBreaches(context)).isInstanceOf(FciException.class);
    }

    @Test
    void RBAC_headquartersReadsEveryOffice() {
        when(scope.offices(any(), any(), any())).thenReturn(Optional.empty());
        db.value("c.was_capped = 'Y'", 9L);
        assertThat(board.ceilingBreaches(context)).isEqualTo(9L);
    }

    @Test
    void SRS_11_17_aLeaseWithHraNotStoppedIsAConflict_quartersStopItWhateverTheFlag() {
        db.value("SELECT COUNT(*)", 3L);
        LocalDate from = LocalDate.of(2026, 4, 1);
        db.rows(
                "a.lease_detail_id",
                cols(
                        1L,
                        "880707",
                        "Farida",
                        "Khan",
                        "STAFF",
                        "THIRD_PARTY",
                        16000,
                        null,
                        "N",
                        "HRMS",
                        null,
                        from),
                cols(
                        2L,
                        "880701",
                        "Neha",
                        null,
                        "STAFF",
                        "DEPARTMENTAL_QUARTERS",
                        null,
                        null,
                        "N",
                        "HRMS",
                        "R-1",
                        from),
                cols(
                        3L,
                        "L10702",
                        "Mohan",
                        "Rawat",
                        "DEPT_LABOUR",
                        "COMPANY_LEASE",
                        12000,
                        900,
                        "Y",
                        "ENT",
                        null,
                        from));
        List<ClaimBoardService.LeaseRow> rows = board.leases(context, new PageSpec(0, 20)).items();
        assertThat(rows.get(0).conflict()).isTrue();
        assertThat(rows.get(0).hraSuppressed()).isFalse();
        assertThat(rows.get(0).sevenPercent()).isNull();
        assertThat(rows.get(0).memberType()).isEqualTo("EMPLOYEE");
        assertThat(rows.get(1).conflict()).isFalse();
        assertThat(rows.get(1).name()).isEqualTo("Neha");
        assertThat(rows.get(2).memberType()).isEqualTo("LABOUR");
        assertThat(rows.get(2).sevenPercent()).isEqualByComparingTo("900");
    }
}
