package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.api.OriginWorkSlips;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * UC M-11 main flow (SRS l.1321, l.1323): the origin holds nothing on or after the transfer date,
 * read through the payroll and labour ports (OD-51).
 */
class TransferOriginTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final OriginWorkSlips slips = mock(OriginWorkSlips.class);
    private final TransferOrigin origin = new TransferOrigin(payroll, slips);

    private void found(LocalDate computed, LocalDate slip, LocalDate from) {
        when(payroll.firstActualPeriodFrom(7L, from)).thenReturn(Optional.ofNullable(computed));
        when(slips.firstFrom(7L, from)).thenReturn(Optional.ofNullable(slip));
    }

    @Test
    void SRS_16_18_theEarliestLaterComputationOrWorkSlipIsNamed() {
        assertThat(TransferOrigin.earliest(null, null)).isEmpty();
        LocalDate a = LocalDate.of(2026, 11, 1);
        LocalDate b = LocalDate.of(2026, 10, 20);
        assertThat(TransferOrigin.earliest(a, null)).contains(a);
        assertThat(TransferOrigin.earliest(null, b)).contains(b);
        assertThat(TransferOrigin.earliest(a, b)).contains(b);
        assertThat(TransferOrigin.earliest(b, a)).contains(b);
        assertThat(TransferOrigin.earliest(a, a)).contains(a);
    }

    @Test
    void SRS_16_18_aClearOriginLetsTheTransferThrough() {
        found(null, null, DAY);
        assertThat(origin.laterRecord(7L, DAY)).isEqualTo(Optional.empty());
        assertThatCode(() -> origin.requireClear(7L, DAY)).doesNotThrowAnyException();
    }

    @Test
    void SRS_16_18_aLaterComputationOrWorkSlipRefusesTheTransferNamingTheEarliest() {
        // SRS-1-65: a transfer is blocked until attendance and payroll at origin are complete up to
        // the transfer date.
        found(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 16), DAY);
        assertThat(origin.laterRecord(7L, DAY)).contains(LocalDate.of(2026, 10, 16));
        assertThatThrownBy(() -> origin.requireClear(7L, DAY))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.M11_ORIGIN_LATER);
                            assertThat(e.getMessageArgs())
                                    .containsExactly("2026-10-16", "2026-10-15");
                        });
    }

    /**
     * OD-51 boundary (Wave A review): a transfer dated the 1st is refused while that month is
     * computed at the origin - the period starts on the transfer date, so it is "after" it.
     */
    @Test
    void SRS_16_18_aTransferOnTheFirstIsRefusedWhileThatMonthIsComputed() {
        LocalDate first = LocalDate.of(2026, 11, 1);
        found(first, null, first);
        assertThatThrownBy(() -> origin.requireClear(7L, first))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getMessageArgs())
                                        .containsExactly("2026-11-01", "2026-11-01"));
    }
}
